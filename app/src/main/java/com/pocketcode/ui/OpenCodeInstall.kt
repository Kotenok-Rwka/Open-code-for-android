package com.pocketcode.ui

/**
 * Команда установки официального OpenCode внутри Linux (Alpine + proot). Одна и та же для чата и для терминала.
 *
 * Главный путь: берём готовый бинарник прямо из архива пакета opencode-linux-<arch>-musl, без `npm install -g`.
 * Через npm в proot получались битые установки: переименование папки падало с ENOTEMPTY, а скопированный
 * бинарник не запускался («The download or copy was probably interrupted»). Прямая загрузка этих проблем не имеет.
 *
 * 1. Доставляет системные библиотеки и curl, проверяет свободное место (нужно около 300 МБ).
 * 2. Если opencode уже запускается, ничего не трогает.
 * 3. Стирает остатки прошлых неудачных установок (они занимали сотни МБ и ломали npm).
 * 4. Узнаёт последнюю версию в реестре (при сбое берёт [FALLBACK_VERSION]), скачивает архив, проверяет
 *    контрольную сумму и gzip, достаёт bin/opencode, кладёт в [linkDir] и проверяет `opencode --version`.
 *    Источников два: registry.npmjs.org и зеркало registry.npmmirror.com.
 * 5. Если не вышло, пробует старый способ через npm (уже после очистки остатков).
 * 6. В конце печатает «код установки: 0» или «код установки: 1» (по этой строке работает «Начать бесплатно»).
 */
object OpenCodeInstall {
    /** Папка из PATH внутри Linux, куда кладётся opencode. */
    const val LINK_DIR = "/usr/local/bin"

    /** Версия на случай, если реестр не отдал номер последней. */
    const val FALLBACK_VERSION = "1.19.6"

    /** Минимум свободного места в КБ: архив около 60 МБ плюс распакованный бинарник около 160 МБ. */
    const val MIN_FREE_KB = 300000

    // В тексте скрипта вместо знака доллара стоит «§», чтобы не экранировать его в каждой строке.
    private val SCRIPT = """
echo "Установка OpenCode"
M=''
for p in curl ca-certificates libgcc libstdc++ gcompat git nodejs npm; do apk info -e §p >/dev/null 2>&1 || M="§M §p"; done
if [ -n "§M" ]; then
  echo "Ставлю:§M"
  apk add --no-cache §M 2>&1 | tail -n 3
  for p in §M; do apk info -e §p >/dev/null 2>&1 || apk add --no-cache §p 2>&1 | tail -n 2; done
fi
case "§(uname -m)" in
  aarch64|arm64) A=arm64;;
  x86_64|amd64) A=x64;;
  *) echo "Архитектура §(uname -m) не поддерживается OpenCode."; echo "код установки: 1"; exit 1;;
esac
P=opencode-linux-§A-musl
if @LINK@/opencode --version >/dev/null 2>&1; then
  echo "OpenCode уже установлен: §(@LINK@/opencode --version 2>&1 | head -n 1)"
  echo "код установки: 0"
  exit 0
fi
R=§(npm root -g 2>/dev/null)
[ -n "§R" ] || R=/usr/local/lib/node_modules
rm -rf "§R"/opencode-ai "§R"/.opencode-* "§R"/opencode-linux-* /tmp/oc-dl /tmp/package /tmp/opencode.tgz @LINK@/opencode 2>/dev/null
FREE=§(df -Pk /usr/local 2>/dev/null | awk 'NR==2{print §4}')
if [ -n "§FREE" ] && [ "§FREE" -lt @MINFREE@ ] 2>/dev/null; then
  echo "Мало места: свободно около §((FREE / 1024)) МБ, нужно не меньше §((@MINFREE@ / 1024)) МБ. Освободи память телефона и повтори."
  echo "код установки: 1"
  exit 1
fi
OK=0
for B in https://registry.npmjs.org https://registry.npmmirror.com; do
  echo "Источник: §B"
  rm -rf /tmp/oc-dl; mkdir -p /tmp/oc-dl
  J=§(curl -fsSL --retry 2 --connect-timeout 20 --max-time 40 "§B/§P/latest" 2>/dev/null)
  V=§(echo "§J" | grep -o '"version": *"[^"]*"' | head -n 1 | cut -d'"' -f4)
  S=§(echo "§J" | grep -o '"shasum": *"[0-9a-f]*"' | head -n 1 | cut -d'"' -f4)
  [ -n "§V" ] || { V=@FALLBACK@; S=''; }
  echo "Скачиваю §P §V"
  curl -fL --retry 3 --connect-timeout 30 "§B/§P/-/§P-§V.tgz" -o /tmp/oc-dl/oc.tgz || { echo "Не скачалось с §B"; continue; }
  if [ -n "§S" ] && [ "§(sha1sum /tmp/oc-dl/oc.tgz | cut -d' ' -f1)" != "§S" ]; then echo "Файл скачался с ошибкой (контрольная сумма не совпала), пробую другой источник"; continue; fi
  gzip -t /tmp/oc-dl/oc.tgz 2>/dev/null || { echo "Архив повреждён, пробую другой источник"; continue; }
  tar -xzf /tmp/oc-dl/oc.tgz -C /tmp/oc-dl 2>&1 | tail -n 3
  F=/tmp/oc-dl/package/bin/opencode
  [ -s "§F" ] || { echo "В архиве нет bin/opencode"; continue; }
  echo "Размер бинарника: §(( §(wc -c < "§F") / 1048576 )) МБ"
  mkdir -p @LINK@
  rm -f @LINK@/opencode
  mv -f "§F" @LINK@/opencode 2>/dev/null || cp "§F" @LINK@/opencode
  chmod 755 @LINK@/opencode
  rm -rf /tmp/oc-dl
  if @LINK@/opencode --version >/dev/null 2>&1; then OK=1; break; fi
  echo "Не запускается: @LINK@/opencode"
  @LINK@/opencode --version 2>&1 | head -n 3
  cp @LINK@/opencode @LINK@/opencode.new && mv -f @LINK@/opencode.new @LINK@/opencode && chmod 755 @LINK@/opencode
  if @LINK@/opencode --version >/dev/null 2>&1; then OK=1; break; fi
done
if [ "§OK" != 1 ]; then
  echo "Прямая загрузка не сработала, пробую через npm"
  rm -rf "§R"/opencode-ai "§R"/.opencode-* "§R"/opencode-linux-* @LINK@/opencode 2>/dev/null
  npm install -g --force --foreground-scripts --os=linux --cpu=§A --libc=musl opencode-ai §P > /tmp/oc-install.log 2>&1
  echo "npm, код: §?"
  tail -n 8 /tmp/oc-install.log
  chmod -R a+rX "§R"/opencode* 2>/dev/null
  for B in "§R/§P/bin/opencode" "§R/opencode-ai/node_modules/§P/bin/opencode" "§R/opencode-ai/bin/opencode.exe" "§R/opencode-ai/bin/opencode"; do
    [ -e "§B" ] || continue
    chmod a+rx "§B" 2>/dev/null
    if "§B" --version >/dev/null 2>&1; then
      mkdir -p @LINK@
      ln -sf "§B" @LINK@/opencode
      OK=1
      break
    fi
    echo "Не запускается: §B"
    "§B" --version 2>&1 | head -n 3
  done
fi
if [ "§OK" = 1 ] && @LINK@/opencode --version; then
  echo "код установки: 0"
  exit 0
fi
echo "OpenCode не установился. Диагностика:"
uname -m
df -h /usr/local /tmp 2>&1 | tail -n 3
ls -la @LINK@/opencode 2>&1
echo "код установки: 1"
exit 1
""".trimIndent()

    fun command(linkDir: String = LINK_DIR): String =
        SCRIPT
            .replace("@LINK@", linkDir)
            .replace("@FALLBACK@", FALLBACK_VERSION)
            .replace("@MINFREE@", MIN_FREE_KB.toString())
            .replace('§', '$')
}
