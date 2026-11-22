package com.pocketcode

/** Парсер человекочитаемых версий X.Y.Z (4.0.1, 4.2.6, 12.30.5 — без верхней границы). */
object VersionUtil {
    data class V(val major: Int, val minor: Int, val patch: Int) : Comparable<V> {
        override fun compareTo(other: V): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
        override fun toString(): String = "$major.$minor.$patch"
        fun code(): Long = major.toLong() * 1_000_000 + minor.toLong() * 1_000 + patch.toLong()
    }

    fun parse(raw: String?): V? {
        if (raw.isNullOrBlank()) return null
        val m = Regex("""^(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$""").matchEntire(raw.trim()) ?: return null
        return V(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
    }

    /**
     * Тег релиза -> (число для сравнения с versionCode, подпись для людей).
     * «v4.0.7» -> (4000007, "4.0.7"); старый «build-200» -> (200, "0.6.200"); остальное -> null.
     */
    fun fromTag(tag: String): Pair<Int, String>? {
        tag.removePrefix("build-").takeIf { it != tag }?.toIntOrNull()?.let { return it to legacy(it).toString() }
        val v = parse(tag.removePrefix("v").takeIf { it != tag }) ?: return null
        val c = v.code()
        if (c > Int.MAX_VALUE) return null
        return c.toInt() to v.toString()
    }

    /**«старой» версии 0.6.200 из buildNumber — не ломаем старые APK. */
    fun legacy(buildNumber: Int): V = V(0, 6, buildNumber)

    fun label(raw: String?, buildNumber: Int): String = parse(raw)?.toString() ?: legacy(buildNumber).toString()
}
