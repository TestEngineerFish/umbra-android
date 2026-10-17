// 左滑 / 右滑露出动作钮的行（提醒列表、常用语列表共用；提醒代理建）。
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

/** 一颗滑出来的动作钮：文字 + 图标、实底色（[bg]）、白字。 */
data class UmbraSwipeAction(val label: String, val icon: String, val bg: Color, val onClick: () -> Unit)

/**
 * [leading] 是右滑（从左边露出）那一侧，[trailing] 是左滑（从右边露出）那一侧。空的那一侧不让滑。
 * [resetKey] 变了（数据刷新、行换了身份）自动收起。
 */
@Composable
fun UmbraRevealSwipeRow(
    leading: List<UmbraSwipeAction> = emptyList(),
    trailing: List<UmbraSwipeAction> = emptyList(),
    resetKey: Any? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var revealed by remember(resetKey) { mutableStateOf<SwipeToDismissBoxValue?>(null) }
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { v -> if (v != SwipeToDismissBoxValue.Settled) revealed = v; false },
    )
    Box(modifier) {
        SwipeToDismissBox(
            state = state,
            enableDismissFromStartToEnd = leading.isNotEmpty() && revealed == null,
            enableDismissFromEndToStart = trailing.isNotEmpty() && revealed == null,
            backgroundContent = {
                val toEnd = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
                val acts = if (toEnd) leading else trailing
                Row(
                    Modifier.fillMaxSize().background(acts.firstOrNull()?.bg ?: Color.Transparent).padding(horizontal = 18.dp),
                    horizontalArrangement = if (toEnd) Arrangement.Start else Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    acts.firstOrNull()?.let { a ->
                        UmbraIcon(a.icon, Color.White, size = 18.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(a.label, color = Color.White, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
                    }
                }
            },
        ) { content() }
        val side = revealed
        if (side != null) {
            val acts = if (side == SwipeToDismissBoxValue.StartToEnd) leading else trailing
            // 摊开的动作钮：盖在行上；点行的其余部分收起。
            Row(
                Modifier.matchParentSize().background(UmbraTheme.colors.card.copy(alpha = 0.92f)).clickable { revealed = null },
                horizontalArrangement = if (side == SwipeToDismissBoxValue.StartToEnd) Arrangement.Start else Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                acts.forEach { a ->
                    Row(
                        Modifier.fillMaxHeight().heightIn(min = UmbraDimens.tapMin).padding(vertical = 6.dp, horizontal = 4.dp)
                            .clip(RoundedCornerShape(UmbraDimens.radiusSwipeRow)).background(a.bg)
                            .clickable(role = Role.Button) { revealed = null; a.onClick() }.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        UmbraIcon(a.icon, Color.White, size = 16.dp)
                        Spacer(Modifier.width(5.dp))
                        Text(a.label, color = Color.White, style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold))
                    }
                }
            }
        }
    }
}
