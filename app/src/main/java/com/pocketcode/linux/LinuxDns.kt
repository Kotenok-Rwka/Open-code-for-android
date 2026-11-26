package com.pocketcode.linux

/**
 * Содержимое /etc/resolv.conf внутри proot. DNS-серверы активной сети телефона (в том числе
 * внутренний DNS VPN, доступный только через туннель) важнее публичных: при включённом VPN
 * прямые запросы к 8.8.8.8/1.1.1.1 часто блокируются, и apt и запросы к нейросетям висят.
 */
object LinuxDns {
    val FALLBACK = listOf("8.8.8.8", "1.1.1.1")

    /** Текст resolv.conf: до трёх серверов из [servers] (без дублей и пустых), остаток — запасные. */
    fun resolvConf(servers: List<String>): String {
        val seen = LinkedHashSet<String>()
        for (s in servers.map { it.trim() }.filter { it.isNotEmpty() }) {
            if (seen.size >= 3) break
            seen.add(s)
        }
        for (f in FALLBACK) {
            if (seen.size >= 3) break
            seen.add(f)
        }
        return seen.joinToString("") { "nameserver $it\n" }
    }
}
