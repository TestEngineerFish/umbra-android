package com.geek.umbra.feature.chat

/**
 * 其他模块调聊天的入口（桩，由聊天模块实现）。
 * - 灵感详情「让 Umbra 去做这件事」、任务「重新发起」：跳到秘书会话，预填正文、挂芯片动作、来源横幅，**不自动发送**；
 * - 求助通知 / 执勤推送：定位到某条消息。
 */
object ChatApi {
    /** 打开秘书会话并预填。chip 例：「创建任务」；banner 是输入栏上方「来自灵感：xxx」那条。 */
    fun startDraft(prefill: String, chip: String? = null, banner: String? = null) {
        com.geek.umbra.app.AppGraph.router.jump(com.geek.umbra.navigation.Route.ChatThread("assistant"))
    }

    /** 打开某会话并滚到某条消息（闪光圈）。 */
    fun focus(conversation: String, messageId: Long?) {
        com.geek.umbra.app.AppGraph.router.jump(com.geek.umbra.navigation.Route.ChatThread(conversation))
    }
}
