package com.pocketcode.ui

/**
 * Команда установки официального OpenCode внутри Linux (Alpine + proot). Одна и та же для чата и для терминала.
 *
 * 1. Доставляет системные библиотеки (libgcc, libstdc++, gcompat) и nodejs, npm, git.
 * 2. Ставит opencode-ai с явной платформой (--os=linux --cpu=arm64|x64 --libc=musl), иначе npm в proot может
 *    выбрать не тот бинарник; платформенный пакет ставится вместе с ним.
 * 3. Выдаёт права на чтение и запуск и, если команда opencode не находится (битая ссылка, bin/opencode.exe),
 *    ищет рабочий бинарник в каталоге пакетов и делает ссылку в [linkDir].
 * 4. Проверяет opencode --version; при неудаче печатает диагностику и завершается с кодом 1.
 */
object OpenCodeInstall {
    /** Папка из PATH внутри Linux, куда кладётся ссылка на opencode. */
    const val LINK_DIR = "/usr/local/bin"

    fun command(linkDir: String = LINK_DIR): String =
        "M=''; for p in nodejs npm git libgcc libstdc++ gcompat; do apk info -e \$p >/dev/null 2>&1 || M=\"\$M \$p\"; done; " +
            "if [ -n \"\$M\" ]; then echo \"Ставлю:\$M\"; apk add --no-cache \$M 2>&1 | tail -n 3; " +
            "for p in \$M; do apk info -e \$p >/dev/null 2>&1 || apk add --no-cache \$p 2>&1 | tail -n 2; done; fi; " +
            "case \"\$(uname -m)\" in aarch64|arm64) A=arm64;; *) A=x64;; esac; P=opencode-linux-\$A-musl; " +
            "npm install -g --force --foreground-scripts --os=linux --cpu=\$A --libc=musl opencode-ai \$P > /tmp/oc-install.log 2>&1; " +
            "echo \"код установки: \$?\"; tail -n 15 /tmp/oc-install.log; " +
            "R=\$(npm root -g 2>/dev/null); chmod -R a+rX \"\$R\"/opencode* 2>/dev/null; " +
            "if ! opencode --version >/dev/null 2>&1; then " +
            "for B in \"\$R/opencode-ai/bin/opencode.exe\" \"\$R/\$P/bin/opencode\" \"\$R/opencode-ai/node_modules/\$P/bin/opencode\" \"\$R/opencode-ai/bin/opencode\" " +
            "\$(find \"\$R\" -type f \\( -name opencode -o -name opencode.exe \\) 2>/dev/null); do " +
            "[ -e \"\$B\" ] || continue; chmod a+rx \"\$B\" 2>/dev/null; " +
            "if \"\$B\" --version >/dev/null 2>&1; then " +
            "mkdir -p $linkDir; ln -sf \"\$B\" $linkDir/opencode; " +
            "echo \"Ссылка на opencode: \$B\"; break; " +
            "else echo \"Не запускается: \$B\"; \"\$B\" --version 2>&1 | head -n 3; fi; done; fi; " +
            "opencode --version || { echo \"opencode не найден после установки, npm root: \$R\"; " +
            "ls -la \"\$R/opencode-ai/bin\" \"\$R/\$P/bin\" 2>&1 | head -n 12; exit 1; }"
}
