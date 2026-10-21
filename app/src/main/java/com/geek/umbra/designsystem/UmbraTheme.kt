package com.geek.umbra.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.unit.dp

/** 外观设置：与 iOS `umbra.appearance` 同取值。 */
enum class Appearance(val wire: String) {
    System("system"), Light("light"), Dark("dark");

    companion object {
        fun of(wire: String?) = entries.firstOrNull { it.wire == wire } ?: System
    }
}

/**
 * 当前主题的调色板 —— [UmbraTheme] 换色时先问这里，问不到用内置值（iOS `Utils/ThemeShared.swift` 的同一件事）。
 * 值的形状同 iOS App Group 里那份：`{"light": {主题键: "#RRGGBB(AA)"}, "dark": {…}}`；null = 默认主题。
 *
 * 为什么放在 designsystem、而不是主题模块里：主 App、分享、自动填充三个 Activity 和小组件都要读它，
 * 而 designsystem 不许反过来依赖 feature。由 `feature/theme/ThemeStore` 写（启动时、换主题、试用、退回）。
 * iOS 那边小组件和自动填充是独立进程、要经 App Group 读；Android 小组件和自动填充都在同一个进程里，内存里这一份就够。
 */
object UmbraThemePalette {
    private val _current = MutableStateFlow<Map<String, Map<String, String>>?>(null)
    val current: StateFlow<Map<String, Map<String, String>>?> = _current

    fun set(palette: Map<String, Map<String, String>>?) {
        _current.value = palette
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
    val palette by UmbraThemePalette.current.collectAsState()
    val c = androidx.compose.runtime.remember(palette, dark) { (if (dark) UmbraDark else UmbraLight).themed(palette?.get(if (dark) "dark" else "light")) }
    val m3 = if (dark) {
        darkColorScheme(
            primary = c.orange, onPrimary = c.onAccent, primaryContainer = c.orangeSoft,
            onPrimaryContainer = c.orangeText, secondary = c.orange, background = c.bg,
            onBackground = c.text, surface = c.card, onSurface = c.text, surfaceVariant = c.chip,
            onSurfaceVariant = c.muted, surfaceContainer = c.card, surfaceContainerHigh = c.card,
            surfaceContainerHighest = c.chip, surfaceContainerLow = c.bg, outline = c.border,
            outlineVariant = c.borderSoft, error = c.danger, onError = c.onDanger,
        )
    } else {
        lightColorScheme(
            primary = c.orange, onPrimary = c.onAccent, primaryContainer = c.orangeSoft,
            onPrimaryContainer = c.orangeText, secondary = c.orange, background = c.bg,
            onBackground = c.text, surface = c.card, onSurface = c.text, surfaceVariant = c.chip,
            onSurfaceVariant = c.muted, surfaceContainer = c.card, surfaceContainerHigh = c.card,
            surfaceContainerHighest = c.chip, surfaceContainerLow = c.bg, outline = c.border,
            outlineVariant = c.borderSoft, error = c.danger, onError = c.onDanger,
        )
    }
    CompositionLocalProvider(LocalUmbraColors provides c) {
        // 菜单（DropdownMenu）取 shapes.extraSmall：圆角 12（设计侧 199 答复第 5 条），各模块自己调的 M3 菜单一次到位
        MaterialTheme(
            colorScheme = m3,
            shapes = androidx.compose.material3.Shapes(extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
            content = content,
        )
    }
}
