package com.geek.umbra.feature.remote

import com.geek.umbra.app.SessionGuard

/**
 * 远程控制模块的进程级入口（iOS: RemoteClient.swift:59 `RemoteCenter.bind` 里那句 `SessionGuard.shared.register`）。
 *
 * - 守卫「关」（登出 / 会话失效）= 结束进行中的会话：远程是联网功能，登出之后它还连着就是「登出了功能还能用」；
 * - 「开」什么都不做：会话只由人点入口发起，没有要恢复的后台任务；
 * - 顺手接上长连接的 rc_* 帧、连接状态与前后台（[RemoteCenter.bind]，只接一次）。
 */
object RemoteModule {
    fun register(guard: SessionGuard) {
        RemoteCenter.bind()
        guard.register("远程控制", onLock = { RemoteCenter.close() }, onUnlock = {})
    }
}
