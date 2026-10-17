// 小组件用的配色与几件共用小部件 —— 对应 iOS UmbraWidgets/WidgetTheme.swift（WTheme + WSignedOutView）。
//
// 为什么不用 UmbraTheme：Glance 不是 Compose UI，没有 CompositionLocal 的 UmbraTheme.colors 可读，
// 而且小组件跟着**系统**深浅色走（iOS 也一样，不读 App 内的外观设置）。所以这里逐个从 UmbraLight / UmbraDark
// 取值，包成 Glance 的 day/night ColorProvider —— 值仍然只有 UmbraTokens.kt 那一份，改主题色不用两边改。
package com.geek.umbra.feature.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.geek.umbra.R
import com.geek.umbra.app.MainActivity
import com.geek.umbra.designsystem.UmbraDark
import com.geek.umbra.designsystem.UmbraLight

/** 小组件那七八个颜色（iOS: WidgetTheme.swift `WTheme`），按系统深浅色自动切。 */
internal object WTheme {
    /** 品牌橙，跨主题不变。 */
    val orange = ColorProvider(UmbraLight.orange, UmbraDark.orange)
    /** 橙色文字：深色下提亮保对比。 */
    val orangeText = ColorProvider(UmbraLight.orangeText, UmbraDark.orangeText)
    val card = ColorProvider(UmbraLight.card, UmbraDark.card)
    val chip = ColorProvider(UmbraLight.chip, UmbraDark.chip)
    val text = ColorProvider(UmbraLight.text, UmbraDark.text)
    val muted = ColorProvider(UmbraLight.muted, UmbraDark.muted)
    val faint = ColorProvider(UmbraLight.faint, UmbraDark.faint)
    /** 过期的圈（实心那支给描边）。 */
    val danger = ColorProvider(UmbraLight.danger, UmbraDark.danger)
    /** 过期的时间文字（规则：文字一律走 *Text 那支）。 */
    val dangerText = ColorProvider(UmbraLight.dangerText, UmbraDark.dangerText)
    /** 字标「U」上的白字：橙底上两个主题都是白（iOS `.white`）。 */
    val onOrange = ColorProvider(UmbraLight.card)
}

/** 点小组件 = 打开 MainActivity 并带上 umbra:// 深链（MainActivity.handle → DeepLinks.receive，门关时要登录的页会被丢弃）。 */
internal fun deepLinkAction(context: Context, uri: String): Action =
    actionStartActivity(
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse(uri))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
    )

/** 橙底「U」字标（iOS: 16pt、圆角 5 的橙底 + 9pt 粗体白字；未登录那张用 22pt / 圆角 6 / 12pt）。 */
@Composable
internal fun WWordmark(big: Boolean = false) {
    val side = if (big) 22.dp else 16.dp
    Box(
        GlanceModifier.size(side).background(WTheme.orange).cornerRadius(if (big) 6.dp else 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        // 出图 #10 字标是 650 → Bold（Glance 只有三档字重）。
        Text("U", style = TextStyle(color = WTheme.onOrange, fontSize = if (big) 12.sp else 9.sp, fontWeight = FontWeight.Bold))
    }
}

/**
 * 小组件左上角那一行：字标 + 名字（iOS 两个小组件的 header），右侧可挂一句（「N 条」/「更新于 HH:mm」）。
 * 出图 #10：名字 10.5/600、字距 .06em、faint。Glance 只有 Normal / Medium / Bold 三档字重，600 按答复的全局规则退 Bold；
 * Glance 的 TextStyle 没有字距，这一项做不到。
 */
@Composable
internal fun WHeader(label: String, trailing: String? = null, modifier: GlanceModifier = GlanceModifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        WWordmark()
        Spacer(GlanceModifier.width(6.dp))
        Text(label, style = TextStyle(color = WTheme.faint, fontSize = 10.5.sp, fontWeight = FontWeight.Bold), maxLines = 1, modifier = GlanceModifier.defaultWeight())
        if (trailing != null) Text(trailing, style = TextStyle(color = WTheme.faint, fontSize = 10.5.sp), maxLines = 1)
    }
}

/**
 * 未登录占位（iOS: WidgetTheme.swift `WSignedOutView`，设计侧批次 217 §二⒝ 的正式样式）：
 * 卡底色不变，左上字标，下方锁 +「请登录 Umbra」；**不画任何数据**（不画「0」、不画空进度条 ——
 * 那些读起来像「你今天没有任务」，事实是「这台设备没登录，看不到」）。整卡一个点击区，挂在调用处。
 * 只有任务小组件会用到：提醒是本地功能（2026-10-10），未登录照画。
 */
@Composable
internal fun WSignedOut(modifier: GlanceModifier) {
    Column(modifier) {
        WWordmark(big = true)
        Spacer(GlanceModifier.defaultWeight())
        Image(
            ImageProvider(R.drawable.ic_widget_lock), contentDescription = null,
            modifier = GlanceModifier.size(18.dp), colorFilter = ColorFilter.tint(WTheme.muted),
        )
        Spacer(GlanceModifier.height(6.dp))
        Text("请登录 Umbra", style = TextStyle(color = WTheme.text, fontSize = 15.sp, fontWeight = FontWeight.Medium), maxLines = 1)
    }
}

/**
 * 卡片底（iOS `.containerBackground(for: .widget) { WTheme.card }`）。第 199 封 #10：圆角交系统裁切 ——
 * Android 12+ 标成 appWidgetBackground 并取系统半径 system_app_widget_background_radius，不自绘圆角、不加阴影；
 * 12 以下 Glance 不应用圆角，由启动器自己处理。card 底随系统深浅色（WTheme.card 是 day/night 一对）。
 */
internal fun GlanceModifier.widgetCard(): GlanceModifier =
    fillMaxSize().appWidgetBackground().background(WTheme.card).cornerRadius(android.R.dimen.system_app_widget_background_radius)
