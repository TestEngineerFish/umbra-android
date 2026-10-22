// 主 App → 小组件 / 进行中通知的桥 —— 对应 iOS Utils/WidgetBridge.swift（UmbraWidgetBridge）+ WidgetShared.swift 的快照读写。
//
// 职责：
//   1. 任务列表一变就写快照（内存 + filesDir/widget/tasks.json），让任务小组件重画；
//   2. 把执行中任务同步给进行中通知（TaskProgressNotifier，替代 iOS 灵动岛 / 锁屏实况）；
//   3. 提醒落盘后让提醒小组件重画、重排下一次到点刷新。
// 任务的小组件与通知出自 [syncTasks] 同一次调用、同一份列表 —— 「同源同 tick」（规范增补 4.3）。
//
// 诚实边界（同 iOS）：任务列表只在 App 进程活着时拉（工具页轮询 / task_update 帧 / 登录时一次），
// 所以进行中通知的进度**跟随 App 内的拉取**更新；进程被系统收掉后要继续动得等服务端推送（FCM 待服务端），这里不装。
package com.geek.umbra.feature.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.geek.umbra.app.AppGraph
import com.geek.umbra.model.TaskItem
import com.geek.umbra.net.UmbraJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

object WidgetBridge {
    private val ctx: Context get() = AppGraph.context

    /** 任务快照文件。任务没有本地持久化，进程被杀后小组件还得有东西画，所以落盘（iOS 落 App Group）。 */
    private val taskFile: File get() = File(File(ctx.filesDir, "widget").also { it.mkdirs() }, "tasks.json")

    private val _task = MutableStateFlow<TaskWidgetSnapshot?>(null)
    @Volatile private var taskLoaded = false

    /** 任务快照（第一次读时从磁盘载入）。null = 还没有快照（还没拉到过任务列表，或登出时删掉了）。 */
    val taskSnapshot: StateFlow<TaskWidgetSnapshot?>
        get() {
            if (!taskLoaded) synchronized(this) { if (!taskLoaded) { _task.value = loadTaskFile(); taskLoaded = true } }
            return _task
        }

    /**
     * 「这一帧」的时钟。到点 / 零点 / 数据变了时拨一下：正在跑的 Glance 会话收到就地重算，
     * 「已过期 / 今天 / 更新于 / 今天完成」都按这一刻现算（umbra-ios#85）。
     */
    val tick = MutableStateFlow(System.currentTimeMillis())

    // MARK: 任务

    /**
     * 任务列表拉到了（TasksStore.loadTasks 调）。iOS: WidgetBridge.swift:198。
     * 未登录不写快照、不发通知：挡的是「登出那一刻正好有一轮拉取在路上、回来之后又把快照和通知写回去」。
     */
    fun syncTasks(items: List<TaskItem>) {
        ensureObserving()
        if (AppGraph.guard.locked.value) return
        val gen = AppGraph.guard.generation
        AppGraph.scope.launch {
            // 一把锁串起来：当前步名要拉详情（挂起点），两轮轮询的回包不能交错写快照 / 发通知。
            syncLock.withLock {
                if (AppGraph.guard.locked.value) return@withLock
                // 当前步名与开始时刻：小组件 4×2 与通知正文 / 计时器同一份（第 199 封 #9 / #10）。
                val running = items.firstOrNull { it.status == "running" }
                val step = running?.let { TaskProgressNotifier.stepTitle(it) }
                val started = running?.let { TaskProgressNotifier.startedAt(it) }
                val snap = WidgetTaskRules.snapshot(items, System.currentTimeMillis(), stepTitle = step, startedAtMs = started)
                // 任务页开着时每 3.5 秒轮询一次：内容没变、上一份又不到一分钟，就不重写文件、不重画
                // （「更新于」只在 10 分钟后才出，一分钟的粒度足够）。
                val prev = taskSnapshot.value
                val unchanged = prev != null && prev.copy(savedAtMs = 0) == snap.copy(savedAtMs = 0) && snap.savedAtMs - prev.savedAtMs < 60_000
                if (!unchanged) {
                    _task.value = snap
                    withContext(Dispatchers.IO) { runCatching { taskFile.writeText(UmbraJson.encodeToString(TaskWidgetSnapshot.serializer(), snap)) } }
                    redraw(task = true, reminder = false)
                }
                // 与小组件同一份列表、同一次调用 —— 同源同 tick。
                TaskProgressNotifier.sync(ctx, items, gen)
            }
        }
    }

    /** syncTasks 的串行锁。 */
    private val syncLock = Mutex()

    // MARK: 提醒

    private var reminderJob: Job? = null

    /**
     * 提醒落盘了（ReminderCache.save 调）。一次操作里可能连着存好几次（推进、合并、补推），合成 300ms 内一次重画。
     * 不看登录（2026-10-10：提醒是本地功能，小组件照显示）。
     */
    fun remindersChanged() {
        reminderJob?.cancel()
        reminderJob = AppGraph.scope.launch {
            delay(300)
            redraw(task = false, reminder = true)
        }
    }

    // MARK: 会话守卫的「关 / 开」（WidgetModule 在守卫上登记）

    /**
     * 「关」：删掉**任务**那份快照（任务名在里面；任务是联网功能），撤掉进行中通知，再让小组件重画 ——
     * 任务小组件读到未登录就画「请登录 Umbra」占位。提醒不受影响（本地功能）。iOS: WidgetBridge.swift:251。
     */
    fun sessionLocked() {
        taskLoaded = true
        _task.value = null
        AppGraph.scope.launch {
            withContext(Dispatchers.IO) { runCatching { taskFile.delete() } }
            TaskProgressNotifier.endAll(ctx)
            redraw(task = true, reminder = true)
        }
    }

    /** 「开」：只让小组件重画（先换回「还没有数据」那一档）。任务快照由下一轮任务拉取写回来。 */
    fun sessionUnlocked() {
        AppGraph.scope.launch { redraw(task = true, reminder = true) }
    }

    // MARK: 重画 / 刷新

    /**
     * 主题换了（iOS `UmbraWidgetBridge.themeChanged`）：小组件的颜色在重画时现取当前主题（WTheme 的 getter），重画一遍就跟上。
     * 不问会话守卫：主题是本机偏好，没登录也能换。
     */
    fun themeChanged() = refreshAll()

    /** 到点 / 零点 / 系统周期刷新：两个小组件都按这一刻重算一次（WidgetRefreshReceiver 调）。 */
    fun refreshAll() {
        AppGraph.scope.launch { redraw(task = true, reminder = true) }
    }

    /**
     * 拨时钟（让开着的会话重组）+ updateAll（让没开会话的那几个起一次 provideGlance）+ 重排下一次到点刷新。
     * updateAll 在没有放小组件时是空操作。
     */
    private suspend fun redraw(task: Boolean, reminder: Boolean) {
        tick.value = System.currentTimeMillis()
        runCatching { if (task) TaskWidget().updateAll(ctx) }
        runCatching { if (reminder) ReminderWidget().updateAll(ctx) }
        WidgetRefresh.schedule(ctx)
    }

    private fun loadTaskFile(): TaskWidgetSnapshot? {
        val f = taskFile
        if (!f.exists()) return null
        val raw = runCatching { f.readText() }.getOrNull() ?: return null
        return runCatching { UmbraJson.decodeFromString(TaskWidgetSnapshot.serializer(), raw) }.getOrElse {
            // 读坏的文件改名留底再从空开始（CLAUDE.md 约定 11）。
            runCatching { f.renameTo(File(f.parentFile, "${f.name}.corrupt-${System.currentTimeMillis()}")) }
            null
        }
    }

    // MARK: 守卫登记的兜底

    @Volatile internal var registered = false
    @Volatile private var observing = false

    /**
     * 守卫「关 / 开」的兜底：正常由 [WidgetModule.register] 在 FeatureGraph 里登记（App 启动时调）。
     * 那一行要动 app/FeatureGraph.kt（不归本包），没接上之前这里自己盯 AppGraph.guard.locked ——
     * 不盯的话，登出后任务小组件会一直挂着上一个账号的任务名、进行中通知也不撤。接上之后这里什么都不做。
     */
    fun ensureObserving() {
        if (registered || observing) return
        observing = true
        AppGraph.scope.launch {
            var first = true
            AppGraph.guard.locked.collect { locked ->
                if (locked) sessionLocked() else if (!first) sessionUnlocked()
                first = false
            }
        }
    }
}
