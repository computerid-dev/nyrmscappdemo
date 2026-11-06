# NYR Musik - catatan build APK

Repo ini adalah template Web2Apk yang sudah diisi aplikasi NYR Musik (versi demo, JavaScript biasa).

## Cara build
1. Push seluruh isi repo ke GitHub (repo **public**).
2. Tab **Actions** > **Build APK** > **Run workflow** (isi `build_id` bebas, misalnya `nyr1`).
3. APK muncul di tab **Releases** dengan tag `build-<build_id>`.

## Yang diubah dari template
- `config/app.json`: nama NYR Musik, package `com.nyr.musik`, rotasi `auto`, semua 11 kunci izin.
- `config/www.zip`: isi aplikasi (layar loading, nyr-msc-strm/, service worker, manifest).
- `config/icon.png`: ikon NYR Musik.
- `MainActivity.java`: mode lokal (navigasi ke situs luar diblokir), jembatan `NyrApk`, tombol Back memindahkan ke
  latar belakang saat musik aktif, jendela mengambang (Picture-in-Picture), izin baterai.
- `NyrBridge.java`: HTTP lewat Android (tanpa CORS), simpan ke folder Download, kontrol layanan musik.
- `NyrHttp.java`: pembantu HTTP (hanya https, menolak alamat privat).
- `MusicService.java`: layanan latar depan + wake lock + wifi lock + notifikasi pemutar.
- `AndroidManifest.xml`: izin tambahan FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK,
  REQUEST_IGNORE_BATTERY_OPTIMIZATIONS di luar blok yang ditimpa prepare.py; layanan musik; PiP; singleTask;
  cleartext traffic dimatikan.

## Memangkas izin
Hapus kunci yang tidak dipakai dari `permissions` di `config/app.json` (camera, video, audio, location, bluetooth, nfc
tidak dipakai oleh pemutar musik).
