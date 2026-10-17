// 远程会话页（全屏画面层）专用的 token —— 只有远程控制模块用。
//
// 为什么单开一份：会话页铺在一块别人电脑的画面上，iOS 用的是「黑底 + 白字 + 几档白 / 黑的透明度」
// （RemoteSessionView.swift：工具条 black .62、修饰键栏 .7、蒙层 .72、键帽 white .12、次级文字 white .75 / .85），
// 不随浅色 / 深色主题变 —— 底下是视频，主题色在这里没有意义。按工程约定页面里不许写字面量颜色 / 字号 / 圆角，
// 所以这几档全收在这里；值逐个照抄 iOS 那个文件里的字面量。
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
    /** 顶部工具条底。 */
    val bar = Color.Black.copy(alpha = 0.62f)
    /** 修饰键栏底。 */
    val modBar = Color.Black.copy(alpha = 0.7f)
    /** 等待 / 重连 / 已结束蒙层。 */
    val veil = Color.Black.copy(alpha = 0.72f)
    /** 快照没拉到那一句的小胶囊底。 */
    val pill = Color.Black.copy(alpha = 0.7f)
    /** 前景主色。 */
    val fg = Color.White
    /** 工具条上的状态字（路径 · RTT · 帧率）。 */
    val fgStrong = Color.White.copy(alpha = 0.85f)
    /** 蒙层副文。 */
    val fgSub = Color.White.copy(alpha = 0.75f)
    /** 已结束那一句原因。 */
    val fgEnd = Color.White.copy(alpha = 0.8f)
    /** 收起工具条后顶边那颗把手。 */
    val handle = Color.White.copy(alpha = 0.5f)
    /** 键帽 / 修饰键未锁定的底。 */
    val keyCap = Color.White.copy(alpha = 0.12f)
    /** 蒙层上的次要按钮底。 */
    val veilButton = Color.White.copy(alpha = 0.18f)
    /** 锁定的修饰键：白底黑字。 */
    val keyLockedFg = Color.Black
}

/** 会话页的字号（iOS RemoteSessionView 里的 UmbraFont.sans(…) 逐个照抄）。 */
object UmbraRemoteType {
    val barStatus = TextStyle(fontSize = 12.sp, fontWeight = UmbraType.weight560)
    val barCode = TextStyle(fontSize = 12.sp, fontWeight = UmbraType.weight560, fontFamily = FontFamily.Monospace)
    val takeoverTitle = TextStyle(fontSize = 13.sp, fontWeight = UmbraType.weight560)
    val takeoverButton = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    val modKey = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    val keyCap = TextStyle(fontSize = 15.sp, fontWeight = UmbraType.weight560)
    val veilTitle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    val veilState = TextStyle(fontSize = 15.sp, fontWeight = UmbraType.weight560)
    val veilSub = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val endTitle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    val endSub = TextStyle(fontSize = 13.5.sp, lineHeight = 19.sp)
    val veilButton = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    val toast = TextStyle(fontSize = 13.sp, fontWeight = UmbraType.weight560)
    val snapshotFail = TextStyle(fontSize = 12.5.sp, fontWeight = UmbraType.weight560)
    val pinTitle = TextStyle(fontSize = 18.sp, fontWeight = UmbraType.weight650)
    val pinField = TextStyle(fontSize = 22.sp, fontWeight = UmbraType.weight560, fontFamily = FontFamily.Monospace, letterSpacing = 4.sp)
    val pinError = TextStyle(fontSize = 12.5.sp, fontWeight = UmbraType.weight560)
    val small = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val panelTitle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    val fileName = TextStyle(fontSize = 15.sp)
    val transferName = TextStyle(fontSize = 14.5.sp)
    val transferAction = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
    val mono12 = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace)
}

/** 会话页的尺寸。 */
object UmbraRemoteDimens {
    /** 键帽 / 修饰键的圆角（iOS cornerRadius 8）。 */
    val keyRadius = 8.dp
    /** 「只管一次」修饰键的描边宽。 */
    val keyOnceStroke = 1.5.dp
    /** 工具条按钮的图标（iOS 17pt 符号）。 */
    val barIcon = 20.dp
    /** 蒙层大图标（iOS 34pt）。 */
    val veilIcon = 34.dp
    /** 蒙层内容最宽。 */
    val veilMaxW = 360.dp
    /** 把手：46×5 的胶囊，热区 88×44。 */
    val handleW = 46.dp
    val handleH = 5.dp
    val handleHitW = 88.dp
    /** 虚拟光标图标尺寸（iOS 22pt）。 */
    val cursor = 22.dp
}
