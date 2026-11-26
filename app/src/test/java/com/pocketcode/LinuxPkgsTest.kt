package com.pocketcode

import com.pocketcode.linux.LinuxPkgs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinuxPkgsTest {
    @Test
    fun pipIsMappedToPy3PipInAlpine() {
        // В Alpine пакета «pip» нет — только py3-pip (ошибка «pip (no such package)» на скрине пользователя)
        assertEquals(
            "apk add --no-cache py3-pip",
            LinuxPkgs.install("ALPINE", "pip"),
        )
    }

    @Test
    fun pipIsMappedToPython3PipInUbuntu() {
        // Обновление индекса и установка — отдельные шаги, чтобы показывать прогресс;
        // recommends отключены: иначе apt тянет GTK/dbus и dpkg падает в proot.
        val cmd = LinuxPkgs.install("UBUNTU", "pip")
        assertTrue(cmd.startsWith("apt-get "))
        assertTrue(cmd.contains(" update && "))
        assertTrue(cmd.endsWith("install -y --no-install-recommends python3-pip"))
        assertTrue(cmd.contains("-o Dpkg::Use-Pty=0"))
    }

    @Test
    fun installOnlyDoesNotUpdateIndex() {
        assertEquals(
            "DEBIAN_FRONTEND=noninteractive apt-get -o Acquire::Retries=3 -o Acquire::http::Timeout=20 -o Acquire::https::Timeout=20 -o Acquire::ForceIPv4=true -o Dpkg::Use-Pty=0 install -y --no-install-recommends python3-pip",
            LinuxPkgs.installOnly("UBUNTU", "pip"),
        )
    }

    @Test
    fun installToolsSetUsesValidAlpineNames() {
        // Набор кнопки «Поставить python, git, node» не должен содержать пакетов, которых нет в Alpine
        val cmd = LinuxPkgs.install("ALPINE", "bash", "git", "python3", "pip", "nodejs", "npm", "curl")
        assertEquals("apk add --no-cache bash git python3 py3-pip nodejs npm curl", cmd)
    }
}
