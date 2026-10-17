package com.geek.umbra.feature.chat

import com.geek.umbra.app.SessionGuard

/**
 * 聊天模块的进程级入口：在会话守卫上登记联网部分的「关 / 开」（iOS: ChatViewModel.swift:179 向 SessionGuard 登记「聊天长连接」）。
 * AppGraph 初始化时由 FeatureGraph 调一次。长连接本身的开关由 AppGraph 登记的 "socket" 那一条管，这里只管聊天自己的状态：
 * - 关：清空会话内存（未登录 = 单机，不留上一个账号的聊天在内存里），「接着跑」那几颗钮退回未知；
 * - 开：补拉主会话历史 / 会话列表 / 设备列表（后台路径，不打「人引起的」头）。
 */
object ChatModule {
    fun register(guard: SessionGuard) {
        ChatStore.start()
        guard.register("聊天", onLock = { ChatStore.onLock() }, onUnlock = { ChatStore.onUnlock() })
    }
}
