package com.pocketcode.ui

/** Стиль оформления интерфейса. Выбирается в Ещё → Настройки → «Стиль интерфейса». */
enum class UiStyle(val id: String, val title: String, val about: String) {
    MD3(
        "md3", "Material 3",
        "Родной стиль Android: тональные карточки, крупные скругления, у выбранного раздела внизу «таблетка».",
    ),
    IOS(
        "ios", "iOS",
        "Как на iPhone: крупные заголовки, списки-группы с тонкими разделителями, зелёные переключатели, строгая нижняя панель.",
    ),
}

/** Стиль по умолчанию, пока пользователь не выбрал другой. */
val DEFAULT_UI_STYLE = UiStyle.MD3

/** Стиль по сохранённому коду; пустое или незнакомое значение даёт стиль по умолчанию. */
fun uiStyleById(id: String?): UiStyle = UiStyle.values().firstOrNull { it.id == id } ?: DEFAULT_UI_STYLE

/** Размеры и скругления стиля в dp. Числа, а не Dp, чтобы проверять их тестами без Compose. */
data class StyleMetrics(
    val cardRadius: Int,
    val heroRadius: Int,
    val iconTile: Int,
    val iconTileRadius: Int,
    /** true: пункты раздела собраны в одну скруглённую группу с разделителями (как настройки iPhone). */
    val groupedLists: Boolean,
    /** true: у выбранного пункта нижней панели есть закрашенная «таблетка». */
    val navIndicator: Boolean,
)

fun metricsOf(style: UiStyle): StyleMetrics = when (style) {
    UiStyle.MD3 -> StyleMetrics(cardRadius = 20, heroRadius = 28, iconTile = 40, iconTileRadius = 20, groupedLists = false, navIndicator = true)
    UiStyle.IOS -> StyleMetrics(cardRadius = 12, heroRadius = 0, iconTile = 30, iconTileRadius = 7, groupedLists = true, navIndicator = false)
}

/**
 * Режет плоский список с заголовками на группы: (заголовок или null, пункты).
 * Пункты до первого заголовка идут в группу без заголовка, пустые группы пропускаются.
 */
fun <T> groupBySections(items: List<T>, isHeader: (T) -> Boolean, titleOf: (T) -> String): List<Pair<String?, List<T>>> {
    val out = ArrayList<Pair<String?, List<T>>>()
    var head: String? = null
    var cur = ArrayList<T>()
    for (x in items) {
        if (isHeader(x)) {
            if (cur.isNotEmpty()) out.add(Pair(head, cur))
            head = titleOf(x)
            cur = ArrayList()
        } else {
            cur.add(x)
        }
    }
    if (cur.isNotEmpty()) out.add(Pair(head, cur))
    return out
}

/** Максимальная ширина экранов-списков на планшете, dp: дальше строки становятся слишком длинными для чтения. */
const val READING_WIDTH_DP = 720

/** Экраны, которые на широком дисплее ставятся по центру. Чат, файлы, терминал и редактор остаются на всю ширину. */
private val READING_SCREENS = setOf(
    "HUB_PROJECT", "HUB_AGENT", "HUB_MORE", "SETTINGS", "MODELS", "SESSIONS", "PROJECTS", "LEARN", "SMART",
    "UPDATE", "BACKUP", "LANGUAGE", "DEVICES", "DIAG", "REMOTE", "FIND", "ROOT", "OCSETTINGS", "OCSESSIONS",
    "PLUGINS", "CONTEXT", "COMPARE", "LINUX",
)

fun isReadingScreen(name: String): Boolean = name in READING_SCREENS

/** Планшет или развёрнутый складной телефон: от 840 dp по ширине. */
const val WIDE_MIN_DP = 840

fun isWideScreen(widthDp: Int): Boolean = widthDp >= WIDE_MIN_DP
