// 首屏三态（iOS: DesignSystem/UmbraStates.swift `UmbraScreenPhase` + `umbraScreenPhase`）：骨架 / 整屏错误卡 / 内容（离线时顶上挂离线条）。
//
// 从前任务（ScreenPhase）、知识库（KbPhase）、记账（MoneyScreenPhase）各抄了一份判据和两块件，判据逐字一致，
// 件的细节已经开始分叉（记账的离线条没有「重试」、「检查服务端」有的 go 有的 jump）—— 收成这一份（开发文档第五节技术债）。
// 骨架、错误卡、错误条这三件视觉基元仍在 UmbraPrimitives.kt；这里只放「读页面标志、决定画哪件」的那一层，理由同 iOS 文件头。
package com.geek.umbra.designsystem

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.geek.umbra.R

/** 这一屏该画什么。 */
sealed interface UmbraScreenPhase {
    data object Skeleton : UmbraScreenPhase
    /** 整屏错误卡；offline 决定第二段说「够不着」还是「够得着但没给数据」。 */
    data class Failed(val offline: Boolean) : UmbraScreenPhase
    /** 有内容可看（含拉完确实是空的）；offline 时顶部挂离线条、内容压 .6。 */
    data class Content(val offline: Boolean) : UmbraScreenPhase

    /** 内容态且离线：顶上该挂离线条。 */
    val showsOfflineStrip: Boolean get() = this is Content && offline

    companion object {
        /**
         * 顺序是事故换来的：有内容永远先给内容（断网时最该顶用的是手上这份）；其次没内容 + 正在拉 → 骨架；
         * 最后没内容 + 不在拉 + 没成功过 → 错误卡。loadedOnce 不能用 isEmpty 代替 —— 真的空列表该画空态。
         * loading 必须是**首屏那一次**（firstLoading），不能传轮询里每轮都翻 true 的那个，否则空账号每轮闪骨架。
         * iOS: UmbraStates.swift:56
         */
        fun resolve(hasContent: Boolean, loading: Boolean, loadedOnce: Boolean, offline: Boolean): UmbraScreenPhase = when {
            hasContent -> Content(offline)
            loading -> Skeleton
            !loadedOnce -> Failed(offline)
            else -> Content(offline)
        }
    }
}

/**
 * 错误卡第二颗钮「检查服务端」要去的地方。设计系统不直接引用路由（视觉基元层反向依赖导航会把它钉死在这个 App 上），
 * 由 AppShell 注入一次；没注入（分享 Activity 这类不在主壳里的界面）时这颗钮不画。iOS: UmbraStates.swift:659 `umbraCheckServer`
 */
object UmbraStates {
    var checkServer: (() -> Unit)? = null
}

/**
 * 整屏错误卡（三态的 failed 档）。[title] 默认「拿不到数据」，这一屏有更准的说法才传；
 * 第二段分「够不着」（查网络）和「够得着但没给数据」（查地址和令牌）。
 * [checkServer] = false：只给「重试」一颗（任务详情照 iOS 就是这样 —— 它不挂整页修饰符，错误卡只带重试）。
 * [body]：这一屏有更准的原因（记账设置那段「为什么拿不到就不画」、存储用量服务端给的那句）才传，替掉通用的两句。
 */
@Composable
fun UmbraPhaseFailedCard(
    offline: Boolean,
    retry: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    checkServer: Boolean = true,
    body: String? = null,
) {
    val actions = buildList {
        add(stringResource(R.string.state_retry) to retry)
        if (checkServer) UmbraStates.checkServer?.let { add(stringResource(R.string.state_checkServer) to it) }
    }
    UmbraErrorCard(
        title = title ?: stringResource(R.string.state_failed_title),
        body = body ?: stringResource(if (offline) R.string.state_failed_reason_offline else R.string.state_failed_reason_server),
        actions = actions,
        modifier = modifier,
    )
}

/**
 * 离线条（错误卡的 edge 形取 warning 档，不新造第四形）。[live]：**没有本地缓存、在线优先**的页面（任务、灵感、知识库、记账）
 * 说「下面是刚才拿到的内容」；有本地缓存的说「下面是上次同步到的内容」。
 */
@Composable
fun UmbraOfflineStrip(retry: () -> Unit, live: Boolean, modifier: Modifier = Modifier) {
    val text = stringResource(R.string.state_offline_title) + " · " +
        stringResource(if (live) R.string.state_offline_reason_live else R.string.state_offline_reason)
    UmbraErrorStrip(text, modifier, tone = UmbraTone.Warning, action = stringResource(R.string.state_retry) to retry)
}

/** 给 [UmbraScreen] 的 `topContent`：内容态且离线才挂离线条，否则 null（不占位）。离线条钉在列表上方、不跟着滚。 */
fun umbraPhaseTop(phase: UmbraScreenPhase, live: Boolean, retry: () -> Unit): (@Composable () -> Unit)? =
    if (phase.showsOfflineStrip) ({ UmbraOfflineStrip(retry, live) }) else null

/**
 * 三态的前两态画进列表（骨架 / 错误卡），第三态交给 [content]。离线条另由 [umbraPhaseTop] 挂到页顶。
 * [failedModifier] 给错误卡外边距（列表本身没有左右留白的页面要补）。
 */
fun LazyListScope.umbraScreenPhase(
    phase: UmbraScreenPhase,
    retry: () -> Unit,
    failedTitle: String? = null,
    failedModifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    when (phase) {
        UmbraScreenPhase.Skeleton -> item(key = "__skeleton") { UmbraSkeleton() }
        is UmbraScreenPhase.Failed -> item(key = "__failed") { UmbraPhaseFailedCard(phase.offline, retry, failedModifier, failedTitle) }
        is UmbraScreenPhase.Content -> content()
    }
}
