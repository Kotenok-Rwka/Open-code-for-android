package com.pocketcode.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pocketcode.session.QuestionData
import com.pocketcode.session.UiItem

/**
 * Всплывающее окно с тем же вопросом: варианты кнопками и поле «Свой ответ».
 * Открывается само, когда модель присылает блок QUESTIONS; «Позже» оставляет карточку в ленте.
 */
@Composable
fun QuestionDialog(
    item: UiItem.Questions,
    onAnswer: (List<Pair<String, String>>) -> Unit,
    onDismiss: () -> Unit,
) {
    val q = item.questions.getOrNull(item.index) ?: return
    val cs = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(22.dp),
            color = cs.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(
                Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (item.questions.size > 1) "Вопрос ${item.index + 1} из ${item.questions.size}" else "Уточнение",
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.primary,
                        modifier = Modifier.weight(1f),
                    )
                    if (q.tag.isNotBlank()) {
                        Text(q.tag, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(end = 6.dp))
                    }
                    TextButton(onClick = onDismiss) { Text("Позже") }
                }
                QuestionAnswerBlock(item, q, onAnswer, autoFocus = true)
            }
        }
    }
}

/** Карточка с вариантами ответа: выбор запоминается в самом UiItem и переживает перезапуск. */
@Composable
fun QuestionCardView(item: UiItem.Questions, onAnswer: (List<Pair<String, String>>) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val q = item.questions.getOrNull(item.index)
    if (item.done || q == null) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp))
                .background(cs.surfaceContainer).padding(12.dp),
        ) {
            Text("Ответы сохранены", style = MaterialTheme.typography.labelMedium, color = cs.primary)
            for (a in item.answers) Text("${a.question} → ${a.answer}", style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp))
            .border(1.dp, cs.primary.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .background(cs.surfaceContainer).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (item.questions.size > 1) "${item.index + 1} из ${item.questions.size}" else "Вопрос",
                style = MaterialTheme.typography.labelMedium,
                color = cs.primary,
                modifier = Modifier.weight(1f),
            )
            if (q.tag.isNotBlank()) Text(q.tag, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
        QuestionAnswerBlock(item, q, onAnswer)
    }
}

/**
 * Общий блок выбора ответа: текст вопроса, варианты, «Свой ответ» и кнопки.
 * Используется и карточкой в ленте, и всплывающим окном, поэтому разметка живёт в одном месте.
 */
@Composable
private fun QuestionAnswerBlock(
    item: UiItem.Questions,
    q: QuestionData,
    onAnswer: (List<Pair<String, String>>) -> Unit,
    autoFocus: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    var selected by remember(item.index, q) { mutableStateOf(-1) }
    var custom by remember(item.index, q) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(item.index, q) { if (autoFocus) runCatching { focus.requestFocus() } }
    val pick = { i: Int ->
        selected = i
        custom = ""
    }
    val answer = { custom.trim().ifBlank { q.options.getOrNull(selected)?.label.orEmpty() } }
    val submit = { if (selected >= 0 || custom.isNotBlank()) onAnswer(listOf(q.question to answer())) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(q.question, style = MaterialTheme.typography.titleMedium)
        q.options.forEachIndexed { i, opt ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(if (selected == i) cs.primaryContainer else cs.surface)
                    .clickable { pick(i) }.padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == i, onClick = { pick(i) })
                Column(Modifier.weight(1f).padding(end = 4.dp)) {
                    Text(opt.label, style = MaterialTheme.typography.bodyMedium)
                    if (opt.description.isNotBlank()) {
                        Text(opt.description, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
        OutlinedTextField(
            value = custom,
            onValueChange = { custom = it; if (it.isNotBlank()) selected = -1 },
            label = { Text("Свой ответ") },
            placeholder = { Text("Напиши, если варианты не подходят") },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            maxLines = 3,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onAnswer(listOf(q.question to SKIP_ANSWER)) }) { Text("Пропустить") }
            Spacer(Modifier.weight(1f))
            Button(
                enabled = selected >= 0 || custom.isNotBlank(),
                onClick = { submit() },
            ) {
                Text(if (item.index < item.questions.lastIndex) "Далее" else "Ответить")
            }
        }
    }
}
