// 小号桌面小组件 · 任务（iOS: UmbraWidgets/UmbraTaskWidget.swift，规范增补 4.3）。
//
// 有执行中任务：进度环 + 任务名，点击直达任务详情；没有：显示今天完成数（也是真实数据，不摆假任务）。
// 快照超过 10 分钟没更新，右上角显示「更新于 HH:mm」。
//
// 尺寸（设计侧第 199 封答复第 10 条，出图 #10）：最小 = 默认 2×2，排版同 iOS 小号（顶行字标 + 「任务」/ 中间环 62 / 底行任务名）；
// 可拉宽到 4×2：环 76 放左，右侧补任务名、「第 3/8 步 · 当前步」与「已用 1分12秒」。不往高拉。SizeMode.Responsive 两档。
// Glance 画不了描边弧：底圈用矢量（tint chip，跟着系统深浅色），弧用 Bitmap 画（橙色跨主题不变），百分比用 Glance 文字叠在中间。
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
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Column
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.layout.Alignment
import androidx.glance.ColorFilter
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.SizeMode
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.graphics.toArgb
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.geek.umbra.R
import com.geek.umbra.designsystem.UmbraLight
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
    /** 两档：2×2 与拉宽后的 4×2（宽度够 WIDE 就用宽排版）。 */
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE))

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

/** 2×2 的最小尺寸（与 widget_task_info.xml 的 minWidth / minHeight 一致）。 */
private val SMALL = DpSize(110.dp, 110.dp)
/** 拉宽到 4×2 的那一档（与今日提醒的 4×2 同宽）。 */
private val WIDE = DpSize(250.dp, 110.dp)

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
    val wide = LocalSize.current.width >= WIDE.width
    when {
        signedOut -> WSignedOut(card.clickable(deepLinkAction(context, "umbra://tasks")))
        snap == null -> Column(card.clickable(deepLinkAction(context, "umbra://tasks"))) {
            // 还没有任何快照（还没拉到过任务列表）：说清楚，不摆假数据。
            WHeader("任务")
            Spacer(GlanceModifier.defaultWeight())
            Text("打开 App 的任务页后，这里会显示执行中任务的进度。", style = TextStyle(color = WTheme.muted, fontSize = 12.sp))
        }
        snap.runningTitle != null -> {
            val open = deepLinkAction(context, "umbra://task/${snap.runningId ?: ""}")
            val stale = WidgetTimeRules.staleText(snap.savedAtMs, now)
            if (wide) Row(card.clickable(open), verticalAlignment = Alignment.CenterVertically) {
                // 4×2：环 76（描边 6）放左，右侧四行（出图 #10）。
                Ring(snap, 76.dp, 6, 15.sp)
                Spacer(GlanceModifier.width(16.dp))
                Column(GlanceModifier.defaultWeight()) {
                    WHeader("任务", trailing = stale)
                    Spacer(GlanceModifier.height(5.dp))
                    Text(snap.runningTitle, style = TextStyle(color = WTheme.text, fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                    Spacer(GlanceModifier.height(5.dp))
                    // 两行（批次 290 §六）：「已完成 2/8 步」一行，「正在做第 3 步 · 步名」另起一行。
                    for (line in TaskNotifyRules.runningBody(snap.stepsDone, snap.stepsTotal, snap.stepTitle).lines()) {
                        Text(line, style = TextStyle(color = WTheme.muted, fontSize = 12.sp), maxLines = 1)
                    }
                    snap.startedAtMs?.let {
                        Spacer(GlanceModifier.height(5.dp))
                        Text("已用 ${TaskNotifyRules.elapsedText(now - it)}", style = TextStyle(color = WTheme.faint, fontSize = 11.5.sp), maxLines = 1)
                    }
                }
            } else Column(card.clickable(open)) {
                // 2×2：顶行字标 + 任务 / 中间环 62（描边 7）/ 底行任务名 12/560 单行省略（同 iOS 小号）。
                WHeader("任务", trailing = stale)
                Spacer(GlanceModifier.defaultWeight())
                Box(GlanceModifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Ring(snap, 62.dp, 7, 14.sp) }
                Spacer(GlanceModifier.defaultWeight())
                Text(snap.runningTitle, style = TextStyle(color = WTheme.text, fontSize = 12.sp, fontWeight = FontWeight.Medium), maxLines = 1)
            }
        }
        else -> Column(card.clickable(deepLinkAction(context, "umbra://tasks"))) {
            WHeader("任务", trailing = WidgetTimeRules.staleText(snap.savedAtMs, now))
            Spacer(GlanceModifier.defaultWeight())
            // 快照不是今天写的就是 0（umbra-ios#85）：那个数是写入那一天的。
            Text("${WidgetTimeRules.todayDone(snap, now)}", style = TextStyle(color = WTheme.text, fontSize = 34.sp, fontWeight = FontWeight.Bold))
            Text("今天完成", style = TextStyle(color = WTheme.muted, fontSize = 12.sp))
        }
    }
}

/**
 * 进度环：底圈矢量（tint chip）+ 橙色弧 Bitmap + 中间百分比（650 → Bold、orangeText）。
 * 服务端没给步骤数时不编百分比（「…」），弧画 30% 当「进行中」示意（iOS UmbraTaskWidget 同）。
 * stroke 是 62 单位 viewBox 里的描边（出图：2×2 是 7，4×2 是 6，都按 viewBox 62 缩放到实际边长）。
 */
@Composable
private fun Ring(snap: TaskWidgetSnapshot, side: Dp, stroke: Int, textSize: TextUnit) {
    val ctx = LocalContext.current
    val fraction = (snap.percent ?: 0.3).toFloat().coerceIn(0f, 1f)
    val track = if (stroke == 7) R.drawable.ic_widget_progress_track_7 else R.drawable.ic_widget_progress_track_6
    Box(GlanceModifier.size(side), contentAlignment = Alignment.Center) {
        Image(ImageProvider(track), contentDescription = null, modifier = GlanceModifier.size(side), colorFilter = ColorFilter.tint(WTheme.chip))
        if (fraction > 0f) {
            Image(ImageProvider(ringArc(ctx, side.value, stroke.toFloat(), fraction)), contentDescription = null, modifier = GlanceModifier.size(side))
        }
        Text(
            WidgetTaskRules.percentText(snap.stepsDone, snap.stepsTotal),
            style = TextStyle(color = WTheme.orangeText, fontSize = textSize, fontWeight = FontWeight.Bold),
        )
    }
}

/** 画橙色弧：12 点钟方向起顺时针、圆头。按设备像素画，避免放大发虚。 */
private fun ringArc(ctx: Context, sideDp: Float, strokeUnits: Float, fraction: Float): Bitmap {
    val density = ctx.resources.displayMetrics.density
    val px = (sideDp * density).toInt().coerceAtLeast(1)
    val unit = px / 62f
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeUnits * unit
        strokeCap = Paint.Cap.ROUND
        color = UmbraLight.orange.toArgb()
    }
    val r = 26f * unit
    val c = px / 2f
    Canvas(bmp).drawArc(RectF(c - r, c - r, c + r, c + r), -90f, 360f * fraction, false, paint)
    return bmp
}
