// 远程会话的文件面板（M2，RC-6.1 / 6.2 / §14.3）—— 照搬 iOS `Features/Remote/RemoteFilesView.swift`：
// 列被控端「下载」「桌面」两个目录、拉文件回来、从系统文件选择器（OpenDocument）选一个推过去。
//
// 只开放这两个目录（被控端那边也只认这两个，不做任意路径浏览）。拉回来的先落 App 缓存目录；
// iOS 交给系统分享面板，Android 给两条路：「分享」（ACTION_SEND，存网盘 / 发给别的 App 都在那一层）
// 和「存到下载」（MediaStore.Downloads，Android 10+；9 上只有分享 —— 那一版写公共目录要存储权限，为一个按钮去要不值）。
// 中继时单文件上限 100 MB 并提示「会计入流量」，直连 500 MB（上限判据在 `RemoteFileCodec.limit`）。
@file:OptIn(ExperimentalMaterial3Api::class)

package com.geek.umbra.feature.remote

import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
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

@Composable
internal fun RemoteFilesView(client: RemoteClient, onDone: () -> Unit) {
    val c = UmbraTheme.colors
    var dir by remember { mutableStateOf("downloads") }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(client::push) }

    LaunchedEffect(dir) { client.listFiles(dir) }

    Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
        Row(Modifier.fillMaxWidth().padding(start = UmbraDimens.pagePadX, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.remote_files_title), color = c.text, style = UmbraRemoteType.panelTitle, modifier = Modifier.weight(1f))
            Box(Modifier.defaultMinSize(UmbraDimens.tapMin, UmbraDimens.tapMin).clickable(onClick = onDone).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.remote_files_done), color = c.orangeText, style = UmbraRemoteType.veilState)
            }
        }
        UmbraSegmentedControl(
            listOf(UmbraSegment(stringResource(R.string.remote_files_downloads)), UmbraSegment(stringResource(R.string.remote_files_desktop))),
            if (dir == "desktop") 1 else 0,
            { dir = if (it == 1) "desktop" else "downloads" },
            Modifier.padding(horizontal = UmbraDimens.pagePadX, vertical = 8.dp),
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
                item(key = "push") {
                    Column {
                        UmbraGroupCard {
                            Row(
                                Modifier.fillMaxWidth().defaultMinSize(minHeight = UmbraDimens.rowMinH).clickable { picker.launch(arrayOf("*/*")) }
                                    .padding(horizontal = 14.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                UmbraIcon(RemoteIcons.upload, c.orangeText, size = 18.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(stringResource(R.string.remote_files_push), color = c.orangeText, style = UmbraRemoteType.fileName)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(if (client.relayPath) R.string.remote_files_relayNote else R.string.remote_files_directNote),
                            color = if (client.relayPath) c.warningText else c.muted, style = UmbraRemoteType.small,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
                if (client.transfers.isNotEmpty()) {
                    item(key = "transfers") {
                        Column {
                            UmbraSectionLabel(stringResource(R.string.remote_files_transfers))
                            Spacer(Modifier.height(6.dp))
                            UmbraGroupCard {
                                client.transfers.forEachIndexed { i, t ->
                                    if (i > 0) UmbraRowDivider(Modifier.padding(start = 14.dp))
                                    TransferRow(client, t)
                                }
                            }
                        }
                    }
                }
                fileSection(client, dir)
            }
        }
    }
}

private fun LazyListScope.fileSection(client: RemoteClient, dir: String) {
    item(key = "files-$dir") {
        val c = UmbraTheme.colors
        Column {
            UmbraSectionLabel(stringResource(if (dir == "desktop") R.string.remote_files_desktop else R.string.remote_files_downloads))
            Spacer(Modifier.height(6.dp))
            UmbraGroupCard {
                val items = client.fileLists[dir]
                if (items == null) {
                    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = UmbraDimens.rowMinH).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = c.muted, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.remote_files_loading), color = c.muted, style = UmbraRemoteType.fileName)
                    }
                } else {
                    if (items.isEmpty()) {
                        Box(Modifier.fillMaxWidth().defaultMinSize(minHeight = UmbraDimens.rowMinH).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                            Text(stringResource(R.string.remote_files_empty), color = c.faint, style = UmbraRemoteType.fileName)
                        }
                    }
                    items.forEachIndexed { i, f ->
                        if (i > 0) UmbraRowDivider(Modifier.padding(start = 14.dp))
                        val a11y = stringResource(R.string.remote_files_pullA11y, f.name)
                        Row(
                            Modifier.fillMaxWidth().defaultMinSize(minHeight = UmbraDimens.rowMinH).clickable { client.pull(f, dir) }
                                .semantics { contentDescription = a11y }.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            UmbraIcon(UmbraIconPaths.file, c.muted, size = 18.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(f.name, color = c.text, style = UmbraRemoteType.fileName, maxLines = 2)
                                Text(RemoteBytes.format(f.size, binary = false), color = c.muted, style = UmbraRemoteType.small)
                            }
                            Spacer(Modifier.width(6.dp))
                            UmbraIcon(RemoteIcons.download, c.orangeText, size = 20.dp)
                        }
                    }
                }
            }
        }
    }
}

/** 拉回来的文件已经存进「下载」的那几条（键 = 会话 id / 传输 id：传输 id 每次会话从 1 数起）。面板是独立窗口，吐司会被它盖住 —— 结果就地写在那一行上。 */
private val savedToDownloads = mutableStateMapOf<String, Boolean>()

@Composable
private fun TransferRow(client: RemoteClient, t: RemoteTransfer) {
    val c = UmbraTheme.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UmbraIcon(if (t.pull) RemoteIcons.arrowDown else RemoteIcons.arrowUp, c.muted, size = 16.dp)
            Spacer(Modifier.width(8.dp))
            Text(t.name, color = c.text, style = UmbraRemoteType.transferName, maxLines = 1, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            when {
                t.running -> Text(percent(t), color = c.muted, style = UmbraRemoteType.mono12)
                t.finished -> {
                    val file = t.localFile
                    if (t.pull && file != null) {
                        if (savedToDownloads[client.id + "/" + t.id] == true) {
                            Text("已存到「下载」", color = c.muted, style = UmbraRemoteType.small)
                        } else Box {
                            Box(Modifier.defaultMinSize(UmbraDimens.tapMin, UmbraDimens.tapMin).clickable { menu = true }, contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.remote_files_save), color = c.orangeText, style = UmbraRemoteType.transferAction)
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.card) {
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
                                                if (saveToDownloads(ctx, file, t.name)) savedToDownloads[client.id + "/" + t.id] = true
                                                else client.toast = ctx.getString(R.string.remote_files_writeFailed)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    } else {
                        Text(stringResource(R.string.remote_files_sent), color = c.muted, style = UmbraRemoteType.small)
                    }
                }
                else -> if (t.pull) {
                    // 失败可续传：从已经写下的字节数接着拉（file_get.offset）。
                    Box(
                        Modifier.defaultMinSize(UmbraDimens.tapMin, UmbraDimens.tapMin)
                            .clickable { client.pull(RemoteFileItem(t.name, t.size, 0), t.remoteDir, t) },
                        contentAlignment = Alignment.Center,
                    ) { Text(stringResource(R.string.remote_files_resume), color = c.orangeText, style = UmbraRemoteType.transferAction) }
                }
            }
        }
        if (t.running) UmbraProgressBar(if (t.size > 0) (t.done.toDouble() / t.size).toFloat() else 0f, c.orange)
        t.failure?.let { Text(it, color = c.dangerText, style = UmbraRemoteType.small) }
    }
}

private fun percent(t: RemoteTransfer): String =
    if (t.size <= 0) RemoteBytes.format(t.done, binary = false) else "${(t.done.toDouble() / t.size * 100).toInt()}%"

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

/** 存进公共「下载」目录（Android 10+ 的 MediaStore.Downloads，不要权限）。先标 IS_PENDING，写完再放出来 —— 别的 App 不会读到半个文件。 */
private suspend fun saveToDownloads(ctx: android.content.Context, file: File, displayName: String): Boolean = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT < 29) return@withContext false
    val cr = ctx.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, RemoteFileName.local(displayName))
        put(MediaStore.Downloads.MIME_TYPE, mimeOf(file))
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val uri = runCatching { cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }.getOrNull() ?: return@withContext false
    val ok = runCatching {
        cr.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } != null
    }.getOrDefault(false)
    if (ok) {
        values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
        runCatching { cr.update(uri, values, null, null) }
    } else runCatching { cr.delete(uri, null, null) }
    ok
}
