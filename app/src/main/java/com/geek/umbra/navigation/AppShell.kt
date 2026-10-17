package com.geek.umbra.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraErrorCard
import com.geek.umbra.designsystem.UmbraButton
import com.geek.umbra.designsystem.UmbraFixedColors
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraScreen
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraTone
import com.geek.umbra.designsystem.UmbraType
import com.geek.umbra.feature.auth.AuthScreen
import com.geek.umbra.net.Gate
import com.geek.umbra.net.SessionGuardRules

/**
 * 外壳 —— 对应 iOS Navigation/AppShell.swift。
 *
 * 结构：底栏在外，每个 Tab 一条自己的返回栈（Navigation 3 的 NavDisplay 直接吃 Router 里的 SnapshotStateList）。
 * - 推入页不留底栏（iOS 用 setTabBarHidden，这里栈非空时不画底栏）；
 * - 路由拦截只在一处：[GuardedRoute]。`needsLogin && locked` 画「要登录」卡，不画页面；
 * - 登录门关着且没选单机时，整个外壳被登录屏换掉；
 * - 系统返回：先关弹层，再退栈，栈空时交给系统（退到桌面）。
 */
@Composable
fun AppShell() {
    val router = AppGraph.router
    val gate by AppGraph.auth.gate.collectAsState()
    val standalone = AppGraph.prefs.snapshot.collectAsState().value[com.geek.umbra.net.Prefs.K.standalone] ?: false
    val c = UmbraTheme.colors

    if (SessionGuardRules.showsAuthScreen(gate, standalone)) {
        AuthScreen(gate)
        Overlays()
        return
    }
    if (gate == Gate.Checking && !SessionGuardRules.showsTabsWhileChecking(AppGraph.prefs.signedOut, standalone)) {
        Box(Modifier.fillMaxSize().background(c.bg))
        return
    }

    val tab = router.tab
    val stack = router.stack(tab)
    Column(Modifier.fillMaxSize().background(c.bg)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // key(tab)：切 Tab 换一整个 NavDisplay，各 Tab 的栈互不干扰（栈本身存在 Router 里，不丢）
            key(tab) {
                val back = listOf<Route>(tab.root) + stack
                NavDisplay(
                    backStack = back,
                    onBack = { router.back() },
                    entryProvider = { r -> NavEntry(r) { GuardedRoute(r) } },
                )
            }
        }
        if (stack.isEmpty()) BottomBar(tab) { router.selectTab(it) }
    }
    com.geek.umbra.feature.remote.RemoteSessionHost()
    Overlays()
}

/** 路由拦截唯一入口。 */
@Composable
fun GuardedRoute(r: Route) {
    val locked by AppGraph.guard.locked.collectAsState()
    if (r.needsLogin && locked) NeedLoginScreen(r) else RouteScreen(r)
}

/** 「要登录」页：warning 档错误卡 +「去登录」（退出单机模式，登录屏盖上来）。 */
@Composable
fun NeedLoginScreen(r: Route) {
    val router = AppGraph.router
    val isRoot = Tab.entries.any { it.root == r }
    UmbraScreen(title = titleOf(r), onBack = if (isRoot) null else ({ router.back() })) {
        item {
            UmbraErrorCard(
                title = "这一页要登录后才能用",
                body = "它的数据在服务端。提醒、记账（生活账本）、灵感、常用语、保险箱不登录也能用。",
                tone = UmbraTone.Warning,
            )
        }
        item { UmbraButton("去登录", { AppGraph.auth.goToLogin() }, Modifier.fillMaxWidth()) }
    }
}

private fun titleOf(r: Route): String = when (r) {
    Route.ChatContacts -> "聊天"
    Route.TaskList -> "任务"
    Route.KbList -> "知识库"
    Route.MeDuty -> "执勤"
    Route.MeMemory -> "记忆"
    Route.MeDevices -> "设备与能力"
    else -> ""
}

/**
 * 底栏。iOS 稿是液态玻璃胶囊（系统私有渲染），Android 没有对等物 —— 这里画不透明的 Umbra 色底栏：
 * card 底、顶部 1px borderSoft、选中橙色、标签 10.5。形制交 Claude Design 裁定（docs/design 交办单）。
 */
@Composable
private fun BottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    val c = UmbraTheme.colors
    val badges = TabBadges.counts
    Column(Modifier.fillMaxWidth().background(c.card).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSoft))
        Row(Modifier.fillMaxWidth().height(58.dp), horizontalArrangement = Arrangement.SpaceAround) {
            Tab.entries.forEach { t ->
                val on = t == current
                val tint = if (on) c.orange else c.muted
                Column(
                    Modifier.weight(1f).height(58.dp).clickable(role = Role.Tab) { onSelect(t) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box {
                        UmbraIcon(t.icon, tint, size = 23.dp, strokeWidth = if (on) 2.1f else 1.9f)
                        val n = badges[t] ?: 0
                        if (n != 0) {
                            Box(Modifier.align(Alignment.TopEnd).padding(start = 14.dp)) {
                                if (n < 0) Box(Modifier.size(8.dp).clip(CircleShape).background(c.badgeRed))
                                else com.geek.umbra.designsystem.UmbraCountBadge(n)
                            }
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(t.label, color = tint, style = UmbraType.tabLabel.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal))
                }
            }
        }
    }
}

/**
 * Tab 角标。各模块往里写自己的数：聊天只要「有没有」（写 -1 画一颗点），提醒写逾期数，工具写需人处理数。
 */
object TabBadges {
    val counts = androidx.compose.runtime.mutableStateMapOf<Tab, Int>()
}

/** 弹窗 / 底部弹层 / 吐司 —— 全由 Router 驱动，一处画。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Overlays() {
    val router = AppGraph.router
    val c = UmbraTheme.colors
    val revokeNotice by AppGraph.auth.revokeNotice.collectAsState()

    router.sheets.forEach { sheet ->
        key(sheet.key) {
            val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { router.sheets.remove(sheet) },
                sheetState = state,
                containerColor = c.bg,
                shape = RoundedCornerShape(topStart = UmbraDimens.radiusSheet, topEnd = UmbraDimens.radiusSheet),
            ) {
                sheet.content { router.sheets.remove(sheet) }
            }
        }
    }

    router.alert?.let { a ->
        AlertDialog(
            onDismissRequest = { router.alert = null; a.onCancel?.invoke() },
            containerColor = c.card,
            shape = RoundedCornerShape(UmbraDimens.radiusDialog),
            title = { Text(a.title, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold), color = c.text) },
            text = a.message?.let { { Text(it, style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp), color = c.muted) } },
            confirmButton = {
                TextButton(onClick = { router.alert = null; a.onConfirm() }) {
                    Text(a.confirmLabel, color = if (a.destructive) c.dangerText else c.orangeText, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { router.alert = null; a.onCancel?.invoke() }) { Text(a.cancelLabel, color = c.text) }
            },
        )
    }

    if (revokeNotice && router.alert == null) {
        AlertDialog(
            onDismissRequest = { AppGraph.auth.revokeNotice.value = false },
            containerColor = c.card,
            title = { Text("登录已经失效了", color = c.text) },
            text = { Text("这台设备的登录被服务端收回了（改过密码、在别处登出，或会话到期）。重新登录后继续用。", color = c.muted) },
            confirmButton = { TextButton(onClick = { AppGraph.auth.revokeNotice.value = false }) { Text("知道了", color = c.orangeText) } },
        )
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        val t = router.toast
        AnimatedVisibility(t != null, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
            val toast = t ?: return@AnimatedVisibility
            Row(
                Modifier.navigationBarsPadding().padding(bottom = if (router.stack().isEmpty()) 84.dp else 32.dp, start = 24.dp, end = 24.dp)
                    .clip(RoundedCornerShape(50)).background(UmbraFixedColors.toastGlass).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(toast.text, color = Color.White, style = TextStyle(fontSize = 13.5.sp))
                if (toast.action != null) {
                    Spacer(Modifier.width(12.dp))
                    Box(
                        Modifier.height(30.dp).clip(RoundedCornerShape(50)).border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(50))
                            .clickable { router.toast = null; toast.action.invoke() }.padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(toast.actionLabel ?: "撤销", color = Color.White, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)) }
                }
            }
        }
    }
}
