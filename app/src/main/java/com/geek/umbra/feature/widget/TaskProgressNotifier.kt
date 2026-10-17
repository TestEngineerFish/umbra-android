// 任务进行中常驻通知 —— Android 对 iOS「灵动岛 + 锁屏实况」（UmbraTaskLiveActivity + WidgetBridge.swift 的
// UmbraLiveActivityController）的替代。
//
// ⛔ 灵动岛 Android 无法实现（没有对等的系统界面）。这里做的是：
//   · 任务执行中：一条 ongoing 通知（渠道「任务进度」，低重要度、不响），标题 = 任务短标题，正文 = 状态行，带进度条；
//     Android 16+ 用 Notification.ProgressStyle 并申请提升为 Live Update（状态栏胶囊 / 锁屏顶部，最接近灵动岛的那一层），
//     以下用 NotificationCompat.setProgress；
//   · 换了任务：撤掉旧的、发新的；
//   · 挂起 / 待执行：**不是结束**（LiveActivityRules.keepsAlive），留着、状态行换成任务列表那一句；
//   · 完成 / 失败 / 取消 / 找不到：换成一条普通通知说结果（LiveActivityRules.finalFrame，只有 done 是「任务完成」），撤掉 ongoing；
//   · 登出：全部撤掉（含结果那一条：任务名是联网数据）。
// 一次只跟一个执行中任务（同 iOS：胶囊态就是「当前那个任务」）。
// 数据与任务小组件同源同 tick：都由 WidgetBridge.syncTasks 那一次调用驱动。
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
import com.geek.umbra.app.MainActivity
import com.geek.umbra.designsystem.UmbraLight
import com.geek.umbra.designsystem.UmbraStatus
import com.geek.umbra.feature.reminder.ReminderNotifier
import com.geek.umbra.feature.task.TaskSuspendRules
import com.geek.umbra.feature.task.suspendInfo
import com.geek.umbra.feature.task.taskStatusOf
import com.geek.umbra.model.TaskItem

object TaskProgressNotifier {
    /** 渠道：低重要度（不响、不弹横幅），进度本来就是「瞄一眼」的东西。 */
    const val CHANNEL = "umbra.task.progress"
    /** 进行中那一条的 tag 前缀，后面接 taskId —— 冷启动认领靠它从系统里认出「这条跟的是哪个任务」。 */
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

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    private fun ensureChannel(ctx: Context) {
        val ch = NotificationChannel(CHANNEL, ctx.getString(R.string.task_progress_channel), NotificationManager.IMPORTANCE_LOW).apply {
            description = ctx.getString(R.string.task_progress_channel_desc)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        nm(ctx).createNotificationChannel(ch)
    }

    /**
     * 冷启动认领（umbra-ios#22 同一件事）：通知活得比进程久。把系统里还挂着的进行中通知认回来，
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
        lastPosted = null
    }

    /** 拿最新的任务列表同步一次（iOS: UmbraLiveActivityController.sync）。在主线程调（WidgetBridge 已切过来）。 */
    fun sync(ctx: Context, items: List<TaskItem>) {
        val running = items.firstOrNull { it.status == "running" }
        if (!adopted) adopt(ctx, running?.id)
        if (running != null) {
            if (current != null && current != running.id) {
                // 换了任务：撤掉旧的（不留结果那一条，旧任务的状态在列表里看）。
                nm(ctx).cancel(TAG_LIVE + current, ID_LIVE)
                current = null
            }
            // 用户关了通知权限 / 渠道就不发 —— 发也只会被系统吞掉（iOS：关了实况就不 request）。
            if (!ReminderNotifier.canPost(ctx)) { current = null; return }
            current = running.id
            currentTitle = running.title
            postLive(ctx, running.id, running.title, running.steps_done ?: 0, running.steps_total ?: 0, WidgetTaskRules.line(running))
            return
        }
        val id = current ?: return
        val ended = items.firstOrNull { it.id == id }
        // 挂起 / 待执行**不是结束**：留着这条，进度停在挂起那一刻，状态行换成任务列表那一行同一句。
        if (ended != null && LiveActivityRules.keepsAlive(ended.status)) {
            if (ReminderNotifier.canPost(ctx)) {
                postLive(ctx, id, ended.title, ended.steps_done ?: 0, ended.steps_total ?: 0, holdLine(ended))
            }
            return
        }
        // 真的结束了：换成一条普通通知说结果，再撤掉 ongoing。
        val (line, _) = LiveActivityRules.finalFrame(ended?.status)
        val title = ended?.title ?: currentTitle ?: ""
        nm(ctx).cancel(TAG_LIVE + id, ID_LIVE)
        current = null
        lastPosted = null
        if (ReminderNotifier.canPost(ctx)) postDone(ctx, id, title, line)
    }

    /** 会话守卫「关」：撤掉系统里**所有**本 App 的任务通知（含结果那一条），不只内存里记着的那条（理由同 iOS endAll）。 */
    fun endAll(ctx: Context) {
        current = null; currentTitle = null; lastPosted = null
        adopted = false
        runCatching {
            nm(ctx).activeNotifications.filter { it.tag?.startsWith(TAG_LIVE) == true || it.tag?.startsWith(TAG_DONE) == true }
                .forEach { nm(ctx).cancel(it.tag, it.id) }
        }
    }

    /** 挂起 / 待执行时那一句：和任务列表那一行同一句（TaskSuspendRules.display 的 listMeta），没有就退回状态词。 */
    private fun holdLine(task: TaskItem): String =
        TaskSuspendRules.display(task.status, task.suspendInfo)?.listMeta
            ?: UmbraStatus.valueOf(taskStatusOf(task.status).name).label

    /** 点通知 → 这条任务的详情（umbra://task/<id>，深链已在 MainActivity 接好）。 */
    private fun openTask(ctx: Context, id: String): PendingIntent =
        PendingIntent.getActivity(
            ctx, id.hashCode(),
            Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse("umbra://task/$id"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private val orange get() = UmbraLight.orange.toArgb()

    private fun postLive(ctx: Context, id: String, title: String, done: Int, total: Int, line: String) {
        val key = "$id|$title|$done|$total|$line"
        if (key == lastPosted) return
        ensureChannel(ctx)
        val n: Notification = if (Build.VERSION.SDK_INT >= 36) {
            // Android 16：ProgressStyle + 申请提升为 Live Update（状态栏胶囊里放百分比，最接近 iOS 胶囊态的「进度弧 + 百分比」）。
            val style = Notification.ProgressStyle()
            if (total > 0) {
                style.setProgressSegments(listOf(Notification.ProgressStyle.Segment(total).setColor(orange)))
                style.setProgress(done.coerceIn(0, total))
            } else style.setProgressIndeterminate(true)
            Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_task_notification)
                .setContentTitle(title)
                .setContentText(line)
                .setColor(orange)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setContentIntent(openTask(ctx, id))
                .setStyle(style)
                .setShortCriticalText(WidgetTaskRules.percentText(done, total))
                .setRequestPromotedOngoing(true)
                .build()
        } else {
            NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_task_notification)
                .setContentTitle(title)
                .setContentText(line)
                .setColor(orange)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setContentIntent(openTask(ctx, id))
                // 服务端没给步骤数：不定进度条，不编进度（iOS percentText = "…"）。
                .setProgress(total.coerceAtLeast(0), done.coerceIn(0, total.coerceAtLeast(0)), total <= 0)
                .build()
        }
        runCatching { nm(ctx).notify(TAG_LIVE + id, ID_LIVE, n) }.onSuccess { lastPosted = key }
    }

    /** 结果那一条：普通通知（可划掉），同一个低重要度渠道，不响。 */
    private fun postDone(ctx: Context, id: String, title: String, line: String) {
        ensureChannel(ctx)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_task_notification)
            .setContentTitle(title)
            .setContentText(line)
            .setColor(orange)
            .setAutoCancel(true)
            .setContentIntent(openTask(ctx, id))
            .build()
        runCatching { nm(ctx).notify(TAG_DONE + id, ID_DONE, n) }
    }
}
