package com.pocketcode.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Окно подтверждения поверх других приложений.
 * Решает проблему «вернулся в PocketCode — экран уже другой»: пользователь нажимает
 * Разрешить/Отклонить прямо на том экране, где агент хочет действовать.
 */
class ApprovalOverlay : Service() {
    companion object {
        const val ACTION_SHOW = "com.pocketcode.approval.SHOW"
        const val ACTION_HIDE = "com.pocketcode.approval.HIDE"
        const val EXTRA_TITLE = "title"
        const val EXTRA_DESCRIPTION = "description"
        const val EXTRA_ALLOW = "allow"
        const val EXTRA_DENY = "deny"
        const val EXTRA_TOOL = "tool"
        @Volatile var active = false
            private set

        fun show(ctx: Context, tool: String, title: String, description: String, onAllow: () -> Unit, onDeny: () -> Unit): Boolean {
            if (!Settings.canDrawOverlays(ctx)) return false
            callbacks = onAllow to onDeny
            val i = Intent(ctx, ApprovalOverlay::class.java).apply {
                action = ACTION_SHOW
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_DESCRIPTION, description.take(600))
                putExtra(EXTRA_TOOL, tool)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startForegroundService(i)
            active = true
            return true
        }

        fun hide(ctx: Context) {
            callbacks = null
            active = false
            runCatching { ctx.stopService(Intent(ctx, ApprovalOverlay::class.java)) }
        }

        /** Инструменты, разрешённые на текущую сессию (в памяти процесса). */
        private val sessionAllowed = java.util.Collections.synchronizedSet(HashSet<String>())

        fun isSessionAllowed(tool: String) = sessionAllowed.contains(tool)
        fun allowSession(tool: String) { sessionAllowed.add(tool) }
        fun clearSession() { sessionAllowed.clear() }

        private var callbacks: Pair<() -> Unit, () -> Unit>? = null
        fun resolve(allow: Boolean) {
            val cb = callbacks ?: return
            callbacks = null
            active = false
            if (allow) cb.first() else cb.second()
        }
    }

    private var root: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(77, foregroundNotification())
        if (intent?.action == ACTION_HIDE) { removeOverlay(); stopSelf(); return START_NOT_STICKY }
        if (intent?.action != ACTION_SHOW) return START_NOT_STICKY
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        removeOverlay()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val ctx = this
        val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#22262E")); setPadding(24, 20, 24, 20) }
        val title = TextView(ctx).apply {
            text = "PocketCode: ${intent.getStringExtra(EXTRA_TITLE) ?: "разрешить действие?"}"
            setTextColor(Color.WHITE); textSize = 16f
        }
        val desc = TextView(ctx).apply {
            text = intent.getStringExtra(EXTRA_DESCRIPTION) ?: ""
            setTextColor(Color.parseColor("#D0D4DC")); textSize = 13f
            setPadding(0, 12, 0, 12)
            maxLines = 12
        }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val tool = intent.getStringExtra(EXTRA_TOOL).orEmpty()
        val deny = Button(ctx).apply { text = "Отклонить"; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        val allow = Button(ctx).apply { text = "Разрешить"; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        val always = Button(ctx).apply { text = "На сессию"; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        deny.setOnClickListener { resolve(false); removeOverlay(); stopSelf() }
        allow.setOnClickListener { resolve(true); removeOverlay(); stopSelf() }
        always.setOnClickListener { if (tool.isNotBlank()) allowSession(tool); resolve(true); removeOverlay(); stopSelf() }
        row.addView(deny); row.addView(allow); row.addView(always)
        layout.addView(title); layout.addView(desc); layout.addView(row)
        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.9f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 80 }
        layout.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { params.x += 0; true }
                MotionEvent.ACTION_MOVE -> { wm.updateViewLayout(v, params); true }
                else -> false
            }
        }
        runCatching { wm.addView(layout, params); root = layout }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (ApprovalOverlay.active) {
                resolve(false)
                removeOverlay()
                stopSelf()
            }
        }, 60_000L)
        return START_NOT_STICKY
    }

    private fun foregroundNotification(): android.app.Notification {
        val channel = "pocketcode_approval"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            if (nm.getNotificationChannel(channel) == null) {
                nm.createNotificationChannel(android.app.NotificationChannel(channel, "Подтверждения", android.app.NotificationManager.IMPORTANCE_LOW))
            }
        }
        return androidx.core.app.NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("PocketCode ждёт подтверждения")
            .setOngoing(true)
            .build()
    }

    private fun removeOverlay() {
        val v = root ?: return
        runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) }
        root = null
    }

    override fun onDestroy() { removeOverlay(); super.onDestroy() }
}
