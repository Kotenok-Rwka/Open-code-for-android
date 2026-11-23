#!/usr/bin/env bash
# PocketCode Desktop — сборка .deb для Debian/Ubuntu (Linux)
# Запуск: bash build_linux_deb.sh
set -euo pipefail
cd "$(dirname "$0")"

NAME=pocketcode-desktop
# Версия — из version.properties и числа коммитов (та же, что у APK), не из ручного файла.
VER=$(python3 version.py | tr -d '[:space:]')
[ -n "$VER" ] || { echo "Не удалось определить версию (desktop/version.py)" >&2; exit 1; }
[ -z "${PC_VERSION:-}" ] || echo "Версия (PC_VERSION): $VER"
ARCH=${PC_ARCH:-amd64}  # для Debian/Ubuntu: amd64 или arm64; переопредели через PC_ARCH
ROOT=$(mktemp -d)
# dpkg-deb требует, чтобы каталоги package/DEBIAN были не уже 0700
chmod 755 "$ROOT"
PKG="$ROOT/$NAME"
mkdir -p "$PKG/DEBIAN" "$PKG/usr/bin" "$PKG/usr/share/applications" "$PKG/usr/lib/pocketcode-desktop"
chmod -R 755 "$PKG"

cat > "$PKG/DEBIAN/control" <<EOF
Package: $NAME
Version: $VER
Section: utils
Priority: optional
Architecture: $ARCH
Depends: python3 (>= 3.8)
Maintainer: PocketCode <noreply@example.com>
Description: PocketCode Desktop companion
 Local companion for PocketCode: chat with local and cloud OpenAI-compatible
 models, sync sessions with your phone, and manage tasks from an IDE.
EOF

cp pocketcode_desktop.py ide_bridge.py "$PKG/usr/lib/pocketcode-desktop/"
cat > "$PKG/usr/bin/pocketcode-desktop" <<'EOF'
#!/usr/bin/env bash
exec python3 /usr/lib/pocketcode-desktop/pocketcode_desktop.py "$@"
EOF
chmod +x "$PKG/usr/bin/pocketcode-desktop"

cat > "$PKG/usr/share/applications/pocketcode-desktop.desktop" <<'EOF'
[Desktop Entry]
Name=PocketCode Desktop
Comment=Companion for PocketCode
Exec=pocketcode-desktop
Terminal=false
Type=Application
Categories=Development;Utility;
EOF

dpkg-deb --build "$PKG" "pocketcode-desktop_${VER}_${ARCH}.deb"
echo "Готово: pocketcode-desktop_${VER}_${ARCH}.deb"
