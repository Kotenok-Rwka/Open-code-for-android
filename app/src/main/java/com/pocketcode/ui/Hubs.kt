package com.pocketcode.ui

import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.pocketcode.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

data class HubItem(val icon: ImageVector, val title: String, val about: String, val badge: String? = null, val header: Boolean = false, val onClick: () -> Unit = {})

/** Заголовок группы внутри раздела. */
fun hubHeader(text: String) = HubItem(AppIcons.Chevron, text, "", null, true)

/** Раздел нижней панели: пункты с пояснениями, куда это ведёт. Вид зависит от выбранного стиля интерфейса. */
@Composable
fun HubScreen(title: String, subtitle: String, items: List<HubItem>, onSearch: (() -> Unit)? = null) {
    if (LocalUiStyle.current == UiStyle.IOS) IosHub(title, subtitle, items, onSearch)
    else Md3Hub(title, subtitle, items, onSearch)
}

/** Material 3: тональная шапка и карточки без рамок, значки в кружках. */
@Composable
private fun Md3Hub(title: String, subtitle: String, items: List<HubItem>, onSearch: (() -> Unit)?) {
    val m = metricsOf(UiStyle.MD3)
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Box(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)
                    .clip(RoundedCornerShape(m.heroRadius.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 24.dp, vertical = 28.dp),
            ) {
                if (onSearch != null) androidx.compose.material3.IconButton(onClick = onSearch, modifier = Modifier.align(Alignment.TopEnd)) {
                    Icon(androidx.compose.material.icons.Icons.Default.Search, contentDescription = "Поиск по настройкам", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Column {
                    Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                }
            }
        }
        items(items) { entry ->
            if (entry.header) {
                Text(
                    entry.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 2.dp),
                )
                return@items
            }
            Card(
                onClick = entry.onClick,
                shape = RoundedCornerShape(m.cardRadius.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(
                        Modifier.size(m.iconTile.dp).clip(RoundedCornerShape(m.iconTileRadius.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) { Icon(entry.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(22.dp)) }
                    Column(Modifier.weight(1f)) {
                        Text(entry.title, style = MaterialTheme.typography.titleMedium)
                        Text(entry.about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    entry.badge?.let { b ->
                        Box(Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 8.dp, vertical = 2.dp)) {
                            Text(b, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                    Icon(AppIcons.Chevron, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** iOS: крупный заголовок на подложке, пункты собраны в скруглённые группы с тонкими разделителями. */
@Composable
private fun IosHub(title: String, subtitle: String, items: List<HubItem>, onSearch: (() -> Unit)?) {
    val m = metricsOf(UiStyle.IOS)
    val sections = groupBySections(items, { it.header }, { it.title })
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineMedium)
                    if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (onSearch != null) androidx.compose.material3.IconButton(onClick = onSearch) {
                    Icon(androidx.compose.material.icons.Icons.Default.Search, contentDescription = "Поиск по настройкам", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        sections.forEach { section ->
            val head = section.first
            val rows = section.second
            item {
                if (head != null) {
                    Text(
                        head.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 22.dp, bottom = 6.dp),
                    )
                } else {
                    Spacer(Modifier.height(16.dp))
                }
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(m.cardRadius.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    rows.forEachIndexed { i, row -> IosRow(row, last = i == rows.lastIndex) }
                }
            }
        }
    }
}

@Composable
private fun IosRow(entry: HubItem, last: Boolean) {
    val m = metricsOf(UiStyle.IOS)
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = entry.onClick).padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(m.iconTile.dp).clip(RoundedCornerShape(m.iconTileRadius.dp)).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) { Icon(entry.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp)) }
            Column(Modifier.weight(1f)) {
                Text(entry.title, style = MaterialTheme.typography.bodyLarge)
                if (entry.about.isNotBlank()) {
                    Text(
                        entry.about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            entry.badge?.let { b -> Text(b, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Icon(AppIcons.Chevron, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
        }
        if (!last) HorizontalDivider(Modifier.padding(start = 58.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}
