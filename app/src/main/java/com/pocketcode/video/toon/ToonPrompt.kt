package com.pocketcode.video.toon

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Формат серии: с голосованием в конце или просто продолжение истории. */
enum class ToonFormat(val label: String) { CHOICE("С выбором зрителей в конце (2 варианта)"), SERIES("Серия без выбора") }

/** Запоминается каждый снятый мультик: можно открыть сценарий, посмотреть ролик и продолжить историю. */
@Serializable
data class ToonEntry(val id: Long, val title: String, val script: String, val rel: String, val episode: Int = 1, val series: String = "")

object ToonHistory {
    const val MAX = 30
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(raw: String?): List<ToonEntry> =
        if (raw.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(ListSerializer(ToonEntry.serializer()), raw) }.getOrDefault(emptyList())

    fun encode(list: List<ToonEntry>): String = json.encodeToString(ListSerializer(ToonEntry.serializer()), list)

    /** Новое — в начало, хранится не больше [MAX]. */
    fun add(list: List<ToonEntry>, e: ToonEntry): List<ToonEntry> = (listOf(e) + list.filter { it.id != e.id }).take(MAX)

    fun remove(list: List<ToonEntry>, id: Long): List<ToonEntry> = list.filter { it.id != id }

    /** Номер следующей серии той же истории. */
    fun nextEpisode(list: List<ToonEntry>, series: String): Int = (list.filter { it.series == series }.maxOfOrNull { it.episode } ?: 0) + 1
}

/** Запрос к нейросети на сценарий и разбор её ответа. */
object ToonPrompt {
    val MIN_OPTIONS = listOf(30, 45, 60, 90, 120)
    const val DEFAULT_MIN = 45

    /** Сколько реплик нужно, чтобы набралась длина: средняя реплика с паузой около 3.6 секунды. */
    fun linesFor(minSec: Int): Int = (minSec / 3.6).toInt().coerceAtLeast(6) + 2

    fun system(
        minSec: Int,
        format: ToonFormat,
        previous: String?,
        chosen: String?,
        episode: Int,
        choiceCount: Int = 2,
        extra: String = "",
    ): String {
        val n = linesFor(minSec)
        val sb = StringBuilder()
        sb.append("Ты сценарист вертикальных мультиков для TikTok: плоские вырезанные герои, живые диалоги, неожиданный поворот. ")
        sb.append("Задача — чтобы зритель не свайпнул на первой секунде и досмотрел до конца.\n\n")
        sb.append("Структура серии:\n")
        sb.append("1. Крючок: первая реплика — 3–7 слов, сразу странность, конфликт или вопрос. Никаких «привет», «однажды», «жил-был», описаний погоды и представлений героев.\n")
        sb.append("2. Развитие: каждые 2–3 реплики новое событие или препятствие. Герой пытается решить проблему и делает только хуже.\n")
        sb.append("3. Поворот: в последней трети — факт, который переворачивает историю (кто-то врёт, это был не тот человек, цена слишком высока).\n")
        sb.append("4. Финал: панчлайн или обрыв на самом интересном. Без морали, объяснений и «и они жили долго».\n\n")
        sb.append("Правила:\n")
        sb.append("- Героев 2–3, простые русские имена. Говорят герои, а не автор.\n")
        sb.append("- Не меньше 70% строк — диалог. Рассказчик максимум две строки и только чтобы назвать время или место.\n")
        sb.append("- В реплике 4–12 слов и одна мысль. Живая разговорная речь, без канцелярита.\n")
        sb.append("- Сцену меняй 2–3 раза в моменты поворота строкой «Сцена: …».\n")
        sb.append("- Не пиши звукоподражания («бах», «шшш») и ремарки в скобках: только то, что произносит герой.\n")
        sb.append("- Числа и сокращения пиши словами, как произносятся.\n\n")
        sb.append("Отвечай ТОЛЬКО сценарием в таком формате, без пояснений, без markdown и без кавычек вокруг:\n")
        sb.append("Название: короткое название\nСцена: ночь | море | комната | город | заправка | лагерь\n")
        sb.append("Имя: реплика\nРассказчик: реплика\n")
        sb.append("Сцену можно менять по ходу истории новой строкой «Сцена: …».\n")
        if (format == ToonFormat.CHOICE) {
            val count = choiceCount.coerceIn(1, 8)
            sb.append("В конце строка «Выбор: вопрос зрителям | вариант 1 | вариант 2»")
            if (count != 2) sb.append(" с ровно $count вариантами через |") else sb.append(", ровно два варианта")
            sb.append(".\n")
        } else {
            sb.append("Без строки «Выбор». История обрывается на интересном месте, чтобы хотелось смотреть следующую серию.\n")
        }
        sb.append("Строка «Призыв: …» необязательна.\n\n")
        sb.append("Пример правильного начала (копируй ритм, а не сюжет):\n")
        sb.append("Название: Кот вернулся\nСцена: комната\nМаша: Кот смотрит на меня третий день.\nМаша: А миска утром была пустая.\nКот: Я тебя не выбирал.\nМаша: Тогда кто выключил будильник?\n\n")
        sb.append("Не меньше $n реплик, чтобы серия шла не короче $minSec секунд. Первая реплика сразу цепляет.\n")
        if (episode > 1) sb.append("Это серия номер $episode: продолжай историю с теми же героями.\n")
        if (extra.isNotBlank()) {
            sb.append("Дополнительные требования (соблюдай их):\n").append(extra.trim().take(2000)).append("\n")
        }
        if (!previous.isNullOrBlank()) {
            sb.append("Предыдущая серия:\n").append(previous.trim().take(3500)).append("\n")
            if (!chosen.isNullOrBlank()) sb.append("Зрители проголосовали за вариант: «${chosen.trim()}». Продолжай именно с него.\n")
        }
        return sb.toString()
    }

    fun user(topic: String): String =
        if (topic.isBlank()) {
            "Придумай новую историю на любую неожиданную тему. Не пересказывай известный сюжет: нужен свой поворот."
        } else {
            "Тема истории: ${topic.trim()}. Придумай свой поворот по этой теме, а не общий пересказ."
        }

    fun extendRequest(script: String, minSec: Int, format: ToonFormat): String =
        "Серия получилась короче, чем нужно: нужна не короче $minSec секунд, то есть не меньше ${linesFor(minSec)} реплик. " +
            "Допиши историю, добавив события и диалоги в середину, и верни ПОЛНЫЙ сценарий в том же формате" +
            (if (format == ToonFormat.CHOICE) ", заканчивая строкой «Выбор: …» с двумя вариантами." else ", без строки «Выбор».") +
            "\n\n" + script
    
    /** Достаёт сценарий из ответа модели: убирает ограждения кода и вступления, проверяет разбором. */
    fun extract(answer: String): String {
        val lines = answer.trim().lines().map { it.trimEnd() }.filter { !it.trim().startsWith("```") }
        // вступление до первой строки сценария отбрасываем
        val first = lines.indexOfFirst { l ->
            val k = l.trim().lowercase()
            k.startsWith("название:") || k.startsWith("сцена:") || Regex("""^\p{L}[\p{L}\p{N} _.\-]{0,23}:\s*\S""").containsMatchIn(l.trim())
        }
        val body = if (first > 0) lines.drop(first) else lines
        val out = body.joinToString("\n").trim()
        ToonScript.parse(out)
        return out
    }

    /** Достаточно ли длинный сценарий (с небольшим допуском: оценка приблизительная). */
    fun longEnough(s: ToonScript, minSec: Int): Boolean = ToonScript.estimateSeconds(s) >= minSec * 0.92

    /** Приводит сценарий к формату серии: «без выбора» убирает строку выбора, «с выбором» оставляет как есть. */
    fun applyFormat(script: String, format: ToonFormat): String =
        if (format == ToonFormat.SERIES) script.lines().filter { !it.trim().lowercase().let { k -> k.startsWith("выбор:") || k.startsWith("choice:") } }.joinToString("\n") else script
}
