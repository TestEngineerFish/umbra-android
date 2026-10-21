// 远程会话的文件面板（M2，RC-6.1 / 6.2 / §14.3；形制照设计侧批次 262 iOS 稿 06）—— 照搬 iOS `Features/Remote/RemoteFilesView.swift`：
// 列被控端「下载」「桌面」两个目录、拉文件回来、从系统文件选择器（OpenDocument）选一个推过去。
//
// 只开放这两个目录（被控端那边也只认这两个，不做任意路径浏览）。拉回来的先落 App 缓存目录；
// iOS 交给系统分享面板，Android 给两条路：「分享」（ACTION_SEND，存网盘 / 发给别的 App 都在那一层）
// 和「存到下载」（MediaStore.Downloads，Android 10+；9 上只有分享 —— 那一版写公共目录要存储权限，为一个按钮去要不值）。
// 这套形制、三句字面与边界由设计侧批次 202 认定：9 及以下菜单只剩一项就不弹，点［存储］直接分享；写入失败在面板底部出提示条带［改用分享］。
// 中继时单文件上限 100 MB 并提示「会计入流量」，直连 500 MB（上限判据在 `RemoteFileCodec.limit`）。
@file:OptIn(ExperimentalMaterial3Api::class)

package com.geek.umbra.feature.remote

import androidx.compose.foundation.layout.widthIn
import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.geek.umbra.designsystem.UmbraFixedColors
import com.geek.umbra.designsystem.UmbraRemoteColors
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.geek.umbra.designsystem.UmbraRemoteDimens
import androidx.core.content.FileProvider
import com.geek.umbra.R
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraGroupCard
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraProgressBar
import com.geek.umbra.designsystem.UmbraRemoteType
import com.geek.umbra.designsystem.UmbraRowDivider
import com.geek.umbra.designsystem.UmbraSectionLabel
import com.geek.umbra.designsystem.UmbraSegment
import com.geek.umbra.designsystem.UmbraSegmentedControl
import com.geek.umbra.designsystem.UmbraTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 文件面板（iOS: RemoteFilesView.swift:13）。形制照设计侧批次 262 iOS 稿 06：「X 的文件」+ 完成 → 下载 / 桌面分段
 * → 文件卡（每行名字 +「今天 13:52 · 412 KB」+ 行尾下载钮；超过这条路的上限时副行写为什么拉不了、钮置灰；末行「从手机推一个过去」）
 * → 传输卡（进度条 / 拉下来了 +［存储］/ 断了 +［接着传］）+ 一句组脚。
 * 超限原来是点了才吐一句 —— 而那句吐司画在面板背后的会话页上，看不见；现在写在行上。
 */
@Composable
internal fun RemoteFilesView(client: RemoteClient, onDone: () -> Unit) {
    val c = UmbraTheme.colors
    var dir by remember { mutableStateOf("downloads") }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(client::push) }

    LaunchedEffect(dir) { client.listFiles(dir) }

    Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
        // 头（iOS 稿 06）：「X 的文件」17/600 + 完成。
        Row(Modifier.fillMaxWidth().padding(start = UmbraDimens.pagePadX, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.remote_files_titleOf, client.hostName), color = c.text, style = UmbraRemoteType.panelTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Box(Modifier.defaultMinSize(UmbraDimens.tapMin, UmbraDimens.tapMin).clickable(role = Role.Button, onClick = onDone).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.remote_files_done), color = c.orangeText, style = UmbraRemoteType.panelDone)
            }
        }
        UmbraSegmentedControl(
            listOf(UmbraSegment(stringResource(R.string.remote_files_downloads)), UmbraSegment(stringResource(R.string.remote_files_desktop))),
            if (dir == "desktop") 1 else 0,
            { dir = if (it == 1) "desktop" else "downloads" },
            Modifier.padding(horizontal = UmbraDimens.pagePadX).padding(bottom = 10.dp),
        )
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                client.listFiles(dir)
                // 回包走 DataChannel、没有「这一次刷新完了」的回执：转一下就收，列表到了自己会换。
                scope.launch { delay(600); refreshing = false }
            },
            modifier = Modifier.weight(1f),
        ) {
            LazyColumn(
                Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = UmbraDimens.pagePadX, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(UmbraDimens.groupGap),
            ) {
                item(key = "files-$dir") { FileCard(client, dir) { picker.launch(arrayOf("*/*")) } }
                item(key = "transfers") {
                    Column {
                        if (client.transfers.isNotEmpty()) {
                            UmbraSectionLabel(stringResource(R.string.remote_files_transfers))
                            Spacer(Modifier.height(6.dp))
                            UmbraGroupCard {
                                client.transfers.forEachIndexed { i, t ->
                                    if (i > 0) UmbraRowDivider(Modifier.padding(start = 14.dp))
                                    TransferRow(client, t)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                        // 组脚：中继时 warning 色说会计入流量（单个最多 100 MB），直连 muted。
                        Text(
                            stringResource(if (client.relayPath) R.string.remote_files_relayNote else R.string.remote_files_directNote),
                            color = if (client.relayPath) c.warningText else c.muted, style = UmbraRemoteType.filesNote,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
        saveFailure?.let { f -> SaveFailStrip(f) }
    }
}

/** 底部提示条（批次 202 ③）：同外壳带动作吐司的深色胶囊，挂 6 秒；［改用分享］直接把同一个文件交给分享面板。 */
@Composable
private fun SaveFailStrip(f: SaveFailure) {
    val ctx = LocalContext.current
    LaunchedEffect(f) { delay(6_000); if (saveFailure === f) saveFailure = null }
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(UmbraTheme.colors.toastGlass).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("没存进「下载」：${f.reason}", color = UmbraRemoteColors.fg, style = UmbraRemoteType.toast, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.defaultMinSize(minHeight = UmbraDimens.tapMin).clickable(role = Role.Button) { saveFailure = null; share(ctx, f.file) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.height(30.dp).clip(RoundedCornerShape(50)).border(1.dp, UmbraRemoteColors.fg.copy(alpha = 0.25f), RoundedCornerShape(50)).padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("改用分享", color = UmbraRemoteColors.fg, style = UmbraRemoteType.toast.copy(fontWeight = FontWeight.SemiBold)) }
            }
        }
    }
}

/** 这个目录的文件（行尾下载钮）+ 末行「从手机推一个过去 · 推到对方「下载」」。 */
@Composable
private fun FileCard(client: RemoteClient, dir: String, onPush: () -> Unit) {
    val c = UmbraTheme.colors
    UmbraGroupCard {
        val items = client.fileLists[dir]
        if (items == null) {
            Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = UmbraDimens.rowMinH).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = c.muted, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.remote_files_loading), color = c.muted, style = UmbraRemoteType.filesEmpty)
            }
        } else {
            if (items.isEmpty()) {
                Box(Modifier.fillMaxWidth().defaultMinSize(minHeight = UmbraDimens.rowMinH).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                    Text(stringResource(R.string.remote_files_empty), color = c.muted, style = UmbraRemoteType.filesEmpty)
                }
            }
            items.forEachIndexed { i, f ->
                if (i > 0) UmbraRowDivider(Modifier.padding(start = 14.dp))
                FileRow(client, f, dir)
            }
        }
        UmbraRowDivider(Modifier.padding(start = 14.dp))
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = UmbraDimens.rowMinH).clickable(role = Role.Button, onClick = onPush)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UmbraIcon(RemoteIcons.upload, c.orangeText, size = 16.dp)
            Spacer(Modifier.width(8.dp))
            // iOS 写「从 iPhone 推一个过去」：Android 换成「手机」（res/values/strings_remote_android.xml）。
            Text(stringResource(R.string.remote_android_files_push), color = c.orangeText, style = UmbraRemoteType.pushLabel, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            // 推过去的固定落在对方「下载」（被控端只收这一个目录）：不跟着分段切到「桌面」就改口（批次 265）。
            Text(stringResource(R.string.remote_files_pushTo), color = c.muted, style = UmbraRemoteType.pushTo)
        }
    }
}

/** 一个文件：名字 + 「今天 13:52 · 412 KB」；超过这条路的上限时副行写为什么拉不了、下载钮置灰（iOS: RemoteFilesView.swift:128）。 */
@Composable
private fun FileRow(client: RemoteClient, f: RemoteFileItem, dir: String) {
    val c = UmbraTheme.colors
    val tooBig = f.size > RemoteFileCodec.limit(client.relayPath)
    val a11y = stringResource(R.string.remote_files_pullA11y, f.name)
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = UmbraRemoteDimens.fileRowMinH).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(f.name, color = c.text, style = UmbraRemoteType.fileName, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(fileMeta(client, f, tooBig), color = c.muted, style = UmbraRemoteType.fileMeta)
        }
        Spacer(Modifier.width(11.dp))
        Box(
            Modifier.size(UmbraDimens.tapMin).clickable(enabled = !tooBig, role = Role.Button) { client.pull(f, dir) }
                .semantics { contentDescription = a11y },
            contentAlignment = Alignment.Center,
        ) { UmbraIcon(RemoteIcons.downloadLine, if (tooBig) c.faint else c.orangeText, size = 18.dp) }
    }
}

/** 副行：超限写原因；有修改时间写「今天 HH:mm」或「10月17日 14:05」；再接大小（十进制，同 iOS ByteCountFormatter .file）。 */
@Composable
private fun fileMeta(client: RemoteClient, f: RemoteFileItem, tooBig: Boolean): String {
    val size = RemoteBytes.format(f.size, binary = false)
    if (tooBig) return stringResource(if (client.relayPath) R.string.remote_files_tooBigRelayRow else R.string.remote_files_tooBigRow, size)
    if (f.mtimeMs <= 0) return size
    val ctx = LocalContext.current
    val d = java.util.Date(f.mtimeMs)
    val today = android.text.format.DateUtils.isToday(f.mtimeMs)
    val whenText = if (today) stringResource(R.string.remote_files_today, android.text.format.DateFormat.getTimeFormat(ctx).format(d))
    else java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "MMMdHHmm"), java.util.Locale.getDefault()).format(d)
    return "$whenText · $size"
}

/**
 * 存进「下载」没成的那一次（批次 202 ③）：面板底部的提示条 `没存进「下载」：<原因>` +［改用分享］。
 * 放模块级而不是面板里的 remember —— 写入在协程里跑完，那时菜单所在的行可能已经滚出去被回收了。
 */
private class SaveFailure(val reason: String, val file: File)
private var saveFailure by mutableStateOf<SaveFailure?>(null)

/** 拉回来的文件已经存进「下载」的那几条（键 = 会话 id / 传输 id：传输 id 每次会话从 1 数起）。面板是独立窗口，吐司会被它盖住 —— 结果就地写在那一行上。 */
private val savedToDownloads = mutableStateMapOf<String, Boolean>()

/**
 * 一条传输（iOS: RemoteFilesView.swift:164）：名字 + 状态行；在传的行尾百分比 + 4 高橙色进度条；拉下来了 +［存储］；断了 +［接着传］。
 * Android 的［存储］给两条路（分享 / 存到「下载」，见文件头），iOS 是系统分享面板一条。
 */
@Composable
private fun TransferRow(client: RemoteClient, t: RemoteTransfer) {
    val c = UmbraTheme.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = UmbraRemoteDimens.fileRowMinH).padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(t.name, color = c.text, style = UmbraRemoteType.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                StatusLine(t)
            }
            Spacer(Modifier.width(8.dp))
            when {
                t.running -> Text(percent(t), color = c.muted, style = UmbraRemoteType.transferPercent, modifier = Modifier.padding(end = 10.dp))
                t.finished -> {
                    val file = t.localFile
                    if (t.pull && file != null) {
                        if (savedToDownloads[client.id + "/" + t.id] == true) {
                            // 状态要图标 + 文字（批次 202 ①）：12 对勾、stroke 2、同 muted。
                            Row(Modifier.padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                UmbraIcon(UmbraIconPaths.check, c.muted, size = 12.dp, strokeWidth = 2f)
                                Spacer(Modifier.width(4.dp))
                                Text("已存到「下载」", color = c.muted, style = UmbraRemoteType.small)
                            }
                        } else Box {
                            // 9 及以下菜单只剩「分享…」一项，不弹，直接分享（批次 202 ②）。走分享之后行尾仍是［存储］—— 拿不到对方 App 存没存。
                            RowAction(stringResource(R.string.remote_files_save)) { if (Build.VERSION.SDK_INT >= 29) menu = true else share(ctx, file) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.card, modifier = Modifier.widthIn(min = 180.dp)) {
                                DropdownMenuItem(
                                    text = { Text("分享…", color = c.text) },
                                    leadingIcon = { UmbraIcon(UmbraIconPaths.shareUp, c.text, size = 18.dp) },
                                    onClick = { menu = false; share(ctx, file) },
                                )
                                if (Build.VERSION.SDK_INT >= 29) {
                                    DropdownMenuItem(
                                        text = { Text("存到「下载」", color = c.text) },
                                        leadingIcon = { UmbraIcon(RemoteIcons.download, c.text, size = 18.dp) },
                                        onClick = {
                                            menu = false
                                            scope.launch {
                                                val reason = saveToDownloads(ctx, file, t.name)
                                                if (reason == null) { savedToDownloads[client.id + "/" + t.id] = true; saveFailure = null }
                                                else saveFailure = SaveFailure(reason, file)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                // 失败可续传：从已经写下的字节数接着拉（file_get.offset）。推的没有续传（被控端不收 offset）。
                t.pull -> RowAction(stringResource(R.string.remote_files_resume)) { client.pull(RemoteFileItem(t.name, t.size, 0), t.remoteDir, t) }
            }
        }
        if (t.running) Box(Modifier.padding(end = 10.dp)) { UmbraProgressBar(fraction(t), c.orange) }
    }
}

/** 传输行的第二行：拉下来了 / 推过去了（成功绿 + 对勾）、断了（warning + 三角，写传到几成、原因）。在传的不写（进度条就是它）。 */
@Composable
private fun StatusLine(t: RemoteTransfer) {
    val c = UmbraTheme.colors
    val failure = t.failure
    when {
        t.finished -> Row(verticalAlignment = Alignment.CenterVertically) {
            UmbraIcon(UmbraIconPaths.check, c.success, size = 12.dp, strokeWidth = 2.4f)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(if (t.pull) R.string.remote_files_pulled else R.string.remote_files_sent), color = c.success, style = UmbraRemoteType.transferStatus)
        }
        failure != null -> Row(verticalAlignment = Alignment.Top) {
            UmbraIcon(UmbraIconPaths.alertTriangle, c.warningText, size = 12.dp, strokeWidth = 2.2f, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(R.string.remote_files_brokeAt, (fraction(t) * 100).toInt().toString(), failure),
                color = c.warningText, style = UmbraRemoteType.transferStatus, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 行尾文字动作（存储 / 接着传）：15 orangeText，热区 48。 */
@Composable
private fun RowAction(title: String, onClick: () -> Unit) {
    Box(
        Modifier.defaultMinSize(minHeight = UmbraDimens.tapMin).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(title, color = UmbraTheme.colors.orangeText, style = UmbraRemoteType.rowAction) }
}

private fun fraction(t: RemoteTransfer): Float =
    if (t.size <= 0) 0f else (t.done.toDouble() / t.size).coerceIn(0.0, 1.0).toFloat()

private fun percent(t: RemoteTransfer): String =
    if (t.size <= 0) RemoteBytes.format(t.done, binary = false) else "${(fraction(t) * 100).toInt()}%"

private fun mimeOf(file: File): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"

/** 交给系统分享面板。FileProvider 用清单里那份 `${applicationId}.files`（只开 cacheDir，拉回来的就落在那下面）。 */
private fun share(ctx: android.content.Context, file: File) {
    val uri = runCatching { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file) }.getOrNull() ?: return
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mimeOf(file)
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { ctx.startActivity(Intent.createChooser(send, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/**
 * 存进公共「下载」目录（Android 10+ 的 MediaStore.Downloads，不要权限）。先标 IS_PENDING，写完再放出来 —— 别的 App 不会读到半个文件。
 * 返回 null = 存好了；否则是提示条冒号后那半句原因（批次 202 ③，另两句定稿见批次 203）。重名交给系统自动加「(1)」。
 * 空间先用 StatFs 估一次（写到一半才满会留半个文件再删，体验差）；估过了写时仍可能满，ENOSPC 也归到空间那句。
 */
private suspend fun saveToDownloads(ctx: android.content.Context, file: File, displayName: String): String? = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT < 29) return@withContext "这台手机不支持直接存"
    @Suppress("DEPRECATION")
    val free = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrDefault(Long.MAX_VALUE)
    if (free < file.length()) return@withContext NO_SPACE
    val cr = ctx.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, RemoteFileName.local(displayName))
        put(MediaStore.Downloads.MIME_TYPE, mimeOf(file))
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val uri = runCatching { cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }.getOrNull()
        ?: return@withContext "系统暂时不让写入「下载」文件夹"
    val err = runCatching {
        cr.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("no stream")
    }.exceptionOrNull()
    if (err == null) {
        values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
        runCatching { cr.update(uri, values, null, null) }
        null
    } else {
        // 写了一半的条目删掉，不在「下载」里留坏文件；删不掉也照旧报这句（批次 203）。
        runCatching { cr.delete(uri, null, null) }
        if (generateSequence(err) { it.cause }.any { it.message?.contains("ENOSPC") == true }) NO_SPACE else "写到一半中断了，文件没存全"
    }
}

/** 设计侧给定的那一句（批次 202 ③）。 */
private const val NO_SPACE = "手机剩余空间不够"
