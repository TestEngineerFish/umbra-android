// 远程会话页（控制端，需求 §6.4 / §11.2 / §14.7）—— 照搬 iOS `Features/Remote/RemoteSessionView.swift`：
// 全屏画面 + 手势层 + 可自动隐藏的两行浮起工具条（横屏拆成左列工具 + 右上状态）+ 修饰键栏 + 接管条 + 快照浮条
// + 等待页 / 覆盖卡（连接中 / 重连中 / 已结束 / 没连上）+ 「对方当前窗口受保护」占位 + 无人值守整页（生物识别 → PIN → 核对短码）。
// 形制照设计侧批次 262 iOS 稿 02–05（tokens remoteControl.iosSession / overlays），数值收在 designsystem/UmbraRemoteTokens.kt。
//
// ⚠️ 这一屏**不用 `UmbraScreen`**（工程约定「页面骨架」那条的例外，理由同 iOS）：
//   · 它不是导航栈里的一页，是外壳上的全屏层 —— 画面要铺满到状态栏和导航条底下；
//   · `UmbraScreen` 的根是可滚动列表，自带下拉刷新 / 滚动 —— 这一屏上每一下点、每一下拖都是发给另一台电脑的鼠标动作；
//   · 修饰键栏画在页面内容里、贴着键盘上沿（`WindowInsets.safeDrawing` 含 IME）。
//
// 手势（RC-4.5）由一个自写的 pointerInput 状态机接（[remoteGestures]）：Compose 自带的 detectTapGestures /
// detectTransformGestures 表达不了「双指单击」，也分不开「捏合」和「双指拖」、「长按后拖」和「单指拖」。
// 所有换算都在 `RemoteRules.kt`；这里只把手指位置换成 dp 交给它（阈值是按 iOS pt 定的，dp 与 pt 同尺度）。
@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.geek.umbra.feature.remote

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import com.geek.umbra.R
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraFixedColors
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraRemoteColors
import com.geek.umbra.designsystem.UmbraRemoteDimens
import com.geek.umbra.designsystem.UmbraRemoteType
import com.geek.umbra.designsystem.UmbraTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

// MARK: - 输入状态

/** 手势的几个阶段（对应 UIGestureRecognizer.State 里用到的四种）。 */
internal enum class GestureStage { Began, Changed, Ended, Cancelled }

/**
 * 画面的本地缩放 / 平移、触控板光标、修饰键 —— 手势层和画面层共用的那一份（iOS: RemoteSessionView.swift:25 `RemoteInputModel`）。
 * 坐标一律 dp。缩放 / 平移 / 光标 / 修饰键是 Compose state（画面要跟着动）；手势中间量是普通字段（60 Hz 的拖动里不触发重组）。
 */
internal class RemoteInputModel(val client: RemoteClient) {
    var zoom by mutableStateOf(1.0)
    var pan by mutableStateOf(RemotePoint(0.0, 0.0))
    /** 触控板模式（相对移动光标）。默认触摸模式（RC-4.5）。 */
    var trackpad by mutableStateOf(false)
    var cursor by mutableStateOf(RemotePoint(0.5, 0.5))
    var mods by mutableStateOf(RemoteModifiers())

    /** 手势层的尺寸（dp）。每次布局时由画面层更新。 */
    var viewSize = RemoteSize(0.0, 0.0)

    private val pad = RemoteTrackpad()
    private val throttle = RemoteMoveThrottle()
    private val longPress = RemoteLongPressDrag()
    private var pinchZoom0 = 1.0
    private var pinchPan0 = RemotePoint(0.0, 0.0)
    private var pinchCenter0 = Offset.Zero
    /** 这一次双指手势里捏合幅度够大了 = 在缩放，双指拖就不再当滚动（不然一捏画面就在那边乱滚）。 */
    private var zoomingNow = false
    private var lastPanLocation: Offset? = null
    private var lastPanTime = 0.0
    private var lastScroll = Offset.Zero
    /** 触控板模式长按后拖：拖的过程中光标跟着手指相对移动。 */
    private var padDragging = false

    val frame: RemoteSize get() = client.frameSize

    private fun rp(p: Offset) = RemotePoint(p.x.toDouble(), p.y.toDouble())

    private val now: Double get() = android.os.SystemClock.elapsedRealtime() / 1000.0

    /** 一个触点对应的帧坐标：触摸模式按手指的位置，触控板模式一律是虚拟光标。 */
    private fun target(p: Offset): RemotePoint? =
        if (trackpad) cursor else RemoteGeometry.toFrame(rp(p), viewSize, frame, zoom, pan)

    fun tap(p: Offset, count: Int) {
        val t = target(p) ?: return
        client.send(RemoteGestureMap.tap(t, count))
    }

    fun secondaryTap(p: Offset) {
        val t = target(p) ?: return
        client.send(RemoteGestureMap.secondaryTap(t))
    }

    /** 长按：成立 → 记点；挪 → 拖拽；松 → 右键或抬起。触控板模式下拖的是光标（相对移动），按下点在光标处。 */
    fun longPress(stage: GestureStage, p: Offset) {
        when (stage) {
            GestureStage.Began -> {
                val t = target(p) ?: return
                longPress.began(t, rp(p))
                lastPanLocation = p
                lastPanTime = now
                padDragging = trackpad
            }
            GestureStage.Changed -> {
                val last = lastPanLocation
                if (padDragging && last != null) {
                    movePad((p.x - last.x).toDouble(), (p.y - last.y).toDouble())
                    lastPanLocation = p
                }
                val t = target(p) ?: return
                val out = longPress.moved(t, rp(p))
                val moves = out.count { it is RemoteInput.Move }
                if (out.size > moves || throttle.allow(now)) client.send(out)
            }
            GestureStage.Ended -> {
                client.send(longPress.ended(target(p)))
                lastPanLocation = null
            }
            GestureStage.Cancelled -> {
                client.send(longPress.cancelled())
                lastPanLocation = null
            }
        }
    }

    /** 单指拖（没长按）：触控板模式挪光标并发 move；触摸模式在放大后平移本地画面（不发给被控端）。 */
    fun onePan(stage: GestureStage, p: Offset) {
        when (stage) {
            GestureStage.Began -> { lastPanLocation = p; lastPanTime = now }
            GestureStage.Changed -> {
                val last = lastPanLocation ?: return
                val dx = (p.x - last.x).toDouble(); val dy = (p.y - last.y).toDouble()
                lastPanLocation = p
                if (trackpad) {
                    movePad(dx, dy)
                    if (throttle.allow(now)) client.send(listOf(RemoteInput.Move(cursor)))
                } else if (zoom > 1) {
                    pan = RemoteGeometry.clampPan(RemotePoint(pan.x + dx, pan.y + dy), viewSize, frame, zoom)
                }
            }
            GestureStage.Ended, GestureStage.Cancelled -> {
                if (trackpad && throttle.allow(now, force = true)) client.send(listOf(RemoteInput.Move(cursor)))
                lastPanLocation = null
            }
        }
    }

    private fun movePad(dx: Double, dy: Double) {
        val t = now
        val dt = max(t - lastPanTime, 1.0 / 240)
        lastPanTime = t
        val fit = RemoteGeometry.fitRect(viewSize, frame).size
        pad.cursor = cursor
        pad.move(dx, dy, dt, RemoteSize(fit.w * zoom, fit.h * zoom))
        cursor = pad.cursor
        followCursor()
    }

    /** 触控板模式放大后，光标走出可见区域就把画面跟过去（不然光标挪到屏幕外人就看不见了）。 */
    private fun followCursor() {
        if (zoom <= 1) return
        val v = RemoteGeometry.toView(cursor, viewSize, frame, zoom, pan)
        val margin = 40.0
        var x = pan.x; var y = pan.y
        if (v.x < margin) x += margin - v.x
        if (v.x > viewSize.w - margin) x -= v.x - (viewSize.w - margin)
        if (v.y < margin) y += margin - v.y
        if (v.y > viewSize.h - margin) y -= v.y - (viewSize.h - margin)
        pan = RemoteGeometry.clampPan(RemotePoint(x, y), viewSize, frame, zoom)
    }

    /** 双指拖 = 滚轮。正在捏合缩放的那一次不滚。translation 是两指中心相对起点的位移。 */
    fun twoPan(stage: GestureStage, translation: Offset) {
        when (stage) {
            GestureStage.Began -> lastScroll = Offset.Zero
            GestureStage.Changed -> {
                if (zoomingNow) { lastScroll = translation; return }
                val dx = (translation.x - lastScroll.x).toDouble(); val dy = (translation.y - lastScroll.y).toDouble()
                val k = RemoteGeometry.framePixelsPerPoint(viewSize, frame, zoom)
                val w = RemoteGestureMap.wheel(dx, dy, k)
                if (w != null && throttle.allow(now)) {
                    client.send(listOf(w))
                    lastScroll = translation
                }
            }
            else -> lastScroll = Offset.Zero
        }
    }

    /** 捏合 = 只缩放控制端的画面（RC-4.5），以两指中心为锚点；两指一起挪 = 顺手平移。 */
    fun pinch(stage: GestureStage, scale: Double, center: Offset) {
        when (stage) {
            GestureStage.Began -> {
                pinchZoom0 = zoom; pinchPan0 = pan; pinchCenter0 = center; zoomingNow = false
            }
            GestureStage.Changed -> {
                if (abs(scale - 1) > 0.08) zoomingNow = true
                if (!zoomingNow) return
                val z1 = RemoteGeometry.clampZoom(pinchZoom0 * scale)
                val p = RemoteGeometry.panAfterZooming(pinchPan0, pinchZoom0, z1, rp(pinchCenter0), viewSize, frame)
                val moved = RemotePoint(p.x + (center.x - pinchCenter0.x), p.y + (center.y - pinchCenter0.y))
                zoom = z1
                pan = RemoteGeometry.clampPan(moved, viewSize, frame, z1)
            }
            else -> zoomingNow = false
        }
    }

    fun resetZoom() {
        zoom = 1.0
        pan = RemotePoint(0.0, 0.0)
    }

    // MARK: 键盘

    fun tapModifier(m: String) {
        mods = mods.copy().also { it.tap(m) }
    }

    /** 修饰键栏上的非字符键（Esc / Tab / 方向 / Del）。 */
    fun special(name: String) {
        val code = RemoteKeys.special[name] ?: return
        client.send(RemoteKeys.press(code, mods.active))
        mods = mods.copy().also { it.consumeOnce() }
    }

    /** 系统键盘吐出来的一段字。 */
    fun typed(s: String) {
        client.send(RemoteKeys.inputs(s, mods.active))
        mods = mods.copy().also { it.consumeOnce() }
    }
}

// MARK: - 手势层

/**
 * 单击 / 双击 / 双指单击 / 长按（拖）/ 单指拖 / 双指拖 / 捏合 —— 一个 awaitEachGesture 状态机（iOS 是七个 UIGestureRecognizer）。
 * 双击要等单击超时才判（同 iOS `require(toFail:)`），代价是单击晚 ~0.3 s 才发 —— 不等的话每次双击都先多发一次单击。
 * 坐标进模型前换成 dp。
 */
private fun Modifier.remoteGestures(
    m: RemoteInputModel,
    scope: CoroutineScope,
    onTouch: () -> Unit,
    onLongPress: () -> Unit,
): Modifier = pointerInput(m) {
    val longPressMs = 450L
    val doubleTapMs = viewConfiguration.doubleTapTimeoutMillis
    val slop = viewConfiguration.touchSlop
    var pendingTap: Job? = null
    var lastTapUp = 0L
    var lastTapPos = Offset.Zero
    fun Offset.dp() = Offset(x / density, y / density)

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val downTime = down.uptimeMillis
        val start = down.position
        var last = start
        // 0 未定 / 1 长按 / 2 单指拖 / 3 双指
        var mode = 0
        var c0 = Offset.Zero
        var d0 = 1f
        var twoMoved = false
        try {
            while (true) {
                val event = if (mode == 0) {
                    val left = longPressMs - (android.os.SystemClock.uptimeMillis() - downTime)
                    withTimeoutOrNull(left.coerceAtLeast(1)) { awaitPointerEvent() }
                } else awaitPointerEvent()
                if (event == null) {
                    // 按住 0.45 s 没动：长按成立。
                    mode = 1
                    onTouch(); onLongPress()
                    m.longPress(GestureStage.Began, start.dp())
                    continue
                }
                val pressed = event.changes.filter { it.pressed }
                if (pressed.size >= 2 && mode != 1) {
                    val a = pressed[0].position; val b = pressed[1].position
                    val center = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)
                    val dist = (a - b).getDistance().coerceAtLeast(1f)
                    if (mode != 3) {
                        if (mode == 2) m.onePan(GestureStage.Ended, last.dp())
                        mode = 3
                        c0 = center; d0 = dist; twoMoved = false
                        m.pinch(GestureStage.Began, 1.0, c0.dp())
                        m.twoPan(GestureStage.Began, Offset.Zero)
                    } else {
                        val scale = (dist / d0).toDouble()
                        if ((center - c0).getDistance() > slop || abs(scale - 1) > 0.08) twoMoved = true
                        m.pinch(GestureStage.Changed, scale, center.dp())
                        m.twoPan(GestureStage.Changed, (center - c0).dp())
                    }
                    event.changes.forEach { it.consume() }
                    continue
                }
                if (pressed.isEmpty()) {
                    val upPos = event.changes.firstOrNull()?.position ?: last
                    when (mode) {
                        0 -> {
                            val upTime = event.changes.firstOrNull()?.uptimeMillis ?: downTime
                            val p = start.dp()
                            if (pendingTap != null && upTime - lastTapUp < doubleTapMs * 2 && (start - lastTapPos).getDistance() < slop * 4) {
                                pendingTap?.cancel(); pendingTap = null
                                onTouch(); m.tap(p, 2)
                            } else {
                                lastTapUp = upTime; lastTapPos = start
                                pendingTap = scope.launch {
                                    delay(doubleTapMs)
                                    pendingTap = null
                                    onTouch(); m.tap(p, 1)
                                }
                            }
                        }
                        1 -> m.longPress(GestureStage.Ended, upPos.dp())
                        2 -> m.onePan(GestureStage.Ended, upPos.dp())
                        3 -> {
                            m.pinch(GestureStage.Ended, 1.0, c0.dp())
                            m.twoPan(GestureStage.Ended, Offset.Zero)
                            if (!twoMoved) { onTouch(); m.secondaryTap(c0.dp()) }
                        }
                    }
                    event.changes.forEach { it.consume() }
                    break
                }
                val ch = pressed.first()
                val pos = ch.position
                last = pos
                when (mode) {
                    0 -> if ((pos - start).getDistance() > slop) {
                        mode = 2
                        m.onePan(GestureStage.Began, start.dp())
                        m.onePan(GestureStage.Changed, pos.dp())
                    }
                    1 -> m.longPress(GestureStage.Changed, pos.dp())
                    2 -> m.onePan(GestureStage.Changed, pos.dp())
                    else -> Unit // 双指抬起一根：剩下那根不再当单指，等全抬起
                }
                ch.consume()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 手势被系统取消（来电、切后台）：拖着的要补一个抬起，不然被控端那边鼠标一直按着。
            if (mode == 1) m.longPress(GestureStage.Cancelled, last.dp())
            if (mode == 2) m.onePan(GestureStage.Cancelled, last.dp())
            throw e
        }
    }
}

// MARK: - 会话页

/**
 * 会话页（iOS: RemoteSessionView.swift:218）。形制照设计侧批次 262 iOS 稿 02–05（tokens remoteControl.iosSession / overlays）：
 * 竖屏工具条是画面上方一块浮起的两行条；横屏（手机横过来 = 可用高度矮于宽度、且矮于 500dp，同 iOS 的竖向 compact）
 * 拆成左侧一列工具 + 右上状态。会话层是外壳上的 Compose 全屏层：转屏时 Activity 重建、会话（[RemoteCenter] 单例）不断，
 * 这里只按可用宽高重新排 —— 不碰 MainActivity 的方向设置。
 */
@Composable
internal fun RemoteSessionView(client: RemoteClient) {
    val input = remember(client) { RemoteInputModel(client) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val view = LocalView.current
    val haptic = LocalHapticFeedback.current

    var chromeShown by remember { mutableStateOf(true) }
    var touchMark by remember { mutableIntStateOf(0) }
    var keyboardOn by remember { mutableStateOf(false) }
    var showFiles by remember { mutableStateOf(false) }
    var toastShown by remember { mutableStateOf<Pair<String, Long>?>(null) }

    // 会话页所在的 Activity：BiometricPrompt 要它。转屏重建后换成新的那一个。
    DisposableEffect(context) {
        (context.findActivity() as? FragmentActivity)?.let { RemoteCenter.activity = WeakReference(it) }
        onDispose { }
    }

    // 工具条收起时把系统栏也藏起来（iOS statusBarHidden + persistentSystemOverlays(.hidden)），从边缘滑一下临时出来。
    DisposableEffect(chromeShown) {
        val win = context.findActivity()?.window
        val ctl = win?.let { WindowCompat.getInsetsController(it, view) }
        if (ctl != null) {
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (chromeShown) ctl.show(WindowInsetsCompat.Type.systemBars()) else ctl.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { ctl?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    fun touched() { if (chromeShown) touchMark++ }
    fun setKeyboard(on: Boolean) { keyboardOn = on }

    // 有操作就重新计时；3 秒没碰工具条 / 画面就把工具条收起来（iOS 稿 03：「3 秒不动自动收起」；键盘开着时不收 —— 修饰键栏要用）。
    LaunchedEffect(touchMark, chromeShown, keyboardOn, client.phase) {
        if (!chromeShown || keyboardOn || client.phase != RemoteClient.Phase.Active) return@LaunchedEffect
        delay(3_000)
        chromeShown = false
    }

    // 会话里的短提示：取走、挂 2.6 秒。带一个序号 —— 同一句话连说两次也要重新计时。
    LaunchedEffect(client.toast) {
        val t = client.toast ?: return@LaunchedEffect
        toastShown = t to System.nanoTime()
        client.toast = null
    }
    LaunchedEffect(toastShown) {
        val t = toastShown ?: return@LaunchedEffect
        delay(2_600)
        if (toastShown == t) toastShown = null
    }
    // 轻提示停 8 秒：比吐司长（它带一颗动作，人要有时间读完再决定点不点），又不一直压在画面上。
    LaunchedEffect(client.hint) {
        val h = client.hint ?: return@LaunchedEffect
        delay(8_000)
        if (client.hint == h) client.hint = null
    }

    LaunchedEffect(client.mode) { if (client.mode != "control") setKeyboard(false) }

    // 系统返回 = 先收键盘，再结束会话（iOS 全屏层没有返回手势，结束只走工具条［结束］；Android 的返回键不能什么都不做）。
    BackHandler {
        if (keyboardOn) setKeyboard(false) else RemoteCenter.close()
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(UmbraRemoteColors.stage)) {
        // 横屏（iOS 稿 04）：手机横过来时可用高度明显矮于宽度。平板两向都够大，照竖屏那一套画（同 iOS iPad 走 regular）。
        val landscape = maxWidth > maxHeight && maxHeight < 500.dp
        RemoteScreen(client, input, keyboardOn, scope, onTouch = { touched() }, onLongPress = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }, onKeyboardDismissed = { keyboardOn = false })

        // 「接管条 + 工具条 + 快照浮条 + 修饰键栏」这一层守安全区与键盘（safeDrawing 含 IME）：修饰键栏因此贴在键盘上沿。
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (client.source == "locate" && client.mode == "control" && client.phase == RemoteClient.Phase.Active) TakeoverBar(client)
            val tools = sessionTools(client, input, keyboardOn, scope, onKeyboard = { setKeyboard(!keyboardOn) }, onFiles = { showFiles = true })
            if (chromeShown) {
                if (landscape) LandscapeBar(client, input, tools, onTouch = { touched() })
                else TopBar(client, input, tools, onTouch = { touched() })
            } else {
                RevealHandle { chromeShown = true; touchMark++ }
            }
            if (showsSnapshotStrip(client)) SnapshotStrip()
            Spacer(Modifier.weight(1f))
            if (input.trackpad && client.mode == "control" && !client.snapshotMode && client.phase == RemoteClient.Phase.Active && !landscape) {
                Text(
                    stringResource(R.string.remote_trackpad_hint), color = UmbraRemoteColors.fgHint, style = UmbraRemoteType.trackpadHint,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
            }
            client.hint?.let { if (client.phase == RemoteClient.Phase.Active) HintPill(client, it) { touched() } }
            toastShown?.let { ToastPill(it.first) }
            val showsModBar = client.mode == "control" && !client.snapshotMode && client.phase == RemoteClient.Phase.Active && (keyboardOn || chromeShown)
            if (showsModBar) ModifierBar(input, keyboardOn) { touched() }
        }

        Overlay(client)
        if (showsUnattended(client)) UnattendedPage(client)
    }

    if (showFiles) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showFiles = false }, sheetState = state, containerColor = UmbraTheme.colors.bg,
            shape = RoundedCornerShape(topStart = UmbraDimens.radiusSheet, topEnd = UmbraDimens.radiusSheet),
            // 把手 36×4、圆角 999、border 色（设计侧 199 答复第 6 条，同外壳那张）。
            dragHandle = {
                Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(36.dp, 4.dp).clip(RoundedCornerShape(50)).background(UmbraTheme.colors.border))
            },
        ) { RemoteFilesView(client) { showFiles = false } }
    }
}

/** 快照浮条：直连没打通降下来的才出（「看一眼」从头就是快照，不是降级）。iOS: RemoteSessionView.swift:235。 */
private fun showsSnapshotStrip(client: RemoteClient): Boolean =
    client.snapshotMode && client.launch.source != "snapshot" && client.phase == RemoteClient.Phase.Active && client.snapshotFailure == null

private fun android.content.Context.findActivity(): Activity? {
    var c: android.content.Context? = this
    while (c != null) {
        if (c is Activity) return c
        c = (c as? android.content.ContextWrapper)?.baseContext
    }
    return null
}

// MARK: 画面

@Composable
private fun RemoteScreen(
    client: RemoteClient,
    input: RemoteInputModel,
    keyboardOn: Boolean,
    scope: CoroutineScope,
    onTouch: () -> Unit,
    onLongPress: () -> Unit,
    onKeyboardDismissed: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val view = RemoteSize(maxWidth.value.toDouble(), maxHeight.value.toDouble())
        // 布局变了（转屏、键盘把可用高度压小）：记下新尺寸、把平移重新夹一次。放在 SideEffect 里 —— 组合过程中写状态会再触发一轮组合。
        androidx.compose.runtime.SideEffect {
            if (input.viewSize != view) {
                input.viewSize = view
                input.pan = RemoteGeometry.clampPan(input.pan, view, input.frame, input.zoom)
            }
        }
        val fit = RemoteGeometry.fitRect(view, client.frameSize).size
        val w = (fit.w * input.zoom).coerceAtLeast(1.0)
        val h = (fit.h * input.zoom).coerceAtLeast(1.0)
        Box(
            Modifier.requiredSize(w.dp, h.dp)
                .offset { IntOffset((input.pan.x * density).roundToInt(), (input.pan.y * density).roundToInt()) },
        ) {
            if (client.snapshotMode) {
                client.snapshotImage?.let {
                    Image(it, contentDescription = null, contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.High, modifier = Modifier.fillMaxSize())
                }
            } else {
                RemoteVideoView(client.videoTrack, active = client.protectedApp == null) { sz ->
                    if (sz.w > 0 && sz.h > 0) client.frameSize = sz
                }
            }
        }
        if (input.trackpad && client.mode == "control") {
            val cpos = RemoteGeometry.toView(input.cursor, view, client.frameSize, input.zoom, input.pan)
            Box(Modifier.fillMaxSize()) {
                UmbraIcon(
                    RemoteIcons.pointer, UmbraRemoteColors.fg, size = UmbraRemoteDimens.cursor,
                    // Lucide 箭头的尖在 (3,3)/24：挪回来，让尖正好落在光标的帧坐标上。
                    modifier = Modifier.offset((cpos.x - 3).dp, (cpos.y - 3).dp),
                )
            }
        }
        Box(Modifier.fillMaxSize().remoteGestures(input, scope, onTouch, onLongPress))
        if (keyboardOn) KeyCatcher(input, onKeyboardDismissed)
    }
}

/**
 * SurfaceViewRenderer（GL 渲染、硬解）。帧尺寸变了（换屏 / 改画质）回调给会话，触摸换算跟着变。
 * 被控端前台是黑名单应用时停渲染（推流也暂停了，留着最后一帧会让人以为那是实时画面）。
 */
@Composable
private fun RemoteVideoView(track: VideoTrack?, active: Boolean, onSize: (RemoteSize) -> Unit) {
    val holder = remember { arrayOfNulls<VideoTrack>(1) }
    val onSizeNow by androidx.compose.runtime.rememberUpdatedState(onSize)
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(RemoteCenter.egl.eglBaseContext, object : RendererCommon.RendererEvents {
                    override fun onFirstFrameRendered() {}
                    override fun onFrameResolutionChanged(w: Int, h: Int, rotation: Int) {
                        val sz = if (rotation % 180 == 0) RemoteSize(w.toDouble(), h.toDouble()) else RemoteSize(h.toDouble(), w.toDouble())
                        post { onSizeNow(sz) }
                    }
                })
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                setEnableHardwareScaler(false)
            }
        },
        update = { r ->
            if (holder[0] !== track) {
                holder[0]?.let { old -> runCatching { old.removeSink(r) } }
                track?.let { runCatching { it.addSink(r) } }
                holder[0] = track
            }
            r.visibility = if (active) android.view.View.VISIBLE else android.view.View.INVISIBLE
        },
        onRelease = { r ->
            holder[0]?.let { old -> runCatching { old.removeSink(r) } }
            holder[0] = null
            r.release()
        },
    )
}

/**
 * 一个看不见的输入框，只用来叫出系统键盘、接住打出来的字（iOS RemoteKeyCatcher）。
 *
 * 中文 / 日文输入法要「组字」：组字期间（`TextFieldValue.composition != null`）什么都不发，组完那一下整段交出去
 * （非 ASCII 走 `{t:"text"}`）。框里的字发完就清空。Compose 的输入框在空框上按退格不回调 —— 所以框里常驻一个零宽空格当哨兵，
 * 哨兵被删掉 = 人按了退格 → 发 Backspace，再把哨兵放回去。多行输入：回车进来就是 "\n"，按 Enter 发。
 */
@Composable
private fun KeyCatcher(input: RemoteInputModel, onDismissed: () -> Unit) {
    val sentinel = "​"
    var value by remember { mutableStateOf(TextFieldValue(sentinel, TextRange(1))) }
    val focus = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    val fm = LocalFocusManager.current
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        kb?.show()
    }
    DisposableEffect(Unit) { onDispose { kb?.hide(); fm.clearFocus(force = true) } }
    // 键盘被系统收起（返回键、下拉）：工具条上那颗「键盘」钮跟着复位。先见过它出来才算。
    val imeVisible = WindowInsets.isImeVisible
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeVisible) seen = true else if (seen) onDismissed()
    }
    BasicTextField(
        value = value,
        onValueChange = { v ->
            if (v.composition != null) { value = v; return@BasicTextField }
            val text = v.text
            if (!text.contains(sentinel)) {
                // 哨兵没了：退格。
                input.special("del")
                val rest = text
                if (rest.isNotEmpty()) input.typed(rest)
            } else {
                val typed = text.replace(sentinel, "")
                if (typed.isNotEmpty()) input.typed(typed)
            }
            value = TextFieldValue(sentinel, TextRange(1))
        },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
            keyboardType = KeyboardType.Text, imeAction = ImeAction.None,
        ),
        singleLine = false,
        modifier = Modifier.size(1.dp).alpha(0.01f).focusRequester(focus),
    )
}

// MARK: 工具条

/**
 * 一颗工具（iOS: RemoteSessionView.swift:425 `Tool`）：图标 + 读屏名 + 开着没有（触控板 / 键盘开着画实心橙圆底）+ 能不能点
 * + 点了做什么（菜单类给 [menu]）。
 */
private class Tool(
    val id: String,
    val icon: String,
    val label: String,
    val on: Boolean = false,
    val enabled: Boolean = true,
    val action: (() -> Unit)? = null,
    val menu: List<BarMenuItem>? = null,
)

private class BarMenuItem(val label: String, val checked: Boolean, val action: () -> Unit)

/**
 * 六颗工具：换屏 / 画质（快照里换成刷新）/ 触摸↔触控板 / 键盘 / 剪贴板 / 文件（iOS: RemoteSessionView.swift:435）。
 * 当前不可用的那颗照样占位、35% 不透明 —— 一行六颗的位置不跟着档位跳，人找得到同一颗钮。
 */
@Composable
private fun sessionTools(
    client: RemoteClient,
    input: RemoteInputModel,
    keyboardOn: Boolean,
    scope: CoroutineScope,
    onKeyboard: () -> Unit,
    onFiles: () -> Unit,
): List<Tool> {
    val live = client.channelOpen && !client.snapshotMode
    val control = client.mode == "control" && !client.snapshotMode
    val out = mutableListOf<Tool>()
    out += Tool(
        "display", UmbraIconPaths.deviceDesktop, stringResource(R.string.remote_bar_display), enabled = client.displays.size > 1,
        menu = client.displays.mapIndexed { i, d ->
            BarMenuItem(d.name.ifEmpty { stringResource(R.string.remote_display_n, i + 1) }, d.id == client.currentDisplay) {
                client.selectDisplay(d.id); input.resetZoom()
            }
        },
    )
    out += if (client.snapshotMode) {
        // 快照里没有画质可调：这一格换成「刷新画面」（每 2 秒本来就自己拉一张，急着看就点一下）。
        Tool("quality", RemoteIcons.refresh, stringResource(R.string.remote_bar_refresh), action = { scope.launch { client.snapshotOnce() } })
    } else {
        Tool("quality", RemoteIcons.gauge, stringResource(R.string.remote_bar_quality), menu = RemoteFallback.qualityLevels.map { q ->
            BarMenuItem(remoteString(RemoteCopy.qualityKey(q)), q == client.quality) { client.chooseQuality(q) }
        })
    }
    out += Tool(
        "pointer", if (input.trackpad) RemoteIcons.mouse else RemoteIcons.hand,
        stringResource(if (input.trackpad) R.string.remote_bar_trackpad else R.string.remote_bar_touch),
        on = input.trackpad, enabled = control, action = { input.trackpad = !input.trackpad },
    )
    out += Tool("keyboard", UmbraIconPaths.keyboard, stringResource(R.string.remote_bar_keyboard), on = keyboardOn, enabled = control, action = onKeyboard)
    out += Tool("clip", RemoteIcons.clipboard, stringResource(R.string.remote_bar_clipboard), enabled = live, menu = listOf(
        BarMenuItem(stringResource(R.string.remote_clip_send), false) { client.sendClipboard() },
        BarMenuItem(stringResource(R.string.remote_clip_get), false) { client.fetchClipboard() },
    ))
    out += Tool("files", UmbraIconPaths.folder, stringResource(R.string.remote_bar_files), enabled = live, action = onFiles)
    return out
}

/**
 * 竖屏工具条（iOS 稿 03；iOS: RemoteSessionView.swift:359）：画面上方一块浮起的圆角条（`#2A251F` 94%、圆角 16），两行 ——
 *   ① ［结束］· 连接状态胶囊 …… （放大时「适应屏幕」）档位胶囊 · 短码；
 *   ② 六颗工具。
 */
@Composable
private fun TopBar(client: RemoteClient, input: RemoteInputModel, tools: List<Tool>, onTouch: () -> Unit) {
    Column(
        Modifier.padding(horizontal = 8.dp).padding(top = 4.dp).fillMaxWidth()
            .clip(RoundedCornerShape(UmbraRemoteDimens.barRadius)).background(UmbraRemoteColors.takeoverBg.copy(alpha = UmbraRemoteColors.barAlpha))
            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EndPill()
            LinkPill(client, Modifier.weight(1f, fill = false))
            Spacer(Modifier.weight(1f))
            if (input.zoom > 1) FitPill(onTouch) { input.resetZoom() }
            ModeButton(client, onTouch)
            CodePill(client)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            tools.forEach { ToolButton(it, onTouch) }
        }
    }
}

/**
 * 横屏（iOS 稿 04；iOS: RemoteSessionView.swift:385）：画面占满高度，工具条拆成左侧一列六颗 + 右上（连接状态 · 档位 · 短码 · 结束）。
 * 左列每颗照 Android 热区取 48 高（iOS 是 40）；矮屏 + 接管条时可能放不下，所以这一列能竖着滚。
 */
@Composable
private fun LandscapeBar(client: RemoteClient, input: RemoteInputModel, tools: List<Tool>, onTouch: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(top = 4.dp), verticalAlignment = Alignment.Top) {
        Column(
            Modifier.padding(top = 40.dp).width(UmbraRemoteDimens.sideColumnW)
                .clip(RoundedCornerShape(UmbraRemoteDimens.barRadius)).background(UmbraRemoteColors.takeoverBg.copy(alpha = UmbraRemoteColors.barAlpha))
                .verticalScroll(rememberScrollState()).padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            tools.forEach { ToolButton(it, onTouch) }
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LinkPill(client)
            if (input.zoom > 1) FitPill(onTouch) { input.resetZoom() }
            ModeButton(client, onTouch)
            CodePill(client)
            EndPill()
        }
    }
}

/** 一颗工具：34 的圆（开着实心橙）、热区 48；不可用 35% 不透明、点不动。菜单类弹锚定小弹框（平台差异答复第 5 条）。 */
@Composable
private fun ToolButton(t: Tool, onTouch: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val c = UmbraTheme.colors
    Box {
        Box(
            Modifier.size(UmbraDimens.tapMin).alpha(if (t.enabled) 1f else UmbraRemoteColors.disabledAlpha)
                .clickable(enabled = t.enabled, role = Role.Button) {
                    if (t.menu != null) open = true else { t.action?.invoke(); onTouch() }
                }
                .semantics { contentDescription = t.label },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(UmbraRemoteDimens.toolFace).clip(CircleShape).background(if (t.on) c.orange else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) { UmbraIcon(t.icon, if (t.on) c.onAccent else UmbraRemoteColors.fgTool, size = UmbraRemoteDimens.toolIcon) }
        }
        t.menu?.let { items ->
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.card, modifier = Modifier.widthIn(min = 180.dp)) {
                items.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.label, color = c.text) },
                        trailingIcon = if (item.checked) { { UmbraIcon(UmbraIconPaths.check, c.orangeText, size = 18.dp) } } else null,
                        onClick = { open = false; item.action(); onTouch() },
                    )
                }
            }
        }
    }
}

/** 胶囊的热区：视觉 32 高，上下各补到 48（同 iOS 的正负一对）。 */
private fun Modifier.pillHit(): Modifier = heightIn(min = UmbraDimens.tapMin)

/** ［结束］：白 14% 底胶囊，高 32、热区 48。 */
@Composable
private fun EndPill() {
    val a11y = stringResource(R.string.remote_bar_end)
    Box(
        Modifier.pillHit().clickable(role = Role.Button) { RemoteCenter.close() }.semantics { contentDescription = a11y },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.height(UmbraRemoteDimens.pillH).clip(RoundedCornerShape(50)).background(UmbraRemoteColors.pillStrong).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.remote_bar_endShort), color = UmbraRemoteColors.fg, style = UmbraRemoteType.endPill) }
    }
}

/** 连接状态（RC-3.4）：点 + 「直连 · 28 ms」/「中继 · 86 ms」/「快照」。点：画面在走是绿（看档色），快照是琥珀。 */
@Composable
private fun LinkPill(client: RemoteClient, modifier: Modifier = Modifier) {
    val label = when {
        client.snapshotMode -> stringResource(R.string.remote_path_snapshot)
        client.path == "relay" -> stringResource(R.string.remote_path_relay)
        client.path == "direct" -> stringResource(R.string.remote_path_direct)
        else -> stringResource(R.string.remote_path_connecting)
    }
    val parts = mutableListOf(label)
    val rtt = client.rttMs
    if (rtt != null && !client.snapshotMode) parts += "$rtt ms"
    val text = parts.joinToString(" · ")
    Row(
        modifier.height(UmbraRemoteDimens.pillH).clip(RoundedCornerShape(50)).background(UmbraRemoteColors.pillSoft).padding(horizontal = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(UmbraRemoteDimens.linkDot).clip(CircleShape).background(if (client.snapshotMode) UmbraRemoteColors.tierControl else UmbraRemoteColors.tierView))
        Spacer(Modifier.width(6.dp))
        Text(text, color = UmbraRemoteColors.fgLink, style = UmbraRemoteType.linkPill, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 安全短码（§14.4）：等宽、字距拉开，和被控端会话窗口右上那一串对得上。没算出来时不占位。 */
@Composable
private fun CodePill(client: RemoteClient) {
    val code = client.safetyCode ?: return
    val a11y = stringResource(R.string.remote_bar_codeA11y, code)
    Box(
        Modifier.height(UmbraRemoteDimens.pillH).clip(RoundedCornerShape(50)).background(UmbraRemoteColors.pillSoft).padding(horizontal = 10.dp)
            .semantics { contentDescription = a11y },
        contentAlignment = Alignment.Center,
    ) { Text(code, color = UmbraRemoteColors.fgCode, style = UmbraRemoteType.codePill) }
}

/** 「适应屏幕」：放大之后才出的一颗小胶囊（32×32，热区 48）。 */
@Composable
private fun FitPill(onTouch: () -> Unit, action: () -> Unit) {
    val a11y = stringResource(R.string.remote_bar_fit)
    Box(
        Modifier.size(UmbraDimens.tapMin).clickable(role = Role.Button) { action(); onTouch() }.semantics { contentDescription = a11y },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(UmbraRemoteDimens.pillH).clip(RoundedCornerShape(50)).background(UmbraRemoteColors.pillSoft), contentAlignment = Alignment.Center) {
            UmbraIcon(RemoteIcons.fit, UmbraRemoteColors.fg, size = 13.dp)
        }
    }
}

/**
 * 档位胶囊（iOS 稿 03：「只看」绿 /「控制」琥珀，`remoteControl.tierColor` —— 屏上这两色跨主题不变，不用橙）。
 * 点它升降档：只看 → 申请控制（被控端再同意一次）；控制 → 降为只看（不用对方同意，立即生效）。快照里不能控，不给升；
 * 升档在途时画「只看 …」、不让连点。iOS: RemoteSessionView.swift:570。
 */
@Composable
private fun ModeButton(client: RemoteClient, onTouch: () -> Unit) {
    val control = client.mode == "control"
    val canUpgrade = !client.snapshotMode && client.phase == RemoteClient.Phase.Active && !client.upgradePending
    val a11y = stringResource(if (control) R.string.remote_bar_downgrade else R.string.remote_bar_upgrade)
    val label = if (client.upgradePending) stringResource(R.string.remote_tier_view) + " …"
    else stringResource(if (control) R.string.remote_tier_control else R.string.remote_tier_view)
    Box(
        Modifier.pillHit().clickable(enabled = control || canUpgrade, role = Role.Button) {
            if (control) client.downgrade() else if (canUpgrade) client.requestUpgrade()
            onTouch()
        }.semantics { contentDescription = a11y },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.height(UmbraRemoteDimens.pillH).clip(RoundedCornerShape(50))
                .background(if (control) UmbraRemoteColors.tierControl.copy(alpha = UmbraRemoteColors.tierControlSoft) else UmbraRemoteColors.tierView.copy(alpha = UmbraRemoteColors.tierViewSoft))
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) { Text(label, color = if (control) UmbraRemoteColors.tierControlText else UmbraRemoteColors.tierViewText, style = UmbraRemoteType.tierPill) }
    }
}

/** 工具条收起后留在顶边的一颗小把手：点它把工具条叫回来（自动隐藏之后总得有条回来的路 —— 画面上的点按是发给对方的鼠标）。 */
@Composable
private fun RevealHandle(onClick: () -> Unit) {
    val a11y = stringResource(R.string.remote_bar_show)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(UmbraRemoteDimens.handleHitW, UmbraDimens.tapMin).clickable(onClick = onClick).semantics { contentDescription = a11y },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(UmbraRemoteDimens.handleW, UmbraRemoteDimens.handleH).clip(RoundedCornerShape(50)).background(UmbraRemoteColors.handle))
        }
    }
}

// MARK: 接管条

/**
 * 从求助卡「实时接管」进来（source=locate）：顶部多一条，随时交还给秘书（§11.2 第 4 条）。
 * iOS 稿 03 接管态：`#2A251F` 底 + 琥珀点 + 「你正在接管秘书的操作」+［交还给秘书］（白 14% 底胶囊，不用橙 —— 同档位胶囊的理由）。
 */
@Composable
private fun TakeoverBar(client: RemoteClient) {
    Row(
        Modifier.fillMaxWidth().background(UmbraRemoteColors.takeoverBg).padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(UmbraRemoteDimens.takeoverDot).clip(CircleShape).background(UmbraRemoteColors.tierControl))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.remote_takeover_title), color = UmbraRemoteColors.fg, style = UmbraRemoteType.takeoverTitle, maxLines = 1, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(4.dp))
        Box(Modifier.pillHit().clickable(role = Role.Button) { client.handback() }, contentAlignment = Alignment.Center) {
            Box(
                Modifier.height(UmbraRemoteDimens.pillH).clip(RoundedCornerShape(50)).background(UmbraRemoteColors.pillStrong).padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.remote_takeover_handback), color = UmbraRemoteColors.fg, style = UmbraRemoteType.takeoverButton) }
        }
    }
}

// MARK: 修饰键栏

/**
 * ⌘ ⌥ ⌃ ⇧ Esc Tab ←↑↓→ Del（RC-4.6，iOS 稿 03 修饰键栏；iOS: RemoteSessionView.swift:625）。修饰键点一下管下一个键
 * （橙 24% 底 + `#F0A878` 字）、点两下锁定（实心橙 + 底边亮线）、再点一下放开。
 * 键盘没弹时是一条浮起的圆角条（离底 28）；键盘弹起时贴键盘上沿、通栏。
 */
@Composable
private fun ModifierBar(input: RemoteInputModel, keyboardOn: Boolean, onTouch: () -> Unit) {
    val shape = RoundedCornerShape(if (keyboardOn) 0.dp else UmbraRemoteDimens.modBarRadius)
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = if (keyboardOn) 0.dp else 8.dp).padding(bottom = if (keyboardOn) 0.dp else UmbraRemoteDimens.modBarBottom)
            .clip(shape).background(UmbraRemoteColors.takeoverBg.copy(alpha = UmbraRemoteColors.barAlpha))
            .horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
    ) {
        listOf("cmd" to "⌘", "alt" to "⌥", "ctrl" to "⌃", "shift" to "⇧").forEach { (m, glyph) -> ModKey(input, m, glyph, onTouch) }
        listOf("esc" to "Esc", "tab" to "Tab", "left" to "←", "up" to "↑", "down" to "↓", "right" to "→", "del" to "Del").forEach { (k, label) ->
            Box(
                Modifier.defaultMinSize(UmbraDimens.tapMin, UmbraDimens.tapMin).clickable(role = Role.Button) { input.special(k); onTouch() },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.defaultMinSize(UmbraRemoteDimens.keyMinW, UmbraRemoteDimens.keyH).clip(RoundedCornerShape(UmbraRemoteDimens.keyRadius))
                        .background(UmbraRemoteColors.pillSoft).padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(label, color = UmbraRemoteColors.fgTool, style = if (label.length > 1) UmbraRemoteType.keyCapWord else UmbraRemoteType.keyCap) }
            }
        }
    }
}

@Composable
private fun ModKey(input: RemoteInputModel, m: String, glyph: String, onTouch: () -> Unit) {
    val c = UmbraTheme.colors
    val lv = input.mods.level(m)
    val name = remoteString(RemoteCopy.modKey(m))
    val state = stringResource(
        when (lv) {
            RemoteModifiers.Level.Off -> R.string.remote_mod_off
            RemoteModifiers.Level.Once -> R.string.remote_mod_once
            RemoteModifiers.Level.Locked -> R.string.remote_mod_locked
        },
    )
    Box(
        Modifier.size(UmbraDimens.tapMin).clickable(role = Role.Button) { input.tapModifier(m); onTouch() }
            .semantics { contentDescription = name; stateDescription = state },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.defaultMinSize(UmbraRemoteDimens.keyMinW, UmbraRemoteDimens.keyH).clip(RoundedCornerShape(UmbraRemoteDimens.keyRadius))
                .background(
                    when (lv) {
                        RemoteModifiers.Level.Locked -> c.orange
                        RemoteModifiers.Level.Once -> c.orange.copy(alpha = UmbraRemoteColors.modOnceAlpha)
                        RemoteModifiers.Level.Off -> UmbraRemoteColors.pillSoft
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                glyph,
                color = when (lv) {
                    RemoteModifiers.Level.Off -> UmbraRemoteColors.fgTool
                    RemoteModifiers.Level.Once -> UmbraFixedColors.onNavAccent
                    RemoteModifiers.Level.Locked -> c.onAccent
                },
                style = if (lv == RemoteModifiers.Level.Off) UmbraRemoteType.modKey else UmbraRemoteType.modKeyOn,
            )
            // 锁定那一档底边一道亮线（稿 inset 0 -2 白 55%）：和「只管一次」一眼分得开，不只靠深浅。
            if (lv == RemoteModifiers.Level.Locked) {
                Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 6.dp).fillMaxWidth().height(UmbraRemoteDimens.lockedLineH).background(UmbraRemoteColors.lockedLine))
            }
        }
    }
}

// MARK: 提示

/** 底部轻提示 + 「申请控制」（iOS 稿 03「只看提示」「空闲降档」两态；iOS: RemoteSessionView.swift:892）。点了走升档同意流程。 */
@Composable
private fun HintPill(client: RemoteClient, text: String, onTouch: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(UmbraRemoteColors.takeoverBg.copy(alpha = UmbraRemoteColors.barAlpha))
                .padding(start = 14.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, color = UmbraRemoteColors.fg, style = UmbraRemoteType.hint, modifier = Modifier.weight(1f, fill = false))
            if (client.mode == "view" && !client.snapshotMode) {
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.heightIn(min = UmbraDimens.tapMin).clickable(role = Role.Button) { client.requestUpgrade(); onTouch() },
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(R.string.remote_bar_upgrade), color = UmbraFixedColors.onNavAccent, style = UmbraRemoteType.hintAction) }
            }
        }
    }
}

@Composable
private fun ToastPill(text: String) {
    Box(Modifier.fillMaxWidth().padding(bottom = 10.dp, start = 24.dp, end = 24.dp), contentAlignment = Alignment.Center) {
        Text(
            text, color = UmbraRemoteColors.fg, style = UmbraRemoteType.toast, textAlign = TextAlign.Center,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(UmbraTheme.colors.toastGlass).padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

/**
 * 快照浮条（iOS 稿 03「快照」）：直连没打通、降到快照时挂在工具条下，不盖画面、点不到。「看一眼」本来就是快照，不出这条。
 * 稿上还有［再试直连］—— 先不出：协议里控制端没法让被控端重新出一次 offer（同 iOS）。
 */
@Composable
private fun SnapshotStrip() {
    val c = UmbraTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 8.dp)
            .clip(RoundedCornerShape(UmbraDimens.radiusInput)).background(c.card).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // 图标不是正文，稿上就是 --warning 实心那支。
        UmbraIcon(UmbraIconPaths.alertTriangle, c.warning, size = 15.dp, strokeWidth = 2f, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.remote_snapshot_strip), color = c.text, style = UmbraRemoteType.snapshotStrip)
    }
}

// MARK: 蒙层

/** 一颗覆盖卡上的钮：文案 + 是不是主钮 + 点了做什么。 */
private class CoverButton(val title: String, val primary: Boolean, val action: () -> Unit)

@Composable
private fun Overlay(client: RemoteClient) {
    when (client.phase) {
        RemoteClient.Phase.Requesting, RemoteClient.Phase.AwaitingConsent -> WaitingPage(client)
        RemoteClient.Phase.Connecting -> if (!client.snapshotMode || client.snapshotImage == null) {
            CoverCard(
                spin = true,
                title = stringResource(if (client.unattended && !client.localAuthPassed) R.string.remote_connecting_auth else R.string.remote_connecting),
                body = null,
                buttons = listOf(CoverButton(stringResource(R.string.remote_wait_cancel), false) { RemoteCenter.close() }),
            )
        }
        RemoteClient.Phase.Active -> {
            val why = client.snapshotFailure
            when {
                // 重连中（iOS 稿 03「重连中」）：30 秒上限是 RemoteFallback.RECONNECT_GRACE，到点由会话自己结束。
                client.reconnecting -> CoverCard(
                    spin = true, title = stringResource(R.string.remote_reconnecting), body = stringResource(R.string.remote_reconnecting_sub),
                    buttons = listOf(CoverButton(stringResource(R.string.remote_bar_endShort), false) { RemoteCenter.close() }),
                )
                // 批次 265：副行只说「画面和操作都先停了。对方切到别的窗口就回来。」—— 不点名对方前台是哪个应用。
                client.protectedApp != null -> Veil {
                    UmbraIcon(UmbraIconPaths.lock, UmbraRemoteColors.fgLock, size = 22.dp)
                    Text(stringResource(R.string.remote_protected_title), color = UmbraRemoteColors.fg, style = UmbraRemoteType.protectedTitle)
                    Text(stringResource(R.string.remote_protected_sub), color = UmbraRemoteColors.fgHint, style = UmbraRemoteType.protectedSub, textAlign = TextAlign.Center)
                }
                client.snapshotMode && why != null -> Box(Modifier.fillMaxSize().padding(bottom = 40.dp), contentAlignment = Alignment.BottomCenter) {
                    Text(
                        why, color = UmbraRemoteColors.fg, style = UmbraRemoteType.snapshotFail,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(UmbraRemoteColors.pill).padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
        RemoteClient.Phase.Ended -> {
            // 结束卡：对方忙时标题是「没连上」（批次 264 §五），其余「会话结束了」；钮照 RemoteCopy.endButtons（原因用端上推出来的那个）。
            val reason = RemoteCopy.effectiveEndReason(client.endReason, client.bye, client.endedDuringConsent)
            val buttons = RemoteCopy.endButtons(client.errorCode, client.busyKind, reason).map { b ->
                when (b) {
                    RemoteCopy.EndButton.Close -> CoverButton(stringResource(R.string.remote_ended_close), false) { RemoteCenter.close() }
                    RemoteCopy.EndButton.Retry -> CoverButton(stringResource(R.string.remote_ended_retry), true) { RemoteCenter.retry() }
                    RemoteCopy.EndButton.ToView -> CoverButton(stringResource(R.string.remote_busy_toView), true) { RemoteCenter.retryAsView() }
                }
            }
            CoverCard(
                spin = false,
                title = stringResource(if (client.errorCode == "busy") R.string.remote_busy_title else R.string.remote_ended_title),
                body = endText(client), buttons = buttons,
            )
        }
    }
}

/**
 * 已结束那一句（iOS: RemoteSessionView.swift:863）：对方忙说两种「没连上」（不写占着的是哪台 —— busy 不一定带名字）；
 * 发起被拒说拒绝码（服务端那句原话优先 —— 它常带着具体是哪个权限），否则说结束原因。
 * 结束原因的字面由 [RemoteCopy.endCopy] 选：被控端的 bye（快捷键键名、空闲分钟数、PIN 锁到几点）优先于服务端那句笼统的 by_host。
 */
private fun endText(client: RemoteClient): String {
    RemoteCopy.busyKey(client.errorCode, client.busyKind)?.let { return remoteString(it, client.hostName) }
    val code = client.errorCode
    if (code != null) {
        if (code == "need_membership" || code == "not_connected" || code == "offline") return remoteString(RemoteCopy.errorKey(code))
        return client.errorMessage ?: remoteString(RemoteCopy.errorKey(code))
    }
    val c = RemoteCopy.endCopy(client.endReason, client.bye, client.endedDuringConsent)
    val args = c.args.map { a ->
        when (a) {
            is RemoteCopy.EndArg.Text -> a.text
            is RemoteCopy.EndArg.Time -> shortTime(a.ms)
        }
    }
    return remoteString(c.key, *args.toTypedArray())
}

/** 「14:30」：跟系统的 12 / 24 小时制走（iOS `.formatted(date: .omitted, time: .shortened)`）。 */
private fun shortTime(ms: Long): String =
    android.text.format.DateFormat.getTimeFormat(com.geek.umbra.app.AppGraph.context).format(java.util.Date(ms))

/**
 * 等对方允许（iOS 稿 02；iOS: RemoteSessionView.swift:739）：一整页浅底（不是盖在黑画面上的蒙层 —— 这时候还没有画面）。
 * 设备块 72 → 名字 22/650 → 旋转弧「等对方允许…」→「还剩 N 秒，没人点就按拒绝处理。」（真在倒数）→ 底部［停止连接］。
 */
@Composable
private fun WaitingPage(client: RemoteClient) {
    val c = UmbraTheme.colors
    // 每秒走一下（倒数要真在动）。用单调时钟，和 consentSince 同一把尺子。
    var nowSec by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime() / 1000.0) }
    LaunchedEffect(Unit) {
        while (true) {
            nowSec = android.os.SystemClock.elapsedRealtime() / 1000.0
            delay(250)
        }
    }
    val since = client.consentSince
    val sub = when {
        client.launch.source == "locate" -> stringResource(R.string.remote_wait_takeover)
        since == null -> stringResource(R.string.remote_wait_sub)
        else -> stringResource(R.string.remote_wait_left, RemoteFallback.consentLeft(since, nowSec).toString())
    }
    Column(
        Modifier.fillMaxSize().background(c.bg).blockTouches().windowInsetsPadding(WindowInsets.safeDrawing),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Column(
            Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            IconBlock(UmbraIconPaths.deviceDesktop, UmbraRemoteDimens.waitBlock, UmbraRemoteDimens.waitBlockRadius, UmbraRemoteDimens.waitIcon)
            Text(client.hostName, color = c.text, style = UmbraRemoteType.waitName, textAlign = TextAlign.Center)
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = c.muted, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.remote_wait_title), color = c.text, style = UmbraRemoteType.waitTitle)
            }
            Text(sub, color = c.muted, style = UmbraRemoteType.waitSub, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.weight(1f))
        BigButton(stringResource(R.string.remote_wait_cancel), primary = false, plain = true) { RemoteCenter.close() }
        Spacer(Modifier.height(34.dp))
    }
}

/** 等待页 / 无人值守页的设备块：card 底 + 1px 描边 + muted 图标。 */
@Composable
private fun IconBlock(icon: String, block: Dp, radius: Dp, iconSize: Dp) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(radius)
    Box(Modifier.size(block).clip(shape).background(c.card).border(UmbraDimens.borderW, c.border, shape), contentAlignment = Alignment.Center) {
        UmbraIcon(icon, c.muted, size = iconSize)
    }
}

/**
 * 盖在画面上的卡（iOS 稿 03「重连中」「已结束」「可控被占」「只看已满」；iOS: RemoteSessionView.swift:794）：
 * 深色 78% 蒙底 + card 圆角 14 的卡，图标（旋转弧 / 圆叉）+ 标题 17/600 → 说明 → 一排等分钮（50 高，主钮实心橙、其余 card 底描边）。
 * 横屏同一套，宽封顶 420。蒙底吃掉触摸：盖着的时候点画面不该变成发给对方的鼠标动作。
 */
@Composable
private fun CoverCard(spin: Boolean, title: String, body: String?, buttons: List<CoverButton>) {
    val c = UmbraTheme.colors
    Box(Modifier.fillMaxSize().background(UmbraRemoteColors.coverScrim).blockTouches(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(UmbraRemoteDimens.coverOuter).widthIn(max = UmbraRemoteDimens.coverMaxW).fillMaxWidth()
                .clip(RoundedCornerShape(UmbraRemoteDimens.coverRadius)).background(c.card).padding(UmbraRemoteDimens.coverPad),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (spin) CircularProgressIndicator(color = c.muted, strokeWidth = 2.dp, modifier = Modifier.size(UmbraRemoteDimens.coverIcon))
                else UmbraIcon(UmbraIconPaths.xCircle, c.muted, size = UmbraRemoteDimens.coverIcon, strokeWidth = 2f)
                Spacer(Modifier.width(10.dp))
                Text(title, color = c.text, style = UmbraRemoteType.coverTitle)
            }
            if (!body.isNullOrEmpty()) Text(body, color = c.muted, style = UmbraRemoteType.coverBody)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                buttons.forEach { b -> CardButton(b.title, b.primary, Modifier.weight(1f), onClick = b.action) }
            }
        }
    }
}

/** 50 高、圆角 12 的钮：主钮实心橙白字，其余 card 底 1px 描边、text 色。 */
@Composable
private fun CardButton(title: String, primary: Boolean, modifier: Modifier = Modifier, plain: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(UmbraRemoteDimens.bigButtonRadius)
    Box(
        modifier.heightIn(min = UmbraRemoteDimens.bigButtonH).clip(shape).background(if (primary) c.orange else c.card)
            .then(if (primary) Modifier else Modifier.border(UmbraDimens.borderW, c.border, shape))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, color = if (primary) c.onAccent else c.text, style = if (plain) UmbraRemoteType.bigButtonPlain else UmbraRemoteType.bigButton)
    }
}

/** 通栏大钮（左右各 16）。 */
@Composable
private fun BigButton(title: String, primary: Boolean, plain: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    CardButton(title, primary, Modifier.padding(horizontal = 16.dp).fillMaxWidth(), plain = plain, enabled = enabled, onClick = onClick)
}

/** 「对方当前窗口受保护」那层：半透明黑，吃掉底下画面的触摸。 */
@Composable
private fun Veil(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(UmbraRemoteColors.veil).blockTouches(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = UmbraRemoteDimens.veilMaxW).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
    }
}

/** 吃掉这一层底下的触摸（覆盖卡、整页）：不然点在蒙层上会穿下去，变成发给对方的鼠标动作。 */
private fun Modifier.blockTouches(): Modifier = pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } }

// MARK: 无人值守（§14.6，iOS 稿 05）

/** 无人值守的三步一整页：生物识别过了才出 PIN；PIN 过了、第一次连这台再核对一次短码（iOS: RemoteSessionView.swift:932）。 */
private fun showsUnattended(client: RemoteClient): Boolean {
    if (!client.unattended || client.phase == RemoteClient.Phase.Ended) return false
    return !client.localAuthPassed || client.pinRequested || client.askVerifyCode
}

private enum class UnattendedStep { Face, Pin, Locked, Code }

/**
 * 无人值守整页（浅底，左上「关闭」；iOS: RemoteSessionView.swift:948）：
 *   · 生物识别：「用生物识别确认是你」+ 说明 +［用生物识别］（系统那一弹还没判过时再拉起一次）；
 *     iOS 写的是 Face ID —— Android 统一叫「生物识别」（设计侧平台差异答复第 13 条），图标沿用 faceId 那颗；
 *   · PIN：「输入「X」的 PIN」+ 说明 + 点（至少 6 个，输到 7、8 位再长）+ 自画 3×4 数字键盘（不弹系统键盘）；
 *     满 6 位才出［连接］、不自动提交；错了红字（带了 left 就写「还能试 N 次」）；
 *   · 锁定：「先停一下」+ 锁到几点 + 「到点再试；急用就请那边的人点「允许」」，键盘 35% 置灰；
 *     稿上［改成请对方允许］没做（同 iOS）：无人值守是被控端判的，控制端发不出「这次要人点允许」的请求；
 *   · 核对短码：「第一次连「X」」+ 大号等宽短码 +［一致，继续］［对不上］。
 */
@Composable
private fun UnattendedPage(client: RemoteClient) {
    val c = UmbraTheme.colors
    var pin by remember { mutableStateOf("") }
    // 锁到点了要自己换回 PIN 那一态：到点那一刻重组一次（iOS 是下一次重画时才换）。
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(client.pinLockedUntilMs) {
        val t = client.pinLockedUntilMs ?: return@LaunchedEffect
        val wait = t - System.currentTimeMillis()
        if (wait > 0) delay(wait + 200)
        nowMs = System.currentTimeMillis()
    }
    val step = when {
        !client.localAuthPassed -> UnattendedStep.Face
        client.pinRequested -> client.pinLockedUntilMs?.takeIf { it > maxOf(nowMs, System.currentTimeMillis()) }?.let { UnattendedStep.Locked } ?: UnattendedStep.Pin
        else -> UnattendedStep.Code
    }
    val lockedAt = client.pinLockedUntilMs?.let(::shortTime) ?: ""
    // 横屏时内容高过屏幕：整页能竖着滚；够高时上半贴顶、钮贴底（SpaceBetween + 最小高 = 可视高）。
    BoxWithConstraints(Modifier.fillMaxSize().background(c.bg).blockTouches().windowInsetsPadding(WindowInsets.safeDrawing)) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = maxHeight).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
      Column {
        Box(
            Modifier.padding(horizontal = 8.dp).heightIn(min = UmbraDimens.tapMin).clickable(role = Role.Button) { RemoteCenter.close() }.padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.remote_ended_close), color = c.orangeText, style = UmbraRemoteType.closeLink) }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 28.dp).padding(top = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            IconBlock(
                when (step) { UnattendedStep.Face -> UmbraIconPaths.faceId; UnattendedStep.Locked -> UmbraIconPaths.lock; else -> RemoteIcons.lockShield },
                UmbraRemoteDimens.lockBlock, UmbraRemoteDimens.lockBlockRadius, UmbraRemoteDimens.lockIcon,
            )
            Text(
                when (step) {
                    UnattendedStep.Face -> stringResource(R.string.remote_android_unattended_faceTitle)
                    UnattendedStep.Pin -> stringResource(R.string.remote_pin_title, client.hostName)
                    UnattendedStep.Locked -> stringResource(R.string.remote_pin_lockedTitle)
                    UnattendedStep.Code -> stringResource(R.string.remote_verify_title, client.hostName)
                },
                color = c.text, style = UmbraRemoteType.unattendedTitle, textAlign = TextAlign.Center,
            )
            Text(
                when (step) {
                    UnattendedStep.Face -> stringResource(R.string.remote_unattended_faceBody, client.hostName)
                    UnattendedStep.Pin -> stringResource(R.string.remote_pin_sub)
                    UnattendedStep.Locked -> stringResource(R.string.remote_pin_locked, client.hostName, lockedAt)
                    UnattendedStep.Code -> stringResource(R.string.remote_verify_body)
                },
                color = c.muted, style = UmbraRemoteType.unattendedBody, textAlign = TextAlign.Center,
            )
            if (step == UnattendedStep.Pin || step == UnattendedStep.Locked) PinDots(pin.length, client.pinError != null)
            if (step == UnattendedStep.Pin) client.pinError?.let { ErrLine(it, UmbraIconPaths.xCircle, c.dangerText) }
            if (step == UnattendedStep.Locked) ErrLine(stringResource(R.string.remote_pin_lockedHint, lockedAt), UmbraIconPaths.clock, c.muted)
            if (step == UnattendedStep.Code) client.safetyCode?.let {
                Text(it, color = c.text, style = UmbraRemoteType.safetyCode, modifier = Modifier.padding(vertical = 10.dp))
            }
        }
      }
      Column(Modifier.padding(top = 24.dp)) {
        when (step) {
            UnattendedStep.Face -> BigButton(stringResource(R.string.remote_android_unattended_faceId), primary = true) { client.retryLocalAuth() }
            UnattendedStep.Pin, UnattendedStep.Locked -> {
                Keypad(disabled = step == UnattendedStep.Locked) { k -> pin = RemoteCommand.pinTyped(pin, k) }
                if (step == UnattendedStep.Pin && RemoteCommand.validPin(pin)) {
                    // 输满 6 位才出「连接」，不自动提交（批次 262：PIN 可能是 8 位）。
                    BigButton(stringResource(R.string.remote_pin_submit), primary = true, enabled = !client.pinBusy) {
                        client.submitPin(pin)
                        pin = ""
                    }
                }
            }
            UnattendedStep.Code -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton(stringResource(R.string.remote_verify_same), primary = true) { client.confirmCode() }
                BigButton(stringResource(R.string.remote_verify_differ), primary = false) { client.end("by_controller") }
            }
        }
        Spacer(Modifier.height(34.dp))
      }
    }
    }
}

/** PIN 点：已输的实心 text 色，没输的空心（有错时描边换 danger）。 */
@Composable
private fun PinDots(count: Int, hasError: Boolean) {
    val c = UmbraTheme.colors
    val a11y = stringResource(R.string.remote_pin_dotsA11y, count.toString())
    Row(
        Modifier.padding(top = 6.dp).clearAndSetSemantics { contentDescription = a11y },
        horizontalArrangement = Arrangement.spacedBy(UmbraRemoteDimens.pinDotGap),
    ) {
        repeat(RemoteCommand.pinDots(count)) { i ->
            val filled = i < count
            Box(
                Modifier.size(UmbraRemoteDimens.pinDot).clip(CircleShape).background(if (filled) c.text else Color.Transparent)
                    .then(if (filled) Modifier else Modifier.border(UmbraRemoteDimens.pinDotStroke, if (hasError) c.danger else c.faint, CircleShape)),
            )
        }
    }
}

@Composable
private fun ErrLine(text: String, icon: String, tint: Color) {
    Row(verticalAlignment = Alignment.Top) {
        UmbraIcon(icon, tint, size = 14.dp, strokeWidth = 2.2f, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = tint, style = UmbraRemoteType.unattendedErr)
    }
}

/** 数字键盘（3 × 4：1–9、空、0、⌫），键 62 高胶囊（iOS: RemoteSessionView.swift:1056）。锁定时整块 35%、点不动。 */
@Composable
private fun Keypad(disabled: Boolean, onKey: (String) -> Unit) {
    val c = UmbraTheme.colors
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫")
    val delA11y = stringResource(R.string.remote_pin_deleteA11y)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = UmbraRemoteDimens.keypadPadX).padding(bottom = 16.dp)
            .alpha(if (disabled) UmbraRemoteColors.disabledAlpha else 1f),
        verticalArrangement = Arrangement.spacedBy(UmbraRemoteDimens.keypadRowGap),
    ) {
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(UmbraRemoteDimens.keypadColGap)) {
                row.forEach { k ->
                    val m = Modifier.weight(1f).height(UmbraRemoteDimens.keypadKeyH)
                    if (k.isEmpty()) {
                        Spacer(m)
                    } else {
                        val isDel = k == "⌫"
                        Box(
                            m.clip(RoundedCornerShape(50)).background(if (isDel) Color.Transparent else c.card)
                                .then(if (isDel) Modifier else Modifier.border(UmbraDimens.borderW, c.border, RoundedCornerShape(50)))
                                .clickable(enabled = !disabled, role = Role.Button) { onKey(k) }
                                .semantics { contentDescription = if (isDel) delA11y else k },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isDel) UmbraIcon(RemoteIcons.deleteLeft, c.text, size = 22.dp)
                            else Text(k, color = c.text, style = UmbraRemoteType.keypadDigit)
                        }
                    }
                }
            }
        }
    }
}
