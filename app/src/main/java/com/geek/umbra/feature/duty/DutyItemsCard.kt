// 执勤页「执勤表」那一组 —— 照搬 iOS Features/Duty/DutyItemsCard.swift（契约 §16.8 第 1 条）：
// 每条 = 名字、触发说人话、动作摘要、开关、下一次、上一次结局（带图标，失败说原因）；点一条进编辑；最后一行「新建」。早报不在这里（它保持原来的专用卡）。
//
// 为什么不用设置行：那一套行「有开关就只认开关」（整行不可点，防误触），而这里一行要两件事 ——
// 点文字进编辑、拨开关停用。所以文字那一块可点、开关是开关，各占各的热区；卡片底色 / 描边 / 行高 / 分隔线照抄分组卡。
package com.geek.umbra.feature.duty

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraGroupCard
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraRowDivider
import com.geek.umbra.designsystem.UmbraSectionLabel
import com.geek.umbra.designsystem.UmbraSwitch
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.navigation.Route

@Composable
internal fun DutyItemsCard(items: List<DutyItem>, st: DutyState, onNew: () -> Unit, onToggle: (DutyItem) -> Unit) {
    val ctx = LocalContext.current
    val tr = rememberDutyTr()
    val c = UmbraTheme.colors
    Column {
        UmbraSectionLabel(ctx.getString(R.string.duty_items), trailing = if (items.isEmpty()) null else items.size.toString())
        Spacer(Modifier.height(UmbraDimens.sp2))
        UmbraGroupCard {
            for (item in items) {
                Row(
                    Modifier.fillMaxWidth().defaultMinSize(minHeight = UmbraDimens.rowMinH).padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    Column(
                        Modifier.weight(1f).clickable(role = Role.Button) { AppGraph.router.go(Route.MeDutyEdit(item.id)) },
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            item.name.ifEmpty { ctx.getString(R.string.duty_edit_unnamed) },
                            color = if (item.enabled) c.text else c.muted, style = TextStyle(fontSize = 16.sp),
                        )
                        for (line in lines(ctx, tr, item, st)) {
                            Text(line, color = c.muted, style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp))
                        }
                        item.last?.let { LastLine(it, tr) }
                    }
                    UmbraSwitch(item.enabled, { onToggle(item) })
                }
                UmbraRowDivider()
            }
            Box(
                Modifier.fillMaxWidth().defaultMinSize(minHeight = UmbraDimens.rowMinH).clickable(role = Role.Button, onClick = onNew)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(ctx.getString(R.string.duty_new), color = c.orangeText, style = TextStyle(fontSize = 16.sp))
            }
        }
        Spacer(Modifier.height(UmbraDimens.sp2))
        DutyFooter(ctx.getString(R.string.duty_items_help))
    }
}

/**
 * 「上一次」那一行（批次 261 §四）：结局图标 + 一句；没跑成的整行 dangerText、写原因（DutyKit.lastLine：「今天 08:00没跑成：原因」）。
 *
 * 稿上这一行还带一颗「下一步」钮（示例是「去授权」）—— 没做（同 iOS）：服务端结案失败的 reason_code 今天只有 error 一种
 * （中途重启 / 例程被删 / 运行异常都是它），端上分不出该给哪一步，猜一颗错的钮比没有钮更糟。
 */
@Composable
private fun LastLine(s: DutyShift, tr: DutyTr) {
    val c = UmbraTheme.colors
    val l = DutyKit.lastLine(s, System.currentTimeMillis(), tr)
    val tint = if (l.failed) c.dangerText else c.muted
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        dutyOutcomeIconPath(s)?.let { UmbraIcon(it, tint, size = 12.dp, strokeWidth = 2f, modifier = Modifier.padding(top = 3.dp)) }
        Text(l.text, color = tint, style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp))
    }
}

/** 名字下面的几行：触发 · 要不要判断 / 动作 / 下一次（「上一次」单起一行，见 [LastLine]）。 */
private fun lines(ctx: android.content.Context, tr: DutyTr, item: DutyItem, st: DutyState): List<String> {
    val out = mutableListOf<String>()
    val trig = DutyKit.trigger(item.trigger, tr)
    if (trig.isNotEmpty()) out += listOf(trig, DutyKit.modeText(item.mode, tr)).joinToString(" · ")
    // 「跑一个例程」带上例程名（M1b）：名字从例程列表里找，找不到退回不带名字的那句。
    val act = DutyKit.action(item.action, tr, flowName = DutyStore.flowName(item.action?.flow_id ?: "", st))
    if (act.isNotEmpty()) out += act
    val whenParts = mutableListOf<String>()
    val n = item.next_at_ms
    if (item.enabled && n != null && n > 0) whenParts += ctx.getString(R.string.duty_brief_next, DutyRules.monthDayTime(n))
    if (whenParts.isNotEmpty()) out += whenParts.joinToString(" · ")
    return out
}
