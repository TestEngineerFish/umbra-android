package com.geek.umbra.feature.task

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 服务端看门狗的一条提醒（聊天消息 `meta.kind = "task_watchdog"`，服务端 #182，geek-7f 11-12 单子第三节）。
 * [cleared]：之后收到过这个任务的 task_update（任务动起来了 / 停了），卡底那一行收掉、气泡里不再给按钮。
 */
data class TaskWatchdog(
    val taskId: String,
    /** overdue（还在跑但没进展）/ suspended（挂起太久）/ interrupted（被服务器重启打断以后一直没接着跑）。 */
    val reminder: String,
    /** `["resume","stop"]` 的子集。有没有「接着跑」只看它，不自己猜。 */
    val actions: List<String>,
    val step: Int? = null,
    val stepName: String? = null,
    val sinceMs: Long? = null,
    val suspendedSecs: Long? = null,
    /** suspended 档带的挂起字段（形状同任务的挂起字段），用来说「等「X」上线」那半句。 */
    val suspend: TaskSuspendInfo = TaskSuspendInfo(),
    val cleared: Boolean = false,
    /** 任务名（服务端 #185 `task_name`）：卡不在会话里时气泡收成小卡，第一行就是它（330 五.5）。 */
    val taskName: String? = null,
)

object TaskWatchdogRules {
    const val KIND = "task_watchdog"
    const val OVERDUE = "overdue"
    const val SUSPENDED = "suspended"
    const val INTERRUPTED = "interrupted"
    private val REMINDERS = setOf(OVERDUE, SUSPENDED, INTERRUPTED)

    /**
     * 认 `meta`：kind 对、task_id 非空、reminder 认得才算。单值 `action`（view_task / resume_task）只为让老版本的去重条件不命中，
     * 这里不认它；actions 缺了按只有「停止」（服务端说 stop 恒有）。
     */
    fun parse(meta: JsonObject?): TaskWatchdog? {
        meta ?: return null
        fun str(k: String) = (meta[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        fun num(k: String) = (meta[k] as? JsonPrimitive)?.takeIf { !it.isString }
        if (str("kind") != KIND) return null
        val taskId = str("task_id") ?: return null
        val reminder = str("reminder")?.takeIf { it in REMINDERS } ?: return null
        val actions = (meta["actions"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?.filter { it == "resume" || it == "stop" }?.distinct() ?: listOf("stop")
        // 挂起字段与任务同形，只是第几步叫 step（任务那边叫 suspended_step）。
        val info = TaskSuspendInfo.fromJson(meta).let { it.copy(step = it.step ?: num("step")?.intOrNull) }
        return TaskWatchdog(
            taskId = taskId, reminder = reminder, actions = actions,
            step = num("step")?.intOrNull?.takeIf { it > 0 }, stepName = str("step_name"),
            sinceMs = num("since_ms")?.longOrNull?.takeIf { it > 0 }, suspendedSecs = num("suspended_secs")?.longOrNull?.takeIf { it > 0 },
            suspend = if (reminder == SUSPENDED) info else TaskSuspendInfo(),
            taskName = str("task_name"),
        )
    }

    /** 那一行挂着时顶不顶替卡的主行（330 五.4）：挂起太久、被打断说的和主行是一件事 → 顶替；卡住时主行说进度、两句都留。 */
    fun replacesMainLine(w: TaskWatchdog?): Boolean = w != null && !w.cleared && w.reminder != OVERDUE

    fun canResume(w: TaskWatchdog): Boolean = !w.cleared && "resume" in w.actions
    fun canStop(w: TaskWatchdog): Boolean = !w.cleared && "stop" in w.actions

    /**
     * 卡底那一行（设计侧 326 b 那张表）。字段缺了有兜底：没有步骤名不写引号那段、没有时间不写「从几点起」，不留空引号。
     * [clock]：since_ms → 「14:05」；单测传固定的。
     */
    fun line(w: TaskWatchdog, clock: (Long) -> String = ::clockText): String = when (w.reminder) {
        OVERDUE -> {
            val step = TaskSuspendRules.stepPhrase(w.step) + (w.stepName?.let { "「$it」" } ?: "")
            step + (w.sinceMs?.let { "从 ${clock(it)} 起没动静了" } ?: "一直没动静了")
        }
        SUSPENDED -> {
            val head = w.suspendedSecs?.let { "已经挂起${durationText(it)}了" } ?: "已经挂起很久了"
            "$head：${suspendReason(w.suspend)}"
        }
        else -> "被服务器重启打断以后还没接着跑"
    }

    /** 挂起原因那半句：沿用本端现有的说法（列表副行去掉「已挂起 · 」）——「等「Sam 的 MacBook」上线」「缺能力：…」。 */
    fun suspendReason(info: TaskSuspendInfo): String =
        TaskSuspendRules.display("suspended", info)!!.listMeta.removePrefix(TaskSuspendCopy.listPrefix)

    /** 「 40 分钟」「 3 小时」「 2 天」（前面带空格，接在「已经挂起」后面；不足一分钟按 1 分钟）。 */
    fun durationText(secs: Long): String = when {
        secs < 3600 -> " ${(secs / 60).coerceAtLeast(1)} 分钟"
        secs < 86400 -> " ${secs / 3600} 小时"
        else -> " ${secs / 86400} 天"
    }

    /** 今天写「14:05」，别的日子写「11月11日 14:05」。 */
    fun clockText(ms: Long): String {
        val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        val pattern = if (t.toLocalDate() == LocalDate.now()) "HH:mm" else "M月d日 HH:mm"
        return t.format(DateTimeFormatter.ofPattern(pattern))
    }

    /**
     * 历史里读到的提醒是不是已经过时：任务已经结束，或状态和提醒对不上（overdue 却挂起了；挂起 / 打断的却在跑）就当已收掉。
     * 不知道状态（任务列表里没有）或认不得的状态 → 不算过时，照画。
     */
    fun stale(w: TaskWatchdog, status: String?): Boolean = when (status) {
        null -> false
        "done", "failed", "error", "cancelled", "canceled", "stopped" -> true
        "suspended" -> w.reminder == OVERDUE
        "running" -> w.reminder != OVERDUE
        else -> false
    }
}
