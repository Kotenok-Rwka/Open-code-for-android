package com.pocketcode.ui

import androidx.compose.foundation.layout.Row
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

/** Небольшая кнопка «Копировать» под сообщением: Ответ «Скопировано» на 1.5 с. */
@Composable
fun CopyButton(text: String, tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val clip = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (copied) {
            Text("✓ Скопировано", style = MaterialTheme.typography.labelSmall, color = tint)
        } else {
            IconButton(onClick = {
                clip.setText(AnnotatedString(text))
                copied = true
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    kotlinx.coroutines.delay(1500)
                    copied = false
                }
            }, modifier = Modifier.size(28.dp)) {
                androidx.compose.material3.Icon(
                    AppIcons.Copy,
                    contentDescription = "Копировать",
                    tint = tint,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}
