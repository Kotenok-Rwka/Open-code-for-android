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

/** Инструкция модели: как оформить уточняющие вопросы. */
const val QUESTION_CARDS_INSTRUCTION = """
If you need to ask the user a clarifying question, use this exact block instead of plain text:
QUESTIONS
TAG: short topic
Q: your question
- First option
  short explanation
- Second option
- Other
/QUESTIONS
Include 2-4 options and no text after the block. The app will show buttons and a free-text answer field; the chosen answer is sent as the user's next message. The block must be the last lines of your reply, with no code fence around it.
"""

/** Текст ответа пользователя, который уходит модели после выбора в карточке. */
fun questionAnswerText(answers: List<Pair<String, String>>): String = buildString {
    append("Выбор в карточке вопроса:")
    for ((q, a) in answers) append("\n").append(q).append(" → ").append(a)
}
