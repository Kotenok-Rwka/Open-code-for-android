package com.pocketcode.ui

import android.content.Context
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import org.json.JSONObject
import java.util.Locale

/** Язык интерфейса. Тексты в коде написаны по-русски; переводы лежат в assets/i18n/<код>.json (русский текст → перевод). */
object I18n {
    class Lang(val code: String, val native: String, val rtl: Boolean = false)

    /** Все языки, для которых есть файл перевода (русский встроен). */
    val all = listOf(
        Lang("ru", "Русский"), Lang("en", "English"), Lang("ar", "العربية", rtl = true),
        Lang("es", "Español"), Lang("de", "Deutsch"), Lang("zh", "中文"), Lang("tr", "Türkçe"),
        Lang("fr", "Français"), Lang("it", "Italiano"), Lang("pt", "Português"),
        Lang("hi", "हिन्दी"), Lang("id", "Bahasa Indonesia"), Lang("bn", "বাংলা"),
    )

    var code by mutableStateOf("ru")
        private set

    /** Язык уже выбран пользователем (иначе при первом входе показываем выбор). */
    var chosen by mutableStateOf(true)
        private set

    private var prefs: android.content.SharedPreferences? = null
    private var app: Context? = null
    private var exact: Map<String, String> = emptyMap()
    private var templates: List<Template> = emptyList()
    private var byPrefix: Map<String, List<Template>> = emptyMap()
    private var generic: List<Template> = emptyList()
    private val cache = HashMap<String, String>()

    /** Онлайн-перевод для языков без встроенного файла: включается пользователем, кэш хранится на устройстве. */
    var online by mutableStateOf(false)
        private set
    private var onlineCache: HashMap<String, String> = HashMap()
    private var onlineCacheFile: java.io.File? = null
    private val onlinePending = HashSet<String>()
    private val onlineExec = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun onlineFile(ctx: Context): java.io.File =
        java.io.File(ctx.filesDir, "i18n_online_$code.json")

    private fun loadOnlineCache() {
        val ctx = app ?: return
        val f = onlineFile(ctx)
        onlineCacheFile = f
        onlineCache = HashMap()
        runCatching {
            if (f.exists()) {
                val o = JSONObject(f.readText())
                for (k in o.keys()) onlineCache[k] = o.getString(k)
            }
        }
    }

    private fun saveOnlineCache() {
        val f = onlineCacheFile ?: return
        runCatching {
            f.writeText(JSONObject(onlineCache as Map<*, *>).toString())
        }
    }

    fun setOnlineEnabled(v: Boolean) {
        online = v
        prefs?.edit()?.putBoolean("online_$code", v)?.apply()
        if (v) loadOnlineCache()
    }

    /** Очередь онлайн-перевода: строка переводится фоном и запоминается в кэш на устройстве. */
    private fun requestOnline(s: String) {
        if (!online || s in onlinePending || s in onlineCache) return
        if (s.length > 500) return
        val target = all.firstOrNull { it.code == code }?.code ?: return
        onlinePending += s
        onlineExec.execute {
            val tr = translateOnce(s, target)
            synchronized(onlineCache) {
                onlinePending -= s
                if (tr != null && tr.isNotBlank()) {
                    onlineCache[s] = tr
                    if (onlineCache.size > 8000) {
                        val keys = onlineCache.keys.take(2000)
                        for (k in keys) onlineCache.remove(k)
                    }
                    saveOnlineCache()
                    android.os.Handler(android.os.Looper.getMainLooper()).post { cache.clear() }
                }
            }
        }
    }

    fun onlineStatus(): Boolean = online

    /** Движки онлайн-перевода. */
    val engines = listOf("google", "deepl", "libre")

    fun engine(): String = prefs?.getString("engine_$code", "google") ?: "google"

    fun setEngine(e: String) {
        prefs?.edit()?.putString("engine_$code", e)?.apply()
    }

    fun apiKey(): String = prefs?.getString("apikey_$code", "") ?: ""

    fun setApiKey(k: String) {
        prefs?.edit()?.putString("apikey_$code", k)?.apply()
    }

    fun serverUrl(): String = prefs?.getString("server_$code", "https://translate.disroot.org") ?: "https://translate.disroot.org"

    fun setServerUrl(u: String) {
        prefs?.edit()?.putString("server_$code", u.trim().removeSuffix("/"))?.apply()
    }

    private fun deeplTarget(lang: String): String = when (lang) {
        "en" -> "EN-US"; "pt" -> "PT-BR"
        else -> lang.uppercase()
    }

    /** Ручное исправление перевода: сохраняется в кэш онлайн-перевода и имеет приоритет над файлом. */
    fun overrideTranslation(ru: String, translated: String) {
        if (translated.isBlank()) return
        if (onlineCacheFile == null) loadOnlineCache()
        onlineCache[ru] = translated
        saveOnlineCache()
        cache.clear()
    }

    /** Экспорт кэша онлайн-перевода текущего языка в JSON-строку. */
    fun exportOnlineCache(): String {
        if (onlineCacheFile == null) loadOnlineCache()
        return JSONObject(onlineCache as Map<*, *>).toString(1)
    }

    /** Импорт кэша онлайн-перевода из JSON-строки (добавляется к существующему). Возвращает число строк. */
    fun importOnlineCache(json: String): Int {
        if (onlineCacheFile == null) loadOnlineCache()
        var n = 0
        runCatching {
            val o = JSONObject(json)
            for (k in o.keys()) { onlineCache[k] = o.getString(k); n++ }
        }
        if (n > 0) { saveOnlineCache(); cache.clear() }
        return n
    }

    private fun translateOnce(s: String, target: String): String? {
        val key = apiKey()
        return when (engine()) {
            "deepl" -> runCatching {
                val host = if (key.endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com"
                val body = "auth_key=" + java.net.URLEncoder.encode(key, "UTF-8") +
                    "&text=" + java.net.URLEncoder.encode(s, "UTF-8") +
                    "&target_lang=" + deeplTarget(target)
                val req = java.net.URL("https://$host/v2/translate").openConnection() as java.net.HttpURLConnection
                req.requestMethod = "POST"
                req.doOutput = true
                req.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                req.connectTimeout = 8000
                req.readTimeout = 8000
                req.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val resp = JSONObject(req.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                resp.getJSONArray("translations").getJSONObject(0).getString("text")
            }.getOrNull()
            "libre" -> runCatching {
                val body = JSONObject(mapOf("q" to s, "source" to "ru", "target" to target, "format" to "text"))
                if (key.isNotBlank()) body.put("api_key", key)
                val req = java.net.URL(serverUrl() + "/translate").openConnection() as java.net.HttpURLConnection
                req.requestMethod = "POST"
                req.doOutput = true
                req.setRequestProperty("Content-Type", "application/json")
                req.connectTimeout = 8000
                req.readTimeout = 8000
                req.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val resp = JSONObject(req.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                resp.getString("translatedText")
            }.getOrNull()
            else -> runCatching {
                val q = java.net.URLEncoder.encode(s, "UTF-8")
                val req = java.net.URL(
                    "https://translate.googleapis.com/translate_a/single?client=gtx&sl=ru&tl=$target&dt=t&q=$q"
                ).openConnection() as java.net.HttpURLConnection
                req.setRequestProperty("User-Agent", "Mozilla/5.0")
                req.connectTimeout = 8000
                req.readTimeout = 8000
                val text = req.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val arr = JSONArray(text)
                val sb = StringBuilder()
                for (i in 0 until arr.getJSONArray(0).length()) sb.append(arr.getJSONArray(0).getJSONArray(i).getString(0))
                sb.toString()
            }.getOrNull()
        }
    }

    private class Template(val rx: Regex, val out: String, val prefix: String)

    val available: List<Lang> get() {
        val ctx = app ?: return all.filter { it.code == "ru" }
        val files = runCatching { ctx.assets.list("i18n")?.toSet() }.getOrNull() ?: emptySet()
        return all.filter { it.code == "ru" || "${it.code}.json" in files }
    }

    val isRtl: Boolean get() = all.firstOrNull { it.code == code }?.rtl == true

    /** Язык системы, если он поддерживается, иначе английский. */
    fun systemDefault(): String {
        val sys = Locale.getDefault().language.lowercase().let { if (it == "iw") "he" else it }
        return available.firstOrNull { it.code == sys }?.code ?: "en"
    }

    fun init(context: Context) {
        val c = context.applicationContext
        app = c
        val p = c.getSharedPreferences("pocketcode_ui", Context.MODE_PRIVATE)
        prefs = p
        val saved = p.getString("lang", null)
        if (saved == null) {
            chosen = false
            apply(systemDefault())
        } else {
            chosen = true
            apply(saved)
        }
        online = p.getBoolean("online_$code", false)
        if (online) loadOnlineCache()
    }

    fun choose(lang: String) {
        prefs?.edit()?.putString("lang", lang)?.apply()
        chosen = true
        apply(lang)
    }

    private fun apply(lang: String) {
        val ctx = app
        cache.clear()
        exact = emptyMap()
        templates = emptyList()
        byPrefix = emptyMap()
        generic = emptyList()
        if (lang != "ru" && ctx != null) {
            runCatching {
                val text = ctx.assets.open("i18n/$lang.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
                val o = JSONObject(text)
                val ex = HashMap<String, String>()
                val tpl = ArrayList<Template>()
                for (k in o.keys()) {
                    val v = o.getString(k)
                    if (k.contains("{0}")) tpl += buildTemplate(k, v) else ex[k] = v
                }
                exact = ex
                templates = tpl
                val grouped = HashMap<String, MutableList<Template>>()
                val gen = ArrayList<Template>()
                for (t in tpl) {
                    val key = t.prefix.take(5)
                    if (key.length < 3) gen += t else grouped.getOrPut(key) { ArrayList() } += t
                }
                byPrefix = grouped
                generic = gen
            }
        }
        code = lang
    }

    private fun buildTemplate(key: String, out: String): Template {
        val parts = Regex("\\{\\d+\\}").split(key)
        val rx = Regex("^" + parts.joinToString("(.+?)") { Regex.escape(it) } + "$", RegexOption.DOT_MATCHES_ALL)
        return Template(rx, out, parts.first())
    }

    /** Перевод русской строки; если перевода нет, возвращается она же. */
    fun t(s: String): String {
        if (code == "ru" || s.isEmpty()) return s
        exact[s]?.let { return it }
        cache[s]?.let { return it }
        if (online) {
            synchronized(onlineCache) { onlineCache[s] }?.let { return it }
            if (!s.contains("${'$'}{")) requestOnline(s)
            return s
        }
        var res = s
        val cands = (byPrefix[s.take(5)] ?: emptyList()) + generic
        for (t in cands) {
            val m = t.rx.matchEntire(s) ?: continue
            var o = t.out
            for (i in 1 until m.groupValues.size) o = o.replace("{${i - 1}}", t(m.groupValues[i]))
            res = o
            break
        }
        if (cache.size > 4000) cache.clear()
        cache[s] = res
        return res
    }
}

/** Заменяет material3.Text: текст сам переводится на выбранный язык. Подключается импортом com.pocketcode.ui.Text. */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
) {
    @Suppress("UNUSED_VARIABLE") val lang = I18n.code // подписка: при смене языка текст перерисуется
    androidx.compose.material3.Text(
        I18n.t(text), modifier, color, fontSize, fontStyle, fontWeight, fontFamily, letterSpacing, textDecoration,
        textAlign, lineHeight, overflow, softWrap, maxLines, minLines, onTextLayout, style,
    )
}

@Composable
fun Text(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current,
) {
    androidx.compose.material3.Text(
        text, modifier, color, fontSize, fontStyle, fontWeight, fontFamily, letterSpacing, textDecoration,
        textAlign, lineHeight, overflow, softWrap, maxLines, minLines, emptyMap(), onTextLayout, style,
    )
}
