package com.pocketcode.ui

/**
 * Команда установки официального OpenCode внутри Linux (Alpine + proot). Одна и та же для чата и для терминала.
 *
 * Новые версии пакета opencode-ai указывают команду на bin/opencode.exe, которую создаёт postinstall,
 * и npm не всегда делает из неё ссылку в PATH (либо остаётся старая битая ссылка). Поэтому после установки
 * команда проверяется, а при необходимости ссылка на рабочий бинарник кладётся в [linkDir].
 */
object OpenCodeInstall {
    /** Папка из PATH внутри Linux, куда кладётся ссылка на opencode. */
    const val LINK_DIR = "/usr/local/bin"

    fun command(linkDir: String = LINK_DIR): String =
        "(command -v npm >/dev/null 2>&1 || (echo 'Ставлю nodejs и npm…'; apk add --no-cache nodejs npm git libstdc++ libgcc 2>&1 | tail -n 3)); " +
            "case \"\$(uname -m)\" in aarch64|arm64) P=opencode-linux-arm64-musl;; *) P=opencode-linux-x64-musl;; esac; " +
            "npm install -g --force opencode-ai \$P > /tmp/oc-install.log 2>&1; echo \"код установки: \$?\"; tail -n 15 /tmp/oc-install.log; " +
            "R=\$(npm root -g 2>/dev/null); " +
            "if ! opencode --version >/dev/null 2>&1; then " +
            "for B in \"\$R/opencode-ai/bin/opencode.exe\" \"\$R/\$P/bin/opencode\" \"\$R/opencode-ai/node_modules/\$P/bin/opencode\" \"\$R/opencode-ai/bin/opencode\"; do " +
            "[ -e \"\$B\" ] || continue; " +
            "if \"\$B\" --version >/dev/null 2>&1; then " +
            "mkdir -p $linkDir; ln -sf \"\$B\" $linkDir/opencode; " +
            "echo \"Ссылка на opencode: \$B\"; break; " +
            "else echo \"Не запускается: \$B\"; \"\$B\" --version 2>&1 | head -n 3; fi; done; fi; " +
            "opencode --version || { echo \"opencode не найден после установки, npm root: \$R\"; " +
            "ls -la \"\$R/opencode-ai/bin\" \"\$R/\$P/bin\" 2>&1 | head -n 12; exit 1; }"
}
