package com.pocketcode

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import com.pocketcode.ui.ChatController

class PocketCodeApp : Application() {
    /** Сколько окон приложения сейчас на экране; по нему хостинг понимает, открыто ли приложение. */
    @Volatile
    private var startedActivities = 0

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) { startedActivities++ }
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) { startedActivities = (startedActivities - 1).coerceAtLeast(0) }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** Хостинг проектов (боты, серверы): запускается по расписанию, фоном или пока приложение открыто. */
    val host: com.pocketcode.host.HostManager by lazy {
        com.pocketcode.host.HostManager(
            this,
            { command, dir -> controller.hostProcessBuilder(command, dir) },
            { name -> controller.hostProjectDir(name) },
            { startedActivities > 0 },
        )
    }

    /** Основной чат: агент не зависит от жизни экрана. */
    val controller: ChatController by lazy { ChatController(this) }

    /** Все вкладки; первая — основной чат. */
    val tabs by lazy { mutableStateListOf(controller) }
    var activeTab by mutableIntStateOf(0)

    val current: ChatController get() = tabs.getOrNull(activeTab) ?: controller

    private var nextNo = 1

    /** Новая вкладка с отдельным чатом; профиль API можно выбрать в самой вкладке. */
    fun addTab() {
        val cur = current
        tabs += ChatController(this, nextNo++, cur.activeProfileId, cur.currentProject)
        activeTab = tabs.lastIndex
    }

    fun closeTab(i: Int) {
        if (i <= 0 || i >= tabs.size) return
        tabs[i].stop()
        tabs.removeAt(i)
        activeTab = activeTab.coerceAtMost(tabs.lastIndex).let { if (it >= i) (it - 1).coerceAtLeast(0) else it }
    }
}
