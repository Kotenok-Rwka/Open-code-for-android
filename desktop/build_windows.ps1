# PocketCode Desktop — сборка portable .exe для Windows 10/11
# Требования: Python 3.10+ (https://python.org), интернет для pip install pyinstaller
# Запуск: powershell -ExecutionPolicy Bypass -File build_windows.ps1

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

python -m pip install --upgrade pyinstaller
python -m PyInstaller --noconfirm --onefile --windowed `
  --name PocketCodeDesktop `
  --add-data "pocketcode_desktop.py;." `
  pocketcode_desktop.py

# Версия — из version.properties и числа коммитов (та же, что у APK), не из ручного файла.
$ver = (& python (Join-Path $PSScriptRoot "version.py")).Trim()
if (-not $ver) { throw "Не удалось определить версию (desktop/version.py)" }
Write-Host "Версия: $ver"

$dist = Join-Path $PSScriptRoot "dist"
$zip = Join-Path $PSScriptRoot "PocketCodeDesktop-$ver-windows.zip"
Compress-Archive -Path (Join-Path $dist "PocketCodeDesktop.exe"), (Join-Path $PSScriptRoot "INSTALL.md") -DestinationPath $zip -Force
Write-Host "Готово: $zip"
