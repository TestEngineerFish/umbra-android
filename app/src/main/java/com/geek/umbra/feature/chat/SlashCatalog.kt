// 「/」快捷输入的动作目录（iOS: Features/Chat/SlashPanel.swift 的 SlashAction / SlashCatalog，批次 005）。
//
// 发送形态：动作名以【动作名】前缀并进正文随消息发出，服务端零改动 —— 秘书的 LLM 认这个人话前缀。
// 「接入的能力」组故意不在：稿里的翻译 / 压视频只是示例，没有真实的 Skill / MCP 接入，摆假动作等于骗人点。
package com.geek.umbra.feature.chat

import com.geek.umbra.designsystem.UmbraIconPaths

/** 一个快捷动作。k 是过滤用的英文键（也当 id）；label 是芯片和发送前缀；params 是选中后输入框的灰色占位。 */
data class SlashAction(val k: String, val label: String, val desc: String, val params: String, val icon: String, val tag: String? = null)

data class SlashGroup(val name: String, val items: List<SlashAction>)

object SlashCatalog {
    val groups: List<SlashGroup> = listOf(
        SlashGroup("记录", listOf(
            SlashAction("money", "记一笔", "金额、分类，几秒记完", "金额 分类 备注", UmbraIconPaths.wallet),
            SlashAction("insp", "记灵感", "先存下来，标题和标签秘书补", "想到什么就写什么", UmbraIconPaths.bulb),
            SlashAction("rem", "建提醒", "时间 + 要提醒你做什么", "时间 要提醒你做什么", UmbraIconPaths.bell),
        )),
        SlashGroup("任务", listOf(
            SlashAction("task", "创建任务", "描述目标，Umbra 拆步骤去做", "要它做什么", UmbraIconPaths.task),
        )),
    )

    val total: Int get() = groups.sumOf { it.items.size }

    /** 灵感页「让 Umbra 去做这件事」预填用的就是这枚「创建任务」。 */
    val taskAction: SlashAction get() = groups[1].items[0]

    /** 按动作名找（ChatApi.startDraft 的 chip 传的是动作名）。 */
    fun byLabel(label: String): SlashAction? = groups.flatMap { it.items }.firstOrNull { it.label == label }

    /** 过滤：动作名包含查询串，或英文键包含小写查询串。空查询返回全目录，空组整组不出。 */
    fun filtered(q: String): List<SlashGroup> {
        if (q.isEmpty()) return groups
        val lower = q.lowercase()
        return groups.mapNotNull { g ->
            val items = g.items.filter { it.label.contains(q) || it.k.contains(lower) }
            if (items.isEmpty()) null else SlashGroup(g.name, items)
        }
    }
}
