package com.pocketcode.ui

import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import com.pocketcode.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketcode.session.UiItem

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    vm: ChatController,
    onOpenSettings: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenFiles: () -> Unit,
    onPickFolder: () -> Unit,
    onOpenContext: () -> Unit = {},
    onExport: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    onOpenGit: () -> Unit = {},
    onMenu: () -> Unit = {},
    onOpenOcSessions: () -> Unit = {},
    onOpenDiff: () -> Unit = {},
) {
    var showNewChat by remember { mutableStateOf(false) }
    val imeOpen = androidx.compose.foundation.layout.WindowInsets.isImeVisible
    var moreMenu by remember { mutableStateOf(false) }
    var dlg by remember { mutableStateOf("") }
    var pickFrom by remember { mutableStateOf(0) }
    var rewindAt by remember { mutableStateOf(-1) }
    var mentionFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var notesDraft by remember { mutableStateOf("") }
    var searchQ by remember { mutableStateOf("") }
    var newTpl by remember { mutableStateOf("") }
    var modelDraft by remember { mutableStateOf("") }
    var newProjectName by remember { mutableStateOf("") }
    var profileMenu by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf(vm.draft) }
    val tick = rememberTick()
    val atTok = Regex("(?:^|\\s)@([\\w./\\-]*)$").find(input)
    var showChanges by remember { mutableStateOf(false) }
    var showTodos by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var editIndex by remember { mutableStateOf(-1) }
    var editText by remember { mutableStateOf("") }
    val pickImage = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) vm.attachImage(uri) }
    val listState = rememberLazyListState()
    // Список сообщений меняется на каждый токен стрима. Читаем из него только нужные значения через derivedStateOf,
    // иначе весь экран (панель, чипы, поле ввода) перерисовывался бы на каждый токен.
    val hasItems by remember(vm) { androidx.compose.runtime.derivedStateOf { vm.items.isNotEmpty() } }
    val lastItemIndex by remember(vm) { androidx.compose.runtime.derivedStateOf { vm.items.lastIndex } }
    val askedQuestion by remember(vm) {
        androidx.compose.runtime.derivedStateOf {
            !vm.running && vm.items.lastOrNull().let { it is UiItem.Assistant && isQuestionReply(it.text) }
        }
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    var adviceText by remember { mutableStateOf("") }
    var tplFill by remember { mutableStateOf<String?>(null) }
    var fillVals by remember { mutableStateOf(mapOf<String, String>()) }
    LaunchedEffect(input) { kotlinx.coroutines.delay(400); vm.saveDraft(input) }
    val voice = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { res ->
        val spoken = res.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) input = if (input.isBlank()) spoken else input.trimEnd() + " " + spoken
    }
    LaunchedEffect(vm.incomingText) {
        vm.incomingText?.let {
            input = if (input.isBlank()) it else input.trimEnd() + "\n" + it
            vm.incomingText = null
        }
    }

    // Автопрокрутка вниз только если пользователь и так внизу; читая историю выше, он остаётся на месте
    var stick by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (inProgress, canForward) -> if (inProgress || !canForward) stick = !canForward }
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Очень длинный последний элемент выше одного прыжка: докручиваем кусками, пока низ не достигнут
    suspend fun toBottom() {
        if (vm.items.isEmpty()) return
        val last = vm.items.lastIndex
        // Если последний элемент уже на экране, к его началу прыгать не нужно: хватает прокрутки вниз (так дешевле на каждом токене)
        if (shouldJumpToItem(listState.layoutInfo.visibleItemsInfo.map { it.index }, last)) listState.scrollToItem(last)
        var n = 0
        while (listState.canScrollForward && n < 200) {
            listState.scrollBy(20000f)
            n++
        }
    }
    // Ключи раньше читались прямо в теле функции, и экран перерисовывался на каждый токен. В snapshotFlow чтение идёт вне композиции.
    LaunchedEffect(listState, vm) {
        androidx.compose.runtime.snapshotFlow { vm.items.size to vm.items.lastOrNull() }
            .collectLatest {
                if (vm.items.isNotEmpty() && stick) {
                    toBottom()
                }
            }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = {
                    TextButton(onClick = onOpenProjects, modifier = Modifier.tourAnchor("project")) {
                        Icon(AppIcons.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(vm.currentProject, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    IconButton(onClick = { showNewChat = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "Новый чат")
                    }
                    Box {
                        TextButton(onClick = { moreMenu = true }) { Text("⋮") }
                        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                            DropdownMenuItem(text = { Text("Новая вкладка чата") }, onClick = { moreMenu = false; (ctx.applicationContext as com.pocketcode.PocketCodeApp).addTab() })
                            DropdownMenuItem(text = { Text("Терминал") }, onClick = { moreMenu = false; onOpenTerminal() })
                            DropdownMenuItem(text = { Text("Git") }, onClick = { moreMenu = false; onOpenGit() })
                            DropdownMenuItem(text = { Text("Что в контексте") }, onClick = { moreMenu = false; onOpenContext() })
                            DropdownMenuItem(text = { Text("Экспорт чата в Markdown") }, onClick = { moreMenu = false; onExport() })
                            if (vm.openCodeMode) DropdownMenuItem(text = { Text("Сбросить сессию (оставить последнее)") }, onClick = { moreMenu = false; vm.resetSessionAndChat(true) })
                            if (vm.openCodeMode) DropdownMenuItem(text = { Text("Сбросить, выбрав с какого сообщения") }, onClick = { moreMenu = false; pickFrom = vm.items.indexOfLast { it is UiItem.User }.coerceAtLeast(0); dlg = "resetpick" })
                            if (vm.openCodeMode) DropdownMenuItem(text = { Text("Сбросить сессию и чат полностью") }, onClick = { moreMenu = false; vm.resetSessionAndChat(false) })
                            if (vm.openCodeMode) DropdownMenuItem(text = { Text("Сжать беседу") }, onClick = { moreMenu = false; vm.compactOcSession() })
                            DropdownMenuItem(text = { Text("Отмеченные ответы (${vm.stars.size})") }, onClick = { moreMenu = false; dlg = "stars" })
                            DropdownMenuItem(text = { Text("Отправить позже") }, onClick = { moreMenu = false; dlg = "later" })
                            DropdownMenuItem(text = { Text("Поиск по чату") }, onClick = { moreMenu = false; searchQ = ""; dlg = "search" })
                            DropdownMenuItem(text = { Text("Заметки проекта") }, onClick = { moreMenu = false; notesDraft = vm.notesText(); dlg = "notes" })
                            DropdownMenuItem(text = { Text("Мои шаблоны") }, onClick = { moreMenu = false; newTpl = ""; dlg = "tpl" })
                            DropdownMenuItem(text = { Text("Сменить модель") }, onClick = { moreMenu = false; modelDraft = vm.settings.model; if (vm.openCodeMode) vm.loadOcModels(); dlg = "model" })
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (vm.rootAuto) {
                androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (vm.rootReviewMode == "ai") "Авто-режим: файлы проверяет нейросеть" else "Авто-режим: файлы одобряешь ты",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        TextButton(onClick = { vm.setRootAutoValue(false) }) { Text("Выключить") }
                    }
                }
            }
            RateStatusLine()
            if (vm.contextUsed > 0) {
                val limit = vm.effectiveContext.coerceAtLeast(1)
                val frac = (vm.contextUsed.toFloat() / limit).coerceIn(0f, 1f)
                fun k(n: Int) = if (n >= 1000) "${n / 1000}к" else "$n"
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenContext() }.padding(horizontal = 16.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LinearProgressIndicator(
                        progress = { frac },
                        modifier = Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = if (frac > 0.85f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "  ${k(vm.contextUsed)}/${k(limit)}" + (if (vm.settings.ecoMode) " · эко" else ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (!hasItems) {
                    EmptyHint(vm.workspacePath, welcome = if (!vm.welcomeSeen || vm.needsFreeStart) ({ Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { if (vm.needsFreeStart) FreeStartCard(vm, onOpenSettings); if (!vm.welcomeSeen) WelcomeCard(vm) { runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/pocket_code_github")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } } } }) else null) { input = it }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(vm.items, key = { i, _ -> i }, contentType = { _, item -> item::class }) { i, item ->
                            Column {
                                ItemView(item, live = vm.running && i == lastItemIndex)
                                if (item is UiItem.Assistant && !item.text.startsWith("⏳") && !(vm.running && i == lastItemIndex)) {
                                    Row {
                                        val star = vm.isStarred(item.text)
                                        TextButton(onClick = { vm.toggleStar(item.text) }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) { Text(if (star) "★" else "☆", style = MaterialTheme.typography.labelMedium) }
                                        TextButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(item.text)) }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) { Text("Копировать", style = MaterialTheme.typography.labelSmall) }
                                        TextButton(onClick = {
                                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, item.text) }
                                            ctx.startActivity(android.content.Intent.createChooser(send, "Поделиться").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                                        }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) { Text("Поделиться", style = MaterialTheme.typography.labelSmall) }
                                    }
                                }
                                if (item is UiItem.Error && !vm.running) {
                                    TextButton(onClick = { adviceText = item.text; dlg = "advice" }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                                        Text("Что делать?", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                                if (item is UiItem.User && !vm.running) {
                                    TextButton(onClick = { rewindAt = i }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                                        Text("↩ вернуться к этому месту", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                    if (!stick) {
                        androidx.compose.material3.SmallFloatingActionButton(
                            onClick = {
                                stick = true
                                scope.launch { toBottom() }
                            },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                        ) { Text("↓") }
                    }
                }
            }
            if (vm.queue.isNotEmpty() || (vm.running && input.isNotBlank() && !imeOpen)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (vm.queue.isNotEmpty()) "В очереди задач: ${vm.queue.size}" else "Отправка ставит в очередь. «Уточнить сейчас» передаст агенту на следующем шаге",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (vm.queue.isNotEmpty()) {
                        if (!vm.running) TextButton(onClick = { vm.runQueue() }) { Text("Запустить") }
                        TextButton(onClick = { showQueue = true }) { Text("Очередь") }
                    }
                    if (vm.running && input.isNotBlank()) {
                        TextButton(onClick = {
                            vm.sendWhileRunning(input)
                            input = ""
                        }) { Text("Уточнить сейчас") }
                    }
                }
            }
            if (showTodos && vm.todos.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    vm.todos.forEach { t ->
                        val mark = when {
                            t.startsWith("[x]", true) -> "☑ "
                            t.startsWith("[~]") -> "▶ "
                            else -> "☐ "
                        }
                        Text(mark + t.removePrefix("[x]").removePrefix("[X]").removePrefix("[~]").removePrefix("[ ]").trim(), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (showChanges) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showChanges = false },
                    title = { Text("Правки файлов (${vm.changes.size})") },
                    text = {
                        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            vm.changes.withIndex().reversed().forEach { (i, ch) ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(ch.path, style = MaterialTheme.typography.bodyMedium)
                                        Text(if (ch.before == null) "создан" else "изменён", style = MaterialTheme.typography.labelSmall)
                                    }
                                    TextButton(onClick = { vm.revertChange(i) }) { Text("Откатить") }
                                }
                            }
                            Text("Откат возвращает файл к состоянию до этой правки. Правки через bash здесь не видны, их откатывает снимок задачи.", style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    confirmButton = { TextButton(onClick = { showChanges = false }) { Text("Закрыть") } },
                    dismissButton = { TextButton(onClick = { showChanges = false; onOpenDiff() }) { Text("Разобрать по блокам") } },
                )
            }
            androidx.compose.runtime.LaunchedEffect(atTok != null, vm.currentProject) { if (atTok != null) mentionFiles = vm.projectFiles() }
            if (atTok != null) {
                val q = atTok.groupValues[1]
                val sugg = mentionFiles.filter { it.contains(q, ignoreCase = true) }.take(10)
                if (sugg.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sugg.forEach { f ->
                        androidx.compose.material3.AssistChip(border = null, colors = chipColors(), onClick = { input = input.removeSuffix("@$q") + "@$f " }, label = { Text(f.takeLast(34)) })
                    }
                }
            }
            if (askedQuestion && !imeOpen) {
                Row(Modifier.padding(horizontal = 8.dp)) {
                    listOf("Да", "Нет", "Продолжай").forEach { a ->
                        androidx.compose.material3.AssistChip(border = null, colors = chipColors(), onClick = { vm.send(a) }, label = { Text(a) }, modifier = Modifier.padding(end = 6.dp))
                    }
                }
            }
            if (!vm.running && vm.lastRunFailed && vm.items.any { it is UiItem.User }) {
                TextButton(onClick = { vm.retryLast() }, modifier = Modifier.padding(horizontal = 4.dp)) { Text("↻ Повторить последнее сообщение") }
            }
            if (vm.running) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ThinkingDots(Modifier.padding(end = 8.dp))
                    Text("Агент работает…", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { confirmStop = true }) { Text("Остановить") }
                }
            }
            if (confirmStop) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { confirmStop = false },
                    title = { Text("Остановить агента?") },
                    text = { Text("Текущая задача прервётся. Изменения, которые он уже внёс, останутся (откатить можно в «Проекты» → «Откат»). Очередь сохранится.") },
                    confirmButton = { TextButton(onClick = { confirmStop = false; vm.stop() }) { Text("Остановить") } },
                    dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Продолжить работу") } },
                )
            }
            if (showQueue) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showQueue = false; editIndex = -1 },
                    title = { Text("Очередь задач (${vm.queue.size})") },
                    text = {
                        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (vm.queue.isEmpty()) Text("Пусто.")
                            vm.queue.forEachIndexed { i, t ->
                                Column {
                                    if (editIndex == i) {
                                        OutlinedTextField(value = editText, onValueChange = { editText = it }, modifier = Modifier.fillMaxWidth())
                                        Row {
                                            TextButton(onClick = { vm.editQueue(i, editText); editIndex = -1 }) { Text("Сохранить") }
                                            TextButton(onClick = { editIndex = -1 }) { Text("Отмена") }
                                        }
                                    } else {
                                        Text("${i + 1}. ${t.take(300)}", style = MaterialTheme.typography.bodyMedium)
                                        Row {
                                            TextButton(onClick = { vm.moveInQueue(i, -1) }, enabled = i > 0) { Text("↑") }
                                            TextButton(onClick = { vm.moveInQueue(i, 1) }, enabled = i < vm.queue.lastIndex) { Text("↓") }
                                            TextButton(onClick = { editIndex = i; editText = t }) { Text("Правка") }
                                            TextButton(onClick = { vm.removeFromQueue(i) }) { Text("Убрать") }
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { showQueue = false; editIndex = -1 }) { Text("Закрыть") } },
                    dismissButton = if (vm.queue.isNotEmpty()) {
                        { TextButton(onClick = { vm.clearQueue() }) { Text("Очистить всё") } }
                    } else null,
                )
            }
            if (vm.pendingImages.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Картинок приложено: ${vm.pendingImages.size}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { vm.removeImage(vm.pendingImages.lastIndex) }) { Text("Убрать последнюю") }
                }
            }
            var modeMenu by remember { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().tourAnchor("quick").horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (vm.running) androidx.compose.material3.AssistChip(border = null, colors = chipColors(), onClick = { vm.stop() }, label = { Text("Остановить") })
                Box {
                    androidx.compose.material3.AssistChip(border = null, colors = chipColors(), 
                        onClick = { modeMenu = true },
                        label = { Text((if (vm.openCodeMode) "OpenCode" else if (vm.planMode) "План" else "Обычный") + " ▾") },
                    )
                    DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                        listOf("Обычный", "OpenCode", "План").forEachIndexed { i, label ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { vm.openCodeMode = i == 1; vm.planMode = i == 2; modeMenu = false })
                        }
                    }
                }
                if (vm.openCodeMode) {
                    androidx.compose.material3.AssistChip(border = null, colors = chipColors(), onClick = { modelDraft = vm.ocModel; vm.loadOcModels(); dlg = "ocmodel" }, label = { Text(vm.ocModel.ifBlank { "по умолчанию" }.take(22), maxLines = 1) }, leadingIcon = { Icon(AppIcons.Cpu, null, Modifier.size(16.dp)) })
                    androidx.compose.material3.AssistChip(border = null, colors = chipColors(), onClick = onOpenOcSessions, label = { Text(if (vm.ocSessionId != null) vm.ocSessionTitle.take(16) else "Диалоги", maxLines = 1) }, leadingIcon = { Icon(AppIcons.History, null, Modifier.size(16.dp)) })
                    if (vm.ocCtxTokens > 60_000 && !vm.running) androidx.compose.material3.AssistChip(border = null, colors = chipColors(), onClick = { vm.compactOcSession() }, label = { Text("Сжать ≈${vm.ocCtxTokens / 1000}к") })
                } else {
                    val active = vm.profiles.firstOrNull { it.id == vm.activeProfileId }
                    Box {
                        androidx.compose.material3.AssistChip(border = null, colors = chipColors(), 
                            onClick = { if (vm.profiles.size > 1) profileMenu = true else { modelDraft = vm.settings.model; dlg = "model" } },
                            enabled = !vm.running,
                            label = { Text("${active?.label ?: "?"} · ${active?.model.orEmpty().take(18)}", maxLines = 1) },
                            leadingIcon = { Icon(AppIcons.Key, null, Modifier.size(16.dp)) },
                        )
                        DropdownMenu(expanded = profileMenu, onDismissRequest = { profileMenu = false }) {
                            vm.profiles.forEach { p ->
                                DropdownMenuItem(text = { Text(p.label + " · " + p.model) }, onClick = { vm.switchProfile(p.id); profileMenu = false })
                            }
                        }
                    }
                }
                if (!vm.openCodeMode) androidx.compose.material3.AssistChip(border = null, colors = chipColors(), onClick = onOpenSessions, label = { Text("История") }, leadingIcon = { Icon(AppIcons.History, null, Modifier.size(16.dp)) })
                androidx.compose.material3.AssistChip(border = null, colors = chipColors(), onClick = onOpenFiles, label = { Text("Файлы") }, leadingIcon = { Icon(AppIcons.Folder, null, Modifier.size(16.dp)) })
                androidx.compose.material3.AssistChip(border = null, colors = chipColors(), onClick = onOpenTerminal, label = { Text("Терминал") }, leadingIcon = { Icon(AppIcons.Terminal, null, Modifier.size(16.dp)) })
                if (vm.changes.isNotEmpty()) androidx.compose.material3.AssistChip(border = null, colors = chipColors(), onClick = onOpenDiff, label = { Text("Правки (${vm.changes.size})") })
                if (vm.todos.isNotEmpty()) {
                    val done = vm.todos.count { it.startsWith("[x]", true) }
                    androidx.compose.material3.AssistChip(border = null, colors = chipColors(), onClick = { showTodos = !showTodos }, label = { Text("Задачи $done/${vm.todos.size}") })
                }
                vm.lastPreview?.let { pv -> androidx.compose.material3.AssistChip(border = null, colors = chipColors(), onClick = { vm.previewUrl = pv }, label = { Text("Превью") }) }
                androidx.compose.material3.AssistChip(border = null, colors = chipColors(), onClick = { showNewChat = true }, label = { Text("Новый чат") }, leadingIcon = { Icon(AppIcons.Plus, null, Modifier.size(16.dp)) })
            }
            androidx.compose.material3.Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(28.dp),
                shadowElevation = 0.dp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).tourAnchor("input"),
            ) {
                Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.Bottom) {
                    Column {
                        IconButton(onClick = { pickImage.launch("image/*") }, enabled = !vm.running) { Icon(AppIcons.Image, contentDescription = "Прикрепить картинку", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    androidx.compose.material3.TextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f).heightIn(min = if (vm.bigInput) 72.dp else 0.dp),
                        textStyle = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = if (vm.bigInput) 18.sp else 16.sp),
                        placeholder = { Text("Опиши задачу…") },
                        maxLines = 6,
                        colors = androidx.compose.material3.TextFieldDefaults.colors(
                            focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        ),
                    )
                    if (vm.running && input.isBlank()) {
                        androidx.compose.material3.FilledIconButton(onClick = { tick(); confirmStop = true }) {
                            Icon(AppIcons.Stop, contentDescription = "Остановить")
                        }
                    } else if (input.isBlank() && vm.pendingImages.isEmpty()) {
                        IconButton(onClick = {
                            val i = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Говори задачу")
                            }
                            try { voice.launch(i) } catch (e: Exception) {
                                android.widget.Toast.makeText(ctx, "На телефоне нет сервиса распознавания речи", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }) { Icon(AppIcons.Mic, contentDescription = "Голосовой ввод", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    } else {
                        androidx.compose.material3.FilledIconButton(
                            onClick = {
                                tick()
                                if (vm.running) vm.enqueue(input) else vm.send(input)
                                input = ""
                            },
                            enabled = input.isNotBlank() || (!vm.running && vm.pendingImages.isNotEmpty()),
                        ) {
                            Icon(AppIcons.ArrowUp, contentDescription = if (vm.running) "В очередь" else "Отправить")
                        }
                    }
                }
            }
        }
    }

    if (showNewChat) {
        AlertDialog(
            onDismissRequest = { showNewChat = false },
            title = { Text("Новый чат: где работать?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    vm.projects.forEach { name ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (name == vm.currentProject) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                            ),
                            modifier = Modifier.fillMaxWidth().clickable {
                                vm.newChatIn(name)
                                showNewChat = false
                            },
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text("📁 " + name + if (name == vm.currentProject) "  (текущий)" else "")
                                Text(
                                    vm.projectPath(name),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = newProjectName,
                        onValueChange = { newProjectName = it },
                        label = { Text("Или новая папка-проект") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    TextButton(
                        enabled = newProjectName.isNotBlank(),
                        onClick = {
                            vm.createProject(newProjectName)
                            if (vm.projectMessage == null) {
                                vm.newChat()
                                newProjectName = ""
                                showNewChat = false
                            }
                        },
                    ) { Text("Создать и начать") }
                    vm.projectMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    TextButton(
                        onClick = {
                            showNewChat = false
                            onPickFolder()
                        },
                    ) { Text("Выбрать папку на телефоне…") }
                    if (!vm.storageGranted) {
                        Text(
                            "Для папок телефона нужен доступ ко всем файлам: Проекты и файлы → «Выдать доступ к файлам».",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showNewChat = false }) { Text("Отмена") } },
        )
    }

    if (dlg == "later") {
        fun at(hour: Int): Long {
            val c = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, hour); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0) }
            if (c.timeInMillis < System.currentTimeMillis() + 60_000) c.add(java.util.Calendar.DAY_OF_YEAR, 1)
            return c.timeInMillis
        }
        val now = System.currentTimeMillis()
        val fmtT = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())
        val options = listOf(
            "Через 1 час" to now + 3_600_000L, "Через 3 часа" to now + 3 * 3_600_000L,
            "В 03:00" to at(3), "В 06:00" to at(6), "В 09:00" to at(9),
        ) + (vm.ocBestWindowStart()?.let { listOf("В лучшее окно (%02d:00, по твоей статистике)".format(it) to at(it)) } ?: emptyList())
        AlertDialog(
            onDismissRequest = { dlg = "" },
            title = { Text("Отправить позже") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(if (input.isBlank()) "Сначала напиши задачу в поле ввода, потом выбери время." else "Задача: " + input.take(80), style = MaterialTheme.typography.bodySmall)
                    Text("Запустится в выбранное время, даже если приложение закрыто, без подтверждений (опасные действия отклоняются). По окончании придёт уведомление. Подходящее время смотри в «Диалоги OpenCode» → «Когда лучше работать».", style = MaterialTheme.typography.labelSmall)
                    options.forEach { (label, t) ->
                        TextButton(onClick = { vm.scheduleDelayed(input, t); input = ""; dlg = "" }, enabled = input.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                            Text("$label (${fmtT.format(java.util.Date(t))})", modifier = Modifier.fillMaxWidth())
                        }
                    }
                    if (vm.delayedTasks.isNotEmpty()) {
                        Text("Ждут запуска:", style = MaterialTheme.typography.titleSmall)
                        vm.delayedTasks.forEach { d ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(fmtT.format(java.util.Date(d.at)) + " · " + d.project + " · " + d.text.take(40), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { vm.cancelDelayed(d.id) }) { Text("✕") }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dlg = "" }) { Text("Закрыть") } },
        )
    }
    if (vm.ocPending.isNotEmpty()) {
        val req = vm.ocPending
        val summary = "OpenCode (в Linux на телефоне, без root) просит доступ вне папки проекта:\n" +
            req.joinToString("\n") { "• ${it.kind}: ${it.target}" } +
            (if (req.first().command.isNotBlank()) "\nКоманда: " + req.first().command else "") +
            "\nБезопасно ли разрешить?"
        AlertDialog(
            onDismissRequest = { },
            title = { Text("OpenCode просит доступ") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Агент приостановлен и ждёт тебя: он хочет выйти за пределы папки проекта. Решай без спешки, окно не закроется само, чат не завершится.", style = MaterialTheme.typography.bodySmall)
                    req.forEach { Text("• ${it.target}", fontFamily = FontFamily.Monospace) }
                    if (req.first().command.isNotBlank()) {
                        Text("Команда:", style = MaterialTheme.typography.labelMedium)
                        Text(req.first().command.take(800), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        dangerNote(req.first().command)?.let { Text("⚠ $it", color = MaterialTheme.colorScheme.error) }
                    }
                    Text(
                        "Linux в приложении изолирован: даже с разрешением он видит только свою файловую систему, папку проекта и /proc, но не Android, не чужие приложения и не твои фото. Чтение /proc безобидно, запись или удаление в /etc, /bin, /usr может сломать Linux.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(summary)) }) { Text("Скопировать описание, чтобы спросить нейросеть") }
                    TextButton(onClick = { vm.ocPermAllow(all = true) }) { Text("Разрешить всё вне проекта до конца беседы") }
                }
            },
            confirmButton = { TextButton(onClick = { vm.ocPermAllow() }) { Text("Разрешить и продолжить") } },
            dismissButton = { TextButton(onClick = { vm.ocPermDeny() }) { Text("Отклонить") } },
        )
    }
    if (dlg == "advice") {
        val acts = vm.adviceFor(adviceText)
        val prompt = vm.lastUserText()
        AlertDialog(
            onDismissRequest = { dlg = "" },
            title = { Text("Что делать") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    acts.forEach { a ->
                        val (label, hint) = when (a) {
                            "later" -> "Повторить через 15 минут" to "Если это лимит запросов, он обычно проходит через несколько минут."
                            "switch" -> "Переключить аккаунт OpenCode" to "Следующий сохранённый аккаунт и повтор запроса."
                            "model" -> "Выбрать другую модель" to "Лимиты у каждой модели свои."
                            "compact" -> "Сжать беседу" to "Огромный контекст быстрее упирается в лимиты."
                            "reset" -> "Сбросить сессию (оставить последнее)" to "Начать заново с новым id."
                            else -> "Повторить сейчас" to "Если причина была временной."
                        }
                        TextButton(onClick = {
                            dlg = ""
                            when (a) {
                                "later" -> prompt?.let { vm.scheduleDelayed(it, System.currentTimeMillis() + 15 * 60_000L) }
                                "switch" -> { vm.ocSwitchNext(); vm.retryLast() }
                                "model" -> { modelDraft = vm.ocModel; vm.loadOcModels(); dlg = "ocmodel" }
                                "compact" -> vm.compactOcSession()
                                "reset" -> vm.resetSessionAndChat(true)
                                else -> vm.retryLast()
                            }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) { Text(label); Text(hint, style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dlg = "" }) { Text("Закрыть") } },
        )
    }
    if (dlg == "stars") {
        AlertDialog(
            onDismissRequest = { dlg = "" },
            title = { Text("Отмеченные ответы") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    if (vm.stars.isEmpty()) Text("Нажми ☆ под ответом, чтобы отметить его.", style = MaterialTheme.typography.bodySmall)
                    vm.stars.reversed().forEach { t ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(t)); dlg = "" }, modifier = Modifier.weight(1f)) {
                                Text(t.replace("\n", " ").take(160), maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { vm.toggleStar(t) }) { Text("✕") }
                        }
                    }
                    if (vm.stars.isNotEmpty()) Text("Нажми на текст, чтобы скопировать.", style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = { TextButton(onClick = { dlg = "" }) { Text("Закрыть") } },
        )
    }
    if (dlg == "fill") {
        val tpl = tplFill.orEmpty()
        val names = Regex("\\{([^}]+)}").findAll(tpl).map { it.groupValues[1] }.distinct().toList()
        AlertDialog(
            onDismissRequest = { dlg = "" },
            title = { Text("Подставь значения") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    names.forEach { n ->
                        OutlinedTextField(value = fillVals[n].orEmpty(), onValueChange = { fillVals = fillVals + (n to it) }, singleLine = true, label = { Text(n) }, modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    var out = tpl
                    names.forEach { n -> out = out.replace("{$n}", fillVals[n].orEmpty()) }
                    input = if (input.isBlank()) out else input.trimEnd() + "\n" + out
                    dlg = ""
                }) { Text("Вставить") }
            },
            dismissButton = { TextButton(onClick = { dlg = "" }) { Text("Отмена") } },
        )
    }
    if (rewindAt >= 0) {
        AlertDialog(
            onDismissRequest = { rewindAt = -1 },
            title = { Text("Вернуться к этому сообщению?") },
            text = { Text("Чат обрежется до сообщения перед этим, файлы проекта вернутся к состоянию до него (по снимку), память модели обрежется так же. Перед откатом делается ещё один снимок, чтобы его можно было отменить. В режиме OpenCode память ведёт сам OpenCode, и её это не откатит.") },
            confirmButton = { TextButton(onClick = { vm.rewindTo(rewindAt); rewindAt = -1 }) { Text("Вернуться") } },
            dismissButton = { TextButton(onClick = { rewindAt = -1 }) { Text("Отмена") } },
        )
    }
    if (dlg == "resetpick") {
        val shownIdx = vm.items.indices.filter { vm.items[it] is UiItem.User || vm.items[it] is UiItem.Assistant }.takeLast(40)
        AlertDialog(
            onDismissRequest = { dlg = "" },
            title = { Text("С какого сообщения оставить") },
            text = {
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    Text("Выбранное и всё ниже останется в чате и уйдёт в первый запрос новой сессии. Всё выше забудется.", style = MaterialTheme.typography.bodySmall)
                    shownIdx.forEach { i ->
                        val it = vm.items[i]
                        val who = if (it is UiItem.User) "🧑" else "🤖"
                        val t = when (it) { is UiItem.User -> it.text; is UiItem.Assistant -> it.text; else -> "" }.replace("\n", " ").take(90)
                        TextButton(onClick = { pickFrom = i }, modifier = Modifier.fillMaxWidth()) {
                            Text((if (i == pickFrom) "▶ " else if (i > pickFrom) "│ " else "   ") + who + " " + t, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.resetSessionKeepFrom(pickFrom); dlg = "" }) { Text("Сбросить") } },
            dismissButton = { TextButton(onClick = { dlg = "" }) { Text("Отмена") } },
        )
    }
    if (dlg == "search") {
        val q = searchQ.trim()
        val hits = if (q.length < 2) emptyList() else vm.items.mapIndexedNotNull { i, it ->
            val t = when (it) {
                is UiItem.User -> it.text
                is UiItem.Assistant -> it.text
                is UiItem.Tool -> it.description
                is UiItem.Error -> it.text
                is UiItem.Notice -> it.text
            }
            val p = t.indexOf(q, ignoreCase = true)
            if (p < 0) null else i to t.substring(maxOf(0, p - 30), minOf(t.length, p + 70)).replace("\n", " ")
        }
        AlertDialog(
            onDismissRequest = { dlg = "" },
            title = { Text("Поиск по чату") },
            text = {
                Column {
                    OutlinedTextField(value = searchQ, onValueChange = { searchQ = it }, singleLine = true, label = { Text("Что искать") }, modifier = Modifier.fillMaxWidth())
                    Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                        if (q.length >= 2 && hits.isEmpty()) Text("Ничего не найдено", style = MaterialTheme.typography.bodySmall)
                        hits.take(50).forEach { (i, s) ->
                            TextButton(onClick = { dlg = ""; scope.launch { listState.scrollToItem(i) } }, modifier = Modifier.fillMaxWidth()) {
                                Text("${i + 1}. $s", maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dlg = "" }) { Text("Закрыть") } },
        )
    }
    if (dlg == "notes") {
        AlertDialog(
            onDismissRequest = { dlg = "" },
            title = { Text("Заметки проекта") },
            text = {
                Column {
                    Text("Файл .pocketcode/NOTES.md. Агент видит эти заметки в каждом чате этого проекта.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = notesDraft, onValueChange = { notesDraft = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp))
                }
            },
            confirmButton = { TextButton(onClick = { vm.saveNotes(notesDraft); dlg = "" }) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { dlg = "" }) { Text("Отмена") } },
        )
    }
    if (dlg == "tpl") {
        AlertDialog(
            onDismissRequest = { dlg = "" },
            title = { Text("Мои шаблоны") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (vm.promptTemplates.isEmpty()) Text("Пока пусто. Добавь фразу, которую часто пишешь.", style = MaterialTheme.typography.bodySmall)
                    vm.promptTemplates.forEachIndexed { i, t ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            TextButton(onClick = {
                                if (Regex("\\{[^}]+}").containsMatchIn(t)) { tplFill = t; fillVals = emptyMap(); dlg = "fill" }
                                else { input = if (input.isBlank()) t else input.trimEnd() + "\n" + t; dlg = "" }
                            }, modifier = Modifier.weight(1f)) {
                                Text(t, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                            }
                            TextButton(onClick = { vm.removeTemplate(i) }) { Text("✕") }
                        }
                    }
                    OutlinedTextField(value = newTpl, onValueChange = { newTpl = it }, label = { Text("Новый шаблон") }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { vm.addTemplate(newTpl); newTpl = "" }, enabled = newTpl.isNotBlank()) { Text("Добавить") }
                }
            },
            confirmButton = { TextButton(onClick = { dlg = "" }) { Text("Закрыть") } },
        )
    }
    if (dlg == "ocmodel") {
        AlertDialog(
            onDismissRequest = { dlg = "" },
            title = { Text("Модель OpenCode") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(value = modelDraft, onValueChange = { modelDraft = it }, singleLine = true, label = { Text("провайдер/модель, пусто — по умолчанию") }, modifier = Modifier.fillMaxWidth())
                    if (vm.ocModelsBusy) Text("Загружаю список…", style = MaterialTheme.typography.labelSmall)
                    vm.ocModelList.take(80).forEach { m ->
                        TextButton(onClick = { vm.setOcModelValue(m); dlg = "" }, modifier = Modifier.fillMaxWidth()) { Text(m, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.setOcModelValue(modelDraft.trim()); dlg = "" }) { Text("Применить") } },
            dismissButton = { TextButton(onClick = { dlg = "" }) { Text("Отмена") } },
        )
    }
    if (dlg == "model") {
        AlertDialog(
            onDismissRequest = { dlg = "" },
            title = { Text("Сменить модель") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Меняется у активного профиля, сразу для следующего запроса.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = modelDraft, onValueChange = { modelDraft = it }, singleLine = true, label = { Text("Модель") }, modifier = Modifier.fillMaxWidth())
                    if (vm.openCodeMode) {
                        Text("Модели OpenCode (нажми, чтобы выбрать):", style = MaterialTheme.typography.labelSmall)
                        vm.ocModelList.take(60).forEach { m ->
                            TextButton(onClick = { vm.setOcModelValue(m); dlg = "" }, modifier = Modifier.fillMaxWidth()) { Text(m, modifier = Modifier.fillMaxWidth()) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.setModelNow(modelDraft); dlg = "" }) { Text("Применить") } },
            dismissButton = { TextButton(onClick = { dlg = "" }) { Text("Отмена") } },
        )
    }

    vm.approval?.let { request ->
        val rootCmd = if (request.tool == "root") request.description.removePrefix("ROOT $ ") else null
        val rootInfo = rootCmd?.let { rootNote(it) }
        val rootLeft = rememberRootCountdown(request, if (rootInfo != null) rootDelaySeconds(vm.rootDelay, rootInfo) else 0)
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Разрешить: ${request.tool}?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val red = androidx.compose.ui.graphics.Color(0xFFE57373)
                    val green = androidx.compose.ui.graphics.Color(0xFF81C784)
                    dangerNote(request.description)?.let {
                        Text("⚠ $it", color = red, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    Text(
                        androidx.compose.ui.text.buildAnnotatedString {
                            request.description.lines().forEachIndexed { i, line ->
                                if (i > 0) append("\n")
                                val color = when {
                                    line.startsWith("- ") -> red
                                    line.startsWith("+ ") -> green
                                    else -> null
                                }
                                if (color != null) withStyle(androidx.compose.ui.text.SpanStyle(color = color)) { append(line) } else append(line)
                            }
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                    if (rootInfo != null) RootNoteView(rootInfo, vm.rootDelay)
                    if (request.tool == "write_file" || request.tool == "edit_file") {
                        TextButton(onClick = { vm.allowEditsThisRun() }) { Text("Принимать все правки до конца задачи") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.answerApproval(true) }, enabled = rootLeft == 0) { Text(if (rootLeft > 0) "Разрешить ($rootLeft)" else "Разрешить") }
            },
            dismissButton = {
                TextButton(onClick = { vm.answerApproval(false) }) { Text("Отклонить") }
            },
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EmptyHint(workspacePath: String, welcome: (@Composable () -> Unit)?, onPick: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        if (welcome != null) { welcome(); Spacer(Modifier.height(20.dp)) }
        val cs = MaterialTheme.colorScheme
        Text(
            "Что будем делать?",
            style = MaterialTheme.typography.headlineMedium.copy(
                brush = androidx.compose.ui.graphics.Brush.linearGradient(listOf(cs.primary, androidx.compose.ui.graphics.lerp(cs.primary, cs.onSurface, 0.35f))),
            ),
        )
        Text(
            "Агент читает и правит файлы, запускает команды и работает с git прямо на телефоне.",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 18.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                Triple(AppIcons.Search, "Разберись в проекте", "Изучи проект и коротко опиши его структуру и как его запускать."),
                Triple(AppIcons.Bug, "Найди ошибки", "Проверь проект на ошибки и исправь найденное. Перед правками перечисли, что собираешься менять."),
                Triple(AppIcons.Flask, "Напиши тесты", "Напиши тесты для основных функций проекта и запусти их."),
                Triple(AppIcons.Bot, "Telegram-бот", "Создай простого Telegram-бота на aiogram 3 с командами /start и /help. Токен читай из переменной окружения BOT_TOKEN."),
                Triple(AppIcons.Globe, "Сайт", "Сделай простую адаптивную страницу index.html и открой превью."),
                Triple(AppIcons.File, "README", "Напиши README.md для этого проекта."),
                Triple(AppIcons.Wrench, "Почини сборку", "Запусти сборку или проверку проекта, найди причину ошибки и исправь её. Покажи, что именно было не так."),
                Triple(AppIcons.Book, "Объясни код", "Объясни простыми словами, как устроен главный файл этого проекта и что в нём важно."),
                Triple(AppIcons.Layers, "Упрости код", "Найди самое запутанное место в проекте и предложи, как его упростить. Сначала покажи план, ничего не меняй без моего согласия."),
                Triple(AppIcons.Package, "Зависимости", "Проверь зависимости проекта: что устарело или не используется. Покажи список, ничего не обновляй без моего согласия."),
            ).chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { (icon, title, text) ->
                        Column(
                            Modifier.weight(1f)
                                .clip(RoundedCornerShape(20.dp))
                                .background(cs.surfaceContainer)
                                .clickable { onPick(text) }
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(cs.primary.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center,
                            ) { Icon(icon, contentDescription = null, tint = cs.primary, modifier = Modifier.size(19.dp)) }
                            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Text(
            "Рабочая папка: $workspacePath",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 24.dp),
        )
    }
}

@Composable
private fun ItemView(item: UiItem, live: Boolean = false) {
    when (item) {
        is UiItem.User -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            val ios = LocalUiStyle.current == UiStyle.IOS
            val cs = MaterialTheme.colorScheme
            Box(
                Modifier.fillMaxWidth(0.85f)
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp))
                    .background(if (ios) cs.primary else cs.primaryContainer),
            ) {
                SelectionContainer {
                    Text(
                        (if (item.images > 0) "🖼 ×${item.images}\n" else "") + item.text,
                        color = if (ios) cs.onPrimary else cs.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }
        is UiItem.Assistant -> {
            val ios = LocalUiStyle.current == UiStyle.IOS
            Box(
                Modifier.fillMaxWidth()
                    .then(
                        if (ios) Modifier
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 20.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        else Modifier,
                    )
                    .padding(horizontal = if (ios) 14.dp else 4.dp, vertical = if (ios) 10.dp else 4.dp),
            ) {
                // Пока текст дописывается, выделение не включаем: оно пересчитывается на каждый кадр и заметно тормозит
                if (live && !LocalReduceMotion.current) SmoothMarkdown(item.text, modifier = Modifier.fillMaxWidth())
                else SelectionContainer { MarkdownText(item.text, modifier = Modifier.fillMaxWidth()) }
            }
        }
        is UiItem.Tool -> {
            var expanded by remember { mutableStateOf(false) }
            val output = item.output
            val cs = MaterialTheme.colorScheme
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (item.isError) cs.error.copy(alpha = 0.10f) else cs.surfaceContainer)
                    .then(if (LocalReduceMotion.current) Modifier else Modifier.animateContentSize())
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(toolIcon(item.name), contentDescription = null, tint = if (item.isError) cs.error else cs.primary, modifier = Modifier.size(16.dp))
                    Text(item.name, style = MaterialTheme.typography.labelLarge)
                    Text(
                        toolTitle(item.description),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = cs.onSurfaceVariant,
                        maxLines = if (expanded) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (output == null) ThinkingDots()
                    else Text(
                        toolStateLabel(running = false, error = item.isError),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (item.isError) cs.error else cs.onSurfaceVariant,
                    )
                }
                if (expanded && item.description.trim().lines().size > 1) {
                    SelectionContainer {
                        Text(
                            item.description.trim(),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                when {
                    output == null -> {}
                    output.isBlank() -> {}
                    expanded -> SelectionContainer {
                        Text(
                            if (output.length > 6000) output.take(6000) + "\n… (показано 6000 из ${output.length} символов)" else output,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    else -> Text(
                        output.trim().lines().filter { it.isNotBlank() }.take(2).joinToString("\n"),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        is UiItem.Notice -> Text(
            item.text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp, horizontal = 12.dp),
        )
        is UiItem.Error -> Card(
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            SelectionContainer {
                Text(item.text, modifier = Modifier.padding(12.dp))
            }
        }
    }
}

private fun toolIcon(name: String): androidx.compose.ui.graphics.vector.ImageVector = when (name) {
    "read_file", "list_dir" -> AppIcons.File
    "write_file", "edit_file" -> AppIcons.Pencil
    "bash", "bg", "python" -> AppIcons.Terminal
    "git", "github" -> AppIcons.Branch
    "grep", "code_search" -> AppIcons.Search
    "web_search", "web_fetch" -> AppIcons.Globe
    "run_tests" -> AppIcons.Flask
    "task", "spawn", "worker_result" -> AppIcons.Layers
    "todo", "present_plan" -> AppIcons.Check
    else -> AppIcons.Wrench
}

@Composable
private fun chipColors() = androidx.compose.material3.AssistChipDefaults.assistChipColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    labelColor = MaterialTheme.colorScheme.onSurface,
    leadingIconContentColor = MaterialTheme.colorScheme.primary,
)

/** Ответ агента заканчивается вопросом: под ним показываем быстрые кнопки «Да», «Нет», «Продолжай». Заглушка «⏳…» не считается. */
fun isQuestionReply(text: String): Boolean = text.trimEnd().endsWith("?") && !text.startsWith("⏳")

/** Нужно ли прыгать к началу элемента списка: только если его ещё нет среди показанных на экране. */
fun shouldJumpToItem(visibleIndices: List<Int>, target: Int): Boolean = target !in visibleIndices

/** Предупреждение об опасной команде в окне подтверждения. */
internal fun dangerNote(desc: String): String? {
    val d = desc.lowercase()
    val rules = listOf(
        Regex("""rm\s+(-\w*r\w*f|-\w*f\w*r|-r\s+-f|-f\s+-r)""") to "Удаление файлов без возврата (rm -rf).",
        Regex("""\bdd\s+if=""") to "Команда dd может затереть диск или раздел.",
        Regex("""\bmkfs""") to "Форматирование раздела.",
        Regex("""chmod\s+-r\s+777""") to "Открытие прав на всё для всех (chmod -R 777).",
        Regex("""git\s+push\s+.*(--force|-f\b)""") to "Принудительная отправка в git затирает чужую историю.",
        Regex("""git\s+reset\s+--hard""") to "Сброс git --hard теряет несохранённые правки.",
        Regex("""git\s+clean\s+-\w*f""") to "git clean удаляет неотслеживаемые файлы.",
        Regex("""(curl|wget)[^|\n]*\|\s*(sudo\s+)?(sh|bash)""") to "Скачивание и запуск скрипта из сети без просмотра.",
        Regex("""drop\s+(table|database)""") to "Удаление таблицы или базы данных.",
        Regex(""">\s*/dev/sd|:\(\)\s*\{""") to "Опасная системная команда.",
    )
    return rules.firstOrNull { it.first.containsMatchIn(d) }?.second
}
