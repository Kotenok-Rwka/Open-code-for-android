// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone.adb

import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

/**
 * adb подключается только к самому телефону: 127/8, IPv6-loopback или адрес на сетевом интерфейсе этого телефона.
 *
 * Имена системному резолверу не передаются. Кроме точного `localhost` (допустима одна точка в конце, всегда соответствует 127.0.0.1),
 * принимаются только числовые литералы; разбор, проверка и последующее использование в Socket идут по одному и тому же [InetAddress], зазора «проверили, а потом снова разобрали строку» нет.
 */
object AdbHosts {
    private val LOOPBACK4 = byteArrayOf(127, 0, 0, 1)

    private data class LocalInterface(
        val name: String,
        val index: Int,
        val addresses: List<InetAddress>,
    )

    /** Возвращает адрес, проверенный как принадлежащий этому телефону; имена, некорректные литералы и чужие адреса дают null. DNS не используется вообще. */
    fun ownAddress(host: String): InetAddress? {
        val interfaces = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map {
                LocalInterface(it.name, it.index, it.inetAddresses.toList())
            }
        }.getOrDefault(emptyList())
        return ownAddress(host, interfaces)
    }

    /** Возвращает разобранный адрес, который можно сразу отдать Socket; вызывающий не должен заново строить [InetSocketAddress] из исходного host. */
    fun ownSocketAddress(host: String, port: Int): InetSocketAddress? =
        ownAddress(host)?.let { InetSocketAddress(it, port) }

    private fun ownAddress(host: String, interfaces: List<LocalInterface>): InetAddress? {
        val address = parseLiteral(host, interfaces) ?: return null
        if (address.isLoopbackAddress || address.address.size == 4 && address.address[0] == 127.toByte()) return address
        return address.takeIf { target ->
            interfaces.any { nic -> nic.addresses.any { local -> sameAddress(target, local) } }
        }
    }

    private fun sameAddress(target: InetAddress, local: InetAddress): Boolean {
        if (!target.address.contentEquals(local.address)) return false
        // Литерал без scope сравнивается только по адресу; с scope он обязан попасть на тот же интерфейс, нельзя пропускать адрес с тем же значением на другом интерфейсе.
        return target !is Inet6Address || local !is Inet6Address || target.scopeId == 0 || target.scopeId == local.scopeId
    }

    private fun parseLiteral(raw: String, interfaces: List<LocalInterface>): InetAddress? {
        var host = raw.trim()
        if (host.equals("localhost", ignoreCase = true) || host.equals("localhost.", ignoreCase = true)) {
            return InetAddress.getByAddress(null, LOOPBACK4)
        }
        if (host.isEmpty() || host.any { it.isWhitespace() }) return null
        if (host.startsWith('[') || host.endsWith(']')) {
            if (!host.startsWith('[') || !host.endsWith(']') || host.length < 3) return null
            host = host.substring(1, host.length - 1)
            // Квадратные скобки — запись IPv6-литерала, а не универсальная обёртка для произвольной строки.
            if (':' !in host || '[' in host || ']' in host) return null
        }
        return if (':' in host) parseIpv6(host, interfaces) else parseIpv4(host)?.let { InetAddress.getByAddress(null, it) }
    }

    /** Строго четыре десятичные части; сокращения, шестнадцатеричные, восьмеричные и целочисленные записи IPv4 не принимаются. */
    private fun parseIpv4(host: String): ByteArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for ((i, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) return null
            val value = part.fold(0) { n, c -> n * 10 + (c - '0') }
            if (value > 255) return null
            out[i] = value.toByte()
        }
        return out
    }

    private fun parseIpv6(host: String, interfaces: List<LocalInterface>): InetAddress? {
        val percent = host.indexOf('%')
        if (percent >= 0 && host.indexOf('%', percent + 1) >= 0) return null
        val address = if (percent >= 0) host.substring(0, percent) else host
        val zone = if (percent >= 0) host.substring(percent + 1) else ""
        if (percent >= 0 && zone.isEmpty()) return null
        val scope = when {
            percent < 0 -> 0
            zone.all { it in '0'..'9' } -> zone.toLongOrNull()?.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: return null
            else -> interfaces.firstOrNull { it.name == zone }?.index ?: return null
        }
        val bytes = ipv6Bytes(address) ?: return null
        // IPv4-mapped IPv6 приводим к IPv4, чтобы 16-байтовая запись не позволила обойти сравнение с 127/8 и адресами интерфейсов.
        if (isIpv4Mapped(bytes)) {
            if (scope != 0) return null
            return InetAddress.getByAddress(null, bytes.copyOfRange(12, 16))
        }
        return Inet6Address.getByAddress(null, bytes, scope)
    }

    private fun isIpv4Mapped(bytes: ByteArray): Boolean =
        (0 until 10).all { bytes[it] == 0.toByte() } && bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()

    /** Раскрываем IPv6 сами: InetAddress.getByName даже на кривом числовом вводе может обратиться к системному разрешению имён. */
    private fun ipv6Bytes(address: String): ByteArray? {
        if (address.isEmpty()) return null
        val halves = address.split("::")
        if (halves.size > 2) return null
        val compressed = halves.size == 2
        val left: List<Int>
        val right: List<Int>
        val whole: List<Int>
        if (compressed) {
            left = ipv6Groups(halves[0], allowIpv4 = false) ?: return null
            right = ipv6Groups(halves[1], allowIpv4 = true) ?: return null
            whole = left + right
            if (whole.size >= 8) return null
        } else {
            whole = ipv6Groups(address, allowIpv4 = true) ?: return null
            if (whole.size != 8) return null
            left = whole
            right = emptyList()
        }
        val out = ByteArray(16)
        fun write(groups: List<Int>, startGroup: Int) {
            for ((i, group) in groups.withIndex()) {
                out[(startGroup + i) * 2] = (group ushr 8).toByte()
                out[(startGroup + i) * 2 + 1] = group.toByte()
            }
        }
        if (compressed) {
            write(left, 0)
            write(right, 8 - right.size)
        } else {
            write(whole, 0)
        }
        return out
    }

    /** Возвращает 16-битные группы; встроенный IPv4 в конце занимает две группы. */
    private fun ipv6Groups(text: String, allowIpv4: Boolean): List<Int>? {
        if (text.isEmpty()) return emptyList()
        val parts = text.split(':')
        if (parts.any { it.isEmpty() }) return null
        val groups = ArrayList<Int>(8)
        for ((i, part) in parts.withIndex()) {
            if ('.' in part) {
                if (!allowIpv4 || i != parts.lastIndex) return null
                val ipv4 = parseIpv4(part) ?: return null
                groups += ((ipv4[0].toInt() and 0xff) shl 8) or (ipv4[1].toInt() and 0xff)
                groups += ((ipv4[2].toInt() and 0xff) shl 8) or (ipv4[3].toInt() and 0xff)
            } else {
                if (part.length > 4 || part.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
                groups += part.toInt(16)
            }
        }
        return groups.takeIf { it.size <= 8 }
    }
}
