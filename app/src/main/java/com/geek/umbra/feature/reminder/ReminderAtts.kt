// 提醒附件（一期只收图片，与 PC 同规则）—— 对应 iOS Features/Reminder/ReminderAtts.swift。
//
// 与记账最大的不同：提醒的附件是**整行的一部分**（reminders.atts 行内 JSON，跟着提醒 LWW 同步）——
// 加图 / 摘图都只改草稿，**点「保存」才生效**：新图先攒本地（pending），保存时逐张上传拿 file_id 再随 PUT 落库；
// 摘掉已存的图也只是从草稿里移除。中途取消编辑 = 什么都没发生，不留孤儿文件。
package com.geek.umbra.feature.reminder

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil3.compose.SubcomposeAsyncImage
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraAttGonePlaceholder
import com.geek.umbra.designsystem.UmbraErrorCard
import com.geek.umbra.designsystem.UmbraErrorStrip
import com.geek.umbra.designsystem.UmbraFieldLabel
import com.geek.umbra.designsystem.UmbraFixedColors
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraTone
import com.geek.umbra.designsystem.UmbraType
import com.geek.umbra.navigation.Route
import com.geek.umbra.navigation.UmbraAlert
import com.geek.umbra.navigation.UmbraSheet
import com.geek.umbra.net.AttLimit
import com.geek.umbra.net.AttachFailCopy
import com.geek.umbra.net.AttachSubject
import com.geek.umbra.net.ImagePurpose
import com.geek.umbra.net.ImageQuality
import com.geek.umbra.net.ImageTier
import com.geek.umbra.net.PreparedImage
import com.geek.umbra.net.ServerSettings
import com.geek.umbra.net.UmbraImages
import com.geek.umbra.net.UploadError
import com.geek.umbra.net.Uploads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 新建 / 编辑时先攒在本地、还没上传的一张图。label 存**来源**（相册图片 / 拍的照片 / 文件图片 —— 批次 007：
 * 文件名截成「IMG_2026…」没有信息量）；filename 只给上传带扩展名用。
 */
class ReminderPendingImage(val id: String, val data: ByteArray, val label: String, val filename: String = "")

/** 一张没传上去的图：哪一张、为什么、多大（「太大」那一档要点名它）。 */
data class ReminderAttFail(val id: String, val err: UploadError, val mb: String)

object ReminderAttUploader {
    /**
     * 逐张上传攒着的图。返回（追加后的数组, 已传成功的 pending id, 失败清单）。
     * ⚠️ 一张失败不中断整批（「三张里第一张太大」不能变成「三张都没传上」）。iOS: ReminderAtts.swift:44
     */
    suspend fun upload(pending: List<ReminderPendingImage>, onto: List<ReminderAtt>, tier: ImageTier? = null): Triple<List<ReminderAtt>, List<String>, List<ReminderAttFail>> {
        val out = onto.toMutableList(); val done = mutableListOf<String>(); val bad = mutableListOf<ReminderAttFail>()
        for (p in pending) {
            val raw = p.filename.ifEmpty { "photo-${System.currentTimeMillis() / 1000}.jpg" }
            val prep = prepare(p.data, tier)
            // ⚠️ 名字问 prep 要，别自己拼后缀：透明的图走 PNG，名字和内容对不上时 /files/{id} 判出的类型是错的。
            val (up, err) = Uploads.upload(prep.data, prep.filename(raw))
            if (up != null) { out.add(ReminderAtt(up.file_id, p.label)); done.add(p.id) }
            else bad.add(ReminderAttFail(p.id, err ?: UploadError.Server, "%.1f".format(prep.data.size / 1024.0 / 1024.0)))
        }
        return Triple(out, done, bad)
    }

    /** 按「提醒」用途的画质档缩图（服务端 /settings 的 image_tiers）；拿不到档位像素或原尺寸档 → 原样上传。 */
    private suspend fun prepare(data: ByteArray, override: ImageTier?): PreparedImage {
        val px = if (override != null) (if (override == ImageTier.Origin) 0 else ServerSettings.imageTiers.value[override.wire])
        else ServerSettings.tier(ImagePurpose.Reminder).second
        if (px == null || px <= 0) return PreparedImage(data, data.size, data.size, transcoded = false, png = false)
        return ImageQuality.prepare(data, px)
    }
}

/** 服务端上的一张图：加载中 chip 底 + 图标；失败（服务端已经没有这张）画「附件已删除」。 */
@Composable
internal fun ReminderRemoteImage(fileId: String, size: Dp, radius: Dp, modifier: Modifier = Modifier) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(radius)
    SubcomposeAsyncImage(
        model = UmbraImages.fileUrl(fileId), imageLoader = UmbraImages.loader, contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size).clip(shape).border(UmbraDimens.borderW, c.border, shape),
        loading = { Box(Modifier.fillMaxSize().background(c.chip), contentAlignment = Alignment.Center) { UmbraIcon(UmbraIconPaths.image, c.faint, size = 18.dp, strokeWidth = 1.8f) } },
        error = { UmbraAttGonePlaceholder() },
    )
}

/**
 * 编辑表单里的附件区。已存的（atts）和攒着的（pending）都能摘；满 4 张收起「加图」、出常驻 warning strip；
 * 上一次保存里没传上的那几张（fails）标红 + 下方一条三段式失败卡。iOS: ReminderAtts.swift:93
 */
@Composable
fun ReminderAttsSection(
    atts: List<ReminderAtt>,
    onAtts: (List<ReminderAtt>) -> Unit,
    pending: List<ReminderPendingImage>,
    onPending: (List<ReminderPendingImage>) -> Unit,
    fails: List<ReminderAttFail>,
    onFails: (List<ReminderAttFail>) -> Unit,
    creating: Boolean,
    savedOnce: Boolean,
    landed: Boolean,
    onRetry: (ReminderPendingImage, ImageTier?) -> Unit,
) {
    val c = UmbraTheme.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val router = AppGraph.router
    val count = atts.size + pending.size
    val subject = AttachSubject.reminder.with(ServerSettings.tier(ImagePurpose.Reminder).first)
    var cameraFile by remember { mutableStateOf<File?>(null) }

    /** 收下选回来的图：能放下的先收，超出名额的静默丢下（满额 strip 就是说明，同 PC fitToRoom）。 */
    fun take(data: ByteArray, label: String, filename: String = "") {
        val item = ReminderPendingImage(UUID.randomUUID().toString(), data, label, filename)
        onPending(pending + AttLimit.fit(listOf(item), count))
    }

    fun readUri(uri: Uri, label: String, filename: String = "") {
        scope.launch {
            when (val got = withContext(Dispatchers.IO) { readImport(ctx, uri) }) {
                is ImportRead.Ok -> take(got.data, label, filename.ifEmpty { got.name })
                ImportRead.TooBig -> router.showToast("这张图太大了（超过 $IMPORT_MAX_MB MB），换一张试试")
                ImportRead.Unreadable -> router.showToast("这张图读不出来，换一张试试")
            }
        }
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) readUri(uri, "相册图片") }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile
        cameraFile = null
        if (ok && f != null) scope.launch {
            val d = withContext(Dispatchers.IO) { runCatching { f.readBytes() }.getOrNull().also { f.delete() } }
            if (d != null) take(d, "拍的照片") else router.showToast("这张图读不出来，换一张试试")
        } else f?.delete()
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) readUri(uri, "文件图片") }

    fun askAdd() {
        if (!AttLimit.showsAddTile(count)) return
        val english = reminderEnglish
        val subtitle = if (english) "Up to ${Reminder.maxAtts} per reminder, saved with it." else "一条最多 ${Reminder.maxAtts} 张，跟提醒一起存。"
        router.present(UmbraSheet("rem-add-att") { dismiss ->
            Column(Modifier.fillMaxWidth().padding(horizontal = UmbraDimens.pagePadX, vertical = 8.dp)) {
                Text(ctx.getString(R.string.reminder_atts_add), color = c.text, style = UmbraType.sectionTitle)
                Text(subtitle, color = c.muted, style = UmbraType.rowSub, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                SheetItem(ctx.getString(R.string.reminder_atts_fromLibrary)) {
                    dismiss(); photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                SheetItem(ctx.getString(R.string.reminder_atts_takePhoto)) {
                    dismiss()
                    // 没有相机的设备直说，不给一个点了没反应的入口。
                    if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) { router.showToast("这台设备没有可用的相机"); return@SheetItem }
                    val dir = File(ctx.cacheDir, "reminder-camera").also { it.mkdirs() }
                    val f = File(dir, "shot-${System.currentTimeMillis()}.jpg")
                    cameraFile = f
                    runCatching { camera.launch(FileProvider.getUriForFile(ctx, ctx.packageName + ".reminderfiles", f)) }
                        .onFailure { router.showToast("这台设备没有可用的相机") }
                }
                SheetItem(if (english) "Choose from Files" else "从「文件」选择") { dismiss(); files.launch(arrayOf("image/*")) }
            }
        })
    }

    fun drop(id: String) {
        onPending(pending.filter { it.id != id })
        onFails(fails.filter { it.id != id })
    }

    fun act(f: ReminderAttFail) {
        when (f.err) {
            UploadError.Unauthorized, UploadError.SessionInvalid -> AppGraph.auth.goToLogin()
            UploadError.FileTooLarge, UploadError.BodyTooLarge -> {
                val p = pending.firstOrNull { it.id == f.id }
                if (subject.tier == ImageTier.Origin && savedOnce && p != null) onRetry(p, ImageTier.Clear) else drop(f.id)
            }
            is UploadError.StorageFull -> router.go(Route.SetStorage)
            else -> {
                val p = pending.firstOrNull { it.id == f.id }
                if (savedOnce && p != null) onRetry(p, null) else drop(f.id)
            }
        }
    }

    val footnote = when {
        landed && fails.isNotEmpty() -> if (fails.size == 1) stringResource(R.string.reminder_atts_noteUnsent_one, fails.size) else stringResource(R.string.reminder_atts_noteUnsent_many, fails.size)
        landed && atts.isEmpty() -> stringResource(R.string.reminder_atts_noteSaved)
        landed -> if (atts.size == 1) stringResource(R.string.reminder_atts_noteSavedImages_one, atts.size) else stringResource(R.string.reminder_atts_noteSavedImages_many, atts.size)
        creating -> stringResource(R.string.reminder_atts_noteNew)
        else -> stringResource(R.string.reminder_atts_applyOnSave)
    }

    val shape = RoundedCornerShape(UmbraDimens.radiusCard)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.card).border(UmbraDimens.borderW, c.borderSoft, shape).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.reminder_field_atts), color = c.faint, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
            Spacer8()
            // 永远说话，说法按空不空分（设计侧 140 §一）；英文一律「N / 4」。
            Text(
                if (reminderEnglish) stringResource(R.string.reminder_atts_counter, count, Reminder.maxAtts)
                else if (count > 0) "$count / ${Reminder.maxAtts}" else "最多 ${Reminder.maxAtts} 张",
                color = c.faint, style = TextStyle(fontSize = 11.sp),
            )
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            atts.forEach { a ->
                Thumb(label = a.label.ifEmpty { "图片" }, removeLabel = "移除这张附件", onRemove = {
                    router.confirm(UmbraAlert(
                        title = "移除这张附件？", message = "保存后这张图会从这条提醒上摘掉，服务端的文件也一起清。",
                        confirmLabel = "移除", destructive = true,
                    ) { onAtts(atts.filter { it.fileId != a.fileId }) })
                }) { ReminderRemoteImage(a.fileId, 78.dp, 13.dp) }
            }
            pending.forEach { p ->
                val failed = fails.any { it.id == p.id }
                Thumb(label = "待上传 · ${p.label}", removeLabel = "撤掉这张", onRemove = { drop(p.id) }, failed = failed) {
                    val bmp = remember(p.id) { runCatching { BitmapFactory.decodeByteArray(p.data, 0, p.data.size)?.asImageBitmap() }.getOrNull() }
                    val s = RoundedCornerShape(13.dp)
                    Box(Modifier.size(78.dp).clip(s).background(c.chip).border(if (failed) 2.dp else UmbraDimens.borderW, if (failed) c.danger else c.border, s), contentAlignment = Alignment.Center) {
                        if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else UmbraIcon(UmbraIconPaths.image, c.faint, size = 18.dp, strokeWidth = 1.8f)
                    }
                }
            }
            if (AttLimit.showsAddTile(count)) {
                Box(
                    Modifier.size(78.dp).clip(RoundedCornerShape(13.dp)).border(UmbraDimens.borderW, c.border, RoundedCornerShape(13.dp))
                        .clickable(role = Role.Button) { askAdd() },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        UmbraIcon(UmbraIconPaths.image, c.muted, size = 18.dp)
                        Text(stringResource(R.string.reminder_atts_add), color = c.muted, style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold))
                    }
                }
            }
        }
        // 满额常驻 strip（设计侧 220 §三2，与记一笔同形）：满 4 张出现、删到 3 张消失、不给钮。
        if (AttLimit.shown(count)) UmbraErrorStrip(AttLimit.REMINDER_TEXT, Modifier.clip(RoundedCornerShape(UmbraDimens.radiusControl)))
        AttachFailCopy.worst(fails) { it.err }?.let { f ->
            UmbraErrorCard(
                title = AttachFailCopy.title(subject, fails.size, f.err, f.mb),
                body = AttachFailCopy.reason(subject, f.err, ServerSettings.imageTiers.value[ImageTier.Clear.wire]),
                tone = UmbraTone.Danger,
                actions = listOf(AttachFailCopy.actionLabel(subject, f.err, savedOnce) to { act(f) }),
            )
        }
        Text(footnote, color = c.muted, style = TextStyle(fontSize = 11.5.sp, lineHeight = 18.sp))
    }
}

@Composable
private fun Spacer8() = Box(Modifier.width(8.dp))

@Composable
private fun SheetItem(label: String, onClick: () -> Unit) {
    val c = UmbraTheme.colors
    Box(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 14.dp)) {
        Text(label, color = c.text, style = TextStyle(fontSize = 16.sp))
    }
}

/** 78 的缩略格：右上角移除钮（编辑态不给点开预览，× 是唯一动作）、下方一行来源标签；失败的左上角一颗红点感叹号。 */
@Composable
private fun Thumb(label: String, removeLabel: String, onRemove: () -> Unit, failed: Boolean = false, image: @Composable () -> Unit) {
    val c = UmbraTheme.colors
    Column(Modifier.width(78.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            image()
            if (failed) {
                Box(Modifier.padding(3.dp).size(18.dp).clip(CircleShape).background(c.danger).semantics { contentDescription = "这张没传上" }, contentAlignment = Alignment.Center) {
                    Text("!", color = Color.White, style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold))
                }
            }
            Box(
                Modifier.align(Alignment.TopEnd).size(28.dp).clickable(role = Role.Button, onClick = onRemove).semantics { contentDescription = removeLabel },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(20.dp).clip(CircleShape).background(UmbraTheme.colors.toastGlass), contentAlignment = Alignment.Center) {
                    UmbraIcon(UmbraIconPaths.x, Color.White, size = 12.dp, strokeWidth = 2.4f)
                }
            }
        }
        Text(label, color = c.faint, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontSize = 10.5.sp), modifier = Modifier.padding(top = 5.dp))
    }
}

/**
 * 详情查看态的附件行：只看不改，点开看大图。缩略 64 / 圆角 12 / 间距 8，上面「附件 · N 张」（稿 attachment.reminder.viewIOS）。
 */
@Composable
fun ReminderAttsPreviewRow(atts: List<ReminderAtt>) {
    val c = UmbraTheme.colors
    var viewing by remember { mutableStateOf<ReminderAtt?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UmbraFieldLabel("附件")
            Spacer8()
            Text("${atts.size} 张", color = c.faint, style = TextStyle(fontSize = 11.sp))
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            atts.forEach { a ->
                ReminderRemoteImage(a.fileId, 64.dp, 12.dp, Modifier.clickable { viewing = a }.semantics { contentDescription = a.label.ifEmpty { "附件图片" } })
            }
        }
    }
    viewing?.let { a ->
        Dialog(onDismissRequest = { viewing = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(UmbraFixedColors.viewerBg).clickable { viewing = null }, contentAlignment = Alignment.Center) {
                SubcomposeAsyncImage(
                    model = UmbraImages.fileUrl(a.fileId), imageLoader = UmbraImages.loader, contentDescription = a.label.ifEmpty { "图片" },
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                    error = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.common_attGone), color = Color.White) } },
                )
            }
        }
    }
}

/** 从「文件」/ 相册读一张图的上限：服务端单个文件收 32 MiB，读进来还要缩一遍，放宽到 64 MB；再大的读进内存只会把 App 挤掉。 */
const val IMPORT_MAX_MB = 64

private sealed interface ImportRead {
    class Ok(val data: ByteArray, val name: String) : ImportRead
    data object TooBig : ImportRead
    data object Unreadable : ImportRead
}

/** 在主线程之外读选中的文件：先看大小（拿得到的话），超了不读；读完再按字节数挡一次。 */
private fun readImport(ctx: Context, uri: Uri): ImportRead {
    val limit = IMPORT_MAX_MB * 1024L * 1024L
    var name = ""
    runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
            if (cur.moveToFirst()) {
                val si = cur.getColumnIndex(OpenableColumns.SIZE)
                if (si >= 0 && !cur.isNull(si) && cur.getLong(si) > limit) return ImportRead.TooBig
                val ni = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (ni >= 0) name = cur.getString(ni) ?: ""
            }
        }
    }
    val d = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return ImportRead.Unreadable
    return if (d.size > limit) ImportRead.TooBig else ImportRead.Ok(d, name)
}
