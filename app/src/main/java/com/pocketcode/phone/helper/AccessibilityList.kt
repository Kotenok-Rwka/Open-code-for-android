// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone.helper

/**
 * Работа со списком включённых служб доступности (Settings.Secure enabled_accessibility_services):
 * значения разделены двоеточием, у каждой службы вид «пакет/класс». Нужна, чтобы помощник мог включить нашу службу,
 * не затирая уже включённые.
 */
object AccessibilityList {
    /** Наша служба в виде, в котором её хранит система. */
    const val OUR_SERVICE = "com.pocketcode/com.pocketcode.phone.a11y.PocketAccessibilityService"

    /** Разбирает значение настройки; `null`, пустая строка и литерал «null» от `settings get` дают пустой список. */
    fun parse(raw: String?): List<String> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text == "null") return emptyList()
        return text.split(':').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Сравнение служб: «пакет/.Класс» и «пакет/пакет.Класс» — одна и та же служба. */
    fun same(a: String, b: String): Boolean = normalize(a) == normalize(b)

    private fun normalize(s: String): String {
        val pkg = s.substringBefore('/')
        val cls = s.substringAfter('/', "")
        val full = if (cls.startsWith(".")) pkg + cls else cls
        return "$pkg/$full"
    }

    fun contains(raw: String?, service: String): Boolean = parse(raw).any { same(it, service) }

    /** Новое значение настройки с добавленной [service]; уже есть — значение возвращается без изменений (но очищенным). */
    fun with(raw: String?, service: String): String {
        val list = parse(raw)
        return (if (list.any { same(it, service) }) list else list + service).joinToString(":")
    }

    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** Команды shell для включения службы: сначала список, потом общий флаг. [service] должна быть без пробелов и кавычек. */
    fun enableCommands(raw: String?, service: String): List<String> {
        require(service.none { it.isWhitespace() || it == '\'' || it == '"' }) { "bad service name" }
        return listOf(
            "settings put secure enabled_accessibility_services ${quote(with(raw, service))}",
            "settings put secure accessibility_enabled 1",
        )
    }
}
