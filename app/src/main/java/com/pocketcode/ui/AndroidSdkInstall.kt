package com.pocketcode.ui

/**
 * Команда установки всего, что нужно для сборки Android-приложений в Linux приложения (Alpine + proot):
 * JDK 17, командные инструменты Android SDK, платформа и build-tools. Ставится в [SDK_DIR], не в проект.
 *
 * Важное ограничение: Gradle для сборки APK вызывает aapt2, а Google выпускает его только для x86_64 (glibc).
 * На телефоне с процессором ARM он не запустится. Поэтому скрипт ищет рабочий aapt2 (из SDK, из пакетов Alpine,
 * либо положенный вручную в [AAPT2_DIR]), проверяет его запуском и только тогда прописывает его в
 * ~/.gradle/gradle.properties. Если рабочего aapt2 нет, скрипт честно об этом пишет и советует облачную сборку.
 */
object AndroidSdkInstall {
    /** Где лежит SDK внутри Linux. */
    const val SDK_DIR = "/opt/android-sdk"

    /** Сюда можно положить свой aapt2 для этого процессора: скрипт его подхватит. */
    const val AAPT2_DIR = "/opt/android-sdk/aapt2-native"

    const val CMDLINE_TOOLS_URL = "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

    /** Минимум свободного места в КБ: JDK, инструменты, платформа и кэш Gradle. */
    const val MIN_FREE_KB = 1500000

    const val PLATFORM = "platforms;android-34"
    const val BUILD_TOOLS = "build-tools;34.0.0"

    /** Строка для начала команд агента: после неё sdkmanager и Gradle находят SDK. */
    const val ENV_PREFIX = "export ANDROID_HOME=$SDK_DIR ANDROID_SDK_ROOT=$SDK_DIR; "

    // В тексте скрипта вместо знака доллара стоит «§», чтобы не экранировать его в каждой строке.
    private val SCRIPT = """
echo "Установка Android SDK и JDK"
FREE=§(df -Pk /usr/local 2>/dev/null | awk 'NR==2{print §4}')
if [ -n "§FREE" ] && [ "§FREE" -lt @MINFREE@ ] 2>/dev/null; then
  echo "Мало места: свободно около §((FREE / 1024)) МБ, нужно не меньше §((@MINFREE@ / 1024)) МБ. Освободи память телефона и повтори."
  echo "код установки SDK: 1"
  exit 1
fi
M=''
for p in openjdk17 bash unzip curl ca-certificates git; do apk info -e §p >/dev/null 2>&1 || M="§M §p"; done
if [ -n "§M" ]; then
  echo "Ставлю:§M"
  apk add --no-cache §M 2>&1 | tail -n 3
  for p in §M; do apk info -e §p >/dev/null 2>&1 || apk add --no-cache §p 2>&1 | tail -n 2; done
fi
JH=/usr/lib/jvm/default-jvm
[ -x "§JH/bin/java" ] || JH=§(ls -d /usr/lib/jvm/java-17-openjdk* 2>/dev/null | head -n 1)
if [ -z "§JH" ] || ! "§JH/bin/java" -version >/dev/null 2>&1; then
  echo "Java не установилась. Проверь интернет (при блокировках включи VPN) и повтори."
  echo "код установки SDK: 1"
  exit 1
fi
mkdir -p /usr/local/bin
for t in java javac jar keytool; do [ -x "§JH/bin/§t" ] && ln -sf "§JH/bin/§t" /usr/local/bin/§t; done
export JAVA_HOME=§JH
SDK=@SDK@
export ANDROID_HOME=§SDK ANDROID_SDK_ROOT=§SDK
mkdir -p §SDK/cmdline-tools
if [ ! -x §SDK/cmdline-tools/latest/bin/sdkmanager ]; then
  echo "Скачиваю командные инструменты Android SDK"
  rm -rf /tmp/clt /tmp/clt.zip
  curl -fL --retry 3 --connect-timeout 30 "@URL@" -o /tmp/clt.zip || { echo "Не скачалось с dl.google.com. Включи VPN и повтори."; echo "код установки SDK: 1"; exit 1; }
  unzip -q /tmp/clt.zip -d /tmp/clt || { echo "Архив повреждён, повтори."; echo "код установки SDK: 1"; exit 1; }
  mkdir -p §SDK/cmdline-tools/latest
  cp -r /tmp/clt/cmdline-tools/. §SDK/cmdline-tools/latest/
  rm -rf /tmp/clt /tmp/clt.zip
fi
SM=§SDK/cmdline-tools/latest/bin/sdkmanager
echo "Принимаю лицензии"
yes | §SM --sdk_root=§SDK --licenses >/dev/null 2>&1
echo "Ставлю платформу и build-tools"
§SM --sdk_root=§SDK "@PLATFORM@" "@BUILDTOOLS@" 2>&1 | tail -n 4
if [ ! -d §SDK/platforms ] || [ -z "§(ls §SDK/platforms 2>/dev/null)" ]; then
  echo "Платформа Android не установилась. Проверь интернет и повтори."
  echo "код установки SDK: 1"
  exit 1
fi
A2=''
for C in @AAPT2DIR@/aapt2 §SDK/build-tools/*/aapt2 §(command -v aapt2 2>/dev/null); do
  [ -x "§C" ] && "§C" version >/dev/null 2>&1 && { A2="§C"; break; }
done
if [ -z "§A2" ]; then
  apk search -e aapt2 2>/dev/null | grep -q . && apk add --no-cache aapt2 2>&1 | tail -n 2
  C=§(command -v aapt2 2>/dev/null)
  [ -n "§C" ] && "§C" version >/dev/null 2>&1 && A2="§C"
fi
mkdir -p ~/.gradle
grep -v -e '^android.aapt2FromMavenOverride=' -e '^org.gradle.java.home=' ~/.gradle/gradle.properties 2>/dev/null > ~/.gradle/gradle.properties.new
echo "org.gradle.java.home=§JH" >> ~/.gradle/gradle.properties.new
[ -n "§A2" ] && echo "android.aapt2FromMavenOverride=§A2" >> ~/.gradle/gradle.properties.new
mv -f ~/.gradle/gradle.properties.new ~/.gradle/gradle.properties
if [ -f settings.gradle ] || [ -f settings.gradle.kts ]; then
  grep -q '^sdk.dir=' local.properties 2>/dev/null || echo "sdk.dir=§SDK" >> local.properties
  echo "В проект добавлен local.properties с путём к SDK"
fi
echo "Java: §(java -version 2>&1 | head -n 1)"
echo "SDK: §SDK, платформы: §(ls §SDK/platforms | tr '\n' ' ')"
if [ -n "§A2" ]; then
  echo "aapt2: работает (§A2). Сборка Gradle на телефоне возможна."
else
  echo "aapt2: НЕ запускается на этом процессоре (§(uname -m)). Gradle не сможет собрать APK на телефоне."
  echo "Что можно: положить свой рабочий aapt2 в @AAPT2DIR@ и повторить, либо собирать в облаке через GitHub Actions."
fi
echo "Перед командами сборки: @ENV@"
echo "код установки SDK: 0"
""".trimIndent()

    fun command(): String =
        SCRIPT
            .replace("@SDK@", SDK_DIR)
            .replace("@URL@", CMDLINE_TOOLS_URL)
            .replace("@PLATFORM@", PLATFORM)
            .replace("@BUILDTOOLS@", BUILD_TOOLS)
            .replace("@AAPT2DIR@", AAPT2_DIR)
            .replace("@MINFREE@", MIN_FREE_KB.toString())
            .replace("@ENV@", ENV_PREFIX.trim())
            .replace('§', '$')
}
