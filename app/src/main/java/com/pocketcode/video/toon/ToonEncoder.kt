package com.pocketcode.video.toon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Собирает MP4 без ffmpeg: кадры рисуются в Bitmap, кодируются в H.264, звук в AAC, потом всё склеивается.
 * Видео и звук кодируются в отдельные временные файлы, чтобы не возиться с порядком кусков при записи.
 */
class ToonEncoder(
    private val w: Int,
    private val h: Int,
    private val fps: Int,
    /** Битрейт видео: текст и плоские цвета на 720p требуют больше 3 Мбит/с, иначе кадр «мылит». */
    private val bitrate: Int = 4_500_000,
) {
    private class Sink(val codec: MediaCodec, val muxer: MediaMuxer) {
        var track = -1
        var started = false
        var done = false
        val info = MediaCodec.BufferInfo()

        /** Забирает готовые куски из кодека. Возвращает true, когда кодек сообщил о конце потока. */
        fun drain(timeoutUs: Long): Boolean {
            while (true) {
                val i = codec.dequeueOutputBuffer(info, timeoutUs)
                when {
                    i == MediaCodec.INFO_TRY_AGAIN_LATER -> return done
                    i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!started) {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            started = true
                        }
                    }
                    i >= 0 -> {
                        val buf = codec.getOutputBuffer(i)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && buf != null && started) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(i, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            done = true
                            return true
                        }
                    }
                }
            }
        }

        fun nextInput(): Int {
            while (true) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) return i
                drain(0)
            }
        }

        fun finish() {
            var guard = 0
            while (!done && guard < 500) {
                drain(10_000)
                guard++
            }
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }

        fun abort() {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { muxer.release() }
        }
    }

    /** Рисует [frames] кадров; [draw] получает номер кадра и холст размером w×h. */
    fun encodeVideo(out: File, frames: Int, draw: (Int, Canvas) -> Unit, progress: (Float) -> Unit, cancelled: () -> Boolean) {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val sink = Sink(codec, MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4))
        try {
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val argb = IntArray(w * h)
            val yuv = ByteArray(YuvConvert.size(w, h))
            for (n in 0 until frames) {
                if (cancelled()) throw java.util.concurrent.CancellationException("Отменено")
                draw(n, canvas)
                bmp.getPixels(argb, 0, w, 0, 0, w, h)
                YuvConvert.toI420(argb, w, h, yuv)
                val idx = sink.nextInput()
                val pts = n * 1_000_000L / fps
                feedFrame(codec, idx, yuv, pts)
                sink.drain(0)
                if (n % 5 == 0) progress(n.toFloat() / frames)
            }
            val idx = sink.nextInput()
            codec.queueInputBuffer(idx, 0, 0, frames * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            sink.finish()
            bmp.recycle()
        } catch (e: Throwable) {
            sink.abort()
            throw e
        }
    }

    private fun feedFrame(codec: MediaCodec, idx: Int, i420: ByteArray, pts: Long) {
        val img = codec.getInputImage(idx)
        if (img != null) {
            val planes = img.planes
            for (p in 0..2) {
                val pl = planes[p]
                val pw = if (p == 0) w else w / 2
                val ph = if (p == 0) h else h / 2
                val base = when (p) { 0 -> 0; 1 -> w * h; else -> w * h + (w / 2) * (h / 2) }
                val buf = pl.buffer
                val rs = pl.rowStride
                val ps = pl.pixelStride
                for (r in 0 until ph) {
                    for (cx in 0 until pw) {
                        buf.put(r * rs + cx * ps, i420[base + r * pw + cx])
                    }
                }
            }
            codec.queueInputBuffer(idx, 0, i420.size, pts, 0)
            return
        }
        val buf = codec.getInputBuffer(idx) ?: throw IllegalStateException("Кодек не дал буфер для кадра")
        buf.clear()
        buf.put(i420)
        codec.queueInputBuffer(idx, 0, i420.size, pts, 0)
    }

    /** Кодирует звук (моно 16 бит) в AAC внутри m4a. */
    fun encodeAudio(out: File, samples: ShortArray, rate: Int, cancelled: () -> Boolean) {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val sink = Sink(codec, MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4))
        try {
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            var pos = 0
            while (pos < samples.size) {
                if (cancelled()) throw java.util.concurrent.CancellationException("Отменено")
                val idx = sink.nextInput()
                val buf = codec.getInputBuffer(idx) ?: throw IllegalStateException("Кодек не дал буфер для звука")
                buf.clear()
                val n = minOf(samples.size - pos, buf.capacity() / 2)
                val bb = buf.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until n) bb.putShort(samples[pos + i])
                codec.queueInputBuffer(idx, 0, n * 2, pos * 1_000_000L / rate, 0)
                pos += n
                sink.drain(0)
            }
            val idx = sink.nextInput()
            codec.queueInputBuffer(idx, 0, 0, samples.size * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            sink.finish()
        } catch (e: Throwable) {
            sink.abort()
            throw e
        }
    }

    /** Склеивает видеодорожку из одного файла и звук из другого в готовый MP4. */
    fun mux(video: File, audio: File?, out: File) {
        val vx = MediaExtractor().apply { setDataSource(video.absolutePath) }
        val ax = audio?.let { MediaExtractor().apply { setDataSource(it.absolutePath) } }
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            val vt = firstTrack(vx, "video/") ?: throw IllegalStateException("В видеофайле нет дорожки")
            vx.selectTrack(vt)
            val vTrack = muxer.addTrack(vx.getTrackFormat(vt))
            var aTrack = -1
            if (ax != null) {
                val at = firstTrack(ax, "audio/")
                if (at != null) {
                    ax.selectTrack(at)
                    aTrack = muxer.addTrack(ax.getTrackFormat(at))
                }
            }
            muxer.start()
            started = true
            val buf = ByteBuffer.allocate(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val vTime = vx.sampleTime
                val aTime = if (aTrack >= 0 && ax != null) ax.sampleTime else -1L
                if (vTime < 0 && aTime < 0) break
                val useVideo = vTime >= 0 && (aTime < 0 || vTime <= aTime)
                val src = if (useVideo) vx else ax!!
                buf.clear()
                val size = src.readSampleData(buf, 0)
                if (size < 0) {
                    // дорожка кончилась раньше времени: пропускаем её дальше
                    if (useVideo) break else { src.advance(); continue }
                }
                info.set(0, size, src.sampleTime, src.sampleFlags)
                muxer.writeSampleData(if (useVideo) vTrack else aTrack, buf, info)
                src.advance()
            }
        } finally {
            if (started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            vx.release()
            ax?.release()
        }
    }

    private fun firstTrack(x: MediaExtractor, prefix: String): Int? {
        for (i in 0 until x.trackCount) {
            if (x.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true) return i
        }
        return null
    }
}
