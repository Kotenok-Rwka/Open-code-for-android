package com.pocketcode

import androidx.compose.animation.togetherWith
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Modifier
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import kotlinx.coroutines.launch
import androidx.compose.foundation.verticalScroll
import com.pocketcode.ui.Text
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.pocketcode.ui.ChatController
import com.pocketcode.ui.tourAnchor
import com.pocketcode.ui.ChatScreen
import com.pocketcode.ui.DevicesScreen
import com.pocketcode.ui.PluginsScreen
import com.pocketcode.ui.DiagScreen
import com.pocketcode.ui.PreviewScreen
import com.pocketcode.ui.FileActions
import com.pocketcode.ui.FilesScreen
import com.pocketcode.ui.CompareScreen
import com.pocketcode.ui.ProjectsScreen
import com.pocketcode.ui.SessionsScreen
import com.pocketcode.ui.SettingsScreen

private enum class Screen { CHAT, SETTINGS, SESSIONS, PROJECTS, FILES, DEVICES, PLUGINS, DIAG, COMPARE, CONTEXT, TERMINAL, GIT, OCSESSIONS, REMOTE, DIFF, SEARCH, HUB_PROJECT, HUB_AGENT, HUB_MORE, FIND, ROOT, PHONE, OCSETTINGS, MODELS, LINUX, UPDATE, BACKUP, VIDEO, LANGUAGE, SMART, LEARN }

class MainActivity : ComponentActivity() {
    private val controller: ChatController
        get() = (application as PocketCodeApp).current

    private val pocketApp: PocketCodeApp get() = application as PocketCodeApp

    private var locked by androidx.compose.runtime.mutableStateOf(false)
    private var stopAt = 0L
    private val unlockLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == RESULT_OK) locked = false
        }

    private fun lockAvailable(): Boolean {
        val km = getSystemService(android.app.KeyguardManager::class.java)
        return controller.appLock && km != null && km.isDeviceSecure
    }

    private fun askUnlock() {
        val km = getSystemService(android.app.KeyguardManager::class.java)
        val i = km?.createConfirmDeviceCredentialIntent("PocketCode", "Подтверди, что это ты")
        if (i == null) locked = false else runCatching { unlockLauncher.launch(i) }.onFailure { locked = false }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val legacyStoragePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { controller.refreshStoragePermission() }

    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            controller.importFiles(uris)
        }

    private val pickFolderToImport =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) controller.importFolder(uri)
        }

    private val pickFolderAsProject =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) controller.addExternalFolder(uri, pickFolderFresh)
            pickFolderFresh = false
        }

    /** true — папку выбрали из окна «Новый чат»: после выбора начинаем в ней новый чат. */
    private var pickFolderFresh = false

    private val exportBackup =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) controller.exportBackup(uri)
        }

    private val importBackup =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) controller.importBackup(uri)
        }

    private val exportZip =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) controller.exportZip(uri)
        }

    private val exportMd =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
            if (uri != null) controller.exportMarkdown(uri)
        }

    @androidx.compose.foundation.layout.ExperimentalLayoutApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.pocketcode.ui.I18n.init(this)
        handleIntent(intent)
        locked = lockAvailable()
        controller.resumeSyncLive()
        requestNotificationPermission()
        val actions = FileActions(
            pickFiles = { pickFiles.launch(arrayOf("*/*")) },
            pickFolderToImport = { pickFolderToImport.launch(null) },
            exportZip = { project -> exportZip.launch("$project.zip") },
            pickFolderAsProject = { pickFolderAsProject.launch(null) },
            requestStorageAccess = { requestStorageAccess() },
        )
        setContent {
            AppTheme {
                if (!com.pocketcode.ui.I18n.chosen) {
                    com.pocketcode.ui.LanguagePicker(first = true, onDone = {})
                    return@AppTheme
                }
                val vm = pocketApp.current
                if (locked) {
                    androidx.compose.runtime.LaunchedEffect(Unit) { askUnlock() }
                    androidx.compose.foundation.layout.Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    ) {
                        Text("PocketCode заблокирован", style = MaterialTheme.typography.titleLarge)
                        androidx.compose.material3.Button(onClick = { askUnlock() }, modifier = Modifier.padding(top = 16.dp)) { Text("Разблокировать") }
                    }
                    return@AppTheme
                }
                val crashFile = remember { java.io.File(filesDir, "crashed.flag") }
                var crashed by remember { mutableStateOf(crashFile.exists()) }
                if (crashed) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { crashFile.delete(); crashed = false },
                        title = { Text("Прошлый запуск завершился ошибкой") },
                        text = { Text("Можно скопировать отчёт (ключи в нём скрыты) и прислать разработчику. Он же есть в «Диагностике».") },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                val cm = getSystemService(android.content.ClipboardManager::class.java)
                                cm?.setPrimaryClip(android.content.ClipData.newPlainText("report", vm.buildReport()))
                                crashFile.delete(); crashed = false
                            }) { Text("Скопировать отчёт") }
                        },
                        dismissButton = { androidx.compose.material3.TextButton(onClick = { crashFile.delete(); crashed = false }) { Text("Закрыть") } },
                    )
                }
                com.pocketcode.ui.HostBridgeDialog(pocketApp.controller)
                if (pocketApp.controller.tourDone && pocketApp.controller.updatePrompt && !crashed) com.pocketcode.ui.UpdatePromptDialog(pocketApp.controller)
                var screen by remember { mutableStateOf(Screen.CHAT) }
                var backTo by remember { mutableStateOf(Screen.CHAT) }
                var focusKey by remember { mutableStateOf<String?>(null) }
                fun openFrom(hub: Screen, target: Screen) { focusKey = null; backTo = hub; screen = target }
                val pv = vm.previewUrl
                val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.let { com.pocketcode.ui.isWideScreen(it) }
                val noMotion = com.pocketcode.ui.LocalReduceMotion.current
                val imeDensity = androidx.compose.ui.platform.LocalDensity.current
                // Клавиатура считается открытой только если она действительно высокая (плавающие панели ввода давали ложное срабатывание)
                // Читаем высоту клавиатуры через derivedStateOf: иначе весь экран перерисовывался бы на каждом кадре её анимации
                val imeInsets = androidx.compose.foundation.layout.WindowInsets.ime
                val imeMinPx = with(imeDensity) { 160.dp.roundToPx() }
                val imeOn by remember(imeInsets, imeDensity, imeMinPx) {
                    androidx.compose.runtime.derivedStateOf { imeInsets.getBottom(imeDensity) > imeMinPx }
                }
                val section = when (screen) {
                    Screen.CHAT -> 0
                    Screen.HUB_PROJECT, Screen.PROJECTS, Screen.FILES, Screen.SEARCH, Screen.DIFF, Screen.GIT, Screen.TERMINAL, Screen.VIDEO -> 1
                    Screen.HUB_AGENT, Screen.FIND, Screen.MODELS, Screen.OCSESSIONS, Screen.OCSETTINGS, Screen.PLUGINS, Screen.CONTEXT, Screen.COMPARE, Screen.SESSIONS -> 2
                    else -> 3
                }
                androidx.compose.material3.Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        if (pv == null && !imeOn) com.pocketcode.ui.AppNavigationBar {
                            listOf(
                                Triple("Чат", com.pocketcode.ui.AppIcons.Chat, Screen.CHAT),
                                Triple("Проект", com.pocketcode.ui.AppIcons.Folder, Screen.HUB_PROJECT),
                                Triple("Агент", com.pocketcode.ui.AppIcons.Bolt, Screen.HUB_AGENT),
                                Triple("Ещё", com.pocketcode.ui.AppIcons.Grid, Screen.HUB_MORE),
                            ).forEachIndexed { i, (label, icon, target) ->
                                com.pocketcode.ui.AppNavItem(
                                    selected = section == i,
                                    modifier = Modifier.tourAnchor(when (i) { 1 -> "nav_project"; 2 -> "nav_agent"; 3 -> "nav_more"; else -> "nav_chat" }),
                                    onClick = { backTo = Screen.CHAT; screen = target },
                                    icon = icon,
                                    label = label,
                                )
                            }
                        }
                    },
                ) { pad ->
                androidx.compose.foundation.layout.Column(Modifier.padding(pad).consumeWindowInsets(pad)) {
                com.pocketcode.ui.BackgroundBanner(pocketApp.controller)
                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                if (pv != null) {
                    PreviewScreen(pv, "Превью", onBack = { vm.previewUrl = null })
                } else androidx.compose.animation.AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        if (noMotion) {
                            androidx.compose.animation.EnterTransition.None.togetherWith(androidx.compose.animation.ExitTransition.None)
                        } else {
                            (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)) +
                                androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(220)) { it / 16 })
                                .togetherWith(androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(110)))
                        }
                    },
                    label = "screen",
                ) { sc ->
                  // Планшет: экраны-списки не растягиваются на всю ширину, а стоят по центру
                  androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
                  androidx.compose.foundation.layout.Box(
                    if (wide && com.pocketcode.ui.isReadingScreen(sc.name)) Modifier.widthIn(max = com.pocketcode.ui.READING_WIDTH_DP.dp).fillMaxSize() else Modifier.fillMaxSize(),
                  ) { when (sc) {
                    Screen.HUB_PROJECT -> {
                        BackHandler { screen = Screen.CHAT }
                        com.pocketcode.ui.HubScreen(
                            "Проект", "Сейчас открыт: " + vm.currentProject,
                            listOf(
                                com.pocketcode.ui.hubHeader("Что открыто"),
                                com.pocketcode.ui.HubItem(com.pocketcode.ui.AppIcons.Folder, "Проекты", "Список проектов, создать новый или открыть папку телефона") { openFrom(Screen.HUB_PROJECT, Screen.PROJECTS) },
                                com.pocketcode.ui.HubItem(com.pocketcode.ui.AppIcons.File, "Файлы", "Смотреть и править файлы проекта") { openFrom(Screen.HUB_PROJECT, Screen.FILES) },
                                com.pocketcode.ui.hubHeader("Работа с кодом"),
                                com.pocketcode.ui.HubItem(com.pocketcode.ui.AppIcons.Search, "Поиск по проекту", "Найти текст или TODO во всех файлах") { openFrom(Screen.HUB_PROJECT, Screen.SEARCH) },
                                com.pocketcode.ui.HubItem(com.pocketcode.ui.AppIcons.Pencil, "Правки агента", "Что изменил агент, откат по блокам, сравнение со снимком", if (vm.changes.isNotEmpty()) "${vm.changes.size}" else null) { openFrom(Screen.HUB_PROJECT, Screen.DIFF) },
                                com.pocketcode.ui.HubItem(com.pocketcode.ui.AppIcons.Terminal, "Терминал и запуск", "Команды в Linux, кнопки запуска проекта") { openFrom(Screen.HUB_PROJECT, Screen.TERMINAL) },
                                com.pocketcode.ui.HubItem(com.pocketcode.ui.AppIcons.Branch, "Git и GitHub", "Коммиты, ветки, PR, проверки сборки") { openFrom(Screen.HUB_PROJECT, Screen.GIT) },
                                com.pocketcode.ui.hubHeader("Медиа"),
                                com.pocketcode.ui.HubItem(com.pocketcode.ui.AppIcons.Play, "Видео", "Монтаж: вырезать паузы одной кнопкой, без ПК", if (vm.video.busy) "идёт" else null) { openFrom(Screen.HUB_PROJECT, Screen.VIDEO) },
                            ),
                            onSearch = { openFrom(screen, Screen.FIND) },
                        )
                    }
                    Screen.HUB_AGENT -> {
                        BackHandler { screen = Screen.CHAT }
                        com.pocketcode.ui.HubScreen(
                            "Агент", vm.profileLabel + (if (vm.openCodeMode) " · режим OpenCode" else ""),
                            listOf(
                                com.pocketcode.ui.hubHeader("Кто работает"),
                                com.pocketcode.ui.HubItem(com.pocketcode.ui.AppIcons.Key, "Модель и ключи", "Провайдеры, ключи API, выбор модели, режим работы") { openFrom(Screen.HUB_AGENT, Screen.MODELS) },
                                com.pocketcode.ui.HubItem(com.pocketcode.ui.AppIcons.Chat, "Диалоги OpenCode", "Список бесед: открыть, закрепить, переименовать, удалить, начать новую") { openFrom(Screen.HUB_AGENT, Screen.OCSESSIONS) },
                                com.pocketcode.ui.HubItem(com.pocketcode.ui.AppIcons.Sliders, "Настройки OpenCode", "Аккаунты, ключ, прокси, лимиты, доступ вне проекта, статистика") { openFrom(Screen.HUB_AGENT, Screen.OCSETTINGS) },
                                com.pocketcode.ui.HubItem(com.pocketcode.ui.AppIcons.Layers, "Сравнить модели", "Один запрос сразу в нескольких моделях") { openFrom(Screen.HUB_AGENT, Screen.COMPARE) },
                                com.pocketcode.ui.hubHeader("Что он знает и умеет"),
                                com.pocketcode.ui.HubItem(com.pocketcode.ui.AppIcons.Cpu, "Что в контексте", "Что агент сейчас «помнит» и сколько это весит") { openFrom(Screen.HUB_AGENT, Screen.CONTEXT) },
                                com.pocketcode.ui.HubItem(com.pocketcode.ui.AppIcons.Wrench, "Навыки, команды, MCP", "Готовые инструкции и подключаемые инструменты") { openFrom(Screen.HUB_AGENT, Screen.PLUGINS) },
                                com.pocketcode.ui.hubHeader("Прошлое"),
                                com.pocketcode.ui.HubItem(com.pocketcode.ui.AppIcons.History, "История чатов", "Прошлые беседы, вернуться к любой") { openFrom(Screen.HUB_AGENT, Screen.SESSIONS) },
                            ),
                            onSearch = { openFrom(screen, Screen.FIND) },
                        )
                    }
                    Screen.HUB_MORE -> {
                        BackHandler { screen = Screen.CHAT }
                        com.pocketcode.ui.HubScreen(
                            "Ещё", "Система, устройства и приложение",
                            listOf(
                                com.pocketcode.ui.hubHeader("Система"),
                                com.pocketcode.ui.HubItem(com.pocketcode.ui.AppIcons.Sliders, "Настройки", "Тема и вид, безопасность, правила, общие параметры") { openFrom(Screen.HUB_MORE, Screen.SETTINGS) },
                                com.pocketcode.ui.HubItem(com.pocketcode.ui.AppIcons.Globe, "Язык интерфейса", com.pocketcode.ui.I18n.all.firstOrNull { it.code == com.pocketcode.ui.I18n.code }?.native ?: "") { openFrom(Screen.HUB_MORE, Screen.LANGUAGE) },
                                com.pocketcode.ui.HubItem(com.pocketcode.ui.AppIcons.Terminal, "Среда Linux", "Установка мини-Linux, python, git, node", if (!pocketApp.controller.linuxInstalled && pocketApp.controller.linuxSupported) "не установлен" else null) { openFrom(Screen.HUB_MORE, Screen.LINUX) },
                                com.pocketcode.ui.HubItem(com.pocketcode.ui.AppIcons.Sparkles, "Умные функции", "Авто-режим, предохранитель, корзина, автовыбор модели, карта проекта", if (pocketApp.controller.rootAuto) "авто вкл" else null) { openFrom(Screen.HUB_MORE, Screen.SMART) },
                                com.pocketcode.ui.HubItem(com.pocketcode.ui.AppIcons.Shield, "Root и Android", "Как дать агенту данные с телефона: четыре способа", if (pocketApp.controller.rootAuto) "авто-режим вкл" else if (pocketApp.controller.hostMode == "ask") "мост вкл" else null) { openFrom(Screen.HUB_MORE, Screen.ROOT) },
                                com.pocketcode.ui.HubItem(com.pocketcode.ui.AppIcons.Phone, "Управление телефоном", "Агент смотрит на экран и нажимает: служба доступности, помощник, фон", if (pocketApp.controller.phoneMode.enabled) "вкл" else null) { openFrom(Screen.HUB_MORE, Screen.PHONE) },
                                com.pocketcode.ui.HubItem(com.pocketcode.ui.AppIcons.Phone, "Устройства", "ADB, root, подключённые устройства") { openFrom(Screen.HUB_MORE, Screen.DEVICES) },
                                com.pocketcode.ui.HubItem(com.pocketcode.ui.AppIcons.Refresh, "Синхронизация и пульт", "Файлы между телефонами и задачи на другой телефон") { openFrom(Screen.HUB_MORE, Screen.REMOTE) },
                                com.pocketcode.ui.hubHeader("Приложение"),
                                com.pocketcode.ui.HubItem(com.pocketcode.ui.AppIcons.Package, "Обновления", "Версия ${pocketApp.controller.currentVersion}", if (pocketApp.controller.updateInfo != null) "новая" else null) { openFrom(Screen.HUB_MORE, Screen.UPDATE) },
                                com.pocketcode.ui.HubItem(com.pocketcode.ui.AppIcons.Archive, "Резервная копия", "Сохранить и восстановить настройки и ключи") { openFrom(Screen.HUB_MORE, Screen.BACKUP) },
                                com.pocketcode.ui.HubItem(com.pocketcode.ui.AppIcons.Activity, "Диагностика и расход", "Проверка, расход токенов, очистка места") { openFrom(Screen.HUB_MORE, Screen.DIAG) },
                                com.pocketcode.ui.hubHeader("Помощь"),
                                com.pocketcode.ui.HubItem(com.pocketcode.ui.AppIcons.Book, "Обучение", "Уроки с практикой и мини-тестами по главным функциям", "${com.pocketcode.ui.LESSONS.count { it.id in pocketApp.controller.learnDone }}/${com.pocketcode.ui.LESSONS.size}") { openFrom(Screen.HUB_MORE, Screen.LEARN) },
                                com.pocketcode.ui.HubItem(com.pocketcode.ui.AppIcons.Info, "Подсказки", "Показать обучение первого запуска ещё раз") { screen = Screen.CHAT; pocketApp.controller.restartTour() },
                                com.pocketcode.ui.HubItem(com.pocketcode.ui.AppIcons.Link, "Поддержка", "Помощь, вопросы и идеи: группа PocketCode в Telegram") {
                                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/pocket_code_github"))) }
                                },
                            ),
                            onSearch = { openFrom(screen, Screen.FIND) },
                        )
                    }
                    Screen.CHAT -> {
                        val chat: @Composable () -> Unit = {
                          androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                            if (pocketApp.tabs.size > 1) TabBar(pocketApp)
                            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                            key(vm) {
                            ChatScreen(
                                vm,
                                onOpenSettings = { screen = Screen.MODELS },
                                onOpenSessions = { screen = Screen.SESSIONS },
                                onOpenProjects = { screen = Screen.PROJECTS },
                                onOpenFiles = { screen = Screen.FILES },
                                onOpenContext = { screen = Screen.CONTEXT },
                                onOpenTerminal = { screen = Screen.TERMINAL },
                                onOpenGit = { screen = Screen.GIT },
                                onOpenOcSessions = { screen = Screen.OCSESSIONS },
                                onOpenDiff = { screen = Screen.DIFF },
                                onOpenPhone = { openFrom(Screen.CHAT, Screen.PHONE) },
                                onMenu = { screen = Screen.HUB_MORE },
                                onExport = { exportMd.launch("chat-" + vm.currentProject + ".md") },
                                onPickFolder = {
                                    pickFolderFresh = true
                                    pickFolderAsProject.launch(null)
                                },
                            )
                            }
                            }
                          }
                        }
                        if (wide) {
                            // Планшет и ландшафт: чат слева, файлы проекта справа
                            androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
                                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { chat() }
                                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                                    key(vm.projectRoot.absolutePath) {
                                        FilesScreen(
                                            vm.projectRoot,
                                            vm.currentProject,
                                            onBack = { },
                                            onStartHere = { dir -> vm.openFolderAsProject(dir) },
                                            checker = { name, text -> vm.checkText(name, text) },
                                            refreshKey = vm.items.size,
                                        )
                                    }
                                }
                            }
                        } else chat()
                    }
                    Screen.FIND -> {
                        BackHandler { screen = Screen.CHAT }
                        com.pocketcode.ui.SettingsSearchScreen(
                            onBack = { screen = Screen.CHAT },
                            onOpen = { e ->
                                focusKey = e.focus
                                backTo = Screen.FIND
                                screen = runCatching { Screen.valueOf(e.screen) }.getOrDefault(Screen.CHAT)
                            },
                        )
                    }
                    Screen.SETTINGS, Screen.MODELS -> {
                        BackHandler { screen = backTo }
                        val pv0 = pocketApp.controller
                        key(screen) {
                        SettingsScreen(
                            models = screen == Screen.MODELS,
                            focus = focusKey,
                            onSearch = { focusKey = null; openFrom(screen, Screen.FIND) },
                            vm = pv0,
                            initial = pv0.settings,
                            onSave = { s, p, a -> pv0.saveSettings(s, p, a) },
                            onResetContext = { vm.resetContext() },
                            onOpenCompare = { screen = Screen.COMPARE },
                            onBack = { screen = backTo },
                        )
                        }
                    }
                    Screen.DIFF -> {
                        BackHandler { screen = backTo }
                        com.pocketcode.ui.DiffScreen(vm, onBack = { screen = backTo })
                    }
                    Screen.SEARCH -> {
                        BackHandler { screen = backTo }
                        com.pocketcode.ui.SearchScreen(vm, onBack = { screen = backTo }, onPicked = { screen = Screen.CHAT })
                    }
                    Screen.REMOTE -> {
                        BackHandler { screen = backTo }
                        com.pocketcode.ui.RemoteScreen(vm, onBack = { screen = backTo })
                    }
                    Screen.OCSETTINGS -> {
                        BackHandler { screen = backTo }
                        com.pocketcode.ui.OcSessionsScreen(vm, onBack = { screen = backTo }, onOpened = { screen = Screen.CHAT }, settings = true)
                    }
                    Screen.OCSESSIONS -> {
                        BackHandler { screen = backTo }
                        com.pocketcode.ui.OcSessionsScreen(vm, onBack = { screen = backTo }, onOpened = { screen = Screen.CHAT })
                    }
                    Screen.TERMINAL -> {
                        BackHandler { screen = backTo }
                        com.pocketcode.ui.TerminalScreen(vm, onBack = { screen = backTo })
                    }
                    Screen.GIT -> {
                        BackHandler { screen = backTo }
                        com.pocketcode.ui.GitScreen(vm, onBack = { screen = backTo })
                    }
                    Screen.CONTEXT -> {
                        BackHandler { screen = backTo }
                        com.pocketcode.ui.ContextScreen(vm, onBack = { screen = backTo })
                    }
                    Screen.COMPARE -> {
                        BackHandler { screen = backTo }
                        CompareScreen(vm, onBack = { screen = backTo })
                    }
                    Screen.PROJECTS -> {
                        BackHandler { screen = backTo }
                        ProjectsScreen(vm, actions, onBack = { screen = backTo })
                    }
                    Screen.FILES -> FilesScreen(
                        vm.projectRoot,
                        vm.currentProject,
                        onBack = { screen = backTo },
                        onStartHere = { dir ->
                            vm.openFolderAsProject(dir)
                            screen = Screen.CHAT
                        },
                        checker = { name, text -> vm.checkText(name, text) },
                    )
                    Screen.DEVICES -> {
                        BackHandler { screen = backTo }
                        DevicesScreen(vm, onBack = { screen = backTo })
                    }
                    Screen.DIAG -> {
                        BackHandler { screen = backTo }
                        DiagScreen(vm, onBack = { screen = backTo })
                    }
                    Screen.PLUGINS -> {
                        BackHandler { screen = backTo }
                        PluginsScreen(vm, onBack = { screen = backTo })
                    }
                    Screen.SESSIONS -> {
                        BackHandler { screen = backTo }
                        SessionsScreen(vm, onBack = { screen = backTo })
                    }
                    Screen.PHONE -> {
                        BackHandler { screen = backTo }
                        com.pocketcode.ui.PhoneScreen(vm, onBack = { screen = backTo }, onToChat = { screen = Screen.CHAT })
                    }
                    Screen.ROOT -> {
                        BackHandler { screen = backTo }
                        com.pocketcode.ui.RootScreen(pocketApp.controller, onBack = { screen = backTo }, onToChat = { screen = Screen.CHAT })
                    }
                    Screen.LEARN -> {
                        BackHandler { screen = backTo }
                        com.pocketcode.ui.LearnScreen(
                            pocketApp.controller,
                            onBack = { screen = backTo },
                            onGo = { key ->
                                val t = when (key) {
                                    "models" -> Screen.MODELS
                                    "smart" -> Screen.SMART
                                    "root" -> Screen.ROOT
                                    "diff" -> Screen.DIFF
                                    "linux" -> Screen.LINUX
                                    "diag" -> Screen.DIAG
                                    else -> Screen.CHAT
                                }
                                openFrom(Screen.LEARN, t)
                            },
                            onTour = { screen = Screen.CHAT; pocketApp.controller.restartTour() },
                        )
                    }
                    Screen.SMART -> {
                        BackHandler { screen = backTo }
                        com.pocketcode.ui.SmartScreen(pocketApp.controller, onBack = { screen = backTo }, onOpenRoot = { openFrom(Screen.SMART, Screen.ROOT) })
                    }
                    Screen.VIDEO -> {
                        BackHandler { screen = backTo }
                        com.pocketcode.video.VideoScreen(vm.video, gen = vm.videoGen, onBack = { screen = backTo }, onOpenLinux = { openFrom(Screen.HUB_PROJECT, Screen.LINUX) }, onAsk = { prompt -> screen = Screen.CHAT; vm.send(prompt) })
                    }
                    Screen.LANGUAGE -> {
                        BackHandler { screen = backTo }
                        com.pocketcode.ui.LanguagePicker(first = false, onDone = { screen = backTo })
                    }
                    Screen.LINUX -> {
                        BackHandler { screen = backTo }
                        com.pocketcode.ui.LinuxScreen(pocketApp.controller, onBack = { screen = backTo })
                    }
                    Screen.UPDATE -> {
                        BackHandler { screen = backTo }
                        com.pocketcode.ui.UpdateScreen(pocketApp.controller, onBack = { screen = backTo })
                    }
                    Screen.BACKUP -> {
                        BackHandler { screen = backTo }
                        com.pocketcode.ui.BackupScreen(
                            pocketApp.controller,
                            onBack = { screen = backTo },
                            onExport = { exportBackup.launch("pocketcode-backup.json") },
                            onImport = { importBackup.launch(arrayOf("*/*")) },
                        )
                    }
                }
                }
                }
                }
                }
                }
                }
                if (!pocketApp.controller.tourDone && !crashed) com.pocketcode.ui.TourOverlay(onDone = { pocketApp.controller.finishTour() }, onOpenLearn = { pocketApp.controller.finishTour(); screen = Screen.LEARN; backTo = Screen.HUB_MORE })
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val type = intent.type.orEmpty()
                if (type.startsWith("image/")) {
                    @Suppress("DEPRECATION")
                    (intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))?.let { controller.attachImage(it) }
                }
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { controller.incomingText = it }
            }
            "com.pocketcode.NEW_TASK" -> if (!controller.running) {
                controller.newChat()
            }
        }
        intent.action = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        pocketApp.tabs.forEach { it.appVisible = true }
        if (stopAt > 0 && System.currentTimeMillis() - stopAt > 60_000 && lockAvailable()) locked = true
    }

    override fun onResume() {
        super.onResume()
        // Пользователь мог выдать доступ к файлам в системных настройках
        controller.refreshStoragePermission()
    }

    override fun onStop() {
        pocketApp.tabs.forEach { it.appVisible = false }
        stopAt = System.currentTimeMillis()
        super.onStop()
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            val uri = Uri.parse("package:$packageName")
            try {
                startActivity(Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri))
            } catch (e: Exception) {
                startActivity(Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            legacyStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = when ((context.applicationContext as PocketCodeApp).controller.themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val ctl = (context.applicationContext as PocketCodeApp).controller
    val accent = com.pocketcode.ui.accentById(ctl.palette)
    val style = com.pocketcode.ui.uiStyleById(ctl.uiStyle)
    val systemNoAnim = remember(context) {
        runCatching { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    val reduceMotion = ctl.reduceMotion || systemNoAnim
    val ios = style == com.pocketcode.ui.UiStyle.IOS
    val scheme = when {
        accent == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val dyn = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            if (ios) com.pocketcode.ui.buildIosScheme(dark, dark && ctl.amoled, com.pocketcode.ui.Accent("system", "Из обоев", dyn.primary, dyn.primary))
            else if (dark && ctl.amoled) com.pocketcode.ui.amoledize(dyn) else dyn
        }
        ios -> com.pocketcode.ui.buildIosScheme(dark, dark && ctl.amoled, accent ?: com.pocketcode.ui.ACCENTS.first())
        else -> com.pocketcode.ui.buildScheme(dark, dark && ctl.amoled, accent ?: com.pocketcode.ui.ACCENTS.first())
    }
    // Цвета системных панелей и подложки окна берём из темы приложения: без серой полосы сверху и белой снизу
    androidx.compose.runtime.SideEffect {
        val w = (context as? android.app.Activity)?.window
        if (w != null) {
            val bg = scheme.background.toArgb()
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bg))
            w.statusBarColor = bg
            w.navigationBarColor = bg
            val c = androidx.core.view.WindowCompat.getInsetsController(w, w.decorView)
            c.isAppearanceLightStatusBars = !dark
            c.isAppearanceLightNavigationBars = !dark
        }
    }
    val st = (context.applicationContext as PocketCodeApp).controller.settings
    val d = androidx.compose.ui.platform.LocalDensity.current
    val dens = androidx.compose.ui.unit.Density(
        d.density * (if (st.compactUi) 0.9f else 1f),
        d.fontScale * st.fontScale,
    )
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides dens,
        androidx.compose.ui.platform.LocalLayoutDirection provides if (com.pocketcode.ui.I18n.isRtl) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr,
        com.pocketcode.ui.LocalUiStyle provides style,
        com.pocketcode.ui.LocalReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = com.pocketcode.ui.appTypography(style),
            shapes = com.pocketcode.ui.appShapes(style),
            content = content,
        )
    }
}

@androidx.compose.runtime.Composable
private fun TabBar(app: PocketCodeApp) {
    androidx.compose.foundation.layout.Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .horizontalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        app.tabs.forEachIndexed { i, t ->
            androidx.compose.material3.FilterChip(
                selected = i == app.activeTab,
                onClick = { app.activeTab = i },
                label = { Text((if (t.running) "● " else "") + t.tabTitle + " · " + t.profileLabel.take(14), maxLines = 1) },
                trailingIcon = if (i > 0) ({
                    androidx.compose.material3.Text("×", modifier = Modifier.clickable { app.closeTab(i) }.padding(horizontal = 4.dp))
                }) else null,
            )
        }
        androidx.compose.material3.TextButton(onClick = { app.addTab() }) { Text("+ чат") }
    }
}
