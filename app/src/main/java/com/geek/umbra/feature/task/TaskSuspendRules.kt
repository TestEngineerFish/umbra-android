// 任务挂起两档（设备离线 / 缺能力）+ 可以接着跑了：全部字面与判据。
// iOS: Features/Task/TaskSuspendCopy.swift + TaskSuspendRules.swift（设计侧批次 223 §二、224 §二）。
// 字面两端逐字同源（PC UmbraPC/src/features/tasks/taskSuspendCopy.ts），带参数的保留 `{{…}}` 占位原样。
// sam 已拍板：条件满足后**不自动续跑**，问用户、按「接着跑」才续跑。
// 纯 Kotlin，配 JUnit 用例（TaskSuspendRulesTest.kt + 两端共享夹具 TaskSuspendFixtureTest.kt）。
package com.geek.umbra.feature.task

import com.geek.umbra.app.Brand
import com.geek.umbra.model.TaskItem
import com.geek.umbra.model.TaskWaitingDevice
import com.geek.umbra.net.UmbraUrl
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** 挂起两档 + 接着跑的字面。除了 iosCapabilityHint（Android 同用），全部两端同源。 */
object TaskSuspendCopy {
    // 设备离线档
    const val deviceTitle = "在等「{{device}}」上线"
    const val deviceBody = "{{step}}要用这台设备上的能力，它现在不在线。上线后会问你要不要接着跑。"
    const val deviceListMeta = "已挂起 · 等「{{device}}」上线"
    const val deviceCard = "等「{{device}}」上线，上线后会问你要不要接着跑"
    const val devicesMany = "{{device}} 等 {{count}} 台设备"
    /** 一个名字都拿不到。不念 id。填进模板时**去掉它两边的书名号**。 */
    const val deviceUnnamed = "能做这一步的设备"

    // 缺能力档
    const val capabilityTitle = "缺能力：{{names}}"
    /** 超过两项：前两项 +「等 k 项」。「等」前面**没有空格**（稿原样）。 */
    const val capabilityMany = "{{names}}等 {{count}} 项"
    const val capabilitySeparator = "、"
    const val capabilityBodyMany = "哪台设备上都没有这几项能力。接上以后会问你要不要接着跑。"
    const val capabilityBodyOne = "哪台设备上都没有这项能力。接上以后会问你要不要接着跑。"
    const val capabilityEmptyTitle = "缺一项程序或 Skill"
    const val capabilityEmptyBody = "这一步要用的程序或 Skill 哪台设备上都没有。接上以后会问你要不要接着跑。"
    const val capabilityCardSuffix = "，接上后会问你要不要接着跑"

    // 两档共用
    const val listPrefix = "已挂起 · "
    const val stepNumbered = "第 {{n}} 步"
    const val stepUnknown = "这一步"
    const val stop = "停止"
    const val copyDetail = "复制详情"

    /** 手机上接不了 PC 的程序，所以不出「接入能力」，改说这件事去哪做（iOS 独有那句，Android 同样适用）。 */
    const val iosCapabilityHint = "在电脑上打开 ${Brand.productName} 的「能力」页接上。"

    // 可以接着跑了（resume_ready: true，批次 224）
    const val readyDeviceTitle = "「{{device}}」上线了，要接着跑吗？"
    const val readyCapabilityTitle = "需要的能力接上了，要接着跑吗？"
    const val readyBody = "按「接着跑」从{{step}}继续，已完成的里程碑不重做。不想要了就按「停止」。"
    const val readyListMeta = "已挂起 · 可以接着跑了"
    const val readyDeviceCard = "「{{device}}」上线了，可以接着跑了"
    const val readyCapabilityCard = "需要的能力接上了，可以接着跑了"
    const val resume = "接着跑"
    const val resuming = "继续中…"

    // 接着跑没成（写在钮下面那一行）
    const val notReadyDevice = "「{{device}}」刚才又离线了，任务还挂着。"
    const val notReadyCapability = "需要的能力刚才又不可用了，任务还挂着。"
    const val wrongStatus = "这个任务的状态刚变过，现在不能接着跑。详情已经刷新。"
    const val offline = "连不上服务器，没接上。联网后再按一次。"
    const val serverError = "服务端这次没有响应（HTTP {{code}}），过一会儿再按一次。"
    // 404 不在这里：同 222 missing，住在 TaskRetryCopy.missing，再抄一份就是两处。

    /** 把模板里的 `{{key}}` 换成值。只换给了的键；没给的占位原样留着（宁可露出来被看见）。 */
    fun fill(template: String, values: Map<String, String>): String {
        var out = template
        for ((k, v) in values) out = out.replace("{{$k}}", v)
        return out
    }
}

/**
 * 一个挂起任务的那七个字段（详情 task.* / 列表行 / task_update 三处同形）。缺字段一律按「没有」：
 * 老服务端不带这些键时 reason 是 null、resumeReady 是 false —— 按 device_offline 老规矩画设备档。
 */
data class TaskSuspendInfo(
    val reason: String? = null,
    val missingCapabilities: List<String> = emptyList(),
    /** 与 missingCapabilities 按位置一一对应的中文名（服务端给，端上不再映射）；缺的那格是空串。 */
    val labels: List<String> = emptyList(),
    val waitingDevices: List<TaskWaitingDevice> = emptyList(),
    /** 卡住的是第几步，1 起；null 念「这一步」。 */
    val step: Int? = null,
    val resumeReady: Boolean = false,
    /** device_online / capability_added / null。 */
    val readyReason: String? = null,
) {
    companion object {
        /**
         * 从 WS task_update 顶层读（那条路不走 serializer）。每个键都可缺、类型不对就当没有（iOS: TaskSuspendRules.swift:64）。
         * 中文名按位置对 id，所以**不能丢项**：null / 类型不对的那一格记成空串。
         */
        fun fromJson(o: JsonObject): TaskSuspendInfo {
            fun str(k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val caps = (o["missing_capabilities"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList()
            val labels = (o["missing_capability_labels"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: "" } ?: emptyList()
            val devs = (o["waiting_devices"] as? JsonArray)?.mapNotNull { d ->
                val obj = d as? JsonObject ?: return@mapNotNull null
                TaskWaitingDevice(
                    id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "",
                    name = (obj["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                )
            } ?: emptyList()
            val stepP = o["suspended_step"] as? JsonPrimitive
            val step = if (stepP == null || stepP is JsonNull || stepP.isString) null else stepP.intOrNull
            val readyP = o["resume_ready"] as? JsonPrimitive
            val ready = if (readyP == null || readyP.isString) false else readyP.booleanOrNull ?: false
            return TaskSuspendInfo(str("suspend_reason"), caps, labels, devs, step, ready, str("ready_reason"))
        }
    }
}

/** 七个字段收成判据要的形状（iOS: Models.swift `TaskItem.suspendInfo`）。读的时候一律经它，别在视图里碰裸字段。 */
val TaskItem.suspendInfo: TaskSuspendInfo
    get() = TaskSuspendInfo(
        reason = suspend_reason,
        missingCapabilities = missing_capabilities ?: emptyList(),
        labels = (missing_capability_labels ?: emptyList()).map { it ?: "" },
        waitingDevices = waiting_devices ?: emptyList(),
        step = suspended_step,
        resumeReady = resume_ready ?: false,
        readyReason = ready_reason,
    )

/** 挂起的哪一档。 */
enum class TaskSuspendKind { Device, Capability }

/** 一个挂起任务在各处该写的话。视图只管摆，不拼句子。 */
data class TaskSuspendDisplay(
    val title: String,
    val body: String,
    /** 缺能力档、还没就绪时才有那一句。 */
    val iosHint: String?,
    val ready: Boolean,
    val listMeta: String,
    val cardText: String,
) {
    /** 列表行用不用琥珀字（设计侧 225）：**只有「可以接着跑了」琥珀**，等待档灰。 */
    val listMetaWarn: Boolean get() = ready
}

/** POST /tasks/{id}/resume 的分档（224 §二 4）。 */
sealed interface TaskResumeOutcome {
    data object Ok : TaskResumeOutcome
    data object NotReady : TaskResumeOutcome
    data object WrongStatus : TaskResumeOutcome
    data object Missing : TaskResumeOutcome
    data object Offline : TaskResumeOutcome
    data class ServerError(val code: Int) : TaskResumeOutcome
    data class Unknown(val detail: String) : TaskResumeOutcome

    /** 钮下那一行。not_ready 要按档写，所以要按下那一刻的挂起字段。 */
    fun message(info: TaskSuspendInfo): String? = when (this) {
        Ok -> null
        NotReady -> if (TaskSuspendRules.kind(info) == TaskSuspendKind.Device)
            TaskSuspendRules.fillDevice(TaskSuspendCopy.notReadyDevice, info.waitingDevices)
        else TaskSuspendCopy.notReadyCapability
        WrongStatus -> TaskSuspendCopy.wrongStatus
        Missing -> TaskRetryCopy.missing
        Offline -> TaskSuspendCopy.offline
        is ServerError -> TaskSuspendCopy.fill(TaskSuspendCopy.serverError, mapOf("code" to code.toString()))
        is Unknown -> detail
    }

    /** 这一档之后「接着跑」还留不留：断网 / 5xx / 未知再按一次可能就好；其余再按也是同样结果。 */
    val keepsResume: Boolean
        get() = when (this) {
            Offline, is ServerError, is Unknown -> true
            Ok, NotReady, WrongStatus, Missing -> false
        }

    /** 顺手重拉一次：成功要看到执行中；not_ready / wrong_status 说明本端看到的已经旧了。 */
    val refreshes: Boolean get() = this == Ok || this == NotReady || this == WrongStatus
}

object TaskSuspendRules {
    /** 发「接着跑」的路径（按一个路径段编码 id）。 */
    fun resumePath(id: String): String = "/tasks/${UmbraUrl.segment(id)}/resume"

    /** 哪一档：就绪时先看 ready_reason；挂起原因只有 no_capability 是能力档，其余（含 null、新值）一律设备档。 */
    fun kind(info: TaskSuspendInfo): TaskSuspendKind {
        if (info.resumeReady) {
            if (info.readyReason == "capability_added") return TaskSuspendKind.Capability
            if (info.readyReason == "device_online") return TaskSuspendKind.Device
        }
        return if (info.reason == "no_capability") TaskSuspendKind.Capability else TaskSuspendKind.Device
    }

    /** 把设备那一格填进模板。**没有名字时不带书名号**：「在等能做这一步的设备上线」。 */
    fun fillDevice(template: String, devices: List<TaskWaitingDevice>): String {
        val slot = devicePhrase(devices)
        val s = TaskSuspendCopy.fill(template, mapOf("device" to slot))
        if (slot != TaskSuspendCopy.deviceUnnamed) return s
        return s.replace("「$slot」", slot)
    }

    /** 念哪台设备：一台有名字 → 名字；不止一台 → 第一台有名字的 +「等 k 台设备」；一个名字都没有 →「能做这一步的设备」。不念 id。 */
    fun devicePhrase(devices: List<TaskWaitingDevice>): String {
        val named = devices.mapNotNull { d -> (d.name ?: "").trim().takeIf { it.isNotEmpty() } }
        val first = named.firstOrNull() ?: return TaskSuspendCopy.deviceUnnamed
        if (devices.size <= 1) return first
        return TaskSuspendCopy.fill(TaskSuspendCopy.devicesMany, mapOf("device" to first, "count" to devices.size.toString()))
    }

    /** 缺的能力名：以 labels 为准按位置对 ids，某项中文名缺了就念它的 id；id 一个都没有时只看 labels（去空）。 */
    fun capabilityList(info: TaskSuspendInfo): List<String> {
        val ids = info.missingCapabilities.filter { it.isNotEmpty() }
        val labels = info.labels.map { it.trim() }
        if (ids.isEmpty()) return labels.filter { it.isNotEmpty() }
        return ids.mapIndexed { i, id -> if (i < labels.size && labels[i].isNotEmpty()) labels[i] else id }
    }

    /** 缺的能力念成一串。最多列两项，再多写「A、B等 k 项」；一项都没有 → null。 */
    fun capabilityNames(names: List<String>): String? {
        if (names.isEmpty()) return null
        val sep = TaskSuspendCopy.capabilitySeparator
        if (names.size <= 2) return names.joinToString(sep)
        return TaskSuspendCopy.fill(
            TaskSuspendCopy.capabilityMany,
            mapOf("names" to names.take(2).joinToString(sep), "count" to names.size.toString()),
        )
    }

    /** 第几步。null、0、负数都念「这一步」。 */
    fun stepPhrase(step: Int?): String {
        if (step == null || step < 1) return TaskSuspendCopy.stepUnknown
        return TaskSuspendCopy.fill(TaskSuspendCopy.stepNumbered, mapOf("n" to step.toString()))
    }

    /** 一个挂起任务在各处写什么。不是挂起 → null。 */
    fun display(status: String, info: TaskSuspendInfo): TaskSuspendDisplay? {
        if (status != "suspended") return null
        val kind = kind(info)
        val dev = { t: String -> fillDevice(t, info.waitingDevices) }
        val step = stepPhrase(info.step)
        if (info.resumeReady) {
            return TaskSuspendDisplay(
                title = if (kind == TaskSuspendKind.Device) dev(TaskSuspendCopy.readyDeviceTitle) else TaskSuspendCopy.readyCapabilityTitle,
                body = TaskSuspendCopy.fill(TaskSuspendCopy.readyBody, mapOf("step" to step)),
                iosHint = null,
                ready = true,
                listMeta = TaskSuspendCopy.readyListMeta,
                cardText = if (kind == TaskSuspendKind.Device) dev(TaskSuspendCopy.readyDeviceCard) else TaskSuspendCopy.readyCapabilityCard,
            )
        }
        return when (kind) {
            TaskSuspendKind.Device -> TaskSuspendDisplay(
                title = dev(TaskSuspendCopy.deviceTitle),
                body = TaskSuspendCopy.fill(TaskSuspendCopy.deviceBody, mapOf("step" to step)),
                iosHint = null,
                ready = false,
                listMeta = dev(TaskSuspendCopy.deviceListMeta),
                cardText = dev(TaskSuspendCopy.deviceCard),
            )
            TaskSuspendKind.Capability -> {
                val list = capabilityList(info)
                val names = capabilityNames(list)
                val title: String
                val body: String
                if (names != null) {
                    title = TaskSuspendCopy.fill(TaskSuspendCopy.capabilityTitle, mapOf("names" to names))
                    body = if (list.size == 1) TaskSuspendCopy.capabilityBodyOne else TaskSuspendCopy.capabilityBodyMany
                } else {
                    title = TaskSuspendCopy.capabilityEmptyTitle
                    body = TaskSuspendCopy.capabilityEmptyBody
                }
                TaskSuspendDisplay(
                    title = title, body = body,
                    iosHint = TaskSuspendCopy.iosCapabilityHint,
                    ready = false,
                    listMeta = TaskSuspendCopy.listPrefix + title,
                    cardText = title + TaskSuspendCopy.capabilityCardSuffix,
                )
            }
        }
    }

    /** 出不出「接着跑」（详情动作条、聊天任务卡、助手问话三处同一个条件）。 */
    fun showsResume(status: String, info: TaskSuspendInfo): Boolean = status == "suspended" && info.resumeReady

    /** 任务角标数不数它：失败（failed / error 两个字面）+ 可以接着跑了；只是挂着等的不算。 */
    fun needsAttention(status: String, resumeReady: Boolean): Boolean =
        status == "failed" || status == "error" || (status == "suspended" && resumeReady)

    /** 把一次回应分档。只按 state 分（同重试）。 */
    fun outcome(r: TaskRetryResponse): TaskResumeOutcome {
        if (r.status in 200..299) return TaskResumeOutcome.Ok
        if (r.status == 404 || r.state == "missing") return TaskResumeOutcome.Missing
        if (r.status == 409 && r.code == "state_conflict") {
            return when (r.state) {
                "not_ready" -> TaskResumeOutcome.NotReady
                "wrong_status" -> TaskResumeOutcome.WrongStatus
                else -> TaskResumeOutcome.Unknown(r.detail)
            }
        }
        if (r.status == 0) return TaskResumeOutcome.Offline
        if (r.status >= 500) return TaskResumeOutcome.ServerError(r.status)
        return TaskResumeOutcome.Unknown(r.detail)
    }

    /** 按下 not_ready 之后本端先把「就绪」撤掉（服务端那条 resume_ready:false 推送可能晚到或丢）。 */
    fun afterNotReady(info: TaskSuspendInfo): TaskSuspendInfo = info.copy(resumeReady = false, readyReason = null)
}
