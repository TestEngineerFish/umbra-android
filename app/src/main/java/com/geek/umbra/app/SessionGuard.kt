package com.geek.umbra.app

import com.geek.umbra.navigation.Router
import com.geek.umbra.net.AuthService
import com.geek.umbra.net.GuardStep
import com.geek.umbra.net.Prefs
import com.geek.umbra.net.SessionGuardRules
import com.geek.umbra.net.SessionLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 会话守卫 —— 对应 iOS App/SessionGuard.swift。
 *
 * 契约 2026-10-10：**未登录 = 单机**。本地功能（提醒、保险箱、常用语、记账生活账本、灵感本机账本）不看登录；
 * 联网功能（长连接、各模块同步、推送）在这里登记「关 / 开」，进出「未登录」时统一执行 ——
 * 不让每个模块各自去盯登录态（各盯各的必然有一处漏关，登出后照样在后台跑）。
 *
 * 顺序：**先写 `umbra.session.signedOut` 标记**（请求闸、小组件、自动填充都读它），再逐项执行。
 * 第一次定下状态时无论如何执行一次（冷启动登着也跑「开」）。
 */
class SessionGuard(
    private val prefs: Prefs,
    private val auth: AuthService,
    private val router: Router,
    scope: CoroutineScope,
) {
    private class Hook(val name: String, val onLock: () -> Unit, val onUnlock: () -> Unit)

    private val hooks = mutableListOf<Hook>()
    private var settled = false
    private val _locked = MutableStateFlow(prefs.signedOut)
    /** 联网功能是否被锁（未登录 / 会话失效）。路由拦截读它。 */
    val locked: StateFlow<Boolean> = _locked
    private val _lock = MutableStateFlow(SessionLock.Undecided)
    val lock: StateFlow<SessionLock> = _lock

    init {
        scope.launch {
            combine(auth.gate, auth.revoked) { g, r -> SessionGuardRules.lock(g, r) }.collect { apply(it) }
        }
    }

    fun register(name: String, onLock: () -> Unit, onUnlock: () -> Unit) {
        hooks += Hook(name, onLock, onUnlock)
    }

    private fun apply(target: SessionLock) {
        _lock.value = target
        when (SessionGuardRules.step(_locked.value, settled, target)) {
            GuardStep.None -> Unit
            GuardStep.Lock -> {
                prefs.setNow(Prefs.K.signedOut, true)
                _locked.value = true
                val wasSettled = settled
                settled = true
                hooks.forEach { runCatching { it.onLock() } }
                if (wasSettled) router.resetForSignedOut() else if (prefs.standalone) router.tab = com.geek.umbra.navigation.Tab.standaloneHome
            }
            GuardStep.Unlock -> {
                prefs.setNow(Prefs.K.signedOut, false)
                _locked.value = false
                settled = true
                hooks.forEach { runCatching { it.onUnlock() } }
            }
        }
    }
}
