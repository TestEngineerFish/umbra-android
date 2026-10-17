// 远程会话页（控制端，需求 §6.4 / §11.2 / §14.7）—— 照搬 iOS `Features/Remote/RemoteSessionView.swift`：
// 全屏画面 + 手势层 + 可自动隐藏的工具条 + 修饰键栏 + 接管条 + 等待 / 重连 / 已结束蒙层 + 「对方当前窗口受保护」占位
// + 无人值守的 PIN + 短码核对。
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
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

    // 会话页所在的 Activity：BiometricPrompt 要它。
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

    // 有操作就重新计时；5 秒没碰工具条 / 画面就把工具条收起来（键盘开着时不收 —— 修饰键栏要用）。
    LaunchedEffect(touchMark, chromeShown, keyboardOn, client.phase) {
        if (!chromeShown || keyboardOn || client.phase != RemoteClient.Phase.Active) return@LaunchedEffect
        delay(5_000)
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

    LaunchedEffect(client.mode) { if (client.mode != "control") setKeyboard(false) }

    // 系统返回 = 先收键盘，再结束会话（iOS 全屏层没有返回手势，结束只走工具条那颗 ×；Android 的返回键不能什么都不做）。
    BackHandler {
        if (keyboardOn) setKeyboard(false) else RemoteCenter.close()
    }

    Box(Modifier.fillMaxSize().background(UmbraRemoteColors.stage)) {
        RemoteScreen(client, input, keyboardOn, scope, onTouch = { touched() }, onLongPress = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }, onKeyboardDismissed = { keyboardOn = false })

        // 「工具条 + 接管条 + 修饰键栏」这一层守安全区与键盘：修饰键栏因此贴在键盘上沿。
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (chromeShown) {
                TopBar(client, input, keyboardOn, onKeyboard = { setKeyboard(!keyboardOn) }, onFiles = { showFiles = true },
                    onHide = { chromeShown = false }, onTouch = { touched() })
            } else {
                RevealHandle { chromeShown = true; touchMark++ }
            }
            if (client.source == "locate" && client.mode == "control" && client.phase == RemoteClient.Phase.Active) TakeoverBar(client)
            Spacer(Modifier.weight(1f))
            toastShown?.let { ToastPill(it.first) }
            val showsModBar = client.mode == "control" && !client.snapshotMode && client.phase == RemoteClient.Phase.Active && (keyboardOn || chromeShown)
            if (showsModBar) ModifierBar(input) { touched() }
        }

        Overlay(client)
    }

    if (showFiles) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showFiles = false }, sheetState = state, containerColor = UmbraTheme.colors.bg,
            shape = RoundedCornerShape(topStart = UmbraDimens.radiusSheet, topEnd = UmbraDimens.radiusSheet),
        ) { RemoteFilesView(client) { showFiles = false } }
    }

    if (client.pinRequested && client.localAuthPassed && client.phase != RemoteClient.Phase.Ended) {
        PinSheet(client)
    }

    // 核对短码的弹窗等 PIN 那张面板收起之后再弹（PIN 面板里本来就印着短码 —— 先输 PIN、再确认一次「一样」）。
    if (client.askVerifyCode && !client.pinRequested) {
        val c = UmbraTheme.colors
        AlertDialog(
            onDismissRequest = { client.askVerifyCode = false },
            containerColor = c.card,
            shape = RoundedCornerShape(UmbraDimens.radiusDialog),
            title = { Text(stringResource(R.string.remote_verify_title), color = c.text) },
            text = { Text(stringResource(R.string.remote_verify_body, client.safetyCode ?: "----"), color = c.muted) },
            confirmButton = { TextButton(onClick = { client.confirmCode() }) { Text(stringResource(R.string.remote_verify_same), color = c.orangeText) } },
            dismissButton = {
                TextButton(onClick = { client.askVerifyCode = false; client.end("by_controller") }) {
                    Text(stringResource(R.string.remote_verify_differ), color = c.dangerText)
                }
            },
        )
    }
}

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

/** 工具条一行装不下全部按钮：横向滚动，结束钮固定在最前（iOS 同）。 */
@Composable
private fun TopBar(
    client: RemoteClient,
    input: RemoteInputModel,
    keyboardOn: Boolean,
    onKeyboard: () -> Unit,
    onFiles: () -> Unit,
    onHide: () -> Unit,
    onTouch: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Row(
        Modifier.fillMaxWidth().background(UmbraRemoteColors.bar).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BarButton(UmbraIconPaths.x, stringResource(R.string.remote_bar_end), onTouch) { RemoteCenter.close() }
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            QualityBadge(client)
            if (client.displays.size > 1) {
                BarMenu(RemoteIconsCompat.display, stringResource(R.string.remote_bar_display), onTouch, client.displays.mapIndexed { i, d ->
                    BarMenuItem(d.name.ifEmpty { stringResource(R.string.remote_display_n, i + 1) }, d.id == client.currentDisplay) {
                        client.selectDisplay(d.id); input.resetZoom()
                    }
                })
            }
            if (!client.snapshotMode) {
                BarMenu(RemoteIcons.gauge, stringResource(R.string.remote_bar_quality), onTouch, RemoteFallback.qualityLevels.map { q ->
                    BarMenuItem(remoteString(RemoteCopy.qualityKey(q)), q == client.quality) { client.chooseQuality(q) }
                })
            }
            if (client.mode == "control" && !client.snapshotMode) {
                BarButton(
                    if (input.trackpad) RemoteIcons.mouse else RemoteIcons.hand,
                    stringResource(if (input.trackpad) R.string.remote_bar_trackpad else R.string.remote_bar_touch), onTouch,
                ) { input.trackpad = !input.trackpad }
                BarButton(if (keyboardOn) RemoteIcons.keyboardHide else UmbraIconPaths.keyboard, stringResource(R.string.remote_bar_keyboard), onTouch, action = onKeyboard)
            }
            if (!client.snapshotMode && client.channelOpen) {
                BarMenu(RemoteIcons.clipboard, stringResource(R.string.remote_bar_clipboard), onTouch, listOf(
                    BarMenuItem(stringResource(R.string.remote_clip_send), false, RemoteIcons.upload) { client.sendClipboard() },
                    BarMenuItem(stringResource(R.string.remote_clip_get), false, RemoteIcons.arrowDown) { client.fetchClipboard() },
                ))
                BarButton(UmbraIconPaths.folder, stringResource(R.string.remote_bar_files), onTouch, action = onFiles)
            }
            // 升降档：只看 → 申请控制（被控端再同意一次）；控制 → 降为只看（立即生效）。快照里不能控，不给升。
            if (client.mode == "control") {
                BarButton(UmbraIconPaths.eye, stringResource(R.string.remote_bar_downgrade), onTouch) { client.downgrade() }
            } else if (!client.snapshotMode && client.phase == RemoteClient.Phase.Active) {
                BarButton(
                    if (client.upgradePending) RemoteIcons.hourglass else RemoteIcons.pointer, stringResource(R.string.remote_bar_upgrade), onTouch,
                    enabled = !client.upgradePending,
                ) { client.requestUpgrade() }
            }
            if (client.snapshotMode) {
                BarButton(RemoteIcons.refresh, stringResource(R.string.remote_bar_refresh), onTouch) { scope.launch { client.snapshotOnce() } }
            }
            if (input.zoom > 1) BarButton(RemoteIcons.fit, stringResource(R.string.remote_bar_fit), onTouch) { input.resetZoom() }
            client.safetyCode?.let { code ->
                val a11y = stringResource(R.string.remote_bar_codeA11y, code)
                Box(Modifier.heightIn(min = UmbraDimens.tapMin).padding(horizontal = 8.dp).semantics { contentDescription = a11y }, contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.remote_bar_code, code), color = UmbraRemoteColors.fgStrong, style = UmbraRemoteType.barCode)
                }
            }
            BarButton(UmbraIconPaths.chevronUp, stringResource(R.string.remote_bar_hide), onTouch, action = onHide)
        }
    }
}

/** 显示器图标在 UmbraIconPaths 里叫 deviceDesktop；单独起个名字，读起来和 iOS「display.2」对得上。 */
private object RemoteIconsCompat {
    val display = UmbraIconPaths.deviceDesktop
}

/** 连接质量：直连 / 中继 / 快照 + RTT + 帧率（RC-3.4）。中继时用暖色，提醒「这在花流量」。 */
@Composable
private fun QualityBadge(client: RemoteClient) {
    val label = when {
        client.snapshotMode -> stringResource(R.string.remote_path_snapshot)
        client.path == "relay" -> stringResource(R.string.remote_path_relay)
        client.path == "direct" -> stringResource(R.string.remote_path_direct)
        else -> stringResource(R.string.remote_path_connecting)
    }
    val parts = mutableListOf(label)
    val rtt = client.rttMs
    if (rtt != null && !client.snapshotMode) parts += "$rtt ms"
    val f = client.fps
    if (f != null && f > 0 && !client.snapshotMode) parts += "%.0f fps".format(f)
    Box(Modifier.heightIn(min = UmbraDimens.tapMin).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        Text(
            parts.joinToString(" · "),
            color = if (client.path == "relay") UmbraFixedColors.onNavAccent else UmbraRemoteColors.fgStrong,
            style = UmbraRemoteType.barStatus,
        )
    }
}

private class BarMenuItem(val label: String, val checked: Boolean, val icon: String? = null, val action: () -> Unit)

@Composable
private fun BarMenu(icon: String, label: String, onTouch: () -> Unit, items: List<BarMenuItem>) {
    var open by remember { mutableStateOf(false) }
    val c = UmbraTheme.colors
    Box {
        BarButton(icon, label, onTouch) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.card, modifier = Modifier.widthIn(min = 180.dp)) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label, color = c.text) },
                    leadingIcon = when {
                        item.icon != null -> { { UmbraIcon(item.icon, c.text, size = 18.dp) } }
                        item.checked -> { { UmbraIcon(UmbraIconPaths.check, c.orangeText, size = 18.dp) } }
                        else -> null
                    },
                    onClick = { open = false; item.action(); onTouch() },
                )
            }
        }
    }
}

@Composable
private fun BarButton(icon: String, label: String, onTouch: () -> Unit, enabled: Boolean = true, action: () -> Unit) {
    Box(
        Modifier.size(UmbraDimens.tapMin).clickable(enabled = enabled) { action(); onTouch() },
        contentAlignment = Alignment.Center,
    ) {
        UmbraIcon(icon, if (enabled) UmbraRemoteColors.fg else UmbraRemoteColors.fgSub, size = UmbraRemoteDimens.barIcon, contentDescription = label)
    }
}

/** 工具条收起后留在顶边的一颗小把手：点它把工具条叫回来（自动隐藏之后总得有条回来的路）。 */
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

/** 从求助卡「实时接管」进来（source=locate）：顶部多一条，随时交还给秘书（§11.2 第 4 条）。 */
@Composable
private fun TakeoverBar(client: RemoteClient) {
    val c = UmbraTheme.colors
    Row(
        Modifier.fillMaxWidth().background(c.orangeDeep.copy(alpha = 0.92f)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.remote_takeover_title), color = UmbraRemoteColors.fg, style = UmbraRemoteType.takeoverTitle, maxLines = 1, modifier = Modifier.weight(1f))
        Spacer(Modifier.size(4.dp))
        Box(
            Modifier.heightIn(min = UmbraDimens.tapMin).padding(vertical = 4.dp).clip(RoundedCornerShape(50)).background(c.orange)
                .clickable { client.handback() }.padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.remote_takeover_handback), color = UmbraRemoteColors.fg, style = UmbraRemoteType.takeoverButton)
        }
    }
}

// MARK: 修饰键栏

/**
 * ⌘ ⌥ ⌃ ⇧ Esc Tab ←↑↓→ Del（RC-4.6）。修饰键点一下管下一个键、点两下锁定、第三下松开 ——
 * 锁定的画实心，只管一次的画描边，一眼看得出现在按着什么。
 */
@Composable
private fun ModifierBar(input: RemoteInputModel, onTouch: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(UmbraRemoteColors.modBar).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf("cmd" to "⌘", "alt" to "⌥", "ctrl" to "⌃", "shift" to "⇧").forEach { (m, glyph) -> ModKey(input, m, glyph, onTouch) }
        listOf("esc" to "Esc", "tab" to "Tab", "left" to "←", "up" to "↑", "down" to "↓", "right" to "→", "del" to "Del").forEach { (k, label) ->
            Box(
                Modifier.defaultMinSize(UmbraDimens.tapMin, UmbraDimens.tapMin).clip(RoundedCornerShape(UmbraRemoteDimens.keyRadius))
                    .background(UmbraRemoteColors.keyCap).clickable { input.special(k); onTouch() }.padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, color = UmbraRemoteColors.fg, style = UmbraRemoteType.keyCap) }
        }
    }
}

@Composable
private fun ModKey(input: RemoteInputModel, m: String, glyph: String, onTouch: () -> Unit) {
    val lv = input.mods.level(m)
    val shape = RoundedCornerShape(UmbraRemoteDimens.keyRadius)
    val name = remoteString(RemoteCopy.modKey(m))
    val state = stringResource(
        when (lv) {
            RemoteModifiers.Level.Off -> R.string.remote_mod_off
            RemoteModifiers.Level.Once -> R.string.remote_mod_once
            RemoteModifiers.Level.Locked -> R.string.remote_mod_locked
        },
    )
    Box(
        Modifier.defaultMinSize(UmbraDimens.tapMin, UmbraDimens.tapMin).clip(shape)
            .background(if (lv == RemoteModifiers.Level.Locked) UmbraRemoteColors.fg else UmbraRemoteColors.keyCap)
            .then(if (lv == RemoteModifiers.Level.Once) Modifier.border(UmbraRemoteDimens.keyOnceStroke, UmbraRemoteColors.fg, shape) else Modifier)
            .clickable { input.tapModifier(m); onTouch() }
            .semantics { contentDescription = name; stateDescription = state },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = if (lv == RemoteModifiers.Level.Locked) UmbraRemoteColors.keyLockedFg else UmbraRemoteColors.fg, style = UmbraRemoteType.modKey)
    }
}

@Composable
private fun ToastPill(text: String) {
    Box(Modifier.fillMaxWidth().padding(bottom = 10.dp, start = 24.dp, end = 24.dp), contentAlignment = Alignment.Center) {
        Text(
            text, color = UmbraRemoteColors.fg, style = UmbraRemoteType.toast, textAlign = TextAlign.Center,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(UmbraFixedColors.toastGlass).padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

// MARK: 蒙层

@Composable
private fun Overlay(client: RemoteClient) {
    when (client.phase) {
        RemoteClient.Phase.Requesting, RemoteClient.Phase.AwaitingConsent -> Veil {
            CircularProgressIndicator(color = UmbraRemoteColors.fg, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            Text(stringResource(R.string.remote_wait_title, client.hostName), color = UmbraRemoteColors.fg, style = UmbraRemoteType.veilTitle, textAlign = TextAlign.Center)
            Text(
                stringResource(if (client.launch.source == "locate") R.string.remote_wait_takeover else R.string.remote_wait_sub),
                color = UmbraRemoteColors.fgSub, style = UmbraRemoteType.veilSub, textAlign = TextAlign.Center,
            )
            VeilButton(stringResource(R.string.remote_wait_cancel)) { RemoteCenter.close() }
        }
        RemoteClient.Phase.Connecting -> if (!client.snapshotMode || client.snapshotImage == null) Veil {
            CircularProgressIndicator(color = UmbraRemoteColors.fg, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            Text(
                stringResource(if (client.unattended && !client.localAuthPassed) R.string.remote_connecting_auth else R.string.remote_connecting),
                color = UmbraRemoteColors.fg, style = UmbraRemoteType.veilState,
            )
            VeilButton(stringResource(R.string.remote_wait_cancel)) { RemoteCenter.close() }
        }
        RemoteClient.Phase.Active -> {
            val app = client.protectedApp
            val why = client.snapshotFailure
            when {
                client.reconnecting -> Veil {
                    CircularProgressIndicator(color = UmbraRemoteColors.fg, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                    Text(stringResource(R.string.remote_reconnecting), color = UmbraRemoteColors.fg, style = UmbraRemoteType.veilState)
                }
                app != null -> Veil {
                    UmbraIcon(UmbraIconPaths.shieldCheck, UmbraRemoteColors.fg, size = UmbraRemoteDimens.veilIcon)
                    Text(stringResource(R.string.remote_protected_title), color = UmbraRemoteColors.fg, style = UmbraRemoteType.veilTitle)
                    Text(
                        if (app.isEmpty()) stringResource(R.string.remote_protected_sub) else stringResource(R.string.remote_protected_subApp, app),
                        color = UmbraRemoteColors.fgSub, style = UmbraRemoteType.veilSub, textAlign = TextAlign.Center,
                    )
                }
                client.snapshotMode && why != null -> Box(Modifier.fillMaxSize().padding(bottom = 40.dp), contentAlignment = Alignment.BottomCenter) {
                    Text(
                        why, color = UmbraRemoteColors.fg, style = UmbraRemoteType.snapshotFail,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(UmbraRemoteColors.pill).padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
        RemoteClient.Phase.Ended -> Veil {
            UmbraIcon(RemoteIcons.monitorOff, UmbraRemoteColors.fg, size = UmbraRemoteDimens.veilIcon)
            Text(stringResource(R.string.remote_ended_title), color = UmbraRemoteColors.fg, style = UmbraRemoteType.endTitle)
            Text(endText(client), color = UmbraRemoteColors.fgEnd, style = UmbraRemoteType.endSub, textAlign = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VeilButton(stringResource(R.string.remote_ended_close)) { RemoteCenter.close() }
                if (canRetry(client)) VeilButton(stringResource(R.string.remote_ended_retry), primary = true) { RemoteCenter.retry() }
            }
        }
    }
}

/** 已结束那一句：发起被拒说拒绝码（服务端那句原话优先 —— 它常带着具体是哪个权限），否则说结束原因。 */
private fun endText(client: RemoteClient): String {
    val code = client.errorCode
    if (code != null) {
        if (code == "need_membership" || code == "not_connected" || code == "offline") return remoteString(RemoteCopy.errorKey(code))
        return client.errorMessage ?: remoteString(RemoteCopy.errorKey(code))
    }
    return remoteString(RemoteCopy.endReasonKey(client.endReason))
}

/** 被拒成「要会员 / 对方没开 / 缺权限」时重连也是同一个答案，不给那颗钮（给了就是让人重试一个必然失败的动作）。 */
private fun canRetry(client: RemoteClient): Boolean {
    val code = client.errorCode ?: return client.endReason != "replaced"
    return code in listOf("not_connected", "busy", "offline")
}

/** 半透明蒙层：吃掉底下画面的触摸（蒙着的时候点画面不该变成发给对方的鼠标动作）。 */
@Composable
private fun Veil(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(UmbraRemoteColors.veil).pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = UmbraRemoteDimens.veilMaxW).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
    }
}

@Composable
private fun VeilButton(title: String, primary: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = UmbraDimens.tapMin).clip(RoundedCornerShape(50))
            .background(if (primary) UmbraTheme.colors.orange else UmbraRemoteColors.veilButton)
            .clickable(onClick = onClick).padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) { Text(title, color = UmbraRemoteColors.fg, style = UmbraRemoteType.veilButton) }
}

// MARK: PIN（无人值守，§14.6）

/** PIN 面板：不能下滑关掉（iOS interactiveDismissDisabled）—— 要么输 PIN，要么点「取消」结束会话。 */
@Composable
private fun PinSheet(client: RemoteClient) {
    val c = UmbraTheme.colors
    var pin by remember { mutableStateOf("") }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden })
    ModalBottomSheet(
        onDismissRequest = {}, sheetState = state, containerColor = c.bg,
        shape = RoundedCornerShape(topStart = UmbraDimens.radiusSheet, topEnd = UmbraDimens.radiusSheet),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.remote_pin_title), color = c.text, style = UmbraRemoteType.pinTitle)
            Text(stringResource(R.string.remote_pin_sub, client.hostName), color = c.muted, style = UmbraRemoteType.veilSub)
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(UmbraDimens.radiusControl)).background(c.card).padding(12.dp)) {
                if (pin.isEmpty()) Text(stringResource(R.string.remote_pin_placeholder), color = c.faint, style = UmbraRemoteType.pinField)
                BasicTextField(
                    value = pin,
                    onValueChange = { v -> pin = v.filter { it in '0'..'9' }.take(8) },
                    singleLine = true,
                    textStyle = UmbraRemoteType.pinField.copy(color = c.text),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            client.pinError?.let { Text(it, color = c.dangerText, style = UmbraRemoteType.pinError) }
            client.safetyCode?.let { Text(stringResource(R.string.remote_pin_code, it), color = c.muted, style = UmbraRemoteType.small) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.weight(1f).heightIn(min = UmbraDimens.tapMin).clip(RoundedCornerShape(UmbraDimens.radiusInput)).background(c.chip)
                        .clickable { RemoteCenter.close() },
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(R.string.remote_wait_cancel), color = c.text, style = UmbraRemoteType.veilState) }
                val ok = RemoteCommand.validPin(pin) && !client.pinBusy
                Box(
                    Modifier.weight(1f).heightIn(min = UmbraDimens.tapMin).clip(RoundedCornerShape(UmbraDimens.radiusInput))
                        .background(if (RemoteCommand.validPin(pin)) c.orange else c.faint)
                        .clickable(enabled = ok) { client.submitPin(pin); pin = "" },
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(R.string.remote_pin_submit), color = UmbraRemoteColors.fg, style = UmbraRemoteType.veilButton) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
