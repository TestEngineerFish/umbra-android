// 「我」「账号」「记忆」「知识库」四个包共用的小件 —— 对应 iOS DesignSystem/UmbraSettingsKit.swift 的
// `UmbraSettingsPage`（hero / intro / footnote / ⋯ 破坏性动作）与 `UmbraSheet(title:items:)` 那一套。
// 地基的 designsystem 只有分组与行（UmbraSettingsKit.kt），整页模板、选择弹层、左滑露钮的行都还没有，
// 这里先在自己包里补上（**不改地基**）；合并时若别的模块也要，可挪进 designsystem。
package com.geek.umbra.feature.me

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraButton
import com.geek.umbra.designsystem.UmbraButtonKind
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraGroupCard
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraRowDivider
import com.geek.umbra.designsystem.UmbraSectionLabel
import com.geek.umbra.designsystem.UmbraSettingRow
import com.geek.umbra.designsystem.UmbraSpinningIcon
import com.geek.umbra.designsystem.UmbraSwitch
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraTone
import com.geek.umbra.navigation.UmbraSheet

// MARK: - 整页模板里的几块（iOS UmbraSettingsPage 的 intro / footnote / hero）

/** 页首说明：12.5 muted，左右 16（iOS UmbraSettingsKit.swift:341）。 */
fun LazyListScope.introItem(text: String?) {
    if (text.isNullOrEmpty()) return
    item(key = "intro") { MeNote(text, size = 12.5f) }
}

/** 页尾说明：12 muted（iOS UmbraSettingsKit.swift:352）。 */
fun LazyListScope.footnoteItem(text: String?, key: String = "footnote") {
    if (text.isNullOrEmpty()) return
    item(key = key) { MeNote(text, size = 12f) }
}

/** 一段 muted 说明字。行距照 iOS 的 lineSpacing 0.7 换成 Compose 的 lineHeight。 */
@Composable
fun MeNote(text: String, size: Float = 12.5f, color: Color = UmbraTheme.colors.muted, modifier: Modifier = Modifier) {
    Text(
        text, color = color, style = TextStyle(fontSize = size.sp, lineHeight = (size * 1.6f).sp),
        modifier = modifier.padding(horizontal = 4.dp),
    )
}

// MARK: - 带「提醒半句」的行（iOS UmbraSettingRow.subWarn）

/**
 * 地基的 [UmbraSettingRow] 没有 iOS 的 `subWarn: String`（副行前面那半句提醒色 + 三角）。
 * 商城「我的道具」那一组要它（设计侧 236：过期那半句标提醒 + 三角，整行不上色），这里包一层。
 */
data class MeRow(val row: UmbraSettingRow, val warn: String? = null)

/** 一组：和 [com.geek.umbra.designsystem.settingSection] 同尺寸，行支持 [MeRow.warn]。 */
fun LazyListScope.meSection(
    key: String,
    header: String?,
    rows: List<MeRow>,
    headerCount: Int? = null,
    footer: String? = null,
    footerTone: UmbraTone? = null,
) {
    if (rows.isEmpty()) return
    item(key = "msec-$key") {
        Column {
            if (header != null) {
                UmbraSectionLabel(header, trailing = headerCount?.toString())
                Spacer(Modifier.height(6.dp))
            }
            UmbraGroupCard {
                rows.forEachIndexed { i, r ->
                    if (i > 0) UmbraRowDivider(Modifier.padding(start = 14.dp))
                    MeRowView(r)
                }
            }
            if (footer != null) {
                Spacer(Modifier.height(6.dp))
                val c = UmbraTheme.colors
                Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.Top) {
                    if (footerTone != null) {
                        UmbraIcon(UmbraIconPaths.alertTriangle, if (footerTone == UmbraTone.Danger) c.dangerText else c.warningText, size = 13.dp, modifier = Modifier.padding(top = 2.dp))
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        footer,
                        color = when (footerTone) { UmbraTone.Danger -> c.dangerText; UmbraTone.Warning -> c.warningText; else -> c.muted },
                        style = TextStyle(fontSize = if (footerTone != null) 13.sp else 12.sp, lineHeight = 17.sp),
                    )
                }
            }
        }
    }
}

/** 一行。版式同地基 UmbraSettingRowView（14 / 11 / 最小高 48），多一个提醒半句；label 允许多行（详情要点折行不截断）。 */
@Composable
fun MeRowView(m: MeRow) {
    val row = m.row
    val c = UmbraTheme.colors
    val click: (() -> Unit)? = when {
        !row.enabled -> null
        row.toggle != null && row.onToggle != null -> { { row.onToggle.invoke(!row.toggle) } }
        else -> row.action
    }
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = UmbraDimens.rowMinH)
            .then(if (click != null) Modifier.clickable(onClick = click) else Modifier)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.label, color = if (row.enabled) (row.tint ?: c.text) else c.faint, style = TextStyle(fontSize = 16.sp, lineHeight = 22.sp))
            if (m.warn != null || row.sub != null) {
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (m.warn != null) {
                        UmbraIcon(UmbraIconPaths.alertTriangle, c.warningText, size = 12.dp, strokeWidth = 2f)
                        Spacer(Modifier.width(4.dp))
                        Text(if (row.sub == null) m.warn else "${m.warn} ·", color = c.warningText, style = TextStyle(fontSize = 12.5.sp))
                        if (row.sub != null) Spacer(Modifier.width(4.dp))
                    }
                    if (row.sub != null) {
                        Text(row.sub, color = if (row.subWarn) c.warningText else c.muted, style = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp))
                    }
                }
            }
        }
        if (row.valueIcon != null) {
            Spacer(Modifier.width(6.dp))
            val t = row.valueTint ?: c.muted
            if (row.valueIconSpins) UmbraSpinningIcon(row.valueIcon, t, 14.dp) else UmbraIcon(row.valueIcon, t, size = 14.dp)
        }
        if (row.value != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                row.value, color = row.valueTint ?: c.muted,
                style = TextStyle(fontSize = if (row.mono) 13.5.sp else 14.5.sp, fontFamily = if (row.mono) FontFamily.Monospace else FontFamily.Default),
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                modifier = Modifier.widthIn(max = 170.dp),
            )
        }
        if (row.toggle != null) {
            Spacer(Modifier.width(8.dp))
            UmbraSwitch(row.toggle, { row.onToggle?.invoke(it) }, enabled = row.enabled)
        }
        if (row.chevron) {
            Spacer(Modifier.width(6.dp))
            UmbraIcon(UmbraIconPaths.chevronRight, c.faint, size = 15.dp)
        }
    }
}

// MARK: - 选择弹层（iOS UmbraSheet(title:subtitle:items:)）

/** 选择弹层的一项。`checked` 画对勾；`note` 是右侧那句人话（画质档位的说明）；`destructive` 红字。 */
data class MeSheetItem(
    val label: String,
    val checked: Boolean = false,
    val note: String? = null,
    val destructive: Boolean = false,
    val action: () -> Unit,
)

/**
 * 弹出一张选择弹层：标题 + 副标题 + 若干项 + 底部「取消」。点一项先关弹层再执行（iOS 同序：先 onClose 再 action）。
 * [onDismissed]：弹层**因为任何原因**离开（选了 / 取消 / 下滑关掉）都会回调一次，带「是不是选了一项」——
 * 知识库同名询问靠它把「下滑关掉」认成取消（iOS KbClashPrompt.sheetsChanged 那一套）。
 */
fun presentChoice(
    title: String,
    items: List<MeSheetItem>,
    subtitle: String? = null,
    key: String = "choice-${System.nanoTime()}",
    onDismissed: ((picked: Boolean) -> Unit)? = null,
) {
    AppGraph.router.present(UmbraSheet(key) { dismiss ->
        var picked by remember { mutableStateOf(false) }
        androidx.compose.runtime.DisposableEffect(Unit) { onDispose { onDismissed?.invoke(picked) } }
        ChoiceSheetBody(title, subtitle, items, onPick = { item -> picked = true; dismiss(); item.action() }, onCancel = dismiss)
    })
}

@Composable
private fun ChoiceSheetBody(title: String, subtitle: String?, items: List<MeSheetItem>, onPick: (MeSheetItem) -> Unit, onCancel: () -> Unit) {
    val c = UmbraTheme.colors
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        Text(title, color = c.text, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(horizontal = 4.dp))
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = c.muted, style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp), modifier = Modifier.padding(horizontal = 4.dp))
        }
        Spacer(Modifier.height(12.dp))
        UmbraGroupCard {
            items.forEachIndexed { i, it ->
                if (i > 0) UmbraRowDivider(Modifier.padding(start = 14.dp))
                Row(
                    Modifier.fillMaxWidth().defaultMinSize(minHeight = UmbraDimens.rowMinH).clickable(role = Role.Button) { onPick(it) }
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(it.label, color = if (it.destructive) c.dangerText else c.text, style = TextStyle(fontSize = 16.sp))
                        if (it.note != null) {
                            Spacer(Modifier.height(2.dp))
                            Text(it.note, color = c.muted, style = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp))
                        }
                    }
                    if (it.checked) {
                        Spacer(Modifier.width(8.dp))
                        UmbraIcon(UmbraIconPaths.check, c.orangeText, size = 17.dp, strokeWidth = 2.2f)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        UmbraButton("取消", onCancel, Modifier.fillMaxWidth(), kind = UmbraButtonKind.Secondary, height = 46.dp)
    }
}

// MARK: - 平台小工具

/** 普通剪贴板（账号 ID、设备 ID、客户端 ID 不是敏感值 —— iOS 同样走普通剪贴板、不设过期）。 */
fun copyPlainText(text: String) {
    val cm = AppGraph.context.getSystemService(ClipboardManager::class.java) ?: return
    cm.setPrimaryClip(ClipData.newPlainText("Umbra", text))
}

/**
 * 打开一个外链（续费、充值、支付页）。iOS `UIApplication.shared.open` → 这里用 Custom Tabs：
 * 支付宝收银台的 https 页在 Custom Tab 里能被系统交给支付宝 App 接管；打不开（没有浏览器）退回 ACTION_VIEW。
 * 调用方已经按 http(s) 判过（CreditsRules.topupUrl / PayRules.payUrl）。
 */
fun openWebLink(url: String) {
    val ctx = AppGraph.context
    val uri = Uri.parse(url)
    val ok = runCatching {
        val tab = CustomTabsIntent.Builder().setShowTitle(true).build()
        tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        tab.launchUrl(ctx, uri)
    }.isSuccess
    if (!ok) runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** 页内的一块「正在读取…」占位行（iOS 设置页里那种 faint 字的单行组）。 */
@Composable
fun MePlaceholderRow(text: String) {
    val c = UmbraTheme.colors
    UmbraGroupCard {
        Box(Modifier.fillMaxWidth().heightIn(min = UmbraDimens.rowMinH).padding(horizontal = 14.dp, vertical = 11.dp), contentAlignment = Alignment.CenterStart) {
            Text(text, color = c.faint, style = TextStyle(fontSize = 16.sp))
        }
    }
}

/** 卡里的一块，圆角 12（iOS radiusInput），card 底 + borderSoft 描边 —— 记忆编辑页的输入底。 */
fun Modifier.meInputShape(c: com.geek.umbra.designsystem.UmbraColorScheme): Modifier {
    val shape = RoundedCornerShape(UmbraDimens.radiusInput)
    return this.clip(shape).background(c.card).border(UmbraDimens.borderW, c.borderSoft, shape)
}

/**
 * 往左右各「出血」[by]：子件自己带了页边距（PresenceRecordSection 自带左右 16），放进 UmbraScreen（内容区已有 16）
 * 会缩进两层。这里把它的测量宽度放大 2×[by]、向左挪 [by]，让它的 16 正好落在页边上。
 */
fun Modifier.bleedHorizontal(by: androidx.compose.ui.unit.Dp): Modifier = this.layout { m, c ->
    val extra = by.roundToPx() * 2
    val w = if (c.hasBoundedWidth) c.maxWidth + extra else c.maxWidth
    val p = m.measure(c.copy(minWidth = w, maxWidth = w))
    layout(c.maxWidth, p.height) { p.place(-extra / 2, 0) }
}
