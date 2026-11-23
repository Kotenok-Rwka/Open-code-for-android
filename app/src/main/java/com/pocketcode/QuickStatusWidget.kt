package com.pocketcode

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews

/** Виджет со статусом агента: обновляется при запуске/остановке задачи. */
class QuickStatusWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val app = context.applicationContext as? PocketCodeApp
        val status = app?.controller?.let { vm ->
            when {
                vm.running -> "Агент работает"
                vm.approval != null -> "Нужно подтверждение"
                vm.freeStartBusy || vm.linuxBusy -> "Установка…"
                else -> "PocketCode готов"
            }
        } ?: "PocketCode"
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_status)
            views.setTextViewText(R.id.widget_text, status)
            manager.updateAppWidget(id, views)
        }
    }

    companion object {
        fun refresh(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(
                android.content.ComponentName(ctx, QuickStatusWidget::class.java),
            )
            if (ids.isNotEmpty()) QuickStatusWidget().onUpdate(ctx, manager, ids)
        }
    }
}
