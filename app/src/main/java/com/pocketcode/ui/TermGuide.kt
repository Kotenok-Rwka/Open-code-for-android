package com.pocketcode.ui

/**
 * Подсказки терминала без нейросети: объяснение команды, предупреждение об опасных, дополнение по началу,
 * «возможно, имелось в виду», понятный разбор ошибки по коду выхода и тексту. Чистая логика без Android.
 */
object TermGuide {
    private val commands: Map<String, String> = mapOf(
        "ls" to "показывает файлы в папке",
        "cd" to "переходит в другую папку",
        "pwd" to "показывает, в какой папке ты сейчас",
        "cat" to "выводит содержимое файла",
        "echo" to "печатает текст",
        "mkdir" to "создаёт папку",
        "rm" to "удаляет файлы (без корзины, вернуть нельзя)",
        "cp" to "копирует файлы",
        "mv" to "переносит или переименовывает файлы",
        "touch" to "создаёт пустой файл",
        "grep" to "ищет текст в файлах или в выводе",
        "find" to "ищет файлы по имени",
        "head" to "показывает начало файла",
        "tail" to "показывает конец файла",
        "chmod" to "меняет права на файл",
        "df" to "показывает свободное место",
        "du" to "считает, сколько места занимают файлы",
        "ps" to "показывает запущенные процессы",
        "kill" to "останавливает процесс по номеру",
        "curl" to "скачивает данные по ссылке",
        "wget" to "скачивает файл по ссылке",
        "tar" to "работает с архивами tar",
        "unzip" to "распаковывает zip",
        "git" to "работа с историей изменений проекта",
        "python" to "запускает Python",
        "python3" to "запускает Python 3",
        "pip" to "ставит библиотеки Python",
        "pip3" to "ставит библиотеки Python",
        "node" to "запускает JavaScript (Node.js)",
        "npm" to "ставит и запускает пакеты Node.js",
        "apk" to "менеджер программ Alpine Linux",
        "opencode" to "официальный OpenCode, ИИ-агент для кода",
        "which" to "показывает, где лежит программа",
        "uname" to "показывает систему и процессор",
        "clear" to "очищает экран",
        "sh" to "запускает скрипт оболочкой sh",
        "bash" to "запускает скрипт оболочкой bash",
        "sleep" to "ждёт указанное число секунд",
        "ping" to "проверяет связь с сервером",
        "sed" to "заменяет текст в потоке или файле",
        "wc" to "считает строки, слова, байты",
        "sort" to "сортирует строки",
        "export" to "задаёт переменную окружения",
        "java" to "запускает Java",
        "javac" to "компилирует Java",
        "gradle" to "собирает проект Gradle",
        "make" to "собирает проект по Makefile",
        "ssh" to "подключается к другому компьютеру",
        "adb" to "управляет Android-устройством",
    )

    private val subs: Map<String, String> = mapOf(
        "git status" to "что изменилось в проекте",
        "git add" to "готовит файлы к сохранению",
        "git commit" to "сохраняет изменения в историю",
        "git push" to "отправляет историю на GitHub",
        "git pull" to "забирает новое с GitHub",
        "git clone" to "скачивает репозиторий",
        "git log" to "показывает историю сохранений",
        "git diff" to "показывает, что именно изменилось",
        "git init" to "превращает папку в репозиторий",
        "npm install" to "ставит зависимости проекта",
        "npm run" to "запускает скрипт из package.json",
        "npm init" to "создаёт package.json",
        "npm start" to "запускает проект",
        "pip install" to "ставит библиотеку",
        "pip list" to "показывает установленные библиотеки",
        "apk add" to "ставит программу Alpine",
        "apk update" to "обновляет список программ",
        "apk search" to "ищет программу",
        "apk del" to "удаляет программу",
        "opencode run" to "даёт агенту одну задачу и выходит",
        "opencode auth" to "вход и ключи провайдеров",
        "opencode models" to "список моделей",
        "opencode stats" to "статистика использования",
    )

    private val flags: Map<String, String> = mapOf(
        "-la" to "все файлы, включая скрытые, подробным списком",
        "-l" to "подробный список",
        "-a" to "включая скрытые",
        "-h" to "понятные размеры (МБ, ГБ)",
        "-r" to "рекурсивно, со вложенными папками",
        "-R" to "рекурсивно, со вложенными папками",
        "-f" to "без вопросов, принудительно",
        "-rf" to "рекурсивно и без вопросов",
        "-y" to "отвечать «да» на вопросы",
        "-p" to "создавать вложенные папки или сохранять права",
        "-v" to "подробный вывод",
        "--version" to "показать версию",
        "--help" to "показать справку",
        "-n" to "номера строк или без перевода строки",
        "-i" to "без учёта регистра или правка на месте",
        "-g" to "глобально, на всю систему",
        "-O" to "сохранить под именем с сервера",
        "-o" to "куда сохранить результат",
        "-L" to "идти по перенаправлениям",
        "-s" to "тихий режим",
        "--no-cache" to "не хранить кэш (экономит место)",
        "--break-system-packages" to "разрешить pip ставить в систему",
    )

    private val operators: List<Pair<String, String>> = listOf(
        "&&" to "следующая команда выполнится, только если эта прошла успешно",
        "||" to "следующая команда выполнится, только если эта не удалась",
        "|" to "вывод этой команды уходит на вход следующей",
        ">>" to "дописать вывод в конец файла",
        ">" to "записать вывод в файл (старое содержимое пропадёт)",
        "2>&1" to "ошибки выводятся вместе с обычным выводом",
        ";" to "команды идут одна за другой независимо от результата",
        "&" to "команда запускается в фоне",
    )

    /** Разбивает на простые команды по &&, ||, ;, |. */
    private fun segments(cmd: String): List<List<String>> =
        cmd.split(Regex("""&&|\|\||;|\|""")).map { it.trim().split(Regex("""\s+""")).filter { w -> w.isNotEmpty() } }.filter { it.isNotEmpty() }

    /** Строки-объяснения: что делает каждая часть команды. Пустой список, если нечего сказать. */
    fun explain(cmd: String, tr: (String) -> String = { it }): List<String> {
        val out = ArrayList<String>()
        for (seg in segments(cmd)) {
            val name = seg[0].substringAfterLast('/')
            val sub = seg.getOrNull(1)
            val key = if (sub != null) "$name $sub" else name
            val base = commands[name] ?: continue
            val subText = subs[key]
            val fl = seg.drop(1).filter { it.startsWith("-") }.mapNotNull { f -> flags[f]?.let { "$f: ${tr(it)}" } }
            val head = if (subText != null) "$key: ${tr(subText)}" else "$name: ${tr(base)}"
            out += if (fl.isEmpty()) head else head + " (" + fl.joinToString("; ") + ")"
        }
        if (out.isNotEmpty()) for ((op, text) in operators) if (cmd.contains(op) && !(op == "&" && (cmd.contains("&&") || cmd.contains("2>&1"))) && !(op == "|" && cmd.contains("||")) && !(op == ">" && (cmd.contains(">>") || cmd.contains("2>&1")))) out += "$op: ${tr(text)}"
        return out.take(6)
    }

    private val dangerRx = listOf(
        Regex("""\brm\s+(-[a-zA-Z]*[rf][a-zA-Z]*\s+)+(/|~|\*|\.|/\*|[$]HOME)(\s|$)"""),
        Regex("""\bmkfs\b"""),
        Regex("""\bdd\s+[^|;]*of=/dev/"""),
        Regex(""">\s*/dev/(sd|mmc|block)"""),
        Regex(""":\(\)\s*\{"""),
        Regex("""\bchmod\s+-R\s+[0-7]*\s+/(\s|$)"""),
    )

    /** Команда, после которой можно потерять все данные: перед запуском нужно подтверждение. */
    fun isDangerous(cmd: String): Boolean = dangerRx.any { it.containsMatchIn(cmd) }

    fun dangerText(): String = "Эта команда может безвозвратно стереть много файлов. Запустить?"

    /** Дополнение по началу ввода: сначала история, потом известные команды. До 5 вариантов. */
    fun suggest(prefix: String, history: List<String>): List<String> {
        val p = prefix.trimStart()
        if (p.isEmpty()) return emptyList()
        val res = LinkedHashSet<String>()
        history.asReversed().filter { it.startsWith(p) && it != p }.forEach { res += it }
        if (!p.contains(' ')) {
            (commands.keys + subs.keys.map { it.substringBefore(' ') }).filter { it.startsWith(p) && it != p }.sorted().forEach { res += it }
        } else {
            subs.keys.filter { it.startsWith(p) && it != p }.sorted().forEach { res += it }
        }
        return res.take(5)
    }

    /** Расстояние Дамерау-Левенштейна: перестановка соседних букв считается одной ошибкой. */
    private fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            var v = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, d[i - 2][j - 2] + 1)
            d[i][j] = v
        }
        return d[a.length][b.length]
    }

    /** Если первое слово похоже на опечатку известной команды, возвращает исправленную команду целиком. */
    fun didYouMean(cmd: String): String? {
        val words = cmd.trim().split(Regex("""\s+"""), limit = 2)
        val first = words[0]
        if (first.length < 2 || first in commands || first.contains('/') || first.contains('=')) return null
        val best = commands.keys.filter { it.length >= 2 }.map { it to distance(first, it) }.minByOrNull { it.second } ?: return null
        val limit = if (first.length <= 3) 1 else 2
        if (best.second > limit) return null
        return best.first + if (words.size > 1) " " + words[1] else ""
    }

    private val rules: List<Pair<Regex, String>> = listOf(
        Regex("""(?i)no space left|ENOSPC""") to "Закончилось место. Освободи память телефона или удали ненужное: `du -sh * | sort -h`.",
        Regex("""(?i)permission denied|EACCES""") to "Не хватает прав. Для своего скрипта помогает `chmod +x файл`.",
        Regex("""(?i)could not resolve|name or service not known|temporary failure in name resolution|ENOTFOUND|EAI_AGAIN""") to "Не находится адрес сервера: проверь интернет (при блокировках включи VPN).",
        Regex("""(?i)connection refused|ECONNREFUSED""") to "Сервер отказал в соединении: программа, к которой ты подключаешься, не запущена или порт другой.",
        Regex("""(?i)timed out|ETIMEDOUT|timeout""") to "Время ожидания вышло: медленный или заблокированный интернет. Повтори или включи VPN.",
        Regex("""(?i)\bssl\b|certificate|CERT_""") to "Проблема с сертификатом: поставь `apk add ca-certificates` или проверь дату на телефоне.",
        Regex("""(?i)ENOTEMPTY""") to "npm не смог заменить папку (остатки прошлой установки). Нажми «Установить OpenCode» заново: остатки будут стёрты.",
        Regex("""(?i)address already in use|EADDRINUSE""") to "Порт занят другой программой. Останови её или выбери другой порт.",
        Regex("""(?i)syntax ?error""") to "Синтаксическая ошибка: опечатка в коде или неверная кавычка. Смотри номер строки в выводе.",
        Regex("""(?i)no such file or directory|ENOENT""") to "Такого файла или папки нет. Проверь путь: `pwd` и `ls`.",
        Regex("""(?i)not a git repository""") to "Эта папка ещё не репозиторий: выполни `git init`.",
        Regex("""(?i)no module named""") to "Не хватает библиотеки Python: поставь через pip (кнопка ниже).",
        Regex("""(?i)cannot find module""") to "Не хватает пакета Node.js: поставь через npm (кнопка ниже).",
        Regex("""(?i)aapt2""") to "Gradle не запускает aapt2 на процессоре ARM. Собери APK в облаке через GitHub Actions (агент это умеет).",
        Regex("""(?i)out of memory|OutOfMemoryError|cannot allocate memory""") to "Не хватило оперативной памяти. Закрой другие приложения или уменьши нагрузку (например, `--no-daemon`).",
    )

    private val codes: Map<Int, String> = mapOf(
        126 to "Файл есть, но его нельзя запустить: нужен `chmod +x`.",
        127 to "Команда не найдена: опечатка или программа не установлена (`apk add ...`).",
        130 to "Остановлено вручную (Ctrl+C).",
        137 to "Процесс убит системой, чаще всего из-за нехватки памяти.",
        124 to "Команда не уложилась в лимит времени и была остановлена.",
        139 to "Программа аварийно завершилась (ошибка сегментации). Для proot иногда помогает режим совместимости в настройках Linux.",
    )

    /** Понятное объяснение неудачного запуска или null, если сказать нечего. */
    fun diagnose(out: String, code: Int?): String? {
        if (code == null || code == 0) return null
        for ((rx, text) in rules) if (rx.containsMatchIn(out)) return text
        return codes[code]
    }

    /** Следующий разумный шаг после удачной команды (или null). */
    fun nextStep(cmd: String, code: Int?): String? {
        if (code != 0) return null
        val c = cmd.trim()
        return when {
            c.startsWith("git init") -> "Дальше: `git add . && git commit -m \"first\"`"
            c.startsWith("git clone") -> "Дальше: зайди в папку командой `cd имя`"
            c.startsWith("npm init") -> "Дальше: `npm install` нужные пакеты"
            c.startsWith("npm install") && !c.contains(" -g") -> "Запуск обычно через `npm start` или `npm run dev`"
            c.startsWith("pip install") -> "Проверь: `pip list | grep имя`"
            c.startsWith("apk add") -> "Проверь: `which имя`"
            c.startsWith("mkdir") -> "Зайти в папку: `cd имя`"
            c.startsWith("opencode --version") -> "Запуск агента: `opencode run \"задача\"`"
            else -> null
        }
    }

    /** Шпаргалка: заголовок и команды с пояснением. */
    val cheatSheet: List<Pair<String, List<Pair<String, String>>>> = listOf(
        "Файлы" to listOf("ls -la" to "список с подробностями", "cd папка" to "войти в папку", "cd .." to "выйти на уровень выше", "cat файл" to "показать файл", "mkdir имя" to "создать папку", "cp а б" to "копировать", "mv а б" to "переименовать", "rm файл" to "удалить"),
        "Поиск" to listOf("grep -rn текст ." to "найти текст в файлах", "find . -name \"*.kt\"" to "найти файлы по имени", "which python3" to "где лежит программа"),
        "Установка" to listOf("apk add python3 git nodejs npm" to "поставить программы", "pip install --break-system-packages имя" to "библиотека Python", "npm install имя" to "пакет Node.js"),
        "Git" to listOf("git status" to "что изменилось", "git add . && git commit -m \"текст\"" to "сохранить", "git push" to "отправить на GitHub"),
        "Система" to listOf("df -h" to "свободное место", "uname -m" to "процессор", "ps" to "процессы", "clear" to "очистить экран"),
    )
}
