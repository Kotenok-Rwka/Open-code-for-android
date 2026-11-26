package com.pocketcode.ui

/**
 * Карточки уточняющих вопросов. Модель может вернуть служебный блок QUESTIONS,
 * приложение показывает его как карточку с вариантами, а выбранные ответы
 * отправляет в чат обычным сообщением пользователя.
 */

data class QuestionOption(val label: String, val description: String = "")

data class QuestionItem(
    val question: String,
    val tag: String = "",
    val options: List<QuestionOption>,
)

data class ParsedQuestions(val questions: List<QuestionItem>, val cleanText: String, val remainingText: String = "")

/** Варианты вида «B: текст», «C: текст» (латинские буквы B–F; A и Q разобраны отдельно). */
private val LETTER_OPTION = Regex("""^[B-Fb-f]:.*""")
private val OPTION_LINE = Regex("""^\s*(?:[-*•]|\d+[.)]|\(?[a-zA-Z]\)?)\s+(.*)$""")

/** Разбирает блок QUESTIONS. Ничего не знает про Android — проверяется обычным unit-тестом. */
fun parseQuestionCards(raw: String): ParsedQuestions? {
    val lines = raw.replace("\r\n", "\n").replace("```", "").lines()
    val start = lines.indexOfFirst { it.trim().equals("QUESTIONS", ignoreCase = true) || it.trim().equals("ВОПРОСЫ", ignoreCase = true) }
    if (start < 0) return null
    val end = lines.withIndex().firstOrNull { (i, l) -> i > start && l.trim().equals("/QUESTIONS", ignoreCase = true) }?.index ?: -1
    val body = lines.drop(start + 1).take(if (end < 0) lines.size - start - 1 else end - start - 1)
    var tag = ""
    val questions = ArrayList<QuestionItem>()
    var currentQ: String? = null
    var options = ArrayList<QuestionOption>()
    fun flush() {
        val q = currentQ ?: return
        if (options.isNotEmpty()) questions += QuestionItem(q, tag, options.toList())
        currentQ = null
        options = ArrayList()
        tag = ""
    }
    for (line in body) {
        val s = line.trim()
        if (s.isEmpty()) continue
        when {
            s.startsWith("Q:", ignoreCase = true) || s.startsWith("Вопрос:", ignoreCase = true) || s.startsWith("В:", ignoreCase = true) -> {
                flush()
                currentQ = s.substringAfter(':').trim()
            }
            s.startsWith("TAG:", ignoreCase = true) -> tag = s.substring(4).trim()
            s.startsWith("A:", ignoreCase = true) || s.startsWith("О:", ignoreCase = true) || LETTER_OPTION.matches(s) -> {
                if (currentQ != null) options += QuestionOption(s.substring(2).trim())
            }
            OPTION_LINE.matches(s) -> {
                val m = OPTION_LINE.matchEntire(s)!!
                if (currentQ == null) currentQ = m.groupValues[1].trim() else options += QuestionOption(m.groupValues[1].trim())
            }
            currentQ == null -> currentQ = s
            else -> {
                // вторая строка варианта — его пояснение
                if (options.isNotEmpty()) {
                    val last = options.last()
                    options[options.lastIndex] = last.copy(description = (last.description + " " + s).trim())
                }
            }
        }
    }
    flush()
    if (questions.isEmpty()) return null
    val clean = (lines.take(start) + if (end >= 0) lines.drop(end + 1) else emptyList())
        .joinToString("\n").trim()
    return ParsedQuestions(questions, clean)
}

/** Инструкция модели: когда и как задавать уточняющие вопросы. */
const val QUESTION_CARDS_INSTRUCTION = """
ASK WHEN IT HELPS. If the request is genuinely unclear — you cannot tell what to build, two very different results are possible, or one missing detail would waste the whole run — ask before working instead of guessing. Do not ask when an obvious default is fine, do not ask "should I continue?", and never ask just to chat. At most 3 questions, 2-4 ready-made options each, written in the user's language.
Use this exact block (the last lines of your reply, no code fence around it, nothing after it):
QUESTIONS
TAG: short topic
Q: your question
- First option
  short explanation
- Second option
- Other
/QUESTIONS
A short intro line before the block is fine. The app immediately opens a popup: the user taps an option or types their own answer, and that answer comes back as the user's next message. Asking once here is cheaper than doing the wrong thing and redoing it.
"""

/** Ответ «без ответа»: помечает пропущенный вопрос, чтобы он не ушёл модели как выбор варианта. */
const val SKIP_ANSWER = "⏭ без ответа"

/** Текст ответа пользователя, который уходит модели после выбора в карточке. */
fun questionAnswerText(answers: List<Pair<String, String>>): String {
    val chosen = answers.filterNot { it.second == SKIP_ANSWER }
    if (chosen.isEmpty()) return "Вопросы из карточки пропущены — действуй по своему усмотрению."
    return buildString {
        append("Выбор в карточке вопроса:")
        for ((q, a) in chosen) append("\n").append(q).append(" → ").append(a)
    }
}
