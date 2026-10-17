package com.geek.umbra.feature.money

import com.geek.umbra.app.SessionGuard

/**
 * 记账模块的进程级入口：在会话守卫上登记联网部分的「关 / 开」（本地功能不登记）。
 * AppGraph 初始化时由 FeatureGraph 调一次。
 */
object MoneyModule {
    fun register(guard: SessionGuard) {}
}
