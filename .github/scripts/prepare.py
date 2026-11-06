"""Menyiapkan project Android dari config yang di-commit oleh website.

Yang dilakukan:
  1. nama app            -> strings.xml
  2. package name        -> applicationId
  3. orientasi + izin    -> AndroidManifest.xml
  4. isi aplikasi        -> www/ (URL, 1 file HTML, atau ZIP) lalu disalin ke assets
  5. icon                -> semua ukuran mipmap
"""
import json, os, re, shutil, subprocess, sys, tempfile, zipfile
from xml.sax.saxutils import escape

JAVA_KW = {"abstract","assert","boolean","break","byte","case","catch","char","class","const",
    "continue","default","do","double","else","enum","extends","final","finally","float","for",
    "goto","if","implements","import","instanceof","int","interface","long","native","new",
    "package","private","protected","public","return","short","static","strictfp","super",
    "switch","synchronized","this","throw","throws","transient","try","void","volatile","while"}

# website kirim: portrait | landscape | auto
ORIENTATIONS = {"portrait": "portrait", "landscape": "landscape", "auto": "unspecified"}

P = "android.permission."
MAX_API = 'android:maxSdkVersion="%d"'

# key dari website -> izin manifest [(nama, atribut tambahan)], dan fitur hardware (selalu required=false)
PERMISSIONS = {
    "notification": {"perms": [(P + "POST_NOTIFICATIONS", "")]},
    "bluetooth": {"perms": [
        (P + "BLUETOOTH", MAX_API % 30),
        (P + "BLUETOOTH_ADMIN", MAX_API % 30),
        (P + "BLUETOOTH_CONNECT", ""),
        (P + "BLUETOOTH_SCAN", 'android:usesPermissionFlags="neverForLocation"'),
    ], "features": ["android.hardware.bluetooth"]},
    "storage": {"perms": [
        (P + "READ_EXTERNAL_STORAGE", MAX_API % 32),
        (P + "WRITE_EXTERNAL_STORAGE", MAX_API % 28),
        (P + "READ_MEDIA_IMAGES", ""),
        (P + "READ_MEDIA_VIDEO", ""),
        (P + "READ_MEDIA_AUDIO", ""),
    ]},
    "camera": {"perms": [(P + "CAMERA", "")],
               "features": ["android.hardware.camera", "android.hardware.camera.autofocus"]},
    "video": {"perms": [(P + "CAMERA", ""), (P + "RECORD_AUDIO", "")],
              "features": ["android.hardware.camera", "android.hardware.microphone"]},
    "audio": {"perms": [(P + "RECORD_AUDIO", ""), (P + "MODIFY_AUDIO_SETTINGS", "")],
              "features": ["android.hardware.microphone"]},
    "location": {"perms": [(P + "ACCESS_FINE_LOCATION", ""), (P + "ACCESS_COARSE_LOCATION", "")],
                 "features": ["android.hardware.location.gps"]},
    "vibrate": {"perms": [(P + "VIBRATE", "")]},
    "wakelock": {"perms": [(P + "WAKE_LOCK", "")]},
    "wifi": {"perms": [(P + "ACCESS_WIFI_STATE", "")]},
    "nfc": {"perms": [(P + "NFC", "")], "features": ["android.hardware.nfc"]},
}

MAX_ZIP_FILES = 5000
MAX_ZIP_BYTES = 80 * 1024 * 1024


def die(msg):
    sys.exit("ERROR: " + msg)


with open("config/app.json", encoding="utf-8") as f:
    cfg = json.load(f)

name = str(cfg.get("appName", "Web App")).strip()[:50] or "Web App"
pkg = str(cfg.get("packageName", "")).strip()

if not re.fullmatch(r"[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+", pkg) or any(p in JAVA_KW for p in pkg.split(".")):
    die("Package name tidak valid: %r (contoh benar: com.nama.app)" % pkg)

orientation = str(cfg.get("orientation", "auto")).strip().lower()
if orientation not in ORIENTATIONS:
    die("Orientasi tidak dikenal: %r (pilih: portrait, landscape, auto)" % orientation)

perm_keys = cfg.get("permissions", [])
if not isinstance(perm_keys, list):
    die("'permissions' harus berupa daftar")
perm_keys = list(dict.fromkeys(str(k).strip().lower() for k in perm_keys))
unknown = [k for k in perm_keys if k not in PERMISSIONS]
if unknown:
    die("Izin tidak dikenal: %s" % ", ".join(unknown))

# 1) Nama app -> strings.xml (di-escape)
safe = escape(name).replace("'", "\\'").replace('"', '\\"')
if safe.startswith("@") or safe.startswith("?"):
    safe = "\\" + safe
with open("app/src/main/res/values/strings.xml", "w", encoding="utf-8") as f:
    f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
            '    <string name="app_name">%s</string>\n</resources>\n' % safe)

# 2) Package name -> applicationId
path = "app/build.gradle"
text = open(path, encoding="utf-8").read()
text = re.sub(r'applicationId\s+".*?"', 'applicationId "%s"' % pkg, text, count=1)
open(path, "w", encoding="utf-8").write(text)

# 3) Orientasi + izin -> AndroidManifest.xml
perms = {P + "INTERNET": "", P + "ACCESS_NETWORK_STATE": ""}  # selalu ada
features = {}
for key in perm_keys:
    for perm_name, attrs in PERMISSIONS[key]["perms"]:
        perms.setdefault(perm_name, attrs)
    for feat in PERMISSIONS[key].get("features", []):
        features.setdefault(feat, True)

lines = []
for perm_name, attrs in perms.items():
    lines.append('    <uses-permission android:name="%s"%s />' % (perm_name, (" " + attrs) if attrs else ""))
for feat in features:
    lines.append('    <uses-feature android:name="%s" android:required="false" />' % feat)
block = "\n".join(lines)

mpath = "app/src/main/AndroidManifest.xml"
mtext = open(mpath, encoding="utf-8").read()
if "<!-- PERMISSIONS:BEGIN -->" not in mtext or "<!-- PERMISSIONS:END -->" not in mtext:
    die("Penanda PERMISSIONS:BEGIN/END tidak ada di AndroidManifest.xml")
mtext = re.sub(r"(<!-- PERMISSIONS:BEGIN -->).*?(<!-- PERMISSIONS:END -->)",
               lambda m: m.group(1) + "\n" + block + "\n    " + m.group(2), mtext, flags=re.S)
mtext, n = re.subn(r'android:screenOrientation="[^"]*"',
                   'android:screenOrientation="%s"' % ORIENTATIONS[orientation], mtext, count=1)
if n != 1:
    die("android:screenOrientation tidak ditemukan di AndroidManifest.xml")
open(mpath, "w", encoding="utf-8").write(mtext)


# 4) Isi aplikasi. Mode ZIP: config/www.zip dibongkar ke www/
def use_zip(zpath):
    tmp = tempfile.mkdtemp()
    total = 0
    with zipfile.ZipFile(zpath) as z:
        infos = z.infolist()
        if len(infos) > MAX_ZIP_FILES:
            die("ZIP terlalu banyak file (maks %d)" % MAX_ZIP_FILES)
        for info in infos:
            n = info.filename.replace("\\", "/")
            parts = [p for p in n.split("/") if p not in ("", ".")]
            if not parts or parts[0] == "__MACOSX":
                continue
            if n.startswith("/") or ".." in parts or re.match(r"^[A-Za-z]:", parts[0]):
                die("ZIP tidak aman (path mencurigakan): %s" % info.filename)
            total += info.file_size
            if total > MAX_ZIP_BYTES:
                die("Isi ZIP terlalu besar setelah dibongkar (maks 80 MB)")
            dest = os.path.join(tmp, *parts)
            if info.is_dir():
                os.makedirs(dest, exist_ok=True)
                continue
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with z.open(info) as src, open(dest, "wb") as out:
                shutil.copyfileobj(src, out)

    # cari index.html paling dangkal (root, atau 1 folder pembungkus)
    best = None
    for root, _dirs, files in os.walk(tmp):
        if "index.html" in files:
            depth = os.path.relpath(root, tmp).count(os.sep) if root != tmp else -1
            if best is None or depth < best[0]:
                best = (depth, root)
    if best is None:
        die("index.html tidak ditemukan di dalam ZIP")

    shutil.rmtree("www", ignore_errors=True)
    shutil.copytree(best[1], "www")
    with open("www/url.txt", "w", encoding="utf-8") as f:
        f.write("\n")
    print("ZIP dibongkar, root: %s" % os.path.relpath(best[1], tmp))


if os.path.exists("config/www.zip"):
    use_zip("config/www.zip")

for need in ("www/index.html", "www/url.txt"):
    if not os.path.exists(need):
        die("File wajib tidak ada: " + need)
shutil.copytree("www", "app/src/main/assets/www", dirs_exist_ok=True)

# 5) Icon -> semua ukuran mipmap
icon = "config/icon.png"
if not os.path.exists(icon):
    die("config/icon.png tidak ada")
im = shutil.which("magick") or shutil.which("convert")
if not im:
    die("ImageMagick tidak ditemukan")
sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
for dpi, px in sizes.items():
    d = "app/src/main/res/mipmap-%s" % dpi
    os.makedirs(d, exist_ok=True)
    subprocess.check_call([im, icon, "-resize", "%dx%d^" % (px, px), "-gravity", "center",
                           "-extent", "%dx%d" % (px, px), "PNG32:%s/ic_launcher.png" % d])

# 6) Nama file APK untuk langkah workflow berikutnya
slug = re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")[:40] or "app"
gh_env = os.environ.get("GITHUB_ENV")
if gh_env:
    with open(gh_env, "a", encoding="utf-8") as f:
        f.write("APK_FILE=%s.apk\n" % slug)

print("OK -> app: %s | package: %s | orientasi: %s | izin: %s"
      % (name, pkg, orientation, ", ".join(perm_keys) or "(dasar saja)"))
