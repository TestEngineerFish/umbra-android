// 任务「重试」的判据 + 字面：出不出钮、出哪颗、失败怎么分档、每一档写什么（iOS: Features/Task/TaskRetryRules.swift）。
// 任务详情底部动作条、聊天任务卡、任务列表「已不在」那一行三处共用这一份。
//
// 出处：设计侧批次 222 §二。条件和字面都同 PC 221（UmbraPC/src/features/tasks/taskRetry.ts、zh-CN.json 的 tasks.retry*）。
// **钮名也住在这里**：发请求的路径（retryPath）和钮名放在同一个文件，视图一律引用 TaskRetryCopy，不手写这四个字。
// 纯 Kotlin，配 JUnit 用例（TaskRetryRulesTest.kt，照抄 iOS Tools/swifttests/TaskRetryRulesTests.swift）。
package com.geek.umbra.feature.task

import com.geek.umbra.net.ReasonKind
import com.geek.umbra.net.UmbraUrl
import java.net.URI

/** 重试相关的全部字面。除了两句灰色提示，其余逐字同 PC（带参数的两句保留 PC i18n 的 `{{…}}` 占位原样）。 */
object TaskRetryCopy {
    const val retry = "重试任务"
    const val retrying = "重试中…"
    const val relaunch = "重新发起"
    const val goneDismiss = "知道了"

    const val missing = "这个任务已经不在了，可能在另一台设备上删掉了。"
    const val wrongStatus = "这个任务的状态刚变过，现在不能重试。详情已经刷新。"
    const val nothingToRetry = "这个任务的每一步都已经跑完，没有要重跑的。"
    const val operate = "电脑操作任务不能从中间接着跑，屏幕已经变了，要从头再来一次。"
    const val notRetryableTemplate = "这个任务不能重试：{{detail}}"
    const val offline = "连不上服务器，重试没发出去。联网后再按一次。"
    const val serverErrorTemplate = "服务端这次没有响应（HTTP {{code}}），过一会儿再按一次。"

    /** 钮下的灰色提示。iOS 独有（Android 沿用），不参与两端比对。已取消的任务没有「失败的那一步」，所以分两句。 */
    const val hintFailed = "从失败的那一步继续，已完成的里程碑不重做"
    const val hintCancelled = "从停下的那一步继续，已完成的里程碑不重做"
}

/** 一次 `POST /tasks/{id}/retry`（或 /resume）的回应，只留分档要用的几样。status 0 = 根本没拿到响应。 */
data class TaskRetryResponse(val status: Int, val code: String?, val state: String?, val detail: String)

/** 重试分档结果。每一档的字面、留不留钮、要不要重拉都由它说，视图只管摆。 */
sealed interface TaskRetryOutcome {
    data object Ok : TaskRetryOutcome
    /** 任务已不在（404，或引擎侧 state: missing）。 */
    data object Missing : TaskRetryOutcome
    data object WrongStatus : TaskRetryOutcome
    data object NothingToRetry : TaskRetryOutcome
    /** 服务端说这是电脑操作任务（端上没挡住的那种）：换「重新发起」。 */
    data object Operate : TaskRetryOutcome
    data class NotRetryable(val detail: String) : TaskRetryOutcome
    data object Offline : TaskRetryOutcome
    data class ServerError(val code: Int) : TaskRetryOutcome
    /** 服务端以后新加、端上还不认识的 state，以及其它说不清的失败：**摆服务端原话**。 */
    data class Unknown(val detail: String) : TaskRetryOutcome

    /** 写在钮下那一行的话；Ok 没有（成功不提示，222 §二：不弹吐司）。 */
    val message: String?
        get() = when (this) {
            Ok -> null
            Missing -> TaskRetryCopy.missing
            WrongStatus -> TaskRetryCopy.wrongStatus
            NothingToRetry -> TaskRetryCopy.nothingToRetry
            Operate -> TaskRetryCopy.operate
            is NotRetryable -> TaskRetryCopy.notRetryableTemplate.replace("{{detail}}", detail)
            Offline -> TaskRetryCopy.offline
            is ServerError -> TaskRetryCopy.serverErrorTemplate.replace("{{code}}", code.toString())
            is Unknown -> detail
        }

    /** 这一档之后还给不给「重试任务」（同 PC retryable）：说死的几档收掉，过时 / 断网 / 5xx / 未知留着。 */
    val keepsRetry: Boolean
        get() = when (this) {
            Ok, WrongStatus, Offline, is ServerError, is Unknown -> true
            Missing, NothingToRetry, Operate, is NotRetryable -> false
        }

    /** 顺手重拉一次：成功了要看到运行中；状态过时说明本端看到的已经旧了。 */
    val refreshes: Boolean get() = this == Ok || this == WrongStatus
}

/** 一颗重试类的钮：从中间接着跑，或（电脑操作任务）把原目标重新发一次。 */
enum class TaskRetryButton { Retry, Relaunch }

/** 聊天任务卡收尾那颗钮（222 §二 2e）。 */
sealed interface TaskCardAction {
    data object Retry : TaskCardAction
    data object Relaunch : TaskCardAction
    data class Topup(val url: String) : TaskCardAction
    data object ViewResult : TaskCardAction
}

/** 任务失败处的动作分档（iOS: Networking/ReasonKind.swift:182 `TaskFailAction`）。 */
sealed interface TaskFailAction {
    /** 欠费且服务端给了能打开的充值地址：出「去充值」外链。 */
    data class Topup(val url: String) : TaskFailAction
    /** 限流 / 模型服务抖动 / unknown / 缺字段：值得再跑一次的那一档。 */
    data object AsBefore : TaskFailAction
    /** 其余（key 错、参数被拒、没启用、欠费却没给地址）：不给重试类动作。 */
    data object NoRetry : TaskFailAction
}

/**
 * ReasonKind 上任务这条线要用的几条判据（iOS: Networking/ReasonKind.swift 的 showsTopup / retryable / topupLink /
 * taskFailAction）。地基 net/NetRules.kt 只有 ReasonKind 枚举本身，这几条先放在任务包里 —— 聊天错误卡也要用，
 * 合并时宜挪回 net（见交回报告）。
 */
object TaskReason {
    /** 只有欠费三类出「去哪儿买」那颗钮。 */
    fun showsTopup(k: ReasonKind): Boolean =
        k == ReasonKind.InsufficientBalance || k == ReasonKind.NeedMembership || k == ReasonKind.QuotaExhausted

    /** 原样立刻再来一次有没有用（契约表的「可重试」那一列）。 */
    fun retryable(k: ReasonKind): Boolean = when (k) {
        ReasonKind.RateLimited, ReasonKind.UpstreamError, ReasonKind.Unknown -> true
        else -> false
    }

    /**
     * 「去充值」该打开哪儿；null = 不出这颗钮。类别是欠费 + 服务端给了 http(s) 且有 host 的 topup_url 才出 ——
     * 只收 http(s)：这串东西最后交给系统打开，javascript: / 自定义 scheme 一律当没有。
     */
    fun topupLink(kind: ReasonKind?, url: String?): String? {
        if (kind == null || !showsTopup(kind)) return null
        val raw = url?.trim() ?: return null
        val lower = raw.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        val u = runCatching { URI(raw) }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        if (u.host.isNullOrEmpty()) return null
        return raw
    }

    /**
     * 任务失败处该给什么动作。**缺字段按 unknown**（契约原话）：任务这条线从来没按厂商原话猜过欠费，
     * 所以不走兼容分支（iOS: ReasonKind.swift:147）。空串同样按 unknown（iOS `resolve("")` 是 nil，再 `?? .unknown`）。
     */
    fun taskFailAction(wire: String?, topupUrl: String?): TaskFailAction {
        val kind = ReasonKind.of(wire ?: ReasonKind.Unknown.wire) ?: ReasonKind.Unknown
        topupLink(kind, topupUrl)?.let { return TaskFailAction.Topup(it) }
        return if (retryable(kind)) TaskFailAction.AsBefore else TaskFailAction.NoRetry
    }
}

object TaskRetryRules {
    /** 发重试的路径。id 按**一个路径段**编码：带 `/` 的 id 不许多切出一段路径（台账 deeplink-task-id-unescaped）。 */
    fun retryPath(id: String): String = "/tasks/${UmbraUrl.segment(id)}/retry"

    /** 是不是电脑操作任务：**只看 executor == "operate"**，null / 不认识的值一律按普通任务。 */
    fun isOperate(executor: String?): Boolean = executor == "operate"

    /** 把一次回应分档。**只按 state 分，不读 detail 那句中文**（同 PC retryOutcome）。 */
    fun outcome(r: TaskRetryResponse): TaskRetryOutcome {
        if (r.status in 200..299) return TaskRetryOutcome.Ok
        if (r.status == 404 || r.state == "missing") return TaskRetryOutcome.Missing
        if (r.status == 409 && r.code == "state_conflict") {
            return when (r.state) {
                "wrong_status" -> TaskRetryOutcome.WrongStatus
                "nothing_to_retry" -> TaskRetryOutcome.NothingToRetry
                "operate" -> TaskRetryOutcome.Operate
                "not_retryable" -> TaskRetryOutcome.NotRetryable(r.detail)
                else -> TaskRetryOutcome.Unknown(r.detail)
            }
        }
        if (r.status == 0) return TaskRetryOutcome.Offline
        if (r.status >= 500) return TaskRetryOutcome.ServerError(r.status)
        return TaskRetryOutcome.Unknown(r.detail)
    }

    /**
     * 详情底部动作条出不出重试类的钮、出哪颗：失败 / 已取消才出；原因类别要 AsBefore；上一次重试已被说死不出；
     * 电脑操作任务换「重新发起」。
     */
    fun button(status: String, fail: TaskFailAction, blocked: Boolean, operate: Boolean): TaskRetryButton? {
        if (status != "failed" && status != "cancelled") return null
        if (fail != TaskFailAction.AsBefore || blocked) return null
        return if (operate) TaskRetryButton.Relaunch else TaskRetryButton.Retry
    }

    /** 钮下的灰色提示。电脑操作任务没有这行：那是从头再来，不是「接着跑」。 */
    fun hint(status: String, button: TaskRetryButton?): String? {
        if (button != TaskRetryButton.Retry) return null
        return if (status == "cancelled") TaskRetryCopy.hintCancelled else TaskRetryCopy.hintFailed
    }

    /** 聊天任务卡那颗钮。null = 不给钮（已不在、或上一次重试已被说死）。 */
    fun cardAction(status: String, fail: TaskFailAction, blocked: Boolean, operate: Boolean, gone: Boolean): TaskCardAction? {
        if (gone) return null
        if (status != "failed" && status != "cancelled") return TaskCardAction.ViewResult
        return when (fail) {
            is TaskFailAction.Topup -> TaskCardAction.Topup(fail.url)
            TaskFailAction.NoRetry -> TaskCardAction.ViewResult
            TaskFailAction.AsBefore -> when (button(status, fail, blocked, operate)) {
                TaskRetryButton.Retry -> TaskCardAction.Retry
                TaskRetryButton.Relaunch -> TaskCardAction.Relaunch
                null -> null
            }
        }
    }

    /** 「重新发起」填进输入框的那段：goal 优先、没有退回 name；空的就不跳（跳过去面对空输入框等于什么都没做）。 */
    fun relaunchText(goal: String, name: String?): String? {
        val g = goal.trim()
        if (g.isNotEmpty()) return g
        val n = (name ?: "").trim()
        return n.ifEmpty { null }
    }
}
