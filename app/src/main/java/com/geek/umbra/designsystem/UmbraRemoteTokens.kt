// 远程控制专用的 token —— 只有远程控制模块（feature/remote）用。
//
// 为什么单开一份：会话页铺在一块别人电脑的画面上，iOS 用的是「深底 + 白字 + 几档白的透明度」，
// 不随浅色 / 深色主题变 —— 底下是视频，主题色在这里没有意义。按工程约定页面里不许写字面量颜色 / 字号 / 圆角，
// 所以这几档全收在这里；值逐个照抄 iOS `Features/Remote/*.swift` 里的字面量（出处是设计侧批次 262 iOS 稿 01–06、
// tokens `remoteControl.iosSession / overlays / tierColor`）。
// 会话页上那几块浅底的整页 / 卡（等待页、覆盖卡、无人值守页）照稿用主题色（card / bg / text / muted），不在这里。
package com.geek.umbra.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 会话页的颜色（不随主题变）。 */
object UmbraRemoteColors {
    /** 画面底色（黑边那一块）。 */
    val stage = Color.Black
    /**
     * 浮起工具条 / 修饰键栏 / 接管条 / 轻提示的底（稿 `#2A251F`）。浮条叠 94%（[barAlpha]），接管条实底。
     * 批次 262：接管条不用橙 —— 橙是 Umbra 的主操作色，不该用来表示「有人在控这台电脑」。
     */
    val takeoverBg = Color(0xFF2A251F)
    const val barAlpha = 0.94f
    /** 看 / 控两色（tokens `remoteControl.tierColor`，跨主题不变）：查看 = 绿、控制 = 琥珀。 */
    val tierView = Color(0xFF34B5A6)
    val tierControl = Color(0xFFD98A29)
    /** 胶囊上的字用更亮的那一档：压在 20% 的同色软底上，原色字读不清。 */
    val tierViewText = Color(0xFF6FD3C6)
    val tierControlText = Color(0xFFF0B860)
    /** 档位胶囊的软底透明度（只看 20% / 控制 22%）。 */
    const val tierViewSoft = 0.2f
    const val tierControlSoft = 0.22f
    /** 覆盖卡底下那层深色蒙底（稿 rgb(15,13,11) 78%）。 */
    val coverScrim = Color(0xC70F0D0B)
    /** 「对方当前窗口受保护」那层蒙层。 */
    val veil = Color.Black.copy(alpha = 0.72f)
    /** 快照没拉到那一句的小胶囊底。 */
    val pill = Color.Black.copy(alpha = 0.7f)
    /** 前景主色。 */
    val fg = Color.White
    /** 工具图标（没开着时）。 */
    val fgTool = Color.White.copy(alpha = 0.86f)
    /** 连接状态胶囊里的字。 */
    val fgLink = Color.White.copy(alpha = 0.82f)
    /** 短码胶囊里的字。 */
    val fgCode = Color.White.copy(alpha = 0.75f)
    /** 触控板说明那一句、受保护蒙层副文。 */
    val fgHint = Color.White.copy(alpha = 0.62f)
    /** 受保护蒙层的锁图标。 */
    val fgLock = Color.White.copy(alpha = 0.7f)
    /** 收起工具条后顶边那颗把手。 */
    val handle = Color.White.copy(alpha = 0.5f)
    /** ［结束］胶囊、接管条［交还给秘书］的底。 */
    val pillStrong = Color.White.copy(alpha = 0.14f)
    /** 连接状态 / 短码 / 「适应屏幕」胶囊、键帽的底。 */
    val pillSoft = Color.White.copy(alpha = 0.08f)
    /** 锁定的修饰键底边那道亮线。 */
    val lockedLine = Color.White.copy(alpha = 0.55f)
    /** 只管一次的修饰键：橙 24% 底。 */
    const val modOnceAlpha = 0.24f
    /** 不可用的工具：照样占位、35% 不透明。 */
    const val disabledAlpha = 0.35f
    /** 设备详情 hero 卡下那两颗置灰时整颗的不透明度。 */
    const val heroDisabledAlpha = 0.45f
}

/** 远程控制各页的字号（iOS 里的 UmbraFont.sans(…) 逐个照抄；字重按平台差异答复：560→Medium、600/650→SemiBold）。 */
object UmbraRemoteType {
    /** 等宽数字（iOS `.monospacedDigit()`）：时长、毫秒、秒数、百分比跳动时不抖。 */
    private const val TNUM = "tnum"

    // 设备详情 hero 卡下那两颗 + 原因句
    val heroButton = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    val heroReason = TextStyle(fontSize = 13.sp, lineHeight = 21.45.sp)
    // 「远程连接」动作表
    val sheetTitle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    val sheetItem = TextStyle(fontSize = 16.sp)
    val sheetSub = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp)
    val sheetCancel = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)

    // 会话页工具条
    val endPill = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    val linkPill = TextStyle(fontSize = 12.sp, fontFeatureSettings = TNUM)
    val codePill = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
    val tierPill = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    val takeoverTitle = TextStyle(fontSize = 13.sp)
    val takeoverButton = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    val modKey = TextStyle(fontSize = 15.sp)
    val modKeyOn = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    val keyCap = TextStyle(fontSize = 15.sp)
    val keyCapWord = TextStyle(fontSize = 12.sp)
    val trackpadHint = TextStyle(fontSize = 12.sp)
    val hint = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val hintAction = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    val toast = TextStyle(fontSize = 13.sp, fontWeight = UmbraType.weight560)
    val snapshotStrip = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val snapshotFail = TextStyle(fontSize = 12.5.sp, fontWeight = UmbraType.weight560)
    val protectedTitle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    val protectedSub = TextStyle(fontSize = 12.sp, lineHeight = 17.sp)

    // 覆盖卡（重连中 / 已结束 / 没连上 / 连接中）
    val coverTitle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    val coverBody = TextStyle(fontSize = 15.sp, lineHeight = 24.75.sp, fontFeatureSettings = TNUM)
    val bigButton = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    val bigButtonPlain = TextStyle(fontSize = 17.sp)

    // 等待页
    val waitName = TextStyle(fontSize = 22.sp, fontWeight = UmbraType.weight650)
    val waitTitle = TextStyle(fontSize = 15.sp)
    val waitSub = TextStyle(fontSize = 13.sp, lineHeight = 21.45.sp, fontFeatureSettings = TNUM)

    // 无人值守页
    val unattendedTitle = TextStyle(fontSize = 20.sp, fontWeight = UmbraType.weight650)
    val unattendedBody = TextStyle(fontSize = 15.sp, lineHeight = 24.75.sp)
    val unattendedErr = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val safetyCode = TextStyle(fontSize = 44.sp, fontWeight = UmbraType.weight650, fontFamily = FontFamily.Monospace, letterSpacing = 8.sp)
    val keypadDigit = TextStyle(fontSize = 26.sp)
    val closeLink = TextStyle(fontSize = 17.sp)

    // 文件面板
    val panelTitle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    val panelDone = TextStyle(fontSize = 17.sp)
    val fileName = TextStyle(fontSize = 15.sp)
    val fileMeta = TextStyle(fontSize = 12.sp, fontFeatureSettings = TNUM)
    val filesEmpty = TextStyle(fontSize = 14.sp)
    val pushLabel = TextStyle(fontSize = 15.sp)
    val pushTo = TextStyle(fontSize = 12.5.sp)
    val transferStatus = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val transferPercent = TextStyle(fontSize = 13.sp, fontFeatureSettings = TNUM)
    val rowAction = TextStyle(fontSize = 15.sp)
    val filesNote = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val small = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
}

/** 远程控制各页的尺寸（稿上的 pt 照搬成 dp；热区按平台差异答复取 48，视觉尺寸不变）。 */
object UmbraRemoteDimens {
    /** 工具条浮块 / 横屏左列的圆角（稿 16）。 */
    val barRadius = 16.dp
    /** 胶囊（结束 / 连接状态 / 档位 / 短码）的视觉高。 */
    val pillH = 32.dp
    /** 一颗工具的视觉圆（开着时实心橙）。 */
    val toolFace = 34.dp
    /** 工具图标（iOS 17pt 符号）。 */
    val toolIcon = 20.dp
    /** 横屏左列宽（稿 56）。 */
    val sideColumnW = 56.dp
    /** 把手：46×5 的胶囊，热区 88 宽。 */
    val handleW = 46.dp
    val handleH = 5.dp
    val handleHitW = 88.dp
    /** 状态点（连接状态 7、接管条 8）。 */
    val linkDot = 7.dp
    val takeoverDot = 8.dp
    /** 修饰键栏：浮起时圆角 14、离底 28；键帽视觉 36×32、圆角 8，锁定亮线 2。 */
    val modBarRadius = 14.dp
    val modBarBottom = 28.dp
    val keyMinW = 36.dp
    val keyH = 32.dp
    val keyRadius = 8.dp
    val lockedLineH = 2.dp
    /** 覆盖卡：圆角 14、内距 20、外距 24、宽封顶 420；钮 50 高、圆角 12。 */
    val coverRadius = 14.dp
    val coverPad = 20.dp
    val coverOuter = 24.dp
    val coverMaxW = 420.dp
    val bigButtonH = 50.dp
    val bigButtonRadius = 12.dp
    /** 覆盖卡标题前那颗图标。 */
    val coverIcon = 18.dp
    /** 等待页设备块 72、圆角 18、图标 30。 */
    val waitBlock = 72.dp
    val waitBlockRadius = 18.dp
    val waitIcon = 30.dp
    /** 无人值守页图标块 56、圆角 14、图标 26。 */
    val lockBlock = 56.dp
    val lockBlockRadius = 14.dp
    val lockIcon = 26.dp
    /** PIN 点 13、间距 14、空心描边 1.5。 */
    val pinDot = 13.dp
    val pinDotGap = 14.dp
    val pinDotStroke = 1.5.dp
    /** 数字键盘：键高 62（胶囊），列距 22、行距 12、左右各 44。 */
    val keypadKeyH = 62.dp
    val keypadColGap = 22.dp
    val keypadRowGap = 12.dp
    val keypadPadX = 44.dp
    /** hero 卡下那两颗：50 高、圆角 12、图标 17。 */
    val heroButtonH = 50.dp
    val heroButtonRadius = 12.dp
    val heroIcon = 17.dp
    /** 文件行：最小高 56、进度条 4。 */
    val fileRowMinH = 56.dp
    /** 蒙层（受保护）内容最宽。 */
    val veilMaxW = 360.dp
    /** 虚拟光标图标尺寸（iOS 22pt）。 */
    val cursor = 22.dp
}
