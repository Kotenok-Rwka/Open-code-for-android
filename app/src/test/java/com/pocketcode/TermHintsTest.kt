package com.pocketcode

import com.pocketcode.ui.TermHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TermHintsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun detectsQuestionsOfRunningProgram() {
        assertEquals(TermHints.Prompt.YES_NO, TermHints.detectPrompt("Установить пакеты?\nProceed? [Y/n] "))
        assertEquals(TermHints.Prompt.YES_NO, TermHints.detectPrompt("Continue (yes/no)?"))
        assertEquals(TermHints.Prompt.YES_NO, TermHints.detectPrompt("Продолжить? [д/н]"))
        assertEquals(TermHints.Prompt.ENTER, TermHints.detectPrompt("Press Enter to continue"))
        assertEquals(TermHints.Prompt.PASSWORD, TermHints.detectPrompt("[sudo] password for root: "))
        assertEquals(TermHints.Prompt.PASSWORD, TermHints.detectPrompt("Пароль: "))
        assertNull(TermHints.detectPrompt("Compiling main.kt…"))
        assertNull(TermHints.detectPrompt(""))
        // вопрос был раньше, программа уже пошла дальше
        assertNull(TermHints.detectPrompt("Proceed? [Y/n]\nинсталляция началась"))
    }

    @Test
    fun yesNoDefault() {
        assertEquals(true, TermHints.yesNoDefault("Proceed? [Y/n]"))
        assertEquals(false, TermHints.yesNoDefault("Delete all? [y/N]"))
        assertNull(TermHints.yesNoDefault("Continue (y/n)?"))
        assertNull(TermHints.yesNoDefault("ничего"))
    }

    @Test
    fun exitCodeParsing() {
        assertEquals(1, TermHints.parseExitCode("ошибка\n[код выхода: 1]"))
        assertEquals(0, TermHints.parseExitCode("ok\n[код выхода: 0]"))
        assertEquals(-1, TermHints.parseExitCode("[код выхода: -1]"))
        assertNull(TermHints.parseExitCode("просто вывод"))
    }

    @Test
    fun fixesForPython() {
        val out = "Traceback...\nModuleNotFoundError: No module named 'aiogram'"
        assertEquals(listOf("pip install aiogram"), TermHints.fixes("python3 bot.py", out, false))
        assertEquals(listOf("pip install --break-system-packages aiogram"), TermHints.fixes("python3 bot.py", out, true))
        assertEquals(
            listOf("pip install pillow"),
            TermHints.fixes("python3 x.py", "ModuleNotFoundError: No module named 'PIL.Image'", false),
        )
    }

    @Test
    fun fixesForNodeAndShell() {
        assertEquals(listOf("npm install express"), TermHints.fixes("node a.js", "Error: Cannot find module 'express'", false))
        assertEquals(
            listOf("npm install @grammyjs/types"),
            TermHints.fixes("node a.js", "Error: Cannot find module '@grammyjs/types/message'", false),
        )
        // относительный модуль — это свой файл, а не пакет
        assertTrue(TermHints.fixes("node a.js", "Error: Cannot find module './config'", false).isEmpty())
        assertEquals(listOf("apk add python3"), TermHints.fixes("python foo.py", "sh: python: not found", true))
        assertEquals(listOf("apk add git"), TermHints.fixes("git status", "sh: git: not found", true))
        assertTrue(TermHints.fixes("git status", "sh: git: not found", false).isEmpty())
        assertEquals(listOf("chmod +x ./gradlew"), TermHints.fixes("./gradlew build", "sh: ./gradlew: Permission denied", false))
        assertEquals(listOf("npm init -y"), TermHints.fixes("npm start", "npm ERR! enoent ENOENT: no such file package.json", false))
        assertEquals(listOf("ps"), TermHints.fixes("node s.js", "Error: listen EADDRINUSE: address already in use :::3000", false))
        assertTrue(TermHints.fixes("ls", "всё хорошо", true).isEmpty())
    }

    @Test
    fun assistantPromptHasCommandOutputAndCode() {
        val p = TermHints.explainPrompt("python3 bot.py", "Traceback\nValueError", 1)
        assertTrue(p.contains("python3 bot.py"))
        assertTrue(p.contains("ValueError"))
        assertTrue(p.contains("кодом 1"))
        assertTrue(!TermHints.explainPrompt("x", "y", null).contains("кодом"))
    }

    @Test
    fun cdStaysInsideProject() {
        val root = tmp.newFolder("proj")
        val sub = File(root, "server").also { it.mkdirs() }
        File(sub, "deep").mkdirs()

        val toSub = TermHints.parseCd(root, root, "cd server")
        assertTrue(toSub is TermHints.Cd.Ok)
        assertEquals(sub.canonicalPath, (toSub as TermHints.Cd.Ok).dir.canonicalPath)

        val deep = TermHints.parseCd(root, sub, "cd deep") as TermHints.Cd.Ok
        assertEquals("server/deep", TermHints.relPath(root, deep.dir))

        val up = TermHints.parseCd(root, deep.dir, "cd ../..") as TermHints.Cd.Ok
        assertEquals("", TermHints.relPath(root, up.dir))

        assertTrue(TermHints.parseCd(root, root, "cd ..") is TermHints.Cd.Fail)
        assertTrue(TermHints.parseCd(root, root, "cd /etc") is TermHints.Cd.Fail)
        assertTrue(TermHints.parseCd(root, root, "cd нет-такой") is TermHints.Cd.Fail)

        val home = TermHints.parseCd(root, sub, "cd") as TermHints.Cd.Ok
        assertEquals("", TermHints.relPath(root, home.dir))
        val viaWork = TermHints.parseCd(root, root, "cd /work/server") as TermHints.Cd.Ok
        assertEquals("server", TermHints.relPath(root, viaWork.dir))
    }

    @Test
    fun cdInsideCompoundCommandIsLeftToTheShell() {
        val root = tmp.newFolder("p2")
        File(root, "a").mkdirs()
        assertTrue(TermHints.parseCd(root, root, "cd a && ls") is TermHints.Cd.NotCd)
        assertTrue(TermHints.parseCd(root, root, "cd a; ls") is TermHints.Cd.NotCd)
        assertTrue(TermHints.parseCd(root, root, "ls -la") is TermHints.Cd.NotCd)
        assertTrue(TermHints.parseCd(root, root, "cdrom") is TermHints.Cd.NotCd)
    }
}
