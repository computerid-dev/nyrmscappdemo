package com.webapp.wrapper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.Iterator;

/** Pembantu HTTP native: dipakai jembatan supaya JavaScript bisa memanggil situs tanpa terhalang CORS. */
final class NyrHttp {

    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private NyrHttp() { }

    /** Hanya alamat publik: menolak localhost, jaringan lokal, dan alamat privat. */
    static boolean hostAman(String host) {
        try {
            InetAddress[] semua = InetAddress.getAllByName(host);
            for (InetAddress a : semua) {
                if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                        || a.isSiteLocalAddress() || a.isMulticastAddress()) {
                    return false;
                }
            }
            return semua.length > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean headerDiizinkan(String nama) {
        String n = nama.toLowerCase();
        return !(n.equals("host") || n.equals("content-length") || n.equals("connection")
                || n.equals("transfer-encoding") || n.equals("upgrade"));
    }

    /** Membuka koneksi https. Pemanggil bertugas menutup (disconnect). */
    static HttpURLConnection buka(String alamat, String metode, String headerJson,
                                  int connectMs, int readMs) throws Exception {
        URL u = new URL(alamat);
        if (!"https".equalsIgnoreCase(u.getProtocol())) {
            throw new Exception("hanya https yang diizinkan");
        }
        if (!hostAman(u.getHost())) {
            throw new Exception("alamat privat atau tidak ditemukan");
        }
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setRequestMethod(metode);
        c.setConnectTimeout(connectMs);
        c.setReadTimeout(readMs);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Language", "id-ID,id;q=0.9,en;q=0.8");
        if (headerJson != null && headerJson.length() > 2) {
            JSONObject h = new JSONObject(headerJson);
            Iterator<String> it = h.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (headerDiizinkan(k)) {
                    c.setRequestProperty(k, h.getString(k));
                }
            }
        }
        return c;
    }

    static String bacaTeks(InputStream in, int maks) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        int total = 0;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > maks) {
                throw new Exception("respons terlalu besar");
            }
            out.write(buf, 0, n);
        }
        return out.toString("UTF-8");
    }
}
