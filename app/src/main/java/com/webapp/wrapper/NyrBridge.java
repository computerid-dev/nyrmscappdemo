package com.webapp.wrapper;

import android.content.ContentValues;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;

/**
 * Jembatan JavaScript ke Android. Hanya dipasang saat aplikasi memuat file lokal (mode ZIP),
 * dan navigasi ke situs luar diblokir di MainActivity, jadi hanya kode aplikasi yang bisa memanggilnya.
 */
public class NyrBridge {

    private static final long MAKS_BINER = 80L * 1024L * 1024L;
    private static final int UKURAN_POTONG = 256 * 1024;

    private final MainActivity act;
    private final Object kunciSimpan = new Object();
    private OutputStream outSimpan;
    private Uri uriSimpan;
    private File fileSimpan;
    private String namaSimpan = "";

    NyrBridge(MainActivity act) {
        this.act = act;
    }

    private static String pesan(Exception e) {
        String m = e.getMessage();
        return (m == null || m.length() == 0) ? e.getClass().getSimpleName() : m;
    }

    private void js(String kode) {
        act.jalankanJs(kode);
    }

    private void kirimGalat(int id, Exception e) {
        js("window.NYRNative&&NYRNative.galat(" + id + "," + JSONObject.quote(pesan(e)) + ")");
    }

    // ------------------------------------------------------------ HTTP tanpa CORS

    /** Ambil teks (HTML/JSON). Hasil dikirim balik lewat NYRNative.teks(id, status, isi). */
    @JavascriptInterface
    public void ambilTeks(final int id, final String url, final String metode,
                          final String headerJson, final String body) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection c = null;
                try {
                    String m = "POST".equalsIgnoreCase(metode) ? "POST" : "GET";
                    c = NyrHttp.buka(url, m, headerJson, 12000, 20000);
                    if ("POST".equals(m) && body != null && body.length() > 0) {
                        byte[] data = body.getBytes("UTF-8");
                        c.setDoOutput(true);
                        c.setFixedLengthStreamingMode(data.length);
                        OutputStream o = c.getOutputStream();
                        o.write(data);
                        o.flush();
                        o.close();
                    }
                    int status = c.getResponseCode();
                    InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
                    String teks = in == null ? "" : NyrHttp.bacaTeks(in, 6 * 1024 * 1024);
                    js("window.NYRNative&&NYRNative.teks(" + id + "," + status + "," + JSONObject.quote(teks) + ")");
                } catch (Exception e) {
                    kirimGalat(id, e);
                } finally {
                    if (c != null) {
                        c.disconnect();
                    }
                }
            }
        }).start();
    }

    private void kirimPotong(int id, byte[] buf, int n) {
        String b64 = Base64.encodeToString(buf, 0, n, Base64.NO_WRAP);
        js("window.NYRNative&&NYRNative.chunk(" + id + ",'" + b64 + "',false)");
    }

    /** Ambil file biner (lagu) potong demi potong lewat NYRNative.mulai/chunk. */
    @JavascriptInterface
    public void ambilBiner(final int id, final String url) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection c = null;
                try {
                    c = NyrHttp.buka(url, "GET", "{}", 12000, 30000);
                    int status = c.getResponseCode();
                    if (status < 200 || status >= 300) {
                        throw new Exception("HTTP " + status);
                    }
                    int total = c.getContentLength();
                    if (total > MAKS_BINER) {
                        throw new Exception("file terlalu besar (maks 80 MB)");
                    }
                    js("window.NYRNative&&NYRNative.mulai(" + id + "," + total + ")");
                    InputStream in = c.getInputStream();
                    byte[] buf = new byte[UKURAN_POTONG];
                    int pos = 0;
                    long dapat = 0;
                    while (true) {
                        int n = in.read(buf, pos, buf.length - pos);
                        if (n == -1) {
                            break;
                        }
                        pos += n;
                        dapat += n;
                        if (dapat > MAKS_BINER) {
                            throw new Exception("file terlalu besar (maks 80 MB)");
                        }
                        if (pos == buf.length) {
                            kirimPotong(id, buf, pos);
                            pos = 0;
                        }
                    }
                    if (pos > 0) {
                        kirimPotong(id, buf, pos);
                    }
                    js("window.NYRNative&&NYRNative.chunk(" + id + ",'',true)");
                } catch (Exception e) {
                    kirimGalat(id, e);
                } finally {
                    if (c != null) {
                        c.disconnect();
                    }
                }
            }
        }).start();
    }

    // ------------------------------------------------------- simpan ke folder Download

    private static String bersihkanNama(String nama) {
        String n = nama == null ? "" : nama.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "").trim();
        if (n.length() > 120) {
            n = n.substring(0, 120);
        }
        return n.length() == 0 ? "lagu" : n;
    }

    private static File unik(File dir, String nama) {
        File f = new File(dir, nama);
        if (!f.exists()) {
            return f;
        }
        int titik = nama.lastIndexOf('.');
        String dasar = titik > 0 ? nama.substring(0, titik) : nama;
        String ext = titik > 0 ? nama.substring(titik) : "";
        for (int i = 1; i < 1000; i++) {
            File g = new File(dir, dasar + " (" + i + ")" + ext);
            if (!g.exists()) {
                return g;
            }
        }
        return f;
    }

    private void tutupSenyap() {
        try {
            if (outSimpan != null) {
                outSimpan.close();
            }
        } catch (Exception ignored) {
            // abaikan
        }
        outSimpan = null;
        try {
            if (uriSimpan != null) {
                act.getContentResolver().delete(uriSimpan, null, null);
            }
            if (fileSimpan != null) {
                fileSimpan.delete();
            }
        } catch (Exception ignored) {
            // abaikan
        }
        uriSimpan = null;
        fileSimpan = null;
    }

    @JavascriptInterface
    public boolean simpanMulai(String nama, String mime) {
        synchronized (kunciSimpan) {
            tutupSenyap();
            try {
                String aman = bersihkanNama(nama);
                String tipe = (mime == null || mime.length() == 0) ? "application/octet-stream" : mime;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, aman);
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, tipe);
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    uriSimpan = act.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uriSimpan == null) {
                        return false;
                    }
                    outSimpan = act.getContentResolver().openOutputStream(uriSimpan);
                } else {
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!dir.exists()) {
                        dir.mkdirs();
                    }
                    fileSimpan = unik(dir, aman);
                    outSimpan = new FileOutputStream(fileSimpan);
                }
                namaSimpan = aman;
                return outSimpan != null;
            } catch (Exception e) {
                tutupSenyap();
                return false;
            }
        }
    }

    @JavascriptInterface
    public boolean simpanTulis(String base64) {
        synchronized (kunciSimpan) {
            if (outSimpan == null) {
                return false;
            }
            try {
                outSimpan.write(Base64.decode(base64, Base64.DEFAULT));
                return true;
            } catch (Exception e) {
                tutupSenyap();
                return false;
            }
        }
    }

    /** Mengembalikan nama file yang tersimpan, atau teks kosong jika gagal. */
    @JavascriptInterface
    public String simpanSelesai() {
        synchronized (kunciSimpan) {
            if (outSimpan == null) {
                return "";
            }
            try {
                outSimpan.flush();
                outSimpan.close();
                outSimpan = null;
                if (Build.VERSION.SDK_INT >= 29 && uriSimpan != null) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    act.getContentResolver().update(uriSimpan, cv, null, null);
                } else if (fileSimpan != null) {
                    MediaScannerConnection.scanFile(act, new String[]{fileSimpan.getAbsolutePath()}, null, null);
                }
                String hasil = namaSimpan;
                uriSimpan = null;
                fileSimpan = null;
                return hasil;
            } catch (Exception e) {
                tutupSenyap();
                return "";
            }
        }
    }

    // ------------------------------------------------------------ musik di latar belakang

    /** Mulai atau perbarui layanan musik. main=true saat lagu sedang diputar. */
    @JavascriptInterface
    public void musik(String judul, String sub, boolean main) {
        try {
            Intent i = new Intent(act, MusicService.class);
            i.setAction(MusicService.AKSI_MULAI);
            i.putExtra("judul", judul == null ? "" : judul);
            i.putExtra("sub", sub == null ? "" : sub);
            i.putExtra("main", main);
            if (MusicService.aktif || Build.VERSION.SDK_INT < 26) {
                act.startService(i);
            } else {
                act.startForegroundService(i);
            }
            if (main) {
                act.mintaBateraiSekali();
            }
        } catch (Exception ignored) {
            // musik tetap jalan di aplikasi walau layanan latar gagal dimulai
        }
    }

    @JavascriptInterface
    public void musikBerhenti() {
        try {
            act.stopService(new Intent(act, MusicService.class));
        } catch (Exception ignored) {
            // abaikan
        }
    }

    @JavascriptInterface
    public void izinBaterai() {
        act.mintaBaterai(true);
    }

    @JavascriptInterface
    public void jendela() {
        act.masukJendelaMengambang();
    }
}
