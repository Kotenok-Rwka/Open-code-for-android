package com.pocketcode.video.toon

/** Перевод кадра ARGB в планарный YUV 4:2:0 (I420, BT.601, ограниченный диапазон) для видеокодека. */
object YuvConvert {
    fun y(r: Int, g: Int, b: Int): Int = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(16, 235)
    fun u(r: Int, g: Int, b: Int): Int = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(16, 240)
    fun v(r: Int, g: Int, b: Int): Int = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(16, 240)

    fun size(w: Int, h: Int): Int = w * h + 2 * (w / 2) * (h / 2)

    /** Раскладка в out: сначала Y (w×h), затем U, затем V (по w/2×h/2). Ширина и высота — чётные. */
    fun toI420(argb: IntArray, w: Int, h: Int, out: ByteArray) {
        require(w % 2 == 0 && h % 2 == 0) { "Размер кадра должен быть чётным" }
        require(out.size >= size(w, h)) { "Мало места под кадр" }
        val uBase = w * h
        val vBase = uBase + (w / 2) * (h / 2)
        for (j in 0 until h) {
            for (i in 0 until w) {
                val p = argb[j * w + i]
                out[j * w + i] = y((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF).toByte()
            }
        }
        for (j in 0 until h / 2) {
            for (i in 0 until w / 2) {
                var r = 0
                var g = 0
                var b = 0
                for (dy in 0..1) for (dx in 0..1) {
                    val p = argb[(2 * j + dy) * w + 2 * i + dx]
                    r += (p shr 16) and 0xFF
                    g += (p shr 8) and 0xFF
                    b += p and 0xFF
                }
                r /= 4; g /= 4; b /= 4
                val k = j * (w / 2) + i
                out[uBase + k] = u(r, g, b).toByte()
                out[vBase + k] = v(r, g, b).toByte()
            }
        }
    }
}
