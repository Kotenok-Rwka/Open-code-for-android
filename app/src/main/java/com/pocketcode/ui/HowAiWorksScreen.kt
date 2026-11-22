package com.pocketcode.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Понятное объяснение, как модель принимает решения и что показывает живой статус. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HowAiWorksScreen(onBack: () -> Unit) = Scaffold(
    topBar = {
        AppTopBar(
            title = { Text("Как работает нейронка") },
            navigationIcon = { IconButton(onClick = onBack) { androidx.compose.material3.Icon(backIcon(), contentDescription = "Назад") } },
        )
    },
) { pad ->
    Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), Arrangement.spacedBy(10.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), Arrangement.spacedBy(6.dp)) {
                Text("Коротко", style = MaterialTheme.typography.titleMedium)
                Text("Модель не «знает» ваш проект заранее. Ей каждый раз отправляют текст разговора, правила и описания инструментов. Она предсказывает следующий шаг: слово, вызов инструмента или finalize ответа.")
            }
        }
        section("1. Что видит модель", "Системная инструкция (роль и правила), история чата, карта проекта и результаты инструментов. Всё это называется контекстом; чем он длиннее, тем дороже и медленнее ответ. Экран «Что в контексте» показывает его по сообщениям и позволяет обрезать лишнее.")
        section("2. Как она выбирает действие", "На каждом шаге модель получает список инструментов (read_file, grep, bash…) и решает: ответить текстом или вызвать инструмент. Если вызвала — приложение выполняет его, добавляет результат в контекст и снова спрашивает модель. Так повторяется до финального ответа.")
        section("3. Почему «думает»", "Тяжёлая часть — генерация токенов один за другим. Модель может считать несколько секунд даже перед первым словом, особенно на телефоне или при длинном контексте. Это не зависание: значок 🧠 в чате показывает, что идёт генерация reasoning.")
        section("4. Размышление (reasoning)", "Некоторые модели сначала пишут скрытый ход мыслей, а потом ответ. Приложение показывает его как живой статус, но в итоговый архив сессии попадает только ответ. Включить/выключить можно пунктом «Размышление» в меню «…».")
        section("5. Планирование и раздельные модели", "В режиме плана агент только читает файлы и составляет план — не меняя код. При раздельном планировании одну модель (обычно более сильную) используют для плана, а другую — для выполнения. Настройка «Роли моделей» задаёт обе.")
        section("6. Инструменты и разрешения", "Модель сама просит прочитать/изменить файл или запустить команду. Приложение решает: разрешить, спросить вас или запретить — по режиму «Разрешения». Даже автоматический режим не отменяет проверку рискованных команд.")
        section("7. Кэш и стоимость", "Часть контекста, которая повторяется, провайдер может читать из кэша дешевле. В «Использовании» видно ввод, вывод, чтение и запись кэша, а также примерную стоимость по вашим ценам за миллион токенов.")
    }
}

@Composable
private fun section(title: String, text: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
