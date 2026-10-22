// 任务 · 列表与详情（iOS: Features/Task/TaskViews.swift）。
//
// 设计稿里有、端上还没接的两块这里**不画**（同 iOS）：「验收清单」、「生成结果 / 下载结果」。
// 「计划」分段是设计稿自己标了「二期 · 待服务端」的，这里如实说明，不放假数据。
// 重试 / 挂起 / 接着跑的判据与字面全在 TaskRetryRules.kt / TaskSuspendRules.kt —— 这里一句都不手写。
package com.geek.umbra.feature.task

import com.geek.umbra.app.Brand
import com.geek.umbra.designsystem.UmbraDimens
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraButton
import com.geek.umbra.designsystem.UmbraButtonKind
import com.geek.umbra.designsystem.UmbraCard
import com.geek.umbra.designsystem.UmbraEmptyState
import com.geek.umbra.designsystem.UmbraErrorCard
import com.geek.umbra.designsystem.UmbraFieldLabel
import com.geek.umbra.designsystem.UmbraFilterChips
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraPhaseFailedCard
import com.geek.umbra.designsystem.UmbraProgressBar
import com.geek.umbra.designsystem.UmbraScreen
import com.geek.umbra.designsystem.UmbraScreenPhase
import com.geek.umbra.designsystem.UmbraSectionLabel
import com.geek.umbra.designsystem.UmbraSegment
import com.geek.umbra.designsystem.UmbraSegmentedControl
import com.geek.umbra.designsystem.UmbraSkeleton
import com.geek.umbra.designsystem.UmbraSpinningIcon
import com.geek.umbra.designsystem.UmbraStatus
import com.geek.umbra.designsystem.UmbraStatusBadge
import com.geek.umbra.designsystem.UmbraTagPill
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraType
import com.geek.umbra.designsystem.umbraPhaseTop
import com.geek.umbra.feature.chat.ChatApi
import com.geek.umbra.feature.remote.RemoteLauncher
import com.geek.umbra.model.StepError
import com.geek.umbra.model.TaskDetail
import com.geek.umbra.model.TaskEvent
import com.geek.umbra.model.TaskItem
import com.geek.umbra.model.TaskStep
import com.geek.umbra.navigation.Route
import com.geek.umbra.navigation.UmbraAlert
import com.geek.umbra.navigation.UmbraSheet
import com.geek.umbra.net.BackgroundWork
import com.geek.umbra.net.ReasonKind
import com.geek.umbra.net.ReasonRules
import com.geek.umbra.net.TaskFailAction
import com.geek.umbra.net.UmbraImages
import com.geek.umbra.net.UmbraJson
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** TaskStatusKind → 设计系统的状态徽标（图标 + 文字，不只靠颜色）。 */
internal fun TaskStatusKind.ui(): UmbraStatus = when (this) {
    TaskStatusKind.Running -> UmbraStatus.Running
    TaskStatusKind.Done -> UmbraStatus.Done
    TaskStatusKind.AwaitingReview -> UmbraStatus.AwaitingReview
    TaskStatusKind.Failed -> UmbraStatus.Failed
    TaskStatusKind.Pending -> UmbraStatus.Pending
    TaskStatusKind.Cancelled -> UmbraStatus.Cancelled
    TaskStatusKind.Suspended -> UmbraStatus.Suspended
}

private val CardShape @Composable get() = RoundedCornerShape(com.geek.umbra.designsystem.UmbraDimens.radiusCard)

// MARK: - 列表

/** 列表顶上的分段。「计划」只是说明卡（服务端没有定时任务接口）。 */
private enum class TaskSeg { History, Plan }

@Composable
fun TaskListView() {
    val c = UmbraTheme.colors
    val router = AppGraph.router
    val items by TasksStore.items.collectAsState()
    val loadedOnce by TasksStore.loadedOnce.collectAsState()
    val firstLoading by TasksStore.firstLoading.collectAsState()
    val refreshing by TasksStore.refreshing.collectAsState()
    val goneMap by TasksStore.gone.collectAsState()
    val offline by AppGraph.http.offline.collectAsState()

    var seg by remember { mutableStateOf(TaskSeg.History) }
    var query by remember { mutableStateOf("") }
    /** null = 全部。 */
    var filter by remember { mutableStateOf<TaskStatusKind?>(null) }

    // 可见时 3.5 秒轮询；首轮之后的是轮询，不是人点的：标成后台路径（执勤 M0 §14.1）。
    VisiblePolling(periodMs = TaskDetailRules.POLL_MS) { first ->
        if (first) TasksStore.loadTasks() else withContext(BackgroundWork) { TasksStore.loadTasks() }
    }

    val phase = UmbraScreenPhase.resolve(items.isNotEmpty(), firstLoading, loadedOnce, offline)
    val retry: () -> Unit = { AppGraph.scope.launch { TasksStore.retry() } }
    val dim = if (phase is UmbraScreenPhase.Content && phase.offline) 0.6f else 1f

    val q = query.trim()
    val filtered = items.filter { j ->
        (filter == null || taskStatusOf(j.status) == filter) &&
            (q.isEmpty() || (j.title + j.goal + (j.result_summary ?: "")).contains(q, ignoreCase = true))
    }
    val keep = filtered.map { it.id }.toSet()
    // 「已不在」的那几行原位插回、不参与筛选 —— 它是一句要人确认的话，被筛选挡掉就永远看不到了。
    val rows = TasksStore.rowsWithGone(items, goneMap).filter { it.second || it.first.id in keep }
    val isFiltered = q.isNotEmpty() || filter != null

    UmbraScreen(
        title = "任务",
        onBack = { router.back() },
        refreshing = refreshing,
        onRefresh = { AppGraph.scope.launch { TasksStore.refreshTasks() } },
        topContent = umbraPhaseTop(phase, live = true, retry),
        spacing = 9.dp,
    ) {
        when (phase) {
            UmbraScreenPhase.Skeleton -> item("skel") { UmbraSkeleton() }
            is UmbraScreenPhase.Failed -> item("fail") { UmbraPhaseFailedCard(phase.offline, retry, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), "拿不到任务列表") }
            is UmbraScreenPhase.Content -> {
                item("counts") {
                    val running = TasksStore.runningCount(items)
                    Text("共 ${items.size} 个 · $running 个执行中", color = c.muted, style = TextStyle(fontSize = 13.sp), modifier = Modifier.alpha(dim))
                }
                item("seg") {
                    UmbraSegmentedControl(listOf(UmbraSegment("历史"), UmbraSegment("计划")), seg.ordinal, { seg = TaskSeg.entries[it] }, Modifier.alpha(dim))
                }
                if (seg == TaskSeg.Plan) {
                    item("plan") { PlanCard() }
                } else {
                    item("search") {
                        CollapsingSearch("搜索任务目标或结果摘要", query, { query = it }, Modifier.alpha(dim))
                    }
                    item("chips") {
                        UmbraFilterChips(Modifier.alpha(dim)) {
                            UmbraTagPill("全部", filter == null, { filter = null }, count = items.size)
                            // 待确认那档随旧代理状态一起删了：任务行只有服务端那六个状态。
                            for (st in listOf(TaskStatusKind.Running, TaskStatusKind.Pending, TaskStatusKind.Done, TaskStatusKind.Failed)) {
                                val n = items.count { taskStatusOf(it.status) == st }
                                UmbraTagPill(st.ui().label, filter == st, { filter = st }, count = n)
                            }
                        }
                    }
                    if (rows.isEmpty()) {
                        item("empty") {
                            // 「空」和「无结果」分家：无结果那一档要给「清掉筛选」的出口，不能只在正文里说。
                            val title = when {
                                q.isNotEmpty() -> "没有匹配「$q」的任务"
                                filter == null -> "暂无任务"
                                else -> "这个筛选下暂无任务"
                            }
                            val body = when {
                                q.isNotEmpty() -> "换个词，或者清掉筛选试试。"
                                filter == null -> "在聊天里描述目标，${Brand.productName} 会自动建任务。"
                                else -> "清掉筛选就能看到全部。"
                            }
                            if (isFiltered) {
                                UmbraEmptyState(UmbraIconPaths.filter, title, hint = body, action = "清掉筛选", onAction = { query = ""; filter = null })
                            } else UmbraEmptyState(UmbraIconPaths.task, title, hint = body)
                        }
                    } else {
                        // 列表行不放左滑、不放长按（222 §二）；「已不在」那一行只有「知道了」一个出口。
                        for ((task, gone) in rows) {
                            item(key = "t-${task.id}") {
                                Box(Modifier.alpha(dim).animateItem()) {
                                    if (gone) GoneRow(task) else TaskRow(task)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 一行任务卡：短标题、goal 副行（有 name 时）、状态徽标或挂起说明、来自 X、相对时间、执行中进度条。 */
@Composable
private fun TaskRow(task: TaskItem) {
    val c = UmbraTheme.colors
    val st = taskStatusOf(task.status)
    val total = task.steps_total ?: 0
    val done = task.steps_done ?: 0
    Column(
        Modifier.fillMaxWidth().clip(CardShape).background(c.card).border(1.dp, c.border, CardShape)
            .clickable { AppGraph.router.go(Route.TaskDetail(task.id)) }
            .padding(horizontal = 13.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(task.title, color = c.text, style = TextStyle(fontSize = 16.sp, lineHeight = 22.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        // 有短标题时才补一行描述；旧数据没有 name，title 已经是 goal，再画一遍就是同一句话说两遍。
        if (!task.name.isNullOrEmpty()) {
            Text(task.goal, color = c.muted, style = TextStyle(fontSize = 13.5.sp, lineHeight = 19.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            // 挂起：胶囊换成「已挂起 · 原因」那一行字。等待档灰、「可以接着跑了」琥珀（设计侧 225，判据说了算）。
            val sus = TaskSuspendRules.display(task.status, task.suspendInfo)
            if (sus != null) {
                Text(sus.listMeta, color = if (sus.listMetaWarn) c.warningText else c.muted, style = TextStyle(fontSize = 13.sp),
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            } else UmbraStatusBadge(st.ui())
            TaskDetailRules.channelLabel(task.channel)?.let {
                Text(stringResource(R.string.tasks_fromChannel, it), color = c.faint, style = TextStyle(fontSize = 13.sp), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
            Text("· ${TaskTime.relative(task.updated_at)}", color = c.faint, style = TextStyle(fontSize = 13.sp), maxLines = 1)
        }
        // 进度只在服务端真给了步骤数时画：一根永远 0% 的条比不画更容易被当成「卡住了」。
        if (st == TaskStatusKind.Running && total > 0) UmbraProgressBar(done.toFloat() / total, c.orange)
    }
}

/** 「已不在」那一行（222 §二 2d）：标题和状态收掉，只留那句话和「知道了」。 */
@Composable
private fun GoneRow(task: TaskItem) {
    val c = UmbraTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(CardShape).background(c.chip).border(1.dp, c.border, CardShape)
            .padding(start = 13.dp, end = 13.dp, top = 12.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        TaskGoneNote(TaskRetryCopy.missing)
        // 稿：32 胶囊，热区撑到 44 —— 外框撑热区，不把胶囊画大。
        Box(Modifier.heightIn(min = UmbraDimens.tapMin).clickable(role = Role.Button) { TasksStore.dismissGone(task.id) }, contentAlignment = Alignment.Center) {
            Box(
                Modifier.height(32.dp).clip(RoundedCornerShape(50)).background(c.card)
                    .border(1.dp, c.border, RoundedCornerShape(50)).padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text(TaskRetryCopy.goneDismiss, color = c.text, style = TextStyle(fontSize = 14.sp, fontWeight = UmbraType.weight560)) }
        }
    }
}

/** 「计划」分段：设计稿自己标了「二期 · 待服务端」，给一个说清楚状况的承接卡，不摆假的定时任务。 */
@Composable
private fun PlanCard() {
    val c = UmbraTheme.colors
    UmbraCard {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            UmbraSectionLabel("定时执行还没有接服务端")
            Text("等服务端有了定时任务接口，这里会列出每条计划的目标设备、下次执行时间和开关。", color = c.text, style = UmbraType.body)
            Text("定时任务在手机上只做「看 + 启停 + 立即跑一次」。新建要选工作流、配 cron、选设备，去电脑上做。", color = c.muted, style = UmbraType.rowSub)
        }
    }
}

// MARK: - 详情

/** 详情页的页面级状态（iOS 里是 @State）。放进一个对象，好让 AppGraph.scope 里跑完的请求回来写。 */
private class DetailUiState {
    val retrying = mutableStateOf(false)
    val retryOutcome = mutableStateOf<TaskRetryOutcome?>(null)
    val stopping = mutableStateOf(false)
    val resuming = mutableStateOf(false)
    val resumeOutcome = mutableStateOf<TaskResumeOutcome?>(null)
    /** 「接着跑」没成那一句，**按下那一刻就拼好**：not_ready 设备档要念那时候在等的那台。 */
    val resumeFailText = mutableStateOf<String?>(null)
}

@Composable
fun TaskDetailView(id: String) {
    val c = UmbraTheme.colors
    val router = AppGraph.router
    val details by TasksStore.details.collectAsState()
    val misses by TasksStore.detailMiss.collectAsState()
    val offline by AppGraph.http.offline.collectAsState()
    val ui = remember(id) { DetailUiState() }
    val detail = details[id]
    val miss = misses[id]
    val phase = TaskDetailRules.phase(detail != null, miss)
    val keepPolling = TaskDetailRules.keepPolling(detail?.task?.status, miss)

    // 详情页**自己带刷新**（#38）：首轮立刻拉；之后只在 keepPolling 时每 3.5 秒再拉（终态 / 不存在了就停）。
    // keepPolling 是键：重试 / 接着跑把终态翻回非终态时重起一轮。
    VisiblePolling(id, keepPolling, periodMs = TaskDetailRules.POLL_MS) { first ->
        if (first) {
            // 先登记「我开着」：只收开着的详情页的回应。
            TasksStore.openTaskDetail(id)
            TasksStore.loadTaskDetail(id)
        } else if (keepPolling) {
            withContext(BackgroundWork) { TasksStore.loadTaskDetail(id) }
        } else awaitCancellation()
    }
    // 只在这一页**真的不在任何一条栈里了**才清：切 Tab 时整条 NavDisplay 被换掉也会 dispose，但它还在栈里。
    DisposableEffect(id) {
        onDispose {
            val stillThere = router.stacks.values.any { s -> s.any { it == Route.TaskDetail(id) } }
            if (!stillThere) TasksStore.closeTaskDetail(id)
        }
    }
    // 又跑起来了：上一次重试的那一行作废；离开挂起：「接着跑没成」那一行一起作废。
    val status = detail?.task?.status
    LaunchedEffect(status) {
        if (status != null && TaskDetailRules.isActive(status)) ui.retryOutcome.value = null
        if (TaskDetailRules.dropsResumeFail(status)) { ui.resumeOutcome.value = null; ui.resumeFailText.value = null }
    }
    // 条件又满足了（not_ready 之后服务端再次标就绪）：上一次「接着跑」那一行作废，钮回来。
    val ready = detail?.task?.resume_ready
    LaunchedEffect(ready) { if (ready == true) { ui.resumeOutcome.value = null; ui.resumeFailText.value = null } }
    // 进行中通知上按了「停止」（第 199 封 #9）：等详情到手、确认任务还在跑，再弹同一个停止确认；只弹一次。
    val stopReq by TaskStopRequest.pending.collectAsState()
    LaunchedEffect(stopReq, detail != null) {
        if (stopReq == id && detail != null && TaskStopRequest.consume(id) && TaskDetailRules.isActive(detail.task.status)) {
            confirmStop(detail, ui)
        }
    }

    // 顶栏标题：优先任务短标题，拿不到详情时退回短 ID（完整 ID 在下面的详情表里有一行）。
    val navTitle = detail?.task?.title?.takeIf { it.isNotEmpty() } ?: id.take(8)
    UmbraScreen(
        title = navTitle,
        onBack = { router.back() },
        contentPadding = PaddingValues(bottom = 24.dp),
        spacing = 0.dp,
        bottomBar = if (phase == TaskDetailPhase.Content && detail != null) ({ DetailBottomBar(detail, ui) }) else null,
    ) {
        when (phase) {
            TaskDetailPhase.Content -> if (detail != null) detailContent(detail, ui)
            TaskDetailPhase.Skeleton -> item("skel") { UmbraSkeleton() }
            TaskDetailPhase.NotFound -> item("nf") {
                // 话复用列表那一行 / 聊天卡上的那一句；钮写「返回」不写「返回列表」：从聊天卡 / 通知进来时退回去的是聊天。
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TaskGoneNote(TaskRetryCopy.missing)
                    UmbraButton("返回", { router.back() }, Modifier.fillMaxWidth(), kind = UmbraButtonKind.Secondary, height = 46.dp)
                }
            }
            TaskDetailPhase.Failed -> item("fail") {
                // 整屏错误卡同列表那一张（公共件），但只带「重试」、不挂离线条（iOS TaskViews.swift:414 同）：
                // 这一页的四档判据（多一档「不存在」）在 TaskDetailRules，不走 umbraScreenPhase。
                UmbraPhaseFailedCard(offline, { AppGraph.scope.launch { TasksStore.retryTaskDetail(id) } }, Modifier.padding(16.dp), checkServer = false)
            }
        }
    }
}

/** 详情页的分节：通栏、节与节之间一条 borderSoft 发丝线（iOS sectionDivider）。 */
@Composable
private fun DetailSection(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Box(modifier.fillMaxWidth()) { content() }
        Box(Modifier.fillMaxWidth().height(1.dp).background(UmbraTheme.colors.borderSoft))
    }
}

private fun doneCount(d: TaskDetail) = d.steps.count { taskStatusOf(it.status) == TaskStatusKind.Done }

private fun progressOf(d: TaskDetail): Float {
    if (d.task.status == "done") return 1f
    if (d.steps.isEmpty()) return 0f
    return doneCount(d).toFloat() / d.steps.size
}

/** 第一个失败步骤的结构化错误（失败卡画不画、画什么都看它）。 */
private fun failedStepError(d: TaskDetail): StepError? = d.steps.firstOrNull { taskStatusOf(it.status) == TaskStatusKind.Failed }?.error

/** 详情里用的挂起字段：上一次「接着跑」回 not_ready 时先在本端撤掉就绪（不等那条可能晚到的推送）。 */
private fun suspendInfoOf(d: TaskDetail, ui: DetailUiState): TaskSuspendInfo {
    val info = d.task.suspendInfo
    return if (ui.resumeOutcome.value == TaskResumeOutcome.NotReady) TaskSuspendRules.afterNotReady(info) else info
}

private fun androidx.compose.foundation.lazy.LazyListScope.detailContent(d: TaskDetail, ui: DetailUiState) {
    val st = taskStatusOf(d.task.status)
    // 目标 + 状态
    item("head") {
        val c = UmbraTheme.colors
        DetailSection(Modifier.padding(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Text(d.task.title, color = c.text, style = TextStyle(fontSize = 19.sp, fontWeight = UmbraType.weight560, lineHeight = 27.sp))
                if (!d.task.name.isNullOrEmpty()) {
                    SelectionContainer { Text(d.task.goal, color = c.muted, style = TextStyle(fontSize = 15.sp, lineHeight = 22.sp)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UmbraStatusBadge(st.ui(), compact = false)
                    TaskDetailRules.channelLabel(d.task.channel)?.let {
                        Text(stringResource(R.string.tasks_fromChannel, it), color = c.faint, style = TextStyle(fontSize = 13.sp))
                    }
                }
                TaskSuspendRules.display(d.task.status, suspendInfoOf(d, ui))?.let { TaskSuspendReasonCard(it) }
            }
        }
    }
    // 总进度
    item("progress") {
        val c = UmbraTheme.colors
        DetailSection(Modifier.padding(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UmbraFieldLabel("总进度", Modifier.weight(1f))
                    Text("${doneCount(d)} / ${d.steps.size}", color = c.text, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = UmbraType.weight560))
                }
                UmbraProgressBar(progressOf(d), st.ui().bar(), height = 8.dp)
            }
        }
    }
    // 概要字段：只列服务端真给了的。
    item("stats") {
        val c = UmbraTheme.colors
        val stats = buildList {
            add("任务 ID" to d.task.id)
            TaskDetailRules.channelLabel(d.task.channel)?.let { add("来源" to it) }
            add("创建" to TaskTime.absolute(d.task.created_at))
            add("更新" to TaskTime.absolute(d.task.updated_at))
            add("步骤" to "${doneCount(d)} / ${d.steps.size}")
            val failed = d.steps.count { taskStatusOf(it.status) == TaskStatusKind.Failed }
            if (failed > 0) add("失败步骤" to "$failed")
        }
        DetailSection(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (pair in stats.chunked(2)) {
                    Row(Modifier.fillMaxWidth()) {
                        for ((k, v) in pair) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                UmbraFieldLabel(k)
                                Text(v, color = c.text, style = TextStyle(fontSize = 14.5.sp, lineHeight = 20.sp))
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
    // 步骤
    if (d.steps.isNotEmpty()) {
        item("steps") {
            DetailSection(Modifier.padding(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    UmbraFieldLabel("步骤 · ${d.steps.size}")
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { d.steps.forEach { StepRow(it) } }
                }
            }
        }
    }
    // 事件时间线
    if (d.events.isNotEmpty()) {
        item("events") {
            DetailSection(Modifier.padding(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    UmbraFieldLabel("事件时间线 · ${d.events.size}")
                    Column { d.events.forEach { EventRow(it) } }
                }
            }
        }
    }
    // 结果摘要 / 错误三段式。失败态「摘要 / 失败步骤的 error」两样有一样就画（没写摘要时也要出「去充值」）。
    val summary = d.task.result_summary ?: ""
    if (st == TaskStatusKind.Failed && (summary.isNotEmpty() || !failedStepError(d)?.message.isNullOrEmpty())) {
        item("error") { Box(Modifier.padding(16.dp)) { FailBlock(d, summary) } }
    } else if (summary.isNotEmpty()) {
        item("summary") {
            val c = UmbraTheme.colors
            DetailSection(Modifier.padding(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    UmbraFieldLabel("结果摘要")
                    SelectionContainer { Text(summary, color = c.text, style = TextStyle(fontSize = 14.5.sp, lineHeight = 23.sp)) }
                }
            }
        }
    }
}

/** 一步：状态图标块、`seq. title`、行内 Markdown 说明、错误框、截图。 */
@Composable
private fun StepRow(s: TaskStep) {
    val c = UmbraTheme.colors
    val st = taskStatusOf(s.status).ui()
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSoft))
        Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(st.soft()), contentAlignment = Alignment.Center) {
                if (st == UmbraStatus.Running) UmbraSpinningIcon(st.icon, st.fg(), 12.dp) else UmbraIcon(st.icon, st.fg(), size = 12.dp, strokeWidth = 2.2f)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${s.seq}. ${s.title ?: "未命名步骤"}", color = c.text, style = TextStyle(fontSize = 15.sp, lineHeight = 21.sp))
                // 这一步的人话说明（detail），按行内 Markdown 渲染：执行轮写的结论天然带 **粗体** / [链接](url)。
                val note = s.detail
                if (!note.isNullOrEmpty() && s.error == null) {
                    Text(inlineMarkdown(note, c.orangeText, c.chip), color = c.faint, style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp))
                }
                // 服务端给的两句人话都画（message 说发生了什么，detail 补一句怎么办）。
                val err = s.error?.message
                if (!err.isNullOrEmpty()) {
                    Text(
                        listOfNotNull(err, s.error?.detail).filter { it.isNotEmpty() }.joinToString("\n"),
                        color = c.muted, style = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.chip).padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
                StepShot(s)
            }
        }
    }
}

/**
 * 从步骤 result_json 里捞第一个图片类 url 和它的文件名：顶层（技能直回）或 device_results[]（执行轮聚合的截图）。
 * 地址怎么拼交给 TaskStepRules.shotUrl；不认的这一条跳过、接着看下一条候选。
 */
private fun stepShot(s: TaskStep): Pair<String, String>? {
    val raw = s.result_json ?: return null
    val obj = runCatching { UmbraJson.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
    val cands = ((obj["device_results"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()) + obj
    for (d in cands) {
        fun str(k: String) = (d[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val u = str("url")?.takeIf { it.isNotEmpty() } ?: continue
        if (!TaskStepRules.looksImage(u)) continue
        val url = TaskStepRules.shotUrl(u, AppGraph.prefs.serverUrl) ?: continue
        val name = str("filename") ?: str("title") ?: url.substringBefore('?').substringAfterLast('/')
        return url to name
    }
    return null
}

/**
 * 该步产出的内联截图：最大 330×280、圆角 9、borderSoft 描边，下方等宽文件名脚注；点开在应用内看大图。
 * 加载失败 / 还在加载都不占位（一格空白比什么都没有更像 bug），脚注也跟着不出。
 * 取图走 [UmbraImages.loader]：令牌只带给自己的服务端，/files/<id> 之后 302 到对象存储时去掉登录头（shotUrl 另外先判过同源）。
 */
@Composable
private fun StepShot(s: TaskStep) {
    val shot = remember(s.result_json) { stepShot(s) } ?: return
    val c = UmbraTheme.colors
    var ok by remember(shot.first) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        AsyncImage(
            model = shot.first, imageLoader = UmbraImages.loader, contentDescription = shot.second, contentScale = ContentScale.Fit,
            onSuccess = { ok = true }, onError = { ok = false },
            modifier = Modifier.widthIn(max = 330.dp).heightIn(max = 280.dp).clip(RoundedCornerShape(9.dp))
                .then(if (ok) Modifier.border(1.dp, c.borderSoft, RoundedCornerShape(9.dp)) else Modifier)
                .clickable(enabled = ok) { openShotViewer(shot.first, shot.second) },
        )
        if (ok) Text(shot.second, color = c.faint, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp), maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.widthIn(max = 330.dp))
    }
}


/** 应用内看大图（iOS UmbraImageViewer）。地基里还没有看图器，这里用底部弹层顶着，双指缩放留给设计系统那一件。 */
private fun openShotViewer(url: String, name: String) {
    AppGraph.router.present(UmbraSheet("shot-$url") { _ ->
        val c = UmbraTheme.colors
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(name, color = c.muted, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            AsyncImage(model = url, imageLoader = UmbraImages.loader, contentDescription = name, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp).clip(RoundedCornerShape(12.dp)))
        }
    })
}

@Composable
private fun EventRow(e: TaskEvent) {
    val c = UmbraTheme.colors
    val payload = remember(e.id) { TaskEventPayload.parse(e.payload) }
    // 点是装饰性的实心圆、不是字，用实心那一支 token；正文里服务端已写了 ❌ / ✅，状态不只靠颜色。
    val dot = when (TaskStepRules.eventTone(e.type, payload.status)) {
        TaskEventTone.Danger -> c.danger
        TaskEventTone.Success -> c.success
        TaskEventTone.Warning -> c.warning
        TaskEventTone.Neutral -> c.faint
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 30.dp), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Column(Modifier.width(9.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(5.dp))
            Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
            Box(Modifier.width(1.dp).weight(1f, fill = true).background(c.border))
        }
        Column(Modifier.weight(1f).padding(bottom = 13.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(e.message ?: e.type, color = c.text, style = TextStyle(fontSize = 14.5.sp, lineHeight = 21.sp))
            Text(TaskTime.absolute(e.created_at), color = c.faint, style = TextStyle(fontSize = 12.sp))
        }
    }
}

/**
 * 失败三段式：发生了什么（失败步骤名）→ 为什么（它的错误信息）→ 现在能做什么（按 reason_kind 分档的钮）。
 * 欠费且给了 topup_url → 「去充值」外链；值得再跑一次 →「去看能力」；其余不给钮（服务端那句人话自己说了下一步）。
 */
@Composable
private fun FailBlock(d: TaskDetail, summary: String) {
    val failedStep = d.steps.firstOrNull { taskStatusOf(it.status) == TaskStatusKind.Failed }
    val what = failedStep?.let { "第 ${it.seq} 步中断：${it.title ?: "未命名步骤"}" } ?: "任务失败"
    val err = failedStep?.error
    val why = err?.message?.takeIf { it.isNotEmpty() } ?: summary
    val fail = ReasonRules.taskFailAction(err?.reason_kind, err?.topup_url)
    val kind = ReasonKind.of(err?.reason_kind)
    val topupTitle = stringResource(
        when (kind) {
            ReasonKind.NeedMembership -> R.string.vision_openMembership
            ReasonKind.QuotaExhausted -> R.string.android_quotaShop
            else -> R.string.vision_topup
        },
    )
    // 额度用完：手里有对应窗口的卡就出［用重置卡］（不另确认，点了转圈；批次 278），0 张不出、只留去买。窗口优先用错误里服务端说的那条。
    val window = com.geek.umbra.feature.account.ResetCardRules.quotaWindowOf(err?.window)
    val offer = com.geek.umbra.feature.account.ResetCardOffer
    if (kind == ReasonKind.QuotaExhausted) LaunchedEffect(Unit) { offer.maybeRefresh() }
    val showCard = kind == ReasonKind.QuotaExhausted && com.geek.umbra.feature.account.ResetCardRules.showUseCard(window, offer.counts)
    val cardLabel = if (offer.spending) stringResource(R.string.android_quota_using) else stringResource(R.string.android_quota_useCard)
    val actions = buildList<Pair<String, () -> Unit>> {
        if (showCard) add(cardLabel to { offer.useFromFailCard(window) })
        if (fail == TaskFailAction.AsBefore) add("去看能力" to { AppGraph.router.go(Route.MeCaps) })
        if (fail is TaskFailAction.Topup) add(topupTitle to { openExternal(fail.url) })
    }
    UmbraErrorCard(title = what, body = why, actions = actions, raw = summary.ifEmpty { null })
}

/**
 * 底部动作条（设计侧批次 222 §二 2a / 2b / 2c / 2f，224 §二 1）：
 * - 挂起且就绪：接着跑（主）· 停止 · 复制详情；
 * - 还在跑：停止（确认）+ 电脑操作在跑时「看实时画面」+ 复制详情；
 * - 失败 / 已取消、值得再跑一次：重试任务（或重新发起）+ 复制详情，钮下一行提示；没成时这一行换成红字 + 圆叉；
 * - 其余：只有复制详情。
 */
@Composable
private fun DetailBottomBar(d: TaskDetail, ui: DetailUiState) {
    val c = UmbraTheme.colors
    val st = d.task.status
    val retryOutcome = ui.retryOutcome.value
    val blocked = retryOutcome?.let { !it.keepsRetry && it != TaskRetryOutcome.Operate } ?: false
    val operate = TaskRetryRules.isOperate(d.task.executor) || retryOutcome == TaskRetryOutcome.Operate
    val fe = failedStepError(d)
    val fail = ReasonRules.taskFailAction(fe?.reason_kind, fe?.topup_url)
    val button = TaskRetryRules.button(st, fail, blocked, operate, fixable = TaskRetryRules.retryAfterFix(fe?.reason_kind))
    val failLine = retryOutcome?.message ?: ui.resumeFailText.value
    val hint = TaskRetryRules.hint(st, button)
    val resume = TaskSuspendRules.showsResume(st, suspendInfoOf(d, ui)) && (ui.resumeOutcome.value?.keepsResume ?: true)
    val active = TaskDetailRules.isActive(st)
    val runId = remember(d.events) { d.events.lastOrNull { !TaskEventPayload.parse(it.payload).runId.isNullOrEmpty() }?.let { TaskEventPayload.parse(it.payload).runId } }
    val stopLabel = if (ui.stopping.value) "停止中…" else TaskSuspendCopy.stop

    Column(Modifier.fillMaxWidth().background(c.bg)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSoft))
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    resume -> {
                        UmbraButton(if (ui.resuming.value) TaskSuspendCopy.resuming else TaskSuspendCopy.resume, { resumeTask(d, ui) }, Modifier.weight(1f))
                        // 三颗并排时「停止」按字宽让出位置（稿 4e）。
                        UmbraButton(stopLabel, { confirmStop(d, ui) }, Modifier.width(80.dp), kind = UmbraButtonKind.DangerOutline)
                    }
                    active -> {
                        UmbraButton(stopLabel, { confirmStop(d, ui) }, Modifier.weight(1f), kind = UmbraButtonKind.DangerOutline)
                        // 电脑操作正在跑：「看实时画面」（远程控制只看档，source=task, run_id）。
                        if (operate && st == "running" && runId != null) {
                            UmbraButton(stringResource(R.string.remote_task_watch), { watchLive(d, runId) }, Modifier.weight(1f), kind = UmbraButtonKind.Secondary)
                        }
                    }
                    button == TaskRetryButton.Retry ->
                        UmbraButton(if (ui.retrying.value) TaskRetryCopy.retrying else TaskRetryCopy.retry, { retryTask(d, ui) }, Modifier.weight(1f))
                    button == TaskRetryButton.Relaunch ->
                        UmbraButton(TaskRetryCopy.relaunch, { relaunch(d) }, Modifier.weight(1f))
                }
                // 旁边有主动作时它让出宽度（主动作占满、复制详情按字宽）；独自一颗时通栏。
                val alone = !(active || button != null)
                UmbraButton(
                    TaskSuspendCopy.copyDetail, { copyPlain(plainText(d)); AppGraph.router.showToast("已复制") },
                    if (alone) Modifier.weight(1f) else Modifier.width(112.dp), kind = UmbraButtonKind.Secondary,
                )
            }
            if (failLine != null) TaskRetryFailLine(failLine)
            else if (hint != null) Text(hint, color = c.faint, style = TextStyle(fontSize = 11.5.sp), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * 详情底部「重试任务」—— 与聊天卡那颗是同一个函数（TasksStore.runRetry）。成功不弹吐司（状态翻成运行中、
 * 动作条换成「停止」就是反馈）；已不在：只在眼前仍是这一页时才退回，话写在列表那一行上。
 * 请求跑在进程级作用域里：人等不及先返回了，收尾（重拉列表、2 秒后再拉）照样要做完。
 */
private fun retryTask(d: TaskDetail, ui: DetailUiState) {
    if (ui.retrying.value) return
    ui.retrying.value = true
    ui.retryOutcome.value = null
    val id = d.task.id
    AppGraph.scope.launch {
        val o = TasksStore.runRetry(id)
        if (o == TaskRetryOutcome.Missing) {
            ui.retrying.value = false
            TasksStore.markGone(id)
            AppGraph.router.back(ifShowing = Route.TaskDetail(id))
            return@launch
        }
        if (o != TaskRetryOutcome.Ok) ui.retryOutcome.value = o
        if (o.refreshes) TasksStore.loadTaskDetail(id)
        ui.retrying.value = false
        TasksStore.afterRetry(id, o)
    }
}

/** 详情底部「接着跑」—— 与聊天卡、助手问话下那两颗是同一个函数（TasksStore.runResume）。 */
private fun resumeTask(d: TaskDetail, ui: DetailUiState) {
    if (ui.resuming.value) return
    ui.resuming.value = true
    ui.resumeOutcome.value = null
    ui.resumeFailText.value = null
    val id = d.task.id
    val info = d.task.suspendInfo
    AppGraph.scope.launch {
        val o = TasksStore.runResume(id)
        if (o == TaskResumeOutcome.Missing) {
            ui.resuming.value = false
            TasksStore.markGone(id)
            AppGraph.router.back(ifShowing = Route.TaskDetail(id))
            return@launch
        }
        if (o != TaskResumeOutcome.Ok) {
            ui.resumeOutcome.value = o
            ui.resumeFailText.value = o.message(info)
        }
        if (o.refreshes) TasksStore.loadTaskDetail(id)
        ui.resuming.value = false
        TasksStore.afterResume(id, o)
    }
}

/** 「重新发起」（电脑操作任务）：跳到秘书会话，原目标填进输入框，**不自动发送**。 */
private fun relaunch(d: TaskDetail) {
    val text = TaskRetryRules.relaunchText(d.task.goal, d.task.name) ?: return
    ChatApi.startDraft(text)
}

/** 「看实时画面」：认出那次运行跑在哪台电脑上，只看档发起；认不出说一句去哪发起。 */
private fun watchLive(d: TaskDetail, runId: String) {
    AppGraph.scope.launch {
        val dev = TasksStore.operateDevice(d.task.id, runId)
        if (dev == null) AppGraph.router.showToast(AppGraph.context.getString(R.string.remote_takeover_noDevice))
        else RemoteLauncher.launch(dev, "view", "task", runId)
    }
}

/** 「停止」是破坏性的：过确认弹窗。吐司**等回应回来再说**，并且按回应说（断网时不许说「已发出停止」）。 */
private fun confirmStop(d: TaskDetail, ui: DetailUiState) {
    if (ui.stopping.value) return
    AppGraph.router.confirm(
        UmbraAlert(
            title = "停止这个任务？",
            message = "已完成的步骤会保留，正在跑的那一步会被打断。",
            confirmLabel = "停止",
            destructive = true,
        ) {
            ui.stopping.value = true
            AppGraph.scope.launch {
                val ok = TasksStore.stopTask(d.task.id)
                AppGraph.router.showToast(if (ok) "已发出停止" else "没停下，检查网络后再试")
                TasksStore.loadTaskDetail(d.task.id)
                ui.stopping.value = false
            }
        },
    )
}

/** 「复制详情」的全文（iOS: TaskViews.swift:1012）。 */
private fun plainText(d: TaskDetail): String {
    val lines = mutableListOf("任务 ${d.task.id}", d.task.title, d.task.goal, "状态：${taskStatusOf(d.task.status).ui().label}")
    TaskDetailRules.channelLabel(d.task.channel)?.let { lines += "来源：$it" }
    lines += "创建：${TaskTime.absolute(d.task.created_at)}"
    lines += ""
    for (s in d.steps) {
        lines += "${s.seq}. [${taskStatusOf(s.status).ui().label}] ${s.title ?: ""}"
        s.error?.message?.takeIf { it.isNotEmpty() }?.let { lines += "   错误：$it" }
    }
    d.task.result_summary?.takeIf { it.isNotEmpty() }?.let { lines += ""; lines += "结果：$it" }
    return lines.joinToString("\n")
}

// MARK: - 挂起原因卡 / 重试那一行 / 已不在那句话

/**
 * 详情里那张原因卡（223 3g / 3h、224 4e）。**中性底，不是错误卡**（挂起不是失败）。就绪时标题前画绿对勾；
 * 缺能力档还没就绪时多一行「去电脑上接」。卡上不放钮：「接着跑」在底部动作条。
 * iOS 那一行用 --ink-mute；Android token 里没有这一档，取最接近的 faint。
 */
@Composable
fun TaskSuspendReasonCard(display: TaskSuspendDisplay) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.card).border(1.dp, c.borderSoft, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            if (display.ready) UmbraIcon(UmbraIconPaths.check, c.success, Modifier.padding(top = 3.dp), size = 16.dp, strokeWidth = 2.2f)
            Text(display.title, color = c.text, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp))
        }
        Text(display.body, color = c.muted, style = TextStyle(fontSize = 14.sp, lineHeight = 23.sp))
        display.iosHint?.let { hint ->
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                UmbraIcon(UmbraIconPaths.deviceDesktop, c.faint, Modifier.padding(top = 3.dp), size = 14.dp, strokeWidth = 2f)
                Text(hint, color = c.faint, style = TextStyle(fontSize = 13.sp, lineHeight = 21.sp))
            }
        }
    }
}

/** 重试 / 接着跑没成的那一行：红字 + 圆叉，**替换**钮下的灰色提示。不用吐司 —— 话要留在按钮旁边。 */
@Composable
fun TaskRetryFailLine(text: String) {
    val c = UmbraTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        UmbraIcon(UmbraIconPaths.xCircle, c.dangerText, Modifier.padding(top = 2.dp), size = 14.dp, strokeWidth = 2f)
        Text(text, color = c.dangerText, style = TextStyle(fontSize = 12.5.sp, lineHeight = 17.5.sp))
    }
}

/** 「这个任务已经不在了」那句话。列表那一行、详情不存在那一档、聊天卡上同一句、同一个样子。 */
@Composable
fun TaskGoneNote(text: String) {
    val c = UmbraTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        UmbraIcon(UmbraIconPaths.info, c.muted, Modifier.padding(top = 2.dp), size = 16.dp, strokeWidth = 2f)
        Text(text, color = c.text, style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp), modifier = Modifier.weight(1f))
    }
}
