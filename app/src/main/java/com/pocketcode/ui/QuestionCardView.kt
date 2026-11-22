package com.pocketcode.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.pocketcode.session.UiItem

/** Карточка с вариантами ответа: выбор запоминается в самом UiItem и переживает перезапуск. */
@Composable
fun QuestionCardView(item: UiItem.Questions, onAnswer: (List<Pair<String, String>>) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val q = item.questions.getOrNull(item.index)
    var selected by remember(item.index, item) { mutableStateOf(-1) }
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
        Text(q.question, style = MaterialTheme.typography.titleMedium)
        q.options.forEachIndexed { i, opt ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(if (selected == i) cs.primaryContainer else cs.surfaceContainerHigh)
                    .clickable { selected = i }.padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected == i, onClick = { selected = i })
                    Column(Modifier.weight(1f)) {
                        Text(opt.label, style = MaterialTheme.typography.bodyMedium)
                        if (opt.description.isNotBlank()) Text(opt.description, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onAnswer(listOf(q.question to "Пропустить")) }) { Text("Пропустить") }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                Button(enabled = selected >= 0, onClick = { onAnswer(listOf(q.question to q.options[selected].label)) }) {
                    Text(if (item.index < item.questions.lastIndex) "Далее" else "Ответить")
                }
            }
        }
    }
}
