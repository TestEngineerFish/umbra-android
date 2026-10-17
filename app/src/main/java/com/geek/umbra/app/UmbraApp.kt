package com.geek.umbra.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class UmbraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        AppForeground.install()
        AppGraph.scope.launch { AppGraph.auth.decideGate() }
    }
}

/**
 * 前后台。长连接在前台时保持；退到后台 60 秒后断开 —— Android 不像 iOS 会冻结进程，
 * 一直连着会持续耗电，而没有服务端推送（FCM 待服务端）时后台连着也收不到可显示的东西。
 * 回前台立刻重连并补拉。
 */
object AppForeground {
    @Volatile var isForeground = false; private set
    private var stopJob: Job? = null
    val listeners = mutableListOf<(Boolean) -> Unit>()

    fun install() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                isForeground = true
                stopJob?.cancel()
                if (!AppGraph.guard.locked.value) AppGraph.socket.start()
                listeners.forEach { runCatching { it(true) } }
            }

            override fun onStop(owner: LifecycleOwner) {
                isForeground = false
                stopJob = AppGraph.scope.launch { delay(60_000); if (!isForeground) AppGraph.socket.stop() }
                listeners.forEach { runCatching { it(false) } }
            }
        })
    }
}
