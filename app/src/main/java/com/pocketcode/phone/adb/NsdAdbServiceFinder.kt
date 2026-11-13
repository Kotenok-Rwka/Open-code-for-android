// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Ищет порты, которые объявляет adbd этого телефона, через системный mDNS (NsdManager). Принимаются только результаты,
 * адрес которых принадлежит сетевым интерфейсам этого телефона: другие телефоны в той же локальной сети тоже объявляют службы с такими же именами.
 * Получив порт, вызывающий подключается к 127.0.0.1. После первой найденной службы ждём ещё [SETTLE_MS], чтобы собрать
 * сразу несколько записей (старая устаревшая и новая могут прийти вместе); разрешаются они по одной (старые версии системы разрешают только одну за раз).
 */
class NsdAdbServiceFinder(context: Context) : AdbServiceFinder {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)

    override suspend fun findPorts(serviceType: String, timeoutMs: Long): List<Int> {
        val services = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) { services.trySend(info) }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { services.close() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        val ports = LinkedHashSet<Int>()
        try {
            withTimeoutOrNull(timeoutMs) {
                var settleUntil = Long.MAX_VALUE
                while (System.currentTimeMillis() < settleUntil) {
                    val info = withTimeoutOrNull((settleUntil - System.currentTimeMillis()).coerceAtMost(timeoutMs)) {
                        services.receiveCatching().getOrNull()
                    } ?: break
                    resolve(info)?.let {
                        ports += it
                        if (settleUntil == Long.MAX_VALUE) settleUntil = System.currentTimeMillis() + SETTLE_MS
                    }
                }
            }
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
        return ports.toList()
    }

    /** Разрешает одну службу: если она на этом телефоне, отдаёт порт; при ошибке разрешения или чужом адресе — null. */
    private suspend fun resolve(info: NsdServiceInfo): Int? {
        val done = Channel<Int?>(1)
        @Suppress("DEPRECATION")
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) { done.trySend(null) }
            override fun onServiceResolved(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                done.trySend(info.port.takeIf { isLocal(info.host) })
            }
        })
        return withTimeoutOrNull(RESOLVE_MS) { done.receive() }
    }

    private fun isLocal(host: InetAddress?): Boolean {
        if (host == null) return false
        if (host.isLoopbackAddress) return true
        return runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence().any { nic -> nic.inetAddresses.asSequence().any { it == host } }
        }.getOrDefault(false)
    }

    private companion object {
        const val SETTLE_MS = 400L
        const val RESOLVE_MS = 1_500L
    }
}
