package com.pocketcode.assist

/** Что хочет пользователь: открыть приложение или ссылку, либо найти и ответить. */
sealed class AssistIntent {
    object Empty : AssistIntent()
    data class OpenApp(val name: String) : AssistIntent()
    data class OpenUrl(val url: String) : AssistIntent()

    /** [forceSearch] — пользователь прямо попросил найти в интернете (найди, загугли…). */
    data class Ask(val text: String, val forceSearch: Boolean) : AssistIntent()
}

/** Разбор запроса без нейросети: команды «открой…» и «найди…» не должны тратить токены и время. */
object AssistIntentParser {
    private val OPEN_VERBS = listOf("открой", "откройте", "открыть", "запусти", "запустите", "запустить", "open", "launch", "start")
    private val SEARCH_VERBS = listOf("найди", "найдите", "найти", "поищи", "поищите", "загугли", "погугли", "search for", "search", "google", "find", "look up")
    private val SEARCH_PLACES = listOf("в интернете", "в интернет", "в сети", "в гугле", "в google", "в яндексе", "online", "on the web", "on google")
    private val OBJECT_WORDS = listOf("приложение", "программу", "app", "the app")
    private val SITE_WORDS = listOf("сайт", "страницу", "ссылку", "site", "website", "page")
    private val POLITE = listOf("пожалуйста", "please")
    private val TLDS = setOf(
        "com", "org", "net", "ru", "su", "io", "dev", "app", "ai", "рф", "ua", "by", "kz", "uk", "de", "fr", "es", "it", "cn", "jp",
        "me", "tv", "info", "biz", "gov", "edu", "co", "us", "eu", "xyz", "online", "site", "tech", "store", "shop", "club", "wiki", "news", "pro",
    )
    private val URL_RX = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)
    private val DOMAIN_RX = Regex("^[\\p{L}\\p{N}-]+(\\.[\\p{L}\\p{N}-]+)+(/\\S*)?$")

    fun parse(raw: String): AssistIntent {
        val text = clean(raw)
        if (text.isEmpty()) return AssistIntent.Empty
        asUrl(text)?.let { return AssistIntent.OpenUrl(it) }
        stripWord(text, OPEN_VERBS)?.let { return openCommand(it, text) }
        stripWord(text, SEARCH_VERBS)?.let { rest ->
            val q = stripPlaces(rest)
            return if (q.isEmpty()) AssistIntent.Empty else AssistIntent.Ask(q, true)
        }
        return AssistIntent.Ask(text, false)
    }

    private fun clean(raw: String): String {
        var t = raw.trim().replace(Regex("\\s+"), " ")
        t = t.replace(Regex("^(пожалуйста|please)\\s*,\\s*", RegexOption.IGNORE_CASE), "")
        for (p in POLITE) {
            stripWord(t, listOf(p))?.let { t = it.trimStart(',', ' ') }
        }
        return t.trim()
    }

    private fun openCommand(rest: String, original: String): AssistIntent {
        var r = rest.trim().trimEnd('.', '!', '?', '…', ',').trim()
        if (r.isEmpty()) return AssistIntent.Empty
        var site = false
        stripWord(r, SITE_WORDS)?.let { r = it.trim(); site = true }
        if (r.isEmpty()) return AssistIntent.Empty
        asUrl(r)?.let { return AssistIntent.OpenUrl(it) }
        if (site) return AssistIntent.Ask(r, true)
        stripWord(r, OBJECT_WORDS)?.let { r = it.trim() }
        if (r.isEmpty()) return AssistIntent.Empty
        // название приложения короткое; длинная фраза — это вопрос («открой рецепт борща пошагово»)
        if (r.split(' ').size > 3) return AssistIntent.Ask(original, false)
        return AssistIntent.OpenApp(r)
    }

    private fun stripPlaces(rest: String): String {
        var r = rest.trim()
        var changed = true
        while (changed) {
            changed = false
            for (p in SEARCH_PLACES) {
                stripWord(r, listOf(p))?.let { r = it.trim(); changed = true }
            }
        }
        return r.trim()
    }

    /** Если [text] начинается с одного из слов целиком, возвращает остаток (пустой, если слово единственное). */
    private fun stripWord(text: String, words: List<String>): String? {
        for (w in words.sortedByDescending { it.length }) {
            if (text.length == w.length && text.equals(w, ignoreCase = true)) return ""
            if (text.length > w.length && text[w.length] == ' ' && text.regionMatches(0, w, 0, w.length, ignoreCase = true)) {
                return text.substring(w.length + 1).trim()
            }
        }
        return null
    }

    /** Ссылка целиком: с http(s) или домен с известной зоной. Иначе null. */
    fun asUrl(s: String): String? {
        val t = s.trim().trimEnd('.', ',', '!', '?')
        if (t.isEmpty() || t.contains(' ')) return null
        if (URL_RX.matches(t)) return t
        if (!DOMAIN_RX.matches(t)) return null
        val tld = t.substringBefore('/').substringAfterLast('.').lowercase()
        return if (tld in TLDS) "https://$t" else null
    }
}
