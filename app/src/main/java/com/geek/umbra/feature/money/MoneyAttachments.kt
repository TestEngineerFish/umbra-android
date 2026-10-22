// 记一笔的附件区（批次 004 正式形态：缩略 78 / 圆角 13，一笔最多 4 张，加图先出来源选择）——
// 照抄 iOS Features/Money/MoneyAddView.swift:969-1284。单拆一个文件：选图三路（相册 / 拍照 / 文件）+ 扫发票 PDF 的
// ActivityResult 接线、上传流程、失败三段式都在这里，主文件只管表单。
//
// 满 4 张收起「加图」、不给禁用态（稿的原话），计数写在标题旁（有图 N / 4、没图「最多 4 张」）；
// 原始截图 = 凭证不可删，手动加的右上 × 可删。新建时账还没落库，图先攒在本地，保存成功后逐张上传挂接。
package com.geek.umbra.feature.money

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil3.compose.SubcomposeAsyncImage
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraErrorCard
import com.geek.umbra.designsystem.UmbraErrorStrip
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraListRow
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
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

/** 缩略图边长与圆角（批次 004 定稿：78 / 13）。iOS 写死的数，照抄。 */
private val THUMB = 78.dp
private val THUMB_RADIUS = 13.dp

// MARK: - 选图 / 选 PDF 的接线

/** 页面拿到的两个入口：「加图」（先出来源选择）和「扫发票 PDF」。 */
internal class MoneyAttachPickers(val askAddImage: () -> Unit, val pickInvoicePdf: () -> Unit)

/**
 * 三个加图来源 + 发票 PDF 的系统选择器。iOS: MoneyAddView.swift:339-372
 * - 相册：PickMultipleVisualMedia（系统照片选择器，不要存储权限；一次可多选，超出名额的在 addImage 里静默丢下 ——
 *   此刻满额 strip 已经在屏上说了）；
 * - 拍照：TakePicture 写进 cacheDir 下的临时文件，经 FileProvider（authority = 包名 + ".files"）交给相机；
 * - 文件：OpenDocument 只收图片（image 下任意子类型）；
 * - 发票：OpenDocument 只收 application/pdf。⚠️ OFD 这一版不收（稿：「OFD 第一版只存附件，不出解析行」）。
 */
@Composable
internal fun rememberMoneyAttachPickers(form: MoneyAddForm): MoneyAttachPickers {
    val ctx = LocalContext.current
    val router = AppGraph.router
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(AttLimit.MAX)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        MoneyStore.launch {
            for (u in uris) {
                val d = readBytes(ctx, u)
                if (d == null) router.showToast("这张图读不出来，换一张试试") else form.addImage(d, "相册图片")
            }
        }
    }
    var shotFile by remember { mutableStateOf<File?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = shotFile
        shotFile = null
        if (f == null) return@rememberLauncherForActivityResult
        MoneyStore.launch {
            // 相机已经写好 JPEG，原样收下；画质档由上传前那一道 prepare 统一处理（iOS 这里另压一次 0.85，Android 不重复压）。
            val d = if (ok) withContext(Dispatchers.IO) { runCatching { f.readBytes() }.getOrNull() } else null
            withContext(Dispatchers.IO) { f.delete() }
            if (d != null && d.isNotEmpty()) form.addImage(d, "拍的照片")
        }
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        MoneyStore.launch {
            val d = readBytes(ctx, uri)
            // 标签存来源不存文件名（批次 007 答复，tokens.attachment）。
            if (d == null) router.showToast("这张图读不出来，换一张试试") else form.addImage(d, "文件图片")
        }
    }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        MoneyStore.launch {
            // 读失败在这儿和「传不上去」是同一句话：两种都重试得动（iOS 同）。
            val d = readBytes(ctx, uri)
            form.scanInvoice(d, displayName(ctx, uri) ?: "invoice.pdf")
        }
    }
    return remember(form) {
        MoneyAttachPickers(
            askAddImage = {
                // 满额时「加图」那格根本不出，这一道只防竞态；该说的话由附件区那条常驻 strip 说（219 §三4）。
                if (AttLimit.showsAddTile(form.attCount())) {
                    router.present(UmbraSheet("money-add-image") { dismiss ->
                        AddImageSheet(dismiss,
                            onPhotos = { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            onCamera = {
                                // 模拟器 / 无摄像头设备直说，不给一个点了没反应的入口。
                                if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                                    router.showToast("这台设备没有可用的相机")
                                } else {
                                    val f = File(ctx.cacheDir, "money-shot-${UUID.randomUUID()}.jpg")
                                    shotFile = f
                                    camera.launch(FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f))
                                }
                            },
                            onFiles = { files.launch(arrayOf("image/*")) },
                        )
                    })
                }
            },
            pickInvoicePdf = { pdf.launch(arrayOf("application/pdf")) },
        )
    }
}

/** 「加图」来源选择（iOS UmbraSheet：标题「加图」+ 副标题 + 三项）。 */
@Composable
private fun AddImageSheet(dismiss: () -> Unit, onPhotos: () -> Unit, onCamera: () -> Unit, onFiles: () -> Unit) {
    val c = UmbraTheme.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp).navigationBarsPadding()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            Text("加图", color = c.text, style = UmbraType.sectionTitle)
            Text("一笔最多 4 张，跟账目一起存。", color = c.muted, style = UmbraType.rowSub)
        }
        UmbraListRow("从相册选择", onClick = { dismiss(); onPhotos() })
        UmbraListRow("拍照", onClick = { dismiss(); onCamera() })
        UmbraListRow("从「文件」选择", onClick = { dismiss(); onFiles() })
    }
}

/** 读系统选择器给的 Uri（沙箱外的内容，走 ContentResolver）。读不出回 null。 */
private suspend fun readBytes(ctx: Context, uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
    runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
}

/** 文件选择器里那份文件的名字（发票文件行要念它）。 */
private suspend fun displayName(ctx: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
            if (cur.moveToFirst()) cur.getString(0) else null
        }
    }.getOrNull()
}

// MARK: - 计数与上传

/**
 * 已挂在账上的 + 新建时先攒在本地的，加起来的总数（4 张上限按它算）。iOS: MoneyAddView.swift:975
 * ⚠️ 发票 PDF 不在这四张里（invoicePdfIsNotAnAttachment）：一个把两类东西数在一起的计数器，给出的那个数对谁都不准。
 */
internal fun MoneyAddForm.attCount(): Int = InvoicePdfKit.photos(editing()?.attList ?: emptyList()).size + pending.size

/**
 * 收下一张图。两条路都先进格子：编辑态（或新建后已存上、留在本页的那一笔）紧接着就传，新建态等保存。iOS: MoneyAddView.swift:1166
 * ⚠️ 编辑态原来是直接传、失败了连个格子都没有：没有格子就没有角可标，用户看到的是「刚加的那张图凭空不见了」。
 */
internal fun MoneyAddForm.addImage(data: ByteArray, label: String) {
    if (!AttLimit.showsAddTile(attCount())) return
    val item = PendingAtt(data, label)
    pending.add(item)
    val eid = editing()?.id ?: savedEntryId
    if (eid != null) MoneyStore.launch { upload(listOf(item), eid) }
}

/**
 * 按画质档压一张图（iOS MoneySettingsStore.prepareImage）。凭证默认「清晰」—— 小票上那行小字是给模型读的。
 * 档位没拉到、或那一档的长边像素没拉到（/settings.image_tiers）就**原样**：端上不拄一份档位数。
 * [override] 给「用清晰重发」用（只改这一次，不动设置表）。
 */
private suspend fun prepareMoneyImage(data: ByteArray, override: ImageTier?): PreparedImage {
    val untouched = PreparedImage(data, data.size, data.size, transcoded = false, png = false)
    val tier = override ?: MoneySettings.imageTier(ImagePurpose.Money) ?: return untouched
    val px = ServerSettings.imageTiers.value[tier.wire] ?: return untouched
    return ImageQuality.prepare(data, px)
}

/**
 * 逐张传 + 挂引用。**成功的从格子里消失，失败的留着并标红。** iOS: MoneyAddView.swift:1181
 * 要传哪几格是传进来的，不从 pending 里现取 —— 编辑态那一次 add 之后要传的只是刚加的那一张。
 */
internal suspend fun MoneyAddForm.upload(queue: List<PendingAtt>, entryId: String, tier: ImageTier? = null) {
    if (queue.isEmpty()) return
    attBusy = true
    val keys = queue.map { it.key }.toSet()
    attFails.removeAll { it.key in keys }
    val done = HashSet<String>()
    val fails = ArrayList<AttFail>()
    for (p in queue) {
        val prep = prepareMoneyImage(p.data, tier)
        // 后缀问 prep 要：透明的图输出的是 PNG，不是 JPEG。
        val name = prep.filename("photo-${System.currentTimeMillis() / 1000}.jpg")
        val mb = String.format(Locale.ROOT, "%.1f", prep.data.size.toDouble() / 1024 / 1024)
        // ⚠️ 四种失败（太大 / 令牌不对 / 服务端炸了 / 断网）不许压成同一个 null：稿的三档钮全靠这个分档。
        // 本机账本且没登录（单机化 ②-b）：先落本机（local: id），登录后同步补传；否则照常传服务端。
        val fileId: String? = if (MoneyStore.isLocal(MoneyStore.ledger.value) && AppGraph.guard.locked.value) {
            MoneyLocal.stageFile(prep.data, name) ?: run { fails.add(AttFail(p.key, UploadError.Server, mb)); null }
        } else {
            val (resp, err) = Uploads.upload(prep.data, name)
            resp?.file_id ?: run { fails.add(AttFail(p.key, err ?: UploadError.Server, mb)); null }
        }
        if (fileId == null) continue
        if (MoneyStore.addAtt(entryId, fileId, p.label)) done.add(p.key)
        else fails.add(AttFail(p.key, UploadError.Server, mb))
    }
    attBusy = false
    pending.removeAll { it.key in done }
    attFails.addAll(fails)
}

/** 撤掉一格。它那条失败记录也得跟着走 —— 不清的话 strip 会一直挂着一条指向不存在的格子的错误。 */
private fun MoneyAddForm.dropPending(key: String) {
    pending.removeAll { it.key == key }
    attFails.removeAll { it.key == key }
}

/** 这一笔的凭证画质（「太大」那一档的第二段要点名它）。没拉到设置时 null。 */
private val shotTier: ImageTier? get() = MoneySettings.imageTier(ImagePurpose.Money)

/** 这一处的主语。三段话本身在 AttachFailCopy，和提醒那一侧共用一张表（设计侧 057 §二）。 */
private val attSubject: AttachSubject get() = AttachSubject.money.with(shotTier)

/** 这会儿有没有重发的着落：记账要先有 entryId（账已经存上了才谈得上往它身上传图）。 */
private fun MoneyAddForm.canResend(): Boolean = (editing()?.id ?: savedEntryId) != null

/**
 * 撤掉一格 / 就地重发 / 去登录 / 去清理。动作留在调用点，不进那张文案表 —— 重发到哪条记录、跳哪一页，各处是各处的事。
 * iOS: MoneyAddView.swift:1252
 */
private fun MoneyAddForm.failAction(f: AttFail): () -> Unit {
    val one = pending.filter { it.key == f.key }
    val entryId = editing()?.id ?: savedEntryId
    val router = AppGraph.router
    return when (f.err) {
        // 会话是服务端发的，他手上没有 —— 出路是重新登录，不是去连接页。
        UploadError.Unauthorized, UploadError.SessionInvalid -> { { router.jump(Route.MeHome) } }
        UploadError.FileTooLarge, UploadError.BodyTooLarge ->
            if (shotTier == ImageTier.Origin && entryId != null) { { MoneyStore.launch { upload(one, entryId, ImageTier.Clear) } } }
            else { { dropPending(f.key) } }
        // 存储满了：重发、换一张都放不下，钮是「去清理」—— 进管理存储页腾地方（235 §三）。凭证还留在条里。
        is UploadError.StorageFull -> { { router.go(Route.SetStorage) } }
        else -> if (entryId != null) { { MoneyStore.launch { upload(one, entryId) } } } else { { dropPending(f.key) } }
    }
}

/** 删一张已挂上的附件：破坏性，过确认。iOS: MoneyAddView.swift:1272 */
private fun confirmDeleteAtt(e: MoneyEntryDTO, a: MoneyAttDTO) {
    val router = AppGraph.router
    router.confirm(UmbraAlert(
        title = "删掉这张附件？",
        message = "只删图片，账目本身不变。",
        confirmLabel = "删除",
        destructive = true,
        onConfirm = {
            MoneyStore.launch {
                val ok = MoneyStore.deleteAtt(e.id, a.file_id)
                router.showToast(if (ok) "已删掉附件" else "没删掉，检查网络后再试")
            }
        },
    ))
}

// MARK: - 附件卡

/** 附件卡。新建、编辑都出现（新建时先攒本地、保存后上传）。iOS: MoneyAddView.swift:981 */
@Composable
internal fun MoneyAttachmentsCard(form: MoneyAddForm, editing: MoneyEntryDTO?, isBiz: Boolean, pickers: MoneyAttachPickers) {
    val c = UmbraTheme.colors
    val photos = InvoicePdfKit.photos(editing?.attList ?: emptyList())
    val count = photos.size + form.pending.size
    Column(Modifier.moneyCard().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("附件", color = c.faint, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
            // ⚠️ 这一格永远说话，说法按空不空分（设计侧 140 §一）：一张都没有时写「最多 4 张」，不写「0 / 4」——
            // 一张图都没有的人正是最需要知道能放几张的那个人。
            Text(if (count > 0) "$count / ${AttLimit.MAX}" else "最多 4 张", color = c.faint, style = TextStyle(fontSize = 11.sp))
            if (form.attBusy) Text("上传中…", color = c.faint, style = TextStyle(fontSize = 11.sp))
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // ⚠️ photos 而不是 attList：那份发票 PDF 混在里面的话，这儿会给它摆一张永远加载不出来的破图格子。
            if (editing != null) photos.forEach { a -> AttThumb(editing, a) }
            form.pending.forEach { p -> PendingThumb(form, p) }
            if (AttLimit.showsAddTile(count)) AddTile(pickers.askAddImage)
        }
        // at_limit（设计侧 219 §三4）：满 4 张时常驻一条 strip，不给钮，一直在到删掉一张为止。warning 档：什么都没坏，只是满了。
        if (AttLimit.shown(count)) UmbraErrorStrip(AttLimit.MONEY_TEXT, Modifier.clip(RoundedCornerShape(UmbraDimens.radiusControl)), tone = UmbraTone.Warning)
        // 三段式在附件区下面一条，不塞进 78 的瓦片里。多张同时失败只出一条，账号级那两档优先（AttachFailCopy.worst）。
        AttachFailCopy.worst(form.attFails.toList()) { it.err }?.let { f ->
            val s = attSubject
            UmbraErrorCard(
                title = AttachFailCopy.title(s, form.attFails.size, f.err, f.mb),
                body = AttachFailCopy.reason(s, f.err, ServerSettings.imageTiers.value[ImageTier.Clear.wire]),
                tone = UmbraTone.Danger,
                actions = listOf(AttachFailCopy.actionLabel(s, f.err, form.canResend()) to form.failAction(f)),
            )
        }
        Text(
            if (editing?.attList?.any { it.origin } == true) "这笔是截图记的，原始截图一直留着，不能删。其它图可以随时加减。"
            else "小票、账单、转账截图都能放这儿，跟账目一起存。",
            color = c.muted, style = TextStyle(fontSize = 11.5.sp, lineHeight = 17.8.sp),
        )
        // ⚠️ 稿明写的那一句（invoicePdfIsNotAnAttachment 末段）：只在经营账本上出。
        // 没有它，扫过发票的人会在这儿数来数去 —— 那个对不上的 1，他找不到地方去问。
        if (isBiz) Text(InvoicePdfKit.NOT_AN_ATTACHMENT_HINT, color = c.muted, style = TextStyle(fontSize = 11.5.sp, lineHeight = 17.8.sp))
    }
}

/** 附件图的数据源：本机暂存的（local:）读本机文件；服务端的走 /files/{id}（交给 [UmbraImages.loader]，令牌由统一出口带）。 */
private fun attModel(fileId: String): Any? =
    if (MoneyLocalRules.isLocalFileId(fileId)) MoneyLocal.fileFor(fileId) else UmbraImages.fileUrl(fileId)

/** 加载中 / 读不出来时那块灰底（只有图标）。 */
@Composable
private fun ThumbPlaceholder() {
    val c = UmbraTheme.colors
    Box(Modifier.fillMaxSize().background(c.chip), contentAlignment = Alignment.Center) {
        UmbraIcon(UmbraIconPaths.image, c.faint, size = 18.dp, strokeWidth = 1.8f)
    }
}

/**
 * 「附件已删除」占位（iOS UmbraAttGone.swift）：服务端已经没有这张（在「管理存储」里删掉了）—— 灰底 + 那句话，不画成「还在加载」。
 * 404 和断网在加载失败那一档里分不开，所以断网时也会出现；断网时屏上别处已经有离线条说明。
 */
@Composable
private fun AttGonePlaceholder() {
    val c = UmbraTheme.colors
    Column(
        Modifier.fillMaxSize().background(c.chip).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        UmbraIcon(UmbraIconPaths.image, c.faint, size = 16.dp, strokeWidth = 1.8f)
        // 这一句是在回答「图呢」：用 muted，不是装饰用的 faint（227 §二2）。
        Text(stringResource(R.string.common_attGone), color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontSize = 10.5.sp))
    }
}

/**
 * 编辑态缩略图右上角的删除角标（iOS UmbraPrimitives.swift:109 UmbraAttachRemoveBadge）：视觉圆 20、贴角撑 44，
 * 热区往左下长、全落在图里。底色是 iOS 写死的近黑 72%，这里取 nav（同一种近黑）—— token 表里没有专门的遮罩色。
 */
@Composable
private fun RemoveBadge(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = UmbraTheme.colors
    Box(modifier.size(UmbraDimens.tapMin).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.TopEnd) {
        Box(
            Modifier.padding(4.dp).size(20.dp).clip(CircleShape).background(c.nav.copy(alpha = 0.72f)),
            contentAlignment = Alignment.Center,
        ) { UmbraIcon(UmbraIconPaths.x, Color.White, size = 10.dp, strokeWidth = 3f, contentDescription = label) }
    }
}

/** 已挂上的一张。原始截图没有删除键（它是凭证），普通附件右上角一个 ×。iOS: MoneyAddView.swift:1046 */
@Composable
private fun AttThumb(e: MoneyEntryDTO, a: MoneyAttDTO) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(THUMB_RADIUS)
    val model = attModel(a.file_id)
    Column(Modifier.width(THUMB), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(THUMB)) {
            // 点开预览只留给原始截图（attachThumb.editing，批次 016）：编辑态别的图上 × 是唯一的动作 ——
            // 44 的 × 钉在 78 的图上会把图的正中心划进删除区。原始截图没有删除键，冲突不存在。
            SubcomposeAsyncImage(
                model = model, imageLoader = UmbraImages.loader, contentDescription = null, contentScale = ContentScale.Crop,
                loading = { ThumbPlaceholder() }, error = { AttGonePlaceholder() },
                modifier = Modifier.size(THUMB).clip(shape).border(UmbraDimens.borderW, c.border, shape)
                    .then(if (a.origin) Modifier.clickable(role = Role.Image) { previewOrigin(a.file_id) } else Modifier),
            )
            if (!a.origin) RemoveBadge("移除这张附件", { confirmDeleteAtt(e, a) }, Modifier.align(Alignment.TopEnd))
        }
        Text(
            if (a.origin) "原始截图" else a.label.ifEmpty { "图片" },
            color = c.faint, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontSize = 10.5.sp),
        )
    }
}

/**
 * 原始截图点开看一眼（记一笔时想核对一眼原始账单是真诉求）。
 * iOS 是全 App 共用的应用内预览器（UmbraImageViewer）；Android 的 designsystem 还没有这件，
 * 这里先用底部弹层摆一张大图，补上共用预览器后换掉（报告里写了）。
 */
private fun previewOrigin(fileId: String) {
    AppGraph.router.present(UmbraSheet("money-origin-preview") { _ ->
        val c = UmbraTheme.colors
        Column(Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("原始截图", color = c.text, style = UmbraType.sectionTitle)
            SubcomposeAsyncImage(
                model = attModel(fileId), imageLoader = UmbraImages.loader, contentDescription = "原始截图", contentScale = ContentScale.Fit,
                loading = { Box(Modifier.fillMaxWidth().heightIn(min = 200.dp)) { ThumbPlaceholder() } },
                error = { Box(Modifier.fillMaxWidth().heightIn(min = 200.dp)) { AttGonePlaceholder() } },
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).clip(RoundedCornerShape(UmbraDimens.radiusShot)),
            )
        }
    })
}

/**
 * 本地攒着的一张（新建态 / 传失败的）：还没上传，随时可撤。iOS: MoneyAddView.swift:1090
 * 传失败的那一格只标一个角（2px danger 内圈 + 左上一颗实心圆点感叹号）：78 的瓦片装不下三段话。
 */
@Composable
private fun PendingThumb(form: MoneyAddForm, p: PendingAtt) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(THUMB_RADIUS)
    val failed = form.attFails.any { it.key == p.key }
    Column(Modifier.width(THUMB), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(THUMB)) {
            SubcomposeAsyncImage(
                model = p.data, contentDescription = null, contentScale = ContentScale.Crop,
                loading = { ThumbPlaceholder() }, error = { ThumbPlaceholder() },
                modifier = Modifier.size(THUMB).clip(shape)
                    .border(if (failed) 2.dp else UmbraDimens.borderW, if (failed) c.danger else c.border, shape),
            )
            if (failed) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(3.dp).size(18.dp).clip(CircleShape).background(c.danger),
                    contentAlignment = Alignment.Center,
                ) { Text("!", color = c.onDanger, style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold)) }
            }
            RemoveBadge("撤掉这张", { form.dropPending(p.key) }, Modifier.align(Alignment.TopEnd))
        }
        Text(p.label.ifEmpty { "待上传" }, color = c.faint, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontSize = 10.5.sp))
    }
}

/** 「加图」瓦片：虚线框，点了先出来源选择（稿：不直接塞空位）。 */
@Composable
private fun AddTile(onClick: () -> Unit) {
    val c = UmbraTheme.colors
    val border = c.border
    Column(
        Modifier.size(THUMB)
            .drawBehind {
                drawRoundRect(
                    border, cornerRadius = CornerRadius(THUMB_RADIUS.toPx()),
                    style = Stroke(UmbraDimens.borderW.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                )
            }
            .clip(RoundedCornerShape(THUMB_RADIUS))
            .clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        UmbraIcon(UmbraIconPaths.image, c.muted, size = 18.dp)
        Text("加图", color = c.muted, style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold))
    }
}
