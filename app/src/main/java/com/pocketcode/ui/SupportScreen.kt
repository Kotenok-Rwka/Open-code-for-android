package com.pocketcode.ui

import android.content.Intent
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

/** Пасхалка: цветное конфетти, нарисованное кодом (без картинок). Показывается `burst` раз, затем гаснет. */
@Composable
private fun Confetti(burstKey: Int) {
    if (burstKey == 0) return
    val progress = remember(burstKey) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(burstKey) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(2200, easing = LinearEasing))
    }
    val pieces = remember(burstKey) {
        List(70) {
            floatArrayOf(kotlin.random.Random.nextFloat(), kotlin.random.Random.nextFloat(), kotlin.random.Random.nextFloat())
        }
    }
    val colors = listOf(Color(0xFFFFCA28), Color(0xFFF48FB1), Color(0xFF4DD0C4), Color(0xFF8AB4F8), Color(0xFF7BD88F), Color(0xFFB69DF8))
    val t = progress.value
    Canvas(Modifier.fillMaxSize()) {
        if (t >= 1f) return@Canvas
        pieces.forEachIndexed { i, p ->
            val w = size.width
            val x = w * p[0] + kotlin.math.sin(p[1] * 20f + t * 9f) * 40f
            val y = size.height * (p[1] - 0.2f) + t * t * size.height * 1.4f
            val sizeF = 8f + p[2] * 8f
            drawCircle(colors[i % colors.size].copy(alpha = (1f - t).coerceIn(0f, 1f)), radius = sizeF / 2f, center = androidx.compose.ui.geometry.Offset(x, y))
        }
    }
}

/** Экран «Спасибо автору»: несколько необязательных способов сказать спасибо. Ничего не навязывает. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportScreen(vm: ChatController, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var burstKey by remember { mutableIntStateOf(0) }
    var eggTaps by remember { mutableIntStateOf(0) }
    var eggFound by remember { mutableStateOf(false) }
    fun open(url: String) {
        if (!Support.isAllowed(url)) return
        val first = !vm.thanksUnlocked
        vm.markThanks()
        if (first) burstKey++
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
        Box(Modifier.padding(pad)) {
        Confetti(burstKey)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(
                    Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surfaceContainer)),
                ),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.clip(RoundedCornerShape(18.dp)).clickable {
                        eggTaps++
                        if (eggTaps >= 5 && !eggFound) {
                            eggFound = true
                            burstKey++
                        }
                    }) { SupportBadge(AppIcons.Heart, 52) }
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
                    "Спасибо 💛 Бонус открыт: в «Ещё → Настройки», в разделе «Цвет акцента», появились новые цвета, а на этом экране теперь живёт пасхалка. Подсказка: сердечко в шапке не простое.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            if (eggFound) {
                Text(
                    "🎉 Пасхалка найдена! Ты нажал на сердечко пять раз — конфетти твоя награда. Спасибо, что ты такой внимательный. Нажимать на сердечко можно и просто так.",
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
}
