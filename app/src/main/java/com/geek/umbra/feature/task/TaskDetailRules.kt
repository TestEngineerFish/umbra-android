// 任务详情页「拉不到」怎么分档、什么时候继续拉（iOS: Features/Task/TaskDetailRules.swift，umbra-ios#38）。
//
// 纯 Kotlin、不碰 android.*：配 JUnit 用例（app/src/test/.../feature/task/TaskDetailRulesTest.kt），
// 输入输出照抄 iOS Tools/swifttests/TaskDetailRulesTests.swift。
//
// 为什么要这个文件：详情原来只有「有 / 没有」两态，404、5xx、断网、解码失败统统被压成 null，
// 页面又把 null 只解释成「还在加载」—— 进一个已在电脑上删掉的任务、或进入那一刻断网，
// 页面永远停在一张静止的骨架上。属于「读不出来被吞成『空』」那一类。
package com.geek.umbra.feature.task

/**
 * 这一次没拉到详情是哪一种。两档，按下一步分：
 * - NotFound：服务端说没有这个任务（404）—— 重试多少次都一样，给一句话 + 返回；
 * - Failed：其余一切（断网、超时、5xx、解码失败）—— 等等再来，给错误卡 + 重试。
 */
enum class TaskDetailMiss { NotFound, Failed }

/** 详情页那一屏画哪档。 */
enum class TaskDetailPhase { Content, Skeleton, NotFound, Failed }

object TaskDetailRules {
    /** 非终态（「停止」出不出、详情要不要接着拉都看它）。只有这一处定义（iOS: TaskDetailRules.swift:35）。 */
    val activeStatuses = setOf("running", "pending", "suspended")

    fun isActive(status: String): Boolean = status in activeStatuses

    /** 详情页自己轮询的间隔，与列表轮询同一个数 —— 两处各写一个数，同一个任务的进度会按两种节奏跳。 */
    const val POLL_MS = 3_500L
    const val POLL_SECONDS = 3.5

    /**
     * 一次失败的请求 → 哪一档。`status == null` = 请求没发出去 / 没回。
     * **只认 404**；2xx 但解不出来也走这里 → Failed：解不出来不等于不存在（iOS: TaskDetailRules.swift:47）。
     */
    fun miss(status: Int?): TaskDetailMiss = if (status == 404) TaskDetailMiss.NotFound else TaskDetailMiss.Failed

    /**
     * 这一屏画哪档。顺序有讲究：NotFound 最先（哪怕手上有旧详情 —— 再挂「停止」「重试」只会换来「不在了」）；
     * 其次有内容就给内容（轮询偶尔失败一次不把正在看的人换成错误卡）；再 Failed；剩下的是还在拉 → 骨架。
     */
    fun phase(hasDetail: Boolean, miss: TaskDetailMiss?): TaskDetailPhase = when {
        miss == TaskDetailMiss.NotFound -> TaskDetailPhase.NotFound
        hasDetail -> TaskDetailPhase.Content
        miss == TaskDetailMiss.Failed -> TaskDetailPhase.Failed
        else -> TaskDetailPhase.Skeleton
    }

    /**
     * 拉完这一轮之后还要不要接着拉：不存在了不拉；还没拿到过（首拉失败）接着拉 —— 网络回来页面自己好；
     * 拿到了只有非终态才接着拉。重试 / 接着跑让它变回非终态时，详情页的轮询键跟着变、重新起一轮。
     */
    fun keepPolling(status: String?, miss: TaskDetailMiss?): Boolean {
        if (miss == TaskDetailMiss.NotFound) return false
        status ?: return true
        return isActive(status)
    }

    /**
     * 一次详情请求回来时还收不收：页面已经不在任何一条栈里不收；发出之后页面关过（代次变了）也不收 ——
     * 「关了马上又开」时旧页那一次晚到的回应会盖在新页刚拉到的那份上。
     */
    fun acceptsResponse(open: Boolean, sentEpoch: Int, nowEpoch: Int): Boolean = open && sentEpoch == nowEpoch

    /**
     * 状态变成 newStatus 时「接着跑没成」那一行还留不留：那一行只在挂起时有意义，**离开 suspended 就清**；
     * 还在 suspended（同状态重拉）不清，否则 not_ready 那句一重拉就没了。null 不动。
     */
    fun dropsResumeFail(newStatus: String?): Boolean {
        val s = newStatus ?: return false
        return s != "suspended"
    }

    /**
     * 「来自 X」那一格显示什么；null = 不显示。列表行、详情头、详情信息格、复制全文四处都从这里取 ——
     * 判空各写各的，下次就是列表显示、详情不显示（iOS: TaskDetailRules.swift:104）。
     */
    fun channelLabel(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }
}
