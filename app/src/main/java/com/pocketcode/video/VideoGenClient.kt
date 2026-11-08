package com.pocketcode.video

import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Настройки создания видео по API (ключи хранятся зашифрованно, у каждого сервиса свой). */
data class GenSettings(
    val provider: GenProvider = GenProvider.FAL,
    val model: String = "",
    val keys: Map<GenProvider, String> = emptyMap(),
)

private const val POLL_MS = 4000L
private const val MAX_WAIT_SEC = 20 * 60L

/**
 * Создание видео нейросетью через API с ключом пользователя.
 * Не требует среды Linux: готовый файл кладётся в папку проекта (video/gen_*.mp4), дальше его можно монтировать как обычное видео.
 */
class VideoGenerator(
    private val root: () -> File,
    private val load: () -> GenSettings,
    private val save: (GenSettings) -> Unit,
    private val keepAlive: () -> Unit,
    private val release: () -> Unit,
    private val proxy: () -> String,
) {
    var settings by mutableStateOf(load())
        private set
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var resultRel by mutableStateOf<String?>(null)
        private set
    var test by mutableStateOf("")
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private val running = AtomicBoolean(false)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val key: String get() = settings.keys[settings.provider].orEmpty()
    val preset: GenPreset get() = GenPresets.resolve(settings.provider, settings.model)

    fun update(s: GenSettings) {
        settings = s
        save(s)
    }

    fun dismiss() {
        if (!busy) {
            error = null
            status = ""
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun generateUi(r: GenRequest) {
        job = scope.launch {
            try {
                generate(r)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // ошибка уже показана на экране
            }
        }
    }

    fun testUi() {
        job = scope.launch {
            test = "Проверяю…"
            test = try { ping() } catch (e: CancellationException) { throw e } catch (e: Throwable) { e.message ?: "Ошибка" }
        }
    }

    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .apply { parseProxy(proxy())?.let { proxy(it) } }
        .build()

    private suspend fun call(c: OkHttpClient, req: Request): Pair<Int, String> = suspendCancellableCoroutine { cont ->
        val call = c.newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWith(Result.failure(IOException("Нет связи с сервисом: ${e.message ?: e.javaClass.simpleName}. Проверьте интернет, VPN или прокси в настройках.")))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    val text = try { r.body?.string().orEmpty() } catch (e: IOException) { "" }
                    if (cont.isActive) cont.resumeWith(Result.success(r.code to text))
                }
            }
        })
    }

    private fun authed(b: Request.Builder, p: GenProvider, key: String): Request.Builder = when (p) {
        GenProvider.FAL -> b.header("Authorization", "Key $key")
        GenProvider.REPLICATE -> b.header("Authorization", "Bearer $key")
        GenProvider.GEMINI -> b.header("x-goog-api-key", key)
    }

    private val jsonType = "application/json".toMediaType()

    /** Проверка ключа без расходов: у fal.ai бесплатной проверки нет, поэтому проверяем только формат. */
    private suspend fun ping(): String {
        val p = settings.provider
        val k = key.trim()
        if (k.isEmpty()) return "Сначала вставьте ключ"
        return when (p) {
            GenProvider.REPLICATE -> {
                val (code, body) = call(client(), authed(Request.Builder().url("https://api.replicate.com/v1/account"), p, k).get().build())
                if (code in 200..299) "Ключ принят" else explainGenHttp(p, code, body)
            }
            GenProvider.GEMINI -> {
                val (code, body) = call(client(), authed(Request.Builder().url("${p.host}/v1beta/models?pageSize=1"), p, k).get().build())
                if (code in 200..299) "Ключ принят" else explainGenHttp(p, code, body)
            }
            GenProvider.FAL -> "Ключ сохранён. У fal.ai нет бесплатной проверки: ошибка покажется при первом видео."
        }
    }

    suspend fun generate(r: GenRequest): String {
        if (!running.compareAndSet(false, true)) throw IllegalStateException("Видео уже создаётся")
        busy = true
        error = null
        resultRel = null
        keepAlive()
        try {
            require(r.prompt.isNotBlank()) { "Опишите, какое видео нужно" }
            val k = key.trim()
            require(k.isNotEmpty()) { "Вставьте ключ ${settings.provider.label} в блоке «Создание видео по API»" }
            val started = System.currentTimeMillis()
            val tick = { status: String -> this.status = status + " · " + genElapsed((System.currentTimeMillis() - started) / 1000) }
            val bytes = withContext(Dispatchers.IO) { produce(r, k, tick) }
            val rel = "video/" + genFileName(r.prompt, System.currentTimeMillis().toString())
            val f = File(root(), rel)
            f.parentFile?.mkdirs()
            withContext(Dispatchers.IO) { f.writeBytes(bytes) }
            status = "Готово"
            resultRel = rel
            return rel
        } catch (e: CancellationException) {
            status = "Остановлено"
            throw e
        } catch (e: Throwable) {
            error = e.message ?: e.javaClass.simpleName
            status = ""
            throw e
        } finally {
            busy = false
            running.set(false)
            release()
        }
    }

    private suspend fun produce(r: GenRequest, k: String, tick: (String) -> Unit): ByteArray {
        val p = settings.provider
        val pr = preset
        val c = client()
        tick("Отправляю запрос")
        return when (p) {
            GenProvider.FAL -> {
                val (code, body) = call(c, authed(Request.Builder().url("${p.host}/${pr.model.trim().trim('/')}"), p, k)
                    .post(buildGenInput(pr, r).toString().toRequestBody(jsonType)).build())
                if (code !in 200..299) throw IOException(explainGenHttp(p, code, body))
                val sub = parseFalSubmit(body, pr.model)
                try {
                    waitFal(c, k, sub, tick)
                } catch (e: CancellationException) {
                    // отмену на стороне сервиса не ждём: не повисаем, если сеть пропала
                    runCatching { c.newCall(authed(Request.Builder().url(sub.cancelUrl), p, k).put("".toRequestBody(jsonType)).build()).enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {}
                        override fun onResponse(call: Call, response: Response) { response.close() }
                    }) }
                    throw e
                }
            }
            GenProvider.REPLICATE -> {
                val (code, body) = call(c, authed(Request.Builder().url("${p.host}/v1/models/${pr.model.trim().trim('/')}/predictions"), p, k)
                    .post(buildReplicateBody(pr, r).toString().toRequestBody(jsonType)).build())
                if (code !in 200..299) throw IOException(explainGenHttp(p, code, body))
                waitReplicate(c, k, parseReplicate(body), tick)
            }
            GenProvider.GEMINI -> {
                val (code, body) = call(c, authed(Request.Builder().url("${p.host}/v1beta/interactions"), p, k)
                    .post(buildGeminiBody(pr, r).toString().toRequestBody(jsonType)).build())
                if (code !in 200..299) throw IOException(explainGenHttp(p, code, body))
                waitGemini(c, k, body, tick)
            }
        }
    }

    private suspend fun waitFal(c: OkHttpClient, k: String, sub: FalSubmit, tick: (String) -> Unit): ByteArray {
        val p = GenProvider.FAL
        val t0 = System.currentTimeMillis()
        while (true) {
            if ((System.currentTimeMillis() - t0) / 1000 > MAX_WAIT_SEC) throw IOException("Видео не готово за ${MAX_WAIT_SEC / 60} минут. Проверьте очередь в кабинете fal.ai.")
            val (code, body) = call(c, authed(Request.Builder().url(sub.statusUrl), p, k).get().build())
            if (code !in 200..299) throw IOException(explainGenHttp(p, code, body))
            val st = parseFalStatus(body)
            when (st.status) {
                "COMPLETED" -> {
                    if (st.error != null) throw IOException("Сервис не смог создать видео: ${st.error}")
                    val (c2, b2) = call(c, authed(Request.Builder().url(sub.responseUrl), p, k).get().build())
                    if (c2 !in 200..299) throw IOException(explainGenHttp(p, c2, b2))
                    val url = findVideoUrl(b2) ?: throw IOException("В ответе нет ссылки на видео: ${apiErrorText(b2)}")
                    tick("Скачиваю видео")
                    return download(c, url, null)
                }
                "IN_QUEUE" -> tick("В очереди" + (st.queuePosition?.let { ", позиция $it" } ?: ""))
                else -> tick("Создаю видео")
            }
            delay(POLL_MS)
        }
    }

    private suspend fun waitReplicate(c: OkHttpClient, k: String, first: RepPrediction, tick: (String) -> Unit): ByteArray {
        val p = GenProvider.REPLICATE
        var cur = first
        val t0 = System.currentTimeMillis()
        while (true) {
            when (cur.status) {
                "succeeded" -> {
                    val url = findVideoUrl(cur.output) ?: throw IOException("В ответе нет ссылки на видео")
                    tick("Скачиваю видео")
                    return download(c, url, null)
                }
                "failed", "canceled" -> throw IOException("Сервис не смог создать видео: ${cur.error ?: cur.status}")
                else -> tick(if (cur.status == "starting") "Запускаю модель" else "Создаю видео")
            }
            if ((System.currentTimeMillis() - t0) / 1000 > MAX_WAIT_SEC) {
                throw IOException("Видео не готово за ${MAX_WAIT_SEC / 60} минут. Проверьте запрос в кабинете Replicate.")
            }
            delay(POLL_MS)
            val (code, body) = call(c, authed(Request.Builder().url(cur.getUrl), p, k).get().build())
            if (code !in 200..299) throw IOException(explainGenHttp(p, code, body))
            cur = parseReplicate(body)
        }
    }

    private suspend fun waitGemini(c: OkHttpClient, k: String, firstBody: String, tick: (String) -> Unit): ByteArray {
        val p = GenProvider.GEMINI
        var body = firstBody
        val t0 = System.currentTimeMillis()
        while (true) {
            when (val v = parseGeminiVideo(body)) {
                is GeminiVideo.Inline -> return Base64.decode(v.base64, Base64.DEFAULT)
                is GeminiVideo.Uri -> {
                    tick("Скачиваю видео")
                    return download(c, v.uri, k)
                }
                null -> Unit
            }
            val o: JsonObject = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: throw IOException("Gemini вернул не JSON")
            val st = o["status"]?.jsonPrimitive?.content?.lowercase().orEmpty()
            val id = o["id"]?.jsonPrimitive?.content
            if (st == "failed" || st == "cancelled" || st == "canceled" || id == null || st == "completed") {
                throw IOException("В ответе Gemini нет видео: ${apiErrorText(body)}")
            }
            if ((System.currentTimeMillis() - t0) / 1000 > MAX_WAIT_SEC) throw IOException("Видео не готово за ${MAX_WAIT_SEC / 60} минут")
            tick("Создаю видео")
            delay(POLL_MS)
            val (code, b) = call(c, authed(Request.Builder().url("${p.host}/v1beta/interactions/$id"), p, k).get().build())
            if (code !in 200..299) throw IOException(explainGenHttp(p, code, b))
            body = b
        }
    }

    /** Скачивает файл. Ключ Google отправляем только на адреса Google, чужим хостам ключ не показываем. */
    private suspend fun download(c: OkHttpClient, url: String, googleKey: String?): ByteArray {
        val google = url.contains("googleapis.com")
        val u = if (google && !url.contains("alt=media") && url.contains("/files/")) url + (if ('?' in url) "&" else "?") + "alt=media" else url
        val req = Request.Builder().url(u).apply { if (googleKey != null && google) header("x-goog-api-key", googleKey) }.get().build()
        return suspendCancellableCoroutine { cont ->
            val call = c.newCall(req)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWith(Result.failure(IOException("Не удалось скачать видео: ${e.message ?: e.javaClass.simpleName}")))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { r ->
                        if (!cont.isActive) return
                        if (!r.isSuccessful) {
                            cont.resumeWith(Result.failure(IOException("Не удалось скачать видео (HTTP ${r.code})")))
                            return
                        }
                        try {
                            val data = r.body?.bytes() ?: ByteArray(0)
                            if (data.isEmpty()) cont.resumeWith(Result.failure(IOException("Сервис отдал пустой файл")))
                            else cont.resumeWith(Result.success(data))
                        } catch (e: IOException) {
                            cont.resumeWith(Result.failure(IOException("Загрузка видео прервалась: ${e.message ?: ""}")))
                        }
                    }
                }
            })
        }
    }
}
