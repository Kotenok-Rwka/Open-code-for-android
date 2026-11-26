package com.pocketcode.video.toon

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/** Звук: один канал, 16 бит. */
class Pcm(val rate: Int, val samples: ShortArray) {
    val seconds: Double get() = samples.size.toDouble() / rate
}

/** Чтение и запись WAV и простые операции со звуком. Без Android, поэтому покрыто unit-тестами. */
object Wav {
    /** Разбирает WAV: 8/16/24/32 бита целые или 32 бита с плавающей точкой, любое число каналов (смешиваются в один). */
    fun parse(bytes: ByteArray): Pcm {
        require(bytes.size > 44 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "Это не WAV-файл" }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var format = 1
        var channels = 1
        var rate = 22050
        var bits = 16
        var dataStart = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val len = bb.getInt(pos + 4)
            val body = pos + 8
            if (id == "fmt " && body + 16 <= bytes.size) {
                format = bb.getShort(body).toInt() and 0xFFFF
                channels = bb.getShort(body + 2).toInt().coerceAtLeast(1)
                rate = bb.getInt(body + 4)
                bits = bb.getShort(body + 14).toInt()
            } else if (id == "data") {
                dataStart = body
                // у потоковых файлов длина может быть 0 или -1: берём всё до конца
                dataLen = if (len <= 0 || body + len > bytes.size) bytes.size - body else len
                break
            }
            if (len < 0) break
            pos = body + len + (len and 1)
        }
        require(dataStart >= 0) { "В WAV нет звуковых данных" }
        val bps = bits / 8
        require(bps in 1..4) { "Неподдерживаемая глубина звука: $bits бит" }
        val frames = dataLen / (bps * channels)
        val out = ShortArray(frames)
        val float = format == 3 && bits == 32
        for (i in 0 until frames) {
            var sum = 0.0
            for (c in 0 until channels) {
                val o = dataStart + (i * channels + c) * bps
                sum += when {
                    float -> (bb.getFloat(o) * 32767.0)
                    bps == 1 -> ((bytes[o].toInt() and 0xFF) - 128) * 256.0
                    bps == 2 -> bb.getShort(o).toDouble()
                    bps == 3 -> (((bytes[o + 2].toInt() shl 16) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or (bytes[o].toInt() and 0xFF)) / 256.0)
                    else -> bb.getInt(o) / 65536.0
                }
            }
            out[i] = (sum / channels).coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
        return Pcm(rate, out)
    }

    fun write(p: Pcm): ByteArray {
        val data = p.samples.size * 2
        val bb = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(p.rate).putInt(p.rate * 2).putShort(2).putShort(16)
        bb.put("data".toByteArray()).putInt(data)
        for (s in p.samples) bb.putShort(s)
        return bb.array()
    }

    /** Линейный пересчёт частоты дискретизации. */
    fun resample(p: Pcm, rate: Int): Pcm {
        if (p.rate == rate || p.samples.isEmpty()) return Pcm(rate, p.samples)
        val n = (p.samples.size.toLong() * rate / p.rate).toInt()
        val out = ShortArray(n)
        val step = p.rate.toDouble() / rate
        for (i in 0 until n) {
            val x = i * step
            val a = x.toInt().coerceAtMost(p.samples.size - 1)
            val b = (a + 1).coerceAtMost(p.samples.size - 1)
            val f = x - a
            out[i] = (p.samples[a] * (1 - f) + p.samples[b] * f).toInt().toShort()
        }
        return Pcm(rate, out)
    }

    /** Обрезает тишину по краям, оставляя не больше [keepSec] с каждой стороны. */
    fun trim(p: Pcm, threshold: Int = 400, keepSec: Double = 0.08): Pcm {
        val s = p.samples
        var a = 0
        while (a < s.size && abs(s[a].toInt()) < threshold) a++
        if (a >= s.size) return Pcm(p.rate, ShortArray(0))
        var b = s.size - 1
        while (b > a && abs(s[b].toInt()) < threshold) b--
        val keep = (keepSec * p.rate).toInt()
        val from = (a - keep).coerceAtLeast(0)
        val to = (b + keep + 1).coerceAtMost(s.size)
        return Pcm(p.rate, s.copyOfRange(from, to))
    }

    /**
     * Выравнивает громкость реплики: у разных героев и TTS-движков уровень сильно разный,
     * без этого один орёт, а другой шепчет. Пик приводится к [target] от полной шкалы.
     */
    fun normalize(p: Pcm, target: Double = 0.89): Pcm {
        if (p.samples.isEmpty()) return p
        var peak = 0
        for (s in p.samples) {
            val v = if (s < 0) -s.toInt() else s.toInt()
            if (v > peak) peak = v
        }
        if (peak < 200) return p // тишина или почти тишина — не усиливаем шум
        val k = target * 32767.0 / peak
        if (k in 0.97..1.03) return p
        return Pcm(p.rate, ShortArray(p.samples.size) { (p.samples[it] * k).toInt().coerceIn(-32768, 32767).toShort() })
    }

    /** Короткие плавные вход и выход: без щелчков на стыках реплик. */
    fun fade(p: Pcm, inMs: Int = 10, outMs: Int = 14): Pcm {
        if (p.samples.isEmpty()) return p
        val a = (p.rate.toLong() * inMs / 1000).toInt().coerceAtMost(p.samples.size / 2)
        val b = (p.rate.toLong() * outMs / 1000).toInt().coerceAtMost(p.samples.size / 2)
        val out = p.samples.copyOf()
        for (i in 0 until a) out[i] = (out[i] * (i.toDouble() / a)).toInt().toShort()
        for (i in 0 until b) {
            val j = out.size - 1 - i
            out[j] = (out[j] * (i.toDouble() / b)).toInt().toShort()
        }
        return Pcm(p.rate, out)
    }

    fun concat(parts: List<Pcm>, gapSec: Double = 0.0): Pcm {
        if (parts.isEmpty()) return Pcm(24000, ShortArray(0))
        val rate = parts.first().rate
        val gap = (gapSec * rate).toInt()
        val all = ArrayList<Short>()
        for ((i, p) in parts.withIndex()) {
            val r = if (p.rate == rate) p else resample(p, rate)
            for (v in r.samples) all += v
            if (i < parts.size - 1) repeat(gap) { all += 0 }
        }
        return Pcm(rate, all.toShortArray())
    }

    /**
     * Громкость по кадрам видео: для каждого из [frames] кадров среднеквадратичное значение звука в его окне,
     * приведённое к 0..1 относительно «громкого» уровня речи.
     */
    fun envelope(p: Pcm, fps: Int, frames: Int): FloatArray {
        val out = FloatArray(frames)
        if (p.samples.isEmpty() || frames == 0) return out
        val win = p.rate / fps
        var peak = 1.0
        for (f in 0 until frames) {
            val from = f * win
            val to = minOf(p.samples.size, from + win)
            if (from >= to) break
            var acc = 0.0
            for (i in from until to) acc += p.samples[i].toDouble() * p.samples[i]
            val rms = sqrt(acc / (to - from))
            out[f] = rms.toFloat()
            if (rms > peak) peak = rms
        }
        // уровень «громко» — около 85% от пика, чтобы одиночные всплески не прижимали остальное
        val ref = (peak * 0.85).coerceAtLeast(1.0)
        for (f in 0 until frames) out[f] = (out[f] / ref).toFloat().coerceIn(0f, 1f)
        return out
    }
}
