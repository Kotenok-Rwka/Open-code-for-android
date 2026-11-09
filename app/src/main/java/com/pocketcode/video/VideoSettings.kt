package com.pocketcode.video

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Все настройки экрана «Видео» одним значением: их можно сохранить как последние, как «проект» под конкретное видео
 * или как свой стиль с названием. Разбор терпимый: незнакомые и испорченные поля заменяются значениями по умолчанию.
 */
data class VideoSettings(
    val doCut: Boolean = true,
    val noiseDb: Int = -35,
    val minSilence: Double = 0.6,
    val doSubs: Boolean = false,
    val subStyle: SubStyle = SubStyle.CLASSIC,
    val subTweak: SubTweak = SubTweak(),
    val lang: String = "",
    val transition: Transition = Transition.ZOOM,
    val quality: VideoQuality = VideoQuality.BALANCED,
    val small: Boolean = false,
    val shorts: Boolean = false,
    val blurBg: Boolean = true,
    val enhance: Boolean = false,
    val finTitle: String = "",
    val finSec: Double = 2.5,
    val finBar: Boolean = false,
    val finSpeed: Double = 1.0,
    val finVol: Double = 0.25,
    val finDuck: Boolean = true,
    val presetId: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("doCut", doCut); put("noiseDb", noiseDb); put("minSilence", minSilence)
        put("doSubs", doSubs); put("subStyle", subStyle.name)
        put("accent", subTweak.accent); put("subScale", subTweak.scale); put("subPos", subTweak.position.name)
        put("lang", lang); put("transition", transition.name); put("quality", quality.name)
        put("small", small); put("shorts", shorts); put("blurBg", blurBg); put("enhance", enhance)
        put("finTitle", finTitle); put("finSec", finSec); put("finBar", finBar); put("finSpeed", finSpeed)
        put("finVol", finVol); put("finDuck", finDuck)
        presetId?.let { put("presetId", it) }
    }

    companion object {
        private inline fun <reified E : Enum<E>> enumOf(name: String?, def: E): E = enumValues<E>().firstOrNull { it.name == name } ?: def

        fun fromJson(o: JsonObject): VideoSettings {
            fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            fun bool(k: String, d: Boolean) = (o[k] as? JsonPrimitive)?.booleanOrNull ?: d
            fun dbl(k: String, d: Double) = (o[k] as? JsonPrimitive)?.doubleOrNull ?: d
            fun int(k: String, d: Int) = (o[k] as? JsonPrimitive)?.intOrNull ?: d
            val d = VideoSettings()
            return VideoSettings(
                doCut = bool("doCut", d.doCut),
                noiseDb = int("noiseDb", d.noiseDb).coerceIn(-50, -25),
                minSilence = dbl("minSilence", d.minSilence).coerceIn(0.2, 10.0),
                doSubs = bool("doSubs", d.doSubs),
                subStyle = enumOf(str("subStyle"), d.subStyle),
                subTweak = SubTweak(
                    accent = str("accent")?.takeIf { Regex("[0-9A-Fa-f]{6}").matches(it) }.orEmpty(),
                    scale = dbl("subScale", 1.0).coerceIn(0.6, 1.6),
                    position = enumOf(str("subPos"), SubPos.DEFAULT),
                ),
                lang = str("lang").orEmpty().take(8),
                transition = enumOf(str("transition"), d.transition),
                quality = enumOf(str("quality"), d.quality),
                small = bool("small", d.small), shorts = bool("shorts", d.shorts),
                blurBg = bool("blurBg", d.blurBg), enhance = bool("enhance", d.enhance),
                finTitle = str("finTitle").orEmpty().take(120),
                finSec = dbl("finSec", d.finSec).coerceIn(0.5, 15.0),
                finBar = bool("finBar", d.finBar),
                finSpeed = Finish.clampSpeed(dbl("finSpeed", d.finSpeed)),
                finVol = dbl("finVol", d.finVol).coerceIn(0.05, 1.0),
                finDuck = bool("finDuck", d.finDuck),
                presetId = str("presetId"),
            )
        }

        fun parse(s: String?): VideoSettings? =
            runCatching { fromJson(Json.parseToJsonElement(s ?: return null).jsonObject) }.getOrNull()
    }
}

/** Что сейчас включено в настройках, короткими метками (названия переводятся отдельно). */
fun VideoSettings.summary(): List<String> = buildList {
    VideoPresets.byId(presetId)?.let { add(it.label) }
    if (doCut) add("Без пауз")
    if (doSubs) add(subStyle.label)
    if (doCut || doSubs) add(transition.label)
    if (shorts) add("Shorts 9:16")
    if (enhance) add("Чистый звук")
    if (finTitle.isNotBlank()) add("Заголовок")
    if (finBar) add("Полоска прогресса")
    if (finSpeed > 1.001) add("Ускорение")
}

/**
 * Очередь пакетной обработки: видео уже скопированы в проект, поэтому после закрытия приложения её можно продолжить.
 * [done] и [failed] — элементы очереди, [outs] — готовые файлы.
 */
data class BatchState(
    val items: List<String>,
    val settings: VideoSettings,
    val music: String = "",
    val done: List<String> = emptyList(),
    val failed: List<String> = emptyList(),
    val outs: List<String> = emptyList(),
) {
    fun remaining(): List<String> = items.filter { it !in done && it !in failed }
    fun withDone(item: String, out: String?): BatchState = copy(done = done + item, outs = if (out != null) outs + out else outs)
    fun withFailed(item: String): BatchState = copy(failed = failed + item)
    val finished: Boolean get() = remaining().isEmpty()

    fun toJson(): JsonObject = buildJsonObject {
        put("items", buildJsonArray { items.forEach { add(JsonPrimitive(it)) } })
        put("settings", settings.toJson())
        put("music", music)
        put("done", buildJsonArray { done.forEach { add(JsonPrimitive(it)) } })
        put("failed", buildJsonArray { failed.forEach { add(JsonPrimitive(it)) } })
        put("outs", buildJsonArray { outs.forEach { add(JsonPrimitive(it)) } })
    }

    companion object {
        fun parse(s: String?): BatchState? = runCatching {
            val o = Json.parseToJsonElement(s ?: return null).jsonObject
            fun list(k: String) = (o[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            val items = list("items")
            if (items.isEmpty()) return null
            BatchState(
                items, VideoSettings.fromJson((o["settings"] as? JsonObject) ?: JsonObject(emptyMap())),
                (o["music"] as? JsonPrimitive)?.contentOrNull.orEmpty(), list("done"), list("failed"), list("outs"),
            )
        }.getOrNull()
    }
}

/** Свой стиль с названием. */
data class UserPreset(val name: String, val settings: VideoSettings)

/** Запомненный запрос на создание видео по API. */
data class GenHistoryItem(val prompt: String, val provider: String, val model: String, val aspect: String, val duration: Int, val resolution: String, val audio: Boolean)

/** Простое хранилище строк (SharedPreferences на телефоне, словарь в тестах). */
interface KeyValue {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

class MapKeyValue : KeyValue {
    val map = HashMap<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
}

class VideoStore(private val kv: KeyValue) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // последние настройки
    fun last(): VideoSettings? = VideoSettings.parse(kv.get("last"))
    fun saveLast(s: VideoSettings) = kv.put("last", s.toJson().toString())

    // проект: настройки под конкретное видео
    private fun projKey(rel: String) = "proj:" + rel
    fun project(rel: String): VideoSettings? = VideoSettings.parse(kv.get(projKey(rel)))
    fun saveProject(rel: String, s: VideoSettings) {
        val old = projects()
        val list = (listOf(rel) + old.filter { it != rel }).take(MAX_PROJECTS)
        // самые старые проекты забываем, чтобы хранилище не росло
        old.filter { it !in list }.forEach { kv.remove(projKey(it)) }
        kv.put(projKey(rel), s.toJson().toString())
        kv.put("projects", buildJsonArray { list.forEach { add(JsonPrimitive(it)) } }.toString())
    }

    fun projects(): List<String> =
        kv.get("projects")?.let { s -> runCatching { (json.parseToJsonElement(s) as JsonArray).mapNotNull { (it as? JsonPrimitive)?.contentOrNull } }.getOrNull() } ?: emptyList()

    fun forgetProject(rel: String) {
        kv.remove(projKey(rel))
        kv.put("projects", buildJsonArray { projects().filter { it != rel }.forEach { add(JsonPrimitive(it)) } }.toString())
    }

    // незавершённая пакетная обработка
    fun batch(): BatchState? = BatchState.parse(kv.get("batch"))
    fun saveBatch(b: BatchState) = kv.put("batch", b.toJson().toString())
    fun clearBatch() = kv.remove("batch")

    // подсказка для первого запуска
    fun hintSeen(): Boolean = kv.get("hint_seen") == "1"
    fun markHintSeen() = kv.put("hint_seen", "1")

    // свои стили
    fun presets(): List<UserPreset> =
        kv.get("presets")?.let { s ->
            runCatching {
                (json.parseToJsonElement(s) as JsonArray).mapNotNull { e ->
                    val o = e as? JsonObject ?: return@mapNotNull null
                    val name = (o["name"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    UserPreset(name, VideoSettings.fromJson((o["s"] as? JsonObject) ?: JsonObject(emptyMap())))
                }
            }.getOrNull()
        } ?: emptyList()

    /** Сохраняет стиль; одинаковое название заменяет старый. Возвращает итоговый список. */
    fun savePreset(name: String, s: VideoSettings): List<UserPreset> {
        val n = name.trim().take(30)
        if (n.isEmpty()) return presets()
        val list = (presets().filter { !it.name.equals(n, ignoreCase = true) } + UserPreset(n, s.copy(presetId = null))).takeLast(MAX_PRESETS)
        writePresets(list)
        return list
    }

    fun deletePreset(name: String): List<UserPreset> {
        val list = presets().filter { !it.name.equals(name.trim(), ignoreCase = true) }
        writePresets(list)
        return list
    }

    private fun writePresets(list: List<UserPreset>) {
        kv.put("presets", buildJsonArray { list.forEach { p -> add(buildJsonObject { put("name", p.name); put("s", p.settings.toJson()) }) } }.toString())
    }

    // история создания видео
    fun history(): List<GenHistoryItem> =
        kv.get("gen_history")?.let { s ->
            runCatching {
                (json.parseToJsonElement(s) as JsonArray).mapNotNull { e ->
                    val o = e as? JsonObject ?: return@mapNotNull null
                    fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
                    val prompt = str("prompt")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    GenHistoryItem(
                        prompt, str("provider").orEmpty(), str("model").orEmpty(), str("aspect") ?: "16:9",
                        (o["duration"] as? JsonPrimitive)?.intOrNull ?: 5, str("resolution").orEmpty(),
                        (o["audio"] as? JsonPrimitive)?.booleanOrNull ?: true,
                    )
                }
            }.getOrNull()
        } ?: emptyList()

    fun addHistory(item: GenHistoryItem): List<GenHistoryItem> {
        val list = (listOf(item) + history().filter { !(it.prompt == item.prompt && it.model == item.model) }).take(MAX_HISTORY)
        kv.put("gen_history", buildJsonArray {
            list.forEach { h ->
                add(buildJsonObject {
                    put("prompt", h.prompt); put("provider", h.provider); put("model", h.model); put("aspect", h.aspect)
                    put("duration", h.duration); put("resolution", h.resolution); put("audio", h.audio)
                })
            }
        }.toString())
        return list
    }

    companion object {
        const val MAX_PROJECTS = 30
        const val MAX_PRESETS = 20
        const val MAX_HISTORY = 12
    }
}
