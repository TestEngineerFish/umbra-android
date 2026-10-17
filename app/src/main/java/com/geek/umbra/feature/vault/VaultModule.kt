package com.geek.umbra.feature.vault

import com.geek.umbra.app.SessionGuard

/**
 * 保险箱模块的进程级入口。AppGraph 初始化时由 FeatureGraph 调一次。
 *
 * **不在会话守卫上登记「关」**：2026-10-10 起保险箱是本地功能（有自己的主密码），登出不上锁、不清缓存
 * （iOS AppShell.swift:272 同）；同步那一截由统一出口的请求闸拦成「离线」，走本机缓存。
 * 这里只挂保险箱自己的外壳守卫（自动上锁驱动 + 上锁退栈），它们跟着「保险箱开着」走，不跟登录走。
 */
object VaultModule {
    fun register(guard: SessionGuard) {
        VaultSession.install()
    }
}
