// 通用视觉基元。尺寸逐个照抄 iOS DesignSystem/UmbraPrimitives.swift、UmbraStates.swift。
// 只有被两个以上功能用到的才放这里；某个功能自己的卡片放回 feature/<功能>/。
package com.geek.umbra.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Pill = RoundedCornerShape(percent = 50)

// MARK: - 卡片

/** 内容卡：card 底、1px border、圆角 18、内边距 13。**内容卡一律不透明、无阴影**（Umbra 基调）。 */
@Composable
fun UmbraCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(UmbraDimens.cardPad),
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(UmbraDimens.radiusCard)
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.card)
            .border(UmbraDimens.borderW, c.border, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
    ) { content() }
}

/** 分组卡：多行共用一张卡，行间 borderSoft 发丝线（由 [UmbraRowDivider] 画）。 */
@Composable
fun UmbraGroupCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(UmbraDimens.radiusCard)
    Column(
        modifier.fillMaxWidth().clip(shape).background(c.card).border(UmbraDimens.borderW, c.border, shape),
    ) { content() }
}

@Composable
fun UmbraRowDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(UmbraTheme.colors.borderSoft))
}

// MARK: - 文字

@Composable
fun UmbraPageTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, color = UmbraTheme.colors.text, style = UmbraType.pageTitle)

/** 分区标签：11.5/600 字距、muted。 */
@Composable
fun UmbraSectionLabel(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text, color = UmbraTheme.colors.muted,
            style = UmbraType.fieldLabel.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
        )
        if (trailing != null) {
            Spacer(Modifier.width(6.dp))
            Text(trailing, color = UmbraTheme.colors.faint, style = UmbraType.meta.copy(fontFamily = FontFamily.Monospace))
        }
    }
}

@Composable
fun UmbraFieldLabel(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, color = UmbraTheme.colors.muted, style = UmbraType.fieldLabel)

// MARK: - 列表行

/**
 * 列表行：主文 16/560 + 副文 13 muted；左右 12、上下 9；最小高 48（有副文 60）；可选 chevron。
 * 主文最多 2 行省略 —— 「点行进详情看全文」是设计规则。
 */
@Composable
fun UmbraListRow(
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val c = UmbraTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = if (sub != null) UmbraDimens.rowMinHSub else UmbraDimens.rowMinH)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading(); Spacer(Modifier.width(UmbraDimens.sp4))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = c.text, style = UmbraType.rowTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (sub != null) {
                Spacer(Modifier.height(2.dp))
                Text(sub, color = c.muted, style = UmbraType.rowSub, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(UmbraDimens.sp3)); trailing()
        }
        if (chevron) {
            Spacer(Modifier.width(6.dp))
            UmbraIcon(UmbraIconPaths.chevronRight, c.faint, size = 16.dp)
        }
    }
}

// MARK: - 按钮

enum class UmbraButtonKind { Primary, Secondary, DangerOutline, DangerSolid }

/**
 * 胶囊按钮，默认高 48。`enabled=false` 统一画成 disabled（chip 底 + faint 字），不另设 kind ——
 * iOS 那边 disabled 是一种 kind，结果出现过「看着灰、点了照样触发」的不一致，这里两件事绑在一起。
 */
@Composable
fun UmbraButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: UmbraButtonKind = UmbraButtonKind.Primary,
    enabled: Boolean = true,
    height: Dp = UmbraDimens.buttonH,
    icon: String? = null,
    external: Boolean = false,
) {
    val c = UmbraTheme.colors
    val (bg, fg, border) = when {
        !enabled -> Triple(c.chip, c.faint, null)
        kind == UmbraButtonKind.Primary -> Triple(c.orange, c.onAccent, null)
        kind == UmbraButtonKind.Secondary -> Triple(c.card, c.text, c.borderSoft)
        kind == UmbraButtonKind.DangerOutline -> Triple(c.card, c.dangerText, c.borderSoft)
        else -> Triple(c.danger, c.onDanger, null)
    }
    val style = if (kind == UmbraButtonKind.Primary) {
        UmbraType.button.copy(fontWeight = FontWeight.SemiBold)
    } else UmbraType.button.copy(fontSize = 15.5.sp)
    Row(
        modifier
            .heightIn(min = height)
            .clip(Pill)
            .background(bg)
            .then(if (border != null) Modifier.border(1.dp, border, Pill) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            UmbraIcon(icon, fg, size = 16.dp); Spacer(Modifier.width(6.dp))
        }
        Text(text, color = fg, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (external) {
            Spacer(Modifier.width(5.dp)); UmbraIcon(UmbraIconPaths.externalLink, fg, size = 12.dp)
        }
    }
}

/** 底部动作条：左右 16、上 10、下 14，间距 8，bg 底，顶部 1px borderSoft。 */
@Composable
fun UmbraBottomBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val c = UmbraTheme.colors
    Column(modifier.fillMaxWidth().background(c.bg)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSoft))
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/** 文字动作钮（条子 / 卡片里那颗）：12.5/600，固定高 44。iOS 只有一处定义（UmbraStripAction），这里同。 */
@Composable
fun UmbraStripAction(text: String, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.heightIn(min = UmbraDimens.tapMin).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color, style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold))
    }
}

// MARK: - 分段控件

data class UmbraSegment(val label: String, val count: Int? = null)

/**
 * 分段控件：视觉高 30、热区 44；card 胶囊滑块，切换 .22s 平移。字 13.5，选中 600 / 未选 560。
 */
@Composable
fun UmbraSegmentedControl(
    segments: List<UmbraSegment>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = UmbraTheme.colors
    BoxWithConstraints(
        modifier.fillMaxWidth().heightIn(min = UmbraDimens.tapMin),
        contentAlignment = Alignment.Center,
    ) {
        val segW = maxWidth / segments.size.coerceAtLeast(1)
        val x by animateDpAsState(segW * selected, tween(UmbraMotion.slider), label = "seg")
        Box(Modifier.fillMaxWidth().height(UmbraDimens.segmentH).clip(Pill).background(c.chip)) {
            Box(
                Modifier.offset(x = x).width(segW).fillMaxHeight().padding(2.dp).clip(Pill).background(c.card),
            )
            Row(Modifier.fillMaxWidth().fillMaxHeight()) {
                segments.forEachIndexed { i, s ->
                    val on = i == selected
                    Row(
                        Modifier.weight(1f).fillMaxHeight().clickable(role = Role.Tab) { onSelect(i) },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            s.label, color = if (on) c.text else c.muted,
                            style = TextStyle(fontSize = 13.5.sp, fontWeight = if (on) FontWeight.SemiBold else UmbraType.weight560),
                        )
                        if (s.count != null) {
                            Spacer(Modifier.width(4.dp))
                            Text(
                                s.count.toString(), color = (if (on) c.text else c.muted).copy(alpha = 0.7f),
                                style = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                            )
                        }
                    }
                }
            }
        }
    }
}

// MARK: - 状态

/** 七种状态。状态永远「图标 + 文字」，不只靠颜色（设计规则）。 */
enum class UmbraStatus(val label: String, val icon: String) {
    Running("执行中", UmbraIconPaths.spinnerArc),
    Done("已完成", UmbraIconPaths.check),
    AwaitingReview("等你授权", UmbraIconPaths.alertTriangle),
    Failed("失败", UmbraIconPaths.xCircle),
    Pending("待执行", UmbraIconPaths.clock),
    Cancelled("已取消", UmbraIconPaths.stopCircle),
    Suspended("已挂起", UmbraIconPaths.pauseCircle);

    /** 文字色走 *Text 那支。 */
    @Composable fun fg(): Color = UmbraTheme.colors.let {
        when (this) {
            Running -> it.orangeText; Done -> it.success
            AwaitingReview, Suspended -> it.warningText; Failed -> it.dangerText
            Pending, Cancelled -> it.muted
        }
    }

    @Composable fun soft(): Color = UmbraTheme.colors.let {
        when (this) {
            Running -> it.orangeSoft; Done -> it.successSoft
            AwaitingReview, Suspended -> it.warningSoft; Failed -> it.dangerSoft
            Pending, Cancelled -> it.chip
        }
    }

    @Composable fun bar(): Color = UmbraTheme.colors.let {
        when (this) {
            Running -> it.orange; Done -> it.success
            AwaitingReview, Suspended -> it.warning; Failed -> it.danger
            Pending, Cancelled -> it.muted
        }
    }
}

@Composable
fun UmbraStatusBadge(status: UmbraStatus, modifier: Modifier = Modifier, compact: Boolean = true) {
    val fg = status.fg()
    Row(
        modifier.clip(Pill).background(status.soft())
            .padding(horizontal = if (compact) 8.dp else 9.dp, vertical = if (compact) 2.dp else 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val sz = if (compact) 11.dp else 12.dp
        if (status == UmbraStatus.Running) UmbraSpinningIcon(status.icon, fg, sz) else UmbraIcon(status.icon, fg, size = sz, strokeWidth = 2.1f)
        Spacer(Modifier.width(4.dp))
        Text(status.label, color = fg, style = TextStyle(fontSize = if (compact) 11.5.sp else 12.5.sp, fontWeight = FontWeight.SemiBold))
    }
}

/** 状态点：7px，开 = 实心，关 = 1.5 描边空心。 */
@Composable
fun UmbraStatusDot(on: Boolean, color: Color, modifier: Modifier = Modifier) {
    val m = modifier.size(UmbraDimens.statusDot).clip(CircleShape)
    Box(if (on) m.background(color) else m.border(1.5.dp, color, CircleShape))
}

@Composable
fun UmbraProgressBar(progress: Float, color: Color, modifier: Modifier = Modifier, height: Dp = UmbraDimens.progressH) {
    Box(modifier.fillMaxWidth().height(height).clip(Pill).background(UmbraTheme.colors.track)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().clip(Pill).background(color))
    }
}

/** 标签胶囊：高 27、左右 10、字 12.5；选中 orangeSoft 底 + orange 描边 + orangeText；热区补到 44。 */
@Composable
fun UmbraTagPill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, count: Int? = null) {
    val c = UmbraTheme.colors
    val bg by animateColorAsState(if (selected) c.orangeSoft else c.chip, tween(UmbraMotion.tint), label = "pill")
    Box(modifier.heightIn(min = UmbraDimens.tapMin).clickable(role = Role.Checkbox, onClick = onClick), contentAlignment = Alignment.Center) {
        Row(
            Modifier.height(27.dp).clip(Pill).background(bg)
                .border(1.dp, if (selected) c.orange else Color.Transparent, Pill).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, color = if (selected) c.orangeText else c.text, style = TextStyle(fontSize = 12.5.sp, fontWeight = UmbraType.weight560))
            if (count != null) {
                Spacer(Modifier.width(4.dp))
                Text(count.toString(), color = if (selected) c.orangeText else c.muted, style = TextStyle(fontSize = 11.5.sp, fontFamily = FontFamily.Monospace))
            }
        }
    }
}

/** 横滑筛选胶囊，间距 7。 */
@Composable
fun UmbraFilterChips(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(UmbraDimens.sp2),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** 计数角标：badgeRed 胶囊，11.5/600 白字，最小 19×19，>99 显示 99+。 */
@Composable
fun UmbraCountBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(
        modifier.defaultMinSize(19.dp, 19.dp).clip(Pill).background(UmbraTheme.colors.badgeRed).padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (count > 99) "99+" else "$count", color = Color.White, style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold))
    }
}

// MARK: - 输入

/**
 * 单行输入框：高 40（可配）、圆角 10、bg 底、1px border；出错描边换 danger。
 * 用 BasicTextField 而不是 M3 TextField：M3 的浮动标签和下划线和设计稿对不上，改样式比自己画还长。
 */
@Composable
fun UmbraTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    error: Boolean = false,
    singleLine: Boolean = true,
    minHeight: Dp = 40.dp,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    mono: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(UmbraDimens.radiusControl)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        maxLines = maxLines,
        textStyle = TextStyle(fontSize = 15.sp, color = c.text, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default),
        cursorBrush = SolidColor(c.orange),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = minHeight).clip(shape).background(c.bg)
                    .border(1.dp, if (error) c.danger else c.border, shape).padding(horizontal = 12.dp, vertical = 9.dp),
                contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
            ) {
                if (value.isEmpty()) Text(placeholder, color = c.faint, style = TextStyle(fontSize = 15.sp))
                inner()
            }
        },
    )
}

/** 搜索框：高 38、chip 底、圆角 12、图标 16、字 15；清空钮 28（热区 44）。 */
@Composable
fun UmbraSearchField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "搜索") {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(UmbraDimens.radiusInput)
    BasicTextField(
        value = value, onValueChange = onValueChange, singleLine = true,
        textStyle = TextStyle(fontSize = 15.sp, color = c.text), cursorBrush = SolidColor(c.orange),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().height(38.dp).clip(shape).background(c.chip).padding(start = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UmbraIcon(UmbraIconPaths.search, c.muted, size = 16.dp)
                Spacer(Modifier.width(7.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, color = c.faint, style = TextStyle(fontSize = 15.sp))
                    inner()
                }
                if (value.isNotEmpty()) {
                    Box(Modifier.size(UmbraDimens.tapMin).clickable(role = Role.Button) { onValueChange("") }, contentAlignment = Alignment.Center) {
                        UmbraIcon(UmbraIconPaths.xCircle, c.faint, size = 16.dp, contentDescription = "清空")
                    }
                }
            }
        },
    )
}

// MARK: - 开关

/** 开关：44×26 轨道 + 22 白圆；开 orange、关 track；热区上下补到 44。 */
@Composable
fun UmbraSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = UmbraTheme.colors
    val track by animateColorAsState(if (checked) c.orange else c.track, tween(UmbraMotion.tint), label = "sw")
    val x by animateDpAsState(if (checked) 20.dp else 2.dp, tween(UmbraMotion.swipe), label = "swx")
    Box(
        modifier.heightIn(min = UmbraDimens.tapMin)
            .clickable(enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(44.dp, 26.dp).clip(Pill).background(track.copy(alpha = if (enabled) 1f else 0.5f))) {
            Box(Modifier.offset(x = x, y = 2.dp).size(22.dp).clip(CircleShape).background(Color.White))
        }
    }
}

// MARK: - 状态页

/**
 * 空态：图标块 64 圆角 16 图标 28；标题 15/560，hint 13；按钮 44 高最大宽 200；内边距 34/60。
 * [secondary] 是第二颗钮（次要样式，排在主钮下面）—— 深链落到已删对象那一页要两颗：去回收站 / 回到列表（批次 290 §五 A）。
 */
@Composable
fun UmbraEmptyState(
    icon: String,
    title: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    secondary: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    val c = UmbraTheme.colors
    Column(
        modifier.fillMaxWidth().padding(horizontal = 34.dp, vertical = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(c.chip), contentAlignment = Alignment.Center) {
            UmbraIcon(icon, c.muted, size = 28.dp)
        }
        Spacer(Modifier.height(14.dp))
        Text(title, color = c.text, style = TextStyle(fontSize = 15.sp, fontWeight = UmbraType.weight560))
        if (hint != null) {
            Spacer(Modifier.height(6.dp))
            Text(hint, color = c.muted, style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        if (action != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            UmbraButton(action, onAction, Modifier.widthIn(max = 200.dp), height = 44.dp)
        }
        if (secondary != null && onSecondary != null) {
            Spacer(Modifier.height(10.dp))
            UmbraButton(secondary, onSecondary, Modifier.widthIn(max = 200.dp), kind = UmbraButtonKind.Secondary, height = 44.dp)
        }
    }
}

/** 骨架：3 组，每组两条（宽 62/84、48/72、55/66%），高 14/11 圆角 7，track 色，**静止不呼吸**。 */
@Composable
fun UmbraSkeleton(modifier: Modifier = Modifier) {
    val c = UmbraTheme.colors
    val widths = listOf(0.62f to 0.84f, 0.48f to 0.72f, 0.55f to 0.66f)
    Column(modifier.fillMaxWidth().padding(UmbraDimens.pagePadX), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        widths.forEach { (a, b) ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth(a).height(14.dp).clip(RoundedCornerShape(7.dp)).background(c.track))
                Box(Modifier.fillMaxWidth(b).height(11.dp).clip(RoundedCornerShape(7.dp)).background(c.track))
            }
        }
    }
}

enum class UmbraTone { Warning, Danger, Neutral }

/**
 * 错误卡（card 形态）：内边距 16、圆角 18、soft 底 + 1px 实色描边；标题 13.5/600、正文 13；
 * 按钮 44 高、圆角 10、card 底、15/560，两颗各占一半。错误三段式：发生了什么 / 为什么 / 能做什么。
 */
@Composable
fun UmbraErrorCard(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    tone: UmbraTone = UmbraTone.Danger,
    actions: List<Pair<String, () -> Unit>> = emptyList(),
    raw: String? = null,
) {
    val c = UmbraTheme.colors
    val (soft, solid, ink) = when (tone) {
        UmbraTone.Danger -> Triple(c.dangerSoft, c.danger, c.dangerText)
        UmbraTone.Warning -> Triple(c.warningSoft, c.warning, c.warningText)
        UmbraTone.Neutral -> Triple(c.chip, c.border, c.text)
    }
    val shape = RoundedCornerShape(UmbraDimens.radiusCard)
    var showRaw by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().clip(shape).background(soft).border(1.dp, solid.copy(alpha = 0.6f), shape).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UmbraIcon(UmbraIconPaths.alertOctagon, ink, size = 15.dp)
            Spacer(Modifier.width(6.dp))
            Text(title, color = ink, style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold))
        }
        if (body != null) {
            Spacer(Modifier.height(6.dp))
            Text(body, color = c.text, style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp))
        }
        if (raw != null) {
            UmbraStripAction(if (showRaw) "收起原始返回" else "查看原始返回", c.muted, { showRaw = !showRaw })
            if (showRaw) {
                Text(
                    raw, color = c.muted,
                    style = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 96.dp).clip(RoundedCornerShape(8.dp)).background(c.track).padding(8.dp),
                )
            }
        }
        if (actions.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                actions.forEach { (label, act) ->
                    Box(
                        Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(10.dp)).background(c.card)
                            .clickable(role = Role.Button, onClick = act),
                        contentAlignment = Alignment.Center,
                    ) { Text(label, color = c.text, style = TextStyle(fontSize = 15.sp, fontWeight = UmbraType.weight560)) }
                }
            }
        }
    }
}

/** 错误条（strip 形态）：alertOctagon 14 + 12.5 字，最小高 44。离线条 / 页内轻提示用。 */
@Composable
fun UmbraErrorStrip(text: String, modifier: Modifier = Modifier, tone: UmbraTone = UmbraTone.Warning, action: Pair<String, () -> Unit>? = null) {
    val c = UmbraTheme.colors
    val (soft, ink) = if (tone == UmbraTone.Danger) c.dangerSoft to c.dangerText else c.warningSoft to c.warningText
    Row(
        modifier.fillMaxWidth().heightIn(min = UmbraDimens.tapMin).background(soft).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UmbraIcon(UmbraIconPaths.alertOctagon, ink, size = 14.dp)
        Spacer(Modifier.width(7.dp))
        Text(text, color = ink, style = TextStyle(fontSize = 12.5.sp), modifier = Modifier.weight(1f))
        if (action != null) UmbraStripAction(action.first, ink, action.second)
    }
}

// MARK: - Hero 卡

/** Hero 卡：48 图标块（圆角 18、chip 底、图标 23）+ 名字 17/560 + 徽标 11/600 + 副行 12；内边距 14。 */
@Composable
fun UmbraHeroCard(
    icon: String,
    name: String,
    modifier: Modifier = Modifier,
    badge: String? = null,
    badgeOn: Boolean = false,
    sub: String? = null,
    subMono: Boolean = false,
) {
    val c = UmbraTheme.colors
    UmbraCard(modifier, padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UmbraIconBlock(icon, block = 48.dp, icon = 23.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, color = c.text, style = TextStyle(fontSize = 17.sp, fontWeight = UmbraType.weight560), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (badge != null) {
                        Spacer(Modifier.width(7.dp))
                        Text(
                            badge, color = if (badgeOn) c.success else c.muted,
                            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.clip(Pill).background(if (badgeOn) c.successSoft else c.chip).padding(horizontal = 7.dp, vertical = 1.dp),
                        )
                    }
                }
                if (sub != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(sub, color = c.muted, style = TextStyle(fontSize = 12.sp, fontFamily = if (subMono) FontFamily.Monospace else FontFamily.Default), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** 软底小胶囊（「整理中」「已过时」这类）。 */
@Composable
fun UmbraSoftChip(text: String, fg: Color, bg: Color, modifier: Modifier = Modifier) {
    Text(
        text, color = fg, style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
        modifier = modifier.clip(Pill).background(bg).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
