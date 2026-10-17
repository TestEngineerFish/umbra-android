package com.geek.umbra.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/** 外观设置：与 iOS `umbra.appearance` 同取值。 */
enum class Appearance(val wire: String) {
    System("system"), Light("light"), Dark("dark");

    companion object {
        fun of(wire: String?) = entries.firstOrNull { it.wire == wire } ?: System
    }
}

/** 取当前主题 token 的入口：`UmbraTheme.colors.card`。 */
object UmbraTheme {
    val colors: UmbraColorScheme
        @Composable @ReadOnlyComposable get() = LocalUmbraColors.current
}

/**
 * 主题根。除了下发 Umbra 自己的 token，还把它映射进 Material3 的 ColorScheme ——
 * 对话框、底部弹层、开关、日期选择器这些直接用的 M3 组件才会是 Umbra 的暖色，
 * 而不是 M3 默认的紫色动态取色（**不开 dynamic color**：设计稿只有一个主色）。
 */
@Composable
fun UmbraTheme(appearance: Appearance = Appearance.System, content: @Composable () -> Unit) {
    val dark = when (appearance) {
        Appearance.System -> isSystemInDarkTheme()
        Appearance.Light -> false
        Appearance.Dark -> true
    }
    val c = if (dark) UmbraDark else UmbraLight
    val m3 = if (dark) {
        darkColorScheme(
            primary = c.orange, onPrimary = UmbraLight.card, primaryContainer = c.orangeSoft,
            onPrimaryContainer = c.orangeText, secondary = c.orange, background = c.bg,
            onBackground = c.text, surface = c.card, onSurface = c.text, surfaceVariant = c.chip,
            onSurfaceVariant = c.muted, surfaceContainer = c.card, surfaceContainerHigh = c.card,
            surfaceContainerHighest = c.chip, surfaceContainerLow = c.bg, outline = c.border,
            outlineVariant = c.borderSoft, error = c.danger, onError = c.onDanger,
        )
    } else {
        lightColorScheme(
            primary = c.orange, onPrimary = UmbraLight.card, primaryContainer = c.orangeSoft,
            onPrimaryContainer = c.orangeText, secondary = c.orange, background = c.bg,
            onBackground = c.text, surface = c.card, onSurface = c.text, surfaceVariant = c.chip,
            onSurfaceVariant = c.muted, surfaceContainer = c.card, surfaceContainerHigh = c.card,
            surfaceContainerHighest = c.chip, surfaceContainerLow = c.bg, outline = c.border,
            outlineVariant = c.borderSoft, error = c.danger, onError = c.onDanger,
        )
    }
    CompositionLocalProvider(LocalUmbraColors provides c) {
        MaterialTheme(colorScheme = m3, content = content)
    }
}
