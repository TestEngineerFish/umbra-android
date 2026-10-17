package com.geek.umbra.feature.widget

import com.geek.umbra.app.SessionGuard

/**
 * 小组件 / 进行中通知在会话守卫上的「关 / 开」（iOS: App/SessionGuard.swift 里的 UmbraWidgetBridge.sessionLocked / sessionUnlocked
 * 与 UmbraLiveActivityController.endAll）。
 *
 * 「关」= 删任务快照、撤进行中通知、小组件重画成「请登录 Umbra」；「开」= 只重画，任务快照由下一轮任务拉取写回。
 * 提醒小组件不看登录（本地功能）。
 *
 * ⚠️ 要在 app/FeatureGraph.kt 里加一行 `WidgetModule.register(guard)` 才生效；没接上之前 WidgetBridge.ensureObserving 自己盯守卫兜底。
 */
object WidgetModule {
    fun register(guard: SessionGuard) {
        WidgetBridge.registered = true
        guard.register("widget", onLock = { WidgetBridge.sessionLocked() }, onUnlock = { WidgetBridge.sessionUnlocked() })
    }
}
