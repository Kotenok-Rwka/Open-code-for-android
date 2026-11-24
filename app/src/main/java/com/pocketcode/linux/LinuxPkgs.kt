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

    fun packageFor(flavor: String, name: String): String =
        if (isUbuntu(flavor)) UBUNTU_NAMES[name] ?: name else name

    /** Полная команда установки пакетов для выбранного образа. */
    fun install(flavor: String, vararg packages: String): String {
        val names = packages.map { packageFor(flavor, it) }.distinct().joinToString(" ")
        return if (isUbuntu(flavor)) {
            "apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq $names"
        } else {
            "apk add --no-cache $names"
        }
    }
}
