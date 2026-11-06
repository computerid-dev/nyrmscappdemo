package com.webapp.wrapper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Layanan latar depan (foreground) supaya musik tidak dimatikan sistem saat layar mati atau
 * aplikasi di latar belakang. Memegang wake lock dan wifi lock selama lagu diputar.
 * Berhenti hanya jika dimatikan dari notifikasi, aplikasi ditutup, atau HP mati.
 */
public class MusicService extends Service {

    static final String AKSI_MULAI = "com.webapp.wrapper.MUSIK_MULAI";
    static final String AKSI_TOGGLE = "com.webapp.wrapper.MUSIK_TOGGLE";
    static final String AKSI_PREV = "com.webapp.wrapper.MUSIK_PREV";
    static final String AKSI_NEXT = "com.webapp.wrapper.MUSIK_NEXT";
    static final String AKSI_MATIKAN = "com.webapp.wrapper.MUSIK_MATIKAN";

    /** true selama layanan berjalan di latar depan. */
    static volatile boolean aktif = false;

    private static final String KANAL = "nyr_musik";
    private static final int ID_NOTIF = 4101;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private String judul = "NYR Musik";
    private String sub = "";
    private boolean main = false;

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(KANAL, "Pemutar musik",
                        NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String aksi = intent == null ? null : intent.getAction();
        if (aksi == null) {
            berhenti();
            return START_NOT_STICKY;
        }
        if (AKSI_MULAI.equals(aksi)) {
            String j = intent.getStringExtra("judul");
            String s = intent.getStringExtra("sub");
            judul = (j == null || j.length() == 0) ? "NYR Musik" : j;
            sub = s == null ? "" : s;
            main = intent.getBooleanExtra("main", false);
            tampilkan();
        } else if (AKSI_MATIKAN.equals(aksi)) {
            MainActivity.kirimKontrol("matikan");
            berhenti();
        } else if (AKSI_TOGGLE.equals(aksi)) {
            MainActivity.kirimKontrol("toggle");
        } else if (AKSI_PREV.equals(aksi)) {
            MainActivity.kirimKontrol("prev");
        } else if (AKSI_NEXT.equals(aksi)) {
            MainActivity.kirimKontrol("next");
        }
        return START_NOT_STICKY;
    }

    /** Aplikasi digeser dari daftar terbaru = pengguna mematikan musik. */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        berhenti();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        lepasKunci();
        aktif = false;
        super.onDestroy();
    }

    private PendingIntent aksiIntent(String aksi, int kode) {
        Intent i = new Intent(this, MusicService.class);
        i.setAction(aksi);
        return PendingIntent.getService(this, kode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification bangun() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, KANAL)
                : new Notification.Builder(this);
        Intent buka = new Intent(this, MainActivity.class);
        buka.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent piBuka = PendingIntent.getActivity(this, 0, buka,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(judul)
                .setContentText(sub.length() > 0 ? sub : (main ? "Sedang diputar" : "Dijeda"))
                .setContentIntent(piBuka)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        b.addAction(android.R.drawable.ic_media_previous, "Sebelumnya", aksiIntent(AKSI_PREV, 1));
        b.addAction(main ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                main ? "Jeda" : "Putar", aksiIntent(AKSI_TOGGLE, 2));
        b.addAction(android.R.drawable.ic_media_next, "Berikutnya", aksiIntent(AKSI_NEXT, 3));
        b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Matikan", aksiIntent(AKSI_MATIKAN, 4));
        b.setStyle(new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }

    /** Selalu memanggil startForeground (aman dipanggil berulang) supaya sistem tidak menganggap lalai. */
    private void tampilkan() {
        Notification n = bangun();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID_NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(ID_NOTIF, n);
        }
        aktif = true;
        if (main) {
            ambilKunci();
        } else {
            lepasKunci();
        }
    }

    private void ambilKunci() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && (wakeLock == null || !wakeLock.isHeld())) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nyrmusik:putar");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && (wifiLock == null || !wifiLock.isHeld())) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "nyrmusik:wifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception ignored) {
            // kunci opsional: musik tetap jalan walau gagal
        }
    }

    private void lepasKunci() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Exception ignored) {
            // abaikan
        }
    }

    private void berhenti() {
        lepasKunci();
        try {
            stopForeground(true);
        } catch (Exception ignored) {
            // abaikan
        }
        aktif = false;
        stopSelf();
    }
}
