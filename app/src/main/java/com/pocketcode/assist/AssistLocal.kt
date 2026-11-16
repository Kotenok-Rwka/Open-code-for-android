package com.pocketcode.assist

import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs

/** Простые команды, которые ассистент выполняет сам, на телефоне, без нейросети и без интернета. */
sealed class LocalCommand {
    data class Calc(val expr: String) : LocalCommand()
    object Time : LocalCommand()
    object Date : LocalCommand()
    data class SaveNote(val text: String) : LocalCommand()
    object ShowNotes : LocalCommand()
}

sealed class CalcResult {
    data class Ok(val text: String) : CalcResult()
    object DivZero : CalcResult()
    object Bad : CalcResult()
}

object AssistLocal {
    const val MAX_NOTE = 500

    private val NOTE_RX = Regex(
        "^(?:пожалуйста,? )?(?:запомни(?:,)?(?: что)?|(?:запиши|добавь|создай|сделай)(?: мне)?(?: в)? замет(?:ку|ки|ках)[:,]?|замет(?:ка|ку)[:,]|" +
            "remember(?: that)?|(?:make|add|create)(?: a)? note(?: that)?|note[:,])\\s+(.+)$",
        RegexOption.IGNORE_CASE,
    )
    private val SHOW_NOTES_RX = Regex(
        "^(?:(?:покажи|прочитай|скажи) (?:мои |все )?замет(?:ки|ку)|мои замет(?:ки|ка)|что я просил(?:а)? запомнить|что я записывал(?:а)?|show (?:me )?(?:my )?notes|my notes)$",
    )
    private val TIME_RX = Regex(
        "^(?:который (?:сейчас )?час|сколько (?:сейчас )?времени|скажи время|текущее время|what(?:'s| is) the time|what time is it(?: now)?|current time)$",
    )
    private val DATE_RX = Regex(
        "^(?:какое (?:сегодня )?число(?: сегодня)?|какая (?:сегодня )?дата|какой (?:сегодня |сейчас )?день(?: недели)?|скажи дату|" +
            "what(?:'s| is) (?:the )?date(?: today)?|what day is (?:it|today)|today's date)$",
    )
    private val CALC_PREFIX = Regex(
        "^(?:пожалуйста,? )?(?:сколько будет|сколько получится|сколько|посчитай|подсчитай|вычисли|реши|calculate|calc|compute|what is|what's|how much is)\\s+(.+)$",
    )

    // телефон или дата («8-900-123-45-67», «16.11.2026») не должны считаться примером
    private val PHONE_OR_DATE_RX = Regex("^(?:\\+?[\\d\\s()\\-]{9,}|\\d{1,4}[-/.]\\d{1,2}[-/.]\\d{1,4})$")
    private val MATH_RX = Regex("^(?:[0-9.+\\-*/^()%]|sqrt)+$")
    private val HAS_OP_RX = Regex("[+*/^%]|sqrt|(?<=[\\d)])-")

    /** Разбирает запрос; null — это обычный вопрос, пусть отвечает нейросеть или поиск. */
    fun parse(raw: String): LocalCommand? {
        val t = raw.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty() || t.length > 600) return null
        NOTE_RX.find(t)?.let { m ->
            val note = m.groupValues[1].trim()
            if (note.isNotEmpty()) return LocalCommand.SaveNote(note.take(MAX_NOTE).replaceFirstChar { it.uppercase() })
        }
        val low = t.lowercase().trimEnd('?', '!', '.', ' ')
        if (SHOW_NOTES_RX.matches(low)) return LocalCommand.ShowNotes
        if (TIME_RX.matches(low)) return LocalCommand.Time
        if (DATE_RX.matches(low)) return LocalCommand.Date
        calcExpr(t)?.let { return LocalCommand.Calc(it) }
        return null
    }

    /** Приводит словесную или обычную запись примера к строке из цифр и знаков; null, если это не пример. */
    fun calcExpr(raw: String): String? {
        val src = raw.trim().lowercase().trimEnd('?', '!', '=', ' ')
        var s = src
        var prefixed = false
        CALC_PREFIX.find(s)?.let {
            s = it.groupValues[1]
            prefixed = true
        }
        if (!prefixed && PHONE_OR_DATE_RX.matches(src)) return null
        s = normalize(s, prefixed)
        if (!MATH_RX.matches(s) || s.none { it.isDigit() } || !HAS_OP_RX.containsMatchIn(s)) return null
        return s
    }

    private fun normalize(src: String, prefixed: Boolean): String {
        var s = src
        val words = listOf(
            "умножить на" to "*", "умножь на" to "*", "помножить на" to "*", "разделить на" to "/", "поделить на" to "/", "делить на" to "/",
            "плюс" to "+", "минус" to "-", "в степени" to "^", "в квадрате" to "^2", "в кубе" to "^3",
            "multiplied by" to "*", "divided by" to "/", "to the power of" to "^", "plus" to "+", "minus" to "-", "times" to "*",
        )
        for ((w, r) in words) s = s.replace(w, r)
        val num = "(\\d+(?:[.,]\\d+)?)"
        s = Regex("(?:квадратный )?корень из\\s*$num").replace(s) { "sqrt(" + it.groupValues[1] + ")" }
        s = Regex("(?:square root of|sqrt of)\\s*$num").replace(s) { "sqrt(" + it.groupValues[1] + ")" }
        s = Regex("$num\\s*(?:%|процент(?:а|ов)?|percent)\\s*(?:от|of)\\s*$num").replace(s) { "(" + it.groupValues[2] + ")*" + it.groupValues[1] + "/100" }
        s = s.replace('×', '*').replace('·', '*').replace('÷', '/').replace('−', '-').replace('–', '-').replace('—', '-')
        s = Regex("(?<=[\\d)\\s])[xх](?=[\\d(\\s])").replace(s, "*")
        if (prefixed) s = s.replace(':', '/')
        s = s.replace(',', '.')
        return s.replace(" ", "").replace("=", "")
    }

    /** Языки, где десятичный разделитель запятая. */
    fun usesDecimalComma(lang: String): Boolean = lang == "ru" || lang == "de" || lang == "es"

    fun calc(expr: String, decimalComma: Boolean = false): CalcResult {
        val v = try {
            ExprParser(expr).parse()
        } catch (_: ArithmeticException) {
            return CalcResult.DivZero
        } catch (_: IllegalArgumentException) {
            return CalcResult.Bad
        }
        if (v.isNaN() || v.isInfinite()) return CalcResult.Bad
        if (v == 0.0) return CalcResult.Ok("0")
        val bd = BigDecimal(v).round(MathContext(12)).stripTrailingZeros()
        var text = if (abs(v) >= 1e15 || abs(v) < 1e-9) bd.toString() else bd.toPlainString()
        if (decimalComma) text = text.replace('.', ',')
        return CalcResult.Ok(text)
    }

    /** Строка заметки для списка: «купить молоко». */
    fun noteLine(text: String): String = text.replace(Regex("\\s+"), " ").trim()
}

/** Разбор арифметики: + - * / ^, скобки, sqrt(), проценты («200+10%» это 220). */
private class ExprParser(private val s: String) {
    private var i = 0
    private var lastPct = false

    fun parse(): Double {
        val v = expr()
        if (i != s.length) throw IllegalArgumentException("лишнее")
        return v
    }

    private fun peek(): Char? = s.getOrNull(i)

    private fun expr(): Double {
        var v = term()
        while (true) {
            val c = peek()
            if (c != '+' && c != '-') break
            i++
            val r = term()
            val add = if (lastPct) v * r else r
            v = if (c == '+') v + add else v - add
        }
        lastPct = false
        return v
    }

    private fun term(): Double {
        lastPct = false
        var v = unary()
        val pct = lastPct
        var single = true
        while (true) {
            val c = peek()
            if (c != '*' && c != '/') break
            i++
            single = false
            val r = unary()
            if (c == '*') {
                v *= r
            } else {
                if (r == 0.0) throw ArithmeticException("деление на ноль")
                v /= r
            }
        }
        lastPct = pct && single
        return v
    }

    private fun unary(): Double {
        val c = peek()
        if (c == '-') {
            i++
            return -unary()
        }
        if (c == '+') {
            i++
            return unary()
        }
        return power()
    }

    private fun power(): Double {
        val base = postfix()
        if (peek() == '^') {
            i++
            val e = unary()
            lastPct = false
            return Math.pow(base, e)
        }
        return base
    }

    private fun postfix(): Double {
        var v = primary()
        while (peek() == '%') {
            i++
            v /= 100
            lastPct = true
        }
        return v
    }

    private fun primary(): Double {
        if (s.startsWith("sqrt", i)) {
            i += 4
            if (peek() != '(') throw IllegalArgumentException("sqrt")
            i++
            val v = expr()
            if (peek() != ')') throw IllegalArgumentException("скобка")
            i++
            if (v < 0) throw IllegalArgumentException("корень из отрицательного")
            lastPct = false
            return Math.sqrt(v)
        }
        if (peek() == '(') {
            i++
            val v = expr()
            if (peek() != ')') throw IllegalArgumentException("скобка")
            i++
            lastPct = false
            return v
        }
        val start = i
        while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
        if (start == i) throw IllegalArgumentException("число")
        return s.substring(start, i).toDoubleOrNull() ?: throw IllegalArgumentException("число")
    }
}
