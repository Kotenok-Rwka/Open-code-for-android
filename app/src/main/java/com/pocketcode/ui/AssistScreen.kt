package com.pocketcode.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pocketcode.SettingsStore
import com.pocketcode.assist.AnswerDepth
import com.pocketcode.assist.AnswerKind
import com.pocketcode.assist.AssistHints
import com.pocketcode.assist.AssistHistoryStore
import com.pocketcode.assist.AssistNotesStore
import com.pocketcode.assist.AssistPrefs
import com.pocketcode.assist.AssistPrefsStore
import com.pocketcode.assist.AssistProfile
import com.pocketcode.assist.AssistProfileStore
import com.pocketcode.assist.PocketAssistService
import com.pocketcode.assist.ProgressMode
import com.pocketcode.assist.ResultMode
import com.pocketcode.assist.SpeakMode
import kotlinx.coroutines.delay

/** Настройки ассистента: вызов вместо Google, как показывать ответ и ход поиска. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val settingsStore = remember { SettingsStore(ctx.applicationContext) }
    val store = remember { AssistPrefsStore({ settingsStore.rawGet(it) }, { k, v -> settingsStore.rawPut(k, v) }) }
    var prefs by remember { mutableStateOf(store.load()) }
    fun change(p: AssistPrefs) {
        prefs = p
        store.save(p)
    }
    val meStore = remember { AssistProfileStore({ settingsStore.rawGet(it) }, { k, v -> settingsStore.rawPut(k, v) }) }
    var me by remember { mutableStateOf(meStore.load()) }
    fun changeMe(p: AssistProfile) {
        me = p
        meStore.save(p)
    }
    val historyStore = remember { AssistHistoryStore({ settingsStore.rawGet(it) }, { k, v -> settingsStore.rawPut(k, v) }) }
    var historyCount by remember { mutableStateOf(historyStore.list().size) }
    val notesStore = remember { AssistNotesStore({ settingsStore.rawGet(it) }, { k, v -> settingsStore.rawPut(k, v) }) }
    var notesCount by remember { mutableStateOf(notesStore.list().size) }
    var overlayOk by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    var isDefault by remember { mutableStateOf(PocketAssistService.isDefault(ctx)) }
    var mic by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    var note by remember { mutableStateOf("") }
    // пользователь уходит в системные настройки и возвращается: состояние проверяем раз в секунду, пока экран открыт
    LaunchedEffect(Unit) {
        while (true) {
            isDefault = PocketAssistService.isDefault(ctx)
            mic = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            overlayOk = Settings.canDrawOverlays(ctx)
            delay(1000)
        }
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { mic = it }
    val profiles = remember { settingsStore.loadProfiles() }

    Scaffold(
        topBar = {
            AppTopBar(
                title = { Text("Ассистент") },
                navigationIcon = { androidx.compose.material3.IconButton(onClick = onBack) { androidx.compose.material3.Icon(backIcon(), contentDescription = "Назад") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Назначь PocketCode ассистентом по умолчанию, и он будет открываться вместо Google: долгим нажатием Home, жестом из угла экрана или кнопкой питания (как именно, зависит от телефона).", style = MaterialTheme.typography.bodySmall)

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (isDefault) "✓ PocketCode выбран ассистентом" else "✗ PocketCode не выбран ассистентом", style = MaterialTheme.typography.titleSmall)
                    if (!isDefault) {
                        Text(AssistHints.settingsPath(Build.MANUFACTURER), style = MaterialTheme.typography.bodySmall)
                        Text("Путь в настройках может отличаться на твоей прошивке: ищи пункт «Цифровой помощник» или «Помощник по умолчанию».", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { openAssistantSettings(ctx) }, modifier = Modifier.fillMaxWidth()) { Text("Открыть настройки ассистента") }
                    OutlinedButton(
                        onClick = { note = if (PocketAssistService.show()) "" else "Сначала выбери PocketCode ассистентом" },
                        enabled = isDefault,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Вызвать сейчас") }
                    if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall)
                }
            }

            Text("Что отвечать", style = MaterialTheme.typography.titleMedium)
            Chips {
                Choice("Ответ с источниками", prefs.answerKind == AnswerKind.AI) { change(prefs.copy(answerKind = AnswerKind.AI)) }
                Choice("Только ссылки", prefs.answerKind == AnswerKind.LINKS) { change(prefs.copy(answerKind = AnswerKind.LINKS)) }
            }
            Text(
                if (prefs.answerKind == AnswerKind.AI) "Ассистент ищет в интернете, читает страницы и коротко отвечает со ссылками на источники. Нужен ключ API."
                else "Обычный поиск без нейросети: список страниц с описанием. Ключ API не нужен.",
                style = MaterialTheme.typography.bodySmall,
            )

            Text("Глубина ответа", style = MaterialTheme.typography.titleMedium)
            Chips {
                Choice("Быстро", prefs.depth == AnswerDepth.FAST) { change(prefs.copy(depth = AnswerDepth.FAST)) }
                Choice("Обычно", prefs.depth == AnswerDepth.NORMAL) { change(prefs.copy(depth = AnswerDepth.NORMAL)) }
                Choice("Глубоко", prefs.depth == AnswerDepth.DEEP) { change(prefs.copy(depth = AnswerDepth.DEEP)) }
            }
            Text(
                when (prefs.depth) {
                    AnswerDepth.FAST -> "Один поиск и короткий ответ в пару фраз: быстрее и дешевле."
                    AnswerDepth.NORMAL -> "Поиск, при необходимости одна-две страницы, ответ в несколько абзацев."
                    AnswerDepth.DEEP -> "Несколько поисков, чтение страниц и сверка источников: дольше и дороже, зато полнее. Не влияет на режим «Только ссылки»."
                },
                style = MaterialTheme.typography.bodySmall,
            )

            Text("Показ результата", style = MaterialTheme.typography.titleMedium)
            Chips {
                Choice("Спрашивать", prefs.resultMode == ResultMode.ASK) { change(prefs.copy(resultMode = ResultMode.ASK)) }
                Choice("Во всплывающем окне", prefs.resultMode == ResultMode.POPUP) { change(prefs.copy(resultMode = ResultMode.POPUP)) }
                Choice("На весь экран", prefs.resultMode == ResultMode.FULLSCREEN) { change(prefs.copy(resultMode = ResultMode.FULLSCREEN)) }
            }
            Text(
                when (prefs.resultMode) {
                    ResultMode.ASK -> "Когда ответ готов, во всплывающем окошке предлагается показать его здесь или на весь экран. Выбор можно запомнить."
                    ResultMode.POPUP -> "Ответ сразу появляется во всплывающем окне; на весь экран его можно развернуть кнопкой."
                    ResultMode.FULLSCREEN -> "Ответ сразу открывается на весь экран; свернуть его в окно можно кнопкой."
                },
                style = MaterialTheme.typography.bodySmall,
            )

            Text("Прогресс поиска", style = MaterialTheme.typography.titleMedium)
            Chips {
                Choice("Только индикатор", prefs.progressMode == ProgressMode.MINIMAL) { change(prefs.copy(progressMode = ProgressMode.MINIMAL)) }
                Choice("Кратко", prefs.progressMode == ProgressMode.COMPACT) { change(prefs.copy(progressMode = ProgressMode.COMPACT)) }
                Choice("Подробно", prefs.progressMode == ProgressMode.DETAILED) { change(prefs.copy(progressMode = ProgressMode.DETAILED)) }
            }
            Text(
                when (prefs.progressMode) {
                    ProgressMode.MINIMAL -> "После запроса в маленьком окошке крутится только индикатор и виден вопрос."
                    ProgressMode.COMPACT -> "В маленьком окошке одна строка о том, что сейчас делает ассистент: ищет, читает страницу, пишет ответ."
                    ProgressMode.DETAILED -> "Окошко показывает все шаги поиска, какие страницы открыты, сколько найдено источников, время и начало ответа по мере написания."
                },
                style = MaterialTheme.typography.bodySmall,
            )

            Text("Озвучка ответа", style = MaterialTheme.typography.titleMedium)
            Chips {
                Choice("Выкл", prefs.speakMode == SpeakMode.OFF) { change(prefs.copy(speakMode = SpeakMode.OFF)) }
                Choice("Если спросил голосом", prefs.speakMode == SpeakMode.VOICE) { change(prefs.copy(speakMode = SpeakMode.VOICE)) }
                Choice("Всегда", prefs.speakMode == SpeakMode.ALWAYS) { change(prefs.copy(speakMode = SpeakMode.ALWAYS)) }
            }
            Text("Ответ читает вслух системный синтезатор речи. Остановить или запустить озвучку можно кнопкой с динамиком под ответом.", style = MaterialTheme.typography.bodySmall)

            Text("Простые команды", style = MaterialTheme.typography.titleMedium)
            SwitchRow("Выполнять простые команды без нейросети", prefs.localCommands) { change(prefs.copy(localCommands = it)) }
            Text("Калькулятор («сколько будет 15% от 200», «2+2*3»), время и дата, заметки: «запомни, что ключи в ящике», «запиши в заметку купить молоко», «покажи заметки». Считается и записывается на самом телефоне, без интернета и без расхода на нейросеть. Заметки хранятся только в PocketCode.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = { notesStore.clear(); notesCount = 0 },
                enabled = notesCount > 0,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (notesCount > 0) "Удалить заметки ($notesCount)" else "Заметок нет") }

            Text("Вызов", style = MaterialTheme.typography.titleMedium)
            SwitchRow("Сразу слушать при вызове", prefs.autoListen) { change(prefs.copy(autoListen = it)) }
            Text("Окно открывается с включённым микрофоном; чтобы напечатать, коснись поля ввода.", style = MaterialTheme.typography.bodySmall)
            SwitchRow("Показывать окно снова, когда ответ готов", prefs.popupWhenReady) { change(prefs.copy(popupWhenReady = it)) }
            SwitchRow("Спрашивать подтверждение перед закрытием ответа", prefs.confirmClose) { change(prefs.copy(confirmClose = it)) }
            SwitchRow("Плашка с ходом поиска поверх других приложений", prefs.overlayIndicator) { change(prefs.copy(overlayIndicator = it)) }
            Text("Пока идёт поиск, а окно закрыто, вверху экрана висит маленькая плашка с ходом поиска; касание открывает окно. Без разрешения вместо неё показывается уведомление.", style = MaterialTheme.typography.bodySmall)
            if (prefs.overlayIndicator) {
                Text(if (overlayOk) "✓ Разрешено показывать поверх других приложений" else "✗ Нет разрешения показывать поверх других приложений", style = MaterialTheme.typography.bodyMedium)
                if (!overlayOk) {
                    OutlinedButton(
                        onClick = { runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Разрешить поверх других приложений") }
                }
            }
            Text("Команды для телефона: в окне ассистента нажми «Управлять телефоном» и скажи, что сделать. Они выполняются режимом «Управление телефоном», его нужно включить в приложении.", style = MaterialTheme.typography.bodySmall)

            Text("Дополнительно", style = MaterialTheme.typography.titleMedium)
            SwitchRow("Показывать источники под ответом", prefs.showSources) { change(prefs.copy(showSources = it)) }
            SwitchRow("Кнопка «Что на экране?»", prefs.useScreenText) { change(prefs.copy(useScreenText = it)) }
            Text("Текст экрана уходит выбранной модели только после нажатия на эту кнопку; поля паролей не читаются, на заблокированном экране кнопки нет.", style = MaterialTheme.typography.bodySmall)
            SwitchRow("Уведомление, если окно закрыли до конца поиска", prefs.notifyWhenHidden) { change(prefs.copy(notifyWhenHidden = it)) }

            if (profiles.size > 1) {
                Text("Модель ассистента", style = MaterialTheme.typography.titleMedium)
                Chips {
                    Choice("Как в чате", prefs.profileId.isEmpty()) { change(prefs.copy(profileId = "")) }
                    for (p in profiles) {
                        FilterChip(selected = prefs.profileId == p.id, onClick = { change(prefs.copy(profileId = p.id)) }, label = { Text(p.label) })
                    }
                }
                Text("Для быстрых ответов подойдёт лёгкая и дешёвая модель.", style = MaterialTheme.typography.bodySmall)
            }

            Text("История запросов", style = MaterialTheme.typography.titleMedium)
            SwitchRow("Сохранять историю запросов", prefs.saveHistory) { change(prefs.copy(saveHistory = it)) }
            Text("Запросы и ответы хранятся только на телефоне (последние 40); открыть их можно кнопкой «История» в окне ассистента.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = { historyStore.clear(); historyCount = 0 },
                enabled = historyCount > 0,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (historyCount > 0) "Очистить историю ($historyCount)" else "История пуста") }

            Text("О себе", style = MaterialTheme.typography.titleMedium)
            Text("По желанию расскажи о себе, и ассистенту не придётся каждый раз спрашивать основу, например город для погоды. Заполни только то, что хочешь.", style = MaterialTheme.typography.bodySmall)
            SwitchRow("Учитывать то, что я о себе рассказал", me.enabled) { changeMe(me.copy(enabled = it)) }
            MeField("Как к тебе обращаться", me.name, 1) { changeMe(me.copy(name = it)) }
            MeField("Город", me.city, 1) { changeMe(me.copy(city = it)) }
            MeField("Чем занимаешься", me.occupation, 2) { changeMe(me.copy(occupation = it)) }
            MeField("Интересы", me.interests, 2) { changeMe(me.copy(interests = it)) }
            MeField("Как отвечать (например: коротко, с юмором, без лишних слов)", me.style, 2) { changeMe(me.copy(style = it)) }
            MeField("Ещё что стоит знать", me.extra, 4) { changeMe(me.copy(extra = it)) }
            Text("Эти данные уходят выбранной модели с каждым запросом в режиме «Ответ с источниками». Не пиши сюда пароли, номера карт и другие секреты.", style = MaterialTheme.typography.bodySmall)

            Text("Голосовой ввод", style = MaterialTheme.typography.titleMedium)
            Text(if (mic) "✓ Доступ к микрофону есть" else "✗ Нет доступа к микрофону", style = MaterialTheme.typography.bodyMedium)
            if (!mic) {
                OutlinedButton(onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }, modifier = Modifier.fillMaxWidth()) { Text("Разрешить микрофон") }
            }
            Text("Речь распознаёт служба Google или другая служба распознавания, установленная на телефоне. Голосовой ввод в других приложениях продолжит работать как раньше.", style = MaterialTheme.typography.bodySmall)
            Text("Запрос и найденные страницы уходят выбранной модели; поиск идёт через DuckDuckGo.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Chips(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun MeField(title: String, value: String, lines: Int, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(if (lines >= 4) AssistProfile.MAX_EXTRA else AssistProfile.MAX_FIELD)) },
        label = { Text(title) },
        minLines = 1,
        maxLines = lines,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Choice(title: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(title) })
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Открывает системный выбор ассистента; если на прошивке такого экрана нет, идём к «приложениям по умолчанию», затем в общие настройки. */
private fun openAssistantSettings(ctx: android.content.Context) {
    val tries = listOf(Settings.ACTION_VOICE_INPUT_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, Settings.ACTION_SETTINGS)
    for (action in tries) {
        val ok = runCatching { ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (ok) return
    }
}
