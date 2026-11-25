package com.pocketcode.ui

import kotlinx.serialization.Serializable

/** Описание инструмента для экрана «Инструменты модели». */
@Serializable
data class ToolInfo(
    val id: String,
    val name: String,
    val title: String,
    val description: String,
    val group: String,
    val enabled: Boolean = true,
    val requiresLinux: Boolean = false,
)

@Serializable
data class ToolsConfig(
    val disabled: Set<String> = emptySet(),
    val reasoning: Boolean = true,
    val reasoningDepth: Int = 1,
    /** После правок агент сам запускает диагностику и тесты (идея AndroidHarness/Agora — своим кодом). */
    val selfCheck: Boolean = true,
    val reasoningVendor: String = "auto",
    /**
     * Режим под силу модели: AUTO — обычный промпт; SCAFFOLD — жёсткие строительные леса для слабых
     * моделей (план → один файл → проверка → ревью по чек-листу); FOCUSED — дисциплина контекста
     * для моделей, слабых в больших проектах (точечные чтения, карта проекта, субагенты).
     */
    val agentMode: String = "auto",
) {
    val scaffoldMode: Boolean get() = agentMode == "scaffold"
    val focusedMode: Boolean get() = agentMode == "focused"
}

object ToolRegistry {
    /** Статический каталог инструментов, которые агент может получить. */
    val all: List<ToolInfo> = listOf(
        ToolInfo("read_file", "read_file", "Чтение файла", "Читает текстовый файл и возвращает строки с номерами.", "Файлы"),
        ToolInfo("write_file", "write_file", "Запись файла", "Создаёт или перезаписывает файл.", "Файлы"),
        ToolInfo("edit_file", "edit_file", "Правка файла", "Заменяет фрагмент; сохраняет историю отката.", "Файлы"),
        ToolInfo("apply_patch", "apply_patch", "Безопасная правка", "Проверяет уникальность фрагмента и сохраняет версию файла.", "Файлы"),
        ToolInfo("list_dir", "list_dir", "Список файлов", "Показывает содержимое папки.", "Файлы"),
        ToolInfo("web_evidence", "web_evidence", "Проверка фактов", "Ищет подтверждения с источниками и открывает страницы.", "Сеть"),
        ToolInfo("recall", "recall", "Память", "Ищет и удаляет заметки проекта.", "Память"),
        ToolInfo("project_recall", "project_recall", "Поиск по проекту", "Возвращает фрагменты кода и документов по запросу.", "Память"),
        ToolInfo("request_log", "request_log", "Журнал", "Показывает последние события и ошибки без секретов.", "Диагностика"),
        ToolInfo("grep", "grep", "Поиск по коду", "Ищет текст/регулярное выражение по файлам.", "Файлы"),
        ToolInfo("code_search", "code_search", "Семантический поиск", "Ищет по смыслу в коде проекта.", "Файлы"),
        ToolInfo("bash", "bash", "Терминал", "Выполняет команду в Android sh или Linux bash.", "Команды"),
        ToolInfo("python", "python", "Python", "Запускает Python-код в песочнице.", "Команды"),
        ToolInfo("git", "git", "Git", "Статус, коммиты, ветки, diff.", "Команды"),
        ToolInfo("diagnostics", "diagnostics", "Диагностика кода", "Проверяет синтаксис и ошибки после правок.", "Команды"),
        ToolInfo("test", "test", "Тесты", "Запускает тесты проекта.", "Команды"),
        ToolInfo("code_review", "code_review", "Аудит кода", "Ищет секреты, инъекции, отключённый TLS, заглушки и проглоченные ошибки в правках.", "Команды"),
        ToolInfo("notes", "notes", "Заметки проекта", "Сохраняет и читает заметки агента.", "Команды"),
        ToolInfo("web_search", "web_search", "Поиск в интернете", "Ищет актуальную документацию и ответы.", "Сеть"),
        ToolInfo("web_fetch", "web_fetch", "Открыть страницу", "Читает страницу по URL.", "Сеть"),
        ToolInfo("github", "github", "GitHub", "Читает/создаёт issue, PR, файлы репозитория.", "Сеть"),
        ToolInfo("ssh", "ssh", "SSH", "Управляет удалённым сервером по ключу или паролю.", "Сеть"),
        ToolInfo("present_plan", "present_plan", "План", "Показывает план и ждёт одобрения.", "Агент"),
        ToolInfo("todo", "todo", "Список задач", "Ведёт checklist в чате.", "Агент"),
        ToolInfo("skill", "skill", "Навыки", "Загружает навыки проекта по описанию.", "Агент"),
        ToolInfo("subagent", "subagent", "Субагент", "Порождает изолированного помощника для подзадачи.", "Агент"),
        ToolInfo("cartoon_make", "cartoon_make", "Мультики", "Создаёт вертикальный мультик с озвучкой.", "Медиа"),
        ToolInfo("video", "video", "Видео", "Монтаж, субтитры, паузы, клипы.", "Медиа", requiresLinux = true),
        ToolInfo("adb", "adb", "ADB", "Управляет подключёнными Android-устройствами.", "Устройство", requiresLinux = true),
        ToolInfo("codegraph", "codegraph", "CodeGraph", "Индекс символов и связей кода.", "Устройство", requiresLinux = true),
        ToolInfo("phone_mode", "phone_mode", "Управление телефоном", "Инструменты accessibility-помощника.", "Устройство"),
    )

    fun isEnabled(id: String, config: ToolsConfig): Boolean = id !in config.disabled
    fun groups(): List<String> = all.map { it.group }.distinct()
}
