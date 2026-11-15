package com.pocketcode.assist

import com.pocketcode.Profile
import com.pocketcode.Provider
import com.pocketcode.Settings
import com.pocketcode.SettingsStore
import com.pocketcode.llm.AnthropicClient
import com.pocketcode.llm.LlmClient
import com.pocketcode.llm.OpenAiClient

/** Собирает клиент модели для ассистента из тех же профилей и ключей, что и чат. */
object AssistBackend {
    class Ready(
        val profile: Profile,
        val llm: LlmClient,
        val fallback: LlmClient?,
        val contextLimit: Int,
        /** Меняется, когда сменили профиль, модель или адрес: тогда разговор начинается заново. */
        val key: String,
    )

    /** Готовая к работе модель или null, если ключ API не указан. [profileId] пусто — активный профиль чата. */
    fun prepare(store: SettingsStore, profileId: String): Ready? {
        val profiles = store.loadProfiles()
        val active = profiles.firstOrNull { it.id == store.loadActiveId() } ?: profiles.first()
        val p = profiles.firstOrNull { it.id == profileId && profileId.isNotEmpty() } ?: active
        if (p.apiKey.isBlank()) return null
        val s = store.load()
        val fallbackProfile = profiles.firstOrNull { it.id == s.fallbackProfileId && it.id != p.id && it.apiKey.isNotBlank() }
        return Ready(
            profile = p,
            llm = makeLlm(s, p),
            fallback = fallbackProfile?.let { makeLlm(s, it) },
            contextLimit = if (p.contextTokens > 0) p.contextTokens else s.contextTokens,
            key = p.id + "|" + p.provider.name + "|" + p.baseUrl + "|" + p.model,
        )
    }

    private fun makeLlm(s: Settings, p: Profile): LlmClient = when (p.provider) {
        Provider.ANTHROPIC -> AnthropicClient(p.baseUrl, p.apiKey, p.model, s.promptCaching, p.maxOutputTokens)
        Provider.OPENAI -> OpenAiClient(p.baseUrl, p.apiKey, p.model, s.promptCaching, p.maxOutputTokens)
    }
}
