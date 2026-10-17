// 设置类页面的分组 / 行。尺寸照抄 iOS DesignSystem/UmbraSettingsKit.swift。
package com.geek.umbra.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 一行设置。字段与 iOS UmbraSettingRow 对应：label / sub / value / mono / valueIcon / tint / chevron / toggle / action。
 * `toggle` 不为 null 时画开关，此时整行点击 = 切换（热区覆盖整行，不只是那颗开关）。
 */
data class UmbraSettingRow(
    val label: String,
    val sub: String? = null,
    val subWarn: Boolean = false,
    val value: String? = null,
    val mono: Boolean = false,
    val valueIcon: String? = null,
    val valueIconSpins: Boolean = false,
    val valueTint: Color? = null,
    val tint: Color? = null,
    val icon: String? = null,
    val chevron: Boolean = false,
    val toggle: Boolean? = null,
    val onToggle: ((Boolean) -> Unit)? = null,
    val enabled: Boolean = true,
    val action: (() -> Unit)? = null,
)

@Composable
fun UmbraSettingRowView(row: UmbraSettingRow) {
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
        if (row.icon != null) {
            UmbraIcon(row.icon, row.tint ?: c.muted, size = 18.dp)
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(row.label, color = if (row.enabled) (row.tint ?: c.text) else c.faint, style = TextStyle(fontSize = 16.sp))
            if (row.sub != null) {
                Spacer(Modifier.height(2.dp))
                Text(row.sub, color = if (row.subWarn) c.warningText else c.muted, style = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp))
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
                modifier = Modifier.widthIn(max = 150.dp),
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

/**
 * 一组设置：组头（FieldLabel + 等宽计数）+ 分组卡 + 组尾（12 muted；带 tone 时 13 号 + 图标）。
 * 作为 LazyListScope 扩展，页面里写 `settingSection("账号", rows)` 一行就是一组。
 */
fun LazyListScope.settingSection(
    key: String,
    header: String?,
    rows: List<UmbraSettingRow>,
    headerCount: Int? = null,
    footer: String? = null,
    footerTone: UmbraTone? = null,
) {
    if (rows.isEmpty()) return
    item(key = "sec-$key") {
        Column {
            if (header != null) {
                UmbraSectionLabel(header, trailing = headerCount?.toString())
                Spacer(Modifier.height(6.dp))
            }
            UmbraGroupCard {
                rows.forEachIndexed { i, r ->
                    if (i > 0) UmbraRowDivider(Modifier.padding(start = 14.dp))
                    UmbraSettingRowView(r)
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
