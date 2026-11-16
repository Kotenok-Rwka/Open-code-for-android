package com.pocketcode.assist

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.pocketcode.R
import com.pocketcode.ui.I18n

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** ScrollView, у которого можно ограничить высоту: во всплывающем окне ответ не должен занимать весь экран. */
private class MaxHeightScrollView(ctx: Context) : ScrollView(ctx) {
    var maxHeightPx: Int = Int.MAX_VALUE

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var spec = heightMeasureSpec
        if (maxHeightPx != Int.MAX_VALUE && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY) {
            val size = MeasureSpec.getSize(heightMeasureSpec)
            val limit = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) maxHeightPx else minOf(maxHeightPx, size)
            spec = MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST)
        }
        super.onMeasure(widthMeasureSpec, spec)
    }
}

/** Свой индикатор загрузки: бледное кольцо и бегущая по нему дуга, хорошо видно на любом фоне. */
internal class SpinnerView(ctx: Context, private val color: Int, private val track: Int) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()
    private var angle = 0f
    private val anim = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            angle = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim.start()
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val stroke = minOf(width, height) * 0.13f
        paint.strokeWidth = stroke
        rect.set(stroke / 2, stroke / 2, width - stroke / 2, height - stroke / 2)
        paint.color = track
        canvas.drawOval(rect, paint)
        paint.color = color
        canvas.drawArc(rect, angle, 110f, false, paint)
    }
}

/**
 * Внешний вид окна ассистента, собранный кодом (в окне голосового помощника Compose использовать неудобно).
 * Окно показывает по очереди: ввод вопроса, ход поиска (маленькое окно), предложение «как показать», ответ (окном или на весь экран), ошибку.
 */
class AssistUi(private val ctx: Context, private val host: Host, private val c: AssistColors) {
    interface Host {
        fun close()

        /** Пользователь отправил запрос; [followUp] — уточнение из открытого ответа, [withScreen] — вместе с текстом экрана. */
        fun submit(text: String, followUp: Boolean, withScreen: Boolean)
        fun cancelRun()
        fun toggleMic()
        fun openUrl(url: String)
        fun openChat(text: String)
        fun openSettings(screen: String)
        fun copy(text: String)
        fun share(text: String)

        /** Пользователь отметил «запомнить выбор» в предложении как показать ответ. */
        fun rememberResult(fullscreen: Boolean)

        /** Команда для режима «Управление телефоном»: выполняется в чате приложения. */
        fun runPhoneTask(text: String)

        /** Сохранённые запросы, новые первыми. */
        fun history(): List<HistoryEntry>
        fun deleteHistory(time: Long)
        fun clearHistory()

        /** Озвучить ответ (текст с разметкой) и остановить озвучку. */
        fun speak(markdown: String)
        fun stopSpeaking()
    }

    private val density = ctx.resources.displayMetrics.density
    private val screenH = ctx.resources.displayMetrics.heightPixels
    private val handler = Handler(Looper.getMainLooper())
    private val imm = ctx.getSystemService(InputMethodManager::class.java)

    var phase: Phase = Phase.INPUT
        private set
    private var fullscreen = false
    private var locked = false
    private var prefs = AssistPrefs()
    private var lastState: AssistRunner.State? = null
    private var followFrom: Phase? = null
    private var topInset = 0
    private var bottomInset = 0
    private var screenAvailable = false
    private var baseHint = ""
    private var micTarget: EditText? = null
    private var shownSteps: List<Step>? = null
    private var phoneMode = false
    private lateinit var confirmLayer: View
    private val historyList = LinearLayout(ctx)
    private val historyEmpty = label("Пока пусто: запросы появятся здесь", 14f, c.textDim)
    private val historyScroll = MaxHeightScrollView(ctx)
    private lateinit var historyView: View
    private var restored = false

    // ----- общие части -----
    val root = FrameLayout(ctx)
    private val scrim = View(ctx)
    private val card = LinearLayout(ctx)
    private val content = FrameLayout(ctx)

    // ----- ввод -----
    private val edit = EditText(ctx)
    private val micBtn = iconButton(R.drawable.ic_assist_mic, "Сказать голосом", c.textDim) { onMic(edit) }
    private val sendBtn = filledButton(R.drawable.ic_assist_send, "Искать") { submitMain() }
    private val hintText = label("", 13f, c.textDim)
    private val chips = LinearLayout(ctx)

    // ----- ход поиска -----
    private val ring = SpinnerView(ctx, c.accent, AssistColors.withAlpha(c.accent, 0x33))
    private val workHead = label("", 16f, c.text, bold = true)
    private val workSub = label("", 13f, c.textDim)
    private val detailBox = LinearLayout(ctx)
    private val stepsBox = LinearLayout(ctx)
    private val preview = label("", 13f, c.textDim)
    private val workMeta = label("", 12f, c.textDim)
    private val closeHint = label("Можно закрыть окно: ответ придёт уведомлением", 12f, c.textDim)

    // ----- предложение -----
    private val offerSnippet = label("", 14f, c.textDim)
    private val rememberBox = CheckBox(ctx)

    // ----- ответ -----
    private val ansTitle = label("", 13f, c.textDim)
    private val speakBtn = iconButton(R.drawable.ic_assist_speaker, "Озвучить", c.textDim) { toggleSpeak() }
    private var speaking = false
    private val expandBtn = iconButton(R.drawable.ic_assist_expand, "На весь экран", c.textDim) { toggleFullscreen() }
    private val scroll = MaxHeightScrollView(ctx)
    private val answerText = label("", 16f, c.text)
    private val noteText = label("", 13f, c.textDim)
    private val sourcesTitle = label("Источники", 12f, c.textDim, bold = true)
    private val sourcesBox = LinearLayout(ctx)
    private val chatBtn = iconButton(R.drawable.ic_assist_chat, "Продолжить в чате", c.textDim) { lastState?.let { host.openChat(it.query) } }
    private val flashText = label("", 12f, c.textDim)
    private val followEdit = EditText(ctx)
    private val followMic = iconButton(R.drawable.ic_assist_mic, "Сказать голосом", c.textDim) { onMic(followEdit) }
    private val followSend = filledButton(R.drawable.ic_assist_send, "Отправить") { submitFollow() }
    private lateinit var answerView: LinearLayout

    // ----- ошибка -----
    private val errText = label("", 14f, c.textDim)

    private lateinit var inputView: View
    private lateinit var workView: View
    private lateinit var offerView: View
    private lateinit var errorView: View

    private val ticker = object : Runnable {
        override fun run() {
            if (phase == Phase.WORKING) {
                updateMeta()
                handler.postDelayed(this, 1000)
            }
        }
    }

    init {
        root.layoutDirection = if (I18n.isRtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        scrim.setBackgroundColor(c.scrim)
        scrim.setOnClickListener { requestClose() }
        root.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        card.orientation = LinearLayout.VERTICAL
        card.elevation = dpf(12)
        card.isClickable = true
        card.addView(content, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(card, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        confirmLayer = buildConfirm()
        root.addView(confirmLayer, FrameLayout.LayoutParams(MATCH, MATCH))
        root.setOnApplyWindowInsetsListener { _, ins ->
            topInset = ins.systemWindowInsetTop
            bottomInset = ins.systemWindowInsetBottom
            applyCardLayout()
            ins
        }

        styleEdit(edit, "Спроси что угодно") { submitMain() }
        styleEdit(followEdit, "Уточнить…") { submitFollow() }
        edit.addTextChangedListener(watcher { setEnabled(sendBtn, it.isNotBlank()) })
        followEdit.addTextChangedListener(watcher { setEnabled(followSend, it.isNotBlank()) })
        setEnabled(sendBtn, false)
        setEnabled(followSend, false)

        rememberBox.text = I18n.t("Запомнить мой выбор")
        rememberBox.setTextColor(c.textDim)
        rememberBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        rememberBox.buttonTintList = ColorStateList.valueOf(c.accent)

        inputView = buildInput()
        workView = buildWork()
        offerView = buildOffer()
        answerView = buildAnswer()
        errorView = buildError()
        historyView = buildHistory()
        for (v in listOf(inputView, workView, offerView, answerView, errorView, historyView)) {
            v.visibility = View.GONE
            content.addView(v, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        applyCardLayout()
    }

    // ===== публичное управление окном =====

    /** Начало нового вызова: чистое поле ввода. */
    fun reset(locked: Boolean, prefs: AssistPrefs) {
        this.locked = locked
        this.prefs = prefs
        followFrom = null
        lastState = null
        shownSteps = null
        phoneMode = false
        restored = false
        hideConfirm()
        edit.hint = I18n.t("Спроси что угодно")
        edit.setText("")
        followEdit.setText("")
        setListening(false)
        baseHint = if (locked) I18n.t("Телефон заблокирован: искать можно, а открыть приложения и сайты нельзя") else ""
        applyHint(baseHint)
        chatBtn.visibility = if (locked) View.GONE else View.VISIBLE
        refreshChips()
        // при автоматическом микрофоне клавиатуру не показываем: текст можно ввести, коснувшись поля
        show(inputView, Phase.INPUT, ime = !prefs.autoListen)
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(180).start()
        card.translationY = dpf(48)
        card.alpha = 0f
        card.animate().translationY(0f).alpha(1f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
    }

    fun updatePrefs(p: AssistPrefs) {
        prefs = p
    }

    /** Окно получило текст экрана: можно показать кнопку «Что на экране?». */
    fun setScreenContextAvailable(on: Boolean) {
        screenAvailable = on
        refreshChips()
    }

    /** Состояние запроса изменилось. */
    fun render(s: AssistRunner.State, prefs: AssistPrefs) {
        this.prefs = prefs
        when (s.status) {
            AssistRunner.Status.IDLE -> if (phase == Phase.WORKING) {
                edit.setText(s.query)
                edit.setSelection(edit.text.length)
                show(inputView, Phase.INPUT)
            }
            AssistRunner.Status.RUNNING -> {
                if (phase != Phase.WORKING) {
                    shownSteps = null
                    show(workView, Phase.WORKING)
                    handler.removeCallbacks(ticker)
                    handler.postDelayed(ticker, 1000)
                }
                renderWorking(s)
            }
            AssistRunner.Status.DONE -> if (phase == Phase.WORKING) {
                lastState = s
                present(s)
            }
            AssistRunner.Status.ERROR -> if (phase == Phase.WORKING) {
                lastState = s
                showError(s)
            }
        }
    }

    /** Показывает готовый ответ сразу (окно открыли из уведомления). */
    fun reveal(s: AssistRunner.State, prefs: AssistPrefs) {
        this.prefs = prefs
        lastState = s
        present(s)
    }

    fun setListening(on: Boolean) {
        val bg = if (on) rounded(c.danger, 22) else null
        for (b in listOf(micBtn, followMic)) {
            b.background = ripple(bg, 22)
            b.setColorFilter(if (on) Color.WHITE else c.textDim)
        }
        if (on) {
            applyHint(I18n.t("Слушаю…"))
        } else {
            applyHint(baseHint)
        }
    }

    /** Распознанная речь: промежуточная или итоговая (итоговую сразу отправляем). */
    fun setVoiceText(text: String, final: Boolean) {
        val target = micTarget ?: edit
        target.setText(text)
        target.setSelection(target.text.length)
        if (final) {
            if (target === followEdit) submitFollow(byVoice = true) else submitMain(byVoice = true)
        }
    }

    /** Короткое сообщение вместо подсказки: «Скопировано», ошибка распознавания речи. */
    fun flash(msg: String) {
        if (phase == Phase.INPUT) applyHint(msg) else flashText.text = msg
        handler.removeCallbacks(clearFlash)
        handler.postDelayed(clearFlash, 2500)
    }

    private val clearFlash = Runnable {
        flashText.text = ""
        if (phase == Phase.INPUT) applyHint(baseHint)
    }

    /** Кнопка «Назад»: сначала сворачиваем полноэкранный ответ в окно. true — нажатие обработано. */
    fun handleBack(): Boolean {
        if (confirmLayer.visibility == View.VISIBLE) {
            hideConfirm()
            return true
        }
        if (phase == Phase.FULLSCREEN) {
            show(answerView, Phase.POPUP)
            return true
        }
        if (phase == Phase.HISTORY) {
            show(inputView, Phase.INPUT, ime = false)
            return true
        }
        if (AssistFlow.needsCloseConfirm(phase, prefs)) {
            showConfirm()
            return true
        }
        return false
    }

    /** Подготовка к голосовому вводу при вызове: речь пойдёт в основное поле. */
    fun targetMainInput() {
        micTarget = edit
    }

    /** Закрыть окно; если на экране готовый ответ и включено подтверждение, сначала спросить. */
    private fun requestClose() {
        if (confirmLayer.visibility == View.VISIBLE) return
        if (AssistFlow.needsCloseConfirm(phase, prefs)) showConfirm() else host.close()
    }

    private fun showConfirm() {
        hideIme()
        confirmLayer.visibility = View.VISIBLE
        confirmLayer.alpha = 0f
        confirmLayer.animate().alpha(1f).setDuration(120).start()
    }

    private fun hideConfirm() {
        confirmLayer.visibility = View.GONE
    }

    /** Окно «Закрыть ответ?» поверх всего, чтобы случайное касание не стирало результат. */
    private fun buildConfirm(): View = FrameLayout(ctx).apply {
        visibility = View.GONE
        isClickable = true
        setBackgroundColor(AssistColors.withAlpha(Color.BLACK, 0x99))
        setOnClickListener { hideConfirm() }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(c.surface, 24, c.outline, 1)
            elevation = dpf(16)
            isClickable = true
            setPaddingRelative(dp(20), dp(20), dp(20), dp(16))
            addView(label("Закрыть ответ?", 18f, c.text, bold = true))
            addView(label("Он пропадёт. Чтобы увидеть его снова, придётся повторить запрос.", 14f, c.textDim), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(pillButton("Не закрывать", true) { hideConfirm() }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(8) })
                addView(pillButton("Закрыть", false) { hideConfirm(); host.close() }, LinearLayout.LayoutParams(0, WRAP, 1f))
            }
            addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(18) })
        }
        addView(box, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER).apply { setMargins(dp(24), dp(24), dp(24), dp(24)) })
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        hideConfirm()
        hideIme()
    }

    // ===== сборка экранов =====

    private fun buildInput(): View = column().apply {
        addView(handleBar())
        val title = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_assist_spark)
                setColorFilter(c.accent)
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
            })
            addView(label("Что найти или спросить?", 15f, c.textDim, bold = true).apply {
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(8) }
            })
            addView(iconButton(R.drawable.ic_assist_close, "Закрыть", c.textDim) { requestClose() })
        }
        addView(title, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(inputRow(edit, micBtn, sendBtn), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        hintText.visibility = View.GONE
        addView(hintText, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8); marginStart = dp(6) })
        val scroller = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        chips.orientation = LinearLayout.HORIZONTAL
        scroller.addView(chips, FrameLayout.LayoutParams(WRAP, WRAP))
        addView(scroller, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
    }

    private fun buildWork(): View = column().apply {
        addView(handleBar())
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ring, LinearLayout.LayoutParams(dp(24), dp(24)))
            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                workHead.maxLines = 1
                workHead.ellipsize = TextUtils.TruncateAt.END
                workSub.maxLines = 1
                workSub.ellipsize = TextUtils.TruncateAt.END
                addView(workHead)
                addView(workSub)
            }
            addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(14); marginEnd = dp(6) })
            addView(iconButton(R.drawable.ic_assist_close, "Остановить поиск", c.textDim) { host.cancelRun() })
        }
        addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        detailBox.orientation = LinearLayout.VERTICAL
        detailBox.visibility = View.GONE
        stepsBox.orientation = LinearLayout.VERTICAL
        detailBox.addView(stepsBox, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        preview.maxLines = 3
        preview.ellipsize = TextUtils.TruncateAt.END
        preview.visibility = View.GONE
        detailBox.addView(preview, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        detailBox.addView(workMeta, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        addView(detailBox, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(closeHint, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
    }

    private fun buildOffer(): View = column().apply {
        addView(handleBar())
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_assist_check)
                setColorFilter(c.accent)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = rounded(c.accentSoft, 18)
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            })
            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("Ответ готов", 17f, c.text, bold = true))
                offerSnippet.maxLines = 3
                offerSnippet.ellipsize = TextUtils.TruncateAt.END
                addView(offerSnippet, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })
            }
            addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) })
        }
        addView(head, LinearLayout.LayoutParams(MATCH, WRAP))
        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pillButton("Здесь", false) { choose(false) }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(8) })
            addView(pillButton("На весь экран", true) { choose(true) }, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        addView(buttons, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) })
        addView(rememberBox, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6) })
    }

    private fun buildAnswer(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(18), dp(6), dp(6), 0)
            ansTitle.maxLines = 1
            ansTitle.ellipsize = TextUtils.TruncateAt.END
            addView(ansTitle, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(expandBtn)
            addView(iconButton(R.drawable.ic_assist_close, "Закрыть", c.textDim) { requestClose() })
        }
        addView(header, LinearLayout.LayoutParams(MATCH, WRAP))
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(18), dp(4), dp(18), dp(10))
            answerText.setLineSpacing(dpf(3), 1f)
            answerText.movementMethod = LinkMovementMethod.getInstance()
            answerText.setLinkTextColor(c.accent)
            addView(answerText, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(noteText, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
            addView(sourcesTitle, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) })
            sourcesBox.orientation = LinearLayout.VERTICAL
            addView(sourcesBox, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
        addView(scroll, LinearLayout.LayoutParams(MATCH, WRAP))
        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(10), 0, dp(14), 0)
            addView(iconButton(R.drawable.ic_assist_copy, "Копировать", c.textDim) { lastState?.let { host.copy(plain(it)) } })
            addView(iconButton(R.drawable.ic_assist_share, "Поделиться", c.textDim) { lastState?.let { host.share(plain(it)) } })
            addView(speakBtn)
            addView(chatBtn)
            flashText.gravity = Gravity.END
            addView(flashText, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        addView(actions, LinearLayout.LayoutParams(MATCH, WRAP))
        val follow = inputRow(followEdit, followMic, followSend)
        addView(follow, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(12), dp(4), dp(12), dp(12)) })
    }

    private fun buildError(): View = column().apply {
        addView(handleBar())
        addView(label("Не получилось", 17f, c.text, bold = true))
        errText.maxLines = 6
        errText.ellipsize = TextUtils.TruncateAt.END
        addView(errText, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pillButton("Настройки", false) { host.openSettings("ASSISTANT") }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(8) })
            addView(pillButton("Повторить", true) { lastState?.let { host.submit(it.query, false, false) } }, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        addView(buttons, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) })
    }

    // ===== переходы =====

    private fun show(v: View, p: Phase, ime: Boolean = true) {
        phase = p
        fullscreen = p == Phase.FULLSCREEN
        for (i in 0 until content.childCount) {
            val ch = content.getChildAt(i)
            ch.visibility = if (ch === v) View.VISIBLE else View.GONE
        }
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(140).start()
        expandBtn.setImageResource(if (fullscreen) R.drawable.ic_assist_collapse else R.drawable.ic_assist_expand)
        expandBtn.contentDescription = I18n.t(if (fullscreen) "Свернуть" else "На весь экран")
        applyCardLayout()
        if (p == Phase.INPUT && ime) showIme() else hideIme()
    }

    private fun applyCardLayout() {
        val lp = card.layoutParams as FrameLayout.LayoutParams
        val cl = content.layoutParams as LinearLayout.LayoutParams
        val av = answerView.layoutParams
        if (fullscreen) {
            lp.width = MATCH
            lp.height = MATCH
            lp.setMargins(0, 0, 0, 0)
            card.background = GradientDrawable().apply { setColor(c.surface) }
            card.setPadding(0, topInset, 0, bottomInset)
            cl.height = 0
            cl.weight = 1f
            av?.height = MATCH
            scroll.maxHeightPx = Int.MAX_VALUE
            (scroll.layoutParams as LinearLayout.LayoutParams).apply { height = 0; weight = 1f }
        } else {
            lp.width = MATCH
            lp.height = WRAP
            lp.setMargins(dp(8), 0, dp(8), dp(8) + bottomInset)
            card.background = rounded(c.surface, 28, c.outline, 1)
            card.setPadding(0, 0, 0, 0)
            cl.height = WRAP
            cl.weight = 0f
            av?.height = WRAP
            scroll.maxHeightPx = (screenH * 0.5f).toInt()
            (scroll.layoutParams as LinearLayout.LayoutParams).apply { height = WRAP; weight = 0f }
        }
        card.layoutParams = lp
        content.layoutParams = cl
        if (av != null) answerView.layoutParams = av
    }

    private fun toggleFullscreen() {
        show(answerView, if (phase == Phase.FULLSCREEN) Phase.POPUP else Phase.FULLSCREEN)
    }

    private fun present(s: AssistRunner.State) {
        val target = AssistFlow.afterAnswer(prefs.resultMode, followFrom)
        followFrom = null
        when (target) {
            Phase.OFFER -> showOffer(s)
            Phase.FULLSCREEN -> showAnswer(s, true)
            else -> showAnswer(s, false)
        }
    }

    private fun showOffer(s: AssistRunner.State) {
        offerSnippet.text = if (s.linksOnly) {
            I18n.t("Найдено страниц: ${s.sources.size}")
        } else {
            AssistText.firstSentence(s.answer, 140)
        }
        rememberBox.isChecked = false
        show(offerView, Phase.OFFER)
    }

    private fun choose(full: Boolean) {
        if (rememberBox.isChecked) host.rememberResult(full)
        lastState?.let { showAnswer(it, full) }
    }

    private fun spokenText(s: AssistRunner.State): String =
        if (s.linksOnly) s.sources.take(5).joinToString(". ") { it.title } else s.answer

    private fun toggleSpeak() {
        if (speaking) host.stopSpeaking() else lastState?.let { host.speak(spokenText(it)) }
    }

    /** Озвучка началась или закончилась: кнопка динамика подсвечивается, пока идёт речь. */
    fun setSpeaking(on: Boolean) {
        speaking = on
        speakBtn.setColorFilter(if (on) c.accent else c.textDim)
        speakBtn.contentDescription = I18n.t(if (on) "Остановить озвучку" else "Озвучить")
    }

    /** Быстрый ответ телефона без нейросети: калькулятор, время, заметки. Показывается сразу в окне. */
    fun showQuick(query: String, answer: String) {
        restored = true
        val now = System.currentTimeMillis()
        showAnswer(AssistRunner.State(status = AssistRunner.Status.DONE, query = query, answer = answer, startedAt = now, finishedAt = now), false)
    }

    private fun showAnswer(s: AssistRunner.State, full: Boolean, autoSpeak: Boolean = true) {
        host.stopSpeaking()
        lastState = s
        ansTitle.text = s.query
        if (s.linksOnly) {
            answerText.visibility = if (s.sources.isEmpty()) View.VISIBLE else View.GONE
            answerText.text = I18n.t("Ничего не найдено. Попробуй другой запрос.")
        } else {
            answerText.visibility = View.VISIBLE
            answerText.text = spannable(AssistMarkdown.parse(s.answer))
        }
        noteText.visibility = if (s.noKey) View.VISIBLE else View.GONE
        noteText.text = I18n.t("Ключ API не указан, поэтому показан обычный поиск. Добавь ключ в настройках, чтобы получать готовые ответы.")
        sourcesBox.removeAllViews()
        val list = if (s.linksOnly || prefs.showSources) s.sources else emptyList()
        sourcesTitle.visibility = if (list.isEmpty() || s.linksOnly) View.GONE else View.VISIBLE
        for (h in list) sourcesBox.addView(hitRow(h, s.linksOnly))
        followEdit.setText("")
        flashText.text = ""
        show(answerView, if (full) Phase.FULLSCREEN else Phase.POPUP)
        scroll.scrollTo(0, 0)
        if (autoSpeak && AssistFlow.shouldSpeak(prefs.speakMode, AssistRunner.askedByVoice)) host.speak(spokenText(s))
    }

    private fun showError(s: AssistRunner.State) {
        errText.text = I18n.t(s.error ?: "Неизвестная ошибка")
        show(errorView, Phase.ERROR)
    }

    private fun plain(s: AssistRunner.State): String {
        if (s.linksOnly) return s.sources.joinToString("\n\n") { it.title + "\n" + it.url }
        val sb = StringBuilder(AssistMarkdown.parse(s.answer).text)
        if (s.sources.isNotEmpty()) {
            sb.append("\n")
            for (h in s.sources) sb.append("\n").append(h.url)
        }
        return sb.toString()
    }

    // ===== история =====

    private fun openHistory() {
        refreshHistory()
        show(historyView, Phase.HISTORY)
    }

    private fun buildHistory(): View = column().apply {
        addView(handleBar())
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("История", 17f, c.text, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(pillButton("Очистить", false) { host.clearHistory(); refreshHistory() }.apply { minHeight = dp(36) }, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(iconButton(R.drawable.ic_assist_close, "Закрыть", c.textDim) { show(inputView, Phase.INPUT, ime = false) })
        }
        addView(head, LinearLayout.LayoutParams(MATCH, WRAP))
        historyList.orientation = LinearLayout.VERTICAL
        historyScroll.isVerticalScrollBarEnabled = false
        historyScroll.overScrollMode = View.OVER_SCROLL_NEVER
        historyScroll.maxHeightPx = (screenH * 0.55f).toInt()
        historyScroll.addView(historyList, FrameLayout.LayoutParams(MATCH, WRAP))
        addView(historyScroll, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        addView(historyEmpty, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
    }

    private fun refreshHistory() {
        historyList.removeAllViews()
        val items = host.history()
        historyEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        val fmt = java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault())
        for (e in items) historyList.addView(historyRow(e, fmt.format(java.util.Date(e.time))))
    }

    private fun historyRow(e: HistoryEntry, whenText: String): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = ripple(rounded(c.surfaceHigh, 14), 14)
        setPaddingRelative(dp(14), dp(8), dp(4), dp(8))
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val q = label(e.query, 15f, c.text, bold = true)
            q.maxLines = 2
            q.ellipsize = TextUtils.TruncateAt.END
            addView(q)
            val sub = if (e.linksOnly) I18n.t("Найдено страниц: ${e.sources.size}") else AssistText.firstSentence(e.answer, 120)
            val meta = label(if (sub.isEmpty()) whenText else "$whenText · $sub", 12f, c.textDim)
            meta.maxLines = 2
            meta.ellipsize = TextUtils.TruncateAt.END
            addView(meta, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })
        }
        addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(iconButton(R.drawable.ic_assist_close, "Удалить из истории", c.textDim) {
            host.deleteHistory(e.time)
            refreshHistory()
        })
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
        setOnClickListener { openSaved(e) }
    }

    /** Открывает сохранённый ответ; уточнение к нему начнёт новый разговор. */
    private fun openSaved(e: HistoryEntry) {
        restored = true
        val s = AssistHistory.toState(e)
        showAnswer(s, false, autoSpeak = false)
    }

    // ===== ход поиска =====

    private fun renderWorking(s: AssistRunner.State) {
        val p = prefs.progressMode
        workSub.text = s.query
        workHead.visibility = if (AssistFlow.showHeadline(p)) View.VISIBLE else View.GONE
        workHead.text = I18n.t(AssistProgress.headline(s.steps, s.writing))
        workSub.setTextColor(if (AssistFlow.showHeadline(p)) c.textDim else c.text)
        closeHint.visibility = if (p == ProgressMode.MINIMAL) View.GONE else View.VISIBLE
        closeHint.text = I18n.t(if (prefs.popupWhenReady) "Можно закрыть окно: оно появится снова, когда ответ будет готов" else "Можно закрыть окно: ответ придёт уведомлением")
        val det = AssistFlow.showDetails(p)
        detailBox.visibility = if (det) View.VISIBLE else View.GONE
        if (!det) return
        if (shownSteps != s.steps) {
            shownSteps = s.steps
            stepsBox.removeAllViews()
            for (st in s.steps) stepsBox.addView(stepRow(st))
        }
        val tail = AssistMarkdown.parse(s.answer).text.trim()
        preview.visibility = if (tail.isEmpty()) View.GONE else View.VISIBLE
        if (tail.isNotEmpty()) preview.text = if (tail.length > 200) "…" + tail.takeLast(200) else tail
        lastState = s
        updateMeta()
    }

    private fun updateMeta() {
        val s = lastState ?: return
        if (!AssistFlow.showDetails(prefs.progressMode)) return
        val time = AssistProgress.elapsed(System.currentTimeMillis() - s.startedAt)
        workMeta.text = if (s.hits.isEmpty()) time else time + " · " + I18n.t("Источников: ${s.hits.size}")
    }

    private fun stepRow(st: Step): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val mark: View = if (st.done) {
            ImageView(ctx).apply {
                setImageResource(if (st.failed) R.drawable.ic_assist_close else R.drawable.ic_assist_check)
                setColorFilter(if (st.failed) c.danger else c.accent)
            }
        } else {
            SpinnerView(ctx, c.accent, AssistColors.withAlpha(c.accent, 0x33))
        }
        addView(mark, LinearLayout.LayoutParams(dp(16), dp(16)))
        val t = label(I18n.t(st.label), 13f, if (st.done) c.textDim else c.text)
        t.maxLines = 1
        t.ellipsize = TextUtils.TruncateAt.END
        addView(t, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(10) })
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) }
    }

    // ===== ввод =====

    private fun submitMain(byVoice: Boolean = false) {
        val t = edit.text.toString().trim()
        if (t.isEmpty()) return
        AssistRunner.askedByVoice = byVoice
        followFrom = null
        micTarget = null
        if (phoneMode) host.runPhoneTask(t) else host.submit(t, false, false)
    }

    private fun submitFollow(byVoice: Boolean = false) {
        val t = followEdit.text.toString().trim()
        if (t.isEmpty()) return
        AssistRunner.askedByVoice = byVoice
        followFrom = phase
        micTarget = null
        // уточнение к сохранённому ответу начинает новый разговор: прежнего контекста у модели уже нет
        host.submit(t, !restored, false)
        restored = false
    }

    private fun onMic(target: EditText) {
        micTarget = target
        host.toggleMic()
    }

    private fun refreshChips() {
        chips.removeAllViews()
        if (!locked) {
            chips.addView(chip("Управлять телефоном", phoneMode) { togglePhoneMode() })
        }
        if (!phoneMode) chips.addView(chip("История", false) { openHistory() })
        if (phoneMode) {
            // примеры команд только подставляем в поле: телефон трогаем по явной отправке
            for (s in listOf("Включи фонарик", "Сделай скриншот", "Поставь будильник на 7 утра")) {
                chips.addView(chip(s, false) {
                    edit.setText(I18n.t(s))
                    edit.setSelection(edit.text.length)
                })
            }
            return
        }
        if (screenAvailable && prefs.useScreenText && !locked) {
            chips.addView(chip("Что на экране?", true) { host.submit(I18n.t("Что на экране?"), false, true) })
        }
        for (s in listOf("Погода сегодня", "Новости", "Курс доллара", "Рецепт на ужин")) {
            chips.addView(chip(s, false) {
                edit.setText(I18n.t(s))
                submitMain()
            })
        }
    }

    private fun togglePhoneMode() {
        phoneMode = !phoneMode
        edit.hint = I18n.t(if (phoneMode) "Что сделать на телефоне?" else "Спроси что угодно")
        refreshChips()
    }

    private fun applyHint(msg: String) {
        hintText.text = msg
        hintText.visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showIme() {
        edit.requestFocus()
        handler.postDelayed({ runCatching { imm?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT) } }, 150)
    }

    private fun hideIme() {
        runCatching { imm?.hideSoftInputFromWindow(root.windowToken, 0) }
    }

    // ===== мелкие помощники =====

    private fun dp(v: Int): Int = (v * density + 0.5f).toInt()

    private fun dpf(v: Int): Float = v * density

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dpf(radiusDp)
            if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
        }

    private fun ripple(content: Drawable?, radiusDp: Int): Drawable {
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dpf(radiusDp)
        }
        return RippleDrawable(ColorStateList.valueOf(AssistColors.withAlpha(c.text, 0x22)), content, mask)
    }

    private fun label(s: String, sp: Float, color: Int, bold: Boolean = false): TextView = TextView(ctx).apply {
        text = I18n.t(s)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun column(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPaddingRelative(dp(18), 0, dp(18), dp(18))
    }

    private fun handleBar(): View = View(ctx).apply {
        background = rounded(AssistColors.withAlpha(c.textDim, 0x66), 2)
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(10)
            bottomMargin = dp(8)
        }
    }

    private fun iconButton(res: Int, desc: String, tint: Int, onClick: () -> Unit): ImageView = ImageView(ctx).apply {
        setImageResource(res)
        setColorFilter(tint)
        contentDescription = I18n.t(desc)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(10), dp(10), dp(10), dp(10))
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        background = ripple(null, 22)
        setOnClickListener { onClick() }
    }

    private fun filledButton(res: Int, desc: String, onClick: () -> Unit): ImageView = ImageView(ctx).apply {
        setImageResource(res)
        setColorFilter(c.onAccent)
        contentDescription = I18n.t(desc)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(10), dp(10), dp(10), dp(10))
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4) }
        background = ripple(rounded(c.accent, 22), 22)
        setOnClickListener { onClick() }
    }

    private fun setEnabled(v: View, on: Boolean) {
        v.isEnabled = on
        v.alpha = if (on) 1f else 0.4f
    }

    private fun pillButton(text: String, filled: Boolean, onClick: () -> Unit): TextView = TextView(ctx).apply {
        this.text = I18n.t(text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minHeight = dp(48)
        setPadding(dp(16), 0, dp(16), 0)
        setTextColor(if (filled) c.onAccent else c.accent)
        background = ripple(if (filled) rounded(c.accent, 24) else rounded(Color.TRANSPARENT, 24, c.accent, 1), 24)
        setOnClickListener { onClick() }
    }

    private fun chip(text: String, accent: Boolean, onClick: () -> Unit): TextView = TextView(ctx).apply {
        this.text = I18n.t(text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(if (accent) c.accent else c.text)
        gravity = Gravity.CENTER
        minHeight = dp(40)
        setPaddingRelative(dp(14), 0, dp(14), 0)
        background = ripple(rounded(if (accent) c.accentSoft else c.surfaceHigh, 20), 20)
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun inputRow(e: EditText, mic: ImageView, send: ImageView): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(c.surfaceHigh, 28)
        setPaddingRelative(dp(16), dp(4), dp(6), dp(4))
        addView(e, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(mic)
        addView(send)
    }

    private fun styleEdit(e: EditText, hint: String, onSubmit: () -> Unit) {
        e.hint = I18n.t(hint)
        e.setHintTextColor(c.textDim)
        e.setTextColor(c.text)
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        e.background = null
        e.setPadding(dp(2), dp(12), dp(2), dp(12))
        e.maxLines = 4
        e.setHorizontallyScrolling(false)
        e.setRawInputType(android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        e.imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        e.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_SEND || enter) {
                onSubmit()
                true
            } else {
                false
            }
        }
    }

    private fun watcher(onChange: (String) -> Unit): TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            onChange(s?.toString().orEmpty())
        }
    }

    private fun hitRow(h: SearchHit, withSnippet: Boolean): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = ripple(rounded(c.surfaceHigh, 14), 14)
        setPaddingRelative(dp(14), dp(10), dp(14), dp(10))
        val dom = label(h.domain, 12f, c.textDim)
        dom.maxLines = 1
        dom.ellipsize = TextUtils.TruncateAt.END
        addView(dom)
        val title = label(h.title, 15f, c.accent, bold = true)
        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.END
        addView(title)
        if (withSnippet && h.snippet.isNotBlank()) {
            val sn = label(h.snippet, 13f, c.textDim)
            sn.maxLines = 3
            sn.ellipsize = TextUtils.TruncateAt.END
            addView(sn, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })
        }
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
        setOnClickListener { host.openUrl(h.url) }
    }

    private fun spannable(p: MdParsed): CharSequence {
        val out = SpannableStringBuilder(p.text)
        for (s in p.spans) {
            val st = s.start.coerceIn(0, out.length)
            val en = s.end.coerceIn(st, out.length)
            if (en <= st) continue
            when (s.style) {
                MdStyle.BOLD -> out.setSpan(StyleSpan(Typeface.BOLD), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                MdStyle.HEADING -> {
                    out.setSpan(StyleSpan(Typeface.BOLD), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(RelativeSizeSpan(1.12f), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                MdStyle.CODE -> {
                    out.setSpan(TypefaceSpan("monospace"), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(BackgroundColorSpan(c.surfaceHigh), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                MdStyle.LINK -> {
                    val url = s.url
                    if (url != null) {
                        out.setSpan(
                            object : ClickableSpan() {
                                override fun onClick(widget: View) {
                                    host.openUrl(url)
                                }

                                override fun updateDrawState(ds: TextPaint) {
                                    ds.color = c.accent
                                    ds.isUnderlineText = true
                                }
                            },
                            st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                }
            }
        }
        return out
    }
}
