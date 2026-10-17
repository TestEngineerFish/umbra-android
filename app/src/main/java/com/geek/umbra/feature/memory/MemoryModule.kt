package com.geek.umbra.feature.memory

import com.geek.umbra.app.SessionGuard

/**
 * 记忆模块的进程级入口：在会话守卫上登记联网部分的「关 / 开」。
 * 关 = 清空屏上的记忆（umbra-ios#86：三页共用的单例原来从不清，换账号后首帧画的是上一个人的记忆，
 * 偏好 / 档案两条 id 各账号相同，「覆盖保存」会把上一个人的正文写进新账号）。开：不主动拉，进页面才拉（先清再读）。
 */
object MemoryModule {
    fun register(guard: SessionGuard) {
        guard.register("memory", onLock = { MemoryStore.reset() }, onUnlock = {})
    }
}
