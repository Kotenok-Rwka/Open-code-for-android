package com.pocketcode.assist

/** Как показывать готовый ответ: спросить каждый раз, во всплывающем окне или на весь экран. */
enum class ResultMode(val key: String) {
    ASK("ask"), POPUP("popup"), FULLSCREEN("full");

    companion object {
        fun parse(s: String?): ResultMode = values().firstOrNull { it.key == s } ?: ASK
    }
}

/** Сколько показывать, пока идёт поиск: только индикатор, одну строку или шаги с источниками. */
enum class ProgressMode(val key: String) {
    MINIMAL("minimal"), COMPACT("compact"), DETAILED("detailed");

    companion object {
        fun parse(s: String?): ProgressMode = values().firstOrNull { it.key == s } ?: COMPACT
    }
}

/** Что отвечать: готовый ответ нейросети с источниками или только список ссылок из поиска. */
enum class AnswerKind(val key: String) {
    AI("ai"), LINKS("links");

    companion object {
        fun parse(s: String?): AnswerKind = values().firstOrNull { it.key == s } ?: AI
    }
}

/** Насколько глубоко искать: быстрый короткий ответ, обычный или подробный разбор с несколькими страницами. */
enum class AnswerDepth(val key: String, val maxSteps: Int) {
    FAST("fast", 3), NORMAL("normal", 6), DEEP("deep", 12);

    companion object {
        fun parse(s: String?): AnswerDepth = values().firstOrNull { it.key == s } ?: NORMAL
    }
}

data class AssistPrefs(
    val resultMode: ResultMode = ResultMode.ASK,
    val progressMode: ProgressMode = ProgressMode.COMPACT,
    val answerKind: AnswerKind = AnswerKind.AI,
    /** Показывать под ответом страницы, на которые опирался ассистент. */
    val showSources: Boolean = true,
    /** Разрешить кнопку «Что на экране»: текст с экрана уходит модели только по нажатию. */
    val useScreenText: Boolean = true,
    /** Если окно закрыли, пока шёл поиск, прислать готовый ответ уведомлением. */
    val notifyWhenHidden: Boolean = true,
    /** Профиль API для ассистента; пусто — тот же, что активен в чате. */
    val profileId: String = "",
    val depth: AnswerDepth = AnswerDepth.NORMAL,
    /** Когда ответ готов, а окно закрыто, окно с предложением «Здесь / На весь экран» всплывает снова. */
    val popupWhenReady: Boolean = true,
    /** Спрашивать подтверждение, прежде чем закрыть готовый ответ. */
    val confirmClose: Boolean = true,
    /** При вызове сразу включать микрофон; текст можно ввести вручную. */
    val autoListen: Boolean = true,
)

/** Хранит настройки ассистента в тех же настройках приложения; чтение и запись передаются функциями, чтобы это можно было проверить тестом. */
class AssistPrefsStore(private val get: (String) -> String?, private val put: (String, String) -> Unit) {
    fun load(): AssistPrefs {
        val d = AssistPrefs()
        return AssistPrefs(
            resultMode = ResultMode.parse(get(K_RESULT)),
            progressMode = ProgressMode.parse(get(K_PROGRESS)),
            answerKind = AnswerKind.parse(get(K_KIND)),
            showSources = flag(K_SOURCES, d.showSources),
            useScreenText = flag(K_SCREEN, d.useScreenText),
            notifyWhenHidden = flag(K_NOTIFY, d.notifyWhenHidden),
            profileId = get(K_PROFILE).orEmpty(),
            depth = AnswerDepth.parse(get(K_DEPTH)),
            popupWhenReady = flag(K_POPUP, d.popupWhenReady),
            confirmClose = flag(K_CONFIRM, d.confirmClose),
            autoListen = flag(K_LISTEN, d.autoListen),
        )
    }

    fun save(p: AssistPrefs) {
        put(K_RESULT, p.resultMode.key)
        put(K_PROGRESS, p.progressMode.key)
        put(K_KIND, p.answerKind.key)
        put(K_SOURCES, if (p.showSources) "1" else "0")
        put(K_SCREEN, if (p.useScreenText) "1" else "0")
        put(K_NOTIFY, if (p.notifyWhenHidden) "1" else "0")
        put(K_PROFILE, p.profileId)
        put(K_DEPTH, p.depth.key)
        put(K_POPUP, if (p.popupWhenReady) "1" else "0")
        put(K_CONFIRM, if (p.confirmClose) "1" else "0")
        put(K_LISTEN, if (p.autoListen) "1" else "0")
    }

    private fun flag(key: String, default: Boolean): Boolean = when (get(key)) {
        "1" -> true
        "0" -> false
        else -> default
    }

    companion object {
        const val K_RESULT = "assistResult"
        const val K_PROGRESS = "assistProgress"
        const val K_KIND = "assistKind"
        const val K_SOURCES = "assistSources"
        const val K_SCREEN = "assistScreen"
        const val K_NOTIFY = "assistNotify"
        const val K_PROFILE = "assistProfile"
        const val K_DEPTH = "assistDepth"
        const val K_POPUP = "assistPopup"
        const val K_CONFIRM = "assistConfirm"
        const val K_LISTEN = "assistListen"
    }
}
