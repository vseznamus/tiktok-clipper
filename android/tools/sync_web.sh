#!/usr/bin/env bash
# Копирует общий веб-интерфейс (../tclip/static) в assets приложения. Запускать после правок интерфейса.
set -e
cd "$(dirname "$0")/.."
SRC=../tclip/static
DST=app/src/main/assets/web
mkdir -p "$DST/icons"
cp "$SRC/index.html" "$SRC/app.js" "$SRC/app.css" "$SRC/manifest.webmanifest" "$DST/"
cp "$SRC"/icons/*.png "$DST/icons/"
echo "Интерфейс скопирован в $DST"
