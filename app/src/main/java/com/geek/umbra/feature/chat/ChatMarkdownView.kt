// 聊天里的 Markdown 画法（iOS: Features/Chat/UmbraMarkdown.swift 的视图部分：UmbraMarkdownText / MarkdownTableView / ChatBubbleBodyLayout）。
//
// 块级拆分在 MarkdownBlocks.kt，行内解析与链接闸在 ChatInline / ChatLinkRules（都是纯判据、有用例）。
// 这里只管把块画出来。取值同稿：正文 15.5、代码 12.5 等宽行高 1.65（chip 底 + borderSoft 描边 + 圆角 9 + 内边距 10/12）、
// 表格格内 14 行高 1.5、内边距 5/10、列宽 [72,220]、表头 track 底。
package com.geek.umbra.feature.chat

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraType

private const val CODE_SIZE = 12.5f

/** 把行内片段拼成 AnnotatedString。链接挂 LinkAnnotation，点了**再判一次**（兜底闸）才交给系统。 */
@Composable
fun inlineAnnotated(runs: List<InlineRun>, linkColor: Color): AnnotatedString = buildAnnotatedString {
    for (r in runs) {
        val style = SpanStyle(
            fontWeight = if (r.bold) FontWeight.SemiBold else null,
            fontStyle = if (r.italic) FontStyle.Italic else null,
            textDecoration = if (r.strike) TextDecoration.LineThrough else null,
            fontFamily = if (r.code) FontFamily.Monospace else null,
            fontSize = if (r.code) CODE_SIZE.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
        )
        val link = r.link
        if (link != null) {
            withLink(LinkAnnotation.Url(link, TextLinkStyles(SpanStyle(color = linkColor))) { openSafeLink(link) }) {
                withStyle(style) { append(r.text) }
            }
        } else withStyle(style) { append(r.text) }
    }
}

/** 兜底闸：不合格的链接点了什么都不发生（不交给系统去拨号 / 拉起别的 App）。 */
fun openSafeLink(url: String) {
    if (!ChatLinkRules.opens(url)) return
    runCatching {
        AppGraph.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** 一个表的各列理想宽（含内边距，dp）。用文字测量器量单行宽。 */
@Composable
private fun rememberIdealColumns(t: MarkdownTable): List<Float> {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(t) {
        (0 until t.columnCount).map { c ->
            val cells = listOf(t.header.getOrElse(c) { "" }) + t.rows.map { it.getOrElse(c) { "" } }
            cells.maxOf { raw ->
                val text = ChatInline.parse(raw).joinToString("") { it.text }
                val px = measurer.measure(text, TextStyle(fontSize = ChatTableRules.CELL_SIZE.sp, fontWeight = FontWeight.SemiBold), maxLines = 1).size.width
                with(density) { px.toDp().value } + 2 * ChatTableRules.PAD_H
            }
        }
    }
}

/**
 * 气泡里的 Markdown 正文。maxWidth：正文栏平时能用的最宽；tableRelease：带表的消息让出来的对侧留白
 * （表放不下时气泡先撑到屏宽 − 32，见 ChatTableRules.bodyWidth）。
 */
@Composable
fun ChatMarkdownText(raw: String, maxWidth: Dp, tableRelease: Dp = 0.dp, color: Color = UmbraTheme.colors.text, size: Float = 15.5f) {
    val c = UmbraTheme.colors
    val blocks = remember(raw) { ChatMarkdown.parse(raw) }
    val tables = blocks.filterIsInstance<MdBlock.Table>()
    // 表的自然宽决定气泡用掉多少让出来的留白（短表不把气泡撑大）
    val naturals = tables.map { t -> ChatTableRules.naturalWidth(ChatTableRules.columnWidths(rememberIdealColumns(t.table), Float.POSITIVE_INFINITY), 1f) }
    val bodyW = if (tableRelease > 0.dp && naturals.isNotEmpty())
        ChatTableRules.bodyWidth(maxWidth.value + tableRelease.value, tableRelease.value, naturals.max()).dp
    else maxWidth
    val bodyStyle = TextStyle(fontSize = size.sp, lineHeight = (size * 1.6f).sp, color = color)
    Column(Modifier.widthIn(max = bodyW).then(if (tables.isNotEmpty()) Modifier.width(bodyW) else Modifier), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        for (b in blocks) when (b) {
            is MdBlock.Paragraph -> Text(inlineAnnotated(ChatInline.parse(b.text), c.orangeText), style = bodyStyle)
            is MdBlock.Heading -> Text(
                inlineAnnotated(ChatInline.parse(b.text), c.orangeText),
                style = bodyStyle.copy(fontSize = (size + maxOf(0, 4 - b.level) * 1.5f).sp, fontWeight = UmbraType.weight560, lineHeight = (size * 1.45f + maxOf(0, 4 - b.level) * 1.5f).sp),
                modifier = Modifier.padding(top = 2.dp),
            )
            is MdBlock.ListItem -> Row(Modifier.padding(start = (b.indent * 14).dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // 定宽：序号长短不一时正文起点仍然对齐（1. 和 10. 不能错位）
                Text(b.marker, style = bodyStyle.copy(color = c.muted), textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 16.dp))
                Text(inlineAnnotated(ChatInline.parse(b.text), c.orangeText), style = bodyStyle.copy(lineHeight = (size * 1.5f).sp))
            }
            is MdBlock.Code -> Text(
                b.text,
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = CODE_SIZE.sp, lineHeight = (CODE_SIZE * 1.65f).sp, color = color),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(UmbraDimens.radiusCodeBlock)).background(c.chip)
                    .border(UmbraDimens.borderW, c.borderSoft, RoundedCornerShape(UmbraDimens.radiusCodeBlock)).padding(horizontal = 12.dp, vertical = 10.dp),
            )
            is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.width(2.dp).fillMaxHeight().background(c.border))
                Text(inlineAnnotated(ChatInline.parse(b.text), c.orangeText), style = bodyStyle.copy(color = c.muted, lineHeight = (size * 1.5f).sp))
            }
            MdBlock.Rule -> Box(Modifier.fillMaxWidth().height(UmbraDimens.borderW).background(c.borderSoft))
            is MdBlock.Table -> MarkdownTableView(b.table, bodyW)
        }
    }
}

/**
 * 气泡里的一张表（iOS MarkdownTableView + ChatTableLayout）。宽表在外框里横滑、外框不动；
 * 格线行线都是 borderSoft，表头 track 底、600；数字列等宽数字（按列内容判）。单元格一律经 ChatInline（链接闸的源头）。
 */
@Composable
fun MarkdownTableView(t: MarkdownTable, available: Dp) {
    val c = UmbraTheme.colors
    val ideal = rememberIdealColumns(t)
    // 留给格子的宽 = 外框宽 − 两侧外框 − 列间竖线
    val room = available.value - (t.columnCount + 1) * 1f
    val widths = ChatTableRules.columnWidths(ideal, room)
    val numeric = remember(t) { (0 until t.columnCount).filter { ChatTableRules.isNumericColumn(t, it) }.toSet() }
    val shape = RoundedCornerShape(UmbraDimens.radiusCodeBlock)
    Box(Modifier.widthIn(max = available).clip(shape).border(UmbraDimens.borderW, c.borderSoft, shape)) {
        Column(Modifier.horizontalScroll(rememberScrollState()).padding(UmbraDimens.borderW)) {
            val all = listOf(t.header) + t.rows
            all.forEachIndexed { r, row ->
                if (r > 0) Box(Modifier.width(widths.sum().dp + (t.columnCount - 1).dp).height(UmbraDimens.borderW).background(c.borderSoft))
                Row(Modifier.height(IntrinsicSize.Min).then(if (r == 0) Modifier.background(c.track) else Modifier)) {
                    for (col in 0 until t.columnCount) {
                        if (col > 0) Box(Modifier.width(UmbraDimens.borderW).fillMaxHeight().background(c.borderSoft))
                        val a = t.align(col)
                        val raw = row.getOrElse(col) { "" }
                        Box(
                            Modifier.width(widths[col].dp).padding(horizontal = ChatTableRules.PAD_H.dp, vertical = ChatTableRules.PAD_V.dp),
                            contentAlignment = when (a) {
                                MarkdownTable.Align.Center -> Alignment.TopCenter
                                MarkdownTable.Align.Trailing -> Alignment.TopEnd
                                MarkdownTable.Align.Leading -> Alignment.TopStart
                            },
                        ) {
                            Text(
                                inlineAnnotated(ChatInline.parse(raw), c.orangeText),
                                style = TextStyle(
                                    fontSize = ChatTableRules.CELL_SIZE.sp, lineHeight = (ChatTableRules.CELL_SIZE * ChatTableRules.LINE_HEIGHT).sp,
                                    fontWeight = if (r == 0) FontWeight.SemiBold else FontWeight.Normal, color = c.text,
                                    // 数字列挂等宽数字（tabular-nums），个位对个位
                                    fontFeatureSettings = if (col in numeric) "tnum" else null,
                                    textAlign = when (a) {
                                        MarkdownTable.Align.Center -> TextAlign.Center
                                        MarkdownTable.Align.Trailing -> TextAlign.End
                                        MarkdownTable.Align.Leading -> TextAlign.Start
                                    },
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}
