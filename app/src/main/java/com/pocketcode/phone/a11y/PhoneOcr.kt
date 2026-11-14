// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone.a11y

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.pocketcode.phone.OcrLine
import com.pocketcode.phone.OcrText
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Распознавание текста на картинке экрана. Обе встроенные модели (латиница и китайский) работают на самом телефоне, на один кадр идут
 * параллельно; битмап освобождается после завершения обеих. Кириллицу эти модели не читают. Если обе модели дали ошибку, бросает исключение
 * (это не «на экране нет текста»).
 */
internal object PhoneOcr {
    private const val TAG = "PhoneOcr"

    private val chinese: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    private val latin: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /** Читает текст со скриншота в JPEG; null — картинку не удалось разобрать. */
    suspend fun readJpeg(jpeg: ByteArray): List<OcrLine>? {
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
        return read(bmp)
    }

    suspend fun read(bitmap: Bitmap): List<OcrLine> {
        val recognizers = try { listOf(chinese, latin) } catch (e: Exception) {
            bitmap.recycle()
            throw e
        }
        val image = try { InputImage.fromBitmap(bitmap, 0) } catch (e: Exception) {
            bitmap.recycle()
            throw e
        }
        val w = bitmap.width
        val h = bitmap.height
        return suspendCancellableCoroutine { continuation ->
            val remaining = AtomicInteger(recognizers.size)
            val succeeded = AtomicInteger(0)
            val lines = Collections.synchronizedList(mutableListOf<OcrLine>())

            fun finish(result: Text?) {
                if (result != null) succeeded.incrementAndGet()
                result?.textBlocks?.forEach { block ->
                    block.lines.forEach { line ->
                        val box = line.boundingBox ?: return@forEach
                        val value = line.text.trim()
                        if (value.isNotEmpty()) {
                            lines += OcrLine(
                                value,
                                listOf(OcrText.normalize(box.left, w), OcrText.normalize(box.top, h), OcrText.normalize(box.right, w), OcrText.normalize(box.bottom, h)),
                                // часть моделей не даёт уверенности (0): считаем, что её нет
                                line.confidence.takeIf { it > 0f },
                            )
                        }
                    }
                }
                if (remaining.decrementAndGet() == 0) {
                    val complete = lines.toList()
                    bitmap.recycle()
                    if (continuation.isActive) {
                        if (succeeded.get() == 0) continuation.resumeWithException(IllegalStateException("text recognition failed"))
                        else continuation.resume(complete)
                    }
                }
            }

            for (recognizer in recognizers) try {
                val task: Task<Text> = recognizer.process(image)
                task.addOnCompleteListener { done ->
                    if (!done.isSuccessful) Log.w(TAG, "recognizer failed", done.exception)
                    finish(if (done.isSuccessful) done.result else null)
                }
            } catch (e: Exception) {
                Log.w(TAG, "recognizer failed", e)
                finish(null)
            }
        }
    }
}
