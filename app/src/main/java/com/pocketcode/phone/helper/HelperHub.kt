// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone.helper

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.pocketcode.phone.AdbFailure
import com.pocketcode.phone.AdbResult
import com.pocketcode.phone.HelperManager
import com.pocketcode.phone.HelperMode
import com.pocketcode.phone.HelperState
import com.pocketcode.phone.PhoneInput
import com.pocketcode.phone.StartOutcome
import com.pocketcode.phone.WirelessAdb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Приёмник помощника в процессе приложения. Помощник передаёт Binder через [HelperHandoffProvider]; здесь его проверяют
 * и принимают. Один экземпляр на процесс: [get]. Помощник запускается двумя способами: через su (root) и через
 * беспроводную отладку ([WirelessAdb]); проверки при передаче в обоих случаях одинаковые.
 */
class HelperHub private constructor(context: Context) : HelperManager {
    private val app = context.applicationContext

    private val _shell = MutableStateFlow<IPocketShell?>(null)
    /** Интерфейс помощника, который сейчас подключён; когда процесс помощника умирает — снова null. */
    val shell: StateFlow<IPocketShell?> = _shell.asStateFlow()

    private val _state = MutableStateFlow(HelperState(false, HelperMode.NONE, "Помощник не запущен"))
    override val state: StateFlow<HelperState> = _state.asStateFlow()

    private val handoffDiagnostics = HandoffDiagnostics()
    /** Причина отказа при последней попытке передачи (без пароля, Binder, путей и конкретных uid). */
    val lastHandoffRejection: String? get() = handoffDiagnostics.lastRejection

    @Volatile private var mode = HelperMode.NONE
    @Volatile private var adb: WirelessAdb? = null
    private val launchMutex = Mutex()

    override val input: PhoneInput by lazy { HelperInput(app) { _shell.value } }

    /** Клиент беспроводной отладки (его создаёт и передаёт слой настроек). */
    fun attachAdb(adb: WirelessAdb) {
        this.adb = adb
    }

    /** Метка нашего процесса для помощника: умер процесс приложения — помощник узнаёт об этом и убирает за собой. */
    private val token = Binder()
    private val nonces: HandoffNonces by lazy { HandoffNonces(PrefsNonceStore(app), SystemClock::elapsedRealtime) }

    private fun publish() {
        val connected = _shell.value != null
        _state.value = when {
            connected -> HelperState(true, mode, if (mode == HelperMode.ROOT) "Помощник подключён (root)" else "Помощник подключён (отладка по Wi-Fi)")
            else -> HelperState(false, HelperMode.NONE, "Помощник не запущен")
        }
    }

    /**
     * Помощник пришёл передавать Binder. Принимаем только shell (uid 2000), если названные uid/pid совпали с вызывающим,
     * помощник запущен из текущего APK и пароль верен; затем вызываем Binder, чтобы убедиться, что он в том же процессе,
     * и только потом тратим пароль и выдаём следующий.
     */
    @Synchronized
    fun offer(extras: Bundle?, callingUid: Int, callingPid: Int): Bundle {
        val binder = extras?.getBinder(HelperHandoff.KEY_BINDER)
        HelperHandoff.check(
            callingUid, callingPid,
            reportedUid = extras?.getInt(HelperHandoff.KEY_UID, -1) ?: -1,
            reportedPid = extras?.getInt(HelperHandoff.KEY_PID, -1) ?: -1,
            helperApk = extras?.getString(HelperHandoff.KEY_APK),
            appApk = app.applicationInfo.sourceDir,
            hasBinder = binder != null,
        )?.let { return reject(callingUid, it) }
        val nonce = extras?.getString(HelperHandoff.KEY_NONCE)
        // Неверный пароль — Binder от этого помощника не трогаем
        if (!nonces.matches(nonce)) return reject(callingUid, HelperHandoff.Reject.BadNonce)
        val shell = IPocketShell.Stub.asInterface(binder!!)
        val ids = runCatching { shell.identity() }.getOrNull()
        if (ids == null || ids.size < 2 || ids[0] != callingUid || ids[1] != callingPid) {
            return reject(callingUid, HelperHandoff.Reject.BinderElsewhere)
        }
        val next = nonces.accept(nonce) ?: return reject(callingUid, HelperHandoff.Reject.BadNonce)
        try {
            binder.linkToDeath({ if (_shell.compareAndSet(shell, null)) publish() }, 0)
        } catch (e: Exception) {
            return reject(callingUid, HelperHandoff.Reject.NoBinder)
        }
        handoffDiagnostics.clear()
        _shell.value = shell
        publish()
        Log.i(TAG, "helper pid $callingPid handed off")
        return Bundle().apply {
            putBoolean(HelperHandoff.KEY_ACCEPTED, true)
            putString(HelperHandoff.KEY_NEXT, next)
            putBinder(HelperHandoff.KEY_TOKEN, token)
        }
    }

    private fun reject(callingUid: Int, why: HelperHandoff.Reject): Bundle {
        handoffDiagnostics.reject(why, callingUid)
        Log.w(TAG, "helper handoff rejected: ${handoffDiagnostics.lastRejection}")
        return Bundle().apply {
            putBoolean(HelperHandoff.KEY_ACCEPTED, false)
            putString(HelperHandoff.KEY_REASON, why.name)
        }
    }

    // ---------- Запуск ----------

    /** Отпечаток нашей подписи: помощник перед каждой передачей сверяет, что приложение подписано тем же ключом. */
    private fun ownCertPin(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return runCatching {
            val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            HelperHandoff.certPin(info.signingInfo?.apkContentsSigners?.map { it.toByteArray() })
        }.getOrNull()
    }

    private suspend fun awaitNewShell(before: IPocketShell?): Boolean =
        withTimeoutOrNull(HANDOFF_WAIT_MS) { _shell.first { s -> s != null && s !== before } } != null

    /**
     * Запуск через `su 2000 -c '<id -u; команда запуска>'`. Пароль пишем в stdin только после того, как su вернул uid 2000
     * (согласился и переключился на shell). Дальше проверки передачи те же, что у отладки по Wi-Fi.
     */
    override suspend fun startViaRoot(): StartOutcome = launchMutex.withLock {
        if (_shell.value != null && mode == HelperMode.ROOT) return@withLock StartOutcome.Started(HelperMode.ROOT)
        val pin = ownCertPin() ?: return@withLock StartOutcome.Failed("Не удалось прочитать подпись приложения (нужен Android 9 или новее).")
        withContext(Dispatchers.IO) {
            val su = RootShell.su() ?: return@withContext StartOutcome.RootDenied
            val command = HelperHandoff.rootCommand(app.applicationInfo.sourceDir, app.packageName, android.os.Process.myUid(), pin)
            val p = try {
                RootShell.start(SuProbe.shellArgs(su, command))
            } catch (t: Throwable) {
                return@withContext StartOutcome.RootDenied
            }
            val before = _shell.value
            try {
                val uid = try {
                    runInterruptible { RootShell.readUid(p, ROOT_ASK_MS) }
                } catch (e: TimeoutException) {
                    return@withContext StartOutcome.Failed("su не ответил: возможно, окно запроса root осталось без нажатия.")
                } catch (e: ExecutionException) {
                    return@withContext StartOutcome.RootDenied
                }
                when (uid) {
                    null -> return@withContext StartOutcome.RootDenied
                    HelperHandoff.SHELL_UID -> Unit
                    else -> return@withContext StartOutcome.Failed("Этот su не умеет переключаться на пользователя shell.")
                }
                handoffDiagnostics.launchStarted()
                mode = HelperMode.ROOT
                p.outputStream.apply {
                    write(HelperHandoff.stdinLine(nonces.issueLaunch()))
                    flush()
                }
                if (awaitNewShell(before)) StartOutcome.Started(HelperMode.ROOT)
                else StartOutcome.Failed(handoffFailure())
            } finally {
                // Закрываем stdin: sh дочитает и выйдет; помощник работает в своей сессии (setsid) и не страдает
                runCatching { p.outputStream.close() }
                if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroy()
            }
        }
    }

    /** Запуск через беспроводную отладку: подключаемся к adbd этого же телефона и запускаем ту же команду. */
    override suspend fun startViaWirelessDebugging(): StartOutcome = launchMutex.withLock {
        if (_shell.value != null && mode == HelperMode.WIRELESS_DEBUGGING) return@withLock StartOutcome.Started(HelperMode.WIRELESS_DEBUGGING)
        val adb = adb ?: return@withLock StartOutcome.Failed("Отладка по Wi-Fi не подготовлена.")
        val pin = ownCertPin() ?: return@withLock StartOutcome.Failed("Не удалось прочитать подпись приложения (нужен Android 9 или новее).")
        val command = HelperHandoff.command(app.applicationInfo.sourceDir, app.packageName, android.os.Process.myUid(), pin)
        val before = _shell.value
        handoffDiagnostics.launchStarted()
        mode = HelperMode.WIRELESS_DEBUGGING
        val result = adb.startHelper(command, HelperHandoff.stdinLine(nonces.issueLaunch())) { awaitNewShell(before) }
        when (result) {
            is AdbResult.Ok ->
                if (_shell.value != null && _shell.value !== before) StartOutcome.Started(HelperMode.WIRELESS_DEBUGGING)
                else StartOutcome.Failed(handoffFailure())
            is AdbResult.Failed -> when (result.kind) {
                AdbFailure.WIRELESS_OFF, AdbFailure.PORT_NOT_FOUND -> StartOutcome.WirelessDebuggingOff
                AdbFailure.NOT_PAIRED -> StartOutcome.NeedsPairing
                else -> StartOutcome.Failed(result.reason)
            }
        }
    }

    private fun handoffFailure(): String {
        val why = lastHandoffRejection
        return if (why == null) "Помощник запустился, но не передал управление за ${HANDOFF_WAIT_MS / 1000} с."
        else "Помощник отклонён при передаче: $why."
    }

    override suspend fun stop() {
        val s = _shell.value ?: return
        withContext(Dispatchers.IO) {
            // destroy завершает процесс помощника сразу, поэтому вызов обычно заканчивается DeadObjectException
            runCatching { s.destroy() }
        }
        if (_shell.compareAndSet(s, null)) publish()
    }

    /** Сброс: все пароли недействительны (ещё живой помощник при следующей передаче будет отклонён и выйдет). */
    fun forget() {
        nonces.clear()
        handoffDiagnostics.clear()
        _shell.value = null
        publish()
    }

    private class PrefsNonceStore(context: Context) : NonceStore {
        private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        override fun load() = NonceSlots(prefs.getString("resume", null), prefs.getString("launch", null), prefs.getLong("launchAt", 0))
        // Запись синхронная: после выдачи пароля приложение могут сразу убить, и новый процесс должен его узнать
        override fun save(slots: NonceSlots) {
            prefs.edit().putString("resume", slots.resume).putString("launch", slots.launch).putLong("launchAt", slots.launchAt).commit()
        }
    }

    companion object {
        private const val TAG = "PocketCodeHelperHub"
        private const val PREFS = "pocketcode_helper"
        /** Сколько ждём, пока помощник передаст Binder после запуска. */
        const val HANDOFF_WAIT_MS = 15_000L
        /** Сколько ждём ответа su: при первом запуске есть окно запроса, пользователю нужно время. */
        const val ROOT_ASK_MS = 30_000L

        @Volatile private var instance: HelperHub? = null

        fun get(context: Context): HelperHub =
            instance ?: synchronized(this) { instance ?: HelperHub(context).also { instance = it } }
    }
}

/**
 * Вход для передачи Binder от помощника. Открыт для shell, поэтому exported; в манифесте дополнительно требуется
 * INTERACT_ACROSS_USERS_FULL (есть у shell, нет у обычных приложений). Настоящая защита — в [HelperHub.offer].
 */
class HelperHandoffProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != HelperHandoff.METHOD_ATTACH) return null
        val ctx = context ?: return null
        return HelperHub.get(ctx).offer(extras, Binder.getCallingUid(), Binder.getCallingPid())
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
