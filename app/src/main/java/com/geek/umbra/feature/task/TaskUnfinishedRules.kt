// 任务「没做成」（服务端 #175 `reason_kind: "unfinished"`）的纯判据与字面。设计侧第 320 封 / tokens 3.118.0 `batch341.cannotComplete`。
// 它和报错 / 欠费 / 离线不是一回事：那些是中途坏了、再试可能就好；这个是这条路走不通，再跑一次多半一样 ——
// 所以灰色、禁行圈、不给［重试任务］做主钮、不给［去看能力］。纯 Kotlin，TaskUnfinishedRulesTest 有用例。
package com.geek.umbra.feature.task

/**
 * 没做成那一档的字面，中英两份（设计侧 322 §4，英文「Couldn't be done」）。照 iOS `TaskUnfinishedCopy`（3824824）逐字：
 * 一律写成带 english 的函数而不是常量 —— 调用点必须说清是哪种语言，漏传一处就编不过，不会悄悄只剩中文。
 * 任务页其余字面（重试任务等）两端都还只有中文。
 */
object TaskUnfinishedCopy {
    /** 状态胶囊、块标题。 */
    fun title(english: Boolean) = if (english) "Couldn't be done" else "没做成"
    /** 详情「下一步」块的小标题。 */
    fun nextTitle(english: Boolean) = if (english) "Next step" else "下一步"
    /** 任务卡那一行的前缀。 */
    fun nextPrefix(english: Boolean) = if (english) "Next step: " else "下一步："
    /** 列表副行、会话预览、通知标题的前缀。 */
    fun listPrefix(english: Boolean) = if (english) "Couldn't be done: " else "没做成："
    fun sayInChat(english: Boolean) = if (english) "Say this in chat" else "在聊天里说这句"
    /** 详情信息格的标签（322 §2）。 */
    fun stoppedLabel(english: Boolean) = if (english) "Stopped at" else "停在"
}

object TaskUnfinishedRules {
    /** 服务端的类别名（tasks_engine.REASON_UNFINISHED）。设计稿里暂记 cannot_complete，以服务端为准。 */
    const val KIND = "unfinished"

    /** 这一条是不是「没做成」：只认失败态 + 类别 unfinished；别的失败照旧红色「失败」。 */
    fun isUnfinished(status: String?, reasonKind: String?): Boolean = (status == "failed" || status == "error") && reasonKind == KIND

    /**
     * 原因一句（320 §1「正文只写原因」）：去掉开头的「服务器说：」和重复的「没做成：」（result_summary 是「没做成：原因」）。
     * 全角 / 半角冒号都认；剥完是空的回 null，调用方整块不出，不编兜底话。
     */
    fun reason(text: String?): String? {
        var s = text?.trim().orEmpty()
        // 两层叠着（「没做成：服务器说：…」「没做成：没做成：…」）也剥干净，同 iOS reason(_:)。
        while (true) {
            val prefix = listOf("服务器说", "没做成").firstOrNull { p -> s.startsWith(p) && s.removePrefix(p).let { it.startsWith("：") || it.startsWith(":") } } ?: break
            s = s.removePrefix(prefix).substring(1).trim()
        }
        return s.ifEmpty { null }
    }

    /**
     * 详情块 / 步骤行的原因：服务端把全文放在 error.detail、截到 200 字的放在 message（两句是同一句话），
     * 所以取 detail 优先，退回 message，再退回任务的 result_summary。不能两句拼起来画（会把同一句话说两遍）。
     */
    fun reasonOf(message: String?, detail: String?, summary: String? = null): String? =
        reason(detail) ?: reason(message) ?: reason(summary)

    /** 块头右侧那行灰字：「停在第 N 步「步骤名」」；找不到失败的那一步就不写。 */
    fun stoppedAt(seq: Int?, title: String?, english: Boolean): String? {
        if (seq == null || seq <= 0) return null
        val t = title?.trim().orEmpty()
        if (english) return if (t.isEmpty()) "Stopped at step $seq" else "Stopped at step $seq \u201C$t\u201D"
        return if (t.isEmpty()) "停在第 $seq 步" else "停在第 $seq 步「$t」"
    }

    /** 详情信息格「停在　第 N 步」的值（322 §2；不写「失败步骤 1」）。 */
    fun stoppedCell(seq: Int?, english: Boolean): String? {
        if (seq == null || seq <= 0) return null
        return if (english) "Step $seq" else "第 $seq 步"
    }

    /** 「下一步」那句：服务端给了就显示，没有回 null（整块 / 整行不出，不编）。 */
    fun nextStep(raw: String?): String? = raw?.trim()?.ifEmpty { null }

    /** 任务卡上那一行。 */
    fun nextLine(next: String, english: Boolean): String = TaskUnfinishedCopy.nextPrefix(english) + next

    /** 列表副行「没做成：原因」；没有原因回 null（副行不出，同 iOS listLine）。原因是服务端原话，英文界面也照原样。 */
    fun listSub(summary: String?, english: Boolean): String? = reason(summary)?.let { TaskUnfinishedCopy.listPrefix(english) + it }

    /** ［在聊天里说这句］的那句话（服务端 #178 `next_action.say`，只在拿得准时带）：没有就不出钮，只显示文字。 */
    fun say(raw: String?): String? = raw?.trim()?.ifEmpty { null }

    /**
     * 聊天里那条「❌「目标」没做成：…」是不是某张任务卡的复述（#178 `meta.task_id`）。收紧（geek-7f 11-11 深夜）：
     * `meta.kind == "task_unfinished"` 才算；老消息（没有 kind 也没有 action）照旧算。看门狗提醒（`task_watchdog`，
     * 带 action）、「要接着跑吗？」（action: resume_task）、别的 kind 一律照常画 —— 不然提醒会被当成复述吞掉。
     */
    fun echoTaskId(kind: String?, action: String?, taskId: String?): String? {
        if (taskId.isNullOrEmpty()) return null
        return when {
            kind == ECHO_KIND -> taskId
            kind.isNullOrEmpty() && action.isNullOrEmpty() -> taskId
            else -> null
        }
    }

    /** 服务端给「没做成」复述标的 meta.kind。 */
    const val ECHO_KIND = "task_unfinished"

    /** 会话列表预览（320 §4）：「没做成：任务名」，前面一枚灰禁行圈由界面画。 */
    fun previewText(name: String, english: Boolean): String = TaskUnfinishedCopy.listPrefix(english) + name.trim()

    /** 系统通知标题（320 §5 / 322 §4）：同会话预览。 */
    fun notifyTitle(taskName: String, english: Boolean): String = previewText(taskName, english)
}
