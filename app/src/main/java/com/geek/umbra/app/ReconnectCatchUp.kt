package com.geek.umbra.app

import com.geek.umbra.net.ReconnectRules
import com.geek.umbra.net.SocketState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 长连接从断线回到在线 → 各模块补同步一轮（geek-13 11-12）。一处统一：模块只登记「补一轮要做什么」，
 * 何时算重连、怎么节流都在这里（判据 [ReconnectRules]，有用例）。
 *
 * 切后台超过 60 秒 UmbraApp 会主动断开长连接、回前台再连上，所以「回前台」也走这一路，不单做。
 * 守卫「关」（登出）时清掉记忆：再登录那一次在线算第一次，不补（解锁那一刻各模块已经同步过）。
 */
object ReconnectCatchUp {
    private val actions = mutableListOf<Pair<String, () -> Unit>>()
    private var wasOnline: Boolean? = null
    private var lastCatchUpMs: Long? = null
    private var pending: Job? = null
    private var started = false

    /** 模块登记补同步那一下（在各自 XxxModule.register 里调）。[action] 自己起协程，别在这里阻塞。 */
    fun register(name: String, action: () -> Unit) { actions += name to action }

    /** AppGraph 初始化时调一次（FeatureGraph.registerGuards 之前）。 */
    fun start(guard: SessionGuard) {
        if (started) return
        started = true
        guard.register("reconnect-catch-up", onLock = { wasOnline = null; pending?.cancel(); pending = null }, onUnlock = {})
        AppGraph.scope.launch {
            AppGraph.socket.state.collect { st ->
                val on = st == SocketState.Online
                if (ReconnectRules.isReconnect(wasOnline, on)) schedule()
                wasOnline = ReconnectRules.nextWasOnline(wasOnline, on)
            }
        }
    }

    /** 排一次补同步；已经排着一次（还在等间隔满）就不再排 —— 抖动期间的几次重连合并成一次。 */
    private fun schedule() {
        if (pending?.isActive == true) return
        val wait = ReconnectRules.delayMs(lastCatchUpMs, System.currentTimeMillis())
        pending = AppGraph.scope.launch {
            if (wait > 0) delay(wait)
            // 等的这段时间又断了：不补，下一次重连再排。
            if (AppGraph.socket.state.value != SocketState.Online) return@launch
            lastCatchUpMs = System.currentTimeMillis()
            // 各模块自己起协程、自己标后台路径（它们原有的同步函数本来就是后台性质）。一个模块出错不拦着别的。
            actions.forEach { (_, a) -> runCatching { a() } }
        }
    }
}
