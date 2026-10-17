package com.geek.umbra.feature.reminder

import androidx.core.content.FileProvider
import com.geek.umbra.app.SessionGuard

/**
 * 提醒模块的进程级入口：App 启动时由 FeatureGraph 调一次。
 *
 * 提醒是本地功能（2026-10-10 契约：未登录 = 单机）：本地读写、推进、闹钟一律不看登录，启动即开（[ReminderStore.start]）；
 * 只有和服务端的同步登记在会话守卫上 —— 「开」= 同步一次（拉增量 → 合并 → 补推），「关」= 只停同步那一截
 * （不撤通知、不清角标，本机的提醒是他自己的，照响）。iOS: App/SessionGuard.swift:61
 */
object ReminderModule {
    fun register(guard: SessionGuard) {
        ReminderStore.start()
        guard.register("reminder", onLock = { ReminderStore.sessionLocked() }, onUnlock = { ReminderStore.sessionUnlocked() })
    }
}

/** 提醒附件「拍照」用的 FileProvider。单独一个子类：清单里同名 provider 只能有一个，别的模块也可能要。 */
class ReminderFileProvider : FileProvider()
