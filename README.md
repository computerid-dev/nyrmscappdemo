# Web2Apk - Template (GitHub Actions)
Developer: Nugroho Keren (cid)

Template repo ini dipakai website Web2Apk. Website **tidak** menyentuh branch utama: untuk setiap permintaan ia membuat
branch sementara `build-<id>`, commit konfigurasi, lalu menjalankan workflow **Build APK** pada branch itu.
Hasilnya `<nama-app>.apk` muncul di tab **Releases** (tag `build-<id>`), lalu branch sementara dihapus otomatis.
Workflow **Bersihkan build lama** menghapus release dan branch yang berumur lebih dari 3 hari.

## Yang di-commit website per build
| File | Isi |
|---|---|
| `config/app.json` | `appName`, `packageName`, `orientation` (`portrait` / `landscape` / `auto`), `permissions` |
| `config/icon.png` | icon aplikasi |
| `www/index.html`, `www/url.txt` | mode **URL** (redirect) atau mode **HTML** (1 file) |
| `config/www.zip` | mode **ZIP**: dibongkar otomatis, `index.html` dicari di root atau 1 folder pembungkus |

## Kunci izin yang dikenali (`permissions`)
`notification`, `bluetooth`, `storage`, `camera`, `video`, `audio`, `location`, `vibrate`, `wakelock`, `wifi`, `nfc`.
`INTERNET` dan `ACCESS_NETWORK_STATE` selalu ikut. Pemetaan lengkap ada di `.github/scripts/prepare.py`.

## Catatan
- Repo ini harus **public** supaya Actions gratis tanpa batas dan link unduhan release bisa dibuka siapa saja.
- Semua APK ditandatangani `keystore/debug.keystore` yang sama (bawaan template). Cukup untuk dipakai langsung,
  tapi bukan untuk Play Store.
- Notifikasi: WebView tidak punya Web Notification API, jadi app menyediakan polyfill `new Notification(...)` otomatis
  saat izin `notification` dipilih.
- Web Bluetooth API tidak didukung oleh Android WebView; izin Bluetooth hanya dideklarasikan di manifest.
