// 任务通知四态（执行中 / 需确认 / 失败 / 完成）与两个小组件排版的纯判据 —— 设计侧第 199 封答复第 9、10 条
// （docs/design/答复实现侧-Android平台差异.md，出图 Claude Design「Android 端/Pages/平台差异.dc.html」#9 / #10）。
// 纯 Kotlin（不 import android.*），配 JUnit 用例 TaskNotifyRulesTest.kt。
// Android 独有（iOS 是灵动岛 + 锁屏实况，没有这四个槽位），所以用例照答复与出图里的例子写，不来自 swifttests。
package com.geek.umbra.feature.widget

import com.geek.umbra.feature.task.TaskProgress
import com.geek.umbra.feature.task.TaskRetryButton
import com.geek.umbra.feature.task.TaskRetryRules
import com.geek.umbra.feature.task.TaskStatusKind
import com.geek.umbra.feature.task.taskStatusOf
import com.geek.umbra.model.TaskStep
import com.geek.umbra.net.ReasonRules
import com.geek.umbra.net.TaskFailAction

/** 一个任务在通知这一层处于哪一态。 */
enum class TaskNotifyPhase {
    /** 执行中：常驻、走秒、分段进度。 */
    Running,
    /** 需确认（任务模块里映射到 UmbraStatus.AwaitingReview 的那些状态）：常驻、只提醒一次、进度停住转 warning。 */
    Awaiting,
    /** 挂起 / 待执行：**不是结束**（LiveActivityRules.keepsAlive），常驻那条留着、计时停。 */
    Hold,
    /** 失败：转普通可划掉的通知（「任务结果」渠道）。 */
    Failed,
    /** 完成：普通可划掉。 */
    Done,
    /** 已取消 / 找不到 / 不认识：普通一条说结果（LiveActivityRules.finalFrame），答复四态之外沿用原来的收尾。 */
    Ended,
}

/** 失败通知上的一颗动作钮（取任务详情错误卡 / 动作条的前两颗）。 */
sealed interface TaskNotifyFailAction {
    /** 「重试任务」：后台直接 POST /tasks/{id}/retry。 */
    data object Retry : TaskNotifyFailAction
    /** 「重新发起」（电脑操作任务）：进秘书会话预填原目标。 */
    data object Relaunch : TaskNotifyFailAction
    /** 「去充值」/「开通会员」/「去买资源包」：外链。label 由调用方按 reason_kind 取字面。 */
    data class Topup(val url: String) : TaskNotifyFailAction
    /** 「去看能力」：值得再跑一次、但不是在这里重试（同详情错误卡）。 */
    data object Caps : TaskNotifyFailAction
}

/**
 * 本机点了「停止」的任务。设计侧批次 201 第 7 条：本机停的不发「已取消」通知 —— 用户就在现场；
 * 只有别的设备停的、或服务端停的才发。记号在发 POST 之前打（收尾在随后的重拉里就会触发），失败再撤。
 */
object TaskLocalStops {
    private val ids = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    fun mark(id: String) { ids += id }
    fun unmark(id: String) { ids -= id }
    /** 取走记号：收尾时问一次就清掉，下次同一个任务再被别处停掉照常发。 */
    fun take(id: String): Boolean = ids.remove(id)
}

object TaskNotifyRules {
    /** 已取消那一条的正文（设计侧批次 201 第 7 条定稿；步数按批次 290「已完成几步」）。步数拿不到时不编进度。 */
    fun cancelledBody(done: Int?, total: Int?): String =
        if (done != null) TaskProgress.make(done, total)?.let { "已取消，${it.stepLine}。做完的部分留在任务详情里" } ?: "已取消。做完的部分留在任务详情里"
        else "已取消。做完的部分留在任务详情里"

    /** 服务端找不到这条任务时的正文（同上定稿），配动作「打开任务」落任务列表。 */
    const val NOT_FOUND_BODY = "这条任务在服务器上找不到了，可能已在别的设备上删掉。"
    const val NOT_FOUND_ACTION = "打开任务"

    /** 需确认那一态的标题后缀（答复 #9：`任务名 · 等你确认`）。 */
    const val AWAITING_SUFFIX = " · 等你确认"
    /** 需确认时状态栏胶囊那三个字。 */
    const val AWAITING_CHIP = "待确认"

    /** 状态 → 通知这一层的哪一态。null = 列表里已经找不到它。 */
    fun phase(status: String?): TaskNotifyPhase {
        if (status == null) return TaskNotifyPhase.Ended
        if (status == "running") return TaskNotifyPhase.Running
        if (LiveActivityRules.keepsAlive(status)) return TaskNotifyPhase.Hold
        return when (taskStatusOf(status)) {
            TaskStatusKind.AwaitingReview -> TaskNotifyPhase.Awaiting
            TaskStatusKind.Done -> TaskNotifyPhase.Done
            TaskStatusKind.Failed -> TaskNotifyPhase.Failed
            else -> TaskNotifyPhase.Ended
        }
    }

    /** 这一态是不是挂常驻那一条（执行中 / 需确认 / 挂起）。 */
    fun isLive(p: TaskNotifyPhase): Boolean = p == TaskNotifyPhase.Running || p == TaskNotifyPhase.Awaiting || p == TaskNotifyPhase.Hold

    /**
     * 正在做的那一步：先找状态是执行中的那一步，没有就按 seq 排好取第 done+1 步（下标 done）。
     * 步骤标题服务端可能不给（null / 空），那就只说「正在做第 N 步」。
     */
    fun currentStepTitle(steps: List<TaskStep>, done: Int): String? {
        val sorted = steps.sortedBy { it.seq }
        val s = sorted.firstOrNull { it.status == "running" } ?: sorted.getOrNull(done)
        return s?.title?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * 执行中的正文，两行（批次 290 §六）：「已完成 2/8 步」\n「正在做第 3 步 · 打开价格页并截图」。
     * 全做完那一刻只剩第一行；没有步骤数就是「执行中」（不编进度）。⚠️ 原来写「第 3/8 步」（按 done+1 算），和详情的「2 / 8」对不上。
     */
    fun runningBody(done: Int, total: Int, stepTitle: String?): String {
        val p = TaskProgress.make(done, total) ?: return "执行中"
        return listOfNotNull(p.stepLine, p.currentStep?.let { TaskProgress.currentLine(it, stepTitle) }).joinToString("\n")
    }

    /** 状态栏胶囊（Live Updates 的 shortCriticalText）：百分比；需确认换「待确认」。 */
    fun chipText(p: TaskNotifyPhase, done: Int, total: Int): String =
        if (p == TaskNotifyPhase.Awaiting) AWAITING_CHIP else WidgetTaskRules.percentText(done, total)

    /** 需确认的标题。 */
    fun awaitingTitle(name: String): String = name + AWAITING_SUFFIX

    /** 需确认的正文：秘书的问题原文优先（task_update.message），拿不到用任务列表那一句（状态徽标的字）。 */
    fun awaitingBody(question: String?, listLine: String): String = question?.trim()?.takeIf { it.isNotEmpty() } ?: listLine

    /**
     * 失败的标题 =「发生了什么」：与任务详情失败卡同一句（TaskViews.kt FailBlock：`第 N 步中断：步骤名`，
     * 步骤没名字写「未命名步骤」；找不到失败的那一步就是「任务失败」）。
     */
    fun failTitle(failedStep: TaskStep?): String =
        failedStep?.let { "第 ${it.seq} 步中断：${it.title ?: "未命名步骤"}" } ?: "任务失败"

    /** 失败的正文 = `任务名 · 为什么`；为什么是空的就只有任务名。 */
    fun failBody(name: String, why: String?): String {
        val w = why?.trim().orEmpty()
        return if (w.isEmpty()) name else "$name · $w"
    }

    /**
     * 失败通知的动作，最多两颗，顺序同任务详情：先动作条那颗重试类的（TaskRetryRules.button），
     * 再错误卡那颗（欠费给了地址 → 去充值；值得再跑一次 → 去看能力）。
     * blocked 恒为 false：通知这里没有「上一次重试已被说死」的记忆（那是详情页内的状态）。
     */
    fun failActions(status: String, reasonKind: String?, topupUrl: String?, executor: String?): List<TaskNotifyFailAction> {
        val fail = ReasonRules.taskFailAction(reasonKind, topupUrl)
        val out = mutableListOf<TaskNotifyFailAction>()
        when (TaskRetryRules.button(status, fail, blocked = false, operate = TaskRetryRules.isOperate(executor))) {
            TaskRetryButton.Retry -> out += TaskNotifyFailAction.Retry
            TaskRetryButton.Relaunch -> out += TaskNotifyFailAction.Relaunch
            null -> Unit
        }
        if (fail is TaskFailAction.Topup) out += TaskNotifyFailAction.Topup(fail.url)
        if (fail == TaskFailAction.AsBefore) out += TaskNotifyFailAction.Caps
        return out.take(2)
    }

    /**
     * 耗时：满 1 小时「N小时M分」，满 1 分钟「N分SS秒」（秒补两位），不足 1 分钟「N秒」。负数按 0。
     * 出图例：`4分08秒`、`1分12秒`。
     */
    fun elapsedText(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000)
        return when {
            s >= 3600 -> "${s / 3600}小时${(s % 3600) / 60}分"
            s >= 60 -> "${s / 60}分${(s % 60).toString().padStart(2, '0')}秒"
            else -> "${s}秒"
        }
    }

    /** 完成的正文：`全部完成 · 耗时 4分08秒`；起止时刻拿不到就只说「全部完成」（不编耗时）。 */
    fun doneBody(createdMs: Long?, updatedMs: Long?): String =
        if (createdMs == null || updatedMs == null) "全部完成" else "全部完成 · 耗时 ${elapsedText(updatedMs - createdMs)}"

    /** 完成通知发不发：App 在前台且正停在这条任务详情时不发（页面自己会更新）。 */
    fun postsDone(foreground: Boolean, showingThisDetail: Boolean): Boolean = !(foreground && showingThisDetail)

    // MARK: 今日提醒小组件的行数（答复 #10）

    /** 4×2 那一档的槽位数：2 行 +「还有 N 条」。 */
    const val REMINDER_MIN_SLOTS = 3
    /** 最多画几行提醒。 */
    const val REMINDER_MAX_ROWS = 6

    /**
     * 按可用高度能放几个「行位」（一条提醒或「还有 N 条」各占一个）。最少 3（4×2 的排法），最多 7（6 行 + 收尾那行）。
     * - heightDp：小组件实际高度；pad：上下内边距合计；header：顶行高；pitch：一行连行距。
     */
    fun reminderSlots(heightDp: Float, pad: Float, header: Float, pitch: Float): Int {
        val avail = heightDp - pad - header
        val n = if (pitch <= 0f) 0 else (avail / pitch).toInt()
        return n.coerceIn(REMINDER_MIN_SLOTS, REMINDER_MAX_ROWS + 1)
    }

    /**
     * 这么多条、这么多行位，画几行、「还有」几条（0 = 不画收尾那行）。
     * 放得下（且不超过 6 行）就全画 —— 正好 3 条时第 3 行直接显示，不写「还有 1 条」；
     * 放不下就留一个行位给「还有 N 条」。
     */
    fun reminderRows(count: Int, slots: Int): Pair<Int, Int> {
        if (count <= 0) return 0 to 0
        val cap = minOf(slots, REMINDER_MAX_ROWS)
        if (count <= cap) return count to 0
        val shown = minOf(slots - 1, REMINDER_MAX_ROWS)
        return shown to (count - shown)
    }
}
