package com.pocketcode

import com.pocketcode.linux.LinuxDns
import org.junit.Assert.assertEquals
import org.junit.Test

class LinuxDnsTest {
    @Test
    fun dnsServersGoFirstAndFallbackFillsTheRest() {
        // 1 сервер из сети + запасные до трёх всего
        assertEquals(
            "nameserver 10.111.0.1\nnameserver 8.8.8.8\nnameserver 1.1.1.1\n",
            LinuxDns.resolvConf(listOf("10.111.0.1")),
        )
    }

    @Test
    fun duplicatesAndBlanksAreDropped() {
        // 3 уникальных — запасные не добавляются; бланк и дубль отфильтрованы
        assertEquals(
            "nameserver 10.0.0.1\nnameserver 10.0.0.2\nnameserver 10.0.0.3\n",
            LinuxDns.resolvConf(listOf("10.0.0.1", "", "10.0.0.2", "10.0.0.1", "10.0.0.3", "10.0.0.4")),
        )
    }

    @Test
    fun emptyNetworkMeansFallbackOnly() {
        assertEquals("nameserver 8.8.8.8\nnameserver 1.1.1.1\n", LinuxDns.resolvConf(emptyList()))
    }
}
