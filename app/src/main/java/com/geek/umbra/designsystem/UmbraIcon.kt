package com.geek.umbra.designsystem

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.concurrent.ConcurrentHashMap

/**
 * 把 24 viewBox 的描边 path 变成 ImageVector，按 (path, 线宽) 缓存。
 *
 * 不用 Material Icons：交接文档要求「fill:none、stroke 1.8–2.2、圆头、Lucide 风格」，
 * Material 的填充式图标视觉重心对不上，混用会让同一屏不像一套（iOS 不用 SF Symbols 是同一个理由）。
 * 线宽写在 viewBox 坐标里（24 为基准），所以 12dp 和 26dp 的观感一致。
 */
private val cache = ConcurrentHashMap<String, ImageVector>()

fun umbraVector(d: String, strokeWidth: Float = 1.9f): ImageVector =
    cache.getOrPut("$strokeWidth|$d") {
        ImageVector.Builder(
            defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = strokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

/**
 * 线性图标。`tint` 必填：图标一律配文字，颜色由语境决定，没有「默认色」可猜。
 * contentDescription 默认 null（装饰性）；**纯图标按钮必须给读屏名称**，由按钮那一层给。
 */
@Composable
fun UmbraIcon(
    d: String,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    strokeWidth: Float = 1.9f,
    contentDescription: String? = null,
) {
    Icon(
        imageVector = umbraVector(d, strokeWidth),
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size),
    )
}

/** 匀速旋转图标（1s linear 无限）。「看起来在动的东西必须真的在动」—— 做成组件免得哪页忘了加动画。 */
@Composable
fun UmbraSpinningIcon(d: String, tint: Color, size: Dp = 16.dp, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "spin")
    val deg = t.animateFloat(
        0f, 360f, infiniteRepeatable(tween(UmbraMotion.spin, easing = LinearEasing), RepeatMode.Restart),
        label = "deg",
    )
    UmbraIcon(d, tint, modifier.rotate(deg.value), size)
}

/**
 * 圆角方块里的图标（行徽标 34、详情头 44、48）。图标为块的 0.45；块 ≥40 圆角 18，否则 10（iOS 同）。
 */
@Composable
fun UmbraIconBlock(
    d: String,
    modifier: Modifier = Modifier,
    block: Dp = UmbraDimens.iconBlockMD,
    bg: Color = UmbraTheme.colors.chip,
    fg: Color = UmbraTheme.colors.muted,
    icon: Dp? = null,
) {
    val corner = if (block >= 40.dp) 18.dp else 10.dp
    Box(
        modifier.size(block).clip(RoundedCornerShape(corner)).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        UmbraIcon(d, fg, size = icon ?: (block * 0.45f))
    }
}

/**
 * iOS 图标表里没有、Android 侧要用的少数图标。同样是 24 viewBox 的 Lucide 描边形，
 * 等 iOS 也用上时挪进 UmbraIconPath.swift 再重新生成。
 */
object UmbraExtraIcons {
    /** Lucide more-horizontal：三个半径 1 的圆点（用两段贝塞尔近似整圆）。 */
    const val more = "M5,12C5,11.45 5.45,11 6,11C6.55,11 7,11.45 7,12C7,12.55 6.55,13 6,13C5.45,13 5,12.55 5,12Z" +
        "M11,12C11,11.45 11.45,11 12,11C12.55,11 13,11.45 13,12C13,12.55 12.55,13 12,13C11.45,13 11,12.55 11,12Z" +
        "M17,12C17,11.45 17.45,11 18,11C18.55,11 19,11.45 19,12C19,12.55 18.55,13 18,13C17.45,13 17,12.55 17,12Z"
    /** 拖动排序把手（六点）。 */
    const val grip = "M9,6L9.01,6M15,6L15.01,6M9,12L9.01,12M15,12L15.01,12M9,18L9.01,18M15,18L15.01,18"
    /** 相机。 */
    const val camera = "M4,8L7,8L9,5L15,5L17,8L20,8C20.55,8 21,8.45 21,9L21,19C21,19.55 20.55,20 20,20L4,20C3.45,20 3,19.55 3,19L3,9C3,8.45 3.45,8 4,8ZM12,10C10.34,10 9,11.34 9,13C9,14.66 10.34,16 12,16C13.66,16 15,14.66 15,13C15,11.34 13.66,10 12,10Z"
}
