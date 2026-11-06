package com.webapp.wrapper;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {

    private static final int FILE_CHOOSER_CODE = 1001;
    private static final int REQ_APP_PERMS = 2001;
    private static final int REQ_WEB_PERMS = 2002;
    private static final int REQ_GEO_PERMS = 2003;
    private static final String NOTIF_CHANNEL = "webapp_default";

    private static MainActivity instance;

    private WebView webView;
    private boolean modeLokal = false;
    private String realUrl = "";
    private ValueCallback<Uri[]> filePathCallback;

    // izin yang dideklarasikan di AndroidManifest (diisi website lewat prepare.py)
    private final Set<String> declared = new HashSet<>();

    // permintaan izin dari halaman web yang sedang menunggu jawaban pengguna
    private PermissionRequest pendingWebRequest;
    private GeolocationPermissions.Callback pendingGeoCallback;
    private String pendingGeoOrigin;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        loadDeclaredPermissions();
        realUrl = readAsset("www/url.txt").trim();
        // Mode ZIP/HTML: url.txt kosong. Di mode ini aplikasi hanya membuka file lokal.
        modeLokal = realUrl.isEmpty();
        instance = this;

        // Jalur aman untuk buka file lokal (www/index.html) lewat https://appassets.androidplatform.net
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        if (modeLokal) {
            s.setAllowFileAccess(false);
        }
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // Hapus penanda "; wv" supaya situs dengan proteksi bot tidak menganggap ini WebView
        s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        // Jembatan notifikasi: WebView tidak punya Web Notification API, jadi kita sediakan sendiri
        final boolean notifEnabled = declared.contains("android.permission.POST_NOTIFICATIONS");
        if (notifEnabled) {
            webView.addJavascriptInterface(new WebApkBridge(), "WebApk");
        }

        if (modeLokal) {
            webView.addJavascriptInterface(new NyrBridge(this), "NyrApk");
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String u = request.getUrl().toString();
                if (modeLokal) {
                    if (u.startsWith("https://appassets.androidplatform.net/")) {
                        return false;
                    }
                    try { // link luar dibuka di aplikasi lain, bukan di dalam WebView
                        startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl()));
                    } catch (Exception ignored) { }
                    return true;
                }
                if (u.startsWith("http://") || u.startsWith("https://")) {
                    return false; // tetap dibuka di dalam app
                }
                try { // tel:, mailto:, intent:, dll -> buka aplikasi lain
                    startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl()));
                } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (notifEnabled) {
                    view.evaluateJavascript(NOTIFICATION_POLYFILL, null);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame() && !realUrl.isEmpty()) {
                    String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
                            + "<body style='font-family:sans-serif;text-align:center;padding:40px'>"
                            + "<h2>Gagal memuat halaman</h2><p>Cek koneksi internet kamu.</p>"
                            + "<p><a href='" + TextUtils.htmlEncode(realUrl) + "'>Coba lagi</a></p></body></html>";
                    view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_CHOOSER_CODE);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }

            // getUserMedia: kamera, video, mikrofon
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        handleWebPermissionRequest(request);
                    }
                });
            }

            // navigator.geolocation
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                                                           GeolocationPermissions.Callback callback) {
                if (Build.VERSION.SDK_INT < 23
                        || isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
                        || isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    callback.invoke(origin, true, false);
                    return;
                }
                pendingGeoOrigin = origin;
                pendingGeoCallback = callback;
                requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_GEO_PERMS);
            }
        });

        // Unduhan file (link download biasa; blob:/data: belum didukung)
        webView.setDownloadListener(new android.webkit.DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                startDownload(url, userAgent, contentDisposition, mimeType);
            }
        });

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl("https://appassets.androidplatform.net/assets/www/index.html");
            askDeclaredPermissions();
        }
    }

    // ------------------------------------------------------------------ izin

    private void loadDeclaredPermissions() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_PERMISSIONS);
            if (pi.requestedPermissions != null) {
                declared.addAll(Arrays.asList(pi.requestedPermissions));
            }
        } catch (Exception ignored) { }
    }

    private boolean isGranted(String permission) {
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    /** Izin berbahaya (perlu dialog) yang berlaku untuk versi Android perangkat ini. */
    private boolean isRuntimePermission(String p) {
        int sdk = Build.VERSION.SDK_INT;
        switch (p) {
            case "android.permission.CAMERA":
            case "android.permission.RECORD_AUDIO":
            case "android.permission.ACCESS_FINE_LOCATION":
            case "android.permission.ACCESS_COARSE_LOCATION":
                return true;
            case "android.permission.READ_EXTERNAL_STORAGE":
                return sdk <= 32;
            case "android.permission.WRITE_EXTERNAL_STORAGE":
                return sdk <= 28;
            case "android.permission.READ_MEDIA_IMAGES":
            case "android.permission.READ_MEDIA_VIDEO":
            case "android.permission.READ_MEDIA_AUDIO":
            case "android.permission.POST_NOTIFICATIONS":
                return sdk >= 33;
            case "android.permission.BLUETOOTH_CONNECT":
            case "android.permission.BLUETOOTH_SCAN":
                return sdk >= 31;
            default:
                return false;
        }
    }

    /** Minta semua izin yang dipilih di website saat aplikasi pertama dibuka. */
    private void askDeclaredPermissions() {
        if (Build.VERSION.SDK_INT < 23) return;
        List<String> need = new ArrayList<>();
        for (String p : declared) {
            if (isRuntimePermission(p) && !isGranted(p)) need.add(p);
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQ_APP_PERMS);
        }
    }

    private String androidPermissionFor(String webResource) {
        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(webResource)) return Manifest.permission.CAMERA;
        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(webResource)) return Manifest.permission.RECORD_AUDIO;
        return null;
    }

    private void handleWebPermissionRequest(PermissionRequest request) {
        if (pendingWebRequest != null) {
            pendingWebRequest.deny();
            pendingWebRequest = null;
        }
        List<String> need = new ArrayList<>();
        for (String res : request.getResources()) {
            String p = androidPermissionFor(res);
            if (p != null && !isGranted(p) && !need.contains(p)) need.add(p);
        }
        if (need.isEmpty()) {
            answerWebRequest(request);
        } else {
            pendingWebRequest = request;
            requestPermissions(need.toArray(new String[0]), REQ_WEB_PERMS);
        }
    }

    /** Izinkan hanya resource yang izin Android-nya sudah diberikan. */
    private void answerWebRequest(PermissionRequest request) {
        List<String> ok = new ArrayList<>();
        for (String res : request.getResources()) {
            String p = androidPermissionFor(res);
            if (p != null && isGranted(p)) ok.add(res);
        }
        if (ok.isEmpty()) {
            request.deny();
        } else {
            request.grant(ok.toArray(new String[0]));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_WEB_PERMS && pendingWebRequest != null) {
            PermissionRequest r = pendingWebRequest;
            pendingWebRequest = null;
            answerWebRequest(r);
        } else if (requestCode == REQ_GEO_PERMS && pendingGeoCallback != null) {
            boolean ok = false;
            for (int g : grantResults) {
                if (g == PackageManager.PERMISSION_GRANTED) ok = true;
            }
            pendingGeoCallback.invoke(pendingGeoOrigin, ok, false);
            pendingGeoCallback = null;
            pendingGeoOrigin = null;
        }
    }

    // ------------------------------------------------------------- unduhan

    private void startDownload(String url, String userAgent, String contentDisposition, String mimeType) {
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            Toast.makeText(this, "Jenis unduhan ini belum didukung", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            if (mimeType != null && !mimeType.isEmpty()) req.setMimeType(mimeType);
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) req.addRequestHeader("cookie", cookies);
            if (userAgent != null) req.addRequestHeader("User-Agent", userAgent);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            dm.enqueue(req);
            Toast.makeText(this, "Mengunduh: " + fileName, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            try { // cadangan: serahkan ke browser/aplikasi lain
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception ignored) { }
        }
    }

    // ----------------------------------------------------------- notifikasi

    private static final String NOTIFICATION_POLYFILL =
            "(function(){"
            + "if(!window.WebApk||window.__webapkNotif)return;window.__webapkNotif=1;"
            + "function N(t,o){o=o||{};try{WebApk.showNotification(String(t),String(o.body||''));}catch(e){}}"
            + "N.permission='granted';"
            + "N.requestPermission=function(cb){if(cb)cb('granted');return Promise.resolve('granted');};"
            + "window.Notification=N;"
            + "})();";

    private class WebApkBridge {
        @JavascriptInterface
        public void showNotification(String title, String body) {
            try {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm == null) return;
                if (Build.VERSION.SDK_INT >= 26) {
                    nm.createNotificationChannel(new NotificationChannel(
                            NOTIF_CHANNEL, "Notifikasi", NotificationManager.IMPORTANCE_DEFAULT));
                }
                Intent open = new Intent(MainActivity.this, MainActivity.class);
                open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                PendingIntent pi = PendingIntent.getActivity(MainActivity.this, 0, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                Notification.Builder b = Build.VERSION.SDK_INT >= 26
                        ? new Notification.Builder(MainActivity.this, NOTIF_CHANNEL)
                        : new Notification.Builder(MainActivity.this);
                b.setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(title)
                        .setContentText(body)
                        .setContentIntent(pi)
                        .setAutoCancel(true);
                nm.notify((int) (System.currentTimeMillis() & 0x7fffffff), b.build());
            } catch (Exception ignored) { }
        }
    }

    // --------------------------------------------------------------- lainnya

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_CODE && filePathCallback != null) {
            filePathCallback.onReceiveValue(
                    WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            filePathCallback = null;
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else if (MusicService.aktif) {
            moveTaskToBack(true); // musik sedang aktif: pindah ke latar belakang, jangan tutup
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) {
            try {
                stopService(new Intent(this, MusicService.class));
            } catch (Exception ignored) { }
        }
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------- jembatan ke musik & jendela

    /** Jalankan JavaScript di WebView (aman dipanggil dari thread mana pun). */
    void jalankanJs(final String js) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (webView != null) {
                    webView.evaluateJavascript(js, null);
                }
            }
        });
    }

    /** Dipanggil layanan musik saat tombol notifikasi ditekan. cmd hanya dari daftar tetap. */
    static void kirimKontrol(String cmd) {
        MainActivity a = instance;
        if (a != null) {
            a.jalankanJs("window.NYRKontrol&&window.NYRKontrol('" + cmd + "')");
        }
    }

    /** Sekali saja: minta izin agar musik tidak dibatasi penghemat baterai. */
    void mintaBateraiSekali() {
        SharedPreferences sp = getSharedPreferences("nyr", MODE_PRIVATE);
        if (sp.getBoolean("baterai_ditanya", false)) {
            return;
        }
        sp.edit().putBoolean("baterai_ditanya", true).apply();
        mintaBaterai(false);
    }

    void mintaBaterai(final boolean tampilkanPesan) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (Build.VERSION.SDK_INT >= 23) {
                        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                            if (tampilkanPesan) {
                                Toast.makeText(MainActivity.this, "Sudah diizinkan berjalan di latar belakang", Toast.LENGTH_SHORT).show();
                            }
                            return;
                        }
                        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName())));
                    }
                } catch (Exception e) {
                    try {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    } catch (Exception ignored) { }
                }
            }
        });
    }

    /** Jendela mengambang asli Android (Picture-in-Picture), Android 8 ke atas. */
    void masukJendelaMengambang() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (Build.VERSION.SDK_INT >= 26) {
                        enterPictureInPictureMode(new android.app.PictureInPictureParams.Builder().build());
                    } else {
                        Toast.makeText(MainActivity.this, "Jendela mengambang butuh Android 8 ke atas", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Jendela mengambang tidak tersedia", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, android.content.res.Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        jalankanJs("document.body.classList.toggle('pip'," + isInPictureInPictureMode + ")");
    }

    private String readAsset(String path) {
        try (InputStream in = getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
