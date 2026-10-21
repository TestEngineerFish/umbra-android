// Umbra 设计 token —— 唯一取值来源。
//
// 取值逐个照抄 iOS 的 DesignSystem/UmbraTokens.swift（它又照抄设计交接包的 colors.css / spacing.css）。
// 规则与 iOS 相同：**页面和组件里不许出现字面量颜色、字号、圆角**，一律从这里取 ——
// 目测出来的偏差评审时查不出来，只有「代码里根本没有字面量」能保证它不发生。
package com.geek.umbra.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** 从 0xRRGGBB 构造不透明色；alpha 用于深色模式那几个「半透明软底」。 */
private fun hex(rgb: Long, alpha: Float = 1f): Color = Color(0xFF000000 or rgb).copy(alpha = alpha)

/**
 * 一套主题的全部颜色。做成数据类而不是两组顶层常量：
 * 亮 / 暗切换时整份换掉，组件里只写 `UmbraTheme.colors.card`，不会有人漏判 isDark。
 */
@Immutable
data class UmbraColorScheme(
    val isDark: Boolean,
    // 表面 / 中性
    val bg: Color, val card: Color, val titlebar: Color, val rail: Color,
    val chip: Color, val hover: Color, val track: Color,
    // 描边与文字
    val border: Color, val borderSoft: Color, val text: Color, val muted: Color, val faint: Color,
    // 品牌。名字沿用 orange*（同 iOS：改名牵涉上千处引用），对应主题键 accent / accentDeep / accentSoft / accentText。
    val nav: Color, val orange: Color, val orangeDeep: Color, val orangeSoft: Color, val orangeText: Color,
    /**
     * 强调色**实底**上的字与图标（主题键 `onAccent`，主题插件化二期新加，iOS a95213a）。默认浅深都是白；
     * 石墨主题深色那套是深字（强调色提亮了，白字压不住）。门槛只有 3.0，所以**用它的字一律 ≥ 600 字重**（规格 clientConstraints）。
     */
    val onAccent: Color,
    /** 胶囊、徽章、标题栏上的中性字（主题键 `inkQuiet`，规格 v1.1）。取值同 PC `--ink-quiet`；只为 chip / titlebar / hover / track 四张底存在。 */
    val inkQuiet: Color,
    /** 吐司 / 浮条的深色玻璃底。没装主题是稿上的 #1C1915 88%；装着主题取规格派生值「nav 86%」（`derived`，批次 268）。 */
    val toastGlass: Color,
    // 语义：实心那支给图标 / 描边 / 实底，**文字一律走 *Text 那支**（iOS swiftcheck「前景色支」）
    val success: Color, val successSoft: Color,
    val warning: Color, val warningSoft: Color,
    val danger: Color, val dangerSoft: Color,
    val warningText: Color, val dangerText: Color,
    val onWarning: Color, val onDanger: Color, val onSuccess: Color,
    // 其他
    val badgeRed: Color, val userBubble: Color, val deviceBubble: Color,
)

val UmbraLight = UmbraColorScheme(
    isDark = false,
    bg = hex(0xF1EFEA), card = hex(0xFFFFFF), titlebar = hex(0xEFEDE8), rail = hex(0xFBFAF8),
    chip = hex(0xF2F0EC), hover = hex(0xF1EFEA), track = hex(0xEDEBE6),
    border = hex(0xE6E3DC), borderSoft = hex(0xEEEBE4), text = hex(0x1F2320),
    muted = hex(0x656B65), faint = hex(0x9A9992),
    nav = hex(0x15110E), orange = hex(0xE8590C), orangeDeep = hex(0xC2410C),
    orangeSoft = hex(0xFFF1E6), orangeText = hex(0x9A3412),
    onAccent = hex(0xFFFFFF), inkQuiet = hex(0x5B615B), toastGlass = hex(0x1C1915, 0.88f),
    success = hex(0x0F766E), successSoft = hex(0xE2F1EF),
    warning = hex(0xB45309), warningSoft = hex(0xFBEEDD),
    danger = hex(0xB42318), dangerSoft = hex(0xFBE9E7),
    warningText = hex(0x97460A), dangerText = hex(0xB42318),
    onWarning = hex(0xFFFFFF), onDanger = hex(0xFFFFFF), onSuccess = hex(0xFFFFFF),
    badgeRed = hex(0xFF3B30), userBubble = hex(0xEAF1F7), deviceBubble = hex(0xEDEAE4),
)

val UmbraDark = UmbraColorScheme(
    isDark = true,
    bg = hex(0x151310), card = hex(0x26231F), titlebar = hex(0x1C1A17), rail = hex(0x1F1C18),
    chip = hex(0x2A251F), hover = hex(0x2A251F), track = hex(0x302B24),
    border = hex(0x332E26), borderSoft = hex(0x2B2721), text = hex(0xEDEAE3),
    muted = hex(0x9A938A), faint = hex(0x6E675E),
    nav = hex(0x15110E), orange = hex(0xE8590C), orangeDeep = hex(0xC2410C),
    // 深色下软底是半透明：叠在 card 上才是设计稿量出来的那个颜色
    orangeSoft = hex(0xE8590C, 0.16f), orangeText = hex(0xF0A878),
    onAccent = hex(0xFFFFFF), inkQuiet = hex(0xA79E92), toastGlass = hex(0x1C1915, 0.88f),
    success = hex(0x34B5A6), successSoft = hex(0x0F766E, 0.20f),
    warning = hex(0xD98A29), warningSoft = hex(0xB45309, 0.22f),
    danger = hex(0xE0675C), dangerSoft = hex(0xB42318, 0.22f),
    warningText = hex(0xD98A29), dangerText = hex(0xEE8B80),
    onWarning = hex(0x15110E), onDanger = hex(0x15110E), onSuccess = hex(0x15110E),
    badgeRed = hex(0xFF453A), userBubble = hex(0x1E2A35), deviceBubble = hex(0x2E2A25),
)

/**
 * 套上当前主题：标了主题键的 token 先查调色板，查不到用内置值（回落顺序「主题 → 内置」，iOS `UmbraColor.dyn(theme:)` 的同一件事）。
 * 调用点一个都不用改 —— 组件照旧写 `UmbraTheme.colors.orange`，换主题时整份 scheme 换掉。
 * 语义四色、分类色、远程控制看 / 控两色、图片预览底**不接**：规格 `locked`，跨主题不变。
 * `palette`：主题键 → `#RRGGBB(AA)`，就是这一档（浅或深）那一套；null = 默认主题，原样返回。
 */
fun UmbraColorScheme.themed(palette: Map<String, String>?): UmbraColorScheme {
    if (palette == null) return this
    fun k(key: String, fallback: Color) = palette[key]?.let(::themeColor) ?: fallback
    val navC = k("nav", nav)
    return copy(
        bg = k("bg", bg), card = k("card", card), titlebar = k("titlebar", titlebar), rail = k("rail", rail),
        chip = k("chip", chip), hover = k("hover", hover), track = k("track", track),
        border = k("border", border), borderSoft = k("borderSoft", borderSoft),
        text = k("text", text), muted = k("muted", muted), faint = k("faint", faint), inkQuiet = k("inkQuiet", inkQuiet),
        nav = navC, orange = k("accent", orange), orangeDeep = k("accentDeep", orangeDeep),
        orangeSoft = k("accentSoft", orangeSoft), orangeText = k("accentText", orangeText), onAccent = k("onAccent", onAccent),
        userBubble = k("userBubble", userBubble),
        toastGlass = if (palette.containsKey("nav")) navC.copy(alpha = 0.86f) else toastGlass,
    )
}

/**
 * `#RRGGBB` / `#RRGGBBAA`（主题包的写法，透明度在**最后**两位 —— 和 Compose `Color(0xAARRGGBB)` 相反）→ Color。形状不对回 null。
 */
fun themeColor(s: String): Color? {
    val h = s.removePrefix("#")
    if (h.length != 6 && h.length != 8) return null
    val v = h.toLongOrNull(16) ?: return null
    return if (h.length == 6) Color(0xFF000000 or v)
    else Color(red = ((v shr 24) and 0xFF).toInt(), green = ((v shr 16) and 0xFF).toInt(), blue = ((v shr 8) and 0xFF).toInt(), alpha = (v and 0xFF).toInt())
}

/** 两个主题同值、不随外观变的颜色。 */
object UmbraFixedColors {
    val viewerBg = hex(0x0B0A09)
    val onNavText = Color.White.copy(alpha = 0.92f)
    val onNavAccent = hex(0xF0A878)
    val scrim = Color.Black.copy(alpha = 0.32f)
}

val LocalUmbraColors = staticCompositionLocalOf { UmbraLight }

/** 字号与字重。设计稿的四档字重 400 / 560 / 600 / 650 在 Android 上映射成 Normal / Medium / SemiBold / SemiBold。 */
object UmbraType {
    // 设计侧 199 答复「全局规则」：字体走系统默认，字重映射 400→Normal、560→Medium(500)、600 / 650→SemiBold(600)。
    // （原来写的是可变字重 560 / 650 —— 系统字体在多数国产机上不是可变字重，会就近落成 400 / 700，反而更乱。）
    private val W560 = FontWeight.Medium
    private val W650 = FontWeight.SemiBold
    val pageTitle = TextStyle(fontSize = 27.sp, fontWeight = W650, lineHeight = 34.sp)
    val sectionTitle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)
    val detailTitle = TextStyle(fontSize = 20.sp, fontWeight = W650, lineHeight = 26.sp)
    /** 正文行高 1.65 —— 设计稿的长文可读性靠它，别压回系统默认。 */
    val body = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 24.75.sp)
    val rowTitle = TextStyle(fontSize = 16.sp, fontWeight = W560, lineHeight = 21.sp)
    val rowSub = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp)
    val fieldLabel = TextStyle(fontSize = 12.sp, fontWeight = W560, letterSpacing = 0.06.em)
    val meta = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Normal, lineHeight = 15.sp)
    val button = TextStyle(fontSize = 16.sp, fontWeight = W560)
    val tabLabel = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Normal)
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    val weight560 = W560
    val weight650 = W650
}

/** 间距、圆角、尺寸。名字与 iOS UmbraMetric 一一对应。 */
object UmbraDimens {
    val sp1 = 4.dp; val sp2 = 7.dp; val sp3 = 9.dp; val sp4 = 12.dp
    val sp5 = 14.dp; val sp6 = 18.dp; val sp7 = 20.dp; val sp8 = 28.dp
    val bubbleGutter = 56.dp

    val radiusControl = 10.dp
    val radiusCard = 18.dp
    val radiusInput = 12.dp
    val radiusSwipeRow = 16.dp
    val radiusSheet = 26.dp
    val radiusDialog = 28.dp
    val radiusShot = 12.dp
    val radiusCodeBlock = 9.dp

    val pagePadX = 16.dp
    val cardPad = 13.dp
    val groupGap = 11.dp
    /** 热区下限 48dp（设计侧 199 答复「全局规则」：iOS 44pt、Android 48dp）。视觉尺寸不变，用透明外框撑。 */
    val tapMin = 48.dp
    val rowMinH = 48.dp
    val rowMinHSub = 60.dp

    val iconBlockSM = 24.dp
    val iconBlockMD = 34.dp
    val iconBlockLG = 44.dp
    val iconBlockXL = 48.dp

    val progressH = 4.dp
    val statusDot = 7.dp
    val segmentH = 30.dp
    val borderW = 1.dp
    val buttonH = 48.dp
}

/** 动效时长（毫秒）。设计稿禁止 spring 弹跳，一律 tween。 */
object UmbraMotion {
    const val tint = 130
    const val swipe = 160
    const val push = 220
    const val slider = 220
    const val spin = 1000
}
