package com.geek.umbra.feature.tool

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.geek.umbra.app.Brand
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraScreen
import com.geek.umbra.designsystem.UmbraTheme

/**
 * 小组件与快捷方式说明页 —— iOS 是「小组件与轻点背面」（WidgetsGuideView.swift），一整屏说明，不是设置页：
 * 小组件的添加入口在系统侧，App 只能把路指清楚，一步都不能替用户点。
 * 页名、分组、步骤文案按设计侧第 199 封答复第 11 条（出图「Android 端/Pages/平台差异.dc.html」#11）。
 * ⛔ 轻点背面 Android 没有系统 API，那一组换成「长按图标记一笔」（桌面快捷方式，res/xml/shortcuts.xml）。
 */
@Composable
fun ToolWidgetsGuide() {
    UmbraScreen(
        title = "小组件与快捷方式",
        onBack = { AppGraph.router.back() },
        contentPadding = PaddingValues(start = UmbraDimens.pagePadX, end = UmbraDimens.pagePadX, top = 14.dp, bottom = 30.dp),
        spacing = 18.dp,
    ) {
        item("intro") {
            // 导语沿用 iOS 原句（WidgetsGuideView.swift:18 那组的 intro，没有 iOS 专有的说法）。
            Text(
                "两个小组件跟着任务和提醒的事件刷新，点一下直达对应页面；数据超过 10 分钟没更新会显示「更新于」时间。",
                color = UmbraTheme.colors.muted, style = TextStyle(fontSize = 12.5.sp, lineHeight = 21.25.sp),
            )
        }
        item("widgets") {
            GuideGroup("两个小组件") {
                GuideCard {
                    // 说明文字同 iOS（WidgetsGuideView.swift:19-23），尺寸名换成 Android 的「2×2，可拉宽」「4×2，可拉高」。
                    WidgetItem("2×2，可拉宽 · 执行中任务", "进度环 + 任务名；没有执行中任务时显示今天完成数。点击直达任务详情。") { TaskWidgetPreview() }
                    GuideDivider()
                    WidgetItem("4×2，可拉高 · 今天的提醒", "今天到点和已过期的提醒。圆圈只作展示，勾选要进 App —— 小组件不做写操作。") { ReminderWidgetPreview() }
                }
            }
        }
        item("add") {
            GuideGroup("怎么添加") {
                Steps(
                    listOf(
                        "长按桌面空白处，点「小组件」。有的手机叫「小部件」或「卡片」",
                        "找到 ${Brand.productName}，挑「任务」或「今日提醒」，拖到想放的位置",
                        "放好后长按小组件，拖边框可以调大小",
                    ),
                )
            }
        }
        item("shortcuts") {
            GuideGroup("长按图标记一笔") {
                Steps(
                    listOf(
                        "长按桌面上的 ${Brand.productName} 图标，弹出「记一笔」「记灵感」，点一下直接进对应页面",
                        "按住其中一项拖到桌面，会变成一个单独的图标，以后一点就到",
                    ),
                )
                Text(
                    "直接落在记一笔页，不用先过主屏。",
                    color = UmbraTheme.colors.faint, style = TextStyle(fontSize = 12.sp, lineHeight = 20.4.sp),
                )
            }
        }
    }
}

/** 一组：小标题（11.5/600、字距 .08em、faint）+ 内容，间距 10。 */
@Composable
private fun GuideGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            title, color = UmbraTheme.colors.faint,
            style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.08.em),
        )
        content()
    }
}

/** 内容卡：card 底、圆角 18、1px borderSoft。 */
@Composable
private fun GuideCard(content: @Composable () -> Unit) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(UmbraDimens.radiusCard)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.card).border(1.dp, c.borderSoft, shape)) { content() }
}

/** 行间分隔：1px borderSoft。 */
@Composable
private fun GuideDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(UmbraTheme.colors.borderSoft))
}

/** 步骤卡：编号圆 20（chip 底、11/600）+ 正文 13.5/1.65，一步一行、行间分隔。系统菜单的名字照原文写，用户能逐字对上。 */
@Composable
private fun Steps(lines: List<String>) {
    val c = UmbraTheme.colors
    GuideCard {
        lines.forEachIndexed { i, line ->
            if (i > 0) GuideDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                Box(Modifier.padding(top = 1.dp).size(20.dp).clip(CircleShape).background(c.chip), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", color = c.text, style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold))
                }
                Text(line, color = c.text, style = TextStyle(fontSize = 13.5.sp, lineHeight = 22.3.sp), modifier = Modifier.weight(1f))
            }
        }
    }
}

/** 一个小组件的介绍：预览（bg 底上摆一块小组件）+ 尺寸名 + 说明。纯展示，不可点。 */
@Composable
private fun WidgetItem(name: String, desc: String, preview: @Composable () -> Unit) {
    val c = UmbraTheme.colors
    Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.bg).padding(14.dp),
            contentAlignment = Alignment.Center,
        ) { preview() }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(name, color = c.text, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight(560)))
            Text(desc, color = c.faint, style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.75.sp))
        }
    }
}

// MARK: 静态预览 —— 与桌面上那两块（feature/widget/TaskWidget.kt 2×2、ReminderWidget.kt 4×2）同一套排版，数据是出图 #10 的示意。

/** 预览块的外框：card 底、圆角 22（桌面上由系统裁，这里只是示意）、内边距 14。 */
@Composable
private fun WidgetFrame(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(22.dp)).background(UmbraTheme.colors.card).padding(14.dp)) { content() }
}

/** 字标 + 名字那一行。 */
@Composable
private fun PreviewHeader(label: String, trailing: String? = null) {
    val c = UmbraTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(16.dp).clip(RoundedCornerShape(5.dp)).background(c.orange), contentAlignment = Alignment.Center) {
            Text("U", color = c.onAccent, style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight(650)))
        }
        Spacer(Modifier.width(6.dp))
        Text(label, color = c.faint, style = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.06.em), modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, color = c.faint, style = TextStyle(fontSize = 10.5.sp))
    }
}

/** 任务 2×2：顶行 / 进度环 62（描边 7）/ 任务名。 */
@Composable
private fun TaskWidgetPreview() {
    val c = UmbraTheme.colors
    WidgetFrame(Modifier.size(150.dp)) {
        Column(Modifier.size(122.dp), verticalArrangement = Arrangement.SpaceBetween) {
            PreviewHeader("任务")
            Box(Modifier.size(62.dp).align(Alignment.CenterHorizontally), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(62.dp)) {
                    val unit = size.minDimension / 62f
                    val sw = 7f * unit
                    val r = 26f * unit
                    val tl = Offset(center.x - r, center.y - r)
                    drawCircle(c.chip, radius = r, style = Stroke(sw))
                    drawArc(c.orange, -90f, 360f * 0.38f, false, tl, Size(r * 2, r * 2), style = Stroke(sw, cap = StrokeCap.Round))
                }
                Text("38%", color = c.orangeText, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight(650)))
            }
            Text("竞品价格表整理", color = c.text, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight(560)), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 今日提醒 4×2：顶行 + 2 行 +「还有 N 条」。 */
@Composable
private fun ReminderWidgetPreview() {
    val c = UmbraTheme.colors
    WidgetFrame(Modifier.fillMaxWidth().height(150.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            PreviewHeader("提醒 · 今天", "5 条")
            PreviewRow("给房东转房租", "09:00 已过期", overdue = true)
            PreviewRow("交周报", "18:00", overdue = false)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(26.dp))
                Text("还有 3 条", color = c.faint, style = TextStyle(fontSize = 12.sp))
            }
        }
    }
}

/** 一行提醒：17 的圈（过期 danger 描边 1.6，否则 faint）+ 正文 13.5 + 时间 11.5。 */
@Composable
private fun PreviewRow(text: String, time: String, overdue: Boolean) {
    val c = UmbraTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(17.dp).border(1.6.dp, if (overdue) c.danger else c.faint, CircleShape))
        Spacer(Modifier.width(9.dp))
        Text(text, color = c.text, style = TextStyle(fontSize = 13.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(time, color = if (overdue) c.dangerText else c.faint, style = TextStyle(fontSize = 11.5.sp))
    }
}
