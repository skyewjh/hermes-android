#!/usr/bin/env bash
# Builds the Hermes Android APK without Gradle.
#
# Why no Gradle: this app has zero third-party dependencies (plain Java +
# WebView), so aapt2 -> javac -> d8 -> apksigner is the whole toolchain and
# avoids a ~130 MB Gradle download. Every step below uses the Android SDK
# build-tools that ship with Android Studio.
#
# Usage: bash tools/build_apk.sh
set -euo pipefail

ROOT="D:/hermes_android/android"
SDK="$(cygpath -m "$LOCALAPPDATA")/Android/Sdk"
BT="$SDK/build-tools/36.0.0"
ANDROID_JAR="$SDK/platforms/android-36.1/android.jar"
JBR="C:/Program Files/Android/Android Studio/jbr"
export JAVA_HOME="$JBR"

PKG_DIR="$ROOT/app/src/main"
BUILD="$ROOT/build"
OUT="$ROOT/hermes-android-debug.apk"

echo "== clean"
bash "$(dirname "$0")/sync_web.sh"
rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

echo "== aapt2 compile"
"$BT/aapt2.exe" compile --dir "$PKG_DIR/res" -o "$BUILD/res.zip"

echo "== aapt2 link"
"$BT/aapt2.exe" link \
  -o "$BUILD/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$PKG_DIR/AndroidManifest.xml" \
  -A "$PKG_DIR/assets" \
  --java "$BUILD/gen" \
  --auto-add-overlay \
  --min-sdk-version 26 \
  --target-sdk-version 36 \
  --version-code 4 \
  --version-name 0.1.3 \
  --debug-mode \
  "$BUILD/res.zip"

echo "== javac"
find "$PKG_DIR/java" "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
"$JBR/bin/javac.exe" -source 11 -target 11 -encoding UTF-8 \
  -cp "$ANDROID_JAR" -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "== d8"
find "$BUILD/classes" -name '*.class' > "$BUILD/classes.txt"
"$BT/d8.bat" --lib "$ANDROID_JAR" --min-api 26 --output "$BUILD/dex" @"$BUILD/classes.txt"

echo "== package dex into apk"
python - "$BUILD/base.apk" "$BUILD/dex/classes.dex" <<'PY'
import sys, zipfile, shutil
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
print('  added classes.dex')
PY

echo "== zipalign"
"$BT/zipalign.exe" -p -f 4 "$BUILD/base.apk" "$BUILD/aligned.apk"

if [ ! -f "$ROOT/debug.keystore" ]; then
  echo "== debug keystore"
  "$JBR/bin/keytool.exe" -genkeypair -keystore "$ROOT/debug.keystore" -storetype PKCS12 \
    -alias androiddebugkey -storepass android -keypass android \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi

echo "== apksigner"
"$BT/apksigner.bat" sign \
  --ks "$ROOT/debug.keystore" \
  --ks-pass pass:android --key-pass pass:android \
  --out "$OUT" "$BUILD/aligned.apk"

echo "== verify"
"$BT/apksigner.bat" verify --print-certs "$OUT" | head -6
ls -lh "$OUT"
echo "APK: $OUT"
