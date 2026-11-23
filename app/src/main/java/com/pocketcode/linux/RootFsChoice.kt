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
     * Ubuntu rootfs для proot. cdimage.ubuntu.com держит только числовые релизы
     * (22.04 и точечные 22.04.x); пути с кодовыми именами noble/jammy теперь дают 404.
     */
    fun ubuntuUrl(arch: String, release: String = "22.04"): String =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/$release/release/ubuntu-base-$release-base-$arch.tar.gz"

    /** Несколько запасных зеркал на случай, если основной числовой релиз переехал. */
    fun ubuntuUrls(arch: String): List<String> = listOf(
        ubuntuUrl(arch, "22.04.5"),
        ubuntuUrl(arch, "22.04"),
        ubuntuUrl(arch, "22.04.4"),
        "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04.3/release/ubuntu-base-22.04.3-base-$arch.tar.gz",
    ).distinct()

    fun validateUrl(url: String): String? = when {
        !url.startsWith("https://") -> "Нужен HTTPS-адрес образа"
        !url.contains("ubuntu") -> "Похоже, это не образ Ubuntu"
        else -> null
    }
}
