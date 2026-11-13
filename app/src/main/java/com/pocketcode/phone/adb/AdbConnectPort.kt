// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone.adb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Порт, к которому подключались в прошлый раз (хранится в приватной области приложения). */
interface PortCache {
    fun load(): Int?
    fun save(port: Int)
}

/**
 * Сколько кандидатов дал каждый источник при поиске порта подключения (сами порты не включены, нужно для диагностики).
 * Если [scanned] истинно, локальный хост сканировали: [scanOpen] портов открыто, из них [scanAdb] похожи на adbd; [manual] — вместе с этим вызовом передан порт, введённый пользователем.
 */
data class ConnectCandidates(
    val property: Int,
    val mdns: Int,
    val cache: Int,
    val connected: Boolean,
    val scanned: Boolean = false,
    val scanOpen: Int = 0,
    val scanAdb: Int = 0,
    val manual: Boolean = false,
)

/**
 * Ищет порт подключения беспроводной отладки на этом же телефоне и подключается. Источники пробуются по порядку до первого успеха: порт прошлого подключения,
 * системное свойство (если читается), каждый порт из mDNS, сканирование локальных портов ([scan], только если предыдущие не помогли), порт, введённый пользователем ([manual]).
 * Одного mDNS мало: adbd регистрирует службу один раз при включении отладки, после перезапуска mDNS-демона регистрация пропадает, а некоторые сети режут multicast;
 * mDNS может вернуть и устаревший порт, поэтому пробуются все найденные. Порт из кэша при устаревании отвергается быстро, поэтому он идёт первым.
 * Если пользователь указал порт, сканирование не запускается (его просили ввести именно потому, что скан ничего не нашёл), порт пробуется сразу.
 * Отказ на уровне TLS (UNAUTHORIZED, TLS_FAILED) не всегда значит неверный ключ: в некоторых системах первое TLS-рукопожатие на порту перехватывает другая служба,
 * отвергает любой сертификат, а следующее подключение уже доходит до adbd. Поэтому тот же порт пробуется ещё раз через [tlsRetryDelayMs], затем остальные кандидаты;
 * UNAUTHORIZED (нужно новое сопряжение) сообщается, только если ни один порт не подошёл и хотя бы один отверг нас. Если какой-то порт уже отверг нас,
 * локальные порты не сканируются: adbd найден, и скан нашёл бы только его.
 */
class AdbConnectPort(
    private val property: () -> Int?,
    private val finder: AdbServiceFinder,
    private val cache: PortCache,
    private val mdnsTimeoutMs: Long = 3_000,
    /** Один раз по окончании подключения (успех или ошибка) сообщает, сколько кандидатов дал каждый источник. */
    private val report: (ConnectCandidates) -> Unit = {},
    private val scan: LocalPortScan? = null,
    /** Порт, введённый пользователем в этот раз; null, если не вводил. */
    private val manual: () -> Int? = { null },
    /** Вызывается в начале и в конце сканирования локальных портов (чтобы показать «идёт поиск на телефоне…»). */
    private val scanning: (Boolean) -> Unit = {},
    private val localHost: String = LOCALHOST,
    private val tlsRetryDelayMs: Long = 300,
) {
    /** Пробует кандидатов через [open] по очереди и возвращает первый удавшийся результат; если ни один не подошёл, бросает UNAUTHORIZED (когда порт нас отверг) или последнюю ошибку. */
    suspend fun <T> connect(open: (host: String, port: Int) -> T): T {
        val tried = HashSet<AdbEndpoint>()
        val triedPorts = HashSet<Int>()
        var last: AdbConnectionException? = null
        var rejected: AdbConnectionException? = null
        val manualPort = manual()?.takeIf { it in 1..65535 }
        var scanResult: ScanResult? = null
        fun local(vararg ports: Int?) = ports.filterNotNull().map { AdbEndpoint(localHost, it) }
        val sources: List<suspend () -> List<AdbEndpoint>> = listOf(
            { local(cache.load()) },
            { local(property()) },
            { finder.findPorts(AdbServiceTypes.CONNECT, mdnsTimeoutMs).map { AdbEndpoint(localHost, it) } },
            {
                if (scan == null || manualPort != null || rejected != null) emptyList() else {
                    scanning(true)
                    try {
                        scan.scan(triedPorts).also { scanResult = it }.endpoints
                    } finally {
                        scanning(false)
                    }
                }
            },
            { local(manualPort) },
        )
        val counts = IntArray(sources.size)
        var connected = false
        try {
            for ((i, source) in sources.withIndex()) {
                // Источник, который не читается (свойство закрыто, mDNS не стартует), считаем пустым, а не ошибкой
                val endpoints = try {
                    source()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
                counts[i] = endpoints.size
                for (ep in endpoints) {
                    if (ep.port !in 1..65535 || !tried.add(ep)) continue
                    triedPorts += ep.port
                    var retried = false
                    while (true) {
                        try {
                            return open(ep.host, ep.port).also { cache.save(ep.port); connected = true }
                        } catch (e: AdbConnectionException) {
                            // Отказ по адресу — не временная недоступность порта: нельзя ни переходить к другому порту, ни повторять позже.
                            if (e.reason == AdbConnectionException.Reason.NOT_LOCAL) throw e
                            val tls = e.reason == AdbConnectionException.Reason.UNAUTHORIZED ||
                                e.reason == AdbConnectionException.Reason.TLS_FAILED
                            if (tls && !retried) {
                                retried = true
                                delay(tlsRetryDelayMs)
                                continue
                            }
                            if (e.reason == AdbConnectionException.Reason.UNAUTHORIZED) rejected = e
                            last = e
                            break
                        }
                    }
                }
            }
            throw rejected ?: last ?: AdbConnectionException(AdbConnectionException.Reason.CONNECT_FAILED, "no wireless debugging port found")
        } finally {
            val s = scanResult
            report(
                ConnectCandidates(
                    property = counts[1], mdns = counts[2], cache = counts[0], connected = connected,
                    scanned = s != null, scanOpen = s?.open ?: 0, scanAdb = s?.adb ?: 0, manual = manualPort != null,
                ),
            )
        }
    }

    companion object {
        const val LOCALHOST = "127.0.0.1"
    }
}
