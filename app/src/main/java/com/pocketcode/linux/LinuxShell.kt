package com.pocketcode.linux

import com.pocketcode.tools.Shell
import com.pocketcode.tools.Workspace
import com.pocketcode.tools.runProcess
import java.io.File

/** Shell, выполняющий команды внутри Linux (proot). Папка проекта — /work. */
class LinuxShell(private val env: LinuxEnv, private val ws: Workspace) : Shell {
    /** Описание зависит от образа: агент должен знать, apk перед ним или apt. */
    override val info: String
        get() {
            val example = LinuxPkgs.installHint(env.flavor).replace("<пакет>", LinuxPkgs.packageNames(env.flavor))
            return "Linux ${LinuxPkgs.distroName(env.flavor)} (proot, эмуляция root): настоящий sh, " +
                "${LinuxPkgs.manager(env.flavor)} (например `$example`), пакеты ставятся из интернета. " +
                "Папка проекта смонтирована как /work, команды стартуют в ней. Файлы вне /work живут в закрытом rootfs. " +
                "Нет systemd и демонов, интерактивный ввод не поддерживается."
        }

    override suspend fun run(command: String, cwd: File, timeoutSec: Long): String =
        runProcess(builder(command, cwd), timeoutSec)

    override fun builder(command: String, cwd: File): ProcessBuilder = env.processBuilder(command, ws.root, cwd)
}

/** Переключаемый Shell: Linux, если он установлен и включён, иначе системный sh Android. */
class SwitchableShell(private val android: Shell, private val linux: () -> Shell?) : Shell {
    private val current: Shell get() = linux() ?: android
    override val info: String get() = current.info
    override suspend fun run(command: String, cwd: File, timeoutSec: Long) = current.run(command, cwd, timeoutSec)
    override fun builder(command: String, cwd: File) = current.builder(command, cwd)
}
