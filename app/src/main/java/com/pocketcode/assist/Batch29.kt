package com.pocketcode.assist

/** Prefs-хранилище памяти ассистента (Batch29): строка в SharedPreferences. */
object AssistMemoryPrefs {
    private const val KEY = "assistMemory"

    fun load(context: android.content.Context): String =
        context.getSharedPreferences("assist", android.content.Context.MODE_PRIVATE).getString(KEY, "") ?: ""

    fun save(context: android.content.Context, value: String) {
        context.getSharedPreferences("assist", android.content.Context.MODE_PRIVATE)
            .edit().putString(KEY, value).apply()
    }
}

/**
 * Batch 29: живой ассистент — память, вычисления, напоминания, рецепты.
 * Всё локально и без сети: факты хранятся в prefs-строке, калькулятор — свой парсер,
 * напоминания — текст для системы напоминаний (Batch19), рецепты — именованные наборы шагов.
 */

/** Память ассистента: личные факты пользователя («запомни: я люблю овсянку»). */
object AssistMemory {
    private const val SEP = "\u0001"

    /** Разбор «запомни…»: null — это не команда запоминания. */
    fun parseSave(text: String): Pair<String, String>? {
        val t = text.trim()
        val low = t.lowercase()
        val body = when {
            low.startsWith("запомни:") -> t.substringAfter(":").trim()
            low.startsWith("запомни,") -> t.substringAfter(",").trim()
            low.startsWith("запомни ") -> t.substringAfter(' ').trim()
            else -> return null
        }
        if (body.isEmpty()) return null
        val key = body.substringBefore('=').trim().lowercase().take(40)
        val value = body.substringAfter('=', "").trim().take(200)
        return if (key.isNotEmpty() && value.isNotEmpty()) key to value else null
    }

    /** Сохранить/обновить факт (по ключу). Возвращает новую карту. */
    fun put(store: Map<String, String>, key: String, value: String): Map<String, String> =
        store + (key to value)

    /** Ключ факта из вопроса («что я люблю на завтрак?» → «люблю на завтрак»…): упрощённо — нормализация. */
    fun normalizeKey(s: String): String =
        s.trim().lowercase()
            .replace(Regex("^(что |как |какой |какая |где |когда |кем |кто )+"), "")
            .replace(Regex("[?!.,]$"), "")
            .take(40)

    /** Синонимы в вопросах: «меня зовут» = «имя» и т.п. */
    private val synonyms = listOf("зовут" to "имя", "называют" to "имя")

    /** Ответ на вопрос из памяти: точное или частичное совпадение ключей. */
    fun answer(question: String, store: Map<String, String>): String? {
        if (store.isEmpty()) return null
        var q = normalizeKey(question)
        for ((from, to) in synonyms) q = q.replace(from, to)
        store[q]?.let { return it }
        // частичное: вопрос содержит ключ, ключ содержит вопрос или пересечение значимых слов
        store.entries.firstOrNull { (k, _) ->
            q.contains(k) || k.contains(q) || k.split(' ').any { it.length > 3 && q.contains(it) } ||
                q.split(' ').any { w -> w.length > 2 && w in k }
        }?.let { return it.value }
        return null
    }

    /** Сериализация в prefs-строку. */
    fun serialize(store: Map<String, String>): String =
        store.entries.joinToString(SEP) { "${it.key}=${it.value}" }

    fun parse(s: String): Map<String, String> =
        if (s.isBlank()) emptyMap() else s.split(SEP).mapNotNull {
            val p = it.split('=', limit = 2)
            if (p.size == 2 && p[0].isNotBlank()) p[0] to p[1] else null
        }.toMap()
}

/** Локальный калькулятор: «(2+3)*4», «13% от 2400», «150 долларов в рубли» (курс — аргумент). */
object AssistCalc {

    /** Вычисление арифметики + - * / ( ) и унарного минуса. null — не математика/ошибка. */
    fun eval(s: String): Double? = try {
        Parser(s.trim().replace(',', '.').replace(" ", "")).parse()
    } catch (_: Exception) { null }

    private class Parser(private val s: String) {
        private var i = 0
        fun parse(): Double {
            val v = expr()
            if (i < s.length) error("лишний символ")
            return v
        }
        private fun expr(): Double {
            var v = term()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val op = s[i++]; val r = term()
                v = if (op == '+') v + r else v - r
            }
            return v
        }
        private fun term(): Double {
            var v = unary()
            while (i < s.length && (s[i] == '*' || s[i] == '/')) {
                val op = s[i++]; val r = unary()
                v = if (op == '*') v * r else v / r
            }
            return v
        }
        private fun unary(): Double {
            if (i < s.length && s[i] == '-') { i++; return -unary() }
            if (i < s.length && s[i] == '+') { i++; return unary() }
            return primary()
        }
        private fun primary(): Double {
            if (i < s.length && s[i] == '(') {
                i++
                val v = expr()
                if (i >= s.length || s[i] != ')') error("нет скобки")
                i++
                return v
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (start == i) error("не число")
            return s.substring(start, i).toDouble()
        }
    }

    /** «13% от 2400» → 312.0. null — не процент. */
    fun percent(s: String): Double? {
        val m = Regex("(\\d+([.,]\\d+)?)\\s*%\\s*от\\s*(\\d+([.,]\\d+)?)", RegexOption.IGNORE_CASE)
            .find(s.trim()) ?: return null
        val p = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val v = m.groupValues[3].replace(',', '.').toDoubleOrNull() ?: return null
        return v * p / 100.0
    }

    /** Конверсия валют: «150 долларов в рубли», курс передаёт вызывающий (rateFromUsd). null — не конверсия. */
    fun convert(s: String, rateUsdToRub: Double?): Double? {
        if (rateUsdToRub == null || rateUsdToRub <= 0) return null
        val t = s.trim().lowercase()
        val m = Regex("(\\d+([.,]\\d+)?)\\s*(доллар[а-яё]*|usd|\\$)\\s*(в|to)\\s*(рубл[а-яё]*|rub|р)", RegexOption.IGNORE_CASE).find(t) ?: return null
        val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        return v * rateUsdToRub
    }

    /** Это запрос-вычисление (не болтовня)? */
    fun isCalc(text: String): Boolean {
        val t = text.trim()
        if (t.length > 60) return false
        if (percent(t) != null || convert(t, 1.0) != null) return true
        return t.any { it.isDigit() } && t.any { it in "+-*/()" } && Regex("\\d\\s*[+\\-*/]\\s*\\d").containsMatchIn(t)
    }

    /** Форматированный ответ. */
    fun answer(text: String, rateUsdToRub: Double? = null): String? {
        percent(text)?.let { return "= ${fmt(it)}" }
        convert(text, rateUsdToRub)?.let { return "= ${fmt(it)} ₽" }
        if (!isCalc(text)) return null
        val v = eval(text.substringAfterLast("=").trim()) ?: return null
        return "= ${fmt(v)}"
    }

    fun fmt(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(java.util.Locale.US, "%.2f", v)
}

/** Быстрые рецепты: «утро» → список шагов ассистента. */
object AssistRecipes {
    val builtin = mapOf(
        "утро" to listOf("Погода", "Курс доллара", "Новости"),
        "погода" to listOf("Погода"),
        "работа" to listOf("Погода", "Напоминания"),
    )

    /** Разбор «рецепт утро» / самостоятельного «утро». null — не рецепт. */
    fun resolve(text: String, custom: Map<String, List<String>> = emptyMap()): List<String>? {
        val t = text.trim().lowercase()
        val name = when {
            t.startsWith("рецепт ") -> t.removePrefix("рецепт ").trim()
            t in builtin || t in custom -> t
            else -> return null
        }
        return custom[name] ?: builtin[name]
    }
}
