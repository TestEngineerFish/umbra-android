// 小号桌面小组件 · 任务（iOS: UmbraWidgets/UmbraTaskWidget.swift，规范增补 4.3）。
//
// 有执行中任务：百分比 + 进度条 + 任务名 + 状态行，点击直达任务详情；
// 没有：显示今天完成数（也是真实数据，不摆假任务）。快照超过 10 分钟没更新要显示「更新于」。
// iOS 是进度环；Glance 画不了描边弧，换成 LinearProgressIndicator（同色、同口径：没有步骤数时不编进度，画不定进度）。
package com.geek.umbra.feature.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import android.appwidget.AppWidgetManager
import com.geek.umbra.app.AppGraph

/** 任务小组件。数据只读 WidgetBridge 的任务快照（内存 + filesDir/widget/tasks.json）。 */
class TaskWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        WidgetBridge.ensureObserving()
        provideContent {
            // tick：到点 / 零点 / 刷新时由 WidgetBridge 拨一下，正在跑的会话就地重算（「更新于」「今天完成」按这一帧的时刻）。
            val tick by WidgetBridge.tick.collectAsState()
            val now = maxOf(tick, System.currentTimeMillis())
            val snap by WidgetBridge.taskSnapshot.collectAsState()
            // 未登录：画「请登录 Umbra」占位，**不读快照**（iOS TaskProvider.current 同）。登出时快照也已删掉。
            val signedOut by AppGraph.guard.locked.collectAsState()
            TaskWidgetContent(context, snap, signedOut, now)
        }
    }
}

/** 系统那一头的接收器（manifest 里登记）。添加 / 系统周期刷新时顺手排下一次到点刷新。 */
class TaskWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = TaskWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WidgetRefresh.schedule(context)
    }
}

@Composable
private fun TaskWidgetContent(context: Context, snap: TaskWidgetSnapshot?, signedOut: Boolean, now: Long) {
    val card = GlanceModifier.widgetCard().padding(14.dp)
    when {
        signedOut -> WSignedOut(card.clickable(deepLinkAction(context, "umbra://tasks")))
        snap == null -> Column(card.clickable(deepLinkAction(context, "umbra://tasks"))) {
            // 还没有任何快照（还没拉到过任务列表）：说清楚，不摆假数据。
            WHeader("任务")
            Spacer(GlanceModifier.defaultWeight())
            Text("打开 App 的任务页后，这里会显示执行中任务的进度。", style = TextStyle(color = WTheme.muted, fontSize = 12.sp))
        }
        snap.runningTitle != null -> Column(card.clickable(deepLinkAction(context, "umbra://task/${snap.runningId ?: ""}"))) {
            WHeader("任务")
            Spacer(GlanceModifier.defaultWeight())
            Text(
                WidgetTaskRules.percentText(snap.stepsDone, snap.stepsTotal),
                style = TextStyle(color = WTheme.orangeText, fontSize = 22.sp, fontWeight = FontWeight.Bold),
            )
            Spacer(GlanceModifier.height(6.dp))
            val p = snap.percent
            if (p != null) {
                LinearProgressIndicator(p.toFloat(), GlanceModifier.fillMaxWidth().height(6.dp), color = WTheme.orange, backgroundColor = WTheme.chip)
            } else {
                // 服务端没给步骤数：不编百分比、不画一段假进度（iOS 画 30% 弧当「进行中」示意，这里用不定进度表达同一件事）。
                LinearProgressIndicator(GlanceModifier.fillMaxWidth().height(6.dp), color = WTheme.orange, backgroundColor = WTheme.chip)
            }
            Spacer(GlanceModifier.height(8.dp))
            Text(snap.runningTitle, style = TextStyle(color = WTheme.text, fontSize = 12.sp, fontWeight = FontWeight.Medium), maxLines = 1)
            snap.statusLine?.let { Text(it, style = TextStyle(color = WTheme.muted, fontSize = 11.sp), maxLines = 1) }
            StaleLine(snap, now)
        }
        else -> Column(card.clickable(deepLinkAction(context, "umbra://tasks"))) {
            WHeader("任务")
            Spacer(GlanceModifier.defaultWeight())
            // 快照不是今天写的就是 0（umbra-ios#85）：那个数是写入那一天的。
            Text("${WidgetTimeRules.todayDone(snap, now)}", style = TextStyle(color = WTheme.text, fontSize = 34.sp, fontWeight = FontWeight.Bold))
            Text("今天完成", style = TextStyle(color = WTheme.muted, fontSize = 12.sp))
            StaleLine(snap, now)
        }
    }
}

/** 快照超过 10 分钟没更新：亮出「更新于」。过期数据必须自己承认过期；跨日带「昨天」/ 日期。 */
@Composable
private fun StaleLine(s: TaskWidgetSnapshot, now: Long) {
    val t = WidgetTimeRules.staleText(s.savedAtMs, now) ?: return
    Spacer(GlanceModifier.height(2.dp))
    Text(t, style = TextStyle(color = WTheme.faint, fontSize = 10.sp))
}
