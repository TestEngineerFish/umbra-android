// 执勤页「秘书的提议」那一区 —— 照搬 iOS Features/Duty/DutyProposalsCard.swift（M2 §九 / §14.2，同 PC `ProposalsPanel`）。
//
// **只提议、点头才生效**：每条一张小卡 —— 种类小标 + 标题、一句「为什么」、一行细节（点了「好」会改成什么），
// 两颗钮「好 / 不用」；「做成流程」那种三颗：「建好并每个工作日 X 跑 / 只建不定时 / 不用」。
// 不点就什么都不变；「不用」= 同一件事 30 天内不再提（服务端判）。没有提议时一行说明 + 「现在看一遍」。
package com.geek.umbra.feature.duty

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraGroupCard
import com.geek.umbra.designsystem.UmbraRowDivider
import com.geek.umbra.designsystem.UmbraSectionLabel
import com.geek.umbra.designsystem.UmbraStripAction
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraType

@Composable
internal fun DutyProposalsCard(proposals: List<DutyProposal>, proposalBusy: Boolean) {
    val ctx = LocalContext.current
    val tr = rememberDutyTr()
    val c = UmbraTheme.colors
    Column {
        UmbraSectionLabel(ctx.getString(R.string.duty_pr_title), trailing = if (proposals.isEmpty()) null else proposals.size.toString())
        Spacer(Modifier.height(UmbraDimens.sp2))
        UmbraGroupCard {
            if (proposals.isEmpty()) {
                // 没有提议：一行说明 + 「现在看一遍」。
                Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 11.dp)) {
                    Text(ctx.getString(R.string.duty_pr_empty), color = c.muted, style = TextStyle(fontSize = 13.5.sp, lineHeight = 19.sp))
                    UmbraStripAction(ctx.getString(if (proposalBusy) R.string.duty_pr_scanning else R.string.duty_pr_scan), c.orangeText, { scan(ctx) })
                }
            } else {
                proposals.forEachIndexed { i, p ->
                    if (i > 0) UmbraRowDivider()
                    val detail = DutyKit.proposalDetail(p, tr)
                    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            DutyKindTag(tr.k(DutyKit.proposalKindKey(p.kind)), Modifier.padding(top = 2.dp))
                            Text(p.title, color = c.text, style = TextStyle(fontSize = 15.sp, fontWeight = UmbraType.weight560))
                        }
                        if (p.why.isNotEmpty()) Text(p.why, color = c.muted, style = TextStyle(fontSize = 13.5.sp, lineHeight = 19.sp))
                        if (detail.isNotEmpty()) Text(detail, color = c.faint, style = TextStyle(fontSize = 12.5.sp, lineHeight = 17.5.sp))
                        // 「做成流程」三颗的文字长（「建好并每个工作日 08:00 跑」），窄屏一排放不下：用 FlowRow 自动折行，不截字（iOS ViewThatFits）。
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            if (p.kind == "flow") {
                                UmbraStripAction(ctx.getString(R.string.duty_pr_flowSchedule, DutyKit.proposalFlowTime(p)), c.orangeText, { accept(ctx, p, true) })
                                UmbraStripAction(ctx.getString(R.string.duty_pr_flowOnly), c.orangeText, { accept(ctx, p, false) })
                            } else {
                                UmbraStripAction(ctx.getString(R.string.duty_pr_yes), c.orangeText, { accept(ctx, p, true) })
                            }
                            UmbraStripAction(ctx.getString(R.string.duty_pr_no), c.muted, { reject(ctx, p) })
                        }
                    }
                }
            }
        }
        // 「只是建议、点『好』才会改」只在有提议时说 —— 空态那一行自己已经说了秘书什么时候看。
        if (proposals.isNotEmpty()) {
            Spacer(Modifier.height(UmbraDimens.sp2))
            DutyFooter(ctx.getString(R.string.duty_pr_hint))
        }
    }
}

/** 提议种类的小标（新条目 / 调整 / 静默时段 / 早报 / 做成流程 / 关注）。软底 + *Text 字色，同早报卡「后续」那一颗。 */
@Composable
internal fun DutyKindTag(text: String, modifier: Modifier = Modifier) {
    val c = UmbraTheme.colors
    Text(
        text, color = c.orangeText, style = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
        modifier = modifier.clip(RoundedCornerShape(50)).background(c.orangeSoft).padding(horizontal = 6.dp, vertical = 1.5.dp),
    )
}

private val proposalBusyNow: Boolean get() = DutyStore.state.value.proposalBusy

private fun accept(ctx: android.content.Context, p: DutyProposal, schedule: Boolean) {
    if (proposalBusyNow) return
    dutyLaunch {
        val e = DutyStore.acceptProposal(p, schedule)
        if (e != null) dutyReport(e) else AppGraph.router.showToast(ctx.getString(R.string.duty_pr_done))
    }
}

private fun reject(ctx: android.content.Context, p: DutyProposal) {
    if (proposalBusyNow) return
    dutyLaunch {
        val e = DutyStore.rejectProposal(p)
        if (e != null) dutyReport(e) else AppGraph.router.showToast(ctx.getString(R.string.duty_pr_rejected))
    }
}

/** 「现在看一遍」：要跑一次模型，几秒钟 —— 钮上换成「正在看…」，不让连点。409（总开关关着 / 没会员）照吐服务端原话。 */
private fun scan(ctx: android.content.Context) {
    if (proposalBusyNow) return
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
