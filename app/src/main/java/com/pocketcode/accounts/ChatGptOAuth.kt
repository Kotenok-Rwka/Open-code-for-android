package com.pocketcode.accounts

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Работа с токеном ChatGPT/OpenAI. Вход через браузер у OpenAI не выдаёт доступ к API для сторонних приложений,
 * поэтому [signIn] честно сообщает об этом и предлагает вставить ключ API.
 */
object ChatGptOAuth {
    suspend fun signIn(ctx: Context): String {
        AccountStore.token(ctx, AccountKind.CHATGPT)?.let { return it }
        throw IllegalStateException("вход через браузер не даёт доступа к API. Нажми «Открыть сайт», создай ключ API и вставь его в поле выше")
    }

    /** Сохранённый токен (отдельного refresh-токена без браузерного входа нет). */
    suspend fun refreshIfNeeded(ctx: Context): String? = AccountStore.token(ctx, AccountKind.CHATGPT)

    suspend fun models(ctx: Context): List<String> = withContext(Dispatchers.IO) {
        val token = AccountStore.token(ctx, AccountKind.CHATGPT) ?: return@withContext emptyList()
        val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
        val req = Request.Builder().url("https://api.openai.com/v1/models").header("Authorization", "Bearer $token").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use emptyList<String>()
            val text = resp.body?.string().orEmpty()
            Json.parseToJsonElement(text).jsonObject["data"]?.jsonArray
                ?.mapNotNull { runCatching { it.jsonObject["id"]?.jsonPrimitive?.content }.getOrNull() }
                ?.sorted().orEmpty()
        }
    }
}
