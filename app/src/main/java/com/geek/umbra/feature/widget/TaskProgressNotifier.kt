// 任务进行中通知 —— Android 对 iOS「灵动岛 + 锁屏实况」（UmbraTaskLiveActivity + WidgetBridge.swift 的
// UmbraLiveActivityController）的替代；四个状态的槽位按设计侧第 199 封答复第 9 条
// （docs/design/答复实现侧-Android平台差异.md，出图「Android 端/Pages/平台差异.dc.html」#9）。
//
// ⛔ 灵动岛 Android 无法实现（没有对等的系统界面）。这里做的是：
//   · 执行中：常驻、不可划（渠道「任务进行中」，低打扰无声）。标题 = 任务名；正文 =「第 3/8 步 · 当前步名」；
//     头部系统计时器真走秒（setUsesChronometer + setWhen = 任务开始时刻）；进度 = 已完成步数 / 总步数，
//     Android 16+ 用 ProgressStyle 每步一段并申请提升为 Live Update（状态栏胶囊放百分比），以下 setProgress；
//     动作只有「停止」（拉起 App 内的停止确认），点本体进任务详情。
//   · 需确认：常驻、只提醒一次。标题 =「任务名 · 等你确认」，正文 = 秘书的问题原文；进度停住转 warning、计时停；
//     动作「去确认」「停止」；胶囊换「待确认」。
//   · 挂起 / 待执行：**不是结束**（LiveActivityRules.keepsAlive），常驻那条留着、计时停，正文换任务列表那一句。
//   · 失败：撤掉常驻那条，在「任务结果」渠道发一条可划掉的：标题 = 发生了什么，正文 =「任务名 · 为什么」，
//     动作 = 任务详情错误卡的前两颗钮。无进度条。
//   · 完成：同上一条渠道，标题 = 任务名，正文 =「全部完成 · 耗时 4分08秒」，动作「查看产出」；
//     App 在前台且正停在这条任务详情时不发。
//   · 已取消 / 找不到：答复四态之外，沿用原来的收尾（LiveActivityRules.finalFrame 那一句）。
//   · 登出：全部撤掉（含结果那一条：任务名是联网数据）。被吊销 / 全部下线 / 注销同样走守卫「关」；
//     半路上的那一轮按会话代数作废，不在撤完之后再补发一条（refs TestEngineerFish/umbra#134）。
// 一次只跟一个任务（同 iOS：胶囊态就是「当前那个任务」）。数据与任务小组件同源同 tick：都由 WidgetBridge.syncTasks 那一次调用驱动。
package com.geek.umbra.feature.widget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import com.geek.umbra.R
import com.geek.umbra.app.AppForeground
import com.geek.umbra.app.AppGraph
import com.geek.umbra.app.MainActivity
import com.geek.umbra.designsystem.UmbraLight
import com.geek.umbra.designsystem.UmbraStatus
import com.geek.umbra.feature.task.TaskSuspendRules
import com.geek.umbra.feature.task.TaskRetryCopy
import com.geek.umbra.feature.task.TaskRetryRules
import com.geek.umbra.feature.task.TaskStatusKind
import com.geek.umbra.feature.task.TaskTime
import com.geek.umbra.feature.task.suspendInfo
import com.geek.umbra.feature.task.taskStatusOf
import com.geek.umbra.feature.reminder.ReminderNotifier
import com.geek.umbra.model.TaskDetail
import com.geek.umbra.model.TaskItem
import com.geek.umbra.navigation.Route
import com.geek.umbra.net.BackgroundWork
import com.geek.umbra.net.ReasonKind
import com.geek.umbra.net.SessionGuardRules
import com.geek.umbra.net.UmbraUrl
import com.geek.umbra.net.str
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

object TaskProgressNotifier {
    /** 渠道「任务进行中」：低打扰、无声 —— 进度是「瞄一眼」的东西。 */
    const val CHANNEL_LIVE = "umbra.task.live"
    /** 渠道「任务结果」：默认级别（失败 / 完成要让人知道）。 */
    const val CHANNEL_RESULT = "umbra.task.result"
    /** 旧版只有一个渠道「任务进度」，改成两个之后要删掉，不然系统设置里留一个永远不用的空渠道。 */
    private const val CHANNEL_OLD = "umbra.task.progress"
    /** 常驻那一条的 tag 前缀，后面接 taskId —— 冷启动认领靠它从系统里认出「这条跟的是哪个任务」。 */
    private const val TAG_LIVE = "umbra.task.live:"
    /** 结果那一条的 tag 前缀。 */
    private const val TAG_DONE = "umbra.task.done:"
    private const val ID_LIVE = 7301
    private const val ID_DONE = 7302

    /** 当前跟着的任务 id 与标题（标题留着给结果那一条用：任务被删后列表里找不到它了）。 */
    private var current: String? = null
    private var currentTitle: String? = null
    /** 上一次发出去的内容：轮询每 3.5 秒来一次，内容没变就不重发（重发会让 Live Update 的胶囊闪一下）。 */
    private var lastPosted: String? = null
    /** 这个进程里认领过没有（见 [adopt]）。只在第一次 sync 之前做一次。 */
    private var adopted = false
    /**
     * 这一轮 sync 属于哪一代会话（[com.geek.umbra.app.SessionGuard.generation]）。sync 里有挂起点（拉当前步名、问详情）：
     * 半路被踢 / 登出时守卫已经 [endAll] 撤过一遍，这一轮再发就是把上一个会话的任务名补回通知栏（refs TestEngineerFish/umbra#134）。
     */
    @Volatile private var syncGen = 0

    /** 发之前核一次：这一代会话还在、守卫开着。iOS 是推送 payload 的 uid 对不上不展示，Android 是本机拉了再发，门放在发之前。 */
    private fun mayPost(gen: Int) = SessionGuardRules.stillCurrent(gen, AppGraph.guard.generation, AppGraph.guard.locked.value)

    /** 计时器起点：任务的 created_at 解不出时，用这个进程第一次看到它执行的时刻（答复 #9）。 */
    private val firstSeen = mutableMapOf<String, Long>()
    /** 进入需确认 / 挂起那一刻（头部时间停在这里，不走秒）。 */
    private val pausedAt = mutableMapOf<String, Long>()
    /** 当前步名缓存：taskId → (拉详情时的已完成步数, 步名)。步数变了才重拉详情，不跟着 3.5 秒轮询每次都拉。 */
    private val stepCache = mutableMapOf<String, Pair<Int, String?>>()
    /** 秘书的问题原文（task_update.message，需确认那一帧）。拿不到时正文退回任务列表那一句。 */
    private val questions = mutableMapOf<String, String>()
    private var listening = false

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    private fun ensureChannels(ctx: Context) {
        val live = NotificationChannel(CHANNEL_LIVE, ctx.getString(R.string.task_live_channel), NotificationManager.IMPORTANCE_LOW).apply {
            description = ctx.getString(R.string.task_live_channel_desc)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        val result = NotificationChannel(CHANNEL_RESULT, ctx.getString(R.string.task_result_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = ctx.getString(R.string.task_result_channel_desc)
        }
        nm(ctx).createNotificationChannels(listOf(live, result))
        runCatching { nm(ctx).deleteNotificationChannel(CHANNEL_OLD) }
    }

    /**
     * 听 task_update 帧，记下需确认那一帧的问话原文。只听不改：任务列表 / 聊天卡各自照旧处理同一路帧。
     * 第一次 sync 时接上（登录之后才会有 sync）。
     */
    private fun listen() {
        if (listening) return
        listening = true
        AppGraph.scope.launch {
            AppGraph.socket.frames.collect { f ->
                if (f.str("type") != "task_update") return@collect
                val id = f.str("task_id") ?: return@collect
                val needs = (f["needs_confirm"] as? JsonPrimitive)?.booleanOrNull == true
                val msg = f.str("message")?.trim()
                if ((needs || f.str("status") == "awaiting_confirm") && !msg.isNullOrEmpty()) questions[id] = msg
                else if (f.str("status") == "running" && !needs) questions.remove(id)
            }
        }
    }

    /**
     * 冷启动认领（umbra-ios#22 同一件事）：通知活得比进程久。把系统里还挂着的常驻通知认回来，
     * 多出来的撤掉。留哪条由 LiveActivityRules.adopt 判。
     */
    private fun adopt(ctx: Context, running: String?) {
        adopted = true
        val live = runCatching { nm(ctx).activeNotifications.toList() }.getOrDefault(emptyList())
            .filter { it.tag?.startsWith(TAG_LIVE) == true }
        val (keep, end) = LiveActivityRules.adopt(live.map { it.tag to it.tag.removePrefix(TAG_LIVE) }, running)
        end.forEach { nm(ctx).cancel(it, ID_LIVE) }
        val kept = live.firstOrNull { it.tag == keep }
        current = kept?.tag?.removePrefix(TAG_LIVE)
        currentTitle = kept?.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?.removeSuffix(TaskNotifyRules.AWAITING_SUFFIX)
        // 原来那条的起点认回来（计时器不从零重走）。
        if (kept != null && current != null && kept.notification.`when` > 0) firstSeen.putIfAbsent(current!!, kept.notification.`when`)
        lastPosted = null
    }

    /**
     * 执行中那个任务的当前步名（小组件 4×2 与通知正文同一句，同源同 tick）。WidgetBridge 先调它再写快照。
     * 已完成步数变了才拉一次详情；拉不到就当没有步名（只说「第 N/M 步」）。
     */
    suspend fun stepTitle(task: TaskItem): String? {
        val done = task.steps_done ?: 0
        stepCache[task.id]?.let { (d, t) -> if (d == done) return t }
        val d = fetchDetail(task.id) ?: return stepCache[task.id]?.second
        val t = TaskNotifyRules.currentStepTitle(d.steps, done)
        stepCache[task.id] = done to t
        return t
    }

    private suspend fun fetchDetail(id: String): TaskDetail? =
        withContext(BackgroundWork) { AppGraph.http.get("/tasks/${UmbraUrl.segment(id)}", TaskDetail.serializer()) }

    /** 任务开始时刻（计时器起点）：created_at；解不出用第一次看到它执行的时刻。 */
    fun startedAt(task: TaskItem, now: Long = System.currentTimeMillis()): Long =
        TaskTime.parse(task.created_at)?.toEpochMilli() ?: firstSeen.getOrPut(task.id) { now }

    /** 拿最新的任务列表同步一次（iOS: UmbraLiveActivityController.sync）。由 WidgetBridge 在同一把锁里调。 */
    suspend fun sync(ctx: Context, items: List<TaskItem>, gen: Int) {
        syncGen = gen
        if (!mayPost(gen)) return
        listen()
        // 跟哪个：正在跟的那个还在常驻态就继续跟；否则挑第一个执行中 / 需确认的。
        // 挂起的那个让位给别的正在跑 / 等确认的任务（胶囊态说的是「现在在动的那个」）。
        val active = items.firstOrNull { val p = TaskNotifyRules.phase(it.status); p == TaskNotifyPhase.Running || p == TaskNotifyPhase.Awaiting }
        val cur = current?.let { c -> items.firstOrNull { it.id == c } }
        val curPhase = cur?.let { TaskNotifyRules.phase(it.status) }
        val live = when {
            cur != null && curPhase != null && TaskNotifyRules.isLive(curPhase) && (curPhase != TaskNotifyPhase.Hold || active == null) -> cur
            else -> active
        }
        if (!adopted) adopt(ctx, live?.id)

        val prevId = current
        if (live != null && prevId != null && prevId != live.id) {
            val prev = items.firstOrNull { it.id == prevId }
            if (prev != null && TaskNotifyRules.isLive(TaskNotifyRules.phase(prev.status))) {
                // 旧的还没结束（挂起让位）：只撤常驻那条，不替它说结果。
                nm(ctx).cancel(TAG_LIVE + prevId, ID_LIVE)
                current = null; lastPosted = null
            } else {
                // 换了任务：先给旧的那个收尾（它可能已经跑完 / 失败），再跟新的。
                finish(ctx, prevId, prev)
            }
        }
        if (live != null) {
            // 用户关了通知权限 / 渠道就不发 —— 发也只会被系统吞掉（iOS：关了实况就不 request）。
            if (!ReminderNotifier.canPost(ctx)) { current = null; return }
            current = live.id
            currentTitle = live.title
            postLive(ctx, live)
            return
        }
        val id = current ?: return
        finish(ctx, id, items.firstOrNull { it.id == id })
    }

    /** 跟着的任务离开了常驻态：撤掉常驻那条，按结果发一条（失败 / 完成 / 已取消）。 */
    private suspend fun finish(ctx: Context, id: String, listed: TaskItem?) {
        nm(ctx).cancel(TAG_LIVE + id, ID_LIVE)
        // 列表里没有它 ≠ 服务器上删了：列表只拉最近 30 条，任务多了它会被挤出去。单独问一次详情，
        // 只有回 404 才算「找不到」（否则那句「在服务器上找不到了」是假话）；问不到（断网）就按原来的 null 收尾、不下定论。
        var gone404 = false
        val ended: TaskItem? = listed ?: when (val r = AppGraph.http.raw("GET", "/tasks/${com.geek.umbra.net.UmbraUrl.segment(id)}")) {
            is com.geek.umbra.net.HttpResult.Ok ->
                runCatching { com.geek.umbra.net.UmbraJson.decodeFromString(com.geek.umbra.model.TaskDetail.serializer(), r.body).task }.getOrNull()
            is com.geek.umbra.net.HttpResult.Fail -> { gone404 = r.status == 404; null }
            else -> null
        }
        val title = ended?.title ?: currentTitle ?: ""
        if (current == id) { current = null; lastPosted = null }
        pausedAt.remove(id); stepCache.remove(id); questions.remove(id); firstSeen.remove(id)
        if (!ReminderNotifier.canPost(ctx)) return
        val showing = AppGraph.router.top == Route.TaskDetail(id)
        when (TaskNotifyRules.phase(ended?.status)) {
            TaskNotifyPhase.Failed -> postFailed(ctx, ended!!, title)
            TaskNotifyPhase.Done -> if (TaskNotifyRules.postsDone(AppForeground.isForeground, showing)) {
                val body = TaskNotifyRules.doneBody(
                    TaskTime.parse(ended?.created_at)?.toEpochMilli(), TaskTime.parse(ended?.updated_at)?.toEpochMilli(),
                )
                postResult(ctx, id, title, body, listOf(Pair("查看产出", openTask(ctx, id))))
            }
            // 四态之外两态（设计侧批次 201 第 7 条）：服务端找不到 / 被取消。本机点「停止」造成的取消不发。
            else -> when {
                ended == null && gone404 -> postResult(ctx, id, title, TaskNotifyRules.NOT_FOUND_BODY, listOf(Pair(TaskNotifyRules.NOT_FOUND_ACTION, openTaskList(ctx))), contentIntent = openTaskList(ctx))
                ended == null -> Unit // 没问到：不知道它怎么结束的，不发一条可能说错的通知
                TaskLocalStops.take(id) -> Unit
                ended.status == "cancelled" || ended.status == "canceled" || ended.status == "stopped" ->
                    postResult(ctx, id, title, TaskNotifyRules.cancelledBody(ended.steps_done, ended.steps_total), emptyList())
                TaskNotifyRules.postsDone(AppForeground.isForeground, showing) ->
                    postResult(ctx, id, title, LiveActivityRules.finalFrame(ended.status).first, emptyList())
            }
        }
    }

    /** 会话守卫「关」：撤掉系统里**所有**本 App 的任务通知（含结果那一条），不只内存里记着的那条（理由同 iOS endAll）。 */
    fun endAll(ctx: Context) {
        current = null; currentTitle = null; lastPosted = null
        adopted = false
        firstSeen.clear(); pausedAt.clear(); stepCache.clear(); questions.clear()
        runCatching {
            nm(ctx).activeNotifications.filter { it.tag?.startsWith(TAG_LIVE) == true || it.tag?.startsWith(TAG_DONE) == true }
                .forEach { nm(ctx).cancel(it.tag, it.id) }
        }
    }

    /** 「重试任务」成了：失败那条撤掉。 */
    fun clearResult(ctx: Context, id: String) {
        nm(ctx).cancel(TAG_DONE + id, ID_DONE)
    }

    /** 「重试任务」没成：失败那条原地换成没成的那句话（不再挂重试钮），点本体照样进详情。 */
    fun retryFailed(ctx: Context, id: String, message: String) {
        val old = runCatching { nm(ctx).activeNotifications.firstOrNull { it.tag == TAG_DONE + id } }.getOrNull()
        val title = old?.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "任务失败"
        // 人在当前会话里点的「重试」：按现在这一代核（不借 sync 记下的那一代 —— 刚登回来、还没 sync 过时它是旧的）。
        postResult(ctx, id, title, message, emptyList(), gen = AppGraph.guard.generation)
    }

    /** 挂起 / 待执行时那一句：和任务列表那一行同一句（TaskSuspendRules.display 的 listMeta），没有就退回状态词。 */
    private fun holdLine(task: TaskItem): String =
        TaskSuspendRules.display(task.status, task.suspendInfo)?.listMeta ?: listLine(task)

    /** 任务列表那一行的状态词（UmbraStatus 的 label，如需确认是「等你授权」）。 */
    private fun listLine(task: TaskItem): String = UmbraStatus.valueOf(taskStatusOf(task.status).name).label

    /** 点通知 → 这条任务的详情（umbra://task/<id>，深链已在 MainActivity 接好）。 */
    /** 落任务列表（服务端已经找不到这条任务，进详情只会是一张「不存在」）。 */
    private fun openTaskList(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, "tasks".hashCode(),
            Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse("umbra://tasks"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun openTask(ctx: Context, id: String): PendingIntent =
        PendingIntent.getActivity(
            ctx, id.hashCode(),
            Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse("umbra://task/$id"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** 外链（去充值）：交给系统浏览器。地址已经过 ReasonRules.topupLink 只收 http(s)。 */
    private fun openUrl(ctx: Context, url: String): PendingIntent =
        PendingIntent.getActivity(
            ctx, url.hashCode(),
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** accent：品牌橙 #E8590C（进度条与动作钮取它）。 */
    private val orange get() = UmbraLight.orange.toArgb()
    /** 需确认时进度段的颜色：warning #B45309。 */
    private val warning get() = UmbraLight.warning.toArgb()

    private fun postLive(ctx: Context, task: TaskItem) {
        val id = task.id
        val now = System.currentTimeMillis()
        val phase = TaskNotifyRules.phase(task.status)
        val done = task.steps_done ?: 0
        val total = task.steps_total ?: 0
        val running = phase == TaskNotifyPhase.Running
        val awaiting = phase == TaskNotifyPhase.Awaiting
        if (running) pausedAt.remove(id) else pausedAt.putIfAbsent(id, now)
        val title = if (awaiting) TaskNotifyRules.awaitingTitle(task.title) else task.title
        val body = when (phase) {
            TaskNotifyPhase.Running -> TaskNotifyRules.runningBody(done, total, stepCache[id]?.takeIf { it.first == done }?.second)
            TaskNotifyPhase.Awaiting -> TaskNotifyRules.awaitingBody(questions[id], listLine(task))
            else -> holdLine(task)
        }
        val chip = TaskNotifyRules.chipText(phase, done, total)
        // 执行中：头部是计时器，起点 = 任务开始；停住时显示停住那一刻。
        val whenMs = if (running) startedAt(task, now) else pausedAt[id] ?: now
        val key = "$id|$phase|$title|$body|$done|$total|$whenMs"
        if (key == lastPosted) return
        ensureChannels(ctx)

        val actions = buildList {
            if (awaiting) add("去确认" to TaskNotifyIntents.activity(ctx, TaskNotifyIntents.CONFIRM, id))
            add("停止" to TaskNotifyIntents.activity(ctx, TaskNotifyIntents.STOP, id))
        }
        val n: Notification = if (Build.VERSION.SDK_INT >= 36) {
            // Android 16：ProgressStyle 每步一段 + 申请提升为 Live Update（状态栏胶囊放百分比 / 待确认）。
            val segColor = if (awaiting) warning else orange
            val style = Notification.ProgressStyle()
            if (total > 0) {
                style.setProgressSegments(List(total) { Notification.ProgressStyle.Segment(1).setColor(segColor) })
                style.setProgress(done.coerceIn(0, total))
            } else style.setProgressIndeterminate(running)
            Notification.Builder(ctx, CHANNEL_LIVE)
                .setSmallIcon(R.drawable.ic_task_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setColor(orange)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setContentIntent(openTask(ctx, id))
                .setWhen(whenMs)
                .setShowWhen(true)
                .setUsesChronometer(running)
                .setStyle(style)
                .setShortCriticalText(chip)
                .setRequestPromotedOngoing(true)
                .apply { actions.forEach { (label, pi) -> addAction(Notification.Action.Builder(null, label, pi).build()) } }
                .build()
        } else {
            // 旧版的进度条换不了段色：需确认时只停住（答复 #9：旧版无法换色就只停住）。
            NotificationCompat.Builder(ctx, CHANNEL_LIVE)
                .setSmallIcon(R.drawable.ic_task_notification)
                .setContentTitle(title)
                .setContentText(body)
                // 需确认的问话 2 行截断：系统展开模板不限行数，这里只能给 BigText，行数由系统决定。
                .apply { if (awaiting) setStyle(NotificationCompat.BigTextStyle().bigText(body)) }
                .setColor(orange)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setContentIntent(openTask(ctx, id))
                .setWhen(whenMs)
                .setShowWhen(true)
                .setUsesChronometer(running)
                // 服务端没给步骤数：执行中画不定进度，不编进度（iOS percentText = "…"）。
                .setProgress(total.coerceAtLeast(0), done.coerceIn(0, total.coerceAtLeast(0)), total <= 0 && running)
                .apply { actions.forEach { (label, pi) -> addAction(0, label, pi) } }
                .build()
        }
        if (!mayPost(syncGen)) return
        runCatching { nm(ctx).notify(TAG_LIVE + id, ID_LIVE, n) }.onSuccess { lastPosted = key }
    }

    /** 失败：拉一次详情拿失败的那一步与原因，拆进标题 / 正文 / 动作三个槽。拉不到就「任务失败」+ 列表里的摘要。 */
    private suspend fun postFailed(ctx: Context, task: TaskItem, title: String) {
        val d = fetchDetail(task.id)
        val step = d?.steps?.sortedBy { it.seq }?.firstOrNull { taskStatusOf(it.status) == TaskStatusKind.Failed }
        val err = step?.error
        val why = err?.message?.takeIf { it.isNotEmpty() } ?: (d?.task?.result_summary ?: task.result_summary)
        val status = d?.task?.status ?: task.status
        val actions = TaskNotifyRules.failActions(status, err?.reason_kind, err?.topup_url, d?.task?.executor ?: task.executor).map { a ->
            when (a) {
                TaskNotifyFailAction.Retry -> Pair(TaskRetryCopy.retry, TaskNotifyIntents.retry(ctx, task.id))
                TaskNotifyFailAction.Relaunch -> Pair(
                    TaskRetryCopy.relaunch,
                    TaskNotifyIntents.activity(ctx, TaskNotifyIntents.RELAUNCH, task.id,
                        TaskRetryRules.relaunchText(task.goal, task.name)),
                )
                is TaskNotifyFailAction.Topup -> Pair(topupLabel(ctx, err?.reason_kind), openUrl(ctx, a.url))
                TaskNotifyFailAction.Caps -> Pair("去看能力", TaskNotifyIntents.activity(ctx, TaskNotifyIntents.CAPS, task.id))
            }
        }
        postResult(ctx, task.id, TaskNotifyRules.failTitle(step), TaskNotifyRules.failBody(title, why), actions)
    }

    /** 「去充值」那颗钮的字面：与任务详情错误卡同一套（TaskViews.kt FailBlock）。 */
    private fun topupLabel(ctx: Context, wire: String?): String = ctx.getString(
        when (ReasonKind.of(wire)) {
            ReasonKind.NeedMembership -> R.string.vision_openMembership
            ReasonKind.QuotaExhausted -> R.string.android_quotaShop
            else -> R.string.vision_topup
        },
    )

    /** 结果那一条（失败 / 完成 / 已取消）：「任务结果」渠道，普通可划掉。 */
    private fun postResult(
        ctx: Context, id: String, title: String, body: String, actions: List<Pair<String, PendingIntent>>,
        contentIntent: PendingIntent = openTask(ctx, id),
        gen: Int = syncGen,
    ) {
        ensureChannels(ctx)
        val n = NotificationCompat.Builder(ctx, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_task_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setColor(orange)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .apply { actions.forEach { (label, pi) -> addAction(0, label, pi) } }
            .build()
        if (!mayPost(gen)) return
        runCatching { nm(ctx).notify(TAG_DONE + id, ID_DONE, n) }
    }
}
