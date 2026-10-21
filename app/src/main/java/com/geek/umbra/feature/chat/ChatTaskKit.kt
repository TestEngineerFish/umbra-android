// 聊天任务卡要用的那一截任务判据：重试 / 接着跑的分档、卡上收尾钮、挂起两档的卡上那句话。
//
// 出处：iOS Features/Task/TaskRetryRules.swift、TaskSuspendRules.swift、TaskSuspendCopy.swift（设计侧批次 222 / 223 / 224）。
// ⚠️ 这些本该住在任务模块（feature/task），但任务模块的移植与聊天并行、没有给聊天开桩 —— 聊天卡上的「重试任务」
// 「接着跑」「重新发起」又必须就地调接口（iOS 卡上那颗与详情底部那颗是**同一个函数**）。所以先在聊天包里放一份
// 只含聊天要用的那一截，字面逐字照抄 iOS；合并时应与任务模块的同名判据合成一份（报告里已写）。
// 纯判据，不 import android.*，用例照抄 iOS TaskRetryRulesTests / TaskSuspendRulesTests 里与聊天卡相关的几组。
package com.geek.umbra.feature.chat

import com.geek.umbra.model.TaskItem
import com.geek.umbra.net.UmbraUrl
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** 重试相关字面（iOS: TaskRetryRules.swift:33 `TaskRetryCopy`，与 PC 逐字同）。 */
object TaskRetryCopy {
    const val RETRY = "重试任务"
    const val RETRYING = "重试中…"
    const val RELAUNCH = "重新发起"
    const val MISSING = "这个任务已经不在了，可能在另一台设备上删掉了。"
    const val WRONG_STATUS = "这个任务的状态刚变过，现在不能重试。详情已经刷新。"
    const val NOTHING_TO_RETRY = "这个任务的每一步都已经跑完，没有要重跑的。"
    const val OPERATE = "电脑操作任务不能从中间接着跑，屏幕已经变了，要从头再来一次。"
    const val NOT_RETRYABLE = "这个任务不能重试：{{detail}}"
    const val OFFLINE = "连不上服务器，重试没发出去。联网后再按一次。"
    const val SERVER_ERROR = "服务端这次没有响应（HTTP {{code}}），过一会儿再按一次。"
}

/** 挂起两档 + 接着跑的字面（iOS: TaskSuspendCopy.swift，两端同源）。只收聊天卡用得到的那几句。 */
object TaskSuspendCopy {
    const val DEVICE_CARD = "等「{{device}}」上线，上线后会问你要不要接着跑"
    const val DEVICES_MANY = "{{device}} 等 {{count}} 台设备"
    const val DEVICE_UNNAMED = "能做这一步的设备"
    const val CAPABILITY_TITLE = "缺能力：{{names}}"
    const val CAPABILITY_MANY = "{{names}}等 {{count}} 项"
    const val CAPABILITY_SEPARATOR = "、"
    const val CAPABILITY_EMPTY_TITLE = "缺一项程序或 Skill"
    const val CAPABILITY_CARD_SUFFIX = "，接上后会问你要不要接着跑"
    const val READY_DEVICE_CARD = "「{{device}}」上线了，可以接着跑了"
    const val READY_CAPABILITY_CARD = "需要的能力接上了，可以接着跑了"
    const val RESUME = "接着跑"
    const val RESUMING = "继续中…"
    const val NOT_READY_DEVICE = "「{{device}}」刚才又离线了，任务还挂着。"
    const val NOT_READY_CAPABILITY = "需要的能力刚才又不可用了，任务还挂着。"
    const val WRONG_STATUS = "这个任务的状态刚变过，现在不能接着跑。详情已经刷新。"
    const val OFFLINE = "连不上服务器，没接上。联网后再按一次。"
    const val SERVER_ERROR = "服务端这次没有响应（HTTP {{code}}），过一会儿再按一次。"

    /** 把模板里的 `{{key}}` 换成值；没给的占位原样留着（宁可露出来被看见）。 */
    fun fill(template: String, values: Map<String, String>): String {
        var out = template
        for ((k, v) in values) out = out.replace("{{$k}}", v)
        return out
    }
}

/** 挂起那七个字段（iOS: TaskSuspendRules.swift:38）。缺字段一律按「没有」。 */
data class TaskSuspendInfo(
    val reason: String? = null,
    val missingCapabilities: List<String> = emptyList(),
    val labels: List<String> = emptyList(),
    /** (id, name)，两个都可缺。 */
    val waitingDevices: List<Pair<String?, String?>> = emptyList(),
    val step: Int? = null,
    val resumeReady: Boolean = false,
    val readyReason: String? = null,
) {
    companion object {
        /** 从 WS `task_update` 顶层读。中文名按位置对 id，所以不能丢项：null / 类型不对记成空串。 */
        fun of(json: JsonObject): TaskSuspendInfo {
            fun s(k: String) = (json[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return TaskSuspendInfo(
                reason = s("suspend_reason"),
                missingCapabilities = (json["missing_capabilities"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList(),
                labels = (json["missing_capability_labels"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: "" } ?: emptyList(),
                waitingDevices = (json["waiting_devices"] as? JsonArray)?.mapNotNull { d ->
                    val o = d as? JsonObject ?: return@mapNotNull null
                    (o["id"] as? JsonPrimitive)?.contentOrNull to (o["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                } ?: emptyList(),
                step = (json["suspended_step"] as? JsonPrimitive)?.intOrNull,
                resumeReady = (json["resume_ready"] as? JsonPrimitive)?.booleanOrNull ?: false,
                readyReason = s("ready_reason"),
            )
        }

        /** 从任务详情里那份 TaskItem 收。 */
        fun of(t: TaskItem): TaskSuspendInfo = TaskSuspendInfo(
            reason = t.suspend_reason,
            missingCapabilities = t.missing_capabilities ?: emptyList(),
            labels = (t.missing_capability_labels ?: emptyList()).map { it ?: "" },
            waitingDevices = (t.waiting_devices ?: emptyList()).map { it.id to it.name },
            step = t.suspended_step,
            resumeReady = t.resume_ready ?: false,
            readyReason = t.ready_reason,
        )
    }
}

/** 一次 POST /tasks/{id}/retry 或 /resume 的回应，只留分档要用的几样。status 0 = 根本没拿到响应。 */
data class TaskActionResponse(val status: Int, val code: String?, val state: String?, val detail: String)

/** 重试分档（iOS: TaskRetryRules.swift:79）。 */
sealed interface TaskRetryOutcome {
    data object Ok : TaskRetryOutcome
    data object Missing : TaskRetryOutcome
    data object WrongStatus : TaskRetryOutcome
    data object NothingToRetry : TaskRetryOutcome
    data object Operate : TaskRetryOutcome
    data class NotRetryable(val detail: String) : TaskRetryOutcome
    data object Offline : TaskRetryOutcome
    data class ServerError(val code: Int) : TaskRetryOutcome
    data class Unknown(val detail: String) : TaskRetryOutcome

    /** 写在钮下那一行的话；Ok 没有（成功不提示）。 */
    val message: String?
        get() = when (this) {
            Ok -> null
            Missing -> TaskRetryCopy.MISSING
            WrongStatus -> TaskRetryCopy.WRONG_STATUS
            NothingToRetry -> TaskRetryCopy.NOTHING_TO_RETRY
            Operate -> TaskRetryCopy.OPERATE
            is NotRetryable -> TaskRetryCopy.NOT_RETRYABLE.replace("{{detail}}", detail)
            Offline -> TaskRetryCopy.OFFLINE
            is ServerError -> TaskRetryCopy.SERVER_ERROR.replace("{{code}}", code.toString())
            is Unknown -> detail
        }

    /** 这一档之后还给不给「重试任务」。 */
    val keepsRetry: Boolean
        get() = when (this) {
            Ok, WrongStatus, Offline, is ServerError, is Unknown -> true
            Missing, NothingToRetry, Operate, is NotRetryable -> false
        }

    val refreshes: Boolean get() = this == Ok || this == WrongStatus
}

/** 接着跑分档（iOS: TaskSuspendRules.swift:102）。 */
sealed interface TaskResumeOutcome {
    data object Ok : TaskResumeOutcome
    data object NotReady : TaskResumeOutcome
    data object WrongStatus : TaskResumeOutcome
    data object Missing : TaskResumeOutcome
    data object Offline : TaskResumeOutcome
    data class ServerError(val code: Int) : TaskResumeOutcome
    data class Unknown(val detail: String) : TaskResumeOutcome

    /** 钮下那一行；not_ready 要按**按下那一刻**的挂起字段写。 */
    fun message(info: TaskSuspendInfo): String? = when (this) {
        Ok -> null
        NotReady -> if (ChatTaskKit.suspendKind(info) == SuspendKind.Device)
            ChatTaskKit.fillDevice(TaskSuspendCopy.NOT_READY_DEVICE, info.waitingDevices) else TaskSuspendCopy.NOT_READY_CAPABILITY
        WrongStatus -> TaskSuspendCopy.WRONG_STATUS
        Missing -> TaskRetryCopy.MISSING
        Offline -> TaskSuspendCopy.OFFLINE
        is ServerError -> TaskSuspendCopy.fill(TaskSuspendCopy.SERVER_ERROR, mapOf("code" to code.toString()))
        is Unknown -> detail
    }

    val keepsResume: Boolean get() = this is Offline || this is ServerError || this is Unknown
    val refreshes: Boolean get() = this == Ok || this == NotReady || this == WrongStatus
}

enum class SuspendKind { Device, Capability }

/** 聊天任务卡收尾那颗钮（iOS: TaskRetryRules.swift:150 `TaskCardAction`）。 */
sealed interface TaskCardAction {
    data object Retry : TaskCardAction
    data object Relaunch : TaskCardAction
    data class Topup(val url: String) : TaskCardAction
    data object ViewResult : TaskCardAction
}

object ChatTaskKit {
    /**
     * 聊天任务卡上要不要在原来那颗钮之外**再给一颗「重试任务」**（PC 聊天任务卡 `retryHere`，批次 278）：
     * 补救类失败（额度用完 / 没会员 / 欠费）原来那颗是「去买」/「查看结果」，人补救完（就地用了重置卡）得有地方点重试。
     * 原来那颗已经是重试 / 重新发起、上一次重试被说死、任务已经不在了 —— 都不再加。
     */
    fun cardFixRetry(status: String, reasonKind: String?, blocked: Boolean, gone: Boolean, base: TaskCardAction?): Boolean =
        status == "failed" && com.geek.umbra.feature.task.TaskRetryRules.retryAfterFix(reasonKind) && !blocked && !gone && base != TaskCardAction.Retry && base != TaskCardAction.Relaunch


    /** 任务模型就这三个非终态（iOS: TaskDetailRules.swift:35）。 */
    private val activeStatuses = setOf("running", "pending", "suspended")
    fun isActive(status: String) = status in activeStatuses

    fun retryPath(id: String) = "/tasks/${UmbraUrl.segment(id)}/retry"
    fun resumePath(id: String) = "/tasks/${UmbraUrl.segment(id)}/resume"

    /** 只看服务端 `executor == "operate"`。 */
    fun isOperate(executor: String?) = executor == "operate"

    /** 重试回应分档：只按 state 分，不读 detail 那句中文。 */
    fun retryOutcome(r: TaskActionResponse): TaskRetryOutcome {
        if (r.status in 200..299) return TaskRetryOutcome.Ok
        if (r.status == 404 || r.state == "missing") return TaskRetryOutcome.Missing
        if (r.status == 409 && r.code == "state_conflict") return when (r.state) {
            "wrong_status" -> TaskRetryOutcome.WrongStatus
            "nothing_to_retry" -> TaskRetryOutcome.NothingToRetry
            "operate" -> TaskRetryOutcome.Operate
            "not_retryable" -> TaskRetryOutcome.NotRetryable(r.detail)
            else -> TaskRetryOutcome.Unknown(r.detail)
        }
        if (r.status == 0) return TaskRetryOutcome.Offline
        if (r.status >= 500) return TaskRetryOutcome.ServerError(r.status)
        return TaskRetryOutcome.Unknown(r.detail)
    }

    /** 接着跑回应分档。 */
    fun resumeOutcome(r: TaskActionResponse): TaskResumeOutcome {
        if (r.status in 200..299) return TaskResumeOutcome.Ok
        if (r.status == 404 || r.state == "missing") return TaskResumeOutcome.Missing
        if (r.status == 409 && r.code == "state_conflict") return when (r.state) {
            "not_ready" -> TaskResumeOutcome.NotReady
            "wrong_status" -> TaskResumeOutcome.WrongStatus
            else -> TaskResumeOutcome.Unknown(r.detail)
        }
        if (r.status == 0) return TaskResumeOutcome.Offline
        if (r.status >= 500) return TaskResumeOutcome.ServerError(r.status)
        return TaskResumeOutcome.Unknown(r.detail)
    }

    /** 聊天任务卡那颗钮。null = 不给钮（已不在、或上一次重试已被说死）。iOS: TaskRetryRules.swift:181 */
    fun cardAction(status: String, fail: TaskFailAction, blocked: Boolean, operate: Boolean, gone: Boolean): TaskCardAction? {
        if (gone) return null
        if (status != "failed" && status != "cancelled") return TaskCardAction.ViewResult
        return when (fail) {
            is TaskFailAction.Topup -> TaskCardAction.Topup(fail.url)
            TaskFailAction.NoRetry -> TaskCardAction.ViewResult
            TaskFailAction.AsBefore -> if (blocked) null else if (operate) TaskCardAction.Relaunch else TaskCardAction.Retry
        }
    }

    /** 「重新发起」填进输入框的那段：goal 优先、没有退回 name；空的就不跳。 */
    fun relaunchText(goal: String, name: String?): String? {
        val g = goal.trim()
        if (g.isNotEmpty()) return g
        val n = (name ?: "").trim()
        return n.ifEmpty { null }
    }

    /** 哪一档：就绪时先看 ready_reason；挂起原因只有 no_capability 是能力档，其余一律设备档。 */
    fun suspendKind(info: TaskSuspendInfo): SuspendKind {
        if (info.resumeReady) {
            if (info.readyReason == "capability_added") return SuspendKind.Capability
            if (info.readyReason == "device_online") return SuspendKind.Device
        }
        return if (info.reason == "no_capability") SuspendKind.Capability else SuspendKind.Device
    }

    /** 设备那一格填进模板；没有名字时不带书名号。 */
    fun fillDevice(template: String, devices: List<Pair<String?, String?>>): String {
        val slot = devicePhrase(devices)
        val s = TaskSuspendCopy.fill(template, mapOf("device" to slot))
        if (slot != TaskSuspendCopy.DEVICE_UNNAMED) return s
        return s.replace("「$slot」", slot)
    }

    /** 念哪台设备：一台有名字 → 名字；不止一台 → 第一台有名字的 + 等 k 台；一个名字都没有 → 能做这一步的设备。不念 id。 */
    fun devicePhrase(devices: List<Pair<String?, String?>>): String {
        val named = devices.mapNotNull { d -> d.second?.trim()?.takeIf { it.isNotEmpty() } }
        val first = named.firstOrNull() ?: return TaskSuspendCopy.DEVICE_UNNAMED
        if (devices.size <= 1) return first
        return TaskSuspendCopy.fill(TaskSuspendCopy.DEVICES_MANY, mapOf("device" to first, "count" to devices.size.toString()))
    }

    /** 缺的能力名：以 labels 为准、按位置对 id；缺中文名念 id。 */
    fun capabilityList(info: TaskSuspendInfo): List<String> {
        val ids = info.missingCapabilities.filter { it.isNotEmpty() }
        val labels = info.labels.map { it.trim() }
        if (ids.isEmpty()) return labels.filter { it.isNotEmpty() }
        return ids.mapIndexed { i, id -> if (i < labels.size && labels[i].isNotEmpty()) labels[i] else id }
    }

    fun capabilityNames(names: List<String>): String? {
        if (names.isEmpty()) return null
        val sep = TaskSuspendCopy.CAPABILITY_SEPARATOR
        if (names.size <= 2) return names.joinToString(sep)
        return TaskSuspendCopy.fill(TaskSuspendCopy.CAPABILITY_MANY, mapOf("names" to names.take(2).joinToString(sep), "count" to names.size.toString()))
    }

    /** 挂起任务在聊天卡上那一句；不是挂起 → null（卡上照旧画服务端那句 message）。iOS: TaskSuspendRules.swift:216 cardText */
    fun suspendCardText(status: String, info: TaskSuspendInfo): String? {
        if (status != "suspended") return null
        val kind = suspendKind(info)
        if (info.resumeReady) {
            return if (kind == SuspendKind.Device) fillDevice(TaskSuspendCopy.READY_DEVICE_CARD, info.waitingDevices)
            else TaskSuspendCopy.READY_CAPABILITY_CARD
        }
        return when (kind) {
            SuspendKind.Device -> fillDevice(TaskSuspendCopy.DEVICE_CARD, info.waitingDevices)
            SuspendKind.Capability -> {
                val title = capabilityNames(capabilityList(info))?.let { TaskSuspendCopy.fill(TaskSuspendCopy.CAPABILITY_TITLE, mapOf("names" to it)) }
                    ?: TaskSuspendCopy.CAPABILITY_EMPTY_TITLE
                title + TaskSuspendCopy.CAPABILITY_CARD_SUFFIX
            }
        }
    }

    /** 出不出「接着跑」：现在是挂起、且服务端说条件满足了。 */
    fun showsResume(status: String, info: TaskSuspendInfo) = status == "suspended" && info.resumeReady

    /** not_ready 之后本端先把「就绪」撤掉。 */
    fun afterNotReady(info: TaskSuspendInfo) = info.copy(resumeReady = false, readyReason = null)
}
