// 任务「没做成」（服务端 #175 `reason_kind: "unfinished"`）的纯判据与字面。设计侧第 320 封 / tokens 3.118.0 `batch341.cannotComplete`。
// 它和报错 / 欠费 / 离线不是一回事：那些是中途坏了、再试可能就好；这个是这条路走不通，再跑一次多半一样 ——
// 所以灰色、禁行圈、不给［重试任务］做主钮、不给［去看能力］。纯 Kotlin，TaskUnfinishedRulesTest 有用例。
package com.geek.umbra.feature.task

object TaskUnfinishedRules {
    /** 服务端的类别名（tasks_engine.REASON_UNFINISHED）。设计稿里暂记 cannot_complete，以服务端为准。 */
    const val KIND = "unfinished"

    /** 状态胶囊 / 块标题 / 通知前缀的同一个词。 */
    const val LABEL = "没做成"

    /** 这一条是不是「没做成」：只认失败态 + 类别 unfinished；别的失败照旧红色「失败」。 */
    fun isUnfinished(status: String?, reasonKind: String?): Boolean = status == "failed" && reasonKind == KIND

    /**
     * 原因一句（320 §1「正文只写原因」）：去掉开头的「服务器说：」和重复的「没做成：」（result_summary 是「没做成：原因」）。
     * 全角 / 半角冒号都认；剥完是空的回 null，调用方整块不出，不编兜底话。
     */
    fun reason(text: String?): String? {
        var s = text?.trim().orEmpty()
        for (prefix in listOf("服务器说", LABEL)) {
            if (s.startsWith(prefix)) {
                val rest = s.removePrefix(prefix)
                if (rest.startsWith("：") || rest.startsWith(":")) s = rest.substring(1).trim()
            }
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
    fun stoppedAt(seq: Int?, title: String?): String? {
        if (seq == null || seq <= 0) return null
        val t = title?.trim().orEmpty()
        return if (t.isEmpty()) "停在第 $seq 步" else "停在第 $seq 步「$t」"
    }

    /** 「下一步」那句：服务端给了就显示，没有回 null（整块 / 整行不出，不编）。 */
    fun nextStep(raw: String?): String? = raw?.trim()?.ifEmpty { null }

    /** 任务卡上那一行。 */
    fun nextLine(next: String): String = "下一步：$next"

    /** 列表副行「没做成：原因」；没原因只写「没做成」。 */
    fun listSub(summary: String?): String = reason(summary)?.let { "$LABEL：$it" } ?: LABEL

    /** 系统通知标题（320 §5）：「没做成：任务名」。 */
    fun notifyTitle(taskName: String): String = "$LABEL：$taskName"
}
