package com.pocketcode.video.toon

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Batch 24: живая анимация героев и реквизит сцены.
 * - Плавное моргание (фаза закрытия/открытия, а не 3 кадра), дыхание, покачивание головы.
 * - Руки с локтями: жесты говорящего, поза «держит чашку» у рта.
 * - Реквизит кодом: стол, окно, лампа, ТВ, растение… расстановка из строки сценария
 *   «КОМНАТА: стол слева, окно по центру» — нейронка управляет содержимым текстом.
 * Вся геометрия — чистые функции (тестируется без Android), рисование — в ToonDraw.
 */

/** Параметры анимации одного героя в кадре (в локальных координатах героя, масштаб 1). */
data class ToonPose(
    /** 1 — глаза открыты, 0 — закрыты. */
    val eyeOpen: Float,
    /** Смещение всего тела от дыхания (px вверх-вниз). */
    val breath: Float,
    /** Наклон головы, градусы. */
    val headTilt: Float,
    /** Углы рук: [левое плечо, левый локоть, правое плечо, правый локоть], градусы. */
    val arms: FloatArray,
    /** Герой держит предмет у рта (чашка): правая рука согнута к лицу. */
    val holdsProp: Boolean,
) {
    override fun equals(other: Any?): Boolean = other is ToonPose &&
        eyeOpen == other.eyeOpen && breath == other.breath && headTilt == other.headTilt &&
        holdsProp == other.holdsProp && arms.contentEquals(other.arms)
    override fun hashCode(): Int = arms.contentHashCode() * 31 + eyeOpen.hashCode()
}

/** Настроение героя (чистое перечисление для логики/тестов; рисуется как ToonDraw.Mood). */
enum class ToonMood { CALM, ASK, WOW, SAD }

object ToonAnim {

    /**
     * Плавное моргание: каждые ~3.4 с глаз закрывается и открывается за 0.24 с (косинус-фаза).
     * Всегда 0..1, без рывков.
     */
    fun eyeOpen(t: Double, period: Double = 3.4): Float {
        if (period <= 0.0) return 1f
        val ph = t % period
        val closeAt = period - 0.24
        if (ph < closeAt) return 1f
        val k = ((ph - closeAt) / 0.24).coerceIn(0.0, 1.0)
        return (0.5 + 0.5 * Math.cos(2 * PI * k)).toFloat() // 1→0→1 плавно, минимум в середине фазы
    }

    /** Дыхание: медленный подъём-спад ±2.5 px, у говорящего лёгкий акцент от рта. */
    fun breath(t: Double, talking: Boolean, open: Float): Float {
        val base = sin(2.0 * PI * t / 3.1) * 2.5
        val talk = if (talking) sin(2.0 * PI * t / 0.9) * 1.2 * open else 0.0
        return (base + talk).toFloat()
    }

    /** Покачивание головы: медленный наклон ±3°, у WOW сильнее. */
    fun headTilt(t: Double, mood: ToonMood): Float {
        val base = (sin(2.0 * PI * t / 4.7) * 3.0).toFloat()
        return if (mood == ToonMood.WOW) base * 1.6f else base
    }

    /**
     * Руки: угол плеча и локтя. Говорящий жестикулирует плавно (две гармоники),
     * слушающий почти неподвижен. Если holds — правая рука согнута к лицу (чашка у рта).
     */
    fun arms(t: Double, talking: Boolean, open: Float, holds: Boolean): FloatArray {
        val lSh = if (talking) ((sin(2.0 * PI * t / 1.7) * 10.0 + sin(2.0 * PI * t / 0.8) * 5.0 * open).toFloat())
        else (sin(2.0 * PI * t / 4.7) * 2.0).toFloat()
        val rSh = if (talking) ((sin(2.0 * PI * t / 1.3 + 1.1) * 12.0 + sin(2.0 * PI * t / 0.7 + 0.5) * 5.0 * open).toFloat())
        else (sin(2.0 * PI * t / 4.7 + 1.0) * 2.0).toFloat()
        val lEl = 8f + abs(sin(2.0 * PI * t / 2.3)).toFloat() * (if (talking) 14f else 3f)
        val rSh2: Float
        val rEl: Float
        if (holds) {
            // правая рука поднята и согнута: чашка у рта
            rSh2 = -95f + (sin(2.0 * PI * t / 3.1) * 2.0).toFloat()
            rEl = 95f
        } else {
            rSh2 = rSh
            rEl = 8f + abs(sin(2.0 * PI * t / 2.0 + 0.4)).toFloat() * (if (talking) 14f else 3f)
        }
        return floatArrayOf(lSh, lEl, rSh2, rEl)
    }

    /** Собрать позу кадра. */
    fun pose(t: Double, talking: Boolean, open: Float, mood: ToonMood, holds: Boolean): ToonPose =
        ToonPose(eyeOpen(t), breath(t, talking, open), headTilt(t, mood), arms(t, talking, open, holds), holds)
}

/** Реквизит: кодовый объект сцены или своя картинка. */
data class ToonProp(
    val kind: Kind,
    /** Слот: доля ширины и высоты кадра. */
    val x: Float,
    val y: Float,
    val scale: Float,
    /** Для CUSTOM: путь к PNG в проекте. */
    val path: String = "",
) {
    enum class Kind { TABLE, CHAIR, WINDOW, LAMP, TV, PLANT, SHELF, CUP, PICTURE, CUSTOM }

    companion object {
        /** Слоты фона: пол/стена. y — над полом (пол на 0.74 высоты). */
        fun slot(where: String): Pair<Float, Float> = when (where) {
            "слева" -> 0.16f to 0.62f
            "справа" -> 0.84f to 0.62f
            "над" -> 0.5f to 0.24f
            "вцентре", "центр" -> 0.5f to 0.62f
            else -> 0.30f to 0.62f
        }
    }
}

object ToonProps {
    private val words = mapOf(
        "стол" to ToonProp.Kind.TABLE, "стул" to ToonProp.Kind.CHAIR,
        "окно" to ToonProp.Kind.WINDOW, "лампа" to ToonProp.Kind.LAMP,
        "тв" to ToonProp.Kind.TV, "телевизор" to ToonProp.Kind.TV,
        "растение" to ToonProp.Kind.PLANT, "цветок" to ToonProp.Kind.PLANT,
        "полка" to ToonProp.Kind.SHELF, "чашка" to ToonProp.Kind.CUP,
        "картина" to ToonProp.Kind.PICTURE, "постер" to ToonProp.Kind.PICTURE,
    )

    /**
     * Разбор строки «КОМНАТА: стол слева, окно в центре, картина над диваном».
     * Свой PNG: «моё:имя.png слева». Неизвестные слова пропускаются.
     */
    fun parse(line: String): List<ToonProp> {
        val body = line.substringAfter(':', "").trim()
        if (body.isEmpty()) return emptyList()
        val out = ArrayList<ToonProp>()
        for (item in body.split(',', ';')) {
            val t = item.trim().lowercase().replace("по ", "").replace(" ", "")
            if (t.isEmpty()) continue
            var where = "слева"
            var word = t
            when {
                t.endsWith("слева") -> word = t.removeSuffix("слева")
                t.endsWith("справа") -> { where = "справа"; word = t.removeSuffix("справа") }
                t.contains("над") && t.indexOf("над") > 0 -> { where = "над"; word = t.substringBefore("над") }
                t.contains("центр") -> {
                    where = "вцентре"
                    word = t.replace("вцентре", "").replace("центру", "").replace("центр", "")
                }
            }
            if (word.startsWith("моё:") || word.startsWith("мое:")) {
                val p = word.substringAfter(':')
                if (p.isNotBlank()) {
                    val (x, y) = ToonProp.slot(where)
                    out += ToonProp(ToonProp.Kind.CUSTOM, x, y, 1f, p)
                }
                continue
            }
            val kind = words[word] ?: continue
            val (x, y) = ToonProp.slot(where)
            out += ToonProp(kind, x, y, 1f)
        }
        return out
    }

    /** Найти строки реквизита в сценарии (строки, начинающиеся с КОМНАТА/СЦЕНА/РЕКВИЗИТ:). */
    fun fromScript(script: String): List<ToonProp> =
        script.lines().filter { l ->
            val h = l.trim().uppercase()
            listOf("КОМНАТА:", "СЦЕНА:", "РЕКВИЗИТ:").any { h.startsWith(it) }
        }.flatMap { parse(it.trim()) }

    /** Есть ли чашка в реквизите (тогда говорящий держит её у рта). */
    fun hasCup(props: List<ToonProp>): Boolean = props.any { it.kind == ToonProp.Kind.CUP }
}
