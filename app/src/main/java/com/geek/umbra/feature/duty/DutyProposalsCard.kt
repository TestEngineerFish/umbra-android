// 执勤页「秘书的提议」那一区 —— 照搬 iOS Features/Duty/DutyProposalsCard.swift（M2 §九 / §14.2，形制照设计侧批次 261 §一 / §二，
// iOS 稿《批次 261 · 执勤 iOS》01）。
//
// **只提议、点头才生效**。区头「秘书的提议 N」+ 常在的「现在看一遍」（跑的时候「正在看…」+ 旋转弧、不可点）；
// 没有提议时区头下只有一句组脚注；超过 3 条折叠成「还有 N 条」。
// 一条一张卡，从上到下四件：
//   ① 种类小标（中性 chip 底 + muted 字 —— 橙色只留给主操作、当前选中、进度三处，一屏四张卡就是四块橙）+ 标题；
//   ② 为什么（muted，2 行截断）；
//   ③ 变化块（bg 底、1px borderSoft、圆角 10）：一句话 / 前后对比（旧值删除线 faint → 新值 600）/ 节点链（胶囊 + 箭头）；
//   ④ 钮：两颗等分（不用 + 主钮）；「做成例程」三颗：主钮「建并定时」独占一行，下面「只建 / 不用」等分。视觉 36、热区 48（Android 热区取 48）。
// 主钮按种类写动词（DutyKit.proposalPrimaryKey），「好」作废。「不用」不二次确认（它不删任何东西）。
// 点完任何一颗都出吐司写具体结果、带「撤销」：卡先收起，请求在撤销窗口过了才发（DutyStore.holdProposal 写了为什么）。
//
// 为什么不用设置行：一条提议是三段字 + 一块变化 + 两三颗钮，设置行「一个设置 + 一个值」的形状装不下。
package com.geek.umbra.feature.duty

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraFieldLabel
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraSpinningIcon
import com.geek.umbra.designsystem.UmbraTheme

/** 数字等宽（iOS `.monospacedDigit()`）：前后对比的时刻上下不跳。 */
private const val TNUM = "tnum"

@Composable
internal fun DutyProposalsCard(proposals: List<DutyProposal>, st: DutyState) {
    val ctx = LocalContext.current
    val tr = rememberDutyTr()
    val c = UmbraTheme.colors
    /** 点过「还有 N 条」。 */
    var showAll by rememberSaveable { mutableStateOf(false) }
    // 屏上要画的：点过、还在撤销窗口里的先不画（heldProposals）。
    val list = proposals.filter { it.id !in st.heldProposals }
    val fold = DutyKit.proposalShown(list.size, showAll)
    Column(verticalArrangement = Arrangement.spacedBy(UmbraDimens.sp2)) {
        Header(list.size, st.scanning)
        if (list.isEmpty()) {
            Text(
                ctx.getString(R.string.duty_pr_empty), color = c.muted, style = TextStyle(fontSize = 12.sp, lineHeight = 19.8.sp),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        } else {
            for (p in list.take(fold.shown)) ProposalCard(p, st, tr)
            if (fold.more > 0) {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = UmbraDimens.tapMin).clickable(role = Role.Button) { showAll = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(ctx.getString(R.string.duty_pr_more, "${fold.more}"), color = c.orangeText, style = TextStyle(fontSize = 15.sp))
                }
            }
        }
    }
}

// MARK: 区头

@Composable
private fun Header(count: Int, scanning: Boolean) {
    val ctx = LocalContext.current
    val c = UmbraTheme.colors
    Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        UmbraFieldLabel(ctx.getString(R.string.duty_pr_title))
        if (count > 0) {
            Spacer(Modifier.width(6.dp))
            Text("$count", color = c.faint, style = TextStyle(fontSize = 12.sp, fontFeatureSettings = TNUM))
        }
        Spacer(Modifier.weight(1f).width(8.dp))
        val tint = if (scanning) c.faint else c.orangeText
        Row(
            Modifier.heightIn(min = UmbraDimens.tapMin)
                .clickable(enabled = !scanning, role = Role.Button) { scan(ctx) }
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (scanning) UmbraSpinningIcon(UmbraIconPaths.spinnerArc, tint, size = 13.dp)
            Text(ctx.getString(if (scanning) R.string.duty_pr_scanning else R.string.duty_pr_scan), color = tint, style = TextStyle(fontSize = 15.sp))
        }
    }
}

// MARK: 一张卡

@Composable
private fun ProposalCard(p: DutyProposal, st: DutyState, tr: DutyTr) {
    val c = UmbraTheme.colors
    val name = dutyName(p, st)
    val change = DutyKit.proposalChange(p, tr, dutyName = name, currentBlocks = st.list?.brief?.params?.blocks)
    val shape = RoundedCornerShape(UmbraDimens.radiusCard)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.card).border(UmbraDimens.borderW, c.border, shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DutyKindTag(tr.k(DutyKit.proposalKindKey(p.kind)))
            Text(
                p.title, color = c.text, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (p.why.isNotEmpty()) {
            Text(
                p.why, color = c.muted, style = TextStyle(fontSize = 13.sp, lineHeight = 20.8.sp),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        if (change != DutyKit.ProposalChange.None) DutyChangeBlock(change)
        DutyKit.proposalNote(p, tr)?.let { Text(it, color = c.muted, style = TextStyle(fontSize = 12.5.sp)) }
        Buttons(p, name, tr)
    }
}

@Composable
private fun Buttons(p: DutyProposal, name: String, tr: DutyTr) {
    val ctx = LocalContext.current
    val primary = tr.k(DutyKit.proposalPrimaryKey(p))
    if (p.kind == "flow") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DutyPillButton(primary, true, Modifier.fillMaxWidth()) { accept(p, true, name, tr) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DutyPillButton(ctx.getString(R.string.duty_pr_flowOnly), false, Modifier.weight(1f)) { accept(p, false, name, tr) }
                DutyPillButton(ctx.getString(R.string.duty_pr_no), false, Modifier.weight(1f)) { reject(p) }
            }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DutyPillButton(ctx.getString(R.string.duty_pr_no), false, Modifier.weight(1f)) { reject(p) }
            DutyPillButton(primary, true, Modifier.weight(1f)) { accept(p, true, name, tr) }
        }
    }
}

/** 提议指向的那一条的名字（调整用：「早报时间 08:00 → 09:10」「早报改到 09:10 了」）。找不到给空串，文案退回不带名字的那句。 */
private fun dutyName(p: DutyProposal, st: DutyState): String {
    if (p.duty_id.isEmpty()) return ""
    return st.list?.items?.firstOrNull { it.id == p.duty_id }?.name ?: ""
}

/**
 * 提议卡上的「变化块」（批次 261 §二 3）：bg 底 + 1px borderSoft + 圆角 10，三种写法见 [DutyKit.ProposalChange]。
 * 节点链用 FlowRow：一条例程五六步时窄屏放不下一行，换行不截字（iOS 自写的 DutyNodeFlow 布局）。
 */
@Composable
internal fun DutyChangeBlock(change: DutyKit.ProposalChange) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(10.dp)
    val base = TextStyle(fontSize = 13.sp)
    Box(
        Modifier.fillMaxWidth().clip(shape).background(c.bg).border(UmbraDimens.borderW, c.borderSoft, shape)
            .padding(horizontal = 11.dp, vertical = 8.dp),
    ) {
        when (change) {
            DutyKit.ProposalChange.None -> Unit
            is DutyKit.ProposalChange.Text -> Text(change.text, color = c.text, style = base.copy(lineHeight = 20.sp))
            is DutyKit.ProposalChange.Diff -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(change.label, color = c.muted, style = base, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(change.from, color = c.faint, style = base.copy(textDecoration = TextDecoration.LineThrough, fontFeatureSettings = TNUM), maxLines = 1)
                UmbraIcon(UmbraIconPaths.arrowRight, c.faint, size = 13.dp, strokeWidth = 2f)
                Text(change.to, color = c.text, style = base.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = TNUM), maxLines = 1)
            }
            is DutyKit.ProposalChange.Nodes -> FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                change.nodes.forEachIndexed { i, n ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (i > 0) UmbraIcon(UmbraIconPaths.arrowRight, c.faint, size = 12.dp, strokeWidth = 2f)
                        val pill = RoundedCornerShape(50)
                        Row(
                            Modifier.clip(pill).background(c.card).border(UmbraDimens.borderW, c.border, pill)
                                .padding(horizontal = 10.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (n.added) UmbraIcon(UmbraIconPaths.plus, c.text, size = 11.dp, strokeWidth = 2.4f)
                            Text(n.label, color = c.text, style = base.copy(fontWeight = if (n.added) FontWeight.SemiBold else FontWeight.Normal))
                        }
                    }
                }
                if (change.meta.isNotEmpty()) Text(change.meta, color = c.faint, style = base)
            }
        }
    }
}

/**
 * 提议卡上的钮：视觉高 36、热区 48（批次 261 §二 4；Android 热区取 48，设计侧 199 答复全局规则）。
 * 主钮实心橙白字 600，其余 chip 底正文字。等分宽由外层 Row 的 weight 给。
 * 热区做法：外层 Box 撑到 48、可点；里面画 36 高的视觉块 —— 两颗之间的 8 间距由调用方给。
 */
@Composable
internal fun DutyPillButton(title: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.heightIn(min = UmbraDimens.tapMin).padding(vertical = 0.dp).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 36.dp).clip(shape).background(if (primary) c.orange else c.chip)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                title, color = if (primary) Color.White else c.text,
                style = TextStyle(fontSize = 15.sp, fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 提议种类的小标（新条目 / 调整 / 静默时段 / 早报 / 做成例程 / 关注）。批次 261 §二 1：中性 —— chip 底 + muted 字，不用橙。 */
@Composable
internal fun DutyKindTag(text: String, modifier: Modifier = Modifier) {
    val c = UmbraTheme.colors
    Text(
        text, color = c.muted, style = TextStyle(fontSize = 12.sp), maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(50)).background(c.chip).padding(horizontal = 8.dp, vertical = 1.dp),
    )
}

// MARK: 动作

private fun accept(p: DutyProposal, schedule: Boolean, name: String, tr: DutyTr) {
    decide(p, DutyKit.proposalDoneText(p, schedule, tr, dutyName = name)) { DutyStore.acceptProposal(p, schedule) }
}

private fun reject(p: DutyProposal) {
    decide(p, AppGraph.context.getString(R.string.duty_pr_rejected)) { DutyStore.rejectProposal(p) }
}

/** 收起这张、吐司写结果带「撤销」；撤销窗口过了才真发（holdProposal）。发失败卡回来，吐服务端那句原话。iOS DutyProposalsCard.swift:decide */
private fun decide(p: DutyProposal, text: String, op: suspend () -> String?) {
    if (p.id in DutyStore.state.value.heldProposals) return
    DutyStore.holdProposal(p.id, op) { e -> dutyReport(e) }
    // 「撤销」二字同 iOS Router.showToast 的默认 actionLabel（写死中文，iOS 也没进 xcstrings）。
    AppGraph.router.showToast(text, "撤销") { DutyStore.releaseProposal(p.id) }
}

/** 「现在看一遍」：要跑一次模型，几秒钟 —— 钮上换成「正在看…」，不让连点。409（总开关关着 / 没会员）照吐服务端原话。 */
private fun scan(ctx: android.content.Context) {
    if (DutyStore.state.value.scanning) return
    dutyLaunch {
        when (val r = DutyStore.scanProposals()) {
            is DutyRes.Ok -> AppGraph.router.showToast(
                if (r.value > 0) ctx.getString(R.string.duty_pr_scanSome, "${r.value}") else ctx.getString(R.string.duty_pr_scanNone),
            )
            is DutyRes.Failed -> dutyReport(r.why)
            null -> Unit
        }
    }
}
