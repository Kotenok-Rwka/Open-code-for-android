package com.pocketcode.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Виджет, который открывается в WebView и получает данные с localhost/локального сервера. */
@Serializable
data class LocalhostWidget(
    val id: String,
    val name: String,
    val url: String,
    val note: String = "",
    val enabled: Boolean = true,
)

object LocalhostWidgets {
    const val STORE_KEY = "localhostWidgets"

    fun defaults(): List<LocalhostWidget> = listOf(
        LocalhostWidget("desktop", "PocketCode Desktop", "http://127.0.0.1:8765/", "Панель ПК, если приложение запущено на этом же устройстве (Termux/proot).", false),
        LocalhostWidget("lmstudio", "LM Studio", "http://127.0.0.1:1234/", "Локальный сервер моделей LM Studio.", false),
        LocalhostWidget("ollama", "Ollama", "http://127.0.0.1:11434/", "Локальный сервер Ollama.", false),
    )

    fun decode(raw: String?): List<LocalhostWidget> = runCatching {
        Json.decodeFromString<List<LocalhostWidget>>(raw ?: "[]")
    }.getOrDefault(defaults())

    fun encode(list: List<LocalhostWidget>): String = Json.encodeToString(list)

    /** Только localhost и private IP: не даём виджету открывать произвольный сайт. */
    fun isLocal(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return false
        if (host == "localhost" || host == "127.0.0.1" || host == "::1") return true
        val parts = host.split('.').mapNotNull { it.toIntOrNull() }
        return parts.size == 4 && (parts[0] == 10 || (parts[0] == 192 && parts[1] == 168) || (parts[0] == 172 && parts[1] in 16..31))
    }
}
