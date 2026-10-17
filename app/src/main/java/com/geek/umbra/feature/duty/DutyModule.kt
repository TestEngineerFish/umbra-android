package com.geek.umbra.feature.duty

import com.geek.umbra.app.AppGraph
import com.geek.umbra.app.SessionGuard
import kotlinx.coroutines.launch

/**
 * 执勤模块的进程级入口：在会话守卫上登记联网部分的「关 / 开」，并把长连接里的 duty_* 帧转给 [DutyStore]。
 * AppGraph 初始化时由 FeatureGraph 调一次。
 *
 * - 关（登出 / 会话失效 / 换号）= 清空：上一个人的执勤设置不许留在屏上（iOS DutyStore.swift:76）；
 * - 开：不预取 —— 进页自己会拉（同 iOS）。
 * - 长连接帧在这里接（iOS 是 ChatViewModel.handleMessage 转进 DutyStore）：Android 的帧流是广播，各模块自己挑，
 *   不经过聊天模块，聊天那边就不用知道执勤。登出时长连接本身停了，这个收集器空转不花钱。
 */
object DutyModule {
    private var collecting = false

    fun register(guard: SessionGuard) {
        guard.register("执勤", onLock = { DutyStore.clear() }, onUnlock = {})
        if (!collecting) {
            collecting = true
            AppGraph.scope.launch {
                AppGraph.socket.frames.collect { f -> runCatching { DutyStore.handleFrame(f) } }
            }
        }
    }
}
