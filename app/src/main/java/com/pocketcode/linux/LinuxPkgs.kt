package com.pocketcode.linux

/**
 * Пакеты зависят от образа Linux: в Alpine это apk и свои имена, в Ubuntu — apt и deb-имена.
 * Один источник правды для подсказок агенту, экранов и команд установки.
 */
object LinuxPkgs {
    fun isUbuntu(flavor: String): Boolean = flavor == RootFsChoice.UBUNTU.name

    /** Имя менеджера пакетов: apk или apt. */
    fun manager(flavor: String): String = if (isUbuntu(flavor)) "apt" else "apk"

    fun distroName(flavor: String): String =
        if (isUbuntu(flavor)) "Ubuntu 22.04 base (apt, dpkg)" else "Alpine Linux (apk)"

    /** Готовая подсказка «как поставить пакет» для промпта и экранов. */
    fun installHint(flavor: String): String =
        if (isUbuntu(flavor)) "apt-get update && apt-get install -y <пакет>" else "apk add --no-cache <пакет>"

    /** pip в Ubuntu 22.04 старый и не знает --break-system-packages. */
    fun pipHint(flavor: String): String =
        if (isUbuntu(flavor)) "pip install <пакет>" else "pip install --break-system-packages <пакет>"

    /** Базовый набор пакетов для python/git/node в именах этого образа. */
    fun packageNames(flavor: String): String =
        if (isUbuntu(flavor)) "python3 python3-pip nodejs npm git build-essential"
        else "python3 py3-pip nodejs npm git build-base"

    private val UBUNTU_NAMES = mapOf(
        "pip" to "python3-pip",
        "py3-pip" to "python3-pip",
        "build" to "build-essential",
        "build-base" to "build-essential",
        "make" to "build-essential",
        "gcc" to "build-essential",
        "g++" to "build-essential",
        "android-tools" to "adb",
        "adb" to "adb",
        "fonts" to "fonts-dejavu-core",
        "font-dejavu" to "fonts-dejavu-core",
        "openjdk17" to "openjdk-17-jdk-headless",
        "gcompat" to "libc6",
    )

    private val ALPINE_NAMES = mapOf(
        "pip" to "py3-pip",
    )

    fun packageFor(flavor: String, name: String): String =
        if (isUbuntu(flavor)) UBUNTU_NAMES[name] ?: name else ALPINE_NAMES[name] ?: name

    /**
     * Ключи apt, без которых установка в proot идёт в разы дольше или зависает:
     * Use-Pty=0 не даёт dpkg ждать псевдотерминал, ForceIPv4 убирает зависание на IPv6.
     */
    private const val APT_OPTS =
        "-o Acquire::Retries=3 -o Acquire::http::Timeout=20 -o Acquire::https::Timeout=20 " +
            "-o Acquire::ForceIPv4=true -o Dpkg::Use-Pty=0"

    /** Имена пакетов в терминах выбранного образа, без повторов. */
    fun packageList(flavor: String, vararg packages: String): List<String> =
        packages.map { packageFor(flavor, it) }.distinct()

    /** Обновление списка пакетов отдельным шагом: установку показываем по шагам. */
    fun update(flavor: String): String =
        if (isUbuntu(flavor)) "apt-get $APT_OPTS update" else "apk update"

    /** Только установка, без обновления индекса (его делает [update]). */
    fun installOnly(flavor: String, vararg packages: String): String {
        val names = packageList(flavor, *packages).joinToString(" ")
        return if (isUbuntu(flavor)) {
            // --no-install-recommends: без него apt тянет tilix, GTK и dbus, на которых dpkg в proot падает.
            "DEBIAN_FRONTEND=noninteractive apt-get $APT_OPTS install -y --no-install-recommends $names"
        } else {
            "apk add --no-cache $names"
        }
    }

    /** Полная команда установки пакетов для выбранного образа. */
    fun install(flavor: String, vararg packages: String): String =
        if (isUbuntu(flavor)) update(flavor) + " && " + installOnly(flavor, *packages)
        else installOnly(flavor, *packages)

    /**
     * Наборы для запуска полноценных GUI-приложений внутри proot: X11-сервер рисует
     * VNC-клиент на Android, приложения рисуются в Xvfb + x11vnc. Работает в Ubuntu и Alpine.
     */
    fun guiPackages(flavor: String): String = if (isUbuntu(flavor)) {
        "xvfb x11vnc fluxbox xterm dbus-x11"
    } else {
        "xvfb x11vnc fluxbox xterm dbus"
    }

    /** Команда запуска GUI-приложения: VNC-сервер на 5900 + WM, затем само приложение. */
    fun guiRun(flavor: String, appCommand: String): String {
        val wm = if (isUbuntu(flavor)) "fluxbox" else "fluxbox"
        return """
            export DISPLAY=:0
            (Xvfb :0 -screen 0 1280x800x24 &) && sleep 1
            ($wm &) 2>/dev/null
            (x11vnc -display :0 -nopw -listen localhost -forever -quiet &) && sleep 1
            $appCommand
        """.trimIndent()
    }
}
