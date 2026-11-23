package com.pocketcode.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Центр настройки внешнего вида: темы, свой цвет, шрифты, размеры, анимации, фоны. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(
    initial: AppearanceConfig,
    themeMode: String = "dark",
    amoled: Boolean = false,
    onThemeMode: (String) -> Unit = {},
    onAmoled: (Boolean) -> Unit = {},
    onSave: (AppearanceConfig) -> Unit,
    onPickFont: () -> Unit,
    onPickBackground: () -> Unit,
    onBack: () -> Unit,
) = Scaffold(
    topBar = {
        AppTopBar(
            title = { Text("Оформление") },
            navigationIcon = { IconButton(onClick = onBack) { androidx.compose.material3.Icon(backIcon(), contentDescription = "Назад") } },
        )
    },
) { pad ->
    var c by remember { mutableStateOf(initial) }
    fun update(block: (AppearanceConfig) -> AppearanceConfig) { c = block(c); onSave(c) }
    Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), Arrangement.spacedBy(10.dp)) {
        SectionHeader("Режим темы", "Тёмная, светлая или как в системе")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("dark" to "Тёмная", "light" to "Светлая", "system" to "Как в системе", "schedule" to "По времени").forEach { (v, l) ->
                androidx.compose.material3.FilterChip(selected = themeMode == v, onClick = { onThemeMode(v) }, label = { Text(l) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("AMOLED: чисто чёрный фон")
                Text("Экономит заряд на OLED-экранах.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = amoled, onCheckedChange = onAmoled)
        }
        SectionHeader("Тема", "${ThemeCatalog.presets.size} готовых палитр — применяются сразу")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeCatalog.presets.forEach { p ->
                Column(
                    Modifier.width(78.dp).clip(MaterialTheme.shapes.medium)
                        .background(if (c.themeId == p.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                        .clickable { update { it.copy(themeId = p.id) } }.padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(40.dp).clip(MaterialTheme.shapes.small).background(p.dark))
                    Text(p.title, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        SectionHeader("Палитра", "Выбери цвет акцента — применяется сразу")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ACCENTS.forEach { a ->
                FilterChip(
                    selected = c.accentId == a.id && c.customColor.isBlank() && c.themeId == "default",
                    onClick = { update { it.copy(accentId = a.id, customColor = "", themeId = "default") } },
                    leadingIcon = { Box(Modifier.size(14.dp).clip(MaterialTheme.shapes.small).background(a.dark)) },
                    label = { Text(a.title, style = MaterialTheme.typography.labelSmall) },
                )
            }
            FilterChip(
                selected = c.accentId == "system" && c.customColor.isBlank(),
                onClick = { update { it.copy(accentId = "system", customColor = "") } },
                label = { Text("Из обоев", style = MaterialTheme.typography.labelSmall) },
            )
        }
        Text("Свой цвет акцента", style = MaterialTheme.typography.titleSmall)
        var hex by remember { mutableStateOf(c.customColor) }
        OutlinedTextField(hex, { hex = it }, label = { Text("#RRGGBB") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { update { it.copy(customColor = hex.trim()) } }) { Text("Применить") }
            Button(onClick = { hex = ""; update { it.copy(customColor = "") } }) { Text("Сбросить") }
        }
        SectionHeader("Шрифты", "Системный или свой TTF/OTF")
        Text("Текущий: ${c.fontFamily}. Моно: ${c.monoFontFamily}.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPickFont) { Text("Выбрать TTF/OTF") }
            Button(onClick = { update { it.copy(fontFamily = "system", monoFontFamily = "system") } }) { Text("Системный") }
        }
        Text("Размер текста", style = MaterialTheme.typography.titleSmall)
        sliderRow("Интерфейс", c.uiFontScale) { v -> update { cfg -> cfg.copy(uiFontScale = v) } }
        sliderRow("Чат", c.chatFontScale) { v -> update { cfg -> cfg.copy(chatFontScale = v) } }
        SectionHeader("Анимации", "Переходы, списки и индикаторы")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AnimationLevel.entries.forEach { lvl ->
                FilterChip(selected = c.animations.level == lvl, onClick = { update { it.copy(animations = it.animations.copy(level = lvl)) } }, label = { Text(lvl.label) })
            }
        }
        sliderRow("Скорость", c.animations.speed, 0.25f..4f) { v -> update { cfg -> cfg.copy(animations = cfg.animations.copy(speed = v)) } }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EasingPreset.entries.forEach { e ->
                FilterChip(selected = c.animations.easing == e, onClick = { update { it.copy(animations = it.animations.copy(easing = e)) } }, label = { Text(e.label) })
            }
        }
        toggleRow("Переходы экранов", c.animations.screenTransitions) { v -> update { cfg -> cfg.copy(animations = cfg.animations.copy(screenTransitions = v)) } }
        toggleRow("Анимации списков", c.animations.listAnimations) { v -> update { cfg -> cfg.copy(animations = cfg.animations.copy(listAnimations = v)) } }
        toggleRow("Пульсация индикаторов", c.animations.pulseIndicators) { v -> update { cfg -> cfg.copy(animations = cfg.animations.copy(pulseIndicators = v)) } }
        SectionHeader("Фон", "По умолчанию — без фона")
        Text("По умолчанию — без фона. Можно поставить встроенный градиент, свой цвет или изображение.")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BackgroundCatalog.presets.forEach { b ->
                Column(
                    Modifier.width(70.dp).clickable { update { it.copy(background = b.id) } }.padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(44.dp).clip(MaterialTheme.shapes.small)
                            .then(if (b.colors.size > 1) Modifier.background(Brush.linearGradient(b.colors.map { Color(it) })) else Modifier.background(Color(b.colors.firstOrNull() ?: 0xFF000000))),
                    )
                    Text(b.title, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPickBackground) { Text("Своё изображение") }
            Button(onClick = { update { it.copy(background = "color") } }) { Text("Свой цвет") }
            Button(onClick = { update { it.copy(background = "none", backgroundImage = "") } }) { Text("Без фона") }
        }
        if (c.background == "color") {
            var bgHex by remember { mutableStateOf(c.backgroundCustomColor) }
            OutlinedTextField(bgHex, { bgHex = it }, label = { Text("Цвет фона #RRGGBB") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { update { it.copy(background = "color", backgroundCustomColor = bgHex.trim()) } }) { Text("Применить цвет") }
                if (AppearanceCodec.parseColor(bgHex) != null) {
                    Box(Modifier.size(28.dp).clip(MaterialTheme.shapes.small).background(AppearanceCodec.parseColor(bgHex)!!))
                }
            }
        }
        SectionHeader("Дополнительно", "Тонкая настройка внешнего вида")
        sliderRow("Скругление карточек", c.cornerRadius.toFloat(), 0f..28f) { v -> update { cfg -> cfg.copy(cornerRadius = v.toInt()) } }
        sliderRow("Толщина обводки", c.strokeWidth.toFloat(), 0f..4f) { v -> update { cfg -> cfg.copy(strokeWidth = v.toInt()) } }
        toggleRow("Режим чтения", c.readingMode) { v -> update { cfg -> cfg.copy(readingMode = v) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { update { it.copy(themeId = ThemeCatalog.presets.random().id) } }) { Text("Случайная тема") }
            Button(onClick = { update { it.copy(themeJson = kotlinx.serialization.json.Json.encodeToString(AppearanceConfig.serializer(), it)) } }) { Text("Экспорт настроек") }
        }
        if (c.themeJson.isNotBlank()) OutlinedTextField(c.themeJson, { v -> update { cfg -> cfg.copy(themeJson = v) } }, label = { Text("JSON темы (можно вставить и нажать импорт)") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            runCatching { kotlinx.serialization.json.Json.decodeFromString<AppearanceConfig>(c.themeJson) }.getOrNull()?.let { update { _ -> it } }
        }) { Text("Импорт настроек") }
    }
}

@Composable
private fun sliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float> = 0.5f..2f, onChange: (Float) -> Unit) {
    Text("$label: ${"%.2f".format(value)}", style = MaterialTheme.typography.bodySmall)
    Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun toggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
