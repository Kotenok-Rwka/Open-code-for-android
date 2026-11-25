package com.pocketcode.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.pocketcode.MainActivity
import com.pocketcode.R

/** Виджет «Продолжить задачу»: открывает приложение одним тапом. */
class PocketWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val launch = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val views = RemoteViews(context.packageName, R.layout.pocket_widget).apply {
            setOnClickPendingIntent(R.id.widget_root, launch)
        }
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    companion object {
        fun componentName(context: Context) = ComponentName(context, PocketWidgetProvider::class.java)
    }
}
