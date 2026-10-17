package com.geek.umbra.feature.me

import com.geek.umbra.app.SessionGuard

/**
 * 我模块的进程级入口：在会话守卫上登记联网部分的「关 / 开」（本地功能不登记）。
 * AppGraph 初始化时由 FeatureGraph 调一次。
 *
 * 设备与能力是联网数据：关的时候清掉，换账号后首帧不画上一个人的设备。开的时候不主动拉 ——
 * 进页面（onAppear）才拉，iOS 同。
 */
object MeModule {
    fun register(guard: SessionGuard) {
        guard.register("me.devices", onLock = { DevicesStore.clear(); CapsStore.clear() }, onUnlock = {})
    }
}
