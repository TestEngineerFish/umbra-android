// 账本切换器与只读账本胶囊 —— 照抄 iOS Features/Money/MoneyLedgerSwitch.swift。
package com.geek.umbra.feature.money

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.net.Ledger

/**
 * 账本的图标：生活 = 钱包 / 经营 = 公文包（`switcherLayer` 定的两枚）。
 * ⚠️ 不长在 Ledger 上（那在 net 层，不该认识 designsystem）。iOS: MoneyLedgerSwitch.swift:7
 */
fun ledgerIconPath(v: Ledger): String = if (v == Ledger.Business) UmbraIconPaths.briefcase else UmbraIconPaths.wallet

/**
 * 记一笔那一屏金额卡标签行上的那枚账本胶囊（`whereAmI`）。chip 底、faint 字、11/600、内距 2-9，前置 12 同族图标。
 * ⚠️ **它是只读的，不是第二个切换器**：在一张填了一半的表单上改账本，而字段在两个账本里不一样 —— 那是让人填错。
 * ⚠️ 不加热区、不加点击：给一个只读的东西一块 44 的热区，等于告诉手指这里可以按。iOS: MoneyLedgerSwitch.swift:17
 */
@Composable
fun LedgerChip(value: Ledger, modifier: Modifier = Modifier) {
    val c = UmbraTheme.colors
    Row(
        modifier.clip(RoundedCornerShape(50)).background(c.chip).padding(horizontal = 9.dp, vertical = 2.dp)
            .semantics { contentDescription = "当前账本：${value.displayName}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UmbraIcon(ledgerIconPath(value), c.faint, size = 12.dp, strokeWidth = 1.8f)
        Spacer(Modifier.width(5.dp))
        Text(value.displayName, color = c.faint, style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold))
    }
}

/**
 * 账本切换器（`switcherLayer`）：**占掉那个静态标题的位置**。
 *
 * ⚠️ 只挂在记账的根页（统计）上；流水 / 设置这些推入页不挂 —— 那几屏的标题回答的是「我在哪一页」。
 * ⚠️ 菜单顺序取 Ledger 的声明顺序（Personal / Business），和 PC 那张 `LEDGERS` 同序。
 * ⚠️ **换账本整件事只有 MoneyStore.pickLedger 一处**（翻值 + 清筛选 + 重拉），这里只负责说「他选了哪个」。
 * iOS: MoneyLedgerSwitch.swift:52
 */
@Composable
fun MoneyLedgerSwitch(modifier: Modifier = Modifier) {
    val c = UmbraTheme.colors
    val ledger by MoneyStore.ledger.watch()
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.heightIn(min = UmbraDimens.tapMin)
                .clickable(role = Role.Button) { open = true }
                .padding(horizontal = 6.dp)
                .semantics { contentDescription = "切换账本，当前是${ledger.displayName}" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UmbraIcon(ledgerIconPath(ledger), c.text, size = 15.dp, strokeWidth = 1.8f)
            Spacer(Modifier.width(6.dp))
            Text(ledger.displayName, color = c.text, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.width(6.dp))
            UmbraIcon(UmbraIconPaths.chevronDown, c.muted, size = 12.dp, strokeWidth = 1.9f)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.card) {
            Ledger.entries.forEach { v ->
                DropdownMenuItem(
                    text = { Text(v.displayName, color = c.text) },
                    // iOS 菜单项的勾画在当前那一项上；没勾的那一项留同宽空位，两行字才对得齐。
                    leadingIcon = {
                        if (v == ledger) UmbraIcon(UmbraIconPaths.check, c.orange, size = 16.dp)
                        else Spacer(Modifier.size(16.dp))
                    },
                    onClick = {
                        open = false
                        MoneyStore.launch { MoneyStore.pickLedger(v) }
                    },
                )
            }
        }
    }
}
