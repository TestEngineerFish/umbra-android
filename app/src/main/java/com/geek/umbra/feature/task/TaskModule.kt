package com.geek.umbra.feature.task

import com.geek.umbra.app.SessionGuard

/**
 * 任务模块的进程级入口：在会话守卫上登记联网部分的「关 / 开」。
 * 任务是联网功能：登录后订阅 task_update、拉一次列表（工具 Tab 角标从开机就对）；登出清掉上一个账号的任务与角标。
 * 页面级的 3.5 秒轮询不在这里 —— 它只在列表 / 详情页可见时跑。
 */
object TaskModule {
    fun register(guard: SessionGuard) {
        guard.register("tasks", onLock = { TasksStore.sessionLocked() }, onUnlock = { TasksStore.sessionUnlocked() })
    }
}
