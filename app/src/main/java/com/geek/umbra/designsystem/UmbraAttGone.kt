// 「附件已删除」占位（iOS: DesignSystem/UmbraAttGone.swift；设计侧批次 235 §二：删文件那句确认写的是
// 「引用它的聊天或账目会显示「附件已删除」」—— 这就是那一行字落的地方）。
//
// 放 designsystem 而不是某个 feature：聊天图片格、记账凭证、提醒附件三处共用。从前三处各画一份，灰度（faint / muted）、
// 字号（10.5 / 11）、有没有图标都已经分叉 —— 收成这一份（开发文档第五节技术债余项）。
//
// 只在取图失败那一档用：服务端 404 和断网在那一档里分不开，所以断网时也会出现 —— 断网时屏上别处已经有离线条说明，这里不再多一句。
// 全屏看图器（黑底）那种失败另说，不用它。
package com.geek.umbra.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geek.umbra.R

/** 灰底 + 图片图标 +「附件已删除」，铺满所在格子。[radius] 跟着格子走（聊天格 0、记账 / 提醒缩略图 13 / 12）。 */
@Composable
fun UmbraAttGonePlaceholder(radius: Dp = 0.dp, modifier: Modifier = Modifier) {
    val c = UmbraTheme.colors
    Column(
        modifier.fillMaxSize().clip(RoundedCornerShape(radius)).background(c.chip).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        UmbraIcon(UmbraIconPaths.image, c.faint, size = 16.dp, strokeWidth = 1.8f)
        // 这一句是在回答「图呢」：用 muted，不是装饰用的 faint（227 §二2）。一行放不下就缩，最小 0.8 倍（iOS minimumScaleFactor(0.8)）。
        BasicText(
            stringResource(R.string.common_attGone),
            style = TextStyle(color = c.muted, fontSize = 10.5.sp),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 10.5.sp * 0.8f, maxFontSize = 10.5.sp, stepSize = 0.5.sp),
        )
    }
}
