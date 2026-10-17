package com.geek.umbra.feature.phrases

import com.geek.umbra.app.SessionGuard

/**
 * 常用语模块的进程级入口：AppGraph 初始化时由 FeatureGraph 调一次。
 * 本地面（列表、编辑、复制）不看登录；同步是联网那一截 —— 「开」= 同步一次（把单机期间攒的改动推上去、拉回别端的），
 * 「关」不用做什么：sync() 自己先看守卫，未登录直接返回、也不留「同步失败」那行字。
 */
object PhrasesModule {
    fun register(guard: SessionGuard) {
        guard.register("phrases", onLock = {}, onUnlock = { PhraseStore.syncNow() })
    }
}
