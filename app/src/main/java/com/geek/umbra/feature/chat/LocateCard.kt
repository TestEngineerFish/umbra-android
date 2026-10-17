// 电脑操作的「人工求助」卡（iOS: Features/Chat/LocateCard.swift）：显示 operate 当前截图，用户可以
// ①在截图上拖箭头指位 ②文字纠偏 ③暂停我来（之后「继续」）④实时接管（远程控制）。箭头尖端换算成 0–1000 回传。
//
// 交互照搬 iOS：缩放 1–4 倍（双指捏合 + 右上角 +/-）、「画箭头 / 拖动画面」开关、按住箭杆整体平移（手指不挡尖端）。
// iOS 这一块没有设计稿、用的是系统色；这里换成 Umbra token（橙 / danger），形制不变。
package com.geek.umbra.feature.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geek.umbra.R
import com.geek.umbra.designsystem.UmbraButton
import com.geek.umbra.designsystem.UmbraButtonKind
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraFixedColors
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** 求助卡右上角那几颗图标（Lucide 24 描边形，iOS 用的是 SF Symbol，图标表里没有）。 */
private object LocateIcons {
    const val arrowUpLeft = "M17,17L7,7M7,17L7,7L17,7"
    const val zoomIn = "M11,4C14.87,4 18,7.13 18,11C18,14.87 14.87,18 11,18C7.13,18 4,14.87 4,11C4,7.13 7.13,4 11,4ZM20,20L16,16M11,8L11,14M8,11L14,11"
    const val zoomOut = "M11,4C14.87,4 18,7.13 18,11C18,14.87 14.87,18 11,18C7.13,18 4,14.87 4,11C4,7.13 7.13,4 11,4ZM20,20L16,16M8,11L14,11"
}

@Composable
fun LocateCard(
    data: ChatBlock.Locate,
    onLocate: (Int, Int) -> Unit,
    onFeedback: (String) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onTakeover: (() -> Unit)?,
) {
    val c = UmbraTheme.colors
    var image by remember(data.imageUrl) { mutableStateOf<ImageBitmap?>(null) }
    var loadFailed by remember(data.imageUrl) { mutableStateOf(false) }
    var tipNorm by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(data.imageUrl) {
        val bytes = ChatImages.download(ChatImages.full(data.imageUrl))
        val bmp = bytes?.let { withContext(Dispatchers.Default) { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() } }
        if (bmp != null) image = bmp.asImageBitmap() else loadFailed = true
    }
    val shape = RoundedCornerShape(12.dp)
    val screenW = LocalConfiguration.current.screenWidthDp
    Column(
        Modifier.widthIn(max = (screenW * 0.92f).dp).clip(shape).background(c.orangeSoft).border(UmbraDimens.borderW, c.orange, shape)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UmbraIcon(UmbraIconPaths.helpCircle, c.orangeText, size = 15.dp)
            Spacer(Modifier.width(7.dp))
            Text(stringResource(R.string.operate_locate_title), color = c.orangeText, style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold))
        }
        Text(data.hint, color = c.text, style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp))
        val resolved = data.resolved
        val img = image
        when {
            resolved != null -> Resolved(resolved, onResume)
            img != null -> {
                Text(stringResource(R.string.operate_locate_arrowHint), color = c.muted, style = TextStyle(fontSize = 11.sp))
                ImageWithArrow(img) { tipNorm = it }
                UmbraButton(stringResource(R.string.operate_locate_confirm), { tipNorm?.let { onLocate(it.x.toInt(), it.y.toInt()) } }, Modifier.fillMaxWidth(), enabled = tipNorm != null, height = 44.dp)
                ActionRow(onFeedback, onPause, onTakeover)
            }
            loadFailed -> {
                Text(stringResource(R.string.operate_locate_loadFailed), color = c.dangerText, style = TextStyle(fontSize = 12.sp))
                ActionRow(onFeedback, onPause, onTakeover) // 加载失败也允许纠偏 / 暂停
            }
            else -> Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.orange, modifier = Modifier.size(22.dp), strokeWidth = 2.dp) }
        }
    }
}

@Composable
private fun Resolved(s: LocateStatus, onResume: () -> Unit) {
    val c = UmbraTheme.colors
    val st = TextStyle(fontSize = 12.sp, color = c.muted)
    when (s) {
        LocateStatus.Located -> Text(stringResource(R.string.operate_locate_done), style = st)
        LocateStatus.FeedbackSent -> Text(stringResource(R.string.operate_locate_feedbackSent), style = st)
        LocateStatus.Resumed -> Text(stringResource(R.string.operate_locate_resumed), style = st)
        LocateStatus.Paused -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.operate_locate_paused), style = st)
            UmbraButton(stringResource(R.string.operate_locate_resume), onResume, Modifier.fillMaxWidth(), height = 44.dp)
        }
    }
}

/** 文字纠偏输入 + 暂停 + 实时接管（「暂停我来」是去电脑跟前自己弄；「实时接管」是就在手机上远程弄）。 */
@Composable
private fun ActionRow(onFeedback: (String) -> Unit, onPause: () -> Unit, onTakeover: (() -> Unit)?) {
    val c = UmbraTheme.colors
    var text by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val shape = RoundedCornerShape(UmbraDimens.radiusControl)
            BasicTextField(
                value = text, onValueChange = { text = it }, maxLines = 3,
                textStyle = TextStyle(fontSize = 12.5.sp, color = c.text), cursorBrush = SolidColor(c.orange),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth().clip(shape).background(c.card).border(UmbraDimens.borderW, c.border, shape).padding(horizontal = 10.dp, vertical = 9.dp)) {
                        if (text.isEmpty()) Text(stringResource(R.string.operate_locate_feedbackPlaceholder), color = c.faint, style = TextStyle(fontSize = 12.5.sp))
                        inner()
                    }
                },
            )
            Spacer(Modifier.width(8.dp))
            UmbraButton(stringResource(R.string.operate_locate_sendFeedback), { onFeedback(text) }, kind = UmbraButtonKind.Secondary, enabled = text.isNotBlank(), height = 44.dp)
        }
        UmbraButton(stringResource(R.string.operate_locate_manual), onPause, Modifier.fillMaxWidth(), kind = UmbraButtonKind.Secondary, height = 44.dp)
        if (onTakeover != null) UmbraButton(stringResource(R.string.remote_takeover_cta), onTakeover, Modifier.fillMaxWidth(), height = 44.dp)
    }
}

private enum class DragMode { Create, Move }

/** 截图 + 箭头。坐标一律存「未缩放图内坐标」（像素 = 显示尺寸 dp→px），回传时换算成 0–1000。 */
@Composable
private fun ImageWithArrow(img: ImageBitmap, onTip: (Offset?) -> Unit) {
    val c = UmbraTheme.colors
    val aspect = img.width.toFloat() / maxOf(img.height, 1)
    var tail by remember { mutableStateOf<Offset?>(null) }
    var tip by remember { mutableStateOf<Offset?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var drawArrow by remember { mutableStateOf(true) }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(8.dp))) {
        val w = with(density) { maxWidth.toPx() }
        val h = w / aspect
        fun clamp(p: Offset) = Offset(p.x.coerceIn(0f, w), p.y.coerceIn(0f, h))
        // 屏幕坐标 → 未缩放图内坐标：p = center + (V - center - pan)/zoom
        fun unscaled(v: Offset) = Offset(w / 2 + (v.x - w / 2 - pan.x) / zoom, h / 2 + (v.y - h / 2 - pan.y) / zoom)
        fun setTip(p: Offset) { tip = p; onTip(Offset(p.x / w * 1000f, p.y / h * 1000f)) }
        fun distToSeg(p: Offset, a: Offset, b: Offset): Float {
            val dx = b.x - a.x; val dy = b.y - a.y
            val len2 = dx * dx + dy * dy
            if (len2 == 0f) return hypot(p.x - a.x, p.y - a.y)
            val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f)
            return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
        }
        Box(
            Modifier.fillMaxSize().pointerInput(drawArrow) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val start = down.position
                    var mode: DragMode? = null
                    var grab: Offset? = null; var t0: Offset? = null; var p0: Offset? = null
                    val panBase = pan
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            // 双指捏合缩放（与单指手势互不冲突）
                            zoom = (zoom * ev.calculateZoom()).coerceIn(1f, 4f)
                            if (zoom <= 1f) pan = Offset.Zero
                            ev.changes.forEach { it.consume() }
                            continue
                        }
                        val ch = pressed.first()
                        if (!drawArrow) {
                            pan = panBase + (ch.position - start)
                            ch.consume(); continue
                        }
                        if (ch.positionChange() == Offset.Zero && mode != null) continue
                        val p = unscaled(ch.position)
                        if (mode == null) {
                            val s = unscaled(start)
                            val a = tail; val b = tip
                            if (a != null && b != null && distToSeg(s, a, b) < with(density) { 26.dp.toPx() }) {
                                mode = DragMode.Move; grab = s; t0 = a; p0 = b
                            } else { mode = DragMode.Create; tail = clamp(s) }
                        }
                        if (mode == DragMode.Create) setTip(clamp(p))
                        else {
                            val g = grab!!; val d = p - g
                            tail = clamp(t0!! + d); setTip(clamp(p0!! + d))
                        }
                        ch.consume()
                    }
                }
            },
        ) {
            Box(Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y }) {
                Image(img, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                val a = tail; val b = tip
                if (a != null && b != null) {
                    val red = c.danger
                    Canvas(Modifier.fillMaxSize()) {
                        val sw = 2.5.dp.toPx()
                        drawLine(red, a, b, sw, StrokeCap.Round)
                        val ang = atan2(b.y - a.y, b.x - a.x)
                        val head = 14.dp.toPx(); val spread = (Math.PI / 7).toFloat()
                        drawLine(red, b, Offset(b.x - head * cos(ang - spread), b.y - head * sin(ang - spread)), sw, StrokeCap.Round)
                        drawLine(red, b, Offset(b.x - head * cos(ang + spread), b.y - head * sin(ang + spread)), sw, StrokeCap.Round)
                        drawCircle(red, 8.dp.toPx(), b, style = Stroke(2.dp.toPx()))
                        drawCircle(red, 2.5.dp.toPx(), b)
                    }
                }
            }
        }
        // 右上角：箭头开关（默认开；关掉后拖动 = 移动画面）+ 缩放。放在手势层外面（兄弟层），点它不会被当成画箭头
        Row(Modifier.align(Alignment.TopEnd).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ToolBtn(LocateIcons.arrowUpLeft, "画箭头", if (drawArrow) c.orange else UmbraFixedColors.scrim.copy(alpha = 0.45f), state = if (drawArrow) "已开启" else "已关闭") { drawArrow = !drawArrow }
                ToolBtn(LocateIcons.zoomOut, "缩小", UmbraFixedColors.scrim.copy(alpha = 0.45f)) { zoom = maxOf(zoom - 0.5f, 1f); if (zoom <= 1f) pan = Offset.Zero }
                ToolBtn(LocateIcons.zoomIn, "放大", UmbraFixedColors.scrim.copy(alpha = 0.45f)) { zoom = minOf(zoom + 0.5f, 4f) }
        }
    }
}

@Composable
private fun ToolBtn(icon: String, label: String, bg: androidx.compose.ui.graphics.Color, state: String? = null, onClick: () -> Unit) {
    Box(
        Modifier.size(26.dp).clip(CircleShape).background(bg).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label; if (state != null) stateDescription = state },
        contentAlignment = Alignment.Center,
    ) { UmbraIcon(icon, UmbraFixedColors.onNavText, size = 14.dp, strokeWidth = 2.2f) }
}
