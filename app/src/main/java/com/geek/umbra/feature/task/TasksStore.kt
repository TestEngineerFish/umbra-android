package com.geek.umbra.feature.task

import com.geek.umbra.app.AppGraph
import com.geek.umbra.model.KnownDevice
import com.geek.umbra.model.TaskDetail
import com.geek.umbra.model.TaskItem
import com.geek.umbra.navigation.Tab
import com.geek.umbra.navigation.TabBadges
import com.geek.umbra.net.BackgroundWork
import com.geek.umbra.net.HttpResult
import com.geek.umbra.net.UmbraJson
import com.geek.umbra.net.UmbraUrl
import com.geek.umbra.net.str
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 任务的进程级状态 —— 对应 iOS Features/Task/TasksViewModel.swift（AppShell 里一个 @StateObject，全 App 共用）。
 * 工具页的卡片与角标也读这里（items / loadedOnce / firstLoading + 计数函数）。
 *
 * 列表每次拉最近 [LIST_LIMIT] 个，失败不清空；详情按 id 存，只有开着的详情页的回应才收（openDetails + detailEpoch）。
 */
object TasksStore {
    /** 列表一次拉最近多少个。服务端没有计数接口，拉满时工具卡说「最近 N 个」，不把截断后的数说成全部。 */
    const val LIST_LIMIT = 30

    private val _items = MutableStateFlow<List<TaskItem>>(emptyList())
    val items: StateFlow<List<TaskItem>> = _items

    /** 成功拉到过一次。**不能用 items.isEmpty 代替**：真的没有任务的账号也是空的，那时该画空态而不是错误卡。 */
    private val _loadedOnce = MutableStateFlow(false)
    val loadedOnce: StateFlow<Boolean> = _loadedOnce

    /**
     * 首屏那一次拉取还没有结果。**初值 true**、只落一次 —— 拿轮询里每轮都翻的 loading 当骨架判据的话，
     * 空账号会每 3.5 秒被整屏换成骨架再换回来（iOS: TasksViewModel.swift:349）。
     */
    private val _firstLoading = MutableStateFlow(true)
    val firstLoading: StateFlow<Boolean> = _firstLoading

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    /** 打开着的详情，按任务 id 存（聊天 tab 和工具 tab 可能各开着一个详情）。 */
    private val _details = MutableStateFlow<Map<String, TaskDetail>>(emptyMap())
    val details: StateFlow<Map<String, TaskDetail>> = _details

    /** 这个任务上一次没拉到是哪一档。拉到了就清掉。 */
    private val _detailMiss = MutableStateFlow<Map<String, TaskDetailMiss>>(emptyMap())
    val detailMiss: StateFlow<Map<String, TaskDetailMiss>> = _detailMiss

    /** 已不在的那几行（重试 / 接着跑回 missing）：id → 那一行当时的样子与位置。按「知道了」才移走。 */
    private val _gone = MutableStateFlow<Map<String, Pair<TaskItem, Int>>>(emptyMap())
    val gone: StateFlow<Map<String, Pair<TaskItem, Int>>> = _gone

    /** 现在开着详情页的任务 id；关页时撤掉。 */
    private val openDetails = mutableSetOf<String>()
    /** 每个任务详情被关过几次：关页之前发出的请求一律作废（「关了马上又开」也挡得住）。 */
    private val detailEpoch = mutableMapOf<String, Int>()

    /**
     * 电脑操作那次运行跑在哪台设备上（从 task_update 里记，iOS 记在 ChatViewModel.noteOperateDevice）。
     * 「看实时画面」要知道发给哪台；聊天模块那一份不归这里，这里自己从同一路帧里记一份。
     */
    private val operateTaskDevice = mutableMapOf<String, String>()
    private val operateRunDevice = mutableMapOf<String, String>()

    private var started = false
    private var frameRefreshJob: Job? = null

    // MARK: - 计数（工具页卡底 + 底栏角标；iOS ToolHomeView.swift:95 与 AppShell.swift:80 同一个判据）

    /** 执行中（状态映射同 UmbraStatus(taskStatus:) 的 running 档）。 */
    fun runningCount(list: List<TaskItem> = _items.value) = list.count { taskStatusOf(it.status) == TaskStatusKind.Running }
    fun failedCount(list: List<TaskItem> = _items.value) = list.count { taskStatusOf(it.status) == TaskStatusKind.Failed }
    /** 挂起且可以接着跑的。 */
    fun resumableCount(list: List<TaskItem> = _items.value) = list.count { it.status == "suspended" && (it.resume_ready ?: false) }
    /** 需人处理（角标数）：失败 + 可以接着跑了（TaskSuspendRules.needsAttention）。 */
    fun attentionCount(list: List<TaskItem> = _items.value) =
        list.count { TaskSuspendRules.needsAttention(it.status, it.resume_ready ?: false) }

    /** 底栏工具 Tab 的红角标 = 需人处理数（0 就撤掉）。 */
    private fun syncBadge() {
        val n = attentionCount()
        if (n > 0) TabBadges.counts[Tab.Tool] = n else TabBadges.counts.remove(Tab.Tool)
    }

    /** 首屏三态（offline 由页面传进来，别在这儿读全局 —— 一个页面只订阅一处）。 */
    fun screenPhase(offline: Boolean): ScreenPhase =
        ScreenPhase.resolve(_items.value.isNotEmpty(), _firstLoading.value, _loadedOnce.value, offline)

    // MARK: - 生命周期（TaskModule 在守卫上登记）

    /** 登录后：订阅 task_update，并拉一次（角标从开机就对）。 */
    fun sessionUnlocked() {
        if (!started) {
            started = true
            AppGraph.scope.launch {
                AppGraph.socket.frames.collect { f -> if (f.str("type") == "task_update") onTaskUpdate(f) }
            }
        }
        AppGraph.scope.launch { withContext(BackgroundWork) { loadTasks() } }
    }

    /** 登出：清掉上一个账号的任务，角标撤掉（守卫「关」）。 */
    fun sessionLocked() {
        _items.value = emptyList()
        _details.value = emptyMap()
        _detailMiss.value = emptyMap()
        _gone.value = emptyMap()
        _loadedOnce.value = false
        _firstLoading.value = true
        operateTaskDevice.clear(); operateRunDevice.clear()
        TabBadges.counts.remove(Tab.Tool)
    }

    /**
     * WS task_update：记下电脑操作跑在哪台设备上，然后刷新列表与开着的详情。
     * 进度帧可能一秒好几条 —— 合并成 400ms 内一次，别把每一帧都变成一次 HTTP。
     */
    private fun onTaskUpdate(f: JsonObject) {
        val conv = f.str("conversation")
        val fromConv = conv?.takeIf { it.startsWith("device:") }?.removePrefix("device:")
        val dev = f.str("device_id")?.takeIf { it.isNotEmpty() } ?: fromConv
        if (!dev.isNullOrEmpty()) {
            val run = f.str("run_id")
            val tid = f.str("task_id") ?: (f["task"] as? JsonObject)?.str("id")
            if (!run.isNullOrEmpty()) operateRunDevice[run] = dev
            if (!tid.isNullOrEmpty() && run != null) operateTaskDevice[tid] = dev
        }
        frameRefreshJob?.cancel()
        frameRefreshJob = AppGraph.scope.launch {
            delay(400)
            withContext(BackgroundWork) {
                loadTasks()
                openDetails.toList().forEach { loadTaskDetail(it) }
            }
        }
    }

    // MARK: - 列表

    /** 错误卡 / 离线条上那颗「重试」：**把首屏标志重新举起来**再拉，不然点下去界面完全不动。 */
    suspend fun retry() {
        _firstLoading.value = true
        loadTasks()
    }

    suspend fun loadTasks() {
        val fetched = AppGraph.http.get("/tasks", ListSerializer(TaskItem.serializer()), mapOf("limit" to LIST_LIMIT))
        // 拉失败（null）就保留旧列表 —— 刷新失败不该把已经在屏幕上的东西抹掉。
        if (fetched != null) {
            _items.value = fetched
            _loadedOnce.value = true
            syncBadge()
            com.geek.umbra.feature.widget.WidgetBridge.syncTasks(fetched)
            // iOS 这里同步小组件快照 + 灵动岛（UmbraWidgetBridge.syncTasks）。Android 的 Glance 小组件 / 进行中通知
            // 本期不在这一包里（二期），灵动岛 Android 无法实现。
        }
        // 无论成没成，首屏那一轮到此有结果了（成没成由 loadedOnce 说）。
        _firstLoading.value = false
    }

    suspend fun refreshTasks() {
        if (_refreshing.value) return
        _refreshing.value = true
        loadTasks()
        _refreshing.value = false
    }

    /** 列表实际要画的行：拉到的任务（剔掉已不在的）+ 已不在的按原位置插回。 */
    fun rowsWithGone(items: List<TaskItem>, gone: Map<String, Pair<TaskItem, Int>>): List<Pair<TaskItem, Boolean>> {
        val out = items.filter { gone[it.id] == null }.map { it to false }.toMutableList()
        for ((item, index) in gone.values.sortedBy { it.second }) {
            out.add(index.coerceIn(0, out.size), item to true)
        }
        return out
    }

    /** 把一个任务标成「已不在」。列表里没有它（比如从聊天卡进来的）就无从插回，什么都不做。 */
    fun markGone(id: String) {
        val i = _items.value.indexOfFirst { it.id == id }
        if (i < 0) return
        _gone.value = _gone.value + (id to (_items.value[i] to i))
    }

    /** 「知道了」：那一行移走。 */
    fun dismissGone(id: String) {
        _gone.value = _gone.value - id
        _items.value = _items.value.filter { it.id != id }
        syncBadge()
    }

    // MARK: - 详情

    /** 详情页登记「我开着」。幂等。 */
    fun openTaskDetail(id: String) { openDetails += id }

    /** 拉一次详情。**失败不清旧详情**；回应回来时页面已经关了（或关过又开）就丢掉。 */
    suspend fun loadTaskDetail(id: String) {
        val epoch = detailEpoch[id] ?: 0
        val r = AppGraph.http.raw("GET", "/tasks/${UmbraUrl.segment(id)}")
        if (!TaskDetailRules.acceptsResponse(id in openDetails, epoch, detailEpoch[id] ?: 0)) return
        val detail = (r as? HttpResult.Ok)?.let { runCatching { UmbraJson.decodeFromString(TaskDetail.serializer(), it.body) }.getOrNull() }
        if (detail != null) {
            _details.value = _details.value + (id to detail)
            _detailMiss.value = _detailMiss.value - id
        } else {
            // 2xx 但解不出来也交状态码进来 → Failed（解不出来不等于不存在）；没回 = null。
            val status = when (r) { is HttpResult.Ok -> r.status; is HttpResult.Fail -> r.status; else -> null }
            _detailMiss.value = _detailMiss.value + (id to TaskDetailRules.miss(status))
        }
    }

    /** 错误卡上那颗「重试」：先把上一次的失败清掉再拉 —— 页面回到骨架，看得见按下去有反应。 */
    suspend fun retryTaskDetail(id: String) {
        _detailMiss.value = _detailMiss.value - id
        loadTaskDetail(id)
    }

    /** 详情页**真的出栈**之后清掉它那一份（被别的 Tab 盖住时它还在栈里，不清）。 */
    fun closeTaskDetail(id: String) {
        openDetails -= id
        detailEpoch[id] = (detailEpoch[id] ?: 0) + 1
        _details.value = _details.value - id
        _detailMiss.value = _detailMiss.value - id
    }

    /** 强制结束一个任务，然后刷新列表。**把成没成交回去**：吐司要按回应说。 */
    suspend fun stopTask(id: String): Boolean {
        val r = AppGraph.http.raw("POST", "/tasks/${UmbraUrl.segment(id)}/stop")
        loadTasks()
        return r is HttpResult.Ok
    }

    // MARK: - 重试 / 接着跑（详情、聊天卡按下的是同一个函数）

    /** 重试与接着跑共用的那一次 POST：只取分档要的 status / code / state / detail（iOS: HTTPService.swift:218）。 */
    private suspend fun postTaskAction(path: String): TaskRetryResponse = when (val r = AppGraph.http.raw("POST", path)) {
        is HttpResult.Ok -> TaskRetryResponse(r.status, null, null, "HTTP ${r.status}")
        is HttpResult.Fail -> {
            val o = runCatching { UmbraJson.parseToJsonElement(r.body) as? JsonObject }.getOrNull()
            fun s(k: String) = (o?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content
            TaskRetryResponse(r.status, s("code") ?: r.code, s("state"), s("detail")?.takeIf { it.isNotEmpty() } ?: "HTTP ${r.status}")
        }
        is HttpResult.Offline -> TaskRetryResponse(0, null, null, r.message)
        is HttpResult.Blocked -> TaskRetryResponse(0, null, null, "未登录")
    }

    suspend fun runRetry(id: String): TaskRetryOutcome = TaskRetryRules.outcome(postTaskAction(TaskRetryRules.retryPath(id)))

    suspend fun runResume(id: String): TaskResumeOutcome = TaskSuspendRules.outcome(postTaskAction(TaskSuspendRules.resumePath(id)))

    /**
     * 重试之后的公共收尾：成功 / 状态过时重拉列表（详情开着一并重拉）；成功再隔 2 秒拉一次（服务端先放回 pending，
     * 再由执行轮翻成 running）；已不在记进 gone，列表那一行就地接管。
     */
    suspend fun afterRetry(id: String, o: TaskRetryOutcome) {
        if (o == TaskRetryOutcome.Missing) { markGone(id); return }
        if (!o.refreshes) return
        loadTasks()
        if (_details.value[id] != null) loadTaskDetail(id)
        if (o != TaskRetryOutcome.Ok) return
        delay(2_000)
        loadTasks()
    }

    /** 接着跑之后的公共收尾（同 afterRetry）。 */
    suspend fun afterResume(id: String, o: TaskResumeOutcome) {
        if (o == TaskResumeOutcome.Missing) { markGone(id); return }
        if (!o.refreshes) return
        loadTasks()
        if (_details.value[id] != null) loadTaskDetail(id)
        if (o != TaskResumeOutcome.Ok) return
        delay(2_000)
        loadTasks()
    }

    // MARK: - 看实时画面

    /**
     * 这次 operate 运行跑在哪台设备上：先按任务、再按 run_id 查见过的 task_update；都没见过就退到「唯一一台在线、
     * 报了远程能力的设备」（iOS: RemoteEntryViews.swift:67 `RemoteLauncher.device(forRun:)`）。认不出回 null。
     */
    suspend fun operateDevice(taskId: String, runId: String): String? {
        operateTaskDevice[taskId]?.let { return it }
        operateRunDevice[runId]?.let { return it }
        val all = AppGraph.http.get("/devices/all", ListSerializer(KnownDevice.serializer())) ?: return null
        val candidates = all.filter { it.online && it.remote != null }
        return candidates.singleOrNull()?.device_id
    }
}

/** 任务状态的几档（iOS: ChatThreadView.swift:2475 `UmbraStatus(taskStatus:)`）。纯判据，视图再映射到 UmbraStatus。 */
enum class TaskStatusKind { Running, Done, AwaitingReview, Failed, Pending, Cancelled, Suspended }

fun taskStatusOf(s: String): TaskStatusKind = when (s) {
    "done", "succeeded", "success" -> TaskStatusKind.Done
    "failed", "error" -> TaskStatusKind.Failed
    "awaiting_confirm" -> TaskStatusKind.AwaitingReview
    "suspended", "paused" -> TaskStatusKind.Suspended
    "pending", "queued" -> TaskStatusKind.Pending
    "cancelled", "canceled", "stopped" -> TaskStatusKind.Cancelled
    else -> TaskStatusKind.Running
}
