// 秘书能力的权限确认卡在聊天流里的样子（设计侧《权限确认卡 · 分区稿》E / F / F2 / F3 手机形制，C 回执行，L 活动行，I 说明卡）。
// 数据与判据在 CapCard.kt；这里只画。预览一律只读展示：不执行、不解析成可点链接（单子红线）。
package com.geek.umbra.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraButton
import com.geek.umbra.designsystem.UmbraButtonKind
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.navigation.UmbraSheet
import kotlinx.coroutines.delay

private val capShape = RoundedCornerShape(UmbraDimens.radiusCard)
private val rowShape = RoundedCornerShape(8.dp)

/** 一张能力卡：未定的画完整卡，定了的收成 38 高回执行（点开回看当时的预览）。 */
@Composable
fun CapCardBlock(b: ChatBlock.Cap) {
    // 到期要自己收（服务端的清扫推来之前，geek-7f 答复第 6 问）：未定的卡挂一个计时，到了截止 + 宽限就重画。
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val exp = b.card.expiresAtMs
    if (exp != null && CapCardRules.isCard(b.card)) LaunchedEffect(b.card.callId, exp) {
        val wait = exp + CapBlockRules.EXPIRY_GRACE_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait + 500)
        now = System.currentTimeMillis()
    }
    val card = CapBlockRules.effective(b.card, now)
    if (b.supersededBy == null && CapCardRules.isCard(card)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CapCardRules.turnHead(b.turnOpen)?.let { Text(it, color = UmbraTheme.colors.muted, style = TextStyle(fontSize = 12.5.sp)) }
            CapFullCard(b, card)
        }
    } else CapReceiptRow(b, card)
}

@Composable
private fun CapFullCard(b: ChatBlock.Cap, c: CapCard) {
    val t = UmbraTheme.colors
    Column(Modifier.fillMaxWidth().clip(capShape).background(t.card).border(UmbraDimens.borderW, t.border, capShape)) {
        // 头：22 图标块 + 能力名 + 右端胶囊（执行端是别的电脑时卡头不写「在哪」，改在标题下面那一条）
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(t.track), contentAlignment = Alignment.Center) {
                UmbraIcon(UmbraIconPaths.shield, t.muted, size = 13.dp, strokeWidth = 2f)
            }
            Spacer(Modifier.width(8.dp))
            Text(CapCardRules.headName(c), color = t.muted, style = TextStyle(fontSize = 12.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (CapCardRules.isOpen(c)) {
                Spacer(Modifier.width(8.dp))
                Text(CapCardRules.askPill(c), color = t.warningText, style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.clip(CircleShape).background(t.warningSoft).padding(horizontal = 8.dp, vertical = 2.dp))
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (c.title.isNotBlank()) Text(c.title, color = t.text, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp))
            CapCardRules.runOnLine(c)?.let { (text, offline) ->
                Row(Modifier.fillMaxWidth().clip(rowShape).background(if (offline) t.warningSoft else t.chip).padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Top) {
                    UmbraIcon(UmbraIconPaths.monitor, if (offline) t.warningText else t.muted, Modifier.padding(top = 2.dp), size = 14.dp, strokeWidth = 2f)
                    Spacer(Modifier.width(7.dp))
                    Text(text, color = if (offline) t.warningText else t.text, style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp))
                }
            }
            CapCardRules.pendingLine(c)?.let {
                Text(it, color = t.warningText, style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp),
                    modifier = Modifier.fillMaxWidth().clip(rowShape).background(t.warningSoft).padding(horizontal = 10.dp, vertical = 8.dp))
            }
            // 派活时电脑不在线（waiting_device）：只有外框、标题和执行端那一行，没有预览、没有按钮（设计侧 324 §6b）。
            if (c.phase != "waiting_device") {
                for (w in CapCardRules.warnings(c)) CapWarningRow(w, c)
                for (p in c.preview) CapPreviewView(p)
                if (c.previewTruncated) Text("预览太长，后面几段没带过来。", color = t.faint, style = TextStyle(fontSize = 12.sp))
            }
            if (CapCardRules.isOpen(c)) {
                CapScope(b, c)
                CapButtons(b, c)
            }
            CapCardRules.footnote(c)?.let { Text(it, color = t.faint, style = TextStyle(fontSize = 12.sp, lineHeight = 18.sp)) }
        }
    }
}

/** 一种警告一行：找不回 / 花钱 danger-soft，外来内容 / 执行代码 warning-soft。外来内容下面列没信任的域名，每个给「以后信任它」。 */
@Composable
private fun CapWarningRow(w: CapWarning, c: CapCard) {
    val t = UmbraTheme.colors
    val text = CapCardRules.warningText(w, c) ?: return
    val danger = CapCardRules.isDanger(w.kind)
    val ink = if (danger) t.dangerText else t.warningText
    Column(Modifier.fillMaxWidth().clip(rowShape).background(if (danger) t.dangerSoft else t.warningSoft).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            // 每种警告前一颗 13 的图标（iOS CapCardViews warnIcon）；要花钱用 coins（设计侧 328 c，iOS 从 wallet 换回来）。
            val (icon, sw) = when (w.kind) {
                "costs_money" -> com.geek.umbra.designsystem.UmbraExtraIcons.coins to 1.8f
                "runs_code" -> UmbraIconPaths.code to 2f
                "external_content" -> UmbraIconPaths.globe to 2f
                else -> UmbraIconPaths.alertTriangle to 2f
            }
            UmbraIcon(icon, ink, Modifier.padding(top = 2.dp), size = 13.dp, strokeWidth = sw)
            Spacer(Modifier.width(8.dp))
            Text(text, color = ink, style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp))
        }
        if (w.kind == "external_content" && c.trustOffers.isNotEmpty()) {
            for (d in c.trustOffers) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d, color = t.text, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("以后信任它", color = t.orangeText, style = TextStyle(fontSize = 12.5.sp),
                    modifier = Modifier.heightIn(min = 32.dp).clickable(role = Role.Button) { ChatStore.trustSite(d, c.callId) }.padding(start = 10.dp, top = 6.dp, bottom = 6.dp))
            }
            Text("论坛、网盘、文档站这类谁都能发内容的网站，信任它就等于信任在那里写东西的每一个人。", color = ink, style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp))
        }
        // 没有域名的外来内容（文件、邮件…）逐行列，不给信任入口；和网站混着时也在同一块里、排在网站那几行后面（设计侧 326 c）。
        val t0 = c.taint
        if (w.kind == "external_content" && t0 != null && (t0.kinds.isNotEmpty() || t0.items.isNotEmpty())) {
            for (l in CapCardRules.taintLines(t0)) Text("· $l", color = ink, style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp))
        }
    }
}

/** 「总是允许」记的范围：两档以上单选（默认 0 = 最窄），一档只出一行。 */
@Composable
private fun CapScope(b: ChatBlock.Cap, c: CapCard) {
    val t = UmbraTheme.colors
    CapCardRules.scopeOneLine(c)?.let { Text(it, color = t.muted, style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp)); return }
    val title = CapCardRules.scopeTitle(c) ?: return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = t.faint, style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold))
        c.always.forEachIndexed { i, label ->
            val on = b.scopeIndex == i
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable(role = Role.RadioButton) { ChatStore.setCapScope(c.callId, i) },
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(16.dp).clip(CircleShape).border(1.5.dp, if (on) t.orange else t.border, CircleShape), contentAlignment = Alignment.Center) {
                    if (on) Box(Modifier.size(8.dp).clip(CircleShape).background(t.orange))
                }
                Spacer(Modifier.width(9.dp))
                Text(label, color = t.text, style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp))
            }
        }
        CapCardRules.bindNote(c)?.let { Text(it, color = t.faint, style = TextStyle(fontSize = 12.sp)) }
    }
}

/** 按钮一行三等分（稿 E）：不要（文字钮）· 总是允许 · 这一次（主）；高用本端按钮规格 48（点按下限，设计侧 324 §6d）。在发的时候整行不能再点。 */
@Composable
private fun CapButtons(b: ChatBlock.Cap, c: CapCard) {
    val t = UmbraTheme.colors
    val list = CapCardRules.buttons(c)
    if (list.isEmpty()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        for (k in list) when (k) {
            "reject" -> Box(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(10.dp))
                .clickable(enabled = !b.deciding, role = Role.Button) { ChatStore.decideCap(c.callId, "reject") }, contentAlignment = Alignment.Center) {
                Text(CapCardRules.buttonLabel(k), color = if (b.deciding) t.faint else t.muted, style = TextStyle(fontSize = 14.sp))
            }
            "always" -> UmbraButton(CapCardRules.buttonLabel(k), { ChatStore.decideCap(c.callId, "always") }, Modifier.weight(1f),
                kind = UmbraButtonKind.Secondary, height = 48.dp, enabled = !b.deciding)
            else -> UmbraButton(CapCardRules.buttonLabel(k), { ChatStore.decideCap(c.callId, "once") }, Modifier.weight(1f),
                height = 48.dp, enabled = !b.deciding)
        }
    }
}

// ── 预览（通用渲染器，按 kind 画；只读） ──────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CapPreviewView(p: CapPreview, full: Boolean = false) {
    val t = UmbraTheme.colors
    Column(Modifier.fillMaxWidth().clip(rowShape).background(t.bg).border(UmbraDimens.borderW, t.borderSoft, rowShape)) {
        val head = CapCardRules.segHead(p)
        val code = p as? CapPreview.Code
        val folds = code != null && !full && code.text.lines().size > CapCardRules.CODE_FOLD_LINES
        if (head.isNotEmpty() || folds) Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(head, color = t.faint, style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
            // 超过 6 行收起；「展开全文」进全屏只读页（306 §3：手机不就地展开）。没看全文也能点允许。
            if (folds) Text("展开全文", color = t.orangeText, style = TextStyle(fontSize = 12.sp),
                modifier = Modifier.clickable(role = Role.Button) { openFullCode(code!!) }.padding(start = 8.dp))
        }
        Box(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 9.dp, top = if (head.isEmpty()) 9.dp else 0.dp)) {
            when (p) {
                is CapPreview.Graph -> FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    p.nodes.forEachIndexed { i, (label, sub) ->
                        if (i > 0) Text("→", color = t.faint, style = TextStyle(fontSize = 12.sp), modifier = Modifier.align(Alignment.CenterVertically))
                        Text(label + (sub?.let { " $it" } ?: ""), color = t.text, style = TextStyle(fontSize = 12.sp),
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(t.card).border(UmbraDimens.borderW, t.borderSoft, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp))
                    }
                }
                is CapPreview.Code -> CodeLines(if (folds) p.text.lines().take(CapCardRules.CODE_FOLD_LINES) else p.text.lines())
                is CapPreview.Fields -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (cell in FieldDiffRules.cells(p.rows, p.diffs)) {
                        if (cell is FieldDiffRules.Cell.Diff) { androidx.compose.runtime.key("diff-${cell.diff.iOld}-${cell.diff.iNew}") { DiffCell(cell) }; continue }
                        val (k, v) = (cell as FieldDiffRules.Cell.Row).let { it.label to it.value }
                        Row {
                        Text(k, color = t.muted, style = TextStyle(fontSize = 12.5.sp), modifier = Modifier.width(84.dp))
                        // 值原样保留换行和行首空格（Compose 的 Text 本来不挤空白）。没看全文也能点允许。
                        if (CapCardRules.fieldFolds(v)) androidx.compose.runtime.key(k) { FoldedFieldValue(v, Modifier.weight(1f)) }
                        else Text(v, color = t.text, style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp), modifier = Modifier.weight(1f))
                        }
                    }
                }
                is CapPreview.Diff -> Column(Modifier.horizontalScroll(rememberScrollState())) {
                    for ((op, text) in p.lines) Text("$op $text", color = t.text, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp),
                        modifier = Modifier.background(when (op) { '-' -> t.dangerSoft; '+' -> t.successSoft; else -> Color.Transparent }).padding(horizontal = 4.dp))
                }
                is CapPreview.Items -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (it in p.items) Text(it, color = t.text, style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp))
                }
                is CapPreview.Plain -> Text(p.text, color = t.text, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp))
            }
        }
    }
}

/** 等宽、带行号；长行横向滚，不折行（脚本折行会误读）。 */
@Composable
private fun CodeLines(lines: List<String>) {
    val t = UmbraTheme.colors
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        Column { lines.forEachIndexed { i, _ -> Text("${i + 1}", color = t.faint, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp), textAlign = TextAlign.End, modifier = Modifier.width(22.dp)) } }
        Spacer(Modifier.width(8.dp))
        Column { for (l in lines) Text(l, color = t.text, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp), softWrap = false) }
    }
}

/** 全屏只读页（底部弹层撑满）：只看，不给任何动作。 */
private fun openFullCode(p: CapPreview.Code) {
    AppGraph.router.present(UmbraSheet("cap-code-${p.hashCode()}") { _ ->
        val t = UmbraTheme.colors
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(CapCardRules.segHead(p), color = t.muted, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
            Box(Modifier.fillMaxWidth().heightIn(max = 640.dp).clip(rowShape).background(t.bg).padding(10.dp).verticalScroll(rememberScrollState())) {
                CodeLines(p.text.lines())
            }
        }
    })
}

/**
 * 改长文本的一格（设计侧 344 三.2，形状 B，同 PC e21f57c）：头一行「{field} · 改了 N 处 · 第一处在第 X 行」+ 右侧「看原文 / 看改动」；
 * 正文按行画，删掉的 dangerSoft 底 + dangerText 字 + 删除线、加上的 successSoft 底 + success 字；没改的连续 2 行以上收成一条虚线
 * 「没改的 N 行 · 展开」。「看原文」原位换成原文全文（同样走长值折叠）。
 */
@Composable
private fun DiffCell(cell: FieldDiffRules.Cell.Diff) {
    val t = UmbraTheme.colors
    var original by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf(emptySet<Int>()) }
    val style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(FieldDiffRules.head(cell.label, cell.diff), color = t.muted, style = TextStyle(fontSize = 12.5.sp), modifier = Modifier.weight(1f))
            Text(if (original) FieldDiffRules.SHOW_CHANGES else FieldDiffRules.SHOW_ORIGINAL, color = t.orangeText, style = TextStyle(fontSize = 12.sp),
                modifier = Modifier.clickable(role = Role.Button) { original = !original }.padding(start = 8.dp))
        }
        if (original) {
            if (CapCardRules.fieldFolds(cell.original)) FoldedFieldValue(cell.original, Modifier.fillMaxWidth())
            else Text(cell.original, color = t.text, style = style)
            return@Column
        }
        val lines = remember(cell.diff) { FieldDiffRules.lines(cell.diff.hunks) }
        for (v in FieldDiffRules.views(lines, opened)) when (v) {
            is FieldDiffRules.View.Line -> Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    for (h in lines[v.index]) {
                        val st = when (h.op) {
                            "del" -> androidx.compose.ui.text.SpanStyle(color = t.dangerText, background = t.dangerSoft, textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
                            "ins" -> androidx.compose.ui.text.SpanStyle(color = t.success, background = t.successSoft)
                            else -> androidx.compose.ui.text.SpanStyle(color = t.text)
                        }
                        withStyle(st) { append(h.text) }
                    }
                },
                style = style,
            )
            is FieldDiffRules.View.Gap -> Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { opened = opened + v.from }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                Box(Modifier.weight(1f).height(1.dp).drawWithContent {
                    drawLine(t.border, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(size.width, 0f), strokeWidth = 1.dp.toPx(), pathEffect = dash)
                })
                Text(FieldDiffRules.gapText(v.count), color = t.faint, style = TextStyle(fontSize = 12.sp), modifier = Modifier.padding(horizontal = 8.dp))
                Box(Modifier.weight(1f).height(1.dp).drawWithContent {
                    drawLine(t.border, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(size.width, 0f), strokeWidth = 1.dp.toPx(), pathEffect = dash)
                })
            }
        }
    }
}

/**
 * fields 一格的长值（设计侧 344 三.1 定稿，服务端 #193 给全文）：折到 6 行、底部 2 行渐隐到预览底色，下面「展开全文」+ 灰字「共 N 字」；
 * 点了**就地展开**，最多屏高 45%（让卡底的［允许这一次］还在一屏之内），再长在格内滚动、右侧细滚动条，钮变「收起」。每格各自展开。
 * 代码段那套全屏只读页不动，只是 fields 的长值不再进全屏页。
 */
@Composable
private fun FoldedFieldValue(v: String, modifier: Modifier) {
    val t = UmbraTheme.colors
    var open by remember { mutableStateOf(false) }
    val style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp)
    Column(modifier) {
        if (!open) {
            Box {
                Text(v, color = t.text, style = style, maxLines = CapCardRules.FIELD_FOLD_LINES, overflow = TextOverflow.Clip)
                // 底部 2 行（2 × 行高 18）渐隐到预览底色 bg
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(36.dp)
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(t.bg.copy(alpha = 0f), t.bg))))
            }
        } else {
            val maxH = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * CapCardRules.FIELD_OPEN_MAX_SCREEN).dp
            val scroll = rememberScrollState()
            Box(Modifier.heightIn(max = maxH).verticalScroll(scroll).drawWithContent {
                drawContent()
                // 右侧细滚动条：只在真的能滚时画
                if (scroll.maxValue > 0) {
                    val view = size.height - scroll.maxValue
                    val thumb = (view * view / size.height).coerceAtLeast(24.dp.toPx())
                    val top = scroll.value + (view - thumb) * scroll.value / scroll.maxValue
                    drawRoundRect(t.faint.copy(alpha = 0.6f), topLeft = androidx.compose.ui.geometry.Offset(size.width - 3.dp.toPx(), top),
                        size = androidx.compose.ui.geometry.Size(2.dp.toPx(), thumb), cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()))
                }
            }) {
                Text(v, color = t.text, style = style, modifier = Modifier.padding(end = 8.dp))
            }
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (open) "收起" else "展开全文", color = t.orangeText, style = TextStyle(fontSize = 12.sp),
                modifier = Modifier.clickable(role = Role.Button) { open = !open })
            // 字数只在折着时写（同 PC e21f57c）
            if (!open) {
                Spacer(Modifier.width(8.dp))
                Text(CapCardRules.fieldCount(v), color = t.faint, style = TextStyle(fontSize = 12.sp))
            }
        }
    }
}

// ── 回执行（稿 C / F2 / F3） ──────────────────────────────────────────

@Composable
private fun CapReceiptRow(b: ChatBlock.Cap, card: CapCard) {
    val t = UmbraTheme.colors
    val r = CapCardRules.receipt(card, b.supersededBy)
    val (bg, fg) = when (r.pill) {
        CapPill.Ok -> t.successSoft to t.success
        CapPill.Bad -> t.dangerSoft to t.dangerText
        CapPill.Wait -> t.chip to t.muted
        CapPill.Off -> t.chip to t.faint
        CapPill.Busy -> t.orangeSoft to t.orangeText
    }
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(38.dp).clip(shape).background(t.card).border(UmbraDimens.borderW, t.borderSoft, shape)
            .clickable(enabled = card.preview.isNotEmpty(), role = Role.Button) { ChatStore.toggleCapExpanded(card.callId) }
            .padding(start = 12.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.clip(CircleShape).background(bg).padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                // 正在做：12 的转圈（同一颗 spinnerArc，设计侧 324 §6a）
                if (r.pill == CapPill.Busy) { com.geek.umbra.designsystem.UmbraSpinningIcon(UmbraIconPaths.spinnerArc, fg, 12.dp); Spacer(Modifier.width(4.dp)) }
                Text(r.label, color = fg, style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold), maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
            ReceiptLine(CapCardRules.receiptParts(card, r), r.subject, r.tail.joinToString("") { " · $it" },
                if (r.dim) t.muted else t.text, Modifier.weight(1f))
            // 行尾［撤销］（330 五.1 稿 D）：12 muted 文字钮；点了换转圈「正在撤销」（faint），行的其他部分不动。
            if (b.undoing) {
                Spacer(Modifier.width(8.dp))
                com.geek.umbra.designsystem.UmbraSpinningIcon(UmbraIconPaths.spinnerArc, t.faint, 11.dp)
                Spacer(Modifier.width(4.dp))
                Text("正在撤销", color = t.faint, style = TextStyle(fontSize = 12.sp), maxLines = 1)
            } else if (CapCardRules.showsUndo(card, b.undoBlocked)) {
                Text(CapCardRules.undoLabel(card), color = t.muted, style = TextStyle(fontSize = 12.sp), maxLines = 1,
                    modifier = Modifier.heightIn(min = 38.dp).clickable(role = Role.Button) { ChatStore.undoCap(card.callId) }
                        .padding(start = 10.dp, top = 11.dp, bottom = 11.dp))
            }
        }
        // 撤不了的原因（端上按原因码出字）/ 没动的那句（服务端 note）：同一个位置，行下一行 12 muted。
        (b.undoNote ?: r.note)?.let { Text(it, color = t.muted, style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp), modifier = Modifier.padding(start = 12.dp, top = 4.dp, end = 10.dp)) }
        if (b.expanded && card.preview.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (p in card.preview) CapPreviewView(p)
        }
    }
}

/**
 * 回执主体那一格：放不下时先截名字（330 五.7，按 iOS）——「能力 · 动作」不截；名字至少留引号 + 前 N 个字 +「…」（带金额 2 个、不带 4 个）；
 * 金额（342 四.2）永远不截，紧跟在名字后面；还放不下再截行尾。[parts] 为 null（已处理那种）时整串一个 Text 在行尾截。
 */
@Composable
private fun ReceiptLine(parts: CapCardRules.ReceiptParts?, plain: String, tail: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    val style = TextStyle(fontSize = 12.5.sp)
    if (parts == null) {
        Text(plain + tail, color = color, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
        return
    }
    val obj = parts.name
    androidx.compose.ui.layout.Layout(
        contents = listOf(
            { Text(parts.head, color = color, style = style, maxLines = 1, softWrap = false) },
            // softWrap = false：不然拉丁名字（Tax-ID-2024）会在「 · 」后面的空格断行，第一行只剩「 ·…」，名字截光（332 二）。
            { Text(" · $obj", color = color, style = style, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
            { Text(parts.amount ?: "", color = color, style = style, maxLines = 1, softWrap = false) },
            { Text(tail, color = color, style = style, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
            // 只量不画：名字留引号 + 前 N 个字 +「…」那么宽
            { Text(" · " + obj.take(parts.minNameChars + 1) + "…", style = style, maxLines = 1, softWrap = false) },
        ),
        modifier = modifier,
    ) { (h, o, am, tl, mn), c ->
        val w = c.maxWidth
        val hp = h.first().measure(androidx.compose.ui.unit.Constraints(maxWidth = w))
        val ap = am.first().measure(androidx.compose.ui.unit.Constraints(maxWidth = (w - hp.width).coerceAtLeast(0)))
        val avail = (w - hp.width - ap.width).coerceAtLeast(0)
        val oFull = o.first().maxIntrinsicWidth(c.maxHeight)
        val tFull = tl.first().maxIntrinsicWidth(c.maxHeight)
        val oMin = minOf(oFull, mn.first().maxIntrinsicWidth(c.maxHeight))
        val oW = CapCardRules.receiptNameWidth(avail, oFull, oMin, tFull)
        val op = o.first().measure(androidx.compose.ui.unit.Constraints(maxWidth = oW))
        val tp = tl.first().measure(androidx.compose.ui.unit.Constraints(maxWidth = (avail - op.width).coerceAtLeast(0)))
        val ht = maxOf(hp.height, op.height, ap.height, tp.height)
        layout(w, ht) {
            hp.placeRelative(0, (ht - hp.height) / 2)
            op.placeRelative(hp.width, (ht - op.height) / 2)
            ap.placeRelative(hp.width + op.width, (ht - ap.height) / 2)
            tp.placeRelative(hp.width + op.width + ap.width, (ht - tp.height) / 2)
        }
    }
}

/** 「看了 N 样东西」（314 a / 稿 L）：无框、12 muted、眼睛 13 + 小箭头；点开逐行「能力 · 动作　对象」。 */
@Composable
fun CapReadsRow(b: ChatBlock.CapReads) {
    val t = UmbraTheme.colors
    var open by remember(b.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.heightIn(min = 32.dp).clickable(role = Role.Button) { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            UmbraIcon(UmbraIconPaths.eye, t.muted, size = 13.dp, strokeWidth = 2f)
            Spacer(Modifier.width(5.dp))
            Text(CapCardRules.readHead(b.items.size), color = t.muted, style = TextStyle(fontSize = 12.sp))
            Spacer(Modifier.width(3.dp))
            UmbraIcon(UmbraIconPaths.chevronRight, t.muted, Modifier.rotate(if (open) 90f else 0f), size = 12.dp, strokeWidth = 2f)
        }
        if (open) for (c in b.items) Text(CapCardRules.readLine(c), color = t.faint, style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
            modifier = Modifier.padding(start = 18.dp))
    }
}

/** 带保险箱密钥的工作流说明卡（稿 I，手机用 body_remote）：锁 + 「工作流 · 秘书不能动」，无胶囊、无按钮。 */
@Composable
fun CapNoticeCard(n: CapNotice) {
    val t = UmbraTheme.colors
    Column(Modifier.fillMaxWidth().clip(capShape).background(t.card).border(UmbraDimens.borderW, t.border, capShape).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UmbraIcon(UmbraIconPaths.lock, t.muted, size = 14.dp, strokeWidth = 2f)
            Spacer(Modifier.width(7.dp))
            Text(CapNotice.HEAD, color = t.muted, style = TextStyle(fontSize = 12.5.sp))
        }
        Text(n.title, color = t.text, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp))
        Text(n.body, color = t.text, style = TextStyle(fontSize = 12.5.sp, lineHeight = 20.sp))
    }
}
