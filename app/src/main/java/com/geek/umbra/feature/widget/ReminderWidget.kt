// 中号桌面小组件 · 今天的提醒（iOS: UmbraWidgets/UmbraReminderWidget.swift，规范增补 4.3）。
//
// 行数（设计侧第 199 封答复第 10 条，出图 #10）：最小 = 默认 4×2，放 2 行 +「还有 N 条」（正好 3 条时直接显示第三行）；
// 可拉高到 4×4，按可用高度逐行加，最多 6 行，超出仍「还有 N 条」收尾（TaskNotifyRules.reminderSlots / reminderRows）。
// 勾选圈只作展示（小组件不做写操作），点整卡进提醒列表。
// 已过期的圈转 danger、时间转 dangerText 并标「已过期」。**每一帧按这一刻现算**「已过期 + 今天」（umbra-ios#85）。
// 提醒是本地功能（2026-10-10）：不看登录，未登录照画。
//
// Android 差异：和主 App 同进程，不落提醒快照文件 —— 直接读 ReminderStore（启动时 ReminderModule 已从磁盘载入），
// 渲染时现算 WidgetTimeRules.snapshotRows → visible（同 iOS 两步，用例同一份）。
package com.geek.umbra.feature.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.LocalSize
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.geek.umbra.R
import com.geek.umbra.feature.reminder.Reminder
import com.geek.umbra.feature.reminder.ReminderAdvance
import com.geek.umbra.feature.reminder.ReminderStore
import com.geek.umbra.feature.reminder.ReminderWire

/** 提醒小组件。 */
class ReminderWidget : GlanceAppWidget() {
    /** 按实际尺寸排：行数要按高度现算（Responsive 只能给几个固定档，各家桌面格高不一）。 */
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        WidgetBridge.ensureObserving()
        provideContent {
            val tick by WidgetBridge.tick.collectAsState()
            val items by ReminderStore.items.collectAsState()
            val now = maxOf(tick, System.currentTimeMillis())
            ReminderWidgetContent(context, ReminderWidgetData.shown(items, now))
        }
    }
}

/** 系统那一头的接收器。添加 / 系统周期刷新时顺手排下一次到点 / 零点刷新。 */
class ReminderWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = ReminderWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WidgetRefresh.schedule(context)
    }
}

/** 提醒 → 小组件行（iOS: ReminderViews.swift:1044 `syncWidget` + WidgetBridge.syncReminders）。 */
internal object ReminderWidgetData {
    /**
     * 未完成的提醒 → 快照行（「已过期 + 未来 48 小时」）。重复提醒带上「推进会推到的那一次」起往后的时刻：
     * 和排通知同一套 ReminderAdvance.upcoming（同一张节假日表、同一个提醒时区、同一个结束日截点），
     * 小组件上显示的下一次就是推进会推到的那一次。
     */
    fun rows(items: List<Reminder>, now: Long): List<ReminderWidgetRow> {
        val cal = runCatching { ReminderStore.holidayCalendar }.getOrNull()
        val sources = items.filter { !it.done }.map { r ->
            val repeating = r.repeatRule != "不重复"
            val upcoming = if (!repeating) emptyList() else ReminderAdvance.upcoming(
                r.atMs, ReminderWire.ruleToWire(r.repeatRule), ReminderWire.freqToWire(r.customFreq), r.customN,
                ReminderAdvance.endCapMs(r), now, ReminderAdvance.zone(r.tz), WidgetTimeRules.MAX_LATER + 1,
                cal, r.anchorDay, r.weekDays,
            )
            WidgetTimeRules.ReminderSource(r.id, r.text, r.atMs, repeating, upcoming)
        }
        return WidgetTimeRules.snapshotRows(sources, now)
    }

    /** 这一帧该画的「已过期 + 今天」。 */
    fun shown(items: List<Reminder>, now: Long): List<WidgetTimeRules.Shown> = WidgetTimeRules.visible(rows(items, now), now)
}

@Composable
private fun ReminderWidgetContent(context: Context, shown: List<WidgetTimeRules.Shown>) {
    // 出图：4×2 行距 9、拉高后行距 11；一行按 18dp 高（17 的圈 / 13.5sp 的字）。
    val height = LocalSize.current.height.value
    val tight = TaskNotifyRules.reminderSlots(height, PAD_V * 2, HEADER, ROW + 9f)
    val gap = if (tight <= TaskNotifyRules.REMINDER_MIN_SLOTS) 9 else 11
    val slots = TaskNotifyRules.reminderSlots(height, PAD_V * 2, HEADER, ROW + gap)
    val (n, more) = TaskNotifyRules.reminderRows(shown.size, slots)
    Column(GlanceModifier.widgetCard().padding(horizontal = 16.dp, vertical = PAD_V.dp).clickable(deepLinkAction(context, "umbra://reminders"))) {
        WHeader("提醒 · 今天", trailing = if (shown.isNotEmpty()) "${shown.size} 条" else null)
        if (shown.isEmpty()) {
            Spacer(GlanceModifier.defaultWeight())
            Text("今天没有到点的提醒。", style = TextStyle(color = WTheme.muted, fontSize = 12.5.sp))
            Spacer(GlanceModifier.defaultWeight())
        } else {
            shown.take(n).forEach { r ->
                Spacer(GlanceModifier.height(gap.dp))
                ReminderRow(r)
            }
            if (more > 0) {
                Spacer(GlanceModifier.height(gap.dp))
                // 「还有 N 条」：与正文对齐（左边空出圈的位置），12 faint。
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(GlanceModifier.width(26.dp))
                    Text("还有 $more 条", style = TextStyle(color = WTheme.faint, fontSize = 12.sp), maxLines = 1)
                }
            }
            Spacer(GlanceModifier.defaultWeight())
        }
    }
}

/** 上下内边距（出图 14）。 */
private const val PAD_V = 14f
/** 顶行高（16 的字标）。 */
private const val HEADER = 16f
/** 一行的高度。 */
private const val ROW = 18f

@Composable
private fun ReminderRow(r: WidgetTimeRules.Shown) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // 勾选圈只作展示（iOS 1.6pt 描边圆，过期转 danger）。
        Image(
            ImageProvider(R.drawable.ic_widget_ring), contentDescription = null,
            modifier = GlanceModifier.size(17.dp), colorFilter = ColorFilter.tint(if (r.overdue) WTheme.danger else WTheme.faint),
        )
        Spacer(GlanceModifier.width(9.dp))
        Text(r.text, style = TextStyle(color = WTheme.text, fontSize = 13.5.sp), maxLines = 1, modifier = GlanceModifier.defaultWeight())
        Spacer(GlanceModifier.width(6.dp))
        Text(
            if (r.overdue) "${r.time} 已过期" else r.time,
            style = TextStyle(color = if (r.overdue) WTheme.dangerText else WTheme.faint, fontSize = 11.5.sp),
        )
    }
}
