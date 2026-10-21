// 「用电脑转换」面板（设计侧批次 269 §四 01 / 02 / 03 + 271 + 272，照 iOS `Features/Convert/ConvertViews.swift`）。
//
// 一张面板两处用：分享透明 Activity 收到文件时的第一屏，和主 App 设备详情页「用这台电脑转换文件」选完文件后弹的那一张（电脑已经选好）。
// 不用 UmbraScreen：这是**模态面板**（分享里是底部卡片、App 里是底部弹层），不在导航栈里。
//
// 和 iOS 的出入（平台差异，见文件里各处注释）：
//   - 「存到文件」= 系统「新建文档」（SAF，ACTION_CREATE_DOCUMENT），iOS 是 UIDocumentPicker 导出 —— 同一件事；
//   - 「存到相册」= MediaStore（Pictures/Umbra、Movies/Umbra），Android 9 要存储权限，没给就如实说没存进；
//   - 「分享」= 系统分享面板（FileProvider `${applicationId}.files` 交出 cacheDir 里的成品）。
package com.geek.umbra.feature.convert

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.geek.umbra.R
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraSpinningIcon
import com.geek.umbra.designsystem.UmbraStripAction
import com.geek.umbra.designsystem.UmbraTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 面板本体。
 * @param onClose 关面板（× / 知道了）。
 * @param onOpenRemote 「文件太大」那一档的「打开远程控制」：主 App 里交给设备详情页去开远程连接；分享里是 null —— 那颗钮换成「知道了」，
 *   正文换成把路说全的那句（`tooBigBodyExt`，批次 271）。Android 的分享 Activity 其实打得开主 App，但照 iOS 同一套话术与钮，
 *   免得人在分享面板里被一路带进远程控制。
 * @param onPickFile 「作业没了」那屏的［选文件］（批次 272）：主 App 里交给设备详情页重开文件选择器；分享里是 null，只给［知道了］。
 * @param closeOnDispose 面板离开组合时停轮询。分享里传 false：模型挂在 ViewModel 上，转屏、切到「记灵感」再切回来面板会重建，
 *   不能把正在盯的作业掐了（ShareModel.onCleared 才停）。
 */
@Composable
fun ConvertPanel(
    model: ConvertModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenRemote: (() -> Unit)? = null,
    onPickFile: ((ConvertModel.Repick) -> Unit)? = null,
    closeOnDispose: Boolean = true,
) {
    val c = UmbraTheme.colors
    var savedLine by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { model.start() }
    // 下拉 / 返回关掉面板不走 ×：这里也停轮询（作业在服务端照常转完，回来时横条会说）。
    DisposableEffect(Unit) { onDispose { if (closeOnDispose) model.close() } }
    // 「存到文件」：系统「新建文档」让人挑位置，写一份副本过去。
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val f = model.outcome?.file ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } } != null }.getOrDefault(false) }
            if (ok) savedLine = convText(R.string.share_conv_savedFiles)
        }
    }

    Column(modifier.fillMaxWidth().background(c.bg)) {
        Header(model, onClose)
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                .padding(start = UmbraDimens.pagePadX, end = UmbraDimens.pagePadX, top = 16.dp, bottom = 24.dp),
        ) {
            when (model.screen) {
                ConvertModel.Screen.Pick -> PickScreen(model, tooBigBody = convText(if (onOpenRemote == null) R.string.share_conv_tooBigBodyExt else R.string.share_conv_tooBigBody, ConvertRules.MAX_MB))
                ConvertModel.Screen.Run -> RunScreen(model)
                ConvertModel.Screen.Result -> ResultScreen(model, savedLine)
            }
        }
        val actions = actionsOf(model, onClose, onOpenRemote, onPickFile,
            exportTo = { exporter.launch(it.name) },
            share = { shareFile(ctx, it) },
            savePhotos = { o -> scope.launch { o.file?.let { f -> savedLine = convText(if (saveToGallery(f, o.kind)) R.string.share_conv_savedPhotos else R.string.share_conv_savePhotosFailed) } } },
            clearSaved = { savedLine = null },
        )
        Column(Modifier.padding(start = UmbraDimens.pagePadX, end = UmbraDimens.pagePadX, top = 10.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.forEach { BigButton(it) }
        }
    }
}

// MARK: 头

/** 52 高：居中标题 + 右边一颗 ×（30 的灰圆，热区 48）。传文件那一下不给 ×：关面板就把上传掐断了，那一屏的出口是［停止］。 */
@Composable
private fun Header(model: ConvertModel, onClose: () -> Unit) {
    val c = UmbraTheme.colors
    val title = if (model.screen == ConvertModel.Screen.Run) stringResource(R.string.share_conv_runTitle, model.jobName, model.jobTo)
    else stringResource(R.string.share_conv_title)
    val close = stringResource(R.string.share_conv_close)
    Box(Modifier.fillMaxWidth().height(52.dp)) {
        Text(
            title, color = c.text, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp),
        )
        if (!(model.screen == ConvertModel.Screen.Run && model.steps[0] == ConvertModel.Step.Run)) {
            Box(
                Modifier.align(Alignment.CenterEnd).padding(end = 6.dp).size(UmbraDimens.tapMin)
                    .clickable { model.close(); onClose() }.semantics { contentDescription = close },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(30.dp).clip(CircleShape).background(c.chip), contentAlignment = Alignment.Center) {
                    UmbraIcon(UmbraIconPaths.x, c.muted, size = 13.dp, strokeWidth = 2.4f)
                }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp).background(c.borderSoft))
    }
}

// MARK: 01 选格式

@Composable
private fun PickScreen(model: ConvertModel, tooBigBody: String) {
    val c = UmbraTheme.colors
    val devName = model.device?.name.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        FileCard(model)
        // 没电脑在线 / 文件太大 / 还在问在线列表这几档不出电脑那一组（稿 showPc）。
        val showPcs = model.computers.isNotEmpty() && model.pick !in listOf(ConvertModel.Pick.NoComputer, ConvertModel.Pick.TooBig, ConvertModel.Pick.Loading)
        if (showPcs) ComputerSection(model)
        when (val p = model.pick) {
            ConvertModel.Pick.Loading -> SpinnerCard(stringResource(R.string.share_conv_looking))
            ConvertModel.Pick.Asking -> SpinnerCard(stringResource(R.string.share_conv_asking, devName))
            ConvertModel.Pick.Ready -> FormatSection(model)
            ConvertModel.Pick.NoReply -> ErrorCard(UmbraIconPaths.alertTriangle, c.warningText, stringResource(R.string.share_conv_noReplyTitle, devName),
                stringResource(R.string.share_conv_noReplyBody, ConvertRules.ASK_TIMEOUT_SECONDS))
            ConvertModel.Pick.NoComputer -> ErrorCard(UmbraIconPaths.displayOff, c.muted, stringResource(R.string.share_conv_noComputerTitle), stringResource(R.string.share_conv_noComputerBody))
            ConvertModel.Pick.TooBig -> ErrorCard(UmbraIconPaths.xCircle, c.dangerText, stringResource(R.string.share_conv_tooBigTitle), tooBigBody)
            is ConvertModel.Pick.Failed -> ErrorCard(UmbraIconPaths.alertTriangle, c.warningText, stringResource(R.string.share_conv_failedTitle), p.why)
        }
    }
}

/** 文件卡：扩展名方章 + 名字 + 大小（太大时那一行红字写「180 MB · 超过 32 MB」）。 */
@Composable
private fun FileCard(model: ConvertModel) {
    val c = UmbraTheme.colors
    val name = model.input?.name ?: model.jobName
    val bytes = model.input?.bytes ?: 0
    val tooBig = model.pick == ConvertModel.Pick.TooBig
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.card).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Badge(ConvertRules.badge(name))
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, color = c.text, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (bytes > 0) Text(
                if (tooBig) stringResource(R.string.share_conv_tooBigSize, ConvertRules.sizeText(bytes), ConvertRules.MAX_MB) else ConvertRules.sizeText(bytes),
                color = if (tooBig) c.dangerText else c.muted, style = TextStyle(fontSize = 13.sp, fontFeatureSettings = "tnum"),
            )
        }
    }
}

/** 40 方章：扩展名大写。 */
@Composable
private fun Badge(s: String) {
    val c = UmbraTheme.colors
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(9.dp)).background(c.chip), contentAlignment = Alignment.Center) {
        Text(s, color = c.muted, style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun GroupHead(s: String) {
    Text(s, color = UmbraTheme.colors.muted, style = TextStyle(fontSize = 13.sp), modifier = Modifier.padding(horizontal = 16.dp))
}

/** 电脑那一组：一台在线时直接用它（组头「转换用的电脑」、不给勾选）；多台时「选一台电脑」+ 勾选，离线的置灰、点不动。 */
@Composable
private fun ComputerSection(model: ConvertModel) {
    val c = UmbraTheme.colors
    val many = model.onlineCount > 1
    val rows = if (many) model.computers.toList() else model.computers.filter { it.id == model.deviceId }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        GroupHead(stringResource(if (many) R.string.share_conv_pcHeadMany else R.string.share_conv_pcHeadOne))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.card)) {
            rows.forEachIndexed { i, pc ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSoft))
                val on = pc.id == model.deviceId
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .then(if (pc.online && many) Modifier.clickable { model.select(pc.id) } else Modifier)
                        .semantics { selected = on }
                        .alpha(if (pc.online) 1f else 0.5f)
                        .padding(start = 16.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)).background(c.chip), contentAlignment = Alignment.Center) {
                        UmbraIcon(UmbraIconPaths.display, c.muted, size = 16.dp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(pc.name, color = if (pc.online) c.text else c.faint, style = TextStyle(fontSize = 15.sp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(if (pc.online) c.success else c.faint))
                            Spacer(Modifier.width(5.dp))
                            Text(subline(pc), color = c.muted, style = TextStyle(fontSize = 12.sp))
                        }
                    }
                    if (many && on) UmbraIcon(UmbraIconPaths.check, c.orange, size = 18.dp, strokeWidth = 2.4f)
                }
            }
        }
    }
}

/** 「在线」/「离线 · 3 小时前在线」/「离线」。相对时间用系统的说法（同 iOS RelativeDateTimeFormatter）。 */
@Composable
private fun subline(pc: ConvertPC): String {
    if (pc.online) return stringResource(R.string.share_conv_online)
    val t = pc.lastSeen ?: return stringResource(R.string.share_conv_offlineBare)
    val rel = DateUtils.getRelativeTimeSpanString(t.toEpochMilli(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    return stringResource(R.string.share_conv_offlineSince, rel)
}

/** 「转成」三列格子（56 高）+ 选中那颗的提醒 +「X 上转不了」几行（只说缺什么，不给命令）。 */
@Composable
private fun FormatSection(model: ConvertModel) {
    val c = UmbraTheme.colors
    val dev = model.device?.name.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        val avail = model.available
        if (avail.isEmpty()) {
            ErrorCard(UmbraIconPaths.alertTriangle, c.warningText, stringResource(R.string.share_conv_nothingTitle, dev), stringResource(R.string.share_conv_nothingBody))
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                GroupHead(stringResource(R.string.share_conv_to))
                avail.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { t -> FormatCell(t, model.chosen == t.to, Modifier.weight(1f)) { model.chosen = t.to } }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                model.chosenTarget?.note?.takeIf { it.isNotEmpty() }?.let { note ->
                    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.Top) {
                        UmbraIcon(UmbraIconPaths.alertTriangle, c.warningText, size = 14.dp, strokeWidth = 2.1f, modifier = Modifier.padding(top = 3.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(note, color = c.warningText, style = TextStyle(fontSize = 13.sp, lineHeight = 20.8.sp))
                    }
                }
            }
        }
        val blocked = model.blocked
        if (blocked.isNotEmpty()) {
            val intel = stringResource(R.string.share_conv_blockedIntel)
            val why = stringResource(R.string.share_conv_blockedWhy)
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                GroupHead(stringResource(R.string.share_conv_blockedHead, dev))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.card)) {
                    blocked.forEachIndexed { i, t ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSoft))
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(t.label, color = c.faint, style = TextStyle(fontSize = 15.sp, fontFamily = FontFamily.Monospace), modifier = Modifier.width(84.dp))
                            Spacer(Modifier.width(10.dp))
                            // 「Pandoc 是 Intel 版」改写成带电脑名的整句（批次 271），其它原因原样；判据在 ConvertRules。
                            Text(ConvertRules.blockedReason(t.reason, dev, intel, why), color = c.muted, style = TextStyle(fontSize = 13.sp, lineHeight = 20.sp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FormatCell(t: ConvertTarget, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.heightIn(min = 56.dp).clip(shape).background(if (on) c.orangeSoft else c.card)
            .border(if (on) 2.dp else 1.dp, if (on) c.orange else c.border, shape)
            .clickable(onClick = onClick).semantics { selected = on },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            t.label, color = if (on) c.orangeText else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        // 可以转但有出入：右上角一颗琥珀三角，选中后下面出整句提醒。
        if (!t.note.isNullOrEmpty()) {
            UmbraIcon(UmbraIconPaths.alertTriangle, c.warningText, size = 12.dp, strokeWidth = 2.2f, modifier = Modifier.align(Alignment.TopEnd).padding(7.dp))
        }
    }
}

// MARK: 02 三段进度

@Composable
private fun RunScreen(model: ConvertModel) {
    val c = UmbraTheme.colors
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.card)) {
            StepRow(model, 0, stringResource(R.string.share_conv_stepUpload), now)
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSoft))
            StepRow(model, 1, stringResource(R.string.share_conv_stepConvert, model.jobDeviceName), now)
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSoft))
            StepRow(model, 2, stringResource(R.string.share_conv_stepFetch), now)
        }
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val runNote = when {
                model.lost -> stringResource(R.string.share_conv_lostNote)
                model.steps[0] == ConvertModel.Step.Done -> stringResource(R.string.share_conv_noteAway)
                else -> stringResource(R.string.share_conv_noteUploading)
            }
            Text(runNote, color = if (model.lost) c.text else c.muted, style = TextStyle(fontSize = 13.sp, lineHeight = 21.5.sp))
            // 中转区与结果各留多久（批次 271）：人在这一屏最常问的是「我现在切走，回来还在不在」。名字拼不出换不带名字的那句。
            val keep = ConvertRules.expectedResultName(model.jobName, model.jobTo)
                ?.let { stringResource(R.string.share_conv_keepNote, it) } ?: stringResource(R.string.share_conv_keepNoteBare)
            Text(keep, color = c.muted, style = TextStyle(fontSize = 13.sp, lineHeight = 21.5.sp))
        }
    }
}

/** 一段：正在的那段转圈（有百分比就出条），做完的对勾，没轮到的时钟，断开的圆叉。 */
@Composable
private fun StepRow(model: ConvertModel, i: Int, title: String, now: Long) {
    val c = UmbraTheme.colors
    val s = model.steps[i]
    val tint = when (s) { ConvertModel.Step.Done -> c.success; ConvertModel.Step.Run -> c.orange; ConvertModel.Step.Fail -> c.dangerText; ConvertModel.Step.Wait -> c.faint }
    val total = model.input?.bytes ?: 0
    val sub = when {
        s == ConvertModel.Step.Wait -> stringResource(R.string.share_conv_waitPrev)
        i == 0 && s == ConvertModel.Step.Run -> model.uploadFraction?.takeIf { total > 0 }?.let { f ->
            stringResource(R.string.share_conv_uploadRun, ConvertRules.sizeText((total * f).toInt()), ConvertRules.sizeText(total), ConvertRules.percent(f.toDouble()))
        } ?: stringResource(R.string.share_conv_uploadStart)
        i == 0 -> if (total > 0) stringResource(R.string.share_conv_uploadDone, ConvertRules.sizeText(total)) else stringResource(R.string.share_conv_uploadDoneBare)
        i == 1 && s == ConvertModel.Step.Run -> stringResource(R.string.share_conv_convertRun, model.jobTo, maxOf(0L, (now - (model.convertSinceMs ?: now)) / 1000).toInt())
        i == 1 && s == ConvertModel.Step.Done -> model.doneSize?.let { stringResource(R.string.share_conv_convertDone, ConvertRules.sizeText(it)) } ?: stringResource(R.string.share_conv_convertDoneBare)
        i == 1 -> stringResource(R.string.share_conv_convertLost, model.jobDeviceName)
        i == 2 && s == ConvertModel.Step.Run -> stringResource(R.string.share_conv_fetchRun, ConvertRules.percent((model.fetchFraction ?: 0f).toDouble()))
        else -> stringResource(R.string.share_conv_fetchDone)
    }
    val frac: Float? = when (i) { 0 -> model.uploadFraction; 1 -> model.convertFraction?.toFloat(); else -> model.fetchFraction ?: 0f }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (s == ConvertModel.Step.Run) UmbraSpinningIcon(UmbraIconPaths.spinnerArc, tint, 18.dp)
            else UmbraIcon(when (s) { ConvertModel.Step.Done -> UmbraIconPaths.check; ConvertModel.Step.Fail -> UmbraIconPaths.xCircle; else -> UmbraIconPaths.clock }, tint, size = 18.dp, strokeWidth = 2.1f)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = if (s == ConvertModel.Step.Wait) c.faint else c.text, style = TextStyle(fontSize = 15.sp))
            Text(sub, color = if (s == ConvertModel.Step.Fail) c.dangerText else c.muted, style = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, fontFeatureSettings = "tnum"))
            if (s == ConvertModel.Step.Run && frac != null) {
                val a by animateFloatAsState(frac.coerceIn(0f, 1f), label = "step")
                Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(5.dp).clip(CircleShape).background(c.track)) {
                    Box(Modifier.fillMaxWidth(a).height(5.dp).clip(CircleShape).background(c.orange))
                }
            }
        }
    }
}

// MARK: 03 结果

@Composable
private fun ResultScreen(model: ConvertModel, savedLine: String?) {
    val c = UmbraTheme.colors
    val o = model.outcome
    val ok = o?.ok ?: false
    val gone = o?.gone
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.card).padding(horizontal = 16.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 「作业没了」两档（批次 272）：A 过期是 clock + muted（不是出错），B 服务端重启是 alert-triangle + warningText。
                val (icon, tint) = when (gone) {
                    ConvertModel.Gone.Expired -> UmbraIconPaths.clock to c.muted
                    ConvertModel.Gone.Interrupted -> UmbraIconPaths.alertTriangle to c.warningText
                    null -> if (ok) UmbraIconPaths.check to c.success else UmbraIconPaths.xCircle to c.dangerText
                }
                UmbraIcon(icon, tint, size = 19.dp, strokeWidth = 2.2f)
                Spacer(Modifier.width(8.dp))
                val title = when (gone) {
                    ConvertModel.Gone.Expired -> stringResource(R.string.share_conv_goneTitle)
                    ConvertModel.Gone.Interrupted -> stringResource(R.string.share_conv_interruptedTitle)
                    null -> stringResource(if (ok) R.string.share_conv_done else R.string.share_conv_failedTitle)
                }
                Text(title, color = c.text, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
            }
            if (o != null && ok) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Badge(ConvertRules.badge(o.name))
                    Spacer(Modifier.width(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(o.name, color = c.text, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.share_conv_resMeta, ConvertRules.sizeText(o.size ?: 0), model.jobDeviceName), color = c.muted, style = TextStyle(fontSize = 13.sp, fontFeatureSettings = "tnum"))
                    }
                }
                o.note?.takeIf { it.isNotEmpty() }?.let { Text(it, color = c.muted, style = TextStyle(fontSize = 13.sp)) }
            } else {
                Text(o?.reason ?: stringResource(R.string.share_conv_failed), color = c.muted, style = TextStyle(fontSize = 15.sp, lineHeight = 24.75.sp))
            }
        }
        savedLine?.let { Text(it, color = c.muted, style = TextStyle(fontSize = 13.sp), modifier = Modifier.padding(horizontal = 4.dp)) }
    }
}

// MARK: 底部

private enum class ActKind { Primary, Secondary, Off }
private class Act(val label: String, val kind: ActKind, val run: () -> Unit)

private fun actionsOf(
    model: ConvertModel,
    onClose: () -> Unit,
    onOpenRemote: (() -> Unit)?,
    onPickFile: ((ConvertModel.Repick) -> Unit)?,
    exportTo: (File) -> Unit,
    share: (File) -> Unit,
    savePhotos: (ConvertModel.Outcome) -> Unit,
    clearSaved: () -> Unit,
): List<Act> {
    fun t(id: Int) = convText(id)
    return when (model.screen) {
        ConvertModel.Screen.Pick -> when (model.pick) {
            ConvertModel.Pick.NoComputer -> listOf(Act(t(R.string.share_conv_lookAgain), ActKind.Primary) { model.launch { model.loadComputers() } })
            ConvertModel.Pick.TooBig -> listOf(
                if (onOpenRemote != null) Act(t(R.string.share_conv_openRemote), ActKind.Primary) { model.close(); onOpenRemote() }
                else Act(t(R.string.share_conv_gotIt), ActKind.Primary, onClose),
            )
            ConvertModel.Pick.NoReply -> listOf(Act(t(R.string.share_conv_askAgain), ActKind.Primary) { model.launch { model.ask() } })
            is ConvertModel.Pick.Failed -> listOf(Act(t(R.string.share_conv_tryAgain), ActKind.Primary) { model.launch { model.loadComputers() } })
            ConvertModel.Pick.Loading, ConvertModel.Pick.Asking -> listOf(Act(t(R.string.share_conv_start), ActKind.Off) {})
            ConvertModel.Pick.Ready -> {
                val can = model.chosenTarget != null
                listOf(Act(t(R.string.share_conv_start), if (can) ActKind.Primary else ActKind.Off) { if (can) model.convert() })
            }
        }
        ConvertModel.Screen.Run ->
            if (model.lost) listOf(Act(t(R.string.share_conv_retry), ActKind.Primary) { model.retry() })
            else listOf(Act(t(R.string.share_conv_stop), ActKind.Secondary) { model.stop() })
        ConvertModel.Screen.Result -> {
            val o = model.outcome ?: return emptyList()
            val gotIt = { model.close(); onClose() }
            when {
                // 两档「作业没了」同一组钮（批次 272）：［选文件］主 +［知道了］次。不给「重试」：服务端那件已经不在了。
                o.gone != null -> if (onPickFile == null) listOf(Act(t(R.string.share_conv_gotIt), ActKind.Primary, gotIt))
                else {
                    val r = model.repick
                    listOf(Act(t(R.string.share_conv_pickFile), ActKind.Primary) { model.close(); onPickFile(r) }, Act(t(R.string.share_conv_gotIt), ActKind.Secondary, gotIt))
                }
                !o.ok -> if (model.hasSource) listOf(
                    Act(t(R.string.share_conv_otherFormat), ActKind.Primary) { clearSaved(); model.otherFormat() },
                    Act(t(R.string.share_conv_retry), ActKind.Secondary) { clearSaved(); model.convert() },
                ) else listOf(Act(t(R.string.share_conv_gotIt), ActKind.Primary, onClose))
                else -> {
                    val f = o.file ?: return emptyList()
                    val files = Act(t(R.string.share_conv_saveFiles), if (o.kind == ConvertKind.Document) ActKind.Primary else ActKind.Secondary) { exportTo(f) }
                    val sh = Act(t(R.string.share_conv_share), ActKind.Secondary) { share(f) }
                    if (o.kind == ConvertKind.Document) listOf(files, sh)
                    else listOf(Act(t(R.string.share_conv_savePhotos), ActKind.Primary) { savePhotos(o) }, files, sh)
                }
            }
        }
    }
}

/** 50 高整宽钮：主钮强调实底 + onAccent（≥ 600 字重）；次钮 card 底 + 描边；灰钮 chip 底 + faint、点不动。 */
@Composable
private fun BigButton(a: Act) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 50.dp).clip(shape)
            .background(when (a.kind) { ActKind.Primary -> c.orange; ActKind.Off -> c.chip; ActKind.Secondary -> c.card })
            .then(if (a.kind == ActKind.Secondary) Modifier.border(1.dp, c.border, shape) else Modifier)
            .clickable(enabled = a.kind != ActKind.Off, onClick = a.run),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            a.label, color = when (a.kind) { ActKind.Primary -> c.onAccent; ActKind.Off -> c.faint; ActKind.Secondary -> c.text },
            style = TextStyle(fontSize = 17.sp, fontWeight = if (a.kind == ActKind.Secondary) FontWeight.Normal else FontWeight.SemiBold),
        )
    }
}

// MARK: 小件

@Composable
private fun SpinnerCard(s: String) {
    val c = UmbraTheme.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.card).padding(horizontal = 16.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        UmbraSpinningIcon(UmbraIconPaths.spinnerArc, c.muted, 17.dp)
        Spacer(Modifier.width(10.dp))
        Text(s, color = c.text, style = TextStyle(fontSize = 15.sp))
    }
}

/** 三段式里前两段：图标 + 标题（17/600），一句说明（15 muted）。下一步是底部那颗主钮。 */
@Composable
private fun ErrorCard(icon: String, tint: Color, title: String, body: String) {
    val c = UmbraTheme.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.card).padding(horizontal = 16.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UmbraIcon(icon, tint, size = 19.dp, strokeWidth = 2f)
            Spacer(Modifier.width(8.dp))
            Text(title, color = c.text, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
        }
        Text(body, color = c.muted, style = TextStyle(fontSize = 15.sp, lineHeight = 24.75.sp))
    }
}

/** 「分享」：系统分享面板。成品在 cacheDir 里，经 FileProvider（`${applicationId}.files`，只开 cacheDir）交出去。 */
private fun shareFile(ctx: Context, f: File) {
    val uri = runCatching { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f) }.getOrNull() ?: return
    val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ConvertRules.ext(f.name)) ?: "application/octet-stream"
    val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri(f.name, uri) }
    ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

// MARK: 入口要用的

/**
 * 读一个选中 / 分享进来的文件：先问大小，超过上限不读字节（只记名字和大小，面板说太大）。读不出 → null。
 * 名字取系统给的显示名（带扩展名，电脑按它判格式）。
 */
suspend fun readConvertInput(ctx: Context, uri: Uri): ConvertInput? = withContext(Dispatchers.IO) {
    val cr = ctx.contentResolver
    var name = ""
    var size = -1L
    runCatching {
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0).orEmpty()
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
    }
    if (name.isBlank()) name = uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifBlank { "file" }
    if (size > ConvertRules.MAX_BYTES) return@withContext ConvertInput.TooLarge(name, size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    // 大小问不到的（有的 App 不给 SIZE）：边读边数，超了就停，按太大算。读不出（没权限 / 已删）回 null。
    var over = false
    val data = runCatching {
        cr.openInputStream(uri)?.use { s ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                total += n
                if (total > ConvertRules.MAX_BYTES) { over = true; return@use null }
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    }.getOrNull()
    when {
        over -> ConvertInput.TooLarge(name, maxOf(size, ConvertRules.MAX_BYTES + 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        data != null && data.isNotEmpty() -> ConvertInput.File(ConvertSource(name, data))
        else -> null
    }
}

/** 设备详情页顶上那一条：「合同.pdf 转好了」+［去看看］（没转成的写「合同.docx 没转成」）。 */
@Composable
fun ConvertBannerView(banner: ConvertBanner, open: () -> Unit) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(c.card).border(UmbraDimens.borderW, c.border, shape).padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UmbraIcon(if (banner.ok) UmbraIconPaths.check else UmbraIconPaths.xCircle, if (banner.ok) c.success else c.dangerText, size = 16.dp, strokeWidth = 2.2f)
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(if (banner.ok) R.string.convert_bannerDone else R.string.convert_bannerFailed, banner.name),
            color = c.text, style = TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        UmbraStripAction(stringResource(R.string.convert_bannerLook), c.orangeText, open)
    }
}
