#!/usr/bin/env bash
# Copies the renderer bundle (web/) into the Android app's assets and prunes
# files the browser never loads (Electron main/preload, node_modules payload).
set -euo pipefail

SRC="D:/hermes_android/web"
DEST="D:/hermes_android/android/app/src/main/assets/www"

echo "== sync $SRC -> $DEST"
rm -rf "$DEST"
mkdir -p "$DEST"
cp -r "$SRC/." "$DEST/"

rm -rf "$DEST/node_modules" \
       "$DEST/electron-main.mjs" \
       "$DEST/electron-preload.js" \
       "$DEST/preview-guest-preload.js" \
       "$DEST/native"

du -sh "$DEST"
