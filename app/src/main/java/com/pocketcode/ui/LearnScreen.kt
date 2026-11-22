package com.pocketcode.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Один урок: короткое объяснение, переход на нужный экран и мини-тест (правильный ответ — [answer]). */
class Lesson(
    val id: String,
    val title: String,
    val body: String,
    /** Куда ведёт кнопка «Показать, где это»: chat, models, smart, root, diff, linux, diag. */
    val target: String,
    val question: String,
    val options: List<String>,
    val answer: Int,
    val explain: String,
    /** Необязательно: шаги, советы и раздел. Урок без вопроса (пустые options) засчитывается кнопкой «Понятно». */
    val steps: List<String> = emptyList(),
    val tips: List<String> = emptyList(),
    val group: String = "",
)

val LESSONS = listOf(
    Lesson(
        "start", "🚀 Первая задача",
        "Напиши в поле ввода, что сделать с проектом, обычными словами. Агент сам читает файлы, правит их и запускает команды. Над полем есть быстрые кнопки: стоп, файлы, терминал, правки. В пустом чате есть готовые шаблоны задач.",
        "chat",
        "Где агент работает с файлами по умолчанию?",
        listOf("Только в папке текущего проекта", "На всём телефоне", "Только в облаке"), 0,
        "Агент работает в папке проекта. Выйти за её пределы он может только через root или команды, которые ты подтвердил.",
    ),
    Lesson(
        "models", "🔑 Модель и ключи",
        "Чтобы агент работал, нужна модель: ключ провайдера или бесплатный старт. В профиле можно загрузить список моделей с сервера, сохранить свои и быстро проверить ключ, не тратя токены.",
        "models",
        "Что делает кнопка «Быстро проверить ключ»?",
        listOf("Отправляет тестовую задачу агенту", "Проверяет ключ и наличие модели без траты токенов", "Удаляет ключ"), 1,
        "Она запрашивает у сервиса список моделей: так видно, что ключ принят и модель существует, и токены не тратятся.",
    ),
    Lesson(
        "free", "🎁 Бесплатный старт",
        "Нет ключа? В пустом чате нажми «Начать бесплатно»: приложение само поставит мини-Linux и OpenCode с бесплатными моделями. Нужен интернет, в России может понадобиться VPN. Не отправляй туда секретный код: запросы идут через сервис OpenCode.",
        "chat",
        "Что нужно для бесплатного старта?",
        listOf("Платная подписка", "Root-доступ", "Только интернет: ключ и регистрация не нужны"), 2,
        "Приложение ставит всё само. Нужен только интернет, а при проблемах с загрузкой поможет VPN.",
    ),
    Lesson(
        "limits", "⏱ Лимиты и резервные модели",
        "У бесплатных ключей часто лимит, например 1 запрос в минуту. В профиле задай, сколько запросов за какое время, над чатом видно, сколько занято. Добавь резервные профили: если квота кончилась, агент сам перейдёт на следующий.",
        "models",
        "Лимит 3 запроса за 10 минут. Что будет с четвёртым запросом?",
        listOf("Он сразу уйдёт и вызовет ошибку", "Он подождёт, пока в окне освободится место", "Агент остановится навсегда"), 1,
        "Окно скользящее: пока есть место, запросы идут сразу, потом агент ждёт и продолжает сам.",
    ),
    Lesson(
        "auto", "🤖 Авто-режим",
        "Всё подтверждается само, а создание, правку и удаление файлов одобряешь ты или нейросеть-проверяющая. Опасные команды всегда спрашивают тебя. Включается на экране «Root и Android» после предупреждения на 20 секунд и выключается сам через 2 часа. Предохранитель выключит режим, если одобрено слишком много действий.",
        "root",
        "Нейросеть-проверяющая не ответила. Что будет?",
        listOf("Действие выполнится само", "Действие навсегда отклонится", "Решаешь ты"), 2,
        "Нейросеть может только одобрить. Если ответа нет или она сомневается, действие ждёт тебя.",
    ),
    Lesson(
        "undo", "↩️ Откат и корзина",
        "Откат возвращает файлы, которые агент изменил в последнем ходе. Корзина переносит файлы, удалённые командой rm, в папку .pocketcode-trash на 7 дней. Оба включаются в «Умные функции». Команды оболочки и Python откатом не покрываются.",
        "smart",
        "Что возвращает «Откатить последний ход»?",
        listOf("Любые изменения на телефоне", "Файлы, записанные агентом в последнем ходе", "Только git-коммиты"), 1,
        "Журнал запоминает файлы перед записью через инструменты правки. Всё, что сделали команды оболочки или Python, он не видит.",
    ),
    Lesson(
        "smart", "🧠 Умные функции и режимы",
        "В «Ещё → Умные функции» у каждой функции свой выключатель. Режимы «Экономно», «Максимум» и «Бесплатно» меняют сразу несколько настроек. Автовыбор модели отправляет короткие вопросы на дешёвую модель-помощник, а карта проекта сразу показывает агенту, что где лежит.",
        "smart",
        "Что делает автовыбор модели?",
        listOf("Простые вопросы отвечает модель-помощник, задачи с правками основная", "Всегда берёт самую дорогую модель", "Выключает агента"), 0,
        "Так экономятся деньги и квота: сложные задачи по-прежнему идут на основную модель.",
    ),
    Lesson(
        "files", "📝 Файлы, правки и git",
        "В разделе «Проект» есть файлы, поиск, терминал и git. «Правки агента» показывают построчно, что он изменил, и позволяют откатить. Перед подтверждением правки тоже видно разницу.",
        "diff",
        "Где посмотреть, что агент изменил?",
        listOf("Нигде", "Только в git на компьютере", "Проект → Правки агента"), 2,
        "Раздел «Правки агента» показывает изменения построчно и умеет откатывать блоки.",
    ),
    Lesson(
        "linux", "🐧 Среда Linux",
        "В «Ещё → Среда Linux» ставится мини-Linux размером около 4 МБ. С ним агент может ставить и запускать python, git и node. Скачивание идёт в фоне, с экрана можно выйти.",
        "linux",
        "Зачем нужен мини-Linux?",
        listOf("Чтобы агент мог ставить и запускать python, git и node", "Чтобы сменить тему", "Для голосового ввода"), 0,
        "Без него у агента только базовая оболочка Android. С мини-Linux доступны привычные инструменты разработки.",
    ),
    Lesson(
        "help", "🩺 Диагностика и поддержка",
        "«Ещё → Проверка и расход» проверяет связь с моделью, файлы, git и противоречия в настройках, а ещё показывает расход токенов. Вопросы и идеи пиши в Telegram-группу PocketCode.",
        "diag",
        "Что проверяет диагностика?",
        listOf("Только версию приложения", "Скорость интернета", "Ключ, связь с моделью, файлы, git и противоречия в настройках"), 2,
        "Если что-то не работает, начни с диагностики: она подсказывает, что именно не так.",
    ),
)

private val NEW_LESSONS = listOf(
    Lesson(
        "howai", "🧠 Как работает нейронка",
        "Новый пункт в меню «⋮». Простыми словами: что модель видит (контекст и правила), как выбирает шаг, зачем вызывает инструменты и почему «думает» несколько секунд. Там же объясняются живые статусы 🧠/⚙️ и размышления.",
        "chat",
        "Что получает модель при каждом запросе?",
        listOf("Только последнее сообщение", "Контекст: правила, историю, описания инструментов", "Только файлы проекта"), 1,
        "Модель видит системные правила, историю чата и результаты инструментов. Всё это вместе называется контекстом.",
    ),
    Lesson(
        "tools", "🛠 Инструменты модели",
        "Меню «⋮» → «Инструменты модели»: здесь видно все доступные модели инструменты и тумблеры. Отключённые не передаются в запрос — модель их не увидит. На этом же экране включаются и выключаются размышления и их глубина.",
        "chat",
        "Что произойдёт, если выключить инструмент тумблером?",
        listOf("Модель не сможет его вызвать", "Инструмент удалится с телефона", "Ничего не изменится"), 0,
        "Мы просто не передаём описание инструмента модели, поэтому она о нём не знает. Файлы и настройки остаются на месте.",
    ),
    Lesson(
        "appearance_themes", "🎨 Оформление и темы",
        "Меню «⋮» → «Оформление и темы»: 14 палитр, свой цвет акцента, свои шрифты, размер текста, уровень анимаций, фоны и режим чтения. Настройки применяются сразу и сохраняются.",
        "chat",
        "Где настроить размер текста отдельно для чата?",
        listOf("В настройках модели", "В «Оформлении и темах»", "В настройках телефона"), 1,
        "В «Оформлении и темах» есть отдельные слайдеры масштаба интерфейса и чата.",
    ),
    Lesson(
        "plugins_github", "🔌 Навыки, плагины и GitHub-поиск",
        "На экране «Навыки и плагины» можно ставить встроенные навыки, подключать MCP и искать готовые репозитории прямо на GitHub с описанием и числом звёзд. При первом открытии поиск запускается сам; есть сортировка и фильтры по категориям.",
        "plugins",
        "Что показывает поиск GitHub?",
        listOf("Только названия файлов", "Репозитории с описанием и звёздами", "Только ошибки"), 1,
        "Поиск показывает готовые проекты с описанием, языком и числом звёзд — можно сразу установить или открыть.",
    ),
    Lesson(
        "sync", "🔄 Синхронизация с ПК",
        "Меню «⋮» → «Экспорт истории на ПК» экспортирует полную историю чата в JSON, а «Загрузить с ПК» загружает её обратно. На ПК компаньон читает и показывает ответы целиком, без обрезки символов.",
        "chat",
        "Чем отличается синхронизация с ПК от обычного бэкапа?",
        listOf("Это экран пошаговой истории для ПК без обрезки текста", "Она удаляет чат", "Она работает только в root"), 0,
        "Формат содержит полный текст каждого сообщения и проект, чтобы продолжить работу на другом устройстве.",
    ),
)

/** Все уроки: основные с тестом и дополнительные из LearnContent. */
val ALL_LESSONS: List<Lesson> get() = LESSONS + MORE_LESSONS + NEW_LESSONS

/** Обучение с практикой: уроки, кнопка «Показать, где это» и мини-тест на каждый. Прогресс сохраняется. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LearnScreen(vm: ChatController, onBack: () -> Unit, onGo: (String) -> Unit, onTour: () -> Unit) {
    val lessons = ALL_LESSONS
    var open by remember { mutableStateOf<String?>(lessons.firstOrNull { it.id !in vm.learnDone }?.id) }
    var query by remember { mutableStateOf("") }
    var faqOpen by remember { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = {
            AppTopBar(
                title = { Text("Обучение") },
                navigationIcon = { IconButton(onClick = onBack) { androidx.compose.material3.Icon(backIcon(), contentDescription = "Назад") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val done = lessons.count { it.id in vm.learnDone }
            Text("Пройдено $done из ${lessons.size}", style = MaterialTheme.typography.titleMedium)
            val progress by androidx.compose.runtime.rememberUpdatedState(done.toFloat() / lessons.size)
            val animated by androidx.compose.animation.core.animateFloatAsState(progress, tween(if (LocalReduceMotion.current) 0 else 500), label = "learnProgress")
            LinearProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth())
            androidx.compose.material3.OutlinedTextField(
                value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Найти ответ: например «лимит» или «sdk»") },
            )
            Text(
                "Открой урок, нажми «Показать, где это» и ответь на вопрос. Правильный ответ засчитывает урок.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val shown = remember(query) { searchLessons(lessons, query) }
            val faq = remember(query) { searchFaq(query) }
            if (query.isNotBlank() && shown.isEmpty() && faq.isEmpty()) {
                Text("Ничего не нашлось. Попробуй другое слово или открой «Проверка и расход» или группу в Telegram.", style = MaterialTheme.typography.bodyMedium)
            }
            shown.forEach { l ->
                LessonCard(l, vm.learnDone, open == l.id, { open = if (open == l.id) null else l.id }, onGo) { vm.markLesson(l.id) }
            }
            if (faq.isNotEmpty()) {
                Text("Частые вопросы", style = MaterialTheme.typography.titleMedium)
                faq.forEach { f ->
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { faqOpen = if (faqOpen == f.q) null else f.q },
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(f.q, style = MaterialTheme.typography.titleSmall)
                            if (faqOpen == f.q) {
                                Text(f.a, style = MaterialTheme.typography.bodyMedium)
                                val target = lessons.firstOrNull { it.id == f.lesson }
                                if (target != null) TextButton(onClick = { query = ""; open = target.id }) { Text("К уроку: " + target.title) }
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onTour, modifier = Modifier.weight(1f)) { Text("Показать подсказки на экране") }
                TextButton(onClick = { vm.resetLessons() }) { Text("Сбросить") }
            }
        }
    }
}

@Composable
private fun LessonCard(l: Lesson, doneSet: Set<String>, expanded: Boolean, onToggle: () -> Unit, onGo: (String) -> Unit, onPassed: () -> Unit) {
    val done = l.id in doneSet
    var picked by remember(l.id) { mutableStateOf<Int?>(null) }
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(l.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (done) Text("✓ пройден", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = onToggle) { Text(if (expanded) "Свернуть" else "Открыть") }
            }
            if (expanded) {
                Text(l.body, style = MaterialTheme.typography.bodyMedium)
                if (l.steps.isNotEmpty()) {
                    Text("Как сделать", style = MaterialTheme.typography.titleSmall)
                    l.steps.forEachIndexed { i, st ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${i + 1}.", style = MaterialTheme.typography.bodyMedium)
                            Text(st, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        }
                    }
                }
                if (l.tips.isNotEmpty()) {
                    Text("Полезно знать", style = MaterialTheme.typography.titleSmall)
                    l.tips.forEach {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("•", style = MaterialTheme.typography.bodySmall)
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        }
                    }
                }
                Button(onClick = { onGo(l.target) }, modifier = Modifier.fillMaxWidth()) { Text("Показать, где это") }
                if (l.options.isEmpty() && !done) OutlinedButton(onClick = onPassed, modifier = Modifier.fillMaxWidth()) { Text("Понятно, отметить пройденным") }
                if (l.options.isNotEmpty()) Text(l.question, style = MaterialTheme.typography.titleSmall)
                l.options.forEachIndexed { i, o ->
                    val p = picked
                    val label = when {
                        p == null -> o
                        i == l.answer -> "✓ $o"
                        i == p -> "✗ $o"
                        else -> o
                    }
                    OutlinedButton(
                        onClick = {
                            picked = i
                            if (i == l.answer) onPassed()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(label) }
                }
                picked?.let { p ->
                    Text(
                        (if (p == l.answer) "Верно. " else "Не совсем. ") + l.explain,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (p == l.answer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    if (p != l.answer) TextButton(onClick = { picked = null }) { Text("Попробовать ещё раз") }
                }
            }
        }
    }
}
