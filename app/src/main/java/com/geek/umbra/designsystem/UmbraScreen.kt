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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
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
fun UmbraTopBarIcon(
    icon: String, contentDescription: String, onClick: () -> Unit, tint: Color = UmbraTheme.colors.text, enabled: Boolean = true,
    size: androidx.compose.ui.unit.Dp = 20.dp, strokeWidth: Float = 1.9f,
) {
    Box(
        Modifier.size(UmbraDimens.tapMin).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { UmbraIcon(icon, if (enabled) tint else UmbraTheme.colors.faint, size = size, strokeWidth = strokeWidth, contentDescription = contentDescription) }
}

/** 推入页的返回钮：Lucide arrow-left、22、stroke 2、热区 48（设计侧 199 答复第 3 条）。 */
@Composable
fun UmbraBackButton(onClick: () -> Unit) =
    UmbraTopBarIcon(UmbraExtraIcons.arrowLeft, "返回", onClick, size = 22.dp, strokeWidth = 2f)

/**
 * 顶栏。设计侧 199 答复第 2 条：
 * - 根页：滚动 0 时透明、无字；内容滚过 14→58dp 之间，card 底、底边 1px borderSoft、17/600 标题**同步线性渐显**；
 *   标题左对齐、左距 16。状态栏那一条跟着顶栏一起上色（edge-to-edge）。
 * - 推入页：从一开始就是实底，返回箭头后跟单行截断的标题。
 * 不用 M3 LargeTopAppBar：它的折叠观感和设计稿差得更远。
 */
@Composable
private fun UmbraTopBar(
    title: String,
    progress: Float,
    root: Boolean,
    leading: (@Composable () -> Unit)?,
    onBack: (() -> Unit)?,
    titleContent: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit,
) {
    val c = UmbraTheme.colors
    Column(Modifier.fillMaxWidth().background(c.card.copy(alpha = progress)).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                leading != null -> leading()
                onBack != null -> UmbraBackButton(onBack)
                else -> Spacer(Modifier.width(12.dp))
            }
            Box(Modifier.weight(1f)) {
                when {
                    titleContent != null -> titleContent()
                    else -> Text(
                        title, color = c.text.copy(alpha = if (root) progress else 1f),
                        style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSoft.copy(alpha = progress)))
    }
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

/**
 * 锚定小弹框（设计侧 199 答复第 5 条）：card 底、圆角 12、阴影 0 8 24 黑 13%、行高 48、最小宽 180；
 * 删除类项用 dangerText（但它永远不是最终执行，点了还要过确认弹窗）。各模块的长按 / ⋯ 菜单都用这一对。
 */
@Composable
fun UmbraDropdown(expanded: Boolean, onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    DropdownMenu(
        expanded = expanded, onDismissRequest = onDismiss,
        containerColor = UmbraTheme.colors.card,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        shadowElevation = 8.dp, tonalElevation = 0.dp,
        modifier = Modifier.widthIn(min = 180.dp),
        content = content,
    )
}

@Composable
fun UmbraDropdownItem(label: String, danger: Boolean = false, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, color = if (danger) UmbraTheme.colors.dangerText else UmbraTheme.colors.text, style = TextStyle(fontSize = 15.sp)) },
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp),
    )
}

/** 「⋯」菜单。 */
@Composable
fun UmbraMoreMenu(items: List<UmbraMenuItem>) {
    if (items.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        UmbraTopBarIcon(UmbraExtraIcons.more, "更多", { open = true })
        UmbraDropdown(expanded = open, onDismiss = { open = false }) {
            items.forEach { item ->
                UmbraDropdownItem(item.label, danger = item.danger) { open = false; item.onClick() }
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
    val root = onBack == null && leading == null
    val density = androidx.compose.ui.platform.LocalDensity.current
    // 根页顶栏渐显进度：内容滚过 14→58dp 之间线性 0→1；第一项（大标题）滚出去之后恒为 1。
    val progress by remember(listState, root) {
        androidx.compose.runtime.derivedStateOf {
            if (!root) 1f
            else if (listState.firstVisibleItemIndex > 0) 1f
            else with(density) { ((listState.firstVisibleItemScrollOffset.toDp().value - 14f) / 44f).coerceIn(0f, 1f) }
        }
    }
    Column(
        modifier.fillMaxSize().background(c.bg)
            .pointerInput(Unit) { detectTapGestures(onTap = { focus.clearFocus() }) },
    ) {
        UmbraTopBar(title, progress, root, leading, onBack, actions = actions)
        topContent?.invoke()
        val list: @Composable () -> Unit = {
            LazyColumn(
                Modifier.fillMaxSize(), state = listState, contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(spacing),
            ) {
                if (root) {
                    item(key = "__title") { UmbraPageTitle(title, Modifier.padding(start = 0.dp, bottom = 4.dp)) }
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
    Column(modifier.fillMaxSize().background(c.bg)) {
        // 自排版的页（聊天、全屏编辑器）都是推入页形态：顶栏一开始就是实底
        UmbraTopBar(title, 1f, root = false, leading = leading, onBack = onBack, titleContent = titleContent, actions = actions)
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

/** 未使用的系统栏 inset 占位，避免各页自己算。 */
val NoInsets = WindowInsets(0, 0, 0, 0)

@Suppress("unused")
private fun Modifier.navBars() = this.navigationBarsPadding()
