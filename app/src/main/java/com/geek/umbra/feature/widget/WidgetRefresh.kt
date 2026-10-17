// 小组件「到点换一帧」—— 对应 iOS 的 Timeline（WidgetTimeRules.refreshDates 排出每一个该换帧的时刻）。
//
// Android 没有时间线：appwidget-provider 的 updatePeriodMillis 最短 30 分钟，到点那一行 30 分钟后才变红不行。
// 所以用 AlarmManager 只排「下一个」该换帧的时刻（下一次提醒到点 / 下一个零点 / 任务快照满 10 分钟要亮「更新于」），
// 响了就重画并接着排下一个。用 RTC（不唤醒）：屏幕关着时没人看小组件，亮屏时系统补发。
// updatePeriodMillis（30 分钟）只是兜底。
package com.geek.umbra.feature.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import com.geek.umbra.feature.reminder.ReminderStore

object WidgetRefresh {
    private const val REQUEST = 7310

    /** 桌面上有没有放这两个小组件（一个都没放就不排闹钟）。 */
    private fun anyPlaced(ctx: Context): Boolean {
        val m = AppWidgetManager.getInstance(ctx) ?: return false
        return listOf(TaskWidgetReceiver::class.java, ReminderWidgetReceiver::class.java).any {
            runCatching { m.getAppWidgetIds(ComponentName(ctx, it)).isNotEmpty() }.getOrDefault(false)
        }
    }

    /** 下一个该换帧的时刻：提醒的下一次到点、下一个零点、任务快照满 10 分钟（「更新于」该亮出来了）三者取最早。 */
    internal fun nextAt(now: Long): Long {
        val rows = ReminderWidgetData.rows(ReminderStore.items.value, now)
        val dates = WidgetTimeRules.refreshDates(rows, now).toMutableList()
        dates += WidgetTimeRules.startOfNextDay(now, java.time.ZoneId.systemDefault())
        WidgetBridge.taskSnapshot.value?.let { s -> (s.savedAtMs + 10 * 60 * 1000 + 1000).takeIf { it > now }?.let { dates += it } }
        return dates.min()
    }

    /** 排下一次（同一个 PendingIntent，重排即覆盖）。没放小组件就撤掉。 */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            ctx, REQUEST, Intent(ctx, WidgetRefreshReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (!anyPlaced(ctx)) { am.cancel(pi); return }
        val at = runCatching { nextAt(System.currentTimeMillis()) }.getOrNull() ?: return
        // 精确闹钟要授权（Android 12+，提醒那边已申请）；没授权就退非精确 —— 晚一点变红，好过不变。
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        runCatching { if (exact) am.setExact(AlarmManager.RTC, at, pi) else am.set(AlarmManager.RTC, at, pi) }
    }
}

/** 到点：两个小组件按这一刻重算，并排下一个。 */
class WidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        WidgetBridge.refreshAll()
    }
}
