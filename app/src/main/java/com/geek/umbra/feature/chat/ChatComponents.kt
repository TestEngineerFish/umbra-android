// 聊天页的共用小件：气泡形状、思考三点、流式光标、长按菜单、带鉴权的取图、任务状态映射、按 iOS 点分 key 取文案。
//
// iOS 出处：ChatThreadView.swift:2373-2486（UmbraBubbleShape / UmbraBlinkCaret / UmbraThinkingDots / UmbraStatus(taskStatus:)）。
package com.geek.umbra.feature.chat

import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.geek.umbra.R
import com.geek.umbra.designsystem.UmbraStatus
import com.geek.umbra.designsystem.UmbraTheme
import kotlinx.coroutines.delay

/** 气泡形状：我方 16 16 4 16（右下收角），对方 16 16 16 4（左下收角）。 */
fun bubbleShape(mine: Boolean) = if (mine) RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp) else RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)

/** 气泡对侧留白 56（iOS UmbraMetric.bubbleGutter）。动态字号 AX 档在 Android 没有对等分档，恒 56。 */
val BUBBLE_GUTTER = 56.dp

/** 服务端任务 / 操控 status → 状态枚举。认不出来的一律当「执行中」（iOS ChatThreadView.swift:2475）。 */
fun taskStatus(s: String): UmbraStatus = when (s) {
    "done", "succeeded", "success" -> UmbraStatus.Done
    "failed", "error" -> UmbraStatus.Failed
    "awaiting_confirm" -> UmbraStatus.AwaitingReview
    "suspended", "paused" -> UmbraStatus.Suspended
    "pending", "queued" -> UmbraStatus.Pending
    "cancelled", "canceled", "stopped" -> UmbraStatus.Cancelled
    else -> UmbraStatus.Running
}

/** iOS 点分 key → 资源 id（只收聊天里按类别动态取的那几条；资源名由 gen_strings 把 . 换成 _）。 */
fun chatKeyRes(key: String): Int = when (key) {
    "vision.topup" -> R.string.vision_topup
    "vision.openMembership" -> R.string.vision_openMembership
    "vision.quotaShop" -> R.string.android_quotaShop
    "reason.insufficientBalance" -> R.string.reason_insufficientBalance
    "reason.needMembership" -> R.string.reason_needMembership
    "reason.quotaExhausted" -> R.string.reason_quotaExhausted
    "reason.rateLimited" -> R.string.reason_rateLimited
    "reason.upstreamError" -> R.string.reason_upstreamError
    "reason.authFailed" -> R.string.reason_authFailed
    "reason.badRequest" -> R.string.reason_badRequest
    else -> R.string.reason_unavailable
}

/** 占位气泡里的三点：7 圆点、muted、1.2 秒一轮，三颗错开 0.2 秒。 */
@Composable
fun ThinkingDots() {
    val c = UmbraTheme.colors
    val t = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { i ->
            val y by t.animateFloat(
                0f, -3f,
                infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse, StartOffset(i * 200)),
                label = "dot$i",
            )
            Box(Modifier.offset(y = y.dp).size(7.dp).clip(CircleShape).background(c.muted))
        }
    }
}

/** 流式光标：7×16 橙块，1 秒一次**硬闪**（不是渐隐 —— 渐隐像呼吸灯，这里要表达「还在往外吐字」）。 */
@Composable
fun BlinkCaret() {
    var on by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { while (true) { delay(500); on = !on } }
    Box(Modifier.size(7.dp, 16.dp).alpha(if (on) 1f else 0f).background(UmbraTheme.colors.orange))
}

/** 长按菜单的一项。danger 画红字（删除这类仍要再过确认弹窗）。 */
data class ChatMenuItem(val label: String, val danger: Boolean = false, val onClick: () -> Unit)

/**
 * 有条件地挂长按菜单（iOS UmbraConditionalMenu + 系统 contextMenu）。Android 换成 combinedClickable + DropdownMenu。
 * items 为空 = 不挂（不给一个长按了什么都没有的反馈）。onClick 给可点的那一层（如图片点开预览）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatMenuBox(items: List<ChatMenuItem>, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val c = UmbraTheme.colors
    Box(
        modifier.then(
            if (items.isEmpty() && onClick == null) Modifier
            else Modifier.combinedClickable(
                onClick = { onClick?.invoke() },
                onLongClick = if (items.isEmpty()) null else ({ haptic.performHapticFeedback(HapticFeedbackType.LongPress); open = true }),
            ),
        ),
    ) {
        content()
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.card, modifier = Modifier.widthIn(min = 180.dp)) {
            items.forEach { it ->
                DropdownMenuItem(
                    text = { Text(it.label, color = if (it.danger) c.dangerText else c.text) },
                    onClick = { open = false; it.onClick() },
                )
            }
        }
    }
}

