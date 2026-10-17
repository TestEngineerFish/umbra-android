package com.geek.umbra.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 顶栏右侧「⋯」菜单里的一项。`danger` 画红字 —— 但**永远不是最终执行**，调用方必须再过确认弹窗。 */
data class UmbraMenuItem(val label: String, val danger: Boolean = false, val onClick: () -> Unit)

/** 纯图标的顶栏按钮。读屏名称必填 —— 纯图标按钮没有文字可读（iOS swiftcheck「读屏名称」那一件）。 */
@Composable
fun UmbraTopBarIcon(icon: String, contentDescription: String, onClick: () -> Unit, tint: Color = UmbraTheme.colors.text, enabled: Boolean = true) {
    Box(
        Modifier.size(UmbraDimens.tapMin).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { UmbraIcon(icon, if (enabled) tint else UmbraTheme.colors.faint, size = 20.dp, contentDescription = contentDescription) }
}

/** 顶栏文字按钮（「保存」「取消」）。 */
@Composable
fun UmbraTopBarText(text: String, onClick: () -> Unit, enabled: Boolean = true, strong: Boolean = false) {
    val c = UmbraTheme.colors
    Box(
        Modifier.height(UmbraDimens.tapMin).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, color = if (!enabled) c.faint else if (strong) c.orangeText else c.text,
            style = TextStyle(fontSize = 16.sp, fontWeight = if (strong) FontWeight.SemiBold else UmbraType.weight560),
        )
    }
}

/** 「⋯」菜单。 */
@Composable
fun UmbraMoreMenu(items: List<UmbraMenuItem>) {
    if (items.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        UmbraTopBarIcon(UmbraExtraIcons.more, "更多", { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = UmbraTheme.colors.card) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label, color = if (item.danger) UmbraTheme.colors.dangerText else UmbraTheme.colors.text) },
                    onClick = { open = false; item.onClick() },
                )
            }
        }
    }
}

/**
 * 页面骨架 —— 所有页面（根页、推入页）一律用它，对应 iOS 的 UmbraScreen。管五件事：
 * 顶栏（推入页带返回）、可滚内容、下拉刷新、底部动作条、点空白收键盘。
 *
 * 根页（[onBack] 为 null）在内容最上方画 27/650 的大标题，推入页把标题放进顶栏 ——
 * 对应 iOS 的「根页原生大标题、推入页小标题」。Android 没有大标题折叠的系统件，这里不做渐显，
 * 交 Claude Design 裁定（见 docs/design 交办单）。
 *
 * 内容是 LazyColumn：Umbra 的页面绝大多数是列表，统一用懒加载，长列表不会一次性组合。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UmbraScreen(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    leading: (@Composable () -> Unit)? = null,
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(start = UmbraDimens.pagePadX, end = UmbraDimens.pagePadX, top = 4.dp, bottom = 24.dp),
    spacing: androidx.compose.ui.unit.Dp = UmbraDimens.groupGap,
    topContent: (@Composable () -> Unit)? = null,
    bottomBar: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val c = UmbraTheme.colors
    val focus = LocalFocusManager.current
    Column(
        modifier.fillMaxSize().background(c.bg).statusBarsPadding()
            .pointerInput(Unit) { detectTapGestures(onTap = { focus.clearFocus() }) },
    ) {
        // 顶栏
        Row(
            Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                leading != null -> leading()
                onBack != null -> UmbraTopBarIcon(UmbraIconPaths.chevronLeft, "返回", onBack)
                else -> Spacer(Modifier.width(12.dp))
            }
            Box(Modifier.weight(1f)) {
                if (onBack != null || leading != null) {
                    Text(title, color = c.text, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
        topContent?.invoke()
        val list: @Composable () -> Unit = {
            LazyColumn(
                Modifier.fillMaxSize(), state = listState, contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(spacing),
            ) {
                if (onBack == null && leading == null) {
                    item(key = "__title") { UmbraPageTitle(title, Modifier.padding(start = 4.dp, bottom = 4.dp)) }
                }
                content()
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (onRefresh != null) {
                PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) { list() }
            } else list()
        }
        if (bottomBar != null) {
            Box(Modifier.fillMaxWidth().imePadding()) { bottomBar() }
        }
    }
}

/** 非列表形态的页面骨架（聊天页、全屏编辑器）：只管顶栏与底色，内容自己排。 */
@Composable
fun UmbraPlainScreen(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    titleContent: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    val c = UmbraTheme.colors
    Column(modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                leading != null -> leading()
                onBack != null -> UmbraTopBarIcon(UmbraIconPaths.chevronLeft, "返回", onBack)
                else -> Spacer(Modifier.width(12.dp))
            }
            Box(Modifier.weight(1f)) {
                titleContent?.invoke() ?: Text(title, color = c.text, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

/** 未使用的系统栏 inset 占位，避免各页自己算。 */
val NoInsets = WindowInsets(0, 0, 0, 0)

@Suppress("unused")
private fun Modifier.navBars() = this.navigationBarsPadding()
