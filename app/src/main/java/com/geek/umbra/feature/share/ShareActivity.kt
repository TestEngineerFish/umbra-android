// 系统分享面板里的「记到 Umbra 灵感」与「用电脑转换」入口 —— 对应 iOS ShareExtension/ShareViewController.swift + ShareInspirationView。
//
// 在浏览器读到一段、在聊天 App 看到一条、在相册里翻到一张截图 —— 分享里点 Umbra，
// 一张底部卡片预填好分享进来的东西，点保存就记成一条灵感，人留在原来那个 App 里。
//
// 透明 Activity（Theme.Umbra.Share）+ Compose 画底部卡片；excludeFromRecents + 空 taskAffinity，
// 关掉就回到分享来源，不在最近任务里留一张。**只有存上了才 finish**；取消 / 返回键直接关。
package com.geek.umbra.feature.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.app.MainActivity
import com.geek.umbra.designsystem.Appearance
import com.geek.umbra.designsystem.UmbraButton
import com.geek.umbra.designsystem.UmbraButtonKind
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraErrorStrip
import com.geek.umbra.designsystem.UmbraFixedColors
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraSwitch
import com.geek.umbra.designsystem.UmbraTextField
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraTone
import com.geek.umbra.designsystem.UmbraTopBarText
import com.geek.umbra.designsystem.UmbraType
import com.geek.umbra.designsystem.UmbraSegment
import com.geek.umbra.designsystem.UmbraSegmentedControl
import com.geek.umbra.feature.convert.ConvertModel
import com.geek.umbra.feature.convert.ConvertPanel
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.geek.umbra.net.Prefs

class ShareActivity : AppCompatActivity() {
    private val model: ShareModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // ViewModel 跨旋转活着；load 自己幂等，转屏不会再读一遍。
        model.load(contentResolver, intent)
        setContent {
            val prefs by AppGraph.prefs.snapshot.collectAsState()
            UmbraTheme(Appearance.of(prefs[Prefs.K.appearance])) {
                LaunchedEffect(model.finished) {
                    if (model.finished) {
                        // 面板关了人就回到原来的 App：给一句系统吐司说「记下了」（App 内吐司在这里看不见）。
                        Toast.makeText(applicationContext, getString(R.string.share_insp_saved), Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
                BackHandler(enabled = model.saving) { /* 保存中不让返回键关面板：请求还在路上，关了就不知道成没成 */ }
                ShareRoot(model, onClose = { finish() }, onOpenApp = {
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish()
                })
            }
        }
    }
}

/**
 * 面板的根（iOS ShareViewController.ShareRootView，批次 269 §四）：分享进来的是**文件** → 第一屏直接是「用电脑转换」
 * （Umbra 现有的那几件只收文字、网址、图片，文件进来只有转换这一件事）；有图 → 底部一个切换（记灵感 / 用电脑转换），默认记灵感；
 * 只有文字 / 链接 → 只有记灵感（和原来一样）。
 */
@Composable
private fun ShareRoot(model: ShareModel, onClose: () -> Unit, onOpenApp: () -> Unit) {
    var converting by rememberSaveable { mutableStateOf(false) }
    val canSwitch = !model.loading && !model.fileOnly && model.convertInput != null
    val switcher: @Composable () -> Unit = {
        if (canSwitch) {
            UmbraSegmentedControl(
                listOf(UmbraSegment(stringResource(R.string.share_conv_modeInsp)), UmbraSegment(stringResource(R.string.share_conv_modeConvert))),
                if (converting) 1 else 0, { converting = it == 1 },
                Modifier.padding(horizontal = UmbraDimens.pagePadX, vertical = 8.dp),
            )
        }
    }
    val cm = if (!model.loading && (model.fileOnly || converting)) model.convertModel() else null
    if (cm != null) ConvertCard(cm, onClose, switcher) else ShareSheet(model, onCancel = onClose, onOpenApp = onOpenApp, switcher = switcher)
}

/** 转换面板放进同一张底部卡片（同记灵感那张的外形：scrim + 顶部圆角 card）。「打开远程控制」「选文件」不给（见 ConvertPanel 的参数说明）。 */
@Composable
private fun ConvertCard(cm: ConvertModel, onClose: () -> Unit, switcher: @Composable () -> Unit) {
    val c = UmbraTheme.colors
    val sheet = RoundedCornerShape(topStart = UmbraDimens.radiusSheet, topEnd = UmbraDimens.radiusSheet)
    Box(
        Modifier.fillMaxSize().background(UmbraFixedColors.scrim).clickable(remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().statusBarsPadding().clip(sheet).background(c.bg)
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .navigationBarsPadding(),
        ) {
            ConvertPanel(cm, onClose, Modifier.fillMaxWidth().weight(1f, fill = false), closeOnDispose = false)
            switcher()
        }
    }
}

/** key → 那一句。分享面板的文案在 strings_share.xml（iOS 扩展自己的字符串表）。 */
@Composable
private fun shareText(key: String): String = stringResource(
    when (key) {
        "share.insp.saved" -> R.string.share_insp_saved
        "share.insp.signedOut" -> R.string.share_insp_signedOut
        "share.insp.empty" -> R.string.share_insp_empty
        "share.insp.notConfigured" -> R.string.share_insp_notConfigured
        "share.insp.offline" -> R.string.share_insp_offline
        "share.insp.rejected" -> R.string.share_insp_rejected
        "share.insp.serverError" -> R.string.share_insp_serverError
        "share.insp.tooLarge" -> R.string.share_insp_tooLarge
        "share.insp.renewInApp" -> R.string.share_insp_renewInApp
        else -> R.string.share_insp_failed
    },
)

/** 面板本体：标题栏（取消 / 标题 / 保存）、失败那一句、正文、缩略图、「帮我查查」。 */
@Composable
private fun ShareSheet(model: ShareModel, onCancel: () -> Unit, onOpenApp: () -> Unit, switcher: @Composable () -> Unit = {}) {
    val c = UmbraTheme.colors
    val sheet = RoundedCornerShape(topStart = UmbraDimens.radiusSheet, topEnd = UmbraDimens.radiusSheet)
    Box(
        Modifier.fillMaxSize().background(UmbraFixedColors.scrim)
            // 点卡片外面不关：误触一下内容就没了，没有草稿可找（iOS 系统面板也要明确点「取消」或下拉）。
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().statusBarsPadding().clip(sheet).background(c.card)
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .navigationBarsPadding().imePadding(),
        ) {
            // 标题栏
            Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                UmbraTopBarText(stringResource(R.string.share_insp_cancel), onCancel, enabled = !model.saving)
                Text(
                    stringResource(R.string.share_insp_title), color = c.text, style = UmbraType.sectionTitle,
                    modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1,
                )
                if (model.saving) {
                    Box(Modifier.size(UmbraDimens.tapMin), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = c.orange, strokeWidth = 2.dp)
                    }
                } else UmbraTopBarText(stringResource(R.string.share_insp_save), { model.save() }, enabled = !model.loading, strong = true)
            }
            model.messageKey?.let { key ->
                UmbraErrorStrip(shareText(key), tone = UmbraTone.Warning)
            }
            Column(
                Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())
                    .padding(horizontal = UmbraDimens.pagePadX, vertical = UmbraDimens.sp4),
            ) {
                Box {
                    UmbraTextField(
                        model.text, { model.text = it }, singleLine = false, minHeight = 140.dp,
                        placeholder = if (model.loading) "" else stringResource(R.string.share_insp_placeholder),
                    )
                    if (model.loading) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center).size(22.dp), color = c.orange, strokeWidth = 2.dp)
                    }
                }
                if (model.images.isNotEmpty() || model.dropped > 0) {
                    Spacer(Modifier.height(UmbraDimens.sp4))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        model.images.forEach { img -> Thumb(img, tooLarge = model.tooLargeId == img.id, enabled = !model.saving) { model.remove(img.id) } }
                    }
                    ShareInspRules.droppedKey(model.dropped)?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.share_insp_dropped, model.dropped), color = c.muted, style = UmbraType.rowSub)
                    }
                }
                Spacer(Modifier.height(UmbraDimens.sp4))
                Row(Modifier.fillMaxWidth().heightIn(min = UmbraDimens.rowMinH), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.share_insp_research), color = c.text, style = UmbraType.rowTitle, modifier = Modifier.weight(1f))
                    UmbraSwitch(model.research, { model.research = it }, enabled = !model.saving)
                }
                // Android 独有：未登录时给一颗「打开 Umbra」（iOS 扩展打不开宿主 App，只能说一句）。
                if (model.messageKey == "share.insp.signedOut") {
                    Spacer(Modifier.height(UmbraDimens.sp4))
                    UmbraButton(stringResource(R.string.share_insp_openApp), onOpenApp, Modifier.fillMaxWidth(), kind = UmbraButtonKind.Secondary)
                }
            }
            switcher()
        }
    }
}

/** 一格缩略图（64）。读不出缩略图时画一个占位 —— 图照样会传，只是这里看不到。右上角「去掉这张」热区撑到 44。 */
@Composable
private fun Thumb(img: ShareImage, tooLarge: Boolean, enabled: Boolean, onRemove: () -> Unit) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.padding(top = 10.dp, end = 10.dp)) {
        Box(
            Modifier.size(64.dp).clip(shape).background(c.chip)
                // 被 413 拒掉的那一张描一圈：那一句说「去掉那张」，得让人看得出是哪张。
                .then(if (tooLarge) Modifier.border(2.dp, c.orange, shape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            val t = img.thumb
            if (t != null) Image(t, contentDescription = stringResource(R.string.share_insp_image), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else UmbraIcon(UmbraIconPaths.image, c.muted, size = 22.dp, contentDescription = stringResource(R.string.share_insp_image))
        }
        Box(
            Modifier.align(Alignment.TopEnd).offset(x = 17.dp, y = (-17).dp).size(UmbraDimens.tapMin)
                .clickable(enabled = enabled, role = Role.Button, onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(UmbraFixedColors.scrim), contentAlignment = Alignment.Center) {
                UmbraIcon(UmbraIconPaths.x, UmbraFixedColors.onNavText, size = 12.dp, contentDescription = stringResource(R.string.share_insp_removeImage))
            }
        }
    }
}
