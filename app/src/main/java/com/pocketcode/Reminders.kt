package com.pocketcode

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pocketcode.service.Notifier
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/** Напоминание/таймер: одноразовое уведомление в нужное время (идея AndroidHarness, код свой). */
@Serializable
data class Reminder(
    val id: String,
    val text: String,
    val at: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val done: Boolean = false,
)

object Reminders {
    private const val PREFS = "reminders"
    private const val KEY = "list"
    private val json = Json { ignoreUnknownKeys = true }

    fun load(ctx: Context): List<Reminder> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<Reminder>>(raw) }.getOrDefault(emptyList())
    }

    fun save(ctx: Context, list: List<Reminder>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json.encodeToString(list)).apply()
    }

    /** Планирует через WorkManager: сохраняется после перезапуска телефона. */
    fun schedule(ctx: Context, r: Reminder) {
        val delay = (r.at - System.currentTimeMillis()).coerceAtLeast(0)
        val req = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("id" to r.id))
            .addTag("reminder")
            .addTag("reminder-${r.id}")
            .build()
        WorkManager.getInstance(ctx).enqueue(req)
    }

    fun cancel(ctx: Context, id: String) {
        WorkManager.getInstance(ctx).cancelAllWorkByTag("reminder-$id")
    }

    /** Разбор фразы «напомни через 2 часа проверить сборку». */
    fun parseNatural(input: String): Pair<String, Long>? {
        val s = input.trim()
        val rx = Regex("(?i)(?:напомни\\s+)?через\\s+(\\d+)\\s*(минут|мин|час|часа|часов|день|дня|дней)\\s+(.+)")
        val m = rx.find(s) ?: return null
        val n = m.groupValues[1].toLongOrNull() ?: return null
        val unit = m.groupValues[2].lowercase()
        val mult = when {
            unit.startsWith("мин") -> 60_000L
            unit.startsWith("час") -> 3_600_000L
            else -> 86_400_000L
        }
        return m.groupValues[3].trim() to System.currentTimeMillis() + n * mult
    }

    fun add(ctx: Context, text: String, at: Long): Reminder {
        val r = Reminder(java.util.UUID.randomUUID().toString(), text.trim(), at)
        save(ctx, load(ctx) + r)
        schedule(ctx, r)
        return r
    }

    fun remove(ctx: Context, id: String) {
        save(ctx, load(ctx).filterNot { it.id == id })
        cancel(ctx, id)
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.success()
        val r = Reminders.load(applicationContext).firstOrNull { it.id == id } ?: return Result.success()
        if (!r.done) Notifier.alert(applicationContext, "Напоминание PocketCode", r.text)
        Reminders.save(applicationContext, Reminders.load(applicationContext).map { if (it.id == id) it.copy(done = true) else it })
        return Result.success()
    }
}
