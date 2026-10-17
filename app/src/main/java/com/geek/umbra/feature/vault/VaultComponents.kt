// 保险箱页面共用的小件：字段行（FieldRow）、分数环、记录图标、底部选择面板、输入框、卡片外壳。
// 尺寸照 iOS VaultKit.swift / VaultRecordIcon.swift / VaultViews.swift 抄；颜色字号一律从 designsystem 取。
package com.geek.umbra.feature.vault

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraFieldLabel
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraProgressBar
import com.geek.umbra.designsystem.UmbraRowDivider
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraType
import com.geek.umbra.app.AppGraph
import com.geek.umbra.navigation.UmbraSheet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 强度档 → 颜色（0 弱 danger / 1 中 warning / 2 强 success）。图标 / 条用实心色。 */
@Composable
fun strengthColor(level: Int): Color = UmbraTheme.colors.let { c ->
    when (level.coerceIn(0, 2)) { 0 -> c.danger; 1 -> c.warning; else -> c.success }
}

/** 强度档 → 文字色（走 *Text 那支）。 */
@Composable
fun strengthTextColor(level: Int): Color = UmbraTheme.colors.let { c ->
    when (level.coerceIn(0, 2)) { 0 -> c.dangerText; 1 -> c.warningText; else -> c.success }
}

/** 一段明文里把查找词标出来：其它命中卡浅橙底；当前那张实心橙底白字（同 PC 画布查找条）。⚠️ 只许拿非密文调它。 */
@Composable
fun markedText(text: String, mark: VaultFindMark?): AnnotatedString {
    val c = UmbraTheme.colors
    if (mark == null) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        for (r in VaultFindRules.ranges(text, mark.query)) {
            addStyle(
                if (mark.current) SpanStyle(background = c.orange, color = Color.White) else SpanStyle(background = c.orangeSoft),
                r.first, r.last + 1,
            )
        }
    }
}

/**
 * 字段行（iOS `UmbraFieldRow`）：标签 + 值（敏感值等宽 + 遮罩）+ 44 的显示 / 复制按钮。
 * - 敏感值显示 **8 秒后自动重新遮盖**；
 * - 复制反馈是**行内**「图标转对勾 + 已复制」1.5 秒；复制一律走敏感入口（不按 secret 分两路）。
 * ⚠️ 密文那几行不许传 [mark]（这里再兜一层：secret 为真一律不画高亮）。
 */
@Composable
fun VaultFieldRow(
    label: String,
    value: String,
    secret: Boolean = false,
    mono: Boolean = false,
    showStrength: Boolean = false,
    multiline: Boolean = false,
    mark: VaultFindMark? = null,
) {
    val c = UmbraTheme.colors
    var revealed by remember { mutableStateOf(false) }
    var revealTick by remember { mutableIntStateOf(0) }
    var copied by remember { mutableStateOf(false) }
    var copyTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(revealTick) { if (revealed) { delay(8_000); revealed = false } }
    LaunchedEffect(copyTick) { if (copied) { delay(1_500); copied = false } }
    val display = when {
        value.isEmpty() -> "—"
        secret && !revealed -> "•".repeat(VaultAudit.graphemeCount(value).coerceIn(6, 16))
        else -> value
    }
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp).padding(horizontal = 13.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            UmbraFieldLabel(label)
            Text(
                if (mark != null && !secret && value.isNotEmpty()) markedText(value, mark) else AnnotatedString(display),
                color = if (value.isEmpty()) c.faint else c.text,
                style = TextStyle(fontSize = 15.sp, lineHeight = 24.sp, fontFamily = if (mono || secret) FontFamily.Monospace else FontFamily.Default),
                maxLines = if (multiline) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
            )
            if (showStrength && value.isNotEmpty()) {
                val st = PasswordStrength.of(value)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    UmbraProgressBar(st.pct / 100f, strengthColor(st.level), Modifier.width(56.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("强度 ${PasswordStrength.label(st.level)}", color = strengthTextColor(st.level), style = TextStyle(fontSize = 11.5.sp, fontWeight = UmbraType.weight560))
                }
            }
        }
        if (secret && value.isNotEmpty()) {
            Box(Modifier.size(UmbraDimens.tapMin).clickable(role = Role.Button) { revealed = !revealed; revealTick++ }, contentAlignment = Alignment.Center) {
                UmbraIcon(if (revealed) UmbraIconPaths.eyeOff else UmbraIconPaths.eye, c.muted, size = 20.dp, contentDescription = if (revealed) "隐藏" else "显示")
            }
        }
        if (value.isNotEmpty()) {
            Column(
                Modifier.defaultMinSize(UmbraDimens.tapMin, UmbraDimens.tapMin)
                    .clickable(role = Role.Button) { VaultClipboard.copySensitive(value); copied = true; copyTick++ },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                val tint = if (copied) c.success else c.muted
                UmbraIcon(if (copied) UmbraIconPaths.check else UmbraIconPaths.copy, tint, size = 19.dp, contentDescription = if (copied) "已复制" else "复制")
                if (copied) Text("已复制", color = tint, style = TextStyle(fontSize = 9.5.sp, fontWeight = UmbraType.weight560))
            }
        }
    }
}

/** 一张圆角卡里的若干行，行间细线。 */
@Composable
fun VaultRowsCard(modifier: Modifier = Modifier, stroke: Color? = null, strokeWidth: Dp = UmbraDimens.borderW, rows: List<@Composable () -> Unit>) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(UmbraDimens.radiusCard)
    Column(modifier.fillMaxWidth().clip(shape).background(c.card).border(strokeWidth, stroke ?: c.border, shape)) {
        rows.forEachIndexed { i, r -> if (i > 0) UmbraRowDivider(); r() }
    }
}

/** 卡片外壳（不分行）。 */
@Composable
fun VaultCardBox(modifier: Modifier = Modifier, padding: Dp = 14.dp, content: @Composable ColumnScope.() -> Unit) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(UmbraDimens.radiusCard)
    Column(modifier.fillMaxWidth().clip(shape).background(c.card).border(UmbraDimens.borderW, c.border, shape).padding(padding), content = content)
}

/** 分数环：≥80 success、≥50 orange、其余 danger；三档尺寸（72 / 44 / 22）。 */
@Composable
fun VaultScoreRing(score: Int, size: Dp = 72.dp) {
    val c = UmbraTheme.colors
    val lw = if (size >= 60.dp) 5.dp else if (size >= 40.dp) 3.dp else 2.dp
    val fs = if (size >= 60.dp) 24.sp else if (size >= 40.dp) 15.sp else 10.sp
    val ring = if (score >= 80) c.success else if (score >= 50) c.orange else c.danger
    val ink = if (score >= 80) c.success else if (score >= 50) c.orangeText else c.dangerText
    Box(Modifier.size(size).border(BorderStroke(lw, ring), CircleShape), contentAlignment = Alignment.Center) {
        Text("$score", color = ink, style = TextStyle(fontSize = fs, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold))
    }
}

/** 解过的图按原串缓存：列表每次重组都会重画行，base64 别每次都解一遍。 */
private val iconCache = android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(64)

/** 记录图标方块：图片 → 内置名（PC 保险箱 16 个）→ 首字（判据在 [VaultIconRef]）。列表 34、自动填充 28。 */
@Composable
fun VaultRecordIcon(value: String?, monogram: String, side: Dp = 28.dp, corner: Dp = 8.dp) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(corner)
    Box(Modifier.size(side).clip(shape).background(c.chip), contentAlignment = Alignment.Center) {
        val letter: @Composable () -> Unit = {
            Text(monogram, color = c.muted, style = TextStyle(fontSize = if (side >= 32.dp) 15.sp else 13.sp, fontWeight = FontWeight.SemiBold))
        }
        when (val ref = VaultIconRef.resolve(value)) {
            is VaultIconRef.Image -> {
                val bmp = remember(ref.base64) {
                    iconCache.get(ref.base64) ?: runCatching {
                        val d = android.util.Base64.decode(ref.base64, android.util.Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(d, 0, d.size)?.asImageBitmap()
                    }.getOrNull()?.also { iconCache.put(ref.base64, it) }
                }
                if (bmp != null) Image(bmp, null, Modifier.size(side), contentScale = ContentScale.Crop) else letter()
            }
            is VaultIconRef.Builtin -> UmbraIcon(ref.d, c.muted, size = side * 16f / 28f)
            VaultIconRef.Monogram -> letter()
        }
    }
}

/** 底部选择面板的一项（iOS UmbraSheetItem）。 */
data class VaultSheetItem(
    val label: String,
    val note: String? = null,
    val checked: Boolean = false,
    val destructive: Boolean = false,
    val action: () -> Unit,
)

/** 弹一张「标题 + 副标题 + 一列选项」的底部面板。点一项先收面板再执行。 */
fun presentVaultSheet(title: String, subtitle: String? = null, items: List<VaultSheetItem>) {
    AppGraph.router.present(UmbraSheet("vault-sheet-$title-${System.nanoTime()}") { dismiss ->
        VaultSheetBody(title, subtitle, items, dismiss)
    })
}

@Composable
private fun VaultSheetBody(title: String, subtitle: String?, items: List<VaultSheetItem>, dismiss: () -> Unit) {
    val c = UmbraTheme.colors
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = UmbraDimens.pagePadX).padding(bottom = 16.dp).navigationBarsPadding()) {
        Text(title, color = c.text, style = UmbraType.sectionTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = c.muted, style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp))
        }
        Spacer(Modifier.height(12.dp))
        VaultRowsCard(rows = items.map { item ->
            @Composable {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = UmbraDimens.rowMinH).clickable { dismiss(); item.action() }.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.label, color = if (item.destructive) c.dangerText else c.text, style = UmbraType.rowTitle)
                        if (item.note != null) Text(item.note, color = c.muted, style = UmbraType.rowSub)
                    }
                    if (item.checked) UmbraIcon(UmbraIconPaths.check, c.orange, size = 18.dp)
                }
            }
        })
    }
}

/** 单按钮的说明面板（iOS 的 showsCancel:false 单钮弹窗；Router 的确认弹窗总是两颗钮，长句放面板里读得完）。 */
fun presentVaultNotice(title: String, body: String, button: String = "知道了") {
    AppGraph.router.present(UmbraSheet("vault-notice-${System.nanoTime()}") { dismiss ->
        val c = UmbraTheme.colors
        Column(Modifier.fillMaxWidth().padding(horizontal = UmbraDimens.pagePadX).padding(bottom = 16.dp).navigationBarsPadding()) {
            Text(title, color = c.text, style = UmbraType.sectionTitle)
            Spacer(Modifier.height(8.dp))
            Text(body, color = c.muted, style = TextStyle(fontSize = 14.sp, lineHeight = 21.sp))
            Spacer(Modifier.height(16.dp))
            com.geek.umbra.designsystem.UmbraButton(button, dismiss, Modifier.fillMaxWidth(), height = 44.dp)
        }
    })
}

/**
 * 保险箱输入框：卡片底 / bg 底、圆角 10、1px 描边。`password` 时画圆点；关掉联想 / 自动更正（键盘不记密码）。
 * 长按复制 / 剪切由外层 [VaultFrame] 改道到敏感剪贴板。
 */
@Composable
fun VaultInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    password: Boolean = false,
    mono: Boolean = false,
    center: Boolean = false,
    height: Dp = 52.dp,
    fontSize: Float = 15f,
    error: Boolean = false,
    borderColor: Color? = null,
    onCard: Boolean = true,
    singleLine: Boolean = true,
    allCaps: Boolean = false,
    imeDone: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(UmbraDimens.radiusControl)
    BasicTextField(
        value = value, onValueChange = onValueChange, singleLine = singleLine,
        textStyle = TextStyle(fontSize = fontSize.sp, color = c.text, textAlign = if (center) TextAlign.Center else TextAlign.Start,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default),
        cursorBrush = SolidColor(c.orange),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else KeyboardType.Ascii.takeIf { mono } ?: KeyboardType.Text,
            autoCorrectEnabled = false,
            capitalization = if (allCaps) KeyboardCapitalization.Characters else KeyboardCapitalization.None,
            imeAction = if (imeDone != null) ImeAction.Done else ImeAction.Default,
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { imeDone?.invoke() }),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = height).clip(shape).background(if (onCard) c.card else c.bg)
                    .border(UmbraDimens.borderW, borderColor ?: if (error) c.danger else c.border, shape).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
            ) {
                Box(Modifier.weight(1f), contentAlignment = if (center) Alignment.Center else Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, color = c.faint, style = TextStyle(fontSize = fontSize.sp), textAlign = if (center) TextAlign.Center else TextAlign.Start)
                    inner()
                }
                if (trailing != null) trailing()
            }
        },
    )
}

/** 一行灰色可点链接（锁屏页底部那两行）。 */
@Composable
fun VaultLinkText(text: String, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = UmbraDimens.tapMin).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = UmbraTheme.colors.muted, style = TextStyle(fontSize = 13.sp))
    }
}

/** 页面里的说明小字（12 muted，行高 1.65）。 */
@Composable
fun VaultNote(text: String, modifier: Modifier = Modifier, size: Float = 12f) {
    Text(text, modifier, color = UmbraTheme.colors.muted, style = TextStyle(fontSize = size.sp, lineHeight = (size * 1.65f).sp))
}

/** 小号「生成」文字钮：视觉 34、热区 44。 */
@Composable
fun VaultGenButton(onClick: () -> Unit) {
    Box(Modifier.heightIn(min = UmbraDimens.tapMin).widthIn(min = 40.dp).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Text("生成", color = UmbraTheme.colors.orangeText, style = TextStyle(fontSize = 12.5.sp, fontWeight = UmbraType.weight560))
    }
}

/** 协程里调挂起的 Store 方法的小糖。 */
@Composable
fun rememberVaultLauncher(): (suspend () -> Unit) -> Unit {
    val scope = rememberCoroutineScope()
    return remember(scope) { { block -> scope.launch { block() } } }
}

/** 页面消失时跑一下（DisposableEffect 的简写）。 */
@Composable
fun OnLeave(block: () -> Unit) {
    DisposableEffect(Unit) { onDispose(block) }
}
