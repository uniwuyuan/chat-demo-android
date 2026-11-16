#!/usr/bin/env bash
# 把 chat-demo 的静态产物打成可安装的 Android APK。
# 本机是 arm64，Android 官方 build-tools 只有 x86_64 原生程序，
# 所以这里用 apt 的 aapt/zipalign/apksigner（arm64）+ 官方 d8.jar（纯 Java）组合。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
PLATFORM="${PLATFORM:-/root/sdk-dl/android-34/android.jar}"
D8_JAR="${D8_JAR:-/root/sdk-dl/android-14/lib/d8.jar}"
SRC="$ROOT/app/src/main"
OUT="$ROOT/build"
DIST="$ROOT/dist"
WEB="${WEB:-/root/workspace/chat-demo/dist}"

VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-0.1.1}"

# 1) 网页产物 → assets
if [ -d "$WEB" ]; then
  rm -rf "$SRC/assets"
  mkdir -p "$SRC/assets"
  cp -r "$WEB/." "$SRC/assets/"
  mkdir -p "$SRC/assets/shell"
  cp "$ROOT/app/patch.js" "$SRC/assets/shell/patch.js"
fi

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$DIST"

echo "[1/5] aapt 打包资源 + assets"
aapt package -f -m -J "$OUT/gen" \
  -M "$SRC/AndroidManifest.xml" \
  -S "$SRC/res" \
  -A "$SRC/assets" \
  -I "$PLATFORM" \
  -F "$OUT/base.apk"

echo "[2/5] javac"
find "$SRC/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac --release 11 -Xlint:-options -classpath "$PLATFORM" -d "$OUT/classes" @"$OUT/sources.txt"

echo "[3/5] d8 → classes.dex"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
java -cp "$D8_JAR" com.android.tools.r8.D8 \
  --lib "$PLATFORM" --min-api 24 --output "$OUT/dex" $(cat "$OUT/classes.txt")

echo "[4/5] 装入 dex 并 zipalign"
python3 - "$OUT/base.apk" "$OUT/dex/classes.dex" "$OUT/unsigned.apk" <<'PY'
import shutil, sys, zipfile
src, dex, dst = sys.argv[1:4]
shutil.copyfile(src, dst)
with zipfile.ZipFile(dst, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
PY
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[5/5] apksigner 签名"
KS="$ROOT/keystore.jks"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -v -keystore "$KS" -alias chatdemo \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass chatdemo -keypass chatdemo \
    -dname "CN=Little Code Sauce, OU=Demo, O=chat-demo, C=CN" >/dev/null 2>&1
fi
apksigner sign --ks "$KS" --ks-pass pass:chatdemo --key-pass pass:chatdemo \
  --min-sdk-version 24 --v1-signing-enabled true --v2-signing-enabled true \
  --out "$DIST/chat-demo-$VERSION_NAME.apk" "$OUT/aligned.apk"
apksigner verify --print-certs "$DIST/chat-demo-$VERSION_NAME.apk" | head -3

cp "$DIST/chat-demo-$VERSION_NAME.apk" "/sdcard/Download/DSH-Go/chat-demo-$VERSION_NAME.apk"
ls -lh "$DIST/chat-demo-$VERSION_NAME.apk"
