package com.pocketcode.ui

import android.content.Intent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import com.pocketcode.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Значок в скруглённой плашке: общий для карточек этого экрана. */
@Composable
private fun SupportBadge(icon: ImageVector, size: Int = 44) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size((size / 2).dp)) }
}

/** Карточка-ссылка: значок, название, пояснение. Нажатие открывает ссылку. */
@Composable
private fun SupportLinkCard(icon: ImageVector, title: String, about: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SupportBadge(icon)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(AppIcons.Chevron, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Экран «Спасибо автору»: несколько необязательных способов сказать спасибо. Ничего не навязывает. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportScreen(vm: ChatController, onBack: () -> Unit) {
    val ctx = LocalContext.current
    fun open(url: String) {
        if (!Support.isAllowed(url)) return
        vm.markThanks()
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
    }
    Scaffold(
        topBar = {
            AppTopBar(
                title = { Text("Спасибо автору") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(backIcon(), contentDescription = "Назад") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(
                    Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surfaceContainer)),
                ),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SupportBadge(AppIcons.Heart, 52)
                    Text("Спасибо, что пользуешься PocketCode", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Приложение бесплатное и таким останется. Если оно пригодилось и захочется сказать спасибо, ниже несколько способов. Это ни к чему не обязывает: можно просто закрыть экран.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                "Если захочется", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp, top = 8.dp),
            )
            SupportLinkCard(AppIcons.CreditCard, "Российские карты", "Через CloudTips") { open(Support.CLOUDTIPS) }
            SupportLinkCard(AppIcons.Coin, "Криптовалюта", "Через Crypto Bot в Telegram") { open(Support.CRYPTO) }
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        SupportBadge(AppIcons.Star)
                        Column(Modifier.weight(1f)) {
                            Text("Tribute", style = MaterialTheme.typography.titleMedium)
                            Text("Карты, в том числе российские, и криптовалюта", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { open(Support.TRIBUTE_APP) }, modifier = Modifier.weight(1f)) { Text("В Telegram") }
                        FilledTonalButton(onClick = { open(Support.TRIBUTE_WEB) }, modifier = Modifier.weight(1f)) { Text("На сайте") }
                    }
                }
            }

            if (vm.thanksUnlocked) {
                Text(
                    "Спасибо 💛 В «Ещё → Настройки», в разделе «Цвет акцента», появились новые цвета.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            Text(
                "Не хочется или нечем — это тоже нормально. Идеи и сообщения об ошибках в группе Telegram помогают не меньше.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}
