package com.geek.umbra.feature.knowledge

import com.geek.umbra.app.SessionGuard

/**
 * 知识库模块的进程级入口：在会话守卫上登记联网部分的「关 / 开」。
 * 关：停掉所有上传队列、清掉库列表与各库文档（换账号后首帧不画上一个人的库）。开：不主动拉，进页面才拉。
 */
object KnowledgeModule {
    fun register(guard: SessionGuard) {
        guard.register("knowledge", onLock = { KbListStore.clear(); KbDocsModel.clearAll() }, onUnlock = {})
    }
}
