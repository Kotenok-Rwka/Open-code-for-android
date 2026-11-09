package com.pocketcode.video

import com.pocketcode.ui.backIcon
import com.pocketcode.ui.AppTopBar
import android.media.MediaPlayer
import android.os.SystemClock
import android.widget.VideoView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import com.pocketcode.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.pocketcode.ui.AppIcons
import kotlinx.coroutines.delay

/**
 * Монтаж по тексту: слова распознанной речи можно вычёркивать, плеер при просмотре сам перескакивает вычеркнутое,
 * так что результат слышен сразу, без рендера.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun TextEditScreen(
    v: VideoPipeline,
    edit: TextEdit,
    info: VideoInfo,
    pauses: List<TimeRange>?,
    withPauses: Boolean,
    onWithPauses: (Boolean) -> Unit,
    busy: Boolean,
    onBack: () -> Unit,
    onRender: (List<TimeRange>) -> Unit,
) {
    val extra = rememberUpdatedState(if (withPauses && pauses != null) pauses else emptyList())
    val cuts by remember(edit) { derivedStateOf { mergeCuts(edit.cuts() + extra.value) } }
    val cutsNow = rememberUpdatedState(cuts)

    var view by remember { mutableStateOf<VideoView?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var pos by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var lastSeek by remember { mutableLongStateOf(0L) }
    var note by remember { mutableStateOf("") }

    fun seek(sec: Double) {
        val ms = (sec * 1000).toLong().coerceAtLeast(0)
        lastSeek = SystemClock.elapsedRealtime()
        pos = ms
        val p = player
        if (p != null && android.os.Build.VERSION.SDK_INT >= 26) p.seekTo(ms, MediaPlayer.SEEK_CLOSEST) else view?.seekTo(ms.toInt())
    }

    fun playFrom(sec: Double) {
        seek(sec)
        view?.start()
        playing = true
    }

    // Пока видео играет, перескакиваем вычеркнутые куски
    LaunchedEffect(view) {
        while (true) {
            delay(40)
            val vv = view ?: continue
            val t = runCatching { vv.currentPosition }.getOrDefault(0)
            pos = t.toLong()
            val isPlaying = runCatching { vv.isPlaying }.getOrDefault(false)
            playing = isPlaying
            if (isPlaying && SystemClock.elapsedRealtime() - lastSeek > 250) {
                val sec = t / 1000.0
                val c = cutsNow.value.firstOrNull { sec >= it.start && sec < it.end - 0.02 }
                if (c != null) {
                    if (c.end >= info.duration - 0.05) { vv.pause(); playing = false } else seek(c.end)
                }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { runCatching { view?.stopPlayback() } } }

    val active by remember { derivedStateOf { edit.wordAt(pos / 1000.0) } }
    val deleted = edit.deletedCount
    val removedSec = cuts.sumOf { it.length }
    val left = (info.duration - removedSec).coerceAtLeast(0.0)

    Scaffold(topBar = {
        AppTopBar(
            title = { Text("Монтаж по тексту", maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(backIcon(), contentDescription = "Назад") } },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            val ar = if (info.width > 0 && info.height > 0) info.width.toFloat() / info.height else 16f / 9f
            AndroidView(
                factory = { c ->
                    VideoView(c).also { vv ->
                        vv.setVideoPath(v.absPath(info.rel))
                        vv.setOnPreparedListener { mp ->
                            player = mp
                            vv.seekTo(1)
                        }
                        vv.setOnCompletionListener { playing = false }
                        view = vv
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).aspectRatio(ar.coerceIn(0.4f, 2.4f)).align(Alignment.CenterHorizontally),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val vv = view ?: return@Button
                    if (playing) { vv.pause(); playing = false } else playFrom(pos / 1000.0)
                }) { Text(if (playing) "Пауза" else "Смотреть") }
                Text(
                    "${fmtTime(pos / 1000.0)} · останется ${fmtTime(left)} из ${fmtTime(info.duration)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = { note = "Отмечено слов-паразитов: ${edit.markFillers()}" }, label = { Text("Слова-паразиты") })
                AssistChip(onClick = { note = "Отмечено повторов: ${edit.markRepeats()}" }, label = { Text("Повторы") })
                AssistChip(onClick = { edit.reset(); note = "" }, label = { Text("Сбросить") })
                if (pauses != null) FilterChip(selected = withPauses, onClick = { onWithPauses(!withPauses) }, label = { Text("И паузы (${pauses.size})") })
            }
            Text(
                if (note.isNotBlank()) note else "Нажмите слово, чтобы вычеркнуть. Долгое нажатие: слушать с этого места.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(edit.paras) { _, r ->
                    Column(Modifier.fillMaxWidth()) {
                        val allGone = r.all { edit.flags[it] }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { playFrom(edit.words[r.first].start) }) { Text("▶ ${fmtTime(edit.words[r.first].start)}") }
                            TextButton(onClick = { edit.setRange(r, !allGone) }) { Text(if (allGone) "Вернуть" else "Убрать абзац") }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (i in r) {
                                val gone = edit.flags[i]
                                val on = i == active
                                Text(
                                    edit.words[i].text,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (gone) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                    textDecoration = if (gone) TextDecoration.LineThrough else null,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (on) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                                        .combinedClickable(onClick = { edit.toggle(i) }, onLongClick = { playFrom(edit.words[i].start) })
                                        .padding(horizontal = 3.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }

            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    enabled = !busy && (cuts.isNotEmpty()),
                    onClick = { view?.pause(); playing = false; onRender(cuts) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (deleted == 0 && cuts.isNotEmpty()) "Смонтировать" else "Смонтировать: вычеркнуто слов $deleted") }
            }
        }
    }
}
