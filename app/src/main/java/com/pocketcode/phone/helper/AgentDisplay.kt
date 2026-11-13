// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone.helper

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream

/**
 * Виртуальный экран агента. Картинка идёт в ImageReader нашего процесса, поэтому скриншот не зависит от
 * скриншота службы доступности. Кадры обрабатываются не чаще 10 в секунду: если с прошлой обработки прошло меньше
 * 100 мс, обработка откладывается и берётся самый свежий кадр (старые отбрасывает acquireLatestImage).
 */
class AgentDisplay(
    private val shell: IPocketShell,
    private val bufferWidth: Int,
    private val bufferHeight: Int,
    dpi: Int,
    private val displayManager: DisplayManager,
) {
    private val thread = HandlerThread("agent-display").apply { start() }
    private val handler = Handler(thread.looper)
    private val reader = ImageReader.newInstance(bufferWidth, bufferHeight, PixelFormat.RGBA_8888, 2)
    val displayId: Int

    // ImageReader всегда имеет физический размер, с которым создан экран; логические координаты приложения поворачиваются вместе с экраном.
    private val geometry
        get() = DisplayFrameGeometry(bufferWidth, bufferHeight, displayManager.getDisplay(displayId)?.rotation ?: Surface.ROTATION_0)

    val width: Int get() = geometry.width
    val height: Int get() = geometry.height

    private val frameLock = Any()
    private var fullFrame: Bitmap? = null // кадр целиком вместе с выравниванием строк, ширина = rowStride / pixelStride
    private var hasFrame = false
    private var frameGeometry = DisplayFrameGeometry(bufferWidth, bufferHeight, Surface.ROTATION_0)
    private var lastProcessedAt = 0L
    private var pending = false
    @Volatile private var released = false
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val process = Runnable {
        pending = false
        lastProcessedAt = SystemClock.uptimeMillis()
        processLatest()
    }

    init {
        val id = try {
            shell.createDisplay(reader.surface, bufferWidth, bufferHeight, dpi)
        } catch (t: Throwable) {
            closeLocal()
            throw t
        }
        if (id < 0) {
            closeLocal()
            throw IllegalStateException("createDisplay failed")
        }
        displayId = id
        reader.setOnImageAvailableListener({ schedule() }, handler)
        // за время создания экрана кадры уже могли прийти: сначала нужен верный номер экрана, потом их обрабатываем
        handler.post { schedule() }
    }

    private fun schedule() {
        if (pending || released) return
        pending = true
        val wait = lastProcessedAt + FRAME_INTERVAL_MS - SystemClock.uptimeMillis()
        if (wait > 0) handler.postDelayed(process, wait) else handler.post(process)
    }

    private fun processLatest() {
        if (released) return
        val image = try {
            reader.acquireLatestImage()
        } catch (t: Throwable) {
            // нового кадра нет: старый за новый не выдаём
            Log.w(TAG, "acquireLatestImage failed: ${t.javaClass.simpleName}", t)
            null
        } ?: return
        try {
            val plane = image.planes[0]
            val strideWidth = plane.rowStride / plane.pixelStride
            val capturedGeometry = geometry
            synchronized(frameLock) {
                var bmp = fullFrame
                if (bmp == null || bmp.width != strideWidth) {
                    bmp?.recycle()
                    bmp = Bitmap.createBitmap(strideWidth, bufferHeight, Bitmap.Config.ARGB_8888)
                    fullFrame = bmp
                }
                bmp!!.copyPixelsFromBuffer(plane.buffer.rewind())
                hasFrame = true
                frameGeometry = capturedGeometry
            }
        } finally {
            image.close()
        }
    }

    /** Последний кадр в JPEG (длинная сторона не больше [maxSide]); кадра ещё не было — null. */
    fun latestFrameJpeg(maxSide: Int, quality: Int = 70): ByteArray? {
        val bitmap = latestFrameBitmap(maxSide) ?: return null
        return try {
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /** Копия последнего кадра, уменьшенная так, чтобы длинная сторона не превышала [maxSide]; вызывающий делает recycle. */
    fun latestFrameBitmap(maxSide: Int): Bitmap? = synchronized(frameLock) {
        val bmp = fullFrame
        if (!hasFrame || bmp == null) return null
        val g = frameGeometry
        val maxWidth = if (g.width >= g.height) minOf(g.width, maxSide) else minOf(g.width, (g.width.toLong() * maxSide / g.height).toInt().coerceAtLeast(1))
        uprightDisplayFrame(bmp, g, maxWidth, paint)
    }

    /** Кадр ещё не пришёл (экран только создан). */
    fun hasFrame(): Boolean = synchronized(frameLock) { hasFrame }

    fun release() {
        if (released) return
        released = true
        runCatching { shell.releaseDisplay(displayId) }
        closeLocal()
    }

    private fun closeLocal() {
        released = true
        handler.removeCallbacksAndMessages(null)
        // Завершаем на потоке кадров, чтобы не пересечься с обработкой кадра
        handler.post {
            reader.close()
            synchronized(frameLock) {
                fullFrame?.recycle()
                fullFrame = null
                hasFrame = false
            }
        }
        thread.quitSafely()
    }

    private companion object {
        const val TAG = "AgentDisplay"
        const val FRAME_INTERVAL_MS = 100L
    }
}
