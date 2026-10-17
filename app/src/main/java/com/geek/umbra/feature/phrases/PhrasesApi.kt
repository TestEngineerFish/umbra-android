package com.geek.umbra.feature.phrases

/** 其他模块调常用语的入口（常用语模块实现）。 */
object PhrasesApi {
    /**
     * 聊天长按「存为常用语」：名字取第一行前 12 字（调用方算好传进来）；内容完全相同的已存在就不重复存。
     * 返回 false = 已经有一模一样的（调用方说「已经有了」，不是「存失败了」）。iOS: ChatThreadView.swift:891
     */
    fun addFromChat(name: String, content: String): Boolean {
        PhraseStore.ensureLoaded()
        if (PhraseStore.items.value.any { it.content == content }) return false
        if (name.isBlank()) return false // 全是空白的正文不值得存一条名字为空的常用语
        PhraseStore.add(name, content, null)
        return true
    }

    /**
     * 工具页卡片：本地条数；本机为空且从没同步成功过时返回 null（显示「—」）——
     * 不知道服务端有没有，不许说成「还没有常用语」（台账 `card-sub-fake-empty-on-load-failure`）。
     */
    fun countForTool(): Int? {
        PhraseStore.ensureLoaded()
        val n = PhraseStore.items.value.size
        return if (n > 0 || PhraseStore.everSynced) n else null
    }
}
