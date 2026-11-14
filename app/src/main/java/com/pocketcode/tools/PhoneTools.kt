// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.tools

import com.pocketcode.llm.Block
import com.pocketcode.phone.PhoneConfirm
import com.pocketcode.phone.PhoneController
import com.pocketcode.phone.PhoneSafety
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/** Инструмент, который может приложить к ответу картинки (скриншоты); агент забирает их после вызова. */
interface ImageProducer {
    fun takeImages(): List<Block.Image>
}

/** Основа инструментов управления телефоном: подтверждения по режиму и результат вместе с новым состоянием экрана. */
abstract class PhoneTool(
    protected val phone: PhoneController,
    private val confirm: () -> PhoneConfirm,
) : Tool, ImageProducer {
    /** Только смотрит или ждёт, ничего не меняет на телефоне. */
    protected open val readOnly: Boolean = false

    /** Рискованно ли именно это действие (по тексту элемента, адресу и т.п.). */
    protected open fun risky(input: JsonObject): Boolean = false

    /** Только читает именно этот вызов (например, phone_app list); по умолчанию — свойство [readOnly]. */
    protected open fun readOnly(input: JsonObject): Boolean = readOnly

    @Volatile private var riskyNow = false
    @Volatile private var readOnlyNow = false
    private val images = ArrayList<Block.Image>()

    override fun needsApproval(input: JsonObject): Boolean {
        riskyNow = risky(input)
        readOnlyNow = readOnly(input)
        return PhoneSafety.needsConfirm(confirm(), readOnlyNow, riskyNow)
    }

    // «выполнять без подтверждения» не отменяет вопрос: управление телефоном спрашивает по своему режиму
    override val alwaysAsk: Boolean get() = !readOnlyNow && (confirm() == PhoneConfirm.EVERY || riskyNow)

    @Synchronized override fun takeImages(): List<Block.Image> = images.toList().also { images.clear() }

    @Synchronized protected fun addImage(jpeg: ByteArray) {
        images += Block.Image("image/jpeg", android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP))
    }

    /** Выполняет действие: [fail] — причина неудачи (null — успех); после успеха возвращает текст нового экрана. */
    protected suspend fun act(fail: suspend () -> String?): String {
        // на фоновом экране следим, не откроется ли приложение у пользователя на основном (см. PhoneController.beginAction)
        phone.beginAction()
        fail()?.let { throw IllegalStateException(it) }
        phone.settle()
        val spill = phone.endAction()
        return "Готово.\n" + (spill?.let { it + "\n" } ?: "") + phone.look(withImage = false).text
    }

    protected fun JsonObject.node(): Int? = int("node")
    protected fun JsonObject.xy(): Pair<Int, Int> =
        (int("x") ?: throw IllegalArgumentException("Укажи node или x и y")) to (int("y") ?: throw IllegalArgumentException("Укажи node или x и y"))

    protected fun nodeLabel(input: JsonObject): String =
        input.node()?.let { phone.nodeById(it) }?.let { n -> n.text.ifBlank { n.desc }.ifBlank { n.resId }.ifBlank { n.cls } + " (№${n.id})" }
            ?: input.node()?.let { "№$it" } ?: ""
}

class PhoneLookTool(phone: PhoneController, confirm: () -> PhoneConfirm, private val screenshots: () -> Boolean = { true }) : PhoneTool(phone, confirm) {
    override val name = "phone_look"
    override val description =
        "Показывает текущий экран телефона: список элементов с номерами и координатами (текст) и скриншот. " +
            "Вызывай перед действиями и после них, чтобы убедиться в результате. Номера элементов действуют до следующего phone_look."
    override val schema = objSchema(Prop("image", "boolean", "Прикладывать скриншот (по умолчанию true)", required = false))
    override val readOnly = true
    override fun describe(input: JsonObject): String { needsApproval(input); return "Смотрю на экран телефона" }
    override suspend fun run(input: JsonObject): String {
        val r = phone.look(withImage = input.bool("image") != false && screenshots())
        r.jpeg?.let { addImage(it) }
        return r.text + if (r.jpeg == null && screenshots()) "\n(скриншот недоступен, ориентируйся по списку элементов)" else ""
    }
}

class PhoneTapTool(phone: PhoneController, confirm: () -> PhoneConfirm) : PhoneTool(phone, confirm) {
    override val name = "phone_tap"
    override val description = "Нажимает на экране телефона: на элемент по номеру из phone_look (node) или в точку (x, y в пикселях экрана)."
    override val schema = objSchema(
        Prop("node", "integer", "Номер элемента из phone_look", required = false),
        Prop("x", "integer", "X в пикселях", required = false),
        Prop("y", "integer", "Y в пикселях", required = false),
    )
    override fun risky(input: JsonObject) = PhoneSafety.riskyNode(input.node()?.let { phone.nodeById(it) })
    override fun describe(input: JsonObject): String {
        needsApproval(input)
        return "Нажать " + nodeLabel(input).ifEmpty { "(${input.int("x")}, ${input.int("y")})" }
    }
    override suspend fun run(input: JsonObject): String = act {
        val n = input.node()
        if (n != null) { if (phone.tapNode(n)) null else "Не удалось нажать элемент $n. Вызови phone_look и проверь номер." }
        else { val (x, y) = input.xy(); if (phone.tap(x, y)) null else "Не удалось нажать в точку ($x, $y)." }
    }
}

class PhoneLongPressTool(phone: PhoneController, confirm: () -> PhoneConfirm) : PhoneTool(phone, confirm) {
    override val name = "phone_long_press"
    override val description = "Долгое нажатие на элемент (node) или в точку (x, y)."
    override val schema = objSchema(
        Prop("node", "integer", "Номер элемента из phone_look", required = false),
        Prop("x", "integer", "X в пикселях", required = false),
        Prop("y", "integer", "Y в пикселях", required = false),
    )
    override fun risky(input: JsonObject) = PhoneSafety.riskyNode(input.node()?.let { phone.nodeById(it) })
    override fun describe(input: JsonObject): String {
        needsApproval(input)
        return "Долго нажать " + nodeLabel(input).ifEmpty { "(${input.int("x")}, ${input.int("y")})" }
    }
    override suspend fun run(input: JsonObject): String = act {
        val n = input.node()
        if (n != null) { if (phone.longPressNode(n)) null else "Не удалось нажать элемент $n." }
        else { val (x, y) = input.xy(); if (phone.longPress(x, y)) null else "Не удалось нажать в точку ($x, $y)." }
    }
}

class PhoneSwipeTool(phone: PhoneController, confirm: () -> PhoneConfirm) : PhoneTool(phone, confirm) {
    override val name = "phone_swipe"
    override val description =
        "Свайп по экрану: из (x1, y1) в (x2, y2). Чтобы листать список вниз по содержимому, свайпай снизу вверх. " +
            "Либо прокрути элемент по номеру: node и forward (true — вперёд/вниз, false — назад/вверх)."
    override val schema = objSchema(
        Prop("x1", "integer", "Откуда X", required = false), Prop("y1", "integer", "Откуда Y", required = false),
        Prop("x2", "integer", "Куда X", required = false), Prop("y2", "integer", "Куда Y", required = false),
        Prop("duration_ms", "integer", "Длительность, мс (по умолчанию 300)", required = false),
        Prop("node", "integer", "Прокручиваемый элемент", required = false),
        Prop("forward", "boolean", "Направление прокрутки элемента", required = false),
    )
    override fun describe(input: JsonObject): String {
        needsApproval(input)
        return input.node()?.let { "Прокрутить ${nodeLabel(input)} " + if (input.bool("forward") == false) "назад" else "вперёд" }
            ?: "Свайп (${input.int("x1")}, ${input.int("y1")}) → (${input.int("x2")}, ${input.int("y2")})"
    }
    override suspend fun run(input: JsonObject): String = act {
        val n = input.node()
        if (n != null) {
            if (phone.scrollNode(n, input.bool("forward") != false)) null else "Элемент $n не прокручивается."
        } else {
            fun need(k: String) = input.int(k) ?: throw IllegalArgumentException("Укажи x1, y1, x2, y2 или node")
            if (phone.swipe(need("x1"), need("y1"), need("x2"), need("y2"), (input.int("duration_ms") ?: 300).toLong())) null else "Свайп не удался."
        }
    }
}

class PhoneTypeTool(phone: PhoneController, confirm: () -> PhoneConfirm) : PhoneTool(phone, confirm) {
    override val name = "phone_type"
    override val description =
        "Вводит текст. С node — заменяет текст в этом поле; без node — вводит в поле, на котором сейчас фокус (сначала нажми на него). " +
            "В поля паролей вводить нельзя: это делает пользователь."
    override val schema = objSchema(
        Prop("text", "string", "Текст"),
        Prop("node", "integer", "Номер поля из phone_look", required = false),
    )
    override fun describe(input: JsonObject): String {
        needsApproval(input)
        return "Ввести «" + input.str("text").orEmpty().take(60) + "»"
    }
    override suspend fun run(input: JsonObject): String = act { phone.typeText(input.need("text"), input.node()) }
}

class PhoneKeyTool(phone: PhoneController, confirm: () -> PhoneConfirm) : PhoneTool(phone, confirm) {
    override val name = "phone_key"
    override val description =
        "Нажимает кнопку: back, home, recents, notifications, quick_settings, enter, delete, tab, escape, search, menu, " +
            "up/down/left/right, volume_up, volume_down, page_up, page_down, space."
    override val schema = objSchema(Prop("key", "string", "Имя кнопки"))
    override fun describe(input: JsonObject): String {
        needsApproval(input)
        return "Кнопка «" + input.str("key").orEmpty() + "»"
    }
    override suspend fun run(input: JsonObject): String = act { phone.key(input.need("key")) }
}

class PhoneAppTool(phone: PhoneController, confirm: () -> PhoneConfirm) : PhoneTool(phone, confirm) {
    override val name = "phone_app"
    override val description =
        "Приложения и экран: action=launch (value — название или пакет), open_url (value — адрес http/https), list (список установленных; " +
            "value — фильтр по названию), background_on / background_off (отдельный фоновый экран, не мешающий пользователю; нужен помощник)."
    override val schema = objSchema(
        Prop("action", "string", "launch | open_url | list | background_on | background_off"),
        Prop("value", "string", "Название, пакет, адрес или фильтр", required = false),
        Prop("background", "boolean", "Для launch: открыть на фоновом экране (включит его сам)", required = false),
    )
    override fun risky(input: JsonObject) =
        input.str("action") == "open_url" && PhoneSafety.riskyUrl(input.str("value").orEmpty())

    // список приложений ничего не меняет
    override fun readOnly(input: JsonObject) = input.str("action") == "list"

    override fun describe(input: JsonObject): String {
        needsApproval(input)
        return when (input.str("action")) {
            "launch" -> "Открыть приложение «${input.str("value").orEmpty()}»"
            "open_url" -> "Открыть ${input.str("value").orEmpty().take(100)}"
            "list" -> "Список приложений"
            "background_on" -> "Включить фоновый экран"
            "background_off" -> "Выключить фоновый экран"
            else -> "phone_app"
        }
    }

    override suspend fun run(input: JsonObject): String {
        val value = input.str("value").orEmpty()
        return when (input.need("action")) {
            "launch" -> { require(value.isNotBlank()) { "Не указано приложение" }; act { phone.launch(value, input.bool("background") == true) } }
            "open_url" -> {
                require(value.startsWith("http://") || value.startsWith("https://") || PhoneSafety.riskyUrl(value)) { "Нужен адрес http/https" }
                act { phone.openUrl(value) }
            }
            "list" -> {
                val f = value.trim().lowercase()
                val apps = phone.listApps().filter { f.isEmpty() || it.label.lowercase().contains(f) || it.pkg.lowercase().contains(f) }
                if (apps.isEmpty()) "Ничего не найдено."
                else apps.sortedBy { it.label.lowercase() }.take(150).joinToString("\n") { "${it.label} — ${it.pkg}" +
                    if (it.system) " (системное)" else "" }
            }
            "background_on" -> {
                val id = phone.openBackground(1080, 1920, 420)
                if (id <= 0) throw IllegalStateException("Фоновый экран недоступен: нужен помощник (root или беспроводная отладка).")
                "Фоновый экран включён (displayId=$id). Теперь все действия идут на нём; запусти приложение через phone_app launch."
            }
            "background_off" -> { phone.closeBackground(); "Фоновый экран выключен, управление вернулось на основной экран." }
            else -> throw IllegalArgumentException("Неизвестное действие")
        }
    }
}

class PhoneWaitTool(phone: PhoneController, confirm: () -> PhoneConfirm) : PhoneTool(phone, confirm) {
    override val name = "phone_wait"
    override val description = "Ждёт указанное число миллисекунд (до 10000), например пока загрузится экран."
    override val schema = objSchema(Prop("ms", "integer", "Миллисекунды"))
    override val readOnly = true
    override fun describe(input: JsonObject): String { needsApproval(input); return "Жду ${input.int("ms") ?: 1000} мс" }
    override suspend fun run(input: JsonObject): String {
        delay((input.int("ms") ?: 1000).coerceIn(0, 10_000).toLong())
        return "Готово."
    }
}

/** Все инструменты режима. [confirm] читается при каждом вызове, поэтому смена настройки действует сразу. */
fun phoneTools(phone: PhoneController, confirm: () -> PhoneConfirm, screenshots: () -> Boolean = { true }): List<Tool> = listOf(
    PhoneLookTool(phone, confirm, screenshots), PhoneTapTool(phone, confirm), PhoneLongPressTool(phone, confirm),
    PhoneSwipeTool(phone, confirm), PhoneTypeTool(phone, confirm), PhoneKeyTool(phone, confirm),
    PhoneAppTool(phone, confirm), PhoneWaitTool(phone, confirm),
)
