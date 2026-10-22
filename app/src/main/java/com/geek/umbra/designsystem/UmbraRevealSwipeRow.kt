// 左滑 / 右滑露出动作钮的行 —— 全 App 唯一的一份（提醒、常用语、执勤、知识库、存储、记账分类 / 子类 / 周期 / 流水、保险箱、聊天联系人）。
//
// 设计侧批次 201：「左滑收成一份时以『只露按钮』那一版为准，记账流水那三颗钮照搬进同一个件」；199 §4 照默认：
// 胶囊钮（图标 + 文字、实底），拖到底也不执行，删除类仍过确认。从前「我」/ 记账分类 / 记账流水 / 保险箱各有一份自绘的
// （方块整高钮、整行滑走等），已全部换成这一件。
//
// iOS `.swipeActions(allowsFullSwipe: false)`：滑开到位**只露按钮**，仍要点那颗胶囊才执行 —— 滑到底自动执行在「完成」上痛快，
// 同一个手势在另一边就是删除，两边行为不一致比多点一下贵。
// 实现：M3 SwipeToDismissBox 滑过阈值时**拒绝 dismiss**（confirmValueChange 回 false，行弹回原位），
// 改成把那一侧的动作钮常驻摊在行上（再点一下行的其余部分收起）。破坏性动作由调用方再过确认弹窗。
package com.geek.umbra.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 一颗滑出来的动作钮：图标 + 文字、实底色（[bg]）。[fg] 不给就是白字；底色浅的（muted 那类）照样白字，要别的前景才传。 */
data class UmbraSwipeAction(val label: String, val icon: String?, val bg: Color, val fg: Color? = null, val onClick: () -> Unit)

/**
 * [leading] 是右滑（从左边露出）那一侧，[trailing] 是左滑（从右边露出）那一侧。空的那一侧不让滑。
 * [resetKey] 变了（数据刷新、行换了身份）自动收起。
 * [openRow] + [rowKey]：同一张列表里**同一时刻只开一行**（记账那几页的规矩）——列表持有一个 openRow，
 * 摊开时写进自己的 key，别的行看到不是自己就收起。不传就各行各管。
 */
@Composable
fun UmbraRevealSwipeRow(
    leading: List<UmbraSwipeAction> = emptyList(),
    trailing: List<UmbraSwipeAction> = emptyList(),
    resetKey: Any? = null,
    modifier: Modifier = Modifier,
    openRow: MutableState<Any?>? = null,
    rowKey: Any? = null,
    content: @Composable () -> Unit,
) {
    var revealed by remember(resetKey) { mutableStateOf<SwipeToDismissBoxValue?>(null) }
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { v -> if (v != SwipeToDismissBoxValue.Settled) { revealed = v; openRow?.value = rowKey }; false },
    )
    if (openRow != null) {
        LaunchedEffect(openRow.value) { if (openRow.value != rowKey) revealed = null }
    }
    fun close() {
        revealed = null
        if (openRow != null && openRow.value == rowKey) openRow.value = null
    }
    Box(modifier) {
        SwipeToDismissBox(
            state = state,
            enableDismissFromStartToEnd = leading.isNotEmpty() && revealed == null,
            enableDismissFromEndToStart = trailing.isNotEmpty() && revealed == null,
            backgroundContent = {
                // 只在拖着的时候画：静止时 dismissDirection 是 Settled，画出来的底色会从透明底的行里透出来。
                if (state.dismissDirection == SwipeToDismissBoxValue.Settled) return@SwipeToDismissBox
                val toEnd = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
                val acts = if (toEnd) leading else trailing
                Row(
                    Modifier.fillMaxSize().background(acts.firstOrNull()?.bg ?: Color.Transparent).padding(horizontal = 18.dp),
                    horizontalArrangement = if (toEnd) Arrangement.Start else Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    acts.firstOrNull()?.let { a ->
                        val fg = a.fg ?: Color.White
                        a.icon?.let { UmbraIcon(it, fg, size = 18.dp); Spacer(Modifier.width(6.dp)) }
                        Text(a.label, color = fg, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
                    }
                }
            },
        ) { content() }
        val side = revealed
        if (side != null) {
            val acts = if (side == SwipeToDismissBoxValue.StartToEnd) leading else trailing
            // 摊开的动作钮：盖在行上；点行的其余部分收起。
            Row(
                Modifier.matchParentSize().background(UmbraTheme.colors.card.copy(alpha = 0.92f)).clickable { close() },
                horizontalArrangement = if (side == SwipeToDismissBoxValue.StartToEnd) Arrangement.Start else Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                acts.forEach { a ->
                    Row(
                        Modifier.fillMaxHeight().heightIn(min = UmbraDimens.tapMin).padding(vertical = 6.dp, horizontal = 4.dp)
                            .clip(RoundedCornerShape(UmbraDimens.radiusSwipeRow)).background(a.bg)
                            .clickable(role = Role.Button) { close(); a.onClick() }.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val fg = a.fg ?: Color.White
                        a.icon?.let { UmbraIcon(it, fg, size = 16.dp); Spacer(Modifier.width(5.dp)) }
                        Text(a.label, color = fg, style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold), maxLines = 1)
                    }
                }
            }
        }
    }
}
