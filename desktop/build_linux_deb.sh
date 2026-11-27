#!/usr/bin/env bash
# PocketCode Desktop — сборка .deb для Debian/Ubuntu (Linux)
# Запуск: bash build_linux_deb.sh
# PC_OUT  — каталог для готового .deb (по умолчанию — рядом со скриптом),
# PC_ARCH — amd64 или arm64 (по умолчанию amd64).
set -euo pipefail
OUT=${PC_OUT:-}  # считаем до cd, чтобы относительный путь шёл от места запуска
cd "$(dirname "$0")"
OUT=${OUT:-$PWD}
NAME=pocketcode-desktop
# Версия — из version.properties и числа коммитов (та же, что у APK), не из ручного файла.
VER=$(python3 version.py | tr -d '[:space:]')
[ -n "$VER" ] || { echo "Не удалось определить версию (desktop/version.py)" >&2; exit 1; }
[ -z "${PC_VERSION:-}" ] || echo "Версия (PC_VERSION): $VER"
ARCH=${PC_ARCH:-amd64}  # для Debian/Ubuntu: amd64 или arm64; переопредели через PC_ARCH
ROOT=$(mktemp -d)
trap 'rm -rf "$ROOT"' EXIT
# dpkg-deb требует, чтобы каталоги package/DEBIAN были не уже 0700
chmod 755 "$ROOT"
PKG="$ROOT/$NAME"
mkdir -p "$PKG/DEBIAN" "$PKG/usr/bin" "$PKG/usr/share/applications" \
         "$PKG/usr/lib/pocketcode-desktop" "$PKG/usr/share/icons/hicolor/scalable/apps"
chmod -R 755 "$PKG"

cat > "$PKG/DEBIAN/control" <<EOF
Package: $NAME
Version: $VER
Section: utils
Priority: optional
Architecture: $ARCH
Depends: python3 (>= 3.8), python3-tk, python3-qrcode
Recommends: python3-cryptography
Maintainer: PocketCode <noreply@example.com>
Description: PocketCode Desktop companion
 Native desktop app (no browser): same coding agent as the Android app,
 tools, projects and undo, sessions, skills and plugins, local models with
 cancellable downloads, token saving, Aurora UI, and two-way sync so work
 continues from any connected device.
EOF

# features.py обязателен: его импортирует pocketcode_desktop.py. Без него
# установленное приложение падает с ModuleNotFoundError ещё до окна.
cp features.py pocketcode_desktop.py pocketcode_app.py ide_bridge.py version.py \
   "$PKG/usr/lib/pocketcode-desktop/"
cp pocketcode-desktop.svg "$PKG/usr/share/icons/hicolor/scalable/apps/"
cat > "$PKG/usr/bin/pocketcode-desktop" <<'EOF'
#!/usr/bin/env bash
export PC_VERSION="__VER__"
exec python3 /usr/lib/pocketcode-desktop/pocketcode_app.py "$@"
EOF
sed -i "s/__VER__/$VER/" "$PKG/usr/bin/pocketcode-desktop"
chmod +x "$PKG/usr/bin/pocketcode-desktop"

cat > "$PKG/usr/share/applications/pocketcode-desktop.desktop" <<'EOF'
[Desktop Entry]
Name=PocketCode Desktop
Comment=Companion for PocketCode
Exec=pocketcode-desktop
Icon=pocketcode-desktop
Terminal=false
Type=Application
Categories=Development;Utility;
EOF

mkdir -p "$OUT"
# --root-owner-group: файлы пакета принадлежат root:root, а не тому, кто собирал.
dpkg-deb --root-owner-group --build "$PKG" "$OUT/pocketcode-desktop_${VER}_${ARCH}.deb"
echo "Готово: $OUT/pocketcode-desktop_${VER}_${ARCH}.deb"
