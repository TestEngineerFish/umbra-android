// 聊天 · 对话页（iOS: Features/Chat/ChatThreadView.swift，2486 行逐段对照移植）。
//
// 数据全部来自 ChatStore.blocks，**没有 mock**。块与画法的对应：
//   user → 用户气泡   assistant → 秘书气泡（+ 工具轨迹卡 / 识图行 / 琥珀行 / 接着跑 / 出处）   image → 图片消息
//   device → 设备气泡  task → 任务进度卡   done → 完成卡   confirm → 执行前确认卡   question → 问答卡
//   locate → 求助卡    note → 居中系统行   error → 错误卡
// 平台替换：系统 contextMenu → 长按 DropdownMenu；PhotosPicker / 相机 / fileImporter → Photo Picker / TakePicture / OpenMultipleDocuments；
// 按住说话 → SpeechRecognizer（见 ChatVoice.kt）。
package com.geek.umbra.feature.chat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraMenuItem
import com.geek.umbra.designsystem.UmbraMoreMenu
import com.geek.umbra.designsystem.UmbraPlainScreen
import com.geek.umbra.designsystem.UmbraSpinningIcon
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraTopBarText
import com.geek.umbra.designsystem.UmbraType
import com.geek.umbra.feature.duty.DutyBriefCard
import com.geek.umbra.feature.duty.DutyChatApi
import com.geek.umbra.feature.duty.DutyNoticeAction
import com.geek.umbra.feature.phrases.PhrasesApi
import com.geek.umbra.feature.remote.RemoteDeviceMenu
import com.geek.umbra.feature.remote.RemoteLauncher
import com.geek.umbra.navigation.Route
import com.geek.umbra.navigation.UmbraAlert
import com.geek.umbra.navigation.UmbraSheet
import com.geek.umbra.net.ImageTier
import com.geek.umbra.net.Prefs
import com.geek.umbra.net.UmbraImages
import com.geek.umbra.net.UploadError
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 停止钮的视觉直径（热区 44）。 */
private val STOP_D = 32.dp

/** 「这台手机没有系统语音识别服务」—— 国行无 GMS 机型的说明（Android 独有）。 */
private const val NO_SPEECH = "这台手机没有系统语音识别服务"

@Composable
fun ChatThreadPage(conv: String) {
    val c = UmbraTheme.colors
    val ctx = LocalContext.current
    val router = AppGraph.router
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current

    val blocks by ChatStore.blocks.collectAsState()
    val activeConv by ChatStore.activeConv.collectAsState()
    val history by ChatStore.historyRow.collectAsState()
    val devices by ChatStore.devices.collectAsState()
    val draft by ChatStore.draft.collectAsState()
    val quote by ChatStore.quote.collectAsState()
    val chip by ChatStore.chipAction.collectAsState()
    val slashDismissed by ChatStore.slashDismissed.collectAsState()
    val ideaBanner by ChatStore.ideaBanner.collectAsState()
    val focusPending by ChatStore.composerFocusPending.collectAsState()
    val prefs by AppGraph.prefs.snapshot.collectAsState()
    val locate by ChatFocus.locate.collectAsState()

    val isAssistant = conv == ChatStore.MAIN_CONV
    val device = devices.firstOrNull { it.conversation == conv }
    val title = ChatStore.convLabel(conv)
    // 设备会话默认只读：开关在「我 › 设备与能力 › 设备详情」里（allowDeviceSend）
    val inputEnabled = isAssistant || (prefs[Prefs.K.allowDeviceSend] ?: false)
    // 当前页的块：切会话那一瞬 activeConv 还是旧的，别把别的会话的块画到这一页上
    val shown = if (activeConv == conv) blocks else emptyList()

    var voiceMode by remember { mutableStateOf(false) }
    var quickVoiceActive by remember { mutableStateOf(false) }
    var inputFocused by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<ViewerGroup?>(null) }
    var pending by remember { mutableStateOf<List<PendingImage>>(emptyList()) }
    // 只管这一条消息的画质档位；切会话天然重置（每个会话各有各的页面状态）
    var pendingTier by remember { mutableStateOf<ImageTier?>(null) }
    var pendingOrigin by remember { mutableStateOf(false) }
    var jumpTo by remember { mutableStateOf<Long?>(null) }
    var flashBlockId by remember { mutableStateOf<String?>(null) }
    val inputFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val rec = remember { HoldRecorder(ctx.applicationContext) }
    val speechOk = remember { HoldRecorder.available(ctx) }
    val notConnected = stringResource(R.string.chat_notConnected)

    val slashOn = chip == null && !slashDismissed && draft.startsWith("/") && !voiceMode && inputEnabled

    // MARK: 权限与选图
    fun hasMic() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) router.showToast("麦克风或语音识别不可用，去系统设置里开一下")
    }

    fun takeImages(datas: List<ByteArray>) {
        val room = ChatImageMetric.MAX_COUNT - pending.size
        if (room <= 0) { router.showToast(ctx.getString(R.string.chat_img_tooMany)); return }
        pending = pending + datas.take(room).map { PendingImage(data = it) }
        if (datas.size > room) router.showToast(ctx.getString(R.string.chat_img_tooMany))
    }
    fun readUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch { val out = uris.mapNotNull { ChatFiles.read(it) }; if (out.isNotEmpty()) takeImages(out) }
    }
    val remaining = (ChatImageMetric.MAX_COUNT - pending.size).coerceAtLeast(2)
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(remaining)) { readUris(it) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = cameraUri
        if (ok && u != null) readUris(listOf(u))
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { readUris(it) }
    var afterStorageGrant by remember { mutableStateOf<(() -> Unit)?>(null) }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val act = afterStorageGrant
        afterStorageGrant = null
        // iOS 那句指向「设置 › Umbra › 照片」，Android 的入口不同，这里换成 Android 的路径说法
        if (granted) act?.invoke() else router.showToast("没有存储写入权限，去「设置 › 应用 › Umbra › 权限」里开一下")
    }

    // 「+」：相册 / 拍照 / 文件三个入口
    fun askAddImage() {
        if (pending.size >= ChatImageMetric.MAX_COUNT) { router.showToast(ctx.getString(R.string.chat_img_tooMany)); return }
        router.present(UmbraSheet("chat-add-image") { dismiss ->
            Column(Modifier.fillMaxWidth().padding(horizontal = UmbraDimens.pagePadX).padding(bottom = 24.dp)) {
                Text(stringResource(R.string.chat_img_sheetTitle), color = c.text, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.chat_img_sheetSub), color = c.muted, style = TextStyle(fontSize = 13.sp))
                Spacer(Modifier.height(8.dp))
                listOf(
                    stringResource(R.string.chat_attach_album) to { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    stringResource(R.string.chat_attach_camera) to {
                        // 没有相机的设备直说，不给一个点了闪退的入口
                        if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) router.showToast(ctx.getString(R.string.chat_img_noCamera))
                        else { val u = ChatFiles.newCameraUri(); cameraUri = u; takePicture.launch(u) }
                    },
                    stringResource(R.string.chat_attach_file) to { pickFiles.launch(arrayOf("image/*")) },
                ).forEach { (label, act) ->
                    Box(Modifier.fillMaxWidth().heightIn(min = UmbraDimens.rowMinH).clickable { dismiss(); act() }, contentAlignment = Alignment.CenterStart) {
                        Text(label, color = c.text, style = TextStyle(fontSize = 16.sp))
                    }
                }
            }
        })
    }

    // MARK: 生命周期：进页切会话、拿「待聚焦」的旗；切后台 / 离开丢弃录音
    fun takeComposerFocus() {
        if (!ChatStore.composerFocusPending.value || !isAssistant || !inputEnabled) return
        ChatStore.composerFocusPending.value = false
        voiceMode = false
        // 推入动画还没走完就给焦点，键盘会和转场抢同一帧 —— 晚一拍
        scope.launch { delay(350); runCatching { inputFocus.requestFocus() } }
    }
    LaunchedEffect(conv) {
        if (ChatStore.activeConv.value != conv) ChatStore.switchConversation(conv)
        takeComposerFocus()
    }
    LaunchedEffect(focusPending) { if (focusPending) takeComposerFocus() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(conv) {
        ChatFocus.appear(conv)
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) rec.abort() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); rec.abort(); ChatFocus.disappear(conv) }
    }

    // MARK: 发送
    fun sendPending(): Boolean {
        // 有超限的就整条拦下（图和文都不发），并点名是第几张
        val bad = pending.indexOfFirst { it.oversize }
        if (bad >= 0) {
            router.showToast(ctx.getString(R.string.chat_img_oversize, bad + 1, umbraMB(pending[bad].bytes), umbraMB(ChatImageMetric.MAX_BYTES)))
            return false
        }
        if (pending.isNotEmpty()) {
            val once: ImageTier? = if (pendingOrigin) ImageTier.Origin else pendingTier
            val datas = pending.map { it.data }
            ChatStore.sendImages(datas, once, pending.map { it.prep }, if (datas.size == 1) ChatFiles.ratio(datas[0]) else null)
            pending = emptyList(); pendingTier = null; pendingOrigin = false
        }
        if (ChatStore.draft.value.isNotBlank()) ChatStore.send()
        return true
    }

    fun finishRecording(r: HoldRecorder.Result) {
        when (r) {
            is HoldRecorder.Result.Send -> { ChatStore.setDraft(r.text); if (!sendPending()) voiceMode = false }
            is HoldRecorder.Result.ToText -> { ChatStore.setDraft(r.text); voiceMode = false }
            HoldRecorder.Result.Cancel -> Unit
            HoldRecorder.Result.TooShort -> router.showToast("说话时间太短，没听清")
            HoldRecorder.Result.Unavailable -> router.showToast("麦克风或语音识别不可用，去系统设置里开一下")
        }
    }
    LaunchedEffect(rec) { rec.onAutoFinish = { finishRecording(it) } }

    /** 落区判定：以浮层里两个圆钮的实测窗口坐标为准（外扩 14 容差）；量到之前按整屏比例兜底。 */
    val screenW = LocalConfiguration.current.screenWidthDp
    val screenH = LocalConfiguration.current.screenHeightDp
    fun zoneAt(p: Offset): HoldRecorder.Zone {
        val tol = with(density) { 14.dp.toPx() }
        val w = with(density) { screenW.dp.toPx() }
        val h = with(density) { screenH.dp.toPx() }
        val cz = rec.cancelZone; val tz = rec.textZone
        if (cz.width > 0 && tz.width > 0) {
            if (cz.inflate(tol).contains(p)) return HoldRecorder.Zone.Cancel
            if (tz.inflate(tol).contains(p)) return HoldRecorder.Zone.Text
            if (p.y < cz.top) {
                if (p.x < w * 0.382f) return HoldRecorder.Zone.Cancel
                if (p.x > w * 0.618f) return HoldRecorder.Zone.Text
            }
            return HoldRecorder.Zone.Send
        }
        if (p.y > h * 0.868f) return HoldRecorder.Zone.Send
        if (p.x < w * 0.382f) return HoldRecorder.Zone.Cancel
        if (p.x > w * 0.618f) return HoldRecorder.Zone.Text
        return HoldRecorder.Zone.Send
    }

    // MARK: 滚动
    // 往下拖收键盘（IM 的肌肉记忆）。只认手指拖动，不认新消息到来时的程序滚动 —— 那会把正在打字的键盘收掉
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) { if (dragged) focusManager.clearFocus() }
    val headerCount = (if (history.show) 1 else 0) + (if (shown.isEmpty() && !history.failed) 1 else 0)
    suspend fun scrollToEnd() {
        val n = listState.layoutInfo.totalItemsCount
        if (shown.isNotEmpty() && n > 0) listState.animateScrollToItem(n - 1)
    }
    // 只在**末尾那一块换了**时回底（往顶上插旧消息、删掉中间某条都不跟滚）
    LaunchedEffect(shown.lastOrNull()?.id) { scrollToEnd() }
    LaunchedEffect(inputFocused) { scrollToEnd() }
    LaunchedEffect(pending.size) { scrollToEnd() }
    // 回到被引用的那条：滚过去 + 闪一圈 orangeSoft 1.4 秒；找不到就说一声（多半是还没翻到）
    LaunchedEffect(jumpTo) {
        val sid = jumpTo ?: return@LaunchedEffect
        jumpTo = null
        val i = shown.indexOfFirst { it.serverId == sid }
        if (i < 0) { router.showToast(ctx.getString(R.string.chat_quoteJumpMissing)); return@LaunchedEffect }
        listState.animateScrollToItem(i + headerCount)
        val id = shown[i].id
        flashBlockId = id
        delay(1400)
        if (flashBlockId == id) flashBlockId = null
    }
    // 点通知进来定位那一条：可能还没到（回前台时长连接正在补拉）—— 块变了就再找一次，最多等 6 秒
    LaunchedEffect(shown.size, locate) {
        val t = locate ?: return@LaunchedEffect
        if (t.conv != conv) return@LaunchedEffect
        if (shown.any { it.serverId == t.messageId }) {
            ChatFocus.locate.value = null
            delay(350); jumpTo = t.messageId
        }
    }
    LaunchedEffect(conv) {
        val first = ChatFocus.locate.value ?: return@LaunchedEffect
        if (first.conv != conv) return@LaunchedEffect
        repeat(6) { delay(1000); if (ChatFocus.locate.value != first) return@LaunchedEffect }
        if (ChatFocus.locate.value == first) { ChatFocus.locate.value = null; jumpTo = first.messageId }
    }

    // MARK: 菜单动作
    fun quoteWho(role: String): String = when (role) {
        "user" -> ctx.getString(R.string.chat_quoteMe)
        // 设备的话不能无脑用 title：主会话里 title 是「秘书」，会把另一台机器说的话安到秘书头上
        "device" -> if (isAssistant) ctx.getString(R.string.chat_quoteWho_device) else title
        else -> ctx.getString(R.string.chat_conv_secretary)
    }
    fun mCopy(text: String) = ChatMenuItem(ctx.getString(R.string.chat_copy)) { ChatFiles.copyText(text); router.showToast(ctx.getString(R.string.chat_copied)) }
    fun mQuote(id: Long?, role: String, text: String): ChatMenuItem? = if (!inputEnabled) null else ChatMenuItem(ctx.getString(R.string.chat_menu_quote)) {
        voiceMode = false
        ChatStore.quoteMessage(id, role, text)
        scope.launch { delay(50); runCatching { inputFocus.requestFocus() } }
    }
    fun mPhrase(text: String) = ChatMenuItem(ctx.getString(R.string.chat_menu_phrase)) {
        // 取第一行前 12 字当名字（和秘书侧 add_phrase 同规则）；trim 要含换行
        val body = text.trim()
        val head = (text.split("\n").firstOrNull() ?: "").trim()
        val name = (head.ifEmpty { body }).take(12)
        if (name.isEmpty()) return@ChatMenuItem
        router.showToast(ctx.getString(if (PhrasesApi.addFromChat(name, text)) R.string.chat_phraseSaved else R.string.chat_phraseExists))
    }
    fun mIdea(text: String) = ChatMenuItem(ctx.getString(R.string.chat_menu_idea)) {
        scope.launch { router.showToast(ctx.getString(if (ChatStore.saveIdea(text)) R.string.chat_ideaSaved else R.string.chat_ideaSaveFailed)) }
    }
    // 添加提醒：正文预填进新建提醒页，让用户自己补时间（不替用户猜一个时间）
    fun mRemind(text: String) = ChatMenuItem(ctx.getString(R.string.chat_menu_remind)) { router.jump(Route.RemEdit(null, prefill = text.take(200))) }
    fun mDelete(serverId: Long?, settling: Boolean = true): ChatMenuItem? = when {
        // 本该有 id、只是还没拿到：照样给「删除」，点了吐一句实话（不静默少一项）
        serverId == null && settling -> ChatMenuItem(ctx.getString(R.string.chat_menu_delete), danger = true) { router.showToast(ctx.getString(R.string.chat_msgSettling)) }
        serverId == null -> null
        else -> ChatMenuItem(ctx.getString(R.string.chat_menu_delete), danger = true) {
            router.confirm(UmbraAlert(title = ctx.getString(R.string.chat_delMsgTitle), message = ctx.getString(R.string.chat_delMsgBody), confirmLabel = ctx.getString(R.string.chat_menu_delete), destructive = true) {
                // 本地不抢着删：服务端广播 message_deleted 会回来
                router.showToast(ctx.getString(if (ChatStore.deleteMessage(serverId)) R.string.chat_deleted else R.string.chat_notConnected))
            })
        }
    }
    fun myMenu(u: ChatBlock.User): List<ChatMenuItem> = if (u.failed) listOf(
        ChatMenuItem(ctx.getString(R.string.chat_menu_resend)) { ChatStore.resendFailed(u.id) },
        mCopy(u.text),
        // 服务端上根本没有这条，只是本地移除 —— 不走「移入回收站 30 天」那套确认
        ChatMenuItem(ctx.getString(R.string.chat_menu_delete), danger = true) { ChatStore.dropLocal(u.id) },
    ) else listOfNotNull(mCopy(u.text), mQuote(u.serverId, "user", u.text), mPhrase(u.text), mIdea(u.text), mRemind(u.text), mDelete(u.serverId))
    // 秘书的话不给「存为常用语」（常用语是「你常写的话」）；设备的话不归我们删
    fun botMenu(text: String, serverId: Long?, role: String = "assistant") =
        listOfNotNull(mCopy(text), mQuote(serverId, role, text), mIdea(text), mRemind(text), mDelete(serverId, settling = role != "device"))
    fun cardMenu(summary: String) = listOf(ChatMenuItem(ctx.getString(R.string.chat_menu_copySummary)) { ChatFiles.copyText(summary); router.showToast(ctx.getString(R.string.chat_copied)) })

    fun openImage(img: ChatBlock.Image, at: Int) {
        val items = img.atts.mapIndexed { j, fid -> UmbraImages.fileUrl(fid) to ctx.getString(R.string.chat_img_nth, j + 1) }
        if (items.isEmpty()) return
        viewer = ViewerGroup(items, minOf(at, items.size - 1))
    }
    fun copyImage(img: ChatBlock.Image) {
        val done = ctx.getString(if (img.count > 1) R.string.chat_img_copiedFirst else R.string.chat_img_copied)
        scope.launch {
            val bytes = img.data.firstOrNull() ?: img.atts.firstOrNull()?.let { UmbraImages.download(UmbraImages.fileUrl(it)) }
            router.showToast(if (bytes != null && ChatFiles.copyImage(bytes)) done else ctx.getString(R.string.chat_img_copyFailed))
        }
    }
    fun saveImages(img: ChatBlock.Image) {
        val run = {
            scope.launch {
                var ok = 0
                img.atts.forEachIndexed { j, fid ->
                    val bytes = img.data.getOrNull(j) ?: UmbraImages.download(UmbraImages.fileUrl(fid))
                    if (bytes != null && ChatFiles.saveToAlbum(bytes)) ok++
                }
                router.showToast(if (ok > 0) ctx.getString(R.string.chat_img_saved, ok) else ctx.getString(R.string.chat_img_saveFailed))
            }
            Unit
        }
        // Android 9 存相册要写外部存储权限；10 起 MediaStore 不需要
        if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            afterStorageGrant = run
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else run()
    }
    fun imageMenu(img: ChatBlock.Image): List<ChatMenuItem> = if (img.state == ImageState.Failed) listOfNotNull(
        ChatMenuItem(ctx.getString(R.string.chat_menu_resend)) { ChatStore.retryImages(img.id) },
        // 一张都没传成的时候不给「查看大图」—— 服务端上还没有这张
        if (img.atts.isNotEmpty()) ChatMenuItem(ctx.getString(R.string.chat_menu_viewImage)) { openImage(img, 0) } else null,
        // 删除是丢掉，不回填待发条（回填只归「取消上传」）
        ChatMenuItem(ctx.getString(R.string.chat_menu_delete), danger = true) { ChatStore.cancelImageUpload(img.id) },
    ) else listOfNotNull(
        ChatMenuItem(ctx.getString(R.string.chat_menu_viewImage)) { openImage(img, 0) },
        // 标签按张数分档：剪贴板单槽只复制第一张、相册存整条 —— 边界写进标签
        ChatMenuItem(ctx.getString(if (img.count > 1) R.string.chat_menu_copyFirstImage else R.string.chat_menu_copyImage)) { copyImage(img) },
        mQuote(img.serverId, "user", ctx.getString(R.string.chat_quoteImage, img.count)),
        ChatMenuItem(if (img.count > 1) ctx.getString(R.string.chat_menu_saveNToAlbum, img.count) else ctx.getString(R.string.chat_menu_saveToAlbum)) { saveImages(img) },
        mDelete(img.serverId),
    )
    fun dropImages(img: ChatBlock.Image) {
        val back = ChatStore.cancelImageUpload(img.id)
        if (back.isEmpty()) return
        val room = ChatImageMetric.MAX_COUNT - pending.size
        pending = pending + back.take(maxOf(0, room)).map { PendingImage(data = it) }
        if (back.size > room) router.showToast(ctx.getString(R.string.chat_img_tooMany))
    }
    fun transcript(): String = shown.mapNotNull { b ->
        when (b) {
            is ChatBlock.User -> "我：${b.text}"
            is ChatBlock.Assistant -> if (b.text.isEmpty()) null else "秘书：${b.text}"
            is ChatBlock.Image -> "${ctx.getString(R.string.chat_quoteMe)}：${ctx.getString(R.string.chat_quoteImage, b.count)}"
            is ChatBlock.Device -> "$title：${b.text}"
            is ChatBlock.Note -> b.text
            else -> null
        }
    }.joinToString("\n\n")

    val contentW = (screenW.dp - UmbraDimens.pagePadX * 2)

    // MARK: 页面
    Box(Modifier.fillMaxSize()) {
        UmbraPlainScreen(
            title = title,
            onBack = { router.back() },
            titleContent = {
                // 标题带 26 头像：秘书=橙色圆 + 机器人，设备=圆角方块 + 显示器
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(26.dp).clip(RoundedCornerShape(if (isAssistant) 13.dp else 8.dp)).background(if (isAssistant) c.orange else c.chip), contentAlignment = Alignment.Center) {
                        UmbraIcon(if (isAssistant) UmbraIconPaths.robot else UmbraIconPaths.monitor, if (isAssistant) c.onAccent else c.muted, size = 14.dp)
                    }
                    Spacer(Modifier.width(7.dp))
                    Text(title, color = c.text, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            actions = {
                if (isAssistant) {
                    UmbraMoreMenu(listOf(
                        // 离线时 /new 出不去，本地历史也就不该清
                        UmbraMenuItem("新会话") { router.showToast(if (ChatStore.newSession()) "已开始新会话" else notConnected) },
                        UmbraMenuItem("复制聊天") { ChatFiles.copyText(transcript()); router.showToast("已复制") },
                        UmbraMenuItem("清空聊天", danger = true) {
                            router.confirm(UmbraAlert(
                                title = "确认清空与秘书的聊天历史？",
                                // 这一端的回收站没有聊天那一类：句子带上路（说清在哪儿找），设计侧 227 §一1
                                message = "清空后移入回收站，保留 30 天，可在电脑端找回（设备会话不受影响）。",
                                confirmLabel = "清空", destructive = true,
                            ) { ChatStore.clearActiveHistory(); router.showToast("聊天历史已清空") })
                        },
                    ))
                } else if (device != null) {
                    UmbraTopBarText("设备详情", { router.go(Route.DeviceDetail(device.device_id)) })
                    RemoteDeviceMenu(device)
                }
            },
        ) {
            Column(Modifier.fillMaxSize().imePadding()) {
                if (device != null && !device.online) {
                    // 设备离线横幅：warning-soft 底 + warning 字 + 三角图标 —— 状态不只靠颜色
                    Row(Modifier.fillMaxWidth().background(c.warningSoft).padding(horizontal = UmbraDimens.pagePadX, vertical = UmbraDimens.sp3), verticalAlignment = Alignment.CenterVertically) {
                        UmbraIcon(UmbraIconPaths.alertTriangle, c.warningText, size = 15.dp, strokeWidth = 2f)
                        Spacer(Modifier.width(8.dp))
                        Text("这台设备当前离线，端侧任务无法执行", color = c.warningText, style = TextStyle(fontSize = 12.5.sp, fontWeight = UmbraType.weight560))
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) },
                        state = listState,
                        contentPadding = PaddingValues(start = UmbraDimens.pagePadX, end = UmbraDimens.pagePadX, top = UmbraDimens.sp5, bottom = UmbraDimens.sp6),
                        verticalArrangement = Arrangement.spacedBy(UmbraDimens.sp4),
                    ) {
                        if (history.show) item(key = "__history") { HistoryRow(history) }
                        // 首屏没拉到时不画空态：上面那一行的「加载失败 · 重试」才是这时该说的话
                        if (shown.isEmpty() && !history.failed) item(key = "__empty") { EmptyState(isAssistant, title) }
                        items(shown.size, key = { shown[it].id }) { idx ->
                            val b = shown[idx]
                            val align = when (b) {
                                is ChatBlock.User, is ChatBlock.Image -> Alignment.CenterEnd
                                is ChatBlock.Note, is ChatBlock.Error -> Alignment.Center
                                else -> Alignment.CenterStart
                            }
                            Box(Modifier.fillMaxWidth(), contentAlignment = align) {
                                BlockRow(
                                    b, conv, isAssistant, title, contentW, flashBlockId == b.id,
                                    myMenu = ::myMenu, botMenu = ::botMenu, cardMenu = ::cardMenu, imageMenu = ::imageMenu,
                                    onJump = { jumpTo = it }, onOpenImage = ::openImage, onDropImages = ::dropImages,
                                    onViewSingle = { url, name -> viewer = ViewerGroup(listOf(url to name), 0) },
                                    onTakeover = { l ->
                                        val d = ChatStore.deviceForRun(l.runId)
                                        if (d == null) router.showToast(ctx.getString(R.string.remote_takeover_noDevice))
                                        else RemoteLauncher.launch(d.device_id, "control", source = "locate", runId = l.runId, askId = l.askId)
                                    },
                                )
                            }
                        }
                    }
                    if (slashOn) {
                        // 「/」面板：点面板外空白 = 清空草稿关面板（挡触层盖住消息区）
                        Box(Modifier.fillMaxSize().clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { ChatStore.setDraft("") })
                        SlashPanel(
                            query = ChatStore.slashQuery(),
                            onPick = { a ->
                                ChatStore.chipAction.value = a
                                ChatStore.setDraft("")
                                runCatching { inputFocus.requestFocus() } // 选完直接打参数
                            },
                            onSendPlain = { ChatStore.slashDismissed.value = true },
                            modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp).padding(bottom = 8.dp),
                        )
                    }
                }
                if (inputEnabled) {
                    Composer(
                        isAssistant, title, draft, chip, quote, ideaBanner, pending, pendingTier, pendingOrigin, voiceMode, quickVoiceActive, inputFocused,
                        speechOk, rec, inputFocus, ::quoteWho,
                        onPending = { pending = it }, onTier = { pendingTier = it }, onOrigin = { pendingOrigin = it },
                        onAdd = ::askAddImage, onFocusChanged = { inputFocused = it },
                        onVoiceMode = { voiceMode = it }, onQuickVoice = { quickVoiceActive = it },
                        canRecord = { if (hasMic()) true else { micPermission.launch(Manifest.permission.RECORD_AUDIO); false } },
                        zoneAt = ::zoneAt, finish = ::finishRecording, sendPending = { sendPending() },
                    )
                } else ReadOnlyBar(device?.device_id)
            }
        }
        if (rec.active) VoiceHoldOverlay(rec)
        viewer?.let { g -> ImageViewer(g) { viewer = null } }
    }
}

/** 翻页那一行的四档：可点「查看更早的消息」/ 转圈「正在取更早的消息…」/ 不可点「没有更早的消息了」（不撤）/ 可点「加载失败 · 重试」。 */
@Composable
private fun HistoryRow(h: HistoryRowState) {
    val c = UmbraTheme.colors
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxWidth().padding(vertical = UmbraDimens.sp3), contentAlignment = Alignment.Center) {
        when {
            h.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                UmbraSpinningIcon(UmbraIconPaths.spinnerArc, c.faint, 12.dp)
                Spacer(Modifier.width(6.dp))
                Text("正在取更早的消息…", color = c.faint, style = TextStyle(fontSize = 12.5.sp))
            }
            h.exhausted && !h.failed -> Text("没有更早的消息了", color = c.faint, style = TextStyle(fontSize = 12.5.sp))
            else -> {
                val label = if (h.failed) "加载失败 · 重试" else "查看更早的消息"
                // 胶囊仍 28，热区撑到 44 而不改行高
                Box(Modifier.heightIn(min = UmbraDimens.tapMin).clickable(role = Role.Button) {
                    if (h.failed) ChatStore.retryHistory() else scope.launch { ChatStore.loadOlderHistory() }
                }, contentAlignment = Alignment.Center) {
                    Text(label, color = c.muted, style = TextStyle(fontSize = 12.5.sp, fontWeight = UmbraType.weight560),
                        modifier = Modifier.height(28.dp).clip(RoundedCornerShape(50)).background(c.bg).border(UmbraDimens.borderW, c.border, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 5.dp))
                }
            }
        }
    }
}

@Composable
private fun EmptyState(isAssistant: Boolean, title: String) {
    val c = UmbraTheme.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 250.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (isAssistant) "跟秘书说点什么" else "直接吩咐「$title」做事", color = c.text, style = TextStyle(fontSize = 15.sp, fontWeight = UmbraType.weight560), textAlign = TextAlign.Center)
            Text(if (isAssistant) "描述你的目标就行，Umbra 会拆活、派活。" else "秘书会把任务派给这台设备。", color = c.muted, style = TextStyle(fontSize = 13.sp, lineHeight = 20.8.sp), textAlign = TextAlign.Center)
        }
    }
}

/** 刚跳到的那条闪一圈 orangeSoft（不闪的话滚过去了也不知道是哪一条）。 */
@Composable
private fun Modifier.halo(on: Boolean, mine: Boolean): Modifier = if (on) this.border(2.dp, UmbraTheme.colors.orangeSoft, bubbleShape(mine)) else this

@Composable
private fun SenderHeader(icon: String, name: String, tint: Color) {
    val c = UmbraTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        UmbraIcon(icon, tint, size = 12.dp)
        Spacer(Modifier.width(5.dp))
        Text(name, color = c.faint, style = TextStyle(fontSize = 11.5.sp, fontWeight = UmbraType.weight560))
    }
}

@Composable
private fun BlockRow(
    b: ChatBlock, conv: String, isAssistant: Boolean, title: String, contentW: Dp, flash: Boolean,
    myMenu: (ChatBlock.User) -> List<ChatMenuItem>,
    botMenu: (String, Long?, String) -> List<ChatMenuItem>,
    cardMenu: (String) -> List<ChatMenuItem>,
    imageMenu: (ChatBlock.Image) -> List<ChatMenuItem>,
    onJump: (Long) -> Unit,
    onOpenImage: (ChatBlock.Image, Int) -> Unit,
    onDropImages: (ChatBlock.Image) -> Unit,
    onViewSingle: (String, String) -> Unit,
    onTakeover: (ChatBlock.Locate) -> Unit,
) {
    val c = UmbraTheme.colors
    val ctx = LocalContext.current
    val notConnected = stringResource(R.string.chat_notConnected)
    fun offlineToast(sent: Boolean) { if (!sent) AppGraph.router.showToast(notConnected) }
    when (b) {
        is ChatBlock.User -> UserBubble(b, contentW, flash, myMenu(b), onJump, isAssistant, title)
        is ChatBlock.Assistant -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            // 设备会话里左侧会同时出现秘书和设备两方的话，秘书这边也要标明身份
            if (!isAssistant && (b.text.isNotEmpty() || b.streaming)) SenderHeader(UmbraIconPaths.robot, "秘书", c.orange)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (b.trace.isNotEmpty()) TraceCard(b)
                val duty = b.duty
                if (duty != null && DutyChatApi.isBrief(duty)) {
                    // 早报卡替换气泡；长按菜单与气泡同一份（复制的是那段 markdown 兜底）
                    ChatMenuBox(if (b.text.isNotEmpty()) botMenu(b.text, b.serverId, "assistant") else emptyList(), Modifier.padding(end = BUBBLE_GUTTER)) {
                        DutyBriefCard(duty, b.serverId, b.ts)
                    }
                } else if (b.vision?.showsErrorCard == true) {
                    VisionErrorCard(b.vision, conv, b.id)
                } else {
                    // 非早报的执勤消息（say 那种）：气泡上方标头、下方卡底反馈钮（iOS ChatThreadView.swift:446/469）
                    if (duty != null) com.geek.umbra.feature.duty.DutyMessageHeader(duty)
                    if (b.text.isNotEmpty() || b.streaming) AiBubble(b, contentW, flash, if (b.text.isNotEmpty()) botMenu(b.text, b.serverId, "assistant") else emptyList())
                    b.vision?.let { VisionFootnote(it, conv, b.id) }
                    if (duty != null) com.geek.umbra.feature.duty.DutyCardFooter(duty)
                }
                // 停在半截的那条：气泡下一行小字（不在正文里加「（已中断）」）
                if (b.interrupted && b.text.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                    UmbraIcon(UmbraIconPaths.stopSquare, c.faint, size = 12.dp, strokeWidth = 2f)
                    Spacer(Modifier.width(5.dp))
                    Text(stringResource(R.string.chat_interruptedNote), color = c.faint, style = TextStyle(fontSize = 11.5.sp))
                }
                KeptToolsRow(b.toolsRun)
                b.resumeTaskId?.let { ResumeAskAction(it) }
                if (b.citations.isNotEmpty() && !b.streaming) KbCitationChips(b.citations)
            }
        }
        is ChatBlock.Image -> Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(start = UmbraDimens.sp5)) {
            // 上传中不挂菜单：它自带「取消上传」
            val menu = if (b.state != ImageState.Uploading && b.state != ImageState.AwaitingReceipt) imageMenu(b) else emptyList()
            ChatMenuBox(menu, Modifier.halo(flash, true)) { ImageBubble(b) { onOpenImage(b, it) } }
            when (b.state) {
                ImageState.Uploading -> Row(Modifier.defaultMinSize(minHeight = UmbraDimens.tapMin), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.chat_uploading, umbraMB(b.sentBytes), umbraMB(b.totalBytes)), color = c.faint, style = TextStyle(fontSize = 11.5.sp))
                    FailedAction(stringResource(R.string.chat_img_cancelUpload), FontWeight.Normal, c.muted) { onDropImages(b) }
                }
                // 送出去了、在等回执：这一档不给「取消上传」
                ImageState.AwaitingReceipt -> Text(stringResource(R.string.chat_img_sending), color = c.faint, style = TextStyle(fontSize = 11.5.sp))
                ImageState.Failed -> Row(Modifier.defaultMinSize(minHeight = UmbraDimens.tapMin), verticalAlignment = Alignment.CenterVertically) {
                    UmbraIcon(UmbraIconPaths.alertOctagon, c.dangerText, size = 14.dp, strokeWidth = 2f)
                    Spacer(Modifier.width(6.dp))
                    Text(b.failReason.ifEmpty { stringResource(R.string.chat_uploadFailed) }, color = c.dangerText, style = TextStyle(fontSize = 12.5.sp), modifier = Modifier.weight(1f, fill = false))
                    // 钮跟着成因走：401 / 会话失效重发必然再失败 → 重新登录；存储满 → 去清理；其余重新发送
                    when (b.failKind) {
                        UploadError.Unauthorized, UploadError.SessionInvalid -> FailedAction("重新登录", FontWeight.SemiBold, c.danger) { AppGraph.router.jump(Route.MeHome) }
                        is UploadError.StorageFull -> FailedAction("去清理", FontWeight.SemiBold, c.danger) { AppGraph.router.go(Route.SetStorage) }
                        else -> FailedAction(stringResource(R.string.chat_menu_resend), FontWeight.SemiBold, c.danger) { ChatStore.retryImages(b.id) }
                    }
                }
                ImageState.Sent -> Unit
            }
        }
        is ChatBlock.Device -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            SenderHeader(UmbraIconPaths.monitor, title, c.faint)
            PlainLeftBubble(b.text, c.deviceBubble, contentW, flash, botMenu(b.text, null, "device"))
        }
        is ChatBlock.Task -> ChatMenuBox(cardMenu("${b.goal}（${b.pct}%，${taskStatus(b.status).label}）${b.message}")) { TaskCard(b, conv) }
        is ChatBlock.Done -> ChatMenuBox(cardMenu(ctx.getString(R.string.chat_done, b.goal))) { DoneCard(b.goal, b.results, onViewSingle) }
        is ChatBlock.Confirm -> ChatMenuBox(cardMenu(b.summary)) { ConfirmCard(b) }
        is ChatBlock.Question -> QuestionCard(b)
        is ChatBlock.Locate -> LocateCard(
            b,
            onLocate = { x, y -> offlineToast(ChatStore.handleLocate(b.askId, x, y)) },
            onFeedback = { offlineToast(ChatStore.handleLocateFeedback(b.askId, it)) },
            onPause = { offlineToast(ChatStore.handleLocatePause(b.askId)) },
            onResume = { offlineToast(ChatStore.handleResume(b.runId, b.askId)) },
            onTakeover = { onTakeover(b) },
        )
        // 系统提示行不接长按菜单：它不是消息
        is ChatBlock.Note -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                b.text, color = c.faint, style = TextStyle(fontSize = 12.sp), textAlign = TextAlign.Center,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(c.chip)
                    .then(if (flash) Modifier.border(2.dp, c.orangeSoft, RoundedCornerShape(50)) else Modifier)
                    .padding(horizontal = UmbraDimens.sp4, vertical = 6.dp),
            )
            KeptToolsRow(b.toolsRun)
            b.dutyNotice?.let { DutyNoticeAction(it) }
        }
        is ChatBlock.Error -> ErrorCard(b)
    }
}

/** 失败行里的行内文字动作：定尺 44 高（撑热区只能向留白借，不能向可点的东西借）。 */
@Composable
private fun FailedAction(title: String, weight: FontWeight, tint: Color, act: () -> Unit) {
    Box(Modifier.height(UmbraDimens.tapMin).clickable(role = Role.Button, onClick = act).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
        Text(title, color = tint, style = TextStyle(fontSize = 12.5.sp, fontWeight = weight))
    }
}

/** 我方气泡：宽度随内容，对侧留白 56；引用块进气泡顶部；发失败压到 .72，下面一行原因 + 动作。 */
@Composable
private fun UserBubble(u: ChatBlock.User, contentW: Dp, flash: Boolean, menu: List<ChatMenuItem>, onJump: (Long) -> Unit, isAssistant: Boolean, title: String) {
    val c = UmbraTheme.colors
    val ctx = LocalContext.current
    val shape = bubbleShape(true)
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(start = BUBBLE_GUTTER)) {
        ChatMenuBox(menu, Modifier.widthIn(max = contentW - BUBBLE_GUTTER).alpha(if (u.failed) 0.72f else 1f).clip(shape).background(c.userBubble).halo(flash, true)) {
            Column(Modifier.padding(horizontal = 13.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                u.quote?.let { q -> QuotedBlock(q, isAssistant, title, onJump) }
                Text(u.text, color = c.text, style = TextStyle(fontSize = 15.5.sp, lineHeight = 23.25.sp))
            }
        }
        if (u.failed) Row(Modifier.defaultMinSize(minHeight = UmbraDimens.tapMin), verticalAlignment = Alignment.CenterVertically) {
            // 原因照实分档，动作跟着分档：没连上给「重新发送 + 检查服务端」，服务端拒了只给重新发送
            UmbraIcon(UmbraIconPaths.alertOctagon, c.dangerText, size = 14.dp, strokeWidth = 2f)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(if (u.failure == SendFailure.Rejected) R.string.chat_sendFailed_rejected else R.string.chat_sendFailed_offline), color = c.dangerText, style = TextStyle(fontSize = 12.5.sp))
            FailedAction(ctx.getString(R.string.chat_menu_resend), FontWeight.SemiBold, c.danger) { ChatStore.resendFailed(u.id) }
            if (u.failure == SendFailure.Offline) FailedAction(ctx.getString(R.string.chat_menu_checkServer), FontWeight.Normal, c.muted) { AppGraph.router.jump(Route.SetConn) }
        }
    }
}

/** 气泡顶部的被引用块：chip 底 + 左 2 橙条，两行截断，点它回到原消息（没有 id 的不给点）。 */
@Composable
private fun QuotedBlock(q: ChatQuote, isAssistant: Boolean, title: String, onJump: (Long) -> Unit) {
    val c = UmbraTheme.colors
    val ctx = LocalContext.current
    val who = when (q.role) {
        "user" -> ctx.getString(R.string.chat_quoteMe)
        "device" -> if (isAssistant) ctx.getString(R.string.chat_quoteWho_device) else title
        else -> ctx.getString(R.string.chat_conv_secretary)
    }
    Row(
        Modifier.clip(RoundedCornerShape(9.dp)).background(c.chip).then(if (q.id != null) Modifier.clickable { onJump(q.id) } else Modifier)
            .height(androidx.compose.foundation.layout.IntrinsicSize.Min).padding(horizontal = 9.dp, vertical = 6.dp),
    ) {
        Box(Modifier.width(2.dp).fillMaxHeight().clip(RoundedCornerShape(1.dp)).background(c.orange))
        Spacer(Modifier.width(7.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(who, color = c.orangeText, style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold))
            Text(q.text, color = c.muted, style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.75.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}


/** 秘书气泡：三点 / Markdown 正文 / 流式光标；常显 32 停止钮（从对侧留白里扣，气泡可用宽度不变）。带表的消息让出留白。 */
@Composable
private fun AiBubble(a: ChatBlock.Assistant, contentW: Dp, flash: Boolean, menu: List<ChatMenuItem>) {
    val c = UmbraTheme.colors
    val ctx = LocalContext.current
    val gutter = if (a.streaming) maxOf(0.dp, BUBBLE_GUTTER - STOP_D - 8.dp) else BUBBLE_GUTTER
    val stopW = if (a.streaming) STOP_D + 8.dp else 0.dp
    val hasTable = remember(a.text) { ChatTableRules.hasTable(a.text) }
    val release = if (hasTable) gutter else 0.dp
    val maxBubble = contentW - gutter - stopW
    val shape = bubbleShape(false)
    Row(verticalAlignment = Alignment.Bottom) {
        ChatMenuBox(menu, Modifier.clip(shape).background(c.card).border(UmbraDimens.borderW, c.border, shape).halo(flash, false)) {
            Row(Modifier.padding(horizontal = 13.dp, vertical = 10.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                // 占位（一个字都还没流出来）：三点；有字后末尾闪光标
                if (a.thinking && a.text.isEmpty()) ThinkingDots()
                if (a.text.isNotEmpty()) ChatMarkdownText(a.text, maxBubble - 26.dp - (if (a.streaming) 9.dp else 0.dp), release)
                if (a.streaming && a.text.isNotEmpty()) BlinkCaret()
            }
        }
        if (a.streaming) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(STOP_D, UmbraDimens.tapMin).clickable(role = Role.Button) { if (!ChatStore.cancelReply()) AppGraph.router.showToast(ctx.getString(R.string.chat_notConnected)) }
                    .semantics { contentDescription = ctx.getString(R.string.chat_stopReply) },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(STOP_D).clip(CircleShape).background(c.card).border(UmbraDimens.borderW, c.border, CircleShape), contentAlignment = Alignment.Center) {
                    UmbraIcon(UmbraIconPaths.stopSquare, c.muted, size = 13.dp, strokeWidth = 2f)
                }
            }
        }
    }
}

/** 设备气泡：fill 区分说话人（秘书和设备都在左侧，不换色分不出哪句是谁说的）。 */
@Composable
private fun PlainLeftBubble(text: String, fill: Color, contentW: Dp, flash: Boolean, menu: List<ChatMenuItem>) {
    val c = UmbraTheme.colors
    val hasTable = remember(text) { ChatTableRules.hasTable(text) }
    val release = if (hasTable) BUBBLE_GUTTER else 0.dp
    val shape = bubbleShape(false)
    ChatMenuBox(menu, Modifier.clip(shape).background(fill).border(UmbraDimens.borderW, c.border, shape).halo(flash, false)) {
        Box(Modifier.padding(horizontal = 13.dp, vertical = 10.dp)) { ChatMarkdownText(text, contentW - BUBBLE_GUTTER - 26.dp, release) }
    }
}

/** 只读态的底栏：直接说明原因并给一个「去打开」的真实入口（不是把输入框置灰）。 */
@Composable
private fun ReadOnlyBar(deviceId: String?) {
    val c = UmbraTheme.colors
    Column(Modifier.fillMaxWidth().background(c.bg)) {
        Box(Modifier.fillMaxWidth().height(UmbraDimens.borderW).background(c.borderSoft))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = UmbraDimens.pagePadX, end = UmbraDimens.pagePadX, top = UmbraDimens.sp4, bottom = UmbraDimens.sp5), verticalAlignment = Alignment.CenterVertically) {
            UmbraIcon(UmbraIconPaths.lock, c.faint, size = 16.dp)
            Spacer(Modifier.width(10.dp))
            Text("这个设备会话只读。任务进度照样推进来。", color = c.muted, style = TextStyle(fontSize = 12.5.sp, lineHeight = 19.4.sp), modifier = Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.heightIn(min = UmbraDimens.tapMin).clickable(role = Role.Button) { deviceId?.let { AppGraph.router.go(Route.DeviceDetail(it)) } }, contentAlignment = Alignment.Center) {
                Text("去打开", color = c.orangeText, style = TextStyle(fontSize = 13.sp, fontWeight = UmbraType.weight560))
            }
        }
    }
}

/**
 * 输入栏：灵感来源横幅 → 引用条 → 待发图片条 →「+」/ 输入框（1–4 行）/ 右侧按钮（语音态 = 键盘；有草稿或图 = 发送；否则麦克风）。
 * 快捷语音：无焦点无内容时长按输入框 0.48 秒开录。
 */
@Composable
private fun Composer(
    isAssistant: Boolean, title: String, draft: String, chip: SlashAction?, quote: ChatQuote?, ideaBanner: String?,
    pending: List<PendingImage>, pendingTier: ImageTier?, pendingOrigin: Boolean, voiceMode: Boolean, quickVoiceActive: Boolean, inputFocused: Boolean,
    speechOk: Boolean, rec: HoldRecorder, inputFocus: FocusRequester, quoteWho: (String) -> String,
    onPending: (List<PendingImage>) -> Unit, onTier: (ImageTier?) -> Unit, onOrigin: (Boolean) -> Unit, onAdd: () -> Unit,
    onFocusChanged: (Boolean) -> Unit, onVoiceMode: (Boolean) -> Unit, onQuickVoice: (Boolean) -> Unit,
    canRecord: () -> Boolean, zoneAt: (Offset) -> HoldRecorder.Zone, finish: (HoldRecorder.Result) -> Unit, sendPending: () -> Unit,
) {
    val c = UmbraTheme.colors
    val ctx = LocalContext.current
    val focusManager = LocalFocusManager.current
    Column(Modifier.fillMaxWidth().background(c.bg)) {
        Box(Modifier.fillMaxWidth().height(UmbraDimens.borderW).background(c.borderSoft))
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = UmbraDimens.sp4, end = UmbraDimens.sp4, top = 8.dp, bottom = 10.dp)) {
            ideaBanner?.let { banner ->
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(10.dp)).background(c.orangeSoft).padding(horizontal = 11.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    UmbraIcon(UmbraIconPaths.bulb, c.orangeText, size = 14.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(banner, color = c.orangeText, style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp), modifier = Modifier.weight(1f))
                    Box(Modifier.heightIn(min = UmbraDimens.tapMin).clickable(role = Role.Button) { ChatStore.ideaBanner.value = null }.padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                        Text("知道了", color = c.orangeText, style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold))
                    }
                }
            }
            quote?.let { q -> QuoteBar(q, quoteWho) }
            if (pending.isNotEmpty()) PendingStrip(pending, onPending, pendingTier, onTier, pendingOrigin, onOrigin, onAdd)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 「+」：36 圆钮，热区 44（透明外框底对齐撑触达区）
                Box(Modifier.size(UmbraDimens.tapMin).clickable(role = Role.Button, onClick = onAdd).semantics { contentDescription = ctx.getString(R.string.chat_attach_add) }, contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(c.card).border(UmbraDimens.borderW, c.border, CircleShape), contentAlignment = Alignment.Center) {
                        UmbraIcon(UmbraIconPaths.plus, c.muted, size = 19.dp)
                    }
                }
                Box(Modifier.weight(1f).padding(bottom = 4.dp)) {
                    if (voiceMode) HoldBar(rec, speechOk, canRecord, zoneAt, finish)
                    else InputField(isAssistant, title, draft, chip, inputFocus, onFocusChanged)
                    // 快捷语音：只在「没焦点、没内容、没挂芯片」时盖一层；点一下 = 给焦点，长按 0.48s = 进语音。开录后这层必须留着（手势挂在它身上）
                    if (quickVoiceActive || (!voiceMode && !inputFocused && chip == null && draft.isBlank())) {
                        var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
                        Box(
                            Modifier.matchParentSize().onGloballyPositioned { coords = it }.pointerInput(speechOk) {
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    // 0.48 秒内抬手 = 点按（给焦点）；手指滑出被取消 = 什么都不做；按满 0.48 秒 = 长按成立
                                    var ended = false
                                    val up = withTimeoutOrNull(480) { waitForUpOrCancellation().also { ended = true } }
                                    if (ended) { if (up != null) runCatching { inputFocus.requestFocus() }; return@awaitEachGesture }
                                    if (!speechOk) { AppGraph.router.showToast(NO_SPEECH); return@awaitEachGesture }
                                    if (!canRecord()) return@awaitEachGesture
                                    onQuickVoice(true); onVoiceMode(true); rec.start()
                                    var last = down.position
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val ch = ev.changes.firstOrNull() ?: break
                                        last = ch.position
                                        coords?.let { rec.zone = zoneAt(it.localToWindow(last)) }
                                        ch.consume()
                                        if (!ch.pressed) break
                                    }
                                    onQuickVoice(false)
                                    val z = coords?.let { zoneAt(it.localToWindow(last)) } ?: HoldRecorder.Zone.Send
                                    rec.stop(z, finish)
                                }
                            },
                        )
                    }
                }
                RightButton(voiceMode, draft.isNotBlank() || pending.isNotEmpty(), speechOk) {
                    when {
                        voiceMode -> { onVoiceMode(false); runCatching { inputFocus.requestFocus() } }
                        draft.isNotBlank() || pending.isNotEmpty() -> sendPending() // 发完不收键盘 —— IM 里都是连着打下一句
                        !speechOk -> AppGraph.router.showToast(NO_SPEECH)
                        else -> { focusManager.clearFocus(); onVoiceMode(true) }
                    }
                }
            }
            // 挂芯片的瞬时态：一行说明灰字占位是什么、芯片怎么删
            if (chip != null && !voiceMode) Text("灰字是要说清的东西，敲字即替换。点芯片去掉它。", color = c.muted, style = TextStyle(fontSize = 11.5.sp), modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 7.dp))
        }
    }
}

/** 输入框上方的引用条：左 2 橙竖条 +「引用 我 / 秘书」+ 摘要两行截断 + ×（22 宽 × 44 高热区）。 */
@Composable
private fun QuoteBar(q: ChatQuote, quoteWho: (String) -> String) {
    val c = UmbraTheme.colors
    val ctx = LocalContext.current
    val shape = RoundedCornerShape(11.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 2.dp).padding(bottom = 8.dp).clip(shape).background(c.card).border(UmbraDimens.borderW, c.borderSoft, shape)
            .height(androidx.compose.foundation.layout.IntrinsicSize.Min),
    ) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(c.orange))
        Row(Modifier.weight(1f).padding(start = 9.dp, end = 11.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.chat_quoteWho, quoteWho(q.role)), color = c.orangeText, style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold))
                Text(q.text, color = c.muted, style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.75.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(9.dp))
            Box(Modifier.size(22.dp, UmbraDimens.tapMin).clickable(role = Role.Button) { ChatStore.quote.value = null }.semantics { contentDescription = ctx.getString(R.string.chat_quoteClear) }, contentAlignment = Alignment.TopCenter) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(c.chip), contentAlignment = Alignment.Center) { UmbraIcon(UmbraIconPaths.x, c.muted, size = 11.dp, strokeWidth = 2.4f) }
            }
        }
    }
}

/** 文字输入框：芯片 + 1–4 行。没有「发送」键盘动作 —— 多行输入框里回车是换行，发送统一走右边那颗钮。 */
@Composable
private fun InputField(isAssistant: Boolean, title: String, draft: String, chip: SlashAction?, focus: FocusRequester, onFocusChanged: (Boolean) -> Unit) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(18.dp)
    val placeholder = chip?.params ?: if (isAssistant) "说点什么，敲 / 用快捷动作" else "对「$title」说点什么，敲 / 用快捷动作"
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 36.dp).clip(shape).background(c.card).border(UmbraDimens.borderW, c.border, shape)
            .padding(start = UmbraDimens.sp4, end = 6.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        chip?.let { SlashChip(it) { ChatStore.chipAction.value = null }; Spacer(Modifier.width(7.dp)) }
        BasicTextField(
            value = draft, onValueChange = { ChatStore.setDraft(it) },
            textStyle = TextStyle(fontSize = 15.5.sp, color = c.text, lineHeight = 21.sp),
            cursorBrush = SolidColor(c.orange), maxLines = 4,
            modifier = Modifier.weight(1f).focusRequester(focus).onFocusChanged { onFocusChanged(it.isFocused) },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (draft.isEmpty()) Text(placeholder, color = c.faint, style = TextStyle(fontSize = 15.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            },
        )
    }
}

/** 「按住 说话」：按下开录，拖动时按落区换提示，松手按落区收尾。没麦克风权限先要权限。 */
@Composable
private fun HoldBar(rec: HoldRecorder, speechOk: Boolean, canRecord: () -> Boolean, zoneAt: (Offset) -> HoldRecorder.Zone, finish: (HoldRecorder.Result) -> Unit) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(18.dp)
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val fg = if (rec.active) c.orangeText else if (speechOk) c.muted else c.faint
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 36.dp).clip(shape).background(if (rec.active) c.orangeSoft else c.card)
            .border(UmbraDimens.borderW, if (rec.active) c.orange else c.border, shape)
            .onGloballyPositioned { coords = it }
            .pointerInput(speechOk) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!speechOk) { AppGraph.router.showToast(NO_SPEECH); return@awaitEachGesture }
                    if (!canRecord()) return@awaitEachGesture
                    rec.start()
                    var last = down.position
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull() ?: break
                        last = ch.position
                        coords?.let { rec.zone = zoneAt(it.localToWindow(last)) }
                        ch.consume()
                        if (!ch.pressed) break
                    }
                    val z = coords?.let { zoneAt(it.localToWindow(last)) } ?: HoldRecorder.Zone.Send
                    rec.stop(z, finish)
                }
            },
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        UmbraIcon(UmbraIconPaths.mic, fg, size = 16.dp)
        Spacer(Modifier.width(7.dp))
        Text(if (rec.active) "正在说话…" else if (speechOk) "按住 说话" else NO_SPEECH, color = fg, style = TextStyle(fontSize = 15.sp, fontWeight = UmbraType.weight560))
    }
}

/** 右侧按钮三态：语音态 = 键盘；有草稿或待发的图 = 发送；都没有 = 麦克风（没有系统语音识别服务时置灰）。 */
@Composable
private fun RightButton(voiceMode: Boolean, hasDraft: Boolean, speechOk: Boolean, onClick: () -> Unit) {
    val c = UmbraTheme.colors
    val icon = if (voiceMode) UmbraIconPaths.keyboard else if (hasDraft) UmbraIconPaths.send else UmbraIconPaths.mic
    val bg = if (voiceMode) c.orangeSoft else if (hasDraft) c.orange else c.chip
    val fg = if (voiceMode) c.orangeText else if (hasDraft) c.onAccent else if (speechOk) c.muted else c.faint
    val bc = if (voiceMode || hasDraft) c.orange else c.border
    val label = if (voiceMode) "打字输入" else if (hasDraft) "发送" else "语音输入"
    Box(Modifier.size(UmbraDimens.tapMin).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.BottomCenter) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(bg).border(UmbraDimens.borderW, bc, CircleShape), contentAlignment = Alignment.Center) {
            UmbraIcon(icon, fg, size = 19.dp, strokeWidth = if (hasDraft && !voiceMode) 2.4f else 1.9f)
        }
    }
}
