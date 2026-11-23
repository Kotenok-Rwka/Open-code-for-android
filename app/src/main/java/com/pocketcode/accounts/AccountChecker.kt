package com.pocketcode.accounts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Результат проверки ключа: [ok] — запрос списка моделей прошёл. */
data class AccountCheckResult(val ok: Boolean, val message: String)

object AccountChecker {
    /** Запрашивает список моделей: так видно, примет ли сервис токен для агента. */
    suspend fun check(baseUrl: String, token: String, model: String): AccountCheckResult = withContext(Dispatchers.IO) {
        runCatching {
            val base = baseUrl.trim().trimEnd('/')
            val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
            val req = Request.Builder().url("$base/models").header("Authorization", "Bearer $token").build()
            client.newCall(req).execute().use { resp ->
                when {
                    resp.isSuccessful -> AccountCheckResult(true, "Ключ принят, API доступен (модель для проверки: $model).")
                    resp.code == 401 || resp.code == 403 ->
                        AccountCheckResult(false, "HTTP ${resp.code}: токен не подходит для API. Бесплатные тарифы часто не дают API-доступа — нужен ключ API.")
                    resp.code == 429 -> AccountCheckResult(false, "HTTP 429: лимит запросов исчерпан, попробуй позже.")
                    else -> AccountCheckResult(false, "HTTP ${resp.code}")
                }
            }
        }.getOrElse { AccountCheckResult(false, "Нет связи: ${it.message}") }
    }
}
