package com.geek.umbra.feature.inspiration

import com.geek.umbra.app.AppGraph
import com.geek.umbra.app.SessionGuard
import kotlinx.coroutines.launch

/**
 * 灵感模块的进程级入口。灵感是**本机优先**：本机账本不看登录（未登录也能记），只把「同步」登记在守卫上 ——
 * 登录：开始同步、订阅 WS inspiration_*；登出：只停推，不删本机条目（换了账号由同步那一截按 §5.5.2 问）。
 */
object InspirationModule {
    fun register(guard: SessionGuard) {
        // 本机账本不看登录：启动后读一次盘，工具页的灵感卡（读 InspirationStore）不用等谁先进过灵感页。
        AppGraph.scope.launch { InspirationStore.load() }
        guard.register(
            "inspiration-sync",
            onLock = { InspirationLocal.sessionLocked() },
            onUnlock = { InspirationLocal.sessionUnlocked(); InspirationStore.startListening() },
        )
    }
}
