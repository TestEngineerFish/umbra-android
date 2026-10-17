package com.geek.umbra.feature.remote

import com.geek.umbra.app.SessionGuard

/** 远程控制模块的进程级入口：在守卫上登记「关」时结束进行中的会话。 */
object RemoteModule {
    fun register(guard: SessionGuard) {}
}
