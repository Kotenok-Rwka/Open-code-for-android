package com.pocketcode

/** Каталог рекомендаций по локальным моделям: оценка по возможностям и размеру. */
enum class ModelCapability(val label: String) {
    CODE("Код"),
    VISION("Видит изображения"),
    CHAT("Диалог"),
    MULTIMODAL("Мультимодальная"),
    FAST("Быстрая"),
    DEEP("Глубокое мышление"),
}

data class LocalModelAdvice(
    val id: String,
    val title: String,
    val family: String,
    val paramsB: Float,
    val minRamGb: Int,
    val contextK: Int,
    val capabilities: Set<ModelCapability>,
    val note: String,
    val quant: String = "Q4_K_M",
    /** Оценка качества 1–10 (ориентир для рейтинга). */
    val quality: Int = 5,
)

object LocalModelCatalog {
    private val QUALITY = mapOf(
        "qwen2.5-coder:3b" to 6, "qwen2.5-coder:7b" to 8, "qwen2.5-coder:14b" to 9, "deepseek-coder:6.7b" to 6,
        "codellama:7b" to 4, "llama3.2:3b" to 5, "llama3.1:8b" to 7, "mistral:7b" to 6, "gemma3:4b" to 6,
        "llava:7b" to 5, "qwen2.5-vl:7b" to 8, "phi3.5:3.8b" to 5, "smollm2:1.7b" to 3, "qwq:32b" to 9,
    )

    val all: List<LocalModelAdvice> = listOf(
        LocalModelAdvice("qwen2.5-coder:3b", "Qwen2.5 Coder 3B", "Qwen", 3f, 4, 32, setOf(ModelCapability.CODE, ModelCapability.FAST), "Лучший баланс для кода на телефоне: быстрая, адекватная, помещается в 4 ГБ."),
        LocalModelAdvice("qwen2.5-coder:7b", "Qwen2.5 Coder 7B", "Qwen", 7f, 6, 32, setOf(ModelCapability.CODE), "Хорошая модель для кода; нужны память и поддержка GPU/открытого рантайма."),
        LocalModelAdvice("qwen2.5-coder:14b", "Qwen2.5 Coder 14B", "Qwen", 14f, 12, 64, setOf(ModelCapability.CODE, ModelCapability.DEEP), "Сильный агент по коду, но нужен мощный телефон или ПК."),
        LocalModelAdvice("deepseek-coder:6.7b", "DeepSeek Coder 6.7B", "DeepSeek", 6.7f, 6, 16, setOf(ModelCapability.CODE), "Проверенный код, неплохой Fill-in-the-middle."),
        LocalModelAdvice("codellama:7b", "Code Llama 7B", "Meta", 7f, 6, 16, setOf(ModelCapability.CODE), "Классика кода, уступает Qwen2.5 Coder."),
        LocalModelAdvice("llama3.2:3b", "Llama 3.2 3B", "Meta", 3f, 3, 128, setOf(ModelCapability.CHAT, ModelCapability.FAST), "Лёгкий диалог и короткие задачи."),
        LocalModelAdvice("llama3.1:8b", "Llama 3.1 8B", "Meta", 8f, 8, 128, setOf(ModelCapability.CHAT), "Универсальная модель на каждый день."),
        LocalModelAdvice("mistral:7b", "Mistral 7B", "Mistral", 7f, 6, 32, setOf(ModelCapability.CHAT, ModelCapability.FAST), "Быстрая и légère для диалогов."),
        LocalModelAdvice("gemma3:4b", "Gemma 3 4B", "Google", 4f, 4, 128, setOf(ModelCapability.VISION, ModelCapability.CHAT, ModelCapability.MULTIMODAL), "Маленькая мультимодальная: видит изображения, работает на телефоне."),
        LocalModelAdvice("llava:7b", "LLaVA 7B", "LLaVA", 7f, 8, 8, setOf(ModelCapability.VISION, ModelCapability.MULTIMODAL), "Видит картинки, хорошо для описания скриншотов."),
        LocalModelAdvice("qwen2.5-vl:7b", "Qwen2.5 VL 7B", "Qwen", 7f, 8, 32, setOf(ModelCapability.VISION, ModelCapability.MULTIMODAL, ModelCapability.CODE), "Сильное зрение + понимание кода на скриншотах."),
        LocalModelAdvice("phi3.5:3.8b", "Phi-3.5 mini", "Microsoft", 3.8f, 4, 128, setOf(ModelCapability.CHAT, ModelCapability.FAST), "Компактная, неплоха для логики и коротких ответов."),
        LocalModelAdvice("smollm2:1.7b", "SmolLM2 1.7B", "HuggingFace", 1.7f, 2, 8, setOf(ModelCapability.FAST), "Очень маленькая: для быстрых простых задач."),
        LocalModelAdvice("qwq:32b", "QwQ 32B", "Qwen", 32f, 24, 64, setOf(ModelCapability.DEEP, ModelCapability.CODE), "Глубокое мышление; только мощный ПК/сервер."),
    ).map { it.copy(quality = QUALITY[it.id] ?: 5) }

    enum class SortBy { SUITABILITY, SIZE, NAME, CONTEXT, RATING }

    /** Лучшие модели по возможности: сначала качество, при равенстве — поменьше размером. */
    fun topIn(capability: ModelCapability, n: Int = 3): List<LocalModelAdvice> =
        all.filter { capability in it.capabilities }
            .sortedWith(compareByDescending<LocalModelAdvice> { it.quality }.thenBy { it.paramsB })
            .take(n)

    /** Значок «лучшая в своей области» или пустая строка. */
    fun badge(m: LocalModelAdvice): String = when (m.id) {
        topIn(ModelCapability.CODE, 1).firstOrNull()?.id -> "Лучшая для кода"
        topIn(ModelCapability.VISION, 1).firstOrNull()?.id -> "Лучшая для картинок"
        topIn(ModelCapability.FAST, 1).firstOrNull()?.id -> "Лучшая быстрая"
        topIn(ModelCapability.CHAT, 1).firstOrNull()?.id -> "Лучшая для диалога"
        else -> ""
    }

    data class Device(val totalRamGb: Int, val freeStorageGb: Int, val androidVersion: Int)

    /** Рекомендации по приоритетам пользователя и железу. Чем меньше score, тем лучше. */
    fun score(m: LocalModelAdvice, device: Device, preferCode: Boolean, preferVision: Boolean, preferFast: Boolean): Int {
        var s = 0
        if (device.totalRamGb < m.minRamGb) s += 100
        if (device.freeStorageGb < m.paramsB * 2) s += 60
        if (preferCode && m.capabilities.contains(ModelCapability.CODE)) s -= 30
        if (preferVision && m.capabilities.contains(ModelCapability.VISION)) s -= 30
        if (preferFast && m.capabilities.contains(ModelCapability.FAST)) s -= 20
        if (preferCode && !m.capabilities.contains(ModelCapability.CODE)) s += 10
        if (preferVision && !m.capabilities.contains(ModelCapability.VISION)) s += 10
        s += (m.paramsB * 2).toInt()
        return s
    }

    fun recommend(device: Device, code: Boolean, vision: Boolean, fast: Boolean, limit: Int = 5): List<LocalModelAdvice> =
        all.sortedBy { score(it, device, code, vision, fast) }.take(limit)

    fun filter(
        query: String = "",
        capability: ModelCapability? = null,
        maxRamGb: Int? = null,
        sortBy: SortBy = SortBy.SUITABILITY,
        device: Device = Device(6, 32, 13),
        code: Boolean = false,
        vision: Boolean = false,
        fast: Boolean = false,
    ): List<LocalModelAdvice> {
        val q = query.trim().lowercase()
        var list = all.filter { m ->
            (q.isEmpty() || m.id.contains(q) || m.title.lowercase().contains(q) || m.family.lowercase().contains(q)) &&
                (capability == null || m.capabilities.contains(capability)) &&
                (maxRamGb == null || m.minRamGb <= maxRamGb)
        }
        list = when (sortBy) {
            SortBy.SUITABILITY -> list.sortedBy { score(it, device, code, vision, fast) }
            SortBy.SIZE -> list.sortedBy { it.paramsB }
            SortBy.NAME -> list.sortedBy { it.title }
            SortBy.CONTEXT -> list.sortedByDescending { it.contextK }
            SortBy.RATING -> list.sortedByDescending { it.quality }
        }
        return list
    }
}
