#!/bin/sh
set -e

data=${XDG_DATA_HOME:-$HOME/.local/share}
lib=$HOME/.local/lib/vyral-rust
bin=$HOME/.local/bin
desktop=$data/applications/vyral-rust.desktop
icon=$data/icons/hicolor/256x256/apps/vyral-rust.png

refresh() {
    update-desktop-database -q "$data/applications" 2>/dev/null || true
    gtk-update-icon-cache -q -t "$data/icons/hicolor" 2>/dev/null || true
}

if [ "$1" = "--uninstall" ]; then
    rm -rf "$lib"
    rm -f "$desktop" "$icon"
    [ -L "$bin/vyral-rust" ] && rm -f "$bin/vyral-rust"
    refresh
    echo "VYRAL is uninstalled. Settings are kept in the VYRAL Rust data directory and credentials in the keyring."
    exit 0
fi

here=$(cd "$(dirname "$0")" && pwd)
case $(uname -m) in
    x86_64) app=vyral-rust-x86_64 ;;
    aarch64|arm64) app=vyral-rust-aarch64 ;;
    *) echo "Unsupported architecture: $(uname -m)"; exit 1 ;;
esac
[ -f "$here/$app" ] || { echo "Run install.sh from the extracted download."; exit 1; }

mkdir -p "$lib/licenses" "$bin" "$data/applications" "$(dirname "$icon")"
cp "$here/$app" "$lib/vyral-rust.new"
chmod 755 "$lib/vyral-rust.new"
mv -f "$lib/vyral-rust.new" "$lib/vyral-rust"
cp "$here/install.sh" "$lib/install.sh"
cp "$here/LICENSE" "$lib/LICENSE"
cp "$here/licenses/"*.txt "$lib/licenses/"
chmod 755 "$lib/install.sh"
echo "Installed by install.sh. This file lets VYRAL update itself." > "$lib/install.txt"
ln -sf "$lib/vyral-rust" "$bin/vyral-rust"
sed "s|^Exec=.*|Exec=$lib/vyral-rust|" "$here/vyral.desktop" > "$desktop"
cp "$here/vyral-rust.png" "$icon"
refresh

missing=$(ldd "$lib/vyral-rust" 2>/dev/null | awk '/not found/ { print $1 }' | tr '\n' ' ')
ldconfig=$(command -v ldconfig || echo /sbin/ldconfig)
appindicator=$("$ldconfig" -p 2>/dev/null | grep -c 'libayatana-appindicator3\|libappindicator3' || true)
if [ -n "$missing" ] || [ "$appindicator" = 0 ]; then
    sudo=""
    [ "$(id -u)" = 0 ] || sudo=sudo
    echo "Installing the libraries vyral needs${missing:+ ($missing)}"
    if command -v apt-get >/dev/null; then
        $sudo apt-get install -y libwebkit2gtk-4.1-0 libgtk-3-0 libayatana-appindicator3-1 libasound2
    elif command -v dnf >/dev/null; then
        $sudo dnf install -y webkit2gtk4.1 gtk3 libayatana-appindicator-gtk3 alsa-lib
    elif command -v pacman >/dev/null; then
        $sudo pacman -S --needed --noconfirm webkit2gtk-4.1 gtk3 libayatana-appindicator alsa-lib
    elif command -v zypper >/dev/null; then
        $sudo zypper --non-interactive install libwebkit2gtk-4_1-0 libgtk-3-0 libayatana-appindicator3-1 alsa
    else
        echo "Install these with your package manager: $missing"
    fi
fi

echo "VYRAL is installed. Open it from your app menu, or run vyral-rust."
echo "To uninstall: $lib/install.sh --uninstall"
