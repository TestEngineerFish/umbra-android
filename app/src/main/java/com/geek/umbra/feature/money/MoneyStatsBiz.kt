// 经营账本的统计屏 —— 照抄 iOS Features/Money/MoneyStatsBizView.swift（PC 同源 MoneyStatsBiz.tsx）。
//
// ## 为什么是另一屏，而不是把四张卡一张一张藏起来
// 把四张卡一张一张藏起来，漏一张就是一张「给另一个账本的数配这一个的抬头」的卡 —— 它不会报错。要换掉的四张：
// ⒜ 分类占比（科目不上色，画出来是一圈灰）⒝ 月度趋势（一根孤柱不是趋势）⒞ 大额支出（口径是「支出」不是「成本」）
// ⒟ 环比那一行（拿支出算的环比挂在成本下面，比不给那一行坏）。
// ⚠️ 那三个数（cost / not_cost / profit）是**服务端算的**，端上一分都不减 —— 税的口径只许有一个出处。
package com.geek.umbra.feature.money

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraType

/** 经营统计屏：成本概览 + 按科目。onGoList 带科目 slug 跳流水（支出方向）。iOS: MoneyStatsBizView.swift:22 */
@Composable
fun MoneyStatsBiz(st: MoneyStatsDTO, catName: (String) -> String, onGoList: (String?) -> Unit) {
    val cost = st.cost ?: 0L
    val notCost = st.not_cost ?: 0L
    Column(verticalArrangement = Arrangement.spacedBy(UmbraDimens.sp4)) {
        BizSummary(st, cost, notCost)
        BizByAccount(st, cost, catName, onGoList)
    }
}

/** 概览卡：主角从「支出」换成「成本」—— 不是换个标签，数也换了。iOS: MoneyStatsBizView.swift:37 */
@Composable
private fun BizSummary(st: MoneyStatsDTO, cost: Long, notCost: Long) {
    val c = UmbraTheme.colors
    Column(Modifier.moneyCard().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("成本", color = c.muted, style = TextStyle(fontSize = 12.5.sp))
            Text(MoneyFmt.yuan(cost), color = c.text, style = TextStyle(fontSize = 34.sp, fontWeight = UmbraType.weight650))
        }
        // ⚠️ 环比那一行整块不出，换成「差在哪儿」那一句 —— 回答的是「我这个月明明花了更多，为什么成本是这个数」。
        Text(
            if (notCost > 0) "这个月花了 ${MoneyFmt.yuan(st.expense)}，其中 ${MoneyFmt.yuan(notCost)} 无票不可扣，没进成本。"
            else "这个月花的 ${MoneyFmt.yuan(st.expense)} 全部进了成本。",
            color = c.muted, style = TextStyle(fontSize = 13.sp, lineHeight = (13 * 1.6).sp),
        )
        Column {
            MoneyBleedSeparator(16.dp)
            Row(Modifier.padding(top = 11.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("收入", color = c.muted, style = TextStyle(fontSize = 12.sp))
                    Text(MoneyFmt.yuan(st.income), color = c.success, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("利润", color = c.muted, style = TextStyle(fontSize = 12.sp))
                    // ⚠️ 利润为负**不染红**：一个月亏了是一个正常的月份，不是一个错误 —— 红色只表示危险与破坏性操作。
                    Text(MoneyFmt.yuan(st.profit ?: 0L), color = c.text, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
                }
            }
        }
    }
}

/** 按科目卡：每行可点去流水；**没有色块**（科目没有色槽，灰方块是说不出话的占位符）。iOS: MoneyStatsBizView.swift:77 */
@Composable
private fun BizByAccount(st: MoneyStatsDTO, cost: Long, catName: (String) -> String, onGoList: (String?) -> Unit) {
    val c = UmbraTheme.colors
    Column(Modifier.moneyCard().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("按科目", color = c.text, style = TextStyle(fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(bottom = 5.dp))
        if (st.by_cat.isEmpty()) MoneyCardNoData(UmbraIconPaths.briefcase, "这个月还没有成本")
        st.by_cat.forEach { r ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = UmbraDimens.tapMin).clickable(role = Role.Button) { onGoList(r.cat) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(catName(r.cat), color = c.text, style = TextStyle(fontSize = 13.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                CostBar(r.cents, cost)
                Spacer(Modifier.width(10.dp))
                Text(MoneyFmt.yuan(r.cents), color = c.text, style = TextStyle(fontSize = 13.sp, fontFamily = MoneyMono))
            }
        }
        // 那句口径说明。**它不是补充，它是这一屏和生活那一屏读起来不一样的原因。**
        Text(
            "利润 = 收入 − 成本，是税口径的算法，和「收入 − 支出」不一样。" +
                "成本排除票据状态为「无票不可扣」的那几笔；" +
                "待补票的仍算成本 —— 它是票还没拿到，不是这笔不能扣。",
            color = c.muted, style = TextStyle(fontSize = 11.5.sp, lineHeight = (11.5 * 1.65).sp),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * 横条。⚠️ 分母是**成本**，不是支出 —— 拿支出当分母的话各行百分比加起来不到 100，而没有任何一句话解释那一截去哪了。
 * iOS: MoneyStatsBizView.swift:117（72 × 6）
 */
@Composable
private fun CostBar(cents: Long, cost: Long) {
    val c = UmbraTheme.colors
    val ratio = if (cost > 0) minOf(1f, cents.toFloat() / cost.toFloat()) else 0f
    val pill = RoundedCornerShape(50)
    Box(Modifier.size(width = 72.dp, height = 6.dp).clip(pill).background(c.chip)) {
        if (ratio > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(ratio).clip(pill).background(c.orange))
    }
}
