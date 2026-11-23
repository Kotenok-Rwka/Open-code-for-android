package com.pocketcode.accounts

/** Сервисы, к которым можно подключить аккаунт или ключ. */
enum class AccountKind(val title: String, val site: String) {
    CHATGPT("ChatGPT / OpenAI", "https://platform.openai.com/api-keys"),
    CLAUDE("Claude / Anthropic", "https://console.anthropic.com/settings/keys"),
    GEMINI("Gemini / Google AI Studio", "https://aistudio.google.com/apikey"),
}
