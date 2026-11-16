package com.pocketcode.assist

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.pocketcode.SettingsStore
import com.pocketcode.ui.I18n

/**
 * Индикатор работы ассистента, пока окно закрыто: маленькая плашка поверх других приложений (если дано разрешение и она включена)
 * или, иначе, уведомление. Касание плашки открывает окно ассистента.
 */
object AssistIndicator {
    private val handler = Handler(Looper.getMainLooper())

    // Всё ниже трогаем только из главного потока (через handler)
    private var pill: View? = null
    private var pillText: TextView? = null
    private var pillShown = ""

    /** Можно ли показывать плашку: включено в настройках и выдано разрешение «Поверх других окон». */
    fun canOverlay(ctx: Context, prefs: AssistPrefs): Boolean = prefs.overlayIndicator && Settings.canDrawOverlays(ctx)

    fun update(ctx: Context, s: AssistRunner.State, sessionVisible: Boolean, prefs: AssistPrefs) {
        val app = ctx.applicationContext
        if (!AssistFlow.indicatorActive(s.status, sessionVisible)) {
            hide(app)
            return
        }
        val head = I18n.t(AssistProgress.headline(s.steps, s.writing))
        if (canOverlay(app, prefs)) {
            AssistNotifier.cancelProgress(app)
            handler.post { if (!showPill(app, head)) AssistNotifier.progress(app, s.query, head) }
        } else {
            handler.post { removePill(app) }
            AssistNotifier.progress(app, s.query, head)
        }
    }

    fun hide(ctx: Context) {
        val app = ctx.applicationContext
        AssistNotifier.cancelProgress(app)
        handler.post { removePill(app) }
    }

    private fun showPill(app: Context, head: String): Boolean {
        val existing = pill
        if (existing != null) {
            if (head != pillShown) {
                pillText?.text = head
                pillShown = head
            }
            return true
        }
        return try {
            val c = AssistTheme.load(app, SettingsStore(app))
            val d = app.resources.displayMetrics.density
            val text = TextView(app).apply {
                this.text = head
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(c.text)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = (app.resources.displayMetrics.widthPixels * 0.6f).toInt()
            }
            val view = LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = if (I18n.isRtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                background = GradientDrawable().apply {
                    setColor(c.surface)
                    cornerRadius = 24 * d
                    setStroke((1 * d).toInt().coerceAtLeast(1), c.outline)
                }
                elevation = 8 * d
                setPaddingRelative((12 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
                addView(SpinnerView(app, c.accent, AssistColors.withAlpha(c.accent, 0x33)), LinearLayout.LayoutParams((18 * d).toInt(), (18 * d).toInt()).apply { marginEnd = (8 * d).toInt() })
                addView(text)
                setOnClickListener { PocketAssistService.show() }
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (8 * d).toInt()
            }
            app.getSystemService(WindowManager::class.java).addView(view, lp)
            pill = view
            pillText = text
            pillShown = head
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun removePill(app: Context) {
        val v = pill ?: return
        pill = null
        pillText = null
        pillShown = ""
        runCatching { app.getSystemService(WindowManager::class.java).removeView(v) }
    }
}
