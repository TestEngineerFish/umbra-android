package com.geek.umbra.feature.money

import com.geek.umbra.app.AppGraph
import com.geek.umbra.app.SessionGuard
import com.geek.umbra.net.LoginState
import kotlinx.coroutines.launch

/**
 * 记账模块的进程级入口：在会话守卫上登记联网部分的「关 / 开」。AppGraph 初始化时由 FeatureGraph 调一次。
 *
 * 生活账本是本机账本，**不看登录**：守卫只管它的同步（开 = 开始同步并补推，关 = 停推，本机账一笔不删）。
 * 经营账本始终在线，未登录时页面自己画「要登录」。iOS: AppShell.swift:293「记账同步」。
 */
object MoneyModule {
    fun register(guard: SessionGuard) {
        guard.register(
            "记账同步",
            onLock = { MoneyLocal.sessionLocked() },
            onUnlock = {
                MoneyLocal.sessionUnlocked()
                AppGraph.scope.launch { MoneyStore.syncLocalThenRefresh() }
            },
        )
        // 守卫的「开」可能早于 /auth/me 回来 —— 那一轮认不出是谁就不同步（换账号判不了）。
        // 认出人那一刻补一轮（iOS AppShell.swift:159）；sync 自己互斥，多叫一次不会多跑一轮。
        AppGraph.scope.launch {
            var lastUser: Long? = null
            AppGraph.auth.login.collect { s ->
                val u = (s as? LoginState.SignedIn)?.user?.id
                if (u != null && u != lastUser) MoneyStore.syncLocalThenRefresh()
                lastUser = u
            }
        }
    }
}
