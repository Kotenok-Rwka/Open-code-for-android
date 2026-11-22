package com.pocketcode.linux

/** Доступные rootfs для Linux-песочницы. Alpine вшита в APK, Ubuntu скачивается отдельно. */
enum class RootFsChoice(val label: String, val description: String) {
    ALPINE("Alpine", "Вшита в приложение, ≈4 МБ, apk. Рекомендуется по умолчанию."),
    UBUNTU("Ubuntu", "Скачивается отдельно (≈40–80 МБ), apt. Больше знакомых инструментов, больше места и трафика."),
}

object RootFsCatalog {
    /** Архитектура, которую понимают образы. */
    fun arch(abis: Array<String>): String? = abis.firstNotNullOfOrNull {
        when {
            it.equals("arm64-v8a", ignoreCase = true) -> "arm64"
            it.equals("armeabi-v7a", ignoreCase = true) -> "armhf"
            it.equals("x86_64", ignoreCase = true) -> "amd64"
            else -> null
        }
    }

    /**
     * Ubuntu rootfs для proot. По умолчанию используется официальный ubuntu-base
     *omen из mirrors; пользователь может заменить URL в настройках.
     */
    fun ubuntuUrl(arch: String, release: String = "noble"): String =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/$release/release/ubuntu-base-$release-base-$arch.tar.gz"

    fun validateUrl(url: String): String? = when {
        !url.startsWith("https://") -> "Нужен HTTPS-адрес образа"
        !url.contains("ubuntu") -> "Похоже, это не образ Ubuntu"
        else -> null
    }
}
