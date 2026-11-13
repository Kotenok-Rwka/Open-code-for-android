// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone.adb

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.pocketcode.phone.AdbFailure
import com.pocketcode.phone.AdbResult
import com.pocketcode.phone.WirelessAdb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException

/**
 * Встроенный adb-клиент для беспроводной отладки этого же телефона: сопряжение по коду, подключение к adbd (порт ищется сам)
 * и запуск службы `exec:`. Все ошибки превращаются в [AdbResult.Failed], исключения наружу не выходят (кроме [CancellationException]).
 *
 * Поддерживаемые версии Android: беспроводной отладки нет ниже Android 11 (API 30), там возвращается [AdbFailure.UNSUPPORTED_OS].
 * Для подключения к adbd достаточно API 30+. Для сопряжения по коду нужен экспорт ключевого материала TLS: публичный
 * `SSLSockets.exportKeyingMaterial` есть с Android 14 (API 34), на Android 11–13 используется запасной путь через внутренний Conscrypt
 * (см. [AndroidAdb.exportKeyingMaterial]); если он закрыт системой, сопряжение вернёт [AdbFailure.UNSUPPORTED_OS].
 */
class AndroidWirelessAdb(context: Context) : WirelessAdb {
    private val app = context.applicationContext
    private val finder by lazy { NsdAdbServiceFinder(app) }
    private val prefs by lazy { app.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val portCache = object : PortCache {
        override fun load() = runCatching { prefs.getInt(KEY_PORT, 0) }.getOrDefault(0).takeIf { it > 0 }
        override fun save(port: Int) { runCatching { prefs.edit().putInt(KEY_PORT, port).apply() } }
    }
    private val scan by lazy { LocalPortScan(NioPortScanner(), ::localHosts, { LocalPortScan.parseRange(portRangeText()) }) }

    override suspend fun pair(code: String, port: Int?): AdbResult = guarded {
        unsupportedOs()?.let { return@guarded it }
        val normalized = PairingCode.normalize(code)
            ?: return@guarded AdbResult.Failed(AdbFailure.OTHER, "Код сопряжения состоит из 6 цифр.")
        if (port != null && port !in 1..65535) {
            return@guarded AdbResult.Failed(AdbFailure.OTHER, "Некорректный порт сопряжения: $port.")
        }
        val target = port ?: findPairingPort()
            ?: return@guarded AdbResult.Failed(
                AdbFailure.PORT_NOT_FOUND,
                "Окно «Сопряжение по коду» не найдено. Откройте его в настройках: Для разработчиков, Беспроводная отладка, Сопряжение по коду.",
            )
        withContext(Dispatchers.IO) {
            AdbPairing(AndroidAdb.tls(AndroidAdb.pairingIdentity(app))).pair(AdbConnectPort.LOCALHOST, target, normalized)
        }
        AdbResult.Ok
    }

    override suspend fun startHelper(command: String, stdinLine: ByteArray, awaitHandoff: suspend () -> Boolean): AdbResult = guarded {
        unsupportedOs()?.let { return@guarded it }
        if (DeveloperSettings.wirelessDebugging(app.contentResolver) == false) {
            return@guarded AdbResult.Failed(AdbFailure.WIRELESS_OFF, "Беспроводная отладка выключена. Включите её в настройках: Для разработчиков.")
        }
        if (!AndroidAdb.hasKey(app)) {
            return@guarded AdbResult.Failed(AdbFailure.NOT_PAIRED, "Приложение не сопряжено с беспроводной отладкой. Выполните сопряжение по коду.")
        }
        val tls = AndroidAdb.tls(AndroidAdb.identity(app))
        val connection = connect(tls)
        try {
            val stream = try {
                runInterruptible(Dispatchers.IO) { connection.exec(command) }
            } catch (e: IOException) {
                return@guarded execFailure(e)
            }
            try {
                runInterruptible(Dispatchers.IO) { stream.write(stdinLine) }
            } catch (e: IOException) {
                return@guarded execFailure(e)
            }
            // Поток держим открытым, пока помощник не отдаст Binder (sh ждёт, пока открыт stdin); потом соединение закрывается
            when (withTimeoutOrNull(HANDOFF_TIMEOUT_MS) { awaitHandoff() }) {
                true -> AdbResult.Ok
                false -> AdbResult.Failed(AdbFailure.TIMEOUT, "Помощник запущен, но не отозвался приложению.")
                null -> AdbResult.Failed(AdbFailure.TIMEOUT, "Помощник не отозвался за отведённое время.")
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { connection.close() } }
        }
    }

    override fun forgetPairing() {
        runCatching { AndroidAdb.forget(app) }.onFailure { Log.w(TAG, "forget pairing failed", it) }
        runCatching { prefs.edit().remove(KEY_PORT).apply() }
    }

    /** Подключается к adbd этого телефона: порт ищется по порядку источников (кэш, свойство, mDNS, скан, см. [AdbConnectPort]). */
    private suspend fun connect(tls: AdbTls): AdbConnection = withContext(Dispatchers.IO) {
        AdbConnectPort(::tlsPort, finder, portCache, scan = scan).connect { host, port -> AdbConnection.open(host, port, tls) }
    }

    private suspend fun findPairingPort(): Int? = try {
        finder.findPorts(AdbServiceTypes.PAIRING, PAIRING_PORT_TIMEOUT_MS).firstOrNull()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "pairing port discovery failed", e)
        null
    }

    private fun unsupportedOs(): AdbResult.Failed? =
        if (Build.VERSION.SDK_INT < MIN_SDK) {
            AdbResult.Failed(AdbFailure.UNSUPPORTED_OS, "Беспроводная отладка есть только в Android 11 и новее (на этом телефоне Android ${Build.VERSION.RELEASE}).")
        } else null

    /** Один вызов за раз: сопряжение и запуск не должны идти одновременно (общий ключ, один порт). Исключения превращаются в [AdbResult.Failed]. */
    private suspend fun guarded(block: suspend () -> AdbResult): AdbResult = try {
        lock.withLock { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        failure(e)
    } catch (e: LinkageError) {
        // NoSuchMethodError и подобное на нестандартных системах: не роняем процесс
        failure(e)
    }

    private fun execFailure(e: IOException): AdbResult.Failed = when {
        e.message?.startsWith("device refused") == true ->
            AdbResult.Failed(AdbFailure.REJECTED, "Телефон отклонил запуск команды через adb.")
        isTimeout(e) || e.message?.contains("not acknowledged") == true ->
            AdbResult.Failed(AdbFailure.TIMEOUT, "adb не ответил на запуск команды вовремя.")
        else -> AdbResult.Failed(AdbFailure.OTHER, "Соединение с adb оборвалось при запуске команды.")
    }

    private fun failure(e: Throwable): AdbResult.Failed {
        Log.w(TAG, "adb operation failed: ${e.javaClass.simpleName}", e)
        val chain = generateSequence(e) { it.cause }
        if (chain.any { it is KeyingMaterialUnavailableException }) {
            return AdbResult.Failed(
                AdbFailure.UNSUPPORTED_OS,
                "Сопряжение по коду не поддерживается на этой версии Android (${Build.VERSION.RELEASE}): системе недоступен нужный TLS-экспорт, он есть в Android 14 и новее.",
            )
        }
        return when (e) {
            is AdbPairingException -> when (e.reason) {
                AdbPairingException.Reason.WRONG_CODE ->
                    AdbResult.Failed(AdbFailure.REJECTED, "Неверный код сопряжения или он устарел. Откройте окно «Сопряжение по коду» заново и введите новый код.")
                AdbPairingException.Reason.CONNECT_FAILED ->
                    AdbResult.Failed(AdbFailure.PORT_NOT_FOUND, "Не удалось подключиться к окну сопряжения: оно закрыто или порт изменился. Откройте окно заново.")
                AdbPairingException.Reason.TLS_FAILED, AdbPairingException.Reason.PROTOCOL ->
                    if (isTimeout(e)) timeout() else AdbResult.Failed(AdbFailure.OTHER, "Не удалось согласовать сопряжение с телефоном (ошибка протокола).")
                AdbPairingException.Reason.NOT_LOCAL ->
                    AdbResult.Failed(AdbFailure.OTHER, "Адрес сопряжения не принадлежит этому телефону.")
            }
            is AdbConnectionException -> when (e.reason) {
                AdbConnectionException.Reason.UNAUTHORIZED ->
                    AdbResult.Failed(AdbFailure.NOT_PAIRED, "Телефон не признаёт ключ приложения (сопряжение отозвано или устарело). Выполните сопряжение заново.")
                AdbConnectionException.Reason.CONNECT_FAILED ->
                    if (isTimeout(e)) timeout() else AdbResult.Failed(
                        AdbFailure.PORT_NOT_FOUND,
                        if (wifiConnected()) "Не найден порт подключения беспроводной отладки. Выключите и снова включите её."
                        else "Не найден порт подключения: телефон не подключён к Wi-Fi, а беспроводная отладка работает только с Wi-Fi.",
                    )
                AdbConnectionException.Reason.TLS_FAILED, AdbConnectionException.Reason.PROTOCOL ->
                    if (isTimeout(e)) timeout() else AdbResult.Failed(AdbFailure.OTHER, "Не удалось установить соединение с adb на этом телефоне.")
                AdbConnectionException.Reason.NOT_LOCAL ->
                    AdbResult.Failed(AdbFailure.OTHER, "Адрес adb не принадлежит этому телефону.")
            }
            is AdbKeyUnreadableException ->
                AdbResult.Failed(AdbFailure.NOT_PAIRED, "Ключ сопряжения не читается (хранилище ключей сброшено). Выполните сопряжение заново.")
            else -> if (isTimeout(e)) timeout() else AdbResult.Failed(AdbFailure.OTHER, "Ошибка adb: ${e.javaClass.simpleName}.")
        }
    }

    private fun timeout() = AdbResult.Failed(AdbFailure.TIMEOUT, "Телефон не ответил вовремя.")

    private fun isTimeout(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { it is SocketTimeoutException || it is TimeoutException }

    private fun wifiConnected(): Boolean = runCatching {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }.getOrDefault(true)

    /** Для сканирования портов берётся только сам телефон: 127.0.0.1 и его IPv4-адрес в Wi-Fi. */
    private fun localHosts(): List<String> = listOf(AdbConnectPort.LOCALHOST) + runCatching {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        cm.allNetworks.filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            .flatMap { cm.getLinkProperties(it)?.linkAddresses.orEmpty() }
            .mapNotNull { (it.address as? Inet4Address)?.hostAddress }
    }.getOrDefault(emptyList())

    /** Диапазон эфемерных портов системы; если приложению читать его не дают, сканируется диапазон по умолчанию. */
    private fun portRangeText(): String? = runCatching { File(PORT_RANGE_FILE).readText() }.getOrNull()

    /** Оригинальный adbd записывает порт подключения беспроводной отладки в это системное свойство; если не читается, остаются кэш, mDNS и скан. */
    @SuppressLint("PrivateApi")
    private fun tlsPort(): Int? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, TLS_PORT_PROPERTY) as String?
    }.getOrNull()?.trim()?.toIntOrNull()

    private companion object {
        const val TAG = "PocketAdb"
        const val PREFS = "pocketcode_wireless_adb"
        const val KEY_PORT = "port"
        /** Беспроводная отладка появилась в Android 11. */
        const val MIN_SDK = 30
        const val PAIRING_PORT_TIMEOUT_MS = 5_000L
        /** Верхняя граница ожидания помощника внутри клиента; вызывающий обычно ждёт меньше. */
        const val HANDOFF_TIMEOUT_MS = 30_000L
        const val TLS_PORT_PROPERTY = "service.adb.tls.port"
        const val PORT_RANGE_FILE = "/proc/sys/net/ipv4/ip_local_port_range"

        /** Общий замок на весь процесс: экземпляров фасада может быть несколько. */
        val lock = Mutex()
    }
}
