# PocketCode Desktop — сборка portable .exe для Windows 10/11 (нативное окно, без браузера)
# Требования: Python 3.10+ (https://python.org), интернет для pip install pyinstaller
# Запуск: powershell -ExecutionPolicy Bypass -File build_windows.ps1

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

# Версия — из version.properties и числа коммитов (та же, что у APK), не из ручного файла.
$ver = (& python (Join-Path $PSScriptRoot "version.py")).Trim()
if (-not $ver) { throw "Не удалось определить версию (desktop/version.py)" }
Write-Host "Версия: $ver"
# Кладём версию файлом: внутри exe version.py не найдёт ни git, ни version.properties.
Set-Content -Path (Join-Path $PSScriptRoot "version.txt") -Value $ver -NoNewline -Encoding utf8

# qrcode и pillow нужны QR-сопряжению в самом exe, cryptography — шифрованию синхронизации.
python -m pip install --upgrade pyinstaller cryptography qrcode pillow
python -m PyInstaller --noconfirm --onefile --windowed `
  --name PocketCodeDesktop `
  --add-data "version.txt;." `
  --add-data "pocketcode_desktop.py;." `
  --add-data "version.py;." `
  pocketcode_app.py

$dist = Join-Path $PSScriptRoot "dist"
$zip = Join-Path $PSScriptRoot "PocketCodeDesktop-$ver-windows.zip"
Compress-Archive -Path (Join-Path $dist "PocketCodeDesktop.exe"), (Join-Path $PSScriptRoot "version.txt"), (Join-Path $PSScriptRoot "INSTALL.md") -DestinationPath $zip -Force
Write-Host "Готово: $zip"
