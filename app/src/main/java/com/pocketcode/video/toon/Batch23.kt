package com.pocketcode.video.toon

/**
 * Batch 23: детализированный кодовый рендер (без сторонней генерации картинок).
 * Вся «картинка» считается кодом: палитры сцен, узор обоев, драпировка штор,
 * плинтус, картины, тёплый свет лампы, мягкие тени героев и виньетка.
 * Здесь — чистая геометрия и палитры (тестируется без Android), рисование — в ToonDraw.
 */

/** Палитра богатого фона одной сцены. Все цвета в ARGB. */
data class ToonRich(
    val scene: ToonScene,
    val wallTop: Int,
    val wallBottom: Int,
    /** Цвет узора обоев (для комнатных сцен); 0 — обоев нет. */
    val wallpaper: Int,
    val curtain: Int,
    val curtainDark: Int,
    val floor: Int,
    val baseboard: Int,
    val accent: Int,
    /** Тёплый свет лампы: 0 — нет. */
    val lamp: Int,
) {
    /** Строка «сцена|цвета…» для библиотеки фонов (фон можно сохранить и доработать). */
    fun toLine(): String = listOf(scene.name, hex(wallTop), hex(wallBottom), hex(wallpaper),
        hex(curtain), hex(curtainDark), hex(floor), hex(baseboard), hex(accent), hex(lamp)).joinToString("|")

    companion object {
        fun hex(c: Int) = Integer.toHexString(c).padStart(8, '0')
        fun parseHex(s: String): Int = s.toLong(16).toInt()

        fun fromLine(ln: String): ToonRich? {
            val p = ln.split("|")
            if (p.size < 10) return null
            val scene = runCatching { ToonScene.valueOf(p[0]) }.getOrNull() ?: return null
            return try {
                ToonRich(scene, parseHex(p[1]), parseHex(p[2]), parseHex(p[3]), parseHex(p[4]),
                    parseHex(p[5]), parseHex(p[6]), parseHex(p[7]), parseHex(p[8]), parseHex(p[9]))
            } catch (_: NumberFormatException) { null }
        }
    }
}

object ToonRichLib {
    /** Базовые палитры всех сцен — «как в хороших TikTok-мультиках»: тёплые стены, узор, шторы. */
    fun defaults(): List<ToonRich> = listOf(
        ToonRich(ToonScene.ROOM, 0xFFD9B38C.toInt(), 0xFFB9855C.toInt(), 0xFFA96B3F.toInt(),
            0xFF7A3B2E.toInt(), 0xFF5C2A21.toInt(), 0xFF8A5A3B.toInt(), 0xFFE8D8C0.toInt(), 0xFFE4B04A.toInt(), 0xFFFFE3A3.toInt()),
        ToonRich(ToonScene.NIGHT, 0xFF16214A.toInt(), 0xFF0B1330.toInt(), 0xFF22306A.toInt(),
            0xFF2C3B74.toInt(), 0xFF1A2450.toInt(), 0xFF3A4778.toInt(), 0xFF8FA0D0.toInt(), 0xFF9FB6FF.toInt(), 0xFFFFF4C2.toInt()),
        ToonRich(ToonScene.SEA, 0xFFBFE6F7.toInt(), 0xFF8FCBE8.toInt(), 0x00000000,
            0xFF2E86C1.toInt(), 0xFF1F6B9E.toInt(), 0xFFE9D8A6.toInt(), 0xFFF2E7C9.toInt(), 0xFFFFD84D.toInt(), 0xFFFFE9B0.toInt()),
        ToonRich(ToonScene.CITY, 0xFFFFB37B.toInt(), 0xFFE07B54.toInt(), 0xFFC9663F.toInt(),
            0xFF6E3B52.toInt(), 0xFF532A3C.toInt(), 0xFF4A3B52.toInt(), 0xFFD9C6A5.toInt(), 0xFFFFC857.toInt(), 0xFFFFD98E.toInt()),
        ToonRich(ToonScene.GAS, 0xFF9AA7B5.toInt(), 0xFF6E7B8C.toInt(), 0x00000000,
            0xFF3E4A5C.toInt(), 0xFF2C3546.toInt(), 0xFF57565E.toInt(), 0xFFC9CED6.toInt(), 0xFFFF6B4A.toInt(), 0xFFFFC08A.toInt()),
        ToonRich(ToonScene.TENT, 0xFF2F4A33.toInt(), 0xFF1E3323.toInt(), 0xFF3C5A40.toInt(),
            0xFF4A6741.toInt(), 0xFF354C2F.toInt(), 0xFF54452F.toInt(), 0xFFB9A87E.toInt(), 0xFFFFA94D.toInt(), 0xFF9FB98F.toInt()),
        ToonRich(ToonScene.BUNKER, 0xFF4A4E52.toInt(), 0xFF2B2E33.toInt(), 0xFF3A3E44.toInt(),
            0xFF2C3038.toInt(), 0xFF1F232A.toInt(), 0xFF3E424A.toInt(), 0xFF9AA0A8.toInt(), 0xFFB8FF6E.toInt(), 0xFFFFF0A8.toInt()),
    )

    /** Сериализация библиотеки: строка на сцену, «#»-комментарии игнорируются. */
    fun serialize(all: List<ToonRich>): String = all.joinToString("\n") { it.toLine() }

    fun parse(text: String): List<ToonRich> =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { ToonRich.fromLine(it) }

    /** Заменить палитру сцены пользовательской, остальные взять из базовых. */
    fun merge(defaults: List<ToonRich>, custom: List<ToonRich>): List<ToonRich> {
        val byScene = custom.associateBy { it.scene }
        return (defaults.map { byScene[it.scene] ?: it } +
            custom.filter { c -> defaults.none { it.scene == c.scene } })
    }
}

/** Геометрия деталей фона: всё детерминировано — кадр всегда одинаковый. */
object ToonRichGeom {

    /** Узор обоев: шахматный тайл (ромбы) — координаты центров узора. */
    fun wallpaperTiles(w: Int, h: Int, cell: Int = 64): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        var row = 0
        var y = cell / 2f
        while (y < h) {
            val shift = if (row % 2 == 0) 0f else cell / 2f
            var x = cell / 2f + shift
            while (x < w) {
                out += x to y
                x += cell
            }
            row++
            y += cell / 2f
        }
        return out
    }

    /** Драпировка шторы: x-координаты складок по ширине полотна (сгущаются к внутреннему краю). */
    fun curtainFolds(edgeX: Float, width: Float, count: Int): List<Float> {
        if (count <= 0 || width <= 0f) return emptyList()
        return (1..count).map { i ->
            val k = i.toDouble() / (count + 1)
            val t = Math.pow(k, 0.7)
            edgeX + (width * t).toFloat()
        }
    }

    /** Тень героя на полу: [x, y, rx, ry] со смещением от «света» сверху-по-центру. */
    fun heroShadow(heroCx: Float, groundY: Float, heroScale: Float): FloatArray {
        val dx = -(heroCx - 360f) * 0.10f * heroScale
        return floatArrayOf(heroCx + dx, groundY, 130f * heroScale, 26f * heroScale)
    }

    /** Прогресс качания маятника часов/люстры в момент t (без рывков). */
    fun sway(t: Double, ampDeg: Float = 4f, period: Double = 3.0): Float =
        (ampDeg * Math.sin(2.0 * Math.PI * t / period)).toFloat()

    /** Сила виньетки к краю кадра: 0 в центре, до max в углах. */
    fun vignetteAlpha(x: Float, y: Float, w: Int, h: Int, max: Int = 90): Int {
        val nx = (x - w / 2f) / (w / 2f)
        val ny = (y - h / 2f) / (h / 2f)
        val d = Math.sqrt((nx * nx + ny * ny).toDouble()).coerceAtMost(1.414)
        return (max * d / 1.414).toInt().coerceIn(0, max)
    }

    /** Тёплый свет лампы: радиус пятна по мощности источника (0..1). */
    fun lampRadius(w: Int, power: Float): Float = w * (0.45f + 0.25f * power.coerceIn(0f, 1f))

    /** Рамка картины/постера на стене: [left, top, right, bottom]. */
    fun pictureFrame(w: Int, h: Int, cx: Float, cy: Float, scale: Float): FloatArray {
        val fw = w * 0.22f * scale
        val fh = w * 0.28f * scale
        return floatArrayOf(cx - fw / 2, cy - fh / 2, cx + fw / 2, cy + fh / 2)
    }
}
