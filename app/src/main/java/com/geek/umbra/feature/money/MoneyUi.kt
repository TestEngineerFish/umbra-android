// 记账几屏共用的视觉小件：卡片外观、行分隔线、卡内无数据、三态（骨架 / 错误卡 / 离线条）、要登录、分类色。
//
// 放在记账包里而不是 designsystem/：iOS 那边 `umbraScreenPhase` / `UmbraCardNoData` 是设计系统件，
// Android 的 designsystem 还没有它们（地基缺件，报告里写了）。这里先按 iOS 的取值做一份只给记账用，
// 统一补进 designsystem 时整份替换，页面调用点不用动。
package com.geek.umbra.feature.money

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraErrorCard
import com.geek.umbra.designsystem.UmbraErrorStrip
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraSkeleton
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraTone
import com.geek.umbra.designsystem.UmbraType
import com.geek.umbra.navigation.Route

/** 记账卡：card 底 + borderSoft 描边 + 圆角 18（iOS MoneyViews.swift:1289 MoneyCardMod，描边比通用卡浅一档）。 */
@Composable
fun Modifier.moneyCard(): Modifier {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(UmbraDimens.radiusCard)
    return this.fillMaxWidth().clip(shape).background(c.card).border(UmbraDimens.borderW, c.borderSoft, shape)
}

/** 卡内行间线。iOS 那条往左右各探出卡内衬（padding -pad）；Compose 里由调用方把线画在内衬外层，这里只画线。 */
@Composable
fun MoneyRowSeparator(modifier: Modifier = Modifier, tint: Color = UmbraTheme.colors.borderSoft) {
    Box(modifier.fillMaxWidth().height(UmbraDimens.borderW).background(tint))
}

/** 卡内无数据（iOS UmbraStates.swift:632 UmbraCardNoData）：48 图标块 + 14/560 muted，上下 26。 */
@Composable
fun MoneyCardNoData(icon: String, title: String) {
    val c = UmbraTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(vertical = 26.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(c.chip), contentAlignment = Alignment.Center) {
            UmbraIcon(icon, c.muted, size = 22.dp)
        }
        Text(title, color = c.muted, style = TextStyle(fontSize = 14.sp, fontWeight = UmbraType.weight560), textAlign = TextAlign.Center)
    }
}

/** 一屏的三态（iOS UmbraScreenPhase）。 */
sealed interface MoneyScreenPhase {
    data object Skeleton : MoneyScreenPhase
    data class Failed(val offline: Boolean) : MoneyScreenPhase
    data class Content(val offline: Boolean) : MoneyScreenPhase

    companion object {
        /** 手上有东西就画内容（离线时挂离线条，不清屏）；没有且在拉 → 骨架；没拉成过 → 错误卡。iOS: UmbraStates.swift:56 */
        fun resolve(hasContent: Boolean, loading: Boolean, loadedOnce: Boolean, offline: Boolean): MoneyScreenPhase = when {
            hasContent -> Content(offline)
            loading -> Skeleton
            !loadedOnce -> Failed(offline)
            else -> Content(offline)
        }
    }
}

/**
 * 三态的前两态画进列表（骨架 / 错误卡），第三态交给 [content]。
 * 错误卡原因分两句：「够不着」查网络，「够得着但没给数据」查地址和令牌；第二颗钮把人送到「我 › 连接」。
 */
fun LazyListScope.moneyPhase(
    phase: MoneyScreenPhase,
    failedTitle: String,
    retry: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    when (phase) {
        MoneyScreenPhase.Skeleton -> item(key = "__skeleton") { UmbraSkeleton() }
        is MoneyScreenPhase.Failed -> item(key = "__failed") {
            UmbraErrorCard(
                title = failedTitle,
                body = stringResource(if (phase.offline) R.string.state_failed_reason_offline else R.string.state_failed_reason_server),
                actions = listOf(
                    stringResource(R.string.state_retry) to retry,
                    stringResource(R.string.state_checkServer) to { AppGraph.router.jump(Route.SetConn) },
                ),
            )
        }
        is MoneyScreenPhase.Content -> content()
    }
}

/** 离线条：放进 UmbraScreen 的 topContent，钉在列表上方不跟着滚。记账是「刚才拿到的」那句。 */
@Composable
fun MoneyOfflineTop(phase: MoneyScreenPhase) {
    if (phase is MoneyScreenPhase.Content && phase.offline) {
        UmbraErrorStrip(stringResource(R.string.state_offline_title) + " · " + stringResource(R.string.state_offline_reason_live), tone = UmbraTone.Warning)
    }
}

/**
 * 「这个功能要登录」—— 经营账本始终在线，未登录时画它（iOS Features/Auth/NeedLoginView.swift）。
 * 生活账本是本机账本，不走这里。
 */
@Composable
fun MoneyNeedLogin() {
    Column(Modifier.fillMaxWidth().padding(top = 40.dp)) {
        UmbraErrorCard(
            title = "这个功能要登录 Umbra 账号",
            body = "它的数据在服务器上：聊天、任务、记账、灵感、知识库、设备都是。提醒、保险箱、常用语这些本机功能照常用，要用这些就登录一下。",
            tone = UmbraTone.Warning,
            actions = listOf("去登录" to { AppGraph.auth.goToLogin() }),
        )
    }
}

/** 色槽 → 彩色（浅 / 深各一套，与 PC --c1…--c10 一致）。0 与一切非法值 → 中性灰。iOS: MoneySync.swift:197 */
@Composable
fun moneySlotColor(slot: Int): Color {
    val (l, d) = MoneyCatArt.slotHex(slot)
    return Color(0xFF000000 or (if (UmbraTheme.colors.isDark) d else l))
}

/** 分类色块底色：同色 tint，浅色 13% / 深色 22%（深色底上同样的透明度显得更闷）。 */
@Composable
fun moneySlotTint(slot: Int): Color {
    val dark = UmbraTheme.colors.isDark
    val (l, d) = MoneyCatArt.slotHex(slot)
    return Color(0xFF000000 or (if (dark) d else l)).copy(alpha = if (dark) 0.22f else 0.13f)
}

/** 分类色块：同色 tint 底 + 色槽色描边图标（批次 003）。 */
@Composable
fun MoneyCatBlock(slot: Int, icon: String, block: Dp = 30.dp, iconSize: Dp = 15.dp, corner: Dp = 9.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(block).clip(RoundedCornerShape(corner)).background(moneySlotTint(slot)), contentAlignment = Alignment.Center) {
        UmbraIcon(icon, moneySlotColor(slot), size = iconSize)
    }
}

/** 小胶囊开关钮（「以表格查看」「近 6 月」）：胶囊 28、热区补到 44。 */
@Composable
fun MoneyPill(label: String, on: Boolean = false, onClick: () -> Unit) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(50)
    Box(Modifier.height(UmbraDimens.tapMin).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(
            label,
            color = if (on) c.orangeText else c.muted,
            style = TextStyle(fontSize = 12.sp, fontWeight = UmbraType.weight560),
            modifier = Modifier.height(28.dp).clip(shape).background(if (on) c.orangeSoft else c.bg)
                .border(UmbraDimens.borderW, if (on) c.orange else c.border, shape).padding(horizontal = 11.dp, vertical = 6.dp),
        )
    }
}

/** 收集一个 StateFlow 的小糖：页面里 `val x by MoneyStore.entries.watch()`。 */
@Composable
fun <T> kotlinx.coroutines.flow.StateFlow<T>.watch(): androidx.compose.runtime.State<T> = collectAsState()

/** 页面间距常量（iOS sp4 = 12）。 */
val MoneyGap: Dp = UmbraDimens.sp4
