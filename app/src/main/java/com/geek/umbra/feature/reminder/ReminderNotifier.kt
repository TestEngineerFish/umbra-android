// 提醒的本机通知 —— iOS UNUserNotification（ReminderViews.swift 的 rescheduleAll / scheduleSeries）+ Navigation/Notifications.swift。
//
// Android 的做法（docs/reference/04 A 节「系统集成 → Android」）：
// - AlarmManager **每条只排下一次**（主通知 + 提前提醒 `<id>.ahead` 两个闹钟），响了之后算下一次再排 ——
//   比 iOS「可用系统重复触发器的用重复触发器、其余预排 12 次、全局 64 个预算」简单，也没有预算问题
//   （Android 每个 App 闹钟上限 500，每条占 2 个）；
// - 排出来的时刻全部经 ReminderAdvance.upcoming（与推进同一套 step、同一个提醒时区、同一张节假日表、同一个结束截点），
//   所以通知响的就是列表推进会推到的那一刻；
// - 精确闹钟：`setExactAndAllowWhileIdle`；Android 12+ 没有 SCHEDULE_EXACT_ALARM 授权（14+ 默认拒）时退
//   `setAndAllowWhileIdle`（可能晚几分钟），列表横幅说明并给「去授权」；
// - 开机 / 改时间 / 改时区 / 升级后整体重排（ReminderBootReceiver）；
// - 通知分类：一个 NotificationChannel「提醒」+ 两个 Action「完成」（幂等置完成）/「再等 10 分钟」（从现在推 10 分钟），
//   由 ReminderActionReceiver 处理；点通知本体 = 打开 MainActivity 带 `umbra://reminder/<id>`（深链进详情）。
// - ⚠️ 与 iOS 的差别：iOS 排好的通知内容是排的那一刻定死的；这里响的那一刻才从 store 取正文，所以永远是最新的那一版。
package com.geek.umbra.feature.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.app.MainActivity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import com.geek.umbra.net.UmbraJson

object ReminderNotifier {
    const val CHANNEL_ID = "umbra.reminder"
    private const val KIND_MAIN = "main"
    private const val KIND_AHEAD = "ahead"
    private const val EXTRA_ID = "reminderId"
    private const val EXTRA_KIND = "kind"
    private const val EXTRA_FIRE = "fireAt"
    const val ACTION_DONE = "com.geek.umbra.reminder.DONE"
    const val ACTION_SNOOZE = "com.geek.umbra.reminder.SNOOZE"
    /** 通知的 tag 前缀：撤孤儿时只认这一类，不碰别的模块的通知。 */
    private const val TAG_PREFIX = "umbra.reminder:"
    private const val NOTIF_MAIN = 1
    private const val NOTIF_AHEAD = 2
    /** 排过闹钟的提醒 id（落盘）：被别端删掉的那条随墓碑从 items 里消失后，靠它撤掉它的闹钟（删掉的提醒到点照响）。 */
    private const val SCHEDULED_FILE = "alarms.json"

    /** 渠道名「提醒」。重复创建同 id 渠道是幂等的（切语言后名字会跟着更新）。 */
    fun ensureChannel(ctx: Context) {
        val ch = NotificationChannel(CHANNEL_ID, ctx.getString(R.string.reminder_notif_dueTitle), NotificationManager.IMPORTANCE_HIGH)
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    /** 能不能发通知（Android 13+ 要 POST_NOTIFICATIONS 运行时权限；用户也可能在设置里关了）。 */
    fun canPost(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        return NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }

    /** 精确闹钟授权（Android 12+ 才有这一问）。 */
    fun exactAllowed(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** 下一次主通知 / 提前通知各在什么时刻响。null = 不排。 */
    internal fun nextFires(r: Reminder, cal: HolidayCalendar?, nowMs: Long): Pair<Long?, Long?> {
        // 地点提醒归 iOS 的地理围栏触发；它的 at_ms = 0，按时间排会在开机后立刻响一次。
        if (r.done || !ReminderPlace.timed(r)) return null to null
        val tz = ReminderAdvance.zone(r.tz)
        fun first(now: Long): Long? = if (r.repeatRule == "不重复") r.atMs.takeIf { it > now }
        else ReminderAdvance.upcoming(
            r.atMs, ReminderWire.ruleToWire(r.repeatRule), ReminderWire.freqToWire(r.customFreq), r.customN,
            ReminderAdvance.endCapMs(r), now, tz, 1, cal, r.anchorDay, r.weekDays,
        ).firstOrNull()
        val main = first(nowMs)
        // 提前提醒是另一条通知、两条都要响：找「提前时刻还在将来」的第一次（= 发生时刻 > now + 提前量）。
        val ahead = if (Reminder.aheadSchedulable(r.aheadMinutes)) {
            val shift = Reminder.aheadClamped(r.aheadMinutes) * 60_000L
            first(nowMs + shift)?.let { it - shift }
        } else null
        return main to ahead
    }

    /** 整体重排：每条排下一次（主 + 提前），再撤掉主人已不在 / 已完成的闹钟与已送达通知。 */
    fun rescheduleAll(ctx: Context, items: List<Reminder>, cal: HolidayCalendar?, nowMs: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val live = mutableSetOf<String>()
        for (r in items) {
            val (main, ahead) = nextFires(r, cal, nowMs)
            set(ctx, am, r.id, KIND_MAIN, main)
            set(ctx, am, r.id, KIND_AHEAD, ahead)
            if (main != null || ahead != null) live.add(r.id)
        }
        val before = loadScheduled()
        for (id in before - live) { cancelAlarm(ctx, am, id, KIND_MAIN); cancelAlarm(ctx, am, id, KIND_AHEAD) }
        saveScheduled(live)
        // 已送达、还挂在通知栏里的：主人没了就撤（那是一条已删提醒的原文，上面的「完成」点了也找不到主人）。
        val ids = items.map { it.id }.toSet()
        runCatching {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.activeNotifications.filter { it.tag?.startsWith(TAG_PREFIX) == true && it.tag.removePrefix(TAG_PREFIX) !in ids }
                .forEach { nm.cancel(it.tag, it.id) }
        }
    }

    /** 删掉一条：撤它的两个闹钟与已送达的通知。 */
    fun cancel(ctx: Context, id: String) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        cancelAlarm(ctx, am, id, KIND_MAIN); cancelAlarm(ctx, am, id, KIND_AHEAD)
        dismiss(ctx, id)
    }

    /** 撤掉这条已送达的通知（点了「完成 / 再等」之后，通知栏里那条就不该留着）。 */
    fun dismiss(ctx: Context, id: String) {
        val nm = NotificationManagerCompat.from(ctx)
        nm.cancel(TAG_PREFIX + id, NOTIF_MAIN); nm.cancel(TAG_PREFIX + id, NOTIF_AHEAD)
    }

    private fun alarmIntent(ctx: Context, id: String, kind: String): Intent =
        Intent(ctx, ReminderAlarmReceiver::class.java)
            // data 区分每条每种：PendingIntent 按 Intent.filterEquals 去重，靠 requestCode 区分会撞 hash
            .setData(Uri.parse("umbra-alarm://reminder/${Uri.encode(id)}/$kind"))
            .putExtra(EXTRA_ID, id).putExtra(EXTRA_KIND, kind)

    private fun set(ctx: Context, am: AlarmManager, id: String, kind: String, at: Long?) {
        if (at == null) { cancelAlarm(ctx, am, id, kind); return }
        val pi = PendingIntent.getBroadcast(ctx, 0, alarmIntent(ctx, id, kind).putExtra(EXTRA_FIRE, at),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            if (exactAllowed(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun cancelAlarm(ctx: Context, am: AlarmManager, id: String, kind: String) {
        val pi = PendingIntent.getBroadcast(ctx, 0, alarmIntent(ctx, id, kind), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (pi != null) { am.cancel(pi); pi.cancel() }
    }

    private fun loadScheduled(): Set<String> =
        runCatching { (UmbraJson.parseToJsonElement(ReminderFiles.read(SCHEDULED_FILE) ?: "[]") as JsonArray).map { (it as JsonPrimitive).content }.toSet() }
            .getOrDefault(emptySet())

    private fun saveScheduled(ids: Set<String>) =
        ReminderFiles.write(SCHEDULED_FILE, buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } }.toString())

    /**
     * 闹钟响了：发通知。主通知标题「提醒」、正文 = 内容、副文 = 备注；提前那条标题「快到了」、正文「提前 X：内容」。
     * 两条都挂「完成 / 再等 10 分钟」，动作作用在主提醒上。iOS: ReminderViews.swift:870-904
     */
    internal fun post(ctx: Context, r: Reminder, kind: String) {
        if (!canPost(ctx)) return
        ensureChannel(ctx)
        val ahead = kind == KIND_AHEAD
        val title = ctx.getString(if (ahead) R.string.reminder_notif_aheadTitle else R.string.reminder_notif_dueTitle)
        val body = if (ahead) ctx.getString(R.string.reminder_notif_aheadBody, r.aheadNotifText, r.text) else r.text
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse("umbra://reminder/${r.id}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_reminder_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            // 启动器角标数（支持时）：未完成且已过期 / 今天的条数，同 iOS App 图标角标口径
            .setNumber(ReminderStore.pendingCount)
            // 不给「完成」标红 —— 完成一条提醒不是破坏性操作（iOS 同）
            .addAction(0, ctx.getString(R.string.reminder_notif_actionDone), actionIntent(ctx, ACTION_DONE, r.id))
            .addAction(0, ctx.getString(R.string.reminder_notif_actionSnooze), actionIntent(ctx, ACTION_SNOOZE, r.id))
        if (!ahead && r.note.isNotEmpty()) b.setSubText(r.note)
        runCatching { NotificationManagerCompat.from(ctx).notify(TAG_PREFIX + r.id, if (ahead) NOTIF_AHEAD else NOTIF_MAIN, b.build()) }
    }

    private fun actionIntent(ctx: Context, action: String, id: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, 0,
            Intent(ctx, ReminderActionReceiver::class.java).setAction(action).setData(Uri.parse("umbra-action://reminder/${Uri.encode(id)}"))
                .putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    internal fun idOf(i: Intent): String? = i.getStringExtra(EXTRA_ID)
    internal fun kindOf(i: Intent): String = i.getStringExtra(EXTRA_KIND) ?: KIND_MAIN
    internal fun fireOf(i: Intent): Long = i.getLongExtra(EXTRA_FIRE, System.currentTimeMillis())
}

/**
 * 闹钟到点。进程可能是被它冷启动的：Application.onCreate 已经跑过 AppGraph.init → ReminderModule.register →
 * ReminderStore.start（读缓存、推进、重排），这里只管发通知 + 排它之后的下一次。
 * 不看登录（2026-10-10：提醒是本地功能）。
 */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppGraph.init(context.applicationContext)
        ReminderStore.start()
        val id = ReminderNotifier.idOf(intent) ?: return
        val r = ReminderStore.item(id)
        if (r != null && !r.done) ReminderNotifier.post(context, r, ReminderNotifier.kindOf(intent))
        ReminderStore.afterAlarm(ReminderNotifier.fireOf(intent))
    }
}

/**
 * 通知上的两个动作。⚠️「完成」是幂等的「标成完成」，不是取反（主通知和提前那条各点一次、或别的端已完成时，
 * 取反会翻回未完成还以 LWW 赢下全网）。「再等 10 分钟」从**现在**推 10 分钟。iOS: Notifications.swift:113
 */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppGraph.init(context.applicationContext)
        ReminderStore.start()
        val id = ReminderNotifier.idOf(intent) ?: return
        when (intent.action) {
            ReminderNotifier.ACTION_DONE -> ReminderStore.setDone(id, true)
            ReminderNotifier.ACTION_SNOOZE -> ReminderStore.snooze(id)
        }
        ReminderNotifier.dismiss(context, id)
    }
}

/** 开机 / 改时间 / 改时区 / 升级 / 精确闹钟授权变了：闹钟被系统清掉或时刻要重算，整体重排。 */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppGraph.init(context.applicationContext)
        ReminderStore.start()
        ReminderStore.rescheduleAll()
        ReminderStore.refreshBadge()
    }
}
