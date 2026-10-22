// 聊天里的图片消息：画和选（iOS: Features/Chat/UmbraImages.swift）。发和传在 ChatStore（sendImages / runUpload）。
//
// 三条稿定的规矩：①图文分条；②一条 = N 张图，不是 N 条（预览器里左右切的就是这一条里的图）；
// ③多张走固定格子（3 列 68），不按比例排。
package com.geek.umbra.feature.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.SubcomposeAsyncImage
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraFixedColors
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.navigation.UmbraSheet
import com.geek.umbra.net.ImageTier
import com.geek.umbra.net.PreparedImage
import com.geek.umbra.net.ServerSettings
import com.geek.umbra.net.UmbraImages
import com.geek.umbra.net.Uploads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

/** 图片消息的取值（iOS ChatImageMetric）。 */
object ChatImageMetric {
    val SINGLE_MAX = 220.dp
    val CELL = 68.dp
    const val COLUMNS = 3
    val GRID_PAD = 5.dp
    val GRID_GAP = 6.dp
    val PENDING = 64.dp
    /** 单张上限从服务端那个数算出来，不写死（iOS 曾经写死 10 MB，被端上拦下一张 10～32 MB 的图）。 */
    const val MAX_BYTES = Uploads.LIMIT_MB * 1024 * 1024
    const val MAX_COUNT = 9
}

/** 字节 →「1.8 MB」。不足 0.1 MB 的也照 MB 说 —— 单位一致才好比大小。 */
fun umbraMB(n: Int): String = String.format(Locale.ROOT, "%.1f MB", maxOf(0, n) / 1_048_576.0)

/** 还没发出去、攒在输入框上方那条里的一张图。超限那张**留在条里标红**，不吞掉（吞掉会以为没选中）。 */
data class PendingImage(val id: String = UUID.randomUUID().toString(), val data: ByteArray, val prep: PreparedImage? = null) {
    val bytes: Int get() = data.size
    val oversize: Boolean get() = bytes > ChatImageMetric.MAX_BYTES
    override fun equals(other: Any?) = other is PendingImage && other.id == id && other.prep === prep
    override fun hashCode() = id.hashCode()
}

/** 图档的显示名与说明（iOS ImageTier.label / hint）。 */
@Composable
fun tierLabel(t: ImageTier): String = stringResource(
    when (t) {
        ImageTier.Saver -> R.string.imgQ_tierSaver
        ImageTier.Standard -> R.string.imgQ_tierStandard
        ImageTier.Clear -> R.string.imgQ_tierClear
        ImageTier.Origin -> R.string.imgQ_tierOrigin
    },
)

fun tierLabelPlain(t: ImageTier): String = AppGraph.context.getString(
    when (t) {
        ImageTier.Saver -> R.string.imgQ_tierSaver
        ImageTier.Standard -> R.string.imgQ_tierStandard
        ImageTier.Clear -> R.string.imgQ_tierClear
        ImageTier.Origin -> R.string.imgQ_tierOrigin
    },
)

fun tierHintPlain(t: ImageTier): String? {
    val px = ChatStore.longEdge(t)
    val ctx = AppGraph.context
    return when (t) {
        ImageTier.Origin -> ctx.getString(R.string.imgQ_hintOrigin)
        ImageTier.Saver -> px?.let { ctx.getString(R.string.imgQ_hintSaver, it) }
        ImageTier.Standard -> px?.let { ctx.getString(R.string.imgQ_hintStandard, it) }
        ImageTier.Clear -> px?.let { ctx.getString(R.string.imgQ_hintClear, it) }
    }
}

/** 本地字节解成可画的图（缩略用，限 1024 边长省内存）。 */
@Composable
fun rememberLocalBitmap(data: ByteArray): ImageBitmap? {
    var bmp by remember(data) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(data) {
        bmp = withContext(Dispatchers.Default) {
            runCatching {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, o)
                var sample = 1
                while (maxOf(o.outWidth, o.outHeight) / sample > 1024) sample *= 2
                BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    return bmp
}

/** 取不到的服务端图：灰底 +「附件已删除」（不留一个空格子让人以为还在加载）。 */
@Composable
private fun AttGone() {
    val c = UmbraTheme.colors
    Box(Modifier.fillMaxSize().background(c.chip), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.common_attGone), color = c.faint, style = TextStyle(fontSize = 11.sp))
    }
}

/** 一条图片消息的气泡：单图按比例长边 220；多图 3 列固定 68 格外套一层气泡底。 */
@Composable
fun ImageBubble(b: ChatBlock.Image, onOpen: (Int) -> Unit) {
    val c = UmbraTheme.colors
    val failed = b.state == ImageState.Failed
    val border = if (failed) c.danger else c.borderSoft
    if (b.count == 1) {
        // 框自己算死：比例优先用发送时存下的 ratioHint，拿不到按 4:3 占位
        val r = b.ratioHint ?: (4f / 3f)
        val (w, h) = if (r >= 1) ChatImageMetric.SINGLE_MAX to ChatImageMetric.SINGLE_MAX / r else ChatImageMetric.SINGLE_MAX * r to ChatImageMetric.SINGLE_MAX
        val shape = bubbleShape(true)
        Box(Modifier.size(w, h).alpha(if (failed) 0.55f else 1f).clip(shape).background(c.userBubble).border(UmbraDimens.borderW, border, shape)) {
            Cell(b, 0, true, onOpen)
        }
    } else {
        val cols = minOf(ChatImageMetric.COLUMNS, b.count)
        Column(
            Modifier.alpha(if (failed) 0.55f else 1f).clip(bubbleShape(true)).background(c.userBubble).padding(ChatImageMetric.GRID_PAD),
            verticalArrangement = Arrangement.spacedBy(ChatImageMetric.GRID_GAP),
        ) {
            (0 until b.count).chunked(cols).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(ChatImageMetric.GRID_GAP)) {
                    row.forEach { i ->
                        val shape = RoundedCornerShape(8.dp)
                        Box(Modifier.size(ChatImageMetric.CELL).clip(shape).background(c.chip).border(UmbraDimens.borderW, border, shape)) { Cell(b, i, false, onOpen) }
                    }
                }
            }
        }
    }
}

/** 一格：已有 file_id 的从服务端取（正本），还没传上去的用本地数据顶着。传到一半的点不开。 */
@Composable
private fun Cell(b: ChatBlock.Image, i: Int, single: Boolean, onOpen: (Int) -> Unit) {
    val frac = uploadFrac(b, i)
    Box(Modifier.fillMaxSize().clickable(enabled = frac == null) { onOpen(i) }) {
        if (i < b.atts.size) {
            SubcomposeAsyncImage(
                model = UmbraImages.fileUrl(b.atts[i]), imageLoader = UmbraImages.loader, contentDescription = null,
                contentScale = if (single) ContentScale.Fit else ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                error = { AttGone() },
            )
        } else if (i < b.data.size) {
            rememberLocalBitmap(b.data[i])?.let {
                Image(it, null, contentScale = if (single) ContentScale.Fit else ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        if (frac != null) UploadVeil(frac)
    }
}

/** 这一格盖不盖上传罩：传完的不盖、正在传的走它自己的百分比、还没轮到的盖 0%（稿把环画在格子上）。 */
private fun uploadFrac(b: ChatBlock.Image, i: Int): Float? {
    if (b.state != ImageState.Uploading) return null
    if (i < b.atts.size) return null
    return if (i == b.atts.size) b.currentFrac else 0f
}

/** 上传罩：半透明底 + 白色**确定型**进度环 + 百分比（有真百分比就别用旋转弧）。 */
@Composable
private fun UploadVeil(frac: Float) {
    Box(Modifier.fillMaxSize().background(UmbraFixedColors.viewerBg.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val white = Color.White
            Canvas(Modifier.size(27.dp)) {
                val sw = 3.dp.toPx()
                drawCircle(white.copy(alpha = 0.26f), style = Stroke(sw))
                drawArc(white, -90f, 360f * frac.coerceIn(0.001f, 1f), false, style = Stroke(sw, cap = StrokeCap.Round))
            }
            Text("${(frac.coerceIn(0f, 1f) * 100).toInt()}%", color = white, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.SemiBold))
        }
    }
}

/**
 * 输入框上方那条：64 缩略 + 右上角 × + 末尾虚线「+」+ 右端计数「3 / 9」；结果行「3.2 MB → 480 KB · 标准 1600」+「改这一次」+「原尺寸」勾选；
 * 超限那张标红并说清第几张、多大。档位或这一批图变了就整批重算（按 id 回填，不按下标）。
 */
@Composable
fun PendingStrip(items: List<PendingImage>, onItems: (List<PendingImage>) -> Unit, tierOnce: ImageTier?, onTierOnce: (ImageTier?) -> Unit, origin: Boolean, onOrigin: (Boolean) -> Unit, onAdd: () -> Unit) {
    val c = UmbraTheme.colors
    // settingsChatTier / longEdge 读的是 ServerSettings 快照：冷启动直接分享图进来时设置还没拉到、或经 socket 改了档位，
    // 不订阅的话这一行一直停在「只有大小」、也不会按档位重算。订阅并经由它们读。
    val settings by ServerSettings.settings.collectAsState()
    val tiers by ServerSettings.imageTiers.collectAsState()
    val eff: ImageTier? = if (origin) ImageTier.Origin else tierOnce ?: settings.let { ChatStore.settingsChatTier() }
    val key = (eff?.wire ?: "-") + "|" + items.joinToString(",") { it.id }
    val latest = rememberUpdated(items)
    LaunchedEffect(key) {
        // 逐张缩、算好一张换一张（并发九张会把内存顶起来，而这一趟只是为了填一行字）
        var cur = latest.value.map { it.copy(prep = null) }
        onItems(cur)
        if (eff == null) return@LaunchedEffect
        for (it in cur.toList()) {
            val p = ChatStore.prepareChatImage(it.data, eff)
            cur = latest.value.map { x -> if (x.id == it.id) x.copy(prep = p) else x }
            onItems(cur)
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEach { p -> Thumb(p) { onItems(items.filter { it.id != p.id }) } }
                val full = items.size >= ChatImageMetric.MAX_COUNT
                val dash = c.border
                Box(
                    Modifier.size(ChatImageMetric.PENDING).clickable(role = Role.Button, onClick = onAdd)
                        .semantics { contentDescription = AppGraph.context.getString(R.string.chat_attach_add) },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawRoundRect(dash, cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()),
                            style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
                    }
                    UmbraIcon(UmbraIconPaths.plus, if (full) c.faint else c.muted, size = 18.dp)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text("${items.size} / ${ChatImageMetric.MAX_COUNT}", color = c.faint, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tiers.let { entryText(items, eff) }, color = c.muted, style = TextStyle(fontSize = 11.sp), maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            // 勾上「原尺寸」时「改这一次」隐掉 —— 两个入口同时在会让人以为各管一半
            if (!origin) Text(
                stringResource(R.string.chat_imgTierOnce), color = c.orangeText,
                style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline),
                modifier = Modifier.clickable(role = Role.Button) { pickTierOnce(eff) { t -> onTierOnce(t); onOrigin(t == ImageTier.Origin) } },
            )
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.heightIn(min = UmbraDimens.tapMin).clickable(role = Role.Checkbox) { onOrigin(!origin) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val shape = RoundedCornerShape(4.dp)
                Box(Modifier.size(14.dp).clip(shape).background(if (origin) c.orange else Color.Transparent).border(UmbraDimens.borderW, if (origin) c.orange else c.border, shape), contentAlignment = Alignment.Center) {
                    if (origin) UmbraIcon(UmbraIconPaths.check, c.onAccent, size = 10.dp, strokeWidth = 2.6f)
                }
                Spacer(Modifier.width(5.dp))
                Text(stringResource(R.string.imgQ_tierOrigin), color = if (origin) c.orangeText else c.muted, style = TextStyle(fontSize = 11.sp))
            }
        }
        items.indexOfFirst { it.oversize }.takeIf { it >= 0 }?.let { bad ->
            Row(verticalAlignment = Alignment.Top) {
                UmbraIcon(UmbraIconPaths.alertTriangle, c.dangerText, Modifier.padding(top = 2.dp), size = 13.dp)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.chat_img_oversize, bad + 1, umbraMB(items[bad].bytes), umbraMB(ChatImageMetric.MAX_BYTES)), color = c.dangerText, style = TextStyle(fontSize = 12.sp, lineHeight = 18.6.sp))
            }
        }
    }
}

@Composable
private fun <T> rememberUpdated(v: T): androidx.compose.runtime.State<T> {
    val s = remember { mutableStateOf(v) }
    s.value = v
    return s
}

/** 那一行的文字：还没算完写「3.2 MB → 正在算」（不空着、不编数、也不去掉箭头 —— 数落下来时不重排）。 */
@Composable
private fun entryText(items: List<PendingImage>, tier: ImageTier?): String {
    val before = items.sumOf { it.bytes }
    if (tier == null) return umbraMB(before)
    if (!items.all { it.prep != null }) return "${umbraMB(before)} → ${stringResource(R.string.chat_imgSizing)}"
    val after = items.sumOf { it.prep?.after ?: it.bytes }
    val px = ChatStore.longEdge(tier)
    val tail = if (tier == ImageTier.Origin || px == null) tierLabel(tier) else "${tierLabel(tier)} $px"
    return "${umbraMB(before)} → ${umbraMB(after)} · $tail"
}

/** 「改这一次」：弹出四档，选完只改这一条消息，不动设置。 */
private fun pickTierOnce(cur: ImageTier?, onPick: (ImageTier) -> Unit) {
    AppGraph.router.present(UmbraSheet("chat-tier-once") { dismiss ->
        val c = UmbraTheme.colors
        Column(Modifier.fillMaxWidth().padding(horizontal = UmbraDimens.pagePadX).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.chat_imgTierOnce), color = c.text, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.chat_imgTierOnceNote), color = c.muted, style = TextStyle(fontSize = 13.sp))
            Spacer(Modifier.height(12.dp))
            ImageTier.entries.forEach { t ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = UmbraDimens.rowMinHSub).clickable { onPick(t); dismiss() }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tierLabel(t), color = c.text, style = TextStyle(fontSize = 16.sp))
                        tierHintPlain(t)?.let { Text(it, color = c.muted, style = TextStyle(fontSize = 13.sp)) }
                    }
                    if (t == cur) UmbraIcon(UmbraIconPaths.check, c.orange, size = 18.dp)
                }
            }
        }
    })
}

@Composable
private fun Thumb(p: PendingImage, onRemove: () -> Unit) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Box(Modifier.size(ChatImageMetric.PENDING)) {
        Box(Modifier.fillMaxSize().clip(shape).background(c.chip).border(UmbraDimens.borderW, if (p.oversize) c.danger else c.borderSoft, shape)) {
            rememberLocalBitmap(p.data)?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        // 贴角 44 热区的「摘掉这张」（视觉 20 的圆，热区靠外框撑）
        Box(
            Modifier.align(Alignment.TopEnd).size(UmbraDimens.tapMin).clickable(role = Role.Button, onClick = onRemove)
                .semantics { contentDescription = AppGraph.context.getString(R.string.chat_img_remove) },
            contentAlignment = Alignment.TopEnd,
        ) {
            Box(Modifier.padding(4.dp).size(20.dp).clip(CircleShape).background(UmbraTheme.colors.toastGlass), contentAlignment = Alignment.Center) {
                UmbraIcon(UmbraIconPaths.x, UmbraFixedColors.onNavText, size = 11.dp, strokeWidth = 2.4f)
            }
        }
    }
}

/** 应用内图片预览器：一组图左右切，双指缩放。iOS 用 fullScreenCover，这里用全屏 Dialog。 */
data class ViewerGroup(val items: List<Pair<String, String>>, val start: Int)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageViewer(group: ViewerGroup, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(initialPage = group.start) { group.items.size }
        Box(Modifier.fillMaxSize().background(UmbraFixedColors.viewerBg)) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                var scale by remember { mutableFloatStateOf(1f) }
                var offset by remember { mutableStateOf(Offset.Zero) }
                SubcomposeAsyncImage(
                    model = group.items[page].first, imageLoader = UmbraImages.loader, contentDescription = group.items[page].second,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 4f)
                                offset = if (scale <= 1f) Offset.Zero else offset + pan
                            }
                        }
                        .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
                    error = { AttGone() },
                )
            }
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(UmbraDimens.tapMin).clickable(role = Role.Button, onClick = onClose).semantics { contentDescription = AppGraph.context.getString(R.string.common_close) }, contentAlignment = Alignment.Center) {
                    UmbraIcon(UmbraIconPaths.x, UmbraFixedColors.onNavText, size = 20.dp)
                }
                Spacer(Modifier.weight(1f))
                // 一组里只有一项时翻页条不出
                if (group.items.size > 1) Text("${pager.currentPage + 1} / ${group.items.size}", color = UmbraFixedColors.onNavText, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp))
                Spacer(Modifier.width(12.dp))
            }
        }
    }
}
