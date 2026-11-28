#!/usr/bin/env bash
#
# Wraps the app image jpackage produced into an AppImage, the one Linux format
# Gradle cannot make itself.
#
# Usage: app/desktop/kmp/platforms/linux/appimage.sh <app-image-dir> <out.AppImage> [appimagetool]
set -euo pipefail

APP_DIR_SRC=${1:?usage: appimage.sh <app-image-dir> <out.AppImage> [appimagetool]}
OUT=${2:?usage: appimage.sh <app-image-dir> <out.AppImage> [appimagetool]}
TOOL=${3:-appimagetool}

[ -x "$APP_DIR_SRC/bin/VYRAL-KMP" ] || { echo "no VYRAL-KMP launcher in $APP_DIR_SRC" >&2; exit 1; }

ROOT=$(cd "$(dirname "$0")/../../../../.." && pwd)
STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
APPDIR="$STAGE/VYRAL-KMP.AppDir"

mkdir -p "$APPDIR/usr"
cp -a "$APP_DIR_SRC/." "$APPDIR/usr/"

# The mount point differs every run, so nothing may be hard-coded.
cat > "$APPDIR/AppRun" <<'APPRUN'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
exec "$HERE/usr/bin/VYRAL-KMP" "$@"
APPRUN
chmod +x "$APPDIR/AppRun"

cat > "$APPDIR/app.vyral.night.kmp.desktop" <<'DESKTOP'
[Desktop Entry]
Type=Application
Name=VYRAL (KMP)
GenericName=Music Player
Comment=Aesthetic YouTube Music client
Exec=VYRAL-KMP
Icon=vyral
Categories=AudioVideo;Audio;Player;
Terminal=false
StartupWMClass=app-vyral-desktop-MainKt
DESKTOP

# appimagetool reads the icon by the desktop entry; the desktop reads .DirIcon.
cp "$ROOT/app/desktop/kmp/platforms/linux/icons/vyral.png" "$APPDIR/vyral.png"
cp "$ROOT/app/desktop/kmp/platforms/linux/icons/vyral.svg" "$APPDIR/vyral.svg"
cp "$ROOT/app/desktop/kmp/platforms/linux/icons/vyral.png" "$APPDIR/.DirIcon"

mkdir -p "$(dirname "$OUT")"
# APPIMAGE_EXTRACT_AND_RUN rather than the flag of the same name: it is read by the AppImage
# runtime, so it covers a CI runner with no FUSE, and a natively built appimagetool — which has
# no such flag and would refuse to start — simply ignores it.
ARCH=x86_64 APPIMAGE_EXTRACT_AND_RUN=1 "$TOOL" "$APPDIR" "$OUT"
echo "appimage: $OUT"
