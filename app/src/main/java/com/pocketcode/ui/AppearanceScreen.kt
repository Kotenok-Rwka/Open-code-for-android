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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Центр настройки внешнего вида: стиль интерфейса, темы, свой цвет, шрифты, размеры, анимации, фоны.
 * Все изменения применяются сразу; кнопки и чипы разложены так, чтобы подписи не переносились по буквам.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(
    initial: AppearanceConfig,
    themeMode: String = "dark",
    amoled: Boolean = false,
    uiStyle: String = "",
    onUiStyle: (String) -> Unit = {},
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
    val currentStyle = uiStyleById(uiStyle)
    Column(
        Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader("Стиль интерфейса", "Можно переключаться в любой момент — настройки сохраняются")
        ChipRow {
            UiStyle.values().forEach { st ->
                FilterChip(
                    selected = uiStyle == st.id,
                    onClick = { onUiStyle(st.id) },
                    label = { Text(st.title, maxLines = 1) },
                )
            }
        }
        Text(currentStyle.about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        SectionHeader("Режим темы", "Тёмная, светлая или как в системе")
        ChipRow {
            listOf("dark" to "Тёмная", "light" to "Светлая", "system" to "Как в системе", "schedule" to "По времени").forEach { (v, l) ->
                FilterChip(selected = themeMode == v, onClick = { onThemeMode(v) }, label = { Text(l, maxLines = 1) })
            }
        }
        SettingRow(title = "AMOLED: чисто чёрный фон", description = "Экономит заряд на OLED-экранах.") {
            AppSwitch(checked = amoled, onCheckedChange = onAmoled)
        }

        SectionHeader("Тема", "${ThemeCatalog.presets.size} готовых палитр — применяются сразу")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeCatalog.presets.forEach { p ->
                val selected = c.themeId == p.id
                Column(
                    Modifier.width(88.dp).clip(RoundedCornerShape(16.dp))
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
                        .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                        .clickable { update { it.copy(themeId = p.id) } }
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp))
                            .background(p.backgroundDark?.let { Color(it) } ?: p.dark),
                    )
                    Text(
                        p.title,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        minLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        SectionHeader("Палитра", "Цвет акцента — применяется сразу")
        ChipRow {
            ACCENTS.forEach { a ->
                FilterChip(
                    selected = c.accentId == a.id && c.customColor.isBlank() && c.themeId == "default",
                    onClick = { update { it.copy(accentId = a.id, customColor = "", themeId = "default") } },
                    leadingIcon = { Box(Modifier.size(14.dp).clip(MaterialTheme.shapes.small).background(a.dark)) },
                    label = { Text(a.title, maxLines = 1) },
                )
            }
            FilterChip(
                selected = c.accentId == "system" && c.customColor.isBlank(),
                onClick = { update { it.copy(accentId = "system", customColor = "") } },
                label = { Text("Из обоев", maxLines = 1) },
            )
        }
        Text("Свой цвет акцента", style = MaterialTheme.typography.titleSmall)
        var hex by remember { mutableStateOf(c.customColor) }
        OutlinedTextField(
            hex, { hex = it },
            label = { Text("#RRGGBB") },
            singleLine = true,
            isError = hex.isNotBlank() && AppearanceCodec.parseColor(hex) == null,
            supportingText = { if (hex.isNotBlank() && AppearanceCodec.parseColor(hex) == null) Text("Нужен формат #RRGGBB или #AARRGGBB") },
            modifier = Modifier.fillMaxWidth(),
        )
        ActionRow {
            Button(onClick = { update { it.copy(customColor = hex.trim()) } }, enabled = AppearanceCodec.parseColor(hex) != null) { Text("Применить") }
            OutlinedButton(onClick = { hex = ""; update { it.copy(customColor = "") } }) { Text("Сбросить") }
            AppearanceCodec.parseColor(hex)?.let { col ->
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(col))
            }
        }

        SectionHeader("Шрифты", "Системный или свой TTF/OTF")
        Text("Интерфейс: ${fontLabel(c.fontFamily)} · моно: ${fontLabel(c.monoFontFamily)}", style = MaterialTheme.typography.bodySmall)
        ActionRow {
            Button(onClick = onPickFont) { Text("Выбрать TTF/OTF") }
            OutlinedButton(onClick = { update { it.copy(fontFamily = "system", monoFontFamily = "system") } }) { Text("Системный") }
        }

        SectionHeader("Размер текста", "Масштаб интерфейса и чата")
        sliderRow("Интерфейс", c.uiFontScale) { v -> update { cfg -> cfg.copy(uiFontScale = v) } }
        sliderRow("Чат", c.chatFontScale) { v -> update { cfg -> cfg.copy(chatFontScale = v) } }

        SectionHeader("Анимации", "Переходы, списки и индикаторы")
        ChipRow {
            AnimationLevel.entries.forEach { lvl ->
                FilterChip(selected = c.animations.level == lvl, onClick = { update { it.copy(animations = it.animations.copy(level = lvl)) } }, label = { Text(lvl.label, maxLines = 1) })
            }
        }
        sliderRow("Скорость", c.animations.speed, 0.25f..4f) { v -> update { cfg -> cfg.copy(animations = cfg.animations.copy(speed = v)) } }
        ChipRow {
            EasingPreset.entries.forEach { e ->
                FilterChip(selected = c.animations.easing == e, onClick = { update { it.copy(animations = it.animations.copy(easing = e)) } }, label = { Text(e.label, maxLines = 1) })
            }
        }
        toggleRow("Переходы экранов", c.animations.screenTransitions) { v -> update { cfg -> cfg.copy(animations = cfg.animations.copy(screenTransitions = v)) } }
        toggleRow("Анимации списков", c.animations.listAnimations) { v -> update { cfg -> cfg.copy(animations = cfg.animations.copy(listAnimations = v)) } }
        toggleRow("Пульсация индикаторов", c.animations.pulseIndicators) { v -> update { cfg -> cfg.copy(animations = cfg.animations.copy(pulseIndicators = v)) } }

        SectionHeader("Фон", "Можно поставить встроенный градиент, свой цвет или изображение")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BackgroundCatalog.presets.forEach { b ->
                val selected = c.background == b.id
                Column(
                    Modifier.width(76.dp).clip(RoundedCornerShape(14.dp))
                        .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(14.dp))
                        .clickable { update { it.copy(background = b.id) } }
                        .padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
                            .then(
                                if (b.colors.size > 1) Modifier.background(Brush.linearGradient(b.colors.map { Color(it) }))
                                else Modifier.background(Color(b.colors.firstOrNull() ?: 0xFF000000)),
                            ),
                    )
                    Text(b.title, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        ActionRow {
            Button(onClick = onPickBackground) { Text("Своё изображение") }
            OutlinedButton(onClick = { update { it.copy(background = "color") } }) { Text("Свой цвет") }
            OutlinedButton(onClick = { update { it.copy(background = "none", backgroundImage = "") } }) { Text("Без фона") }
        }
        if (c.background == "image" && c.backgroundImage.isNotBlank()) {
            Text("Сейчас стоит изображение: ${c.backgroundImage}", style = MaterialTheme.typography.bodySmall)
        }
        if (c.background == "color") {
            var bgHex by remember { mutableStateOf(c.backgroundCustomColor) }
            OutlinedTextField(
                bgHex, { bgHex = it },
                label = { Text("Цвет фона #RRGGBB") },
                singleLine = true,
                isError = AppearanceCodec.parseColor(bgHex) == null,
                modifier = Modifier.fillMaxWidth(),
            )
            ActionRow {
                Button(onClick = { update { it.copy(background = "color", backgroundCustomColor = bgHex.trim()) } }, enabled = AppearanceCodec.parseColor(bgHex) != null) {
                    Text("Применить цвет")
                }
                AppearanceCodec.parseColor(bgHex)?.let { col ->
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(col))
                }
            }
        }

        SectionHeader("Дополнительно", "Тонкая настройка внешнего вида")
        sliderRow("Скругление карточек", c.cornerRadius.toFloat(), 0f..28f) { v -> update { cfg -> cfg.copy(cornerRadius = v.toInt()) } }
        sliderRow("Толщина обводки", c.strokeWidth.toFloat(), 0f..4f) { v -> update { cfg -> cfg.copy(strokeWidth = v.toInt()) } }
        toggleRow("Режим чтения", c.readingMode) { v -> update { cfg -> cfg.copy(readingMode = v) } }
        ActionRow {
            OutlinedButton(onClick = { update { it.copy(themeId = ThemeCatalog.presets.random().id) } }) { Text("Случайная тема") }
            OutlinedButton(onClick = {
                update { it.copy(themeJson = kotlinx.serialization.json.Json.encodeToString(AppearanceConfig.serializer(), it)) }
            }) { Text("Экспорт настроек") }
        }
        if (c.themeJson.isNotBlank()) {
            OutlinedTextField(
                c.themeJson, { v -> update { cfg -> cfg.copy(themeJson = v) } },
                label = { Text("JSON темы") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    runCatching { kotlinx.serialization.json.Json.decodeFromString<AppearanceConfig>(c.themeJson) }
                        .getOrNull()?.let { loaded -> update { _ -> loaded } }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Импорт настроек") }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp))
    }
}

/** Горизонтальная лента чипов: подписи не сжимаются до вертикальных букв, длинный список прокручивается. */
@Composable
private fun ChipRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Кнопки в одну прокручиваемую строку: на узком экране они не переносят текст по буквам. */
@Composable
private fun ActionRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun SettingRow(title: String, description: String, control: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            control()
        }
    }
}

/** Имя шрифта для человека: система или имя выбранного файла без пути. */
fun fontLabel(value: String): String = if (value.isBlank() || value == "system") "системный" else value.substringAfterLast('/')

@Composable
private fun sliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float> = 0.5f..2f, onChange: (Float) -> Unit) {
    Text("$label: ${"%.2f".format(value)}", style = MaterialTheme.typography.bodySmall)
    Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun toggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        AppSwitch(checked = checked, onCheckedChange = onChange)
    }
}
