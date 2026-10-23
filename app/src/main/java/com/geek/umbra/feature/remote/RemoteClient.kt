// 远程控制的控制端会话（Android 与 iOS 一样只做控制端）—— 逐段照搬 iOS `Features/Remote/RemoteClient.swift`。
// 契约是需求文档 §十四，服务端在 `remote_control.py` + `routes/ws_chat.py::_remote_from_controller`。
//
// 一次会话（§14.2 时序）：
//   控制端 `rc_request` → 被控端同意卡（10 s 默认拒；无人值守 / 求助卡接管直接同意）→ 两端收 `rc_state{connecting, ice_servers}`
//   → **被控端建 PeerConnection、加屏幕轨、建 DataChannel、发 offer** → 这里 answer → trickle ICE
//   → 连上了发 `rc_stats{connected:true, path}` → 服务端翻 `active`。
//
// 这一层只做「接线」：信令进出（复用聊天那条 `/ws/chat`，不另开连接）、PeerConnection 的生命周期、
// 两条 DataChannel（`rc` JSON / `file` 二进制）、统计上报、快照降级、切后台、无人值守的本机认证。
// 凡是「判」的部分都在 `RemoteRules.kt`（纯判据、有用例），这里只照着它做。
//
// 线程：会话的全部状态只在主线程上改（Compose 读它）。WebRTC 的回调落在它自己的信令线程上，
// 一律 [post] 回主线程再处理（iOS 的 RemotePeerBridge 做的是同一件事）。唯一例外是 `file` 通道收块：
// 写盘在回调线程上顺序做（见 [receiveChunk]），只把进度贴回主线程 —— 64 KB 一块在主线程写盘会掉帧。
package com.geek.umbra.feature.remote

import android.content.Context
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.geek.umbra.R
import com.geek.umbra.app.AppForeground
import com.geek.umbra.app.AppGraph
import com.geek.umbra.net.HttpResult
import com.geek.umbra.net.SocketState
import com.geek.umbra.net.UmbraHttp
import com.geek.umbra.net.UmbraJson
import com.geek.umbra.net.UmbraUrl
import com.geek.umbra.net.dataOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.RequestBody.Companion.toRequestBody
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.File
import java.io.RandomAccessFile
import java.lang.ref.WeakReference
import java.net.URI
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

// MARK: - 文案

/** 按 key 取文案（key 是 iOS 的 `remote.x.y`，资源名把 `.` 换成 `_`）。拼出来的 key 用它；字面 key 直接用 R.string。 */
internal fun remoteString(key: String, vararg args: Any): String {
    val ctx = AppGraph.context
    @Suppress("DiscouragedApi")
    val id = ctx.resources.getIdentifier(RemoteCopy.resName(key), "string", ctx.packageName)
    if (id == 0) return key
    return if (args.isEmpty()) ctx.getString(id) else ctx.getString(id, *args)
}

private fun str(id: Int, vararg args: Any): String =
    if (args.isEmpty()) AppGraph.context.getString(id) else AppGraph.context.getString(id, *args)

// MARK: - 发起参数

/**
 * 从哪个入口、对哪台、要什么档（iOS: RemoteClient.swift:29）。四处入口（设备详情 / 设备会话导航栏 / 求助卡「实时接管」/
 * 任务详情「看实时画面」）都只拼这一个值交给 [RemoteCenter.start]，「重新连接」也是拿同一个值再发起一次。
 */
data class RemoteLaunch(
    val deviceId: String,
    val deviceName: String,
    val mode: String,
    val source: String,
    val runId: String? = null,
    val askId: String? = null,
    val manifest: RemoteManifest? = null,
)

// MARK: - 会话中心（单例）

/**
 * 整个 App 同一时刻最多一个控制端会话（iOS: RemoteClient.swift:45）。挂成进程级单例而不是哪个页面的状态：
 * 入口散在四个页面里，会话页是外壳上的一个全屏层 —— 各页各建一个就会同时挂两个会话。
 */
object RemoteCenter {
    private val _client = MutableStateFlow<RemoteClient?>(null)
    /** 当前会话；外壳按它挂全屏层。null = 没有会话 / 已关掉。 */
    val client: StateFlow<RemoteClient?> = _client

    /** 会话页所在的 Activity（BiometricPrompt 要 FragmentActivity）。会话层出现时登记，弱引用不拖住 Activity。 */
    @Volatile var activity: WeakReference<FragmentActivity>? = null

    /** 解码与画面共用的 EGL 上下文：进程里建一份、不释放 —— 渲染器与解码器各自的生命周期错开时，先释放它会让另一方崩在 GL 里。 */
    val egl: EglBase by lazy { EglBase.create() }

    private var bound = false

    /**
     * 接上聊天长连接：rc_* 帧从 `AppGraph.socket.frames` 里按 type 挑（iOS 是 ChatWebSocket 的 `onRemote` 出口），
     * 断线重连的状态也从它来（重连后要 `rc_attach`）；前后台从 [AppForeground] 来。只接一次。
     */
    fun bind() {
        if (bound) return
        bound = true
        AppGraph.scope.launch {
            AppGraph.socket.frames.collect { f ->
                val t = (f["type"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content ?: return@collect
                if (t.startsWith("rc_")) _client.value?.handleSignal(f)
            }
        }
        AppGraph.scope.launch {
            AppGraph.socket.state.collect { st -> _client.value?.socketStatusChanged(st) }
        }
        AppForeground.listeners += { fg -> AppGraph.scope.launch { if (fg) _client.value?.enterForeground() else _client.value?.enterBackground() } }
    }

    /** 发起。已有会话时先结束它（发 rc_end）再开新的 —— 不让两条会话同时挂着。 */
    fun start(launch: RemoteLaunch) {
        bind()
        _client.value?.let { it.end("by_controller"); it.teardown() }
        val c = RemoteClient(launch)
        _client.value = c
        c.start()
    }

    /** 关掉全屏层。会话还活着就先结束（关页面 = 结束，RC-9.1「控制端结束」）。 */
    fun close() {
        _client.value?.let { it.end("by_controller"); it.teardown() }
        _client.value = null
    }

    /** 「重新连接」：同样的参数再走一遍同意流程（RC-8.2：过了宽限只能重新走同意）。 */
    fun retry() {
        val launch = _client.value?.launch ?: return
        _client.value?.teardown()
        start(launch)
    }

    /**
     * 「改成只看」（批次 264 §五：可控名额被占时的主钮；iOS: RemoteClient.swift:96）：同一台、同一个来源，档位换成 view 重发一次 ——
     * 走查看同意卡。只看也满了，服务端回 busy + `busy_kind = view`，落到「只看已满」那一张。
     */
    fun retryAsView() {
        val launch = _client.value?.launch ?: return
        _client.value?.teardown()
        start(launch.copy(mode = "view"))
    }
}

// MARK: - 传输中的文件

/** 文件面板里一行进度（iOS: RemoteClient.swift:98）。拉回来的落在缓存目录，存到哪儿由人在面板上选（分享 / 存到下载）。 */
data class RemoteTransfer(
    val id: String,
    val name: String,
    val pull: Boolean,
    val remoteDir: String,
    val size: Long,
    val done: Long = 0,
    /** null = 进行中；"" = 完成；其他 = 失败原因。 */
    val failure: String? = null,
    val finished: Boolean = false,
    val localFile: File? = null,
) {
    val running: Boolean get() = !finished && failure == null
}

// MARK: - 会话

/**
 * 一次控制端会话（iOS: RemoteClient.swift:115）。状态用 Compose 的 snapshot state 暴露（会话页直接读），只在主线程上改。
 */
class RemoteClient(val launch: RemoteLaunch) {
    /** 会话走到哪一步了。画面层只看这个 + 几个旗子决定画什么蒙层。 */
    enum class Phase { Requesting, AwaitingConsent, Connecting, Active, Ended }

    val id: String = UUID.randomUUID().toString()

    private var phaseState by mutableStateOf(Phase.Requesting)
    /** 会话走到哪一步。进 AwaitingConsent 那一下记下 [consentSince]（iOS 是 phase 的 didSet）。 */
    var phase: Phase
        get() = phaseState
        private set(v) {
            if (v == Phase.AwaitingConsent && phaseState != Phase.AwaitingConsent) consentSince = now
            phaseState = v
        }
    /** 同意卡弹到对方那边的时刻（单调时钟秒，进 awaitingConsent 那一下）：等待页「还剩 N 秒」从这里倒数。 */
    var consentSince by mutableStateOf<Double?>(null); private set
    /** 结束原因（[RemoteCopy.endReasonKey] 的输入）；发起被拒时是 null、看 [errorCode]。 */
    var endReason by mutableStateOf<String?>(null); private set
    /** 发起被拒的码（`rc_error.code`）与服务端那句原话。 */
    var errorCode by mutableStateOf<String?>(null); private set
    var errorMessage by mutableStateOf<String?>(null); private set
    /** `rc_error.busy_kind`（只在 code = busy 时有，服务端 656c2f4 起带）：control = 可控名额被占 / view = 只看也满了。 */
    var busyKind by mutableStateOf<String?>(null); private set
    /**
     * 被控端结束前经 rc 通道说的「为什么」（`{t:"bye"}`）。服务端随后的 rc_state ended 多半只写 by_host，
     * 结束卡的字面由 [RemoteCopy.endCopy] 拿它和服务端原因一起选。
     */
    var bye by mutableStateOf<RemoteBye?>(null); private set
    /** 结束时还在同意阶段（requesting / awaitingConsent）：此时服务端的 `no_permission` = 对方点了「去系统设置」（granting）。 */
    var endedDuringConsent by mutableStateOf(false); private set
    var mode by mutableStateOf(launch.mode); private set
    var source by mutableStateOf(launch.source); private set
    var hostName by mutableStateOf(launch.deviceName); private set
    var sessionId by mutableStateOf<String?>(null); private set
    var unattended by mutableStateOf(false); private set
    /** ICE 断了在等被控端 restartIce（RC-8.1 的「重连中」）。 */
    var reconnecting by mutableStateOf(false); private set
    /** 已经降到快照（或本来就是「看一眼」）。 */
    var snapshotMode by mutableStateOf(!RemoteFallback.usesWebRTC(launch.source)); private set
    var snapshotImage by mutableStateOf<ImageBitmap?>(null); private set
    /** 快照这一张没拉到的那句话（挂在画面上，下一张拉到就消失）。 */
    var snapshotFailure by mutableStateOf<String?>(null); private set
    /** 视频帧尺寸（像素）。触摸坐标换算要它；第一帧没到时是 0（换算按视图本身算，不除零）。 */
    var frameSize by mutableStateOf(RemoteSize(0.0, 0.0))
    var path by mutableStateOf(""); private set
    var rttMs by mutableStateOf<Int?>(null); private set
    var fps by mutableStateOf<Double?>(null); private set
    var displays by mutableStateOf(launch.manifest?.displays ?: emptyList()); private set
    var currentDisplay by mutableStateOf(
        (launch.manifest?.displays ?: emptyList()).let { ds -> ds.firstOrNull { it.primary }?.id ?: ds.firstOrNull()?.id ?: "1" },
    ); private set
    var quality by mutableStateOf("auto"); private set
    var safetyCode by mutableStateOf<String?>(null); private set
    /** 被控端要 PIN（无人值守，§14.6）、且本机认证已经过了 —— 两样都满足才弹 PIN 输入。 */
    var pinRequested by mutableStateOf(false); private set
    var localAuthPassed by mutableStateOf(false); private set
    var pinError by mutableStateOf<String?>(null); private set
    /** PIN 连错锁定到几时（毫秒）。无人值守页据此换成「先停一下」那一态、数字键盘置灰。 */
    var pinLockedUntilMs by mutableStateOf<Long?>(null); private set
    /** 本机认证（生物识别）正在问：无人值守页「用生物识别」那颗钮不重复拉起。 */
    private var authInFlight = false
    var pinBusy by mutableStateOf(false); private set
    /** 被控端前台是黑名单应用（推流暂停）：画「对方当前窗口受保护」。 */
    var protectedApp by mutableStateOf<String?>(null); private set
    var upgradePending by mutableStateOf(false); private set
    var channelOpen by mutableStateOf(false); private set
    /** 一句短提示（输入被拒、剪贴板发出去了、降到快照……），会话页自己画、几秒后消失。 */
    var toast by mutableStateOf<String?>(null)
    /**
     * 底部那条带「申请控制」的轻提示（批次 264 §二：空闲降档「N 分钟没操作，降为只看。会话还在。」；
     * 只看档点画面「现在是只看，点了不会操作对方」）。和 [toast] 分开：它带一颗动作，停留也比一闪而过长。
     */
    var hint by mutableStateOf<String?>(null)
    /** 刚因为空闲被降了档：随后到的 `rc_state`（mode 变 view）不再另吐一句「已降为只看」—— 那条轻提示已经说了。 */
    private var idleDowngraded = false
    /** 这次无人值守会话要人核对短码（§14.4）：记住的电脑侧指纹对不上 / 没记过。 */
    var askVerifyCode by mutableStateOf(false)
    /**
     * 这次会话短码已核过（人点了「一致」，或记住的电脑侧指纹对上了）。没核过不发 PIN（refs umbra-ios#95，[RemoteUnattended]）。
     */
    var codeVerified by mutableStateOf(false); private set
    /** 这次要核的短码是「指纹变了」那一档（标题念「要重新核对」，不念「第一次连」）。 */
    var codeRecheck by mutableStateOf(false); private set
    /** 这次会话里电脑那一侧的 DTLS 指纹（对端 SDP 的 `a=fingerprint`）。点「一致」时按 deviceId 记下它。 */
    private var hostFingerprint: String? = null
    val fileLists = mutableStateMapOf<String, List<RemoteFileItem>>()
    val transfers = mutableStateListOf<RemoteTransfer>()
    /** 被控端推过来的屏幕轨。会话页的 SurfaceViewRenderer 挂在它上面。 */
    var videoTrack by mutableStateOf<VideoTrack?>(null); private set

    private val gate = RemoteSignalGate(UUID.randomUUID().toString(), null)
    private val buffered = mutableListOf<JsonObject>()
    private var iceServers: List<RemoteIceServer> = emptyList()
    private var connectingSince: Double? = null
    private var connectedReported = false
    private var everConnected = false
    private var socketWasDown = false
    private var inBackground = false
    private var backgroundSince: Double? = null
    private val tasks = mutableMapOf<String, Job>()
    private var lastDeniedToastAt = 0.0
    private var fileSeq = 0
    /** 拉文件的落盘句柄。写在 WebRTC 回调线程上、开关在主线程上 —— 并发表。 */
    private val fileHandles = ConcurrentHashMap<String, RandomAccessFile>()
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var cellular = false

    private var factory: PeerConnectionFactory? = null
    private var adm: JavaAudioDeviceModule? = null
    private var pc: PeerConnection? = null
    @Volatile private var rcChannel: DataChannel? = null
    @Volatile private var fileChannel: DataChannel? = null
    private val pendingCandidates = mutableListOf<IceCandidate>()

    /** 单调时钟（秒）：所有「过了多久」都按它算，改系统时间不影响宽限与降级判定。 */
    private val now: Double get() = SystemClock.elapsedRealtime() / 1000.0

    private val ctx: Context get() = AppGraph.context

    /** 从 WebRTC 线程回到主线程。会话已经换人（[RemoteCenter] 里不是这一个了）就不再处理。 */
    private fun post(block: () -> Unit) {
        AppGraph.scope.launch(Dispatchers.Main) { block() }
    }

    // MARK: 发起 / 结束

    /** 发 `rc_request`。没连上当场结束成「没连上」—— 不在等待页上干等一个发不出去的请求。 */
    fun start() {
        startNetworkMonitor()
        val msg = RemoteSignal.request(
            requestId = gate.requestId, deviceId = launch.deviceId, mode = launch.mode, source = launch.source,
            runId = launch.runId, askId = launch.askId, clientId = AppGraph.prefs.clientId, clientName = AppGraph.prefs.deviceName,
        )
        if (!AppGraph.socket.send(msg)) {
            fail("not_connected", null)
            return
        }
        phase = Phase.Requesting
        // 等同意的本地兜底（服务端 30 s 判 no_answer；那一帧丢了的话人会永远在等）。
        schedule("consent", RemoteFallback.CONSENT_WAIT) {
            if (phase == Phase.Requesting || phase == Phase.AwaitingConsent) end("no_answer")
        }
    }

    /** 结束会话：发 `rc_end`（已经有 session_id 时）、关连接、落到「已结束」。重复调无害。 */
    fun end(reason: String) {
        if (phase == Phase.Ended) return
        sessionId?.let { AppGraph.socket.send(RemoteSignal.end(it)) }
        finish(reason)
    }

    /** 本地收尾（不再发信令）：服务端说结束了 / 我们刚发完 rc_end。 */
    private fun finish(reason: String?) {
        if (phase == Phase.Ended) return
        endedDuringConsent = phase == Phase.Requesting || phase == Phase.AwaitingConsent
        phase = Phase.Ended
        endReason = reason
        reconnecting = false
        closePeer()
        tasks.values.forEach { it.cancel() }
        tasks.clear()
    }

    private fun fail(code: String, message: String?) {
        errorCode = code
        errorMessage = message?.trim()?.ifEmpty { null }
        finish(null)
    }

    /** 会话页关掉之后的彻底清理：临时文件、网络监听。 */
    fun teardown() {
        finish(endReason ?: "by_controller")
        netCallback?.let { cb -> runCatching { ctx.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) } }
        netCallback = null
        fileHandles.values.forEach { runCatching { it.close() } }
        fileHandles.clear()
        runCatching { filesRoot().deleteRecursively() }
    }

    // MARK: 信令进

    /** `/ws/chat` 上来的一帧 rc_*。先过 [RemoteSignalGate]（只认自己的 session_id；`rc_created` 之前到的先攒着）。 */
    fun handleSignal(j: JsonObject) {
        if (phase == Phase.Ended) return
        val type = RemoteJson.str(j["type"]) ?: return
        when (gate.verdict(type, RemoteJson.str(j["session_id"]), RemoteJson.str(j["request_id"]))) {
            RemoteSignalGate.Verdict.Drop -> Unit
            RemoteSignalGate.Verdict.Buffer -> if (buffered.size < RemoteSignalGate.BUFFER_LIMIT) buffered += j
            RemoteSignalGate.Verdict.Accept -> process(type, j)
        }
    }

    private fun process(type: String, j: JsonObject) {
        when (type) {
            "rc_created" -> {
                val sid = RemoteJson.str(j["session_id"])?.takeIf { it.isNotEmpty() } ?: return
                gate.sessionId = sid
                sessionId = sid
                if (phase == Phase.Requesting) phase = Phase.AwaitingConsent
                val held = buffered.toList()
                buffered.clear()
                for (b in held) if (RemoteJson.str(b["session_id"]) == sid) RemoteJson.str(b["type"])?.let { process(it, b) }
            }
            "rc_error" -> {
                busyKind = RemoteJson.str(j["busy_kind"])
                fail(RemoteJson.str(j["code"]) ?: "bad_request", RemoteJson.str(j["message"]))
            }
            "rc_state" -> RemoteState.parse(j)?.let(::applyState)
            "rc_offer" -> RemoteJson.str(j["sdp"])?.let(::handleOffer)
            "rc_ice" -> handleRemoteCandidate(j)
        }
    }

    private fun applyState(st: RemoteState) {
        if (st.hostName.isNotEmpty()) hostName = st.hostName
        source = st.source
        if (st.mode != mode) {
            val up = st.mode == "control"
            mode = st.mode
            upgradePending = false
            if (up) hint = null
            // 空闲降档那一下本地已经落到只看、也挂了轻提示：服务端随后推来的 view 不再另吐「已降为只看」（iOS: RemoteClient.swift:342）。
            if (!(idleDowngraded && !up)) toast = str(if (up) R.string.remote_toast_upgraded else R.string.remote_toast_downgraded)
            idleDowngraded = false
        } else if (idleDowngraded && st.mode == "view") {
            idleDowngraded = false
        }
        RemoteCopy.noticeKey(st.notice)?.let {
            upgradePending = false
            toast = remoteString(it)
        }
        val ice = st.iceServers
        if (!ice.isNullOrEmpty() && ice != iceServers) {
            iceServers = ice
            // 已经有连接时换上新凭证（rc_attach / 被控端重新上线后服务端会再发一份）：ICE restart 要用得上 TURN。
            pc?.let { p -> p.setConfiguration(rtcConfig()) }
        }
        when (st.state) {
            "awaiting_consent" -> if (phase == Phase.Requesting) phase = Phase.AwaitingConsent
            "connecting", "active" -> {
                enterConnecting(st.unattended)
                if (st.state == "active" && (snapshotMode || connectedReported)) phase = Phase.Active
            }
            "ended" -> finish(st.reason ?: "error")
        }
    }

    /** 被控端同意了：开始建连接（或开始拉快照）。只进一次 —— 之后每次 rc_state 都会带 connecting / active。 */
    private fun enterConnecting(u: Boolean) {
        if (phase != Phase.Requesting && phase != Phase.AwaitingConsent) return
        tasks.remove("consent")?.cancel()
        phase = Phase.Connecting
        unattended = u
        connectingSince = now
        if (u) authenticateLocally() else localAuthPassed = true
        if (snapshotMode) {
            startSnapshots()
            return
        }
        preparePeer()
        // 15 s 没连上降到快照（§14.5：含中继额度用完、只发了 STUN 打不通）。
        schedule("fallback", RemoteFallback.WEBRTC_TIMEOUT) {
            if (RemoteFallback.shouldFallback(source, connectingSince, connectedReported, now)) fallBackToSnapshot()
        }
    }

    /** 聊天长连接的状态变了：断过又连回来 → `rc_attach` 接回会话（30 s 宽限内，RC-8.3）。断着时 P2P 可能还通，什么都不用做。 */
    fun socketStatusChanged(st: SocketState) {
        when (st) {
            SocketState.Offline, SocketState.Connecting, SocketState.Unauthorized -> if (sessionId != null) socketWasDown = true
            SocketState.Online -> {
                val sid = sessionId
                if (socketWasDown && sid != null && phase != Phase.Ended) AppGraph.socket.send(RemoteSignal.attach(sid))
                socketWasDown = false
            }
        }
    }

    // MARK: 无人值守的本机认证（§14.6）

    /**
     * 指纹 / 人脸 / 设备锁屏密码。过了才弹 PIN；没过就结束 —— 无人值守的电脑不需要那边有人点允许，
     * 这一下是「拿着这台手机的人是不是机主」的唯一一道（PIN 是第二道，在被控端验）。
     *
     * Android 差异：API 28–29 上 BiometricPrompt 不支持 `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` 这个组合（androidx.biometric
     * 文档明说），那两版退到 `BIOMETRIC_WEAK | DEVICE_CREDENTIAL` —— 允许锁屏密码兜底这一点与 iOS `.deviceOwnerAuthentication` 一致。
     */
    private fun authenticateLocally() {
        val act = RemoteCenter.activity?.get()
        val auths = if (Build.VERSION.SDK_INT >= 30) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        }
        // 没设锁屏的手机：没法确认是不是机主。宁可不连 —— 这是一台不需要那边有人点允许的电脑。
        if (act == null || BiometricManager.from(ctx).canAuthenticate(auths) != BiometricManager.BIOMETRIC_SUCCESS) {
            end("auth_failed")
            return
        }
        if (authInFlight) return
        authInFlight = true
        val prompt = BiometricPrompt(act, ContextCompat.getMainExecutor(ctx), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                authInFlight = false
                if (phase == Phase.Ended) return
                localAuthPassed = true
                maybeAskVerify()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                authInFlight = false
                if (phase != Phase.Ended) end("auth_failed")
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(hostName.ifEmpty { str(R.string.remote_entry_control) })
            .setSubtitle(str(R.string.remote_auth_reason))
            .setAllowedAuthenticators(auths)
            .build()
        runCatching { prompt.authenticate(info) }.onFailure { authInFlight = false; end("auth_failed") }
    }

    /** 无人值守页「用生物识别」：系统那一弹还没判过时再拉起一次。正在问 / 已经过了什么都不做（iOS: RemoteClient.swift:455）。 */
    fun retryLocalAuth() {
        if (!unattended || localAuthPassed || phase == Phase.Ended) return
        authenticateLocally()
    }

    /**
     * 本机认证过了、指纹也算出来了：记住的电脑侧指纹对上就算核过，对不上（或没记过）就请人核对短码（§14.4）。
     * 在 PIN 之前（refs umbra-ios#95）：`needPin` 先到也只记下，界面停在短码那一道。
     */
    private fun maybeAskVerify() {
        if (!unattended || !localAuthPassed || codeVerified) return
        val fp = hostFingerprint ?: return          // 指纹还没算出来：等 makeAnswer 算出来再进这里
        val saved = verifyPrefs().getString(fingerprintKey, null)
        if (RemoteUnattended.fingerprintMatches(saved, fp)) codeVerified = true
        else { codeRecheck = RemoteUnattended.fingerprintChanged(saved, fp); askVerifyCode = true }
    }

    /** 模块自己的 SharedPreferences（小开关，不进 DataStore 那张共享表）。 */
    private fun verifyPrefs() = ctx.getSharedPreferences("umbra.remote", Context.MODE_PRIVATE)

    /** 按 deviceId 记电脑那一侧的指纹（不记短码本身：短码由两端指纹派生、每次会话都会变）。 */
    private val fingerprintKey: String get() = "umbra.remote.hostFingerprint." + launch.deviceId

    /** 人说「一致」：记下这次的电脑侧指纹（下次对得上就不再问），放行 PIN。顺手清掉已废的 codeChecked 布尔键。 */
    fun confirmCode() {
        val fp = hostFingerprint
        verifyPrefs().edit().apply {
            if (fp != null) putString(fingerprintKey, fp)
            remove("umbra.remote.codeChecked." + launch.deviceId)
        }.apply()
        askVerifyCode = false
        codeVerified = true
    }

    /** 送 PIN（DTLS 建立之后才发，走 DataChannel，不过服务端 —— RC-11.4）。短码没核过不发（refs umbra-ios#95）。 */
    fun submitPin(pin: String) {
        if (!RemoteCommand.validPin(pin)) {
            pinError = str(R.string.remote_pin_invalid)
            return
        }
        if (!RemoteUnattended.maySendPin(localAuthPassed, codeVerified)) return
        pinBusy = true
        pinError = null
        sendCommand(RemoteCommand.pin(pin))
    }

    // MARK: 快照

    /** 15 s 没连上 → 改走快照（§14.5）。会话还是同一个，只是不再等 WebRTC。 */
    private fun fallBackToSnapshot() {
        if (phase == Phase.Ended || snapshotMode) return
        closePeer()
        snapshotMode = true
        // 不再吐一句「直连没打通」：会话页工具条下挂着一条快照浮条（iOS 稿 03），一直在、不会一闪就没。
        startSnapshots()
    }

    /** 每 2 s 拉一张（§14.5）。切后台停拉；拉失败那句挂在画面上，下一张拉到就撤。 */
    private fun startSnapshots() {
        tasks["snapshot"]?.cancel()
        tasks["snapshot"] = AppGraph.scope.launch {
            while (isActive && phase != Phase.Ended) {
                if (!inBackground) snapshotOnce()
                delay((RemoteFallback.SNAPSHOT_INTERVAL * 1000).toLong())
            }
        }
    }

    /** 立刻拉一张（换屏 / 点「刷新」）。 */
    suspend fun snapshotOnce() {
        val sid = sessionId ?: return
        val body = buildJsonObject { if (displays.isNotEmpty()) put("display", currentDisplay) }
        val r = AppGraph.http.raw(
            "POST", "/remote/sessions/${UmbraUrl.segment(sid)}/snapshot",
            body = body.toString().toRequestBody(UmbraHttp.JSON), timeoutMs = 20_000,
        )
        val snap: RemoteSnapshot? = when (r) {
            is HttpResult.Ok -> runCatching { UmbraJson.parseToJsonElement(r.body).jsonObject }.getOrNull()?.let(RemoteSnapshot::parse)
            is HttpResult.Fail -> { snapshotFailure = r.detail?.takeIf { it.isNotBlank() } ?: str(R.string.remote_snapshot_failed); return }
            else -> null
        }
        if (snap == null) { snapshotFailure = str(R.string.remote_snapshot_failed); return }
        val img = remoteImage(snap.url)
        if (phase == Phase.Ended) return
        if (img == null) { snapshotFailure = str(R.string.remote_snapshot_failed); return }
        snapshotImage = img
        frameSize = RemoteSize(img.width.toDouble(), img.height.toDouble())
        snapshotFailure = null
        path = "snapshot"
        if (phase == Phase.Connecting) phase = Phase.Active
    }

    /**
     * 取快照图的字节（iOS: HTTPService.swift:1553 `remoteImage`）。服务端给的是 `/files/<id>` 这种相对地址；照样带 Bearer。
     * **只认同源**：这一条请求带着登录令牌，绝对地址指到别处时不发（统一出口本来也不给别家带令牌，这里干脆不去取）。
     */
    private suspend fun remoteImage(raw: String): ImageBitmap? = withContext(Dispatchers.IO) {
        val base = AppGraph.prefs.serverUrl
        val full = if (raw.startsWith("http")) raw else base + raw
        val host = runCatching { URI(full).host }.getOrNull()
        if (host == null || host != runCatching { URI(base).host }.getOrNull()) return@withContext null
        val bytes = AppGraph.http.bytes(full).dataOrNull ?: return@withContext null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }

    // MARK: 档位 / 交还

    /** 升档（看 → 控）要被控端再同意一次（RC-1.3）；回答经 `rc_state` 回来。 */
    fun requestUpgrade() {
        val sid = sessionId ?: return
        if (mode != "view" || upgradePending) return
        if (AppGraph.socket.send(RemoteSignal.mode(sid, "control"))) {
            upgradePending = true
            hint = null
            toast = str(R.string.remote_toast_upgradeAsked)
        } else toast = str(R.string.remote_err_not_connected)
    }

    /** 降档随时、立即生效。 */
    fun downgrade() {
        val sid = sessionId ?: return
        if (mode != "control") return
        AppGraph.socket.send(RemoteSignal.mode(sid, "view"))
    }

    /** 「交还给秘书」（只 locate 会话）：operate 续跑，会话降为只看（服务端那边做）。 */
    fun handback() {
        val sid = sessionId ?: return
        if (source != "locate") return
        toast = if (AppGraph.socket.send(RemoteSignal.handback(sid))) str(R.string.remote_toast_handedBack) else str(R.string.remote_err_not_connected)
    }

    // MARK: 输入

    /** 发一串输入。只看档 / PIN 没过 / 通道没开时不发，只看档给一句提示（节流 3 s，不然拖一下就刷屏）。 */
    fun send(inputs: List<RemoteInput>) {
        if (inputs.isEmpty()) return
        if (mode != "control" || snapshotMode) {
            if (now - lastDeniedToastAt > 3) {
                lastDeniedToastAt = now
                // 只看档点画面：底部轻提示带「申请控制」（iOS 稿 03「只看提示」）；快照里不能控，照旧一闪而过。
                if (snapshotMode) toast = str(R.string.remote_toast_snapshotNoInput)
                else if (!upgradePending) hint = str(R.string.remote_toast_viewOnly)
            }
            return
        }
        if (pinRequested) return
        for (i in inputs) sendCommand(i.json(currentDisplay))
    }

    /** `rc` 通道发一条 JSON。 */
    private fun sendCommand(o: JsonObject) {
        val ch = rcChannel ?: return
        if (ch.state() != DataChannel.State.OPEN) return
        runCatching { ch.send(DataChannel.Buffer(ByteBuffer.wrap(o.toString().toByteArray(Charsets.UTF_8)), false)) }
    }

    // MARK: 屏 / 画质 / 剪贴板

    fun selectDisplay(id: String) {
        if (id == currentDisplay) return
        currentDisplay = id
        if (snapshotMode) AppGraph.scope.launch { snapshotOnce() } else sendCommand(RemoteCommand.display(id))
    }

    fun chooseQuality(level: String) {
        if (level !in RemoteFallback.qualityLevels) return
        quality = level
        sendCommand(RemoteCommand.quality(level))
    }

    /**
     * 「发送剪贴板」（RC-5.1：手动，不自动同步）。剪贴板里是保险箱复制出去的值时不发 —— 那是「密码推到另一台设备」。
     * 判法同常用语的 {clipboard}：调保险箱的 `VaultClipboard.holdsSensitive()`，**判不准（抛异常）按「是敏感值」处理**。
     */
    fun sendClipboard() {
        val sensitive = runCatching { com.geek.umbra.feature.vault.VaultClipboard.holdsSensitive() }.getOrDefault(true)
        if (sensitive) {
            toast = str(R.string.remote_clip_sensitive)
            return
        }
        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
        val text = runCatching { cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString() }.getOrNull()
        if (text.isNullOrEmpty()) {
            toast = str(R.string.remote_clip_empty)
            return
        }
        sendCommand(RemoteCommand.clipSend(text))
        toast = str(R.string.remote_clip_sent)
    }

    fun fetchClipboard() = sendCommand(RemoteCommand.clipGet())

    // MARK: 文件（M2）

    /** 缓存目录：拉回来的文件先落这里（FileProvider `${applicationId}.files` 只开 cacheDir，分享出去靠它）；会话页关掉就清。 */
    private fun filesRoot(): File = File(ctx.cacheDir, "remote-files")

    val relayPath: Boolean get() = path == "relay"

    fun listFiles(dir: String) = sendCommand(RemoteCommand.filesList(dir))

    /** 从被控端拉一个文件。超过上限（中继 100 MB / 直连 500 MB）不发起 —— 中继那一档会计入流量（RC-6.2）。 */
    fun pull(item: RemoteFileItem, dir: String, resume: RemoteTransfer? = null) {
        val limit = RemoteFileCodec.limit(relayPath)
        if (item.size > limit) {
            toast = str(if (relayPath) R.string.remote_files_tooBigRelay else R.string.remote_files_tooBig)
            return
        }
        val offset = resume?.done ?: 0
        val id = resume?.id ?: RemoteFileCodec.makeId(++fileSeq)
        val folder = File(filesRoot(), id)
        val file = File(folder, RemoteFileName.local(item.name))
        try {
            folder.mkdirs()
            val h = RandomAccessFile(file, "rw")
            h.setLength(offset)
            h.seek(offset)
            fileHandles.put(id, h)?.let { runCatching { it.close() } }
        } catch (_: Exception) {
            toast = str(R.string.remote_files_writeFailed)
            return
        }
        upsert(RemoteTransfer(id, item.name, pull = true, remoteDir = dir, size = item.size, done = offset, localFile = file))
        sendCommand(RemoteCommand.fileGet(id, dir, item.name, offset))
    }

    /**
     * 推一个文件到被控端的下载目录（系统文件选择器 OpenDocument 选的）。按 64 KB 一块、看着 `bufferedAmount` 发 ——
     * 一口气塞进去会把 SCTP 的发送缓冲撑爆（libwebrtc 超过 16 MB 直接关通道）。
     */
    fun push(uri: Uri) {
        val cr = ctx.contentResolver
        var name = "file"
        var size = 0L
        runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.takeIf { it.isNotBlank() }?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        val limit = RemoteFileCodec.limit(relayPath)
        if (size <= 0 || size > limit) {
            toast = str(if (size <= 0) R.string.remote_files_readFailed else if (relayPath) R.string.remote_files_tooBigRelay else R.string.remote_files_tooBig)
            return
        }
        val id = RemoteFileCodec.makeId(++fileSeq)
        upsert(RemoteTransfer(id, name, pull = false, remoteDir = "downloads", size = size))
        sendCommand(RemoteCommand.filePutBegin(id, name, size))
        tasks["push-$id"] = AppGraph.scope.launch { pumpFile(uri, id, size) }
    }

    private suspend fun pumpFile(uri: Uri, id: String, size: Long) {
        val input = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openInputStream(uri) }.getOrNull() }
        if (input == null) { markTransfer(id, str(R.string.remote_files_readFailed)); return }
        input.use { ins ->
            for (r in RemoteFileCodec.ranges(size)) {
                if (phase == Phase.Ended) return
                val ch = fileChannel
                if (ch == null || ch.state() != DataChannel.State.OPEN) {
                    markTransfer(id, str(R.string.remote_files_channelClosed)); return
                }
                // 背压：缓冲超过 1 MB 就等一等（20 ms 一看）。
                while (ch.bufferedAmount() > 1_048_576) {
                    delay(20)
                    if (phase == Phase.Ended) return
                }
                val len = (r.end - r.start).toInt()
                val chunk = withContext(Dispatchers.IO) {
                    runCatching {
                        val buf = ByteArray(len)
                        var got = 0
                        while (got < len) {
                            val n = ins.read(buf, got, len - got)
                            if (n < 0) break
                            got += n
                        }
                        if (got == len) buf else null
                    }.getOrNull()
                }
                val block = chunk?.let { RemoteFileCodec.encode(id, it) }
                if (block == null) { markTransfer(id, str(R.string.remote_files_readFailed)); return }
                runCatching { ch.send(DataChannel.Buffer(ByteBuffer.wrap(block), true)) }
                updateTransfer(id) { it.copy(done = r.end) }
            }
        }
        sendCommand(RemoteCommand.filePutEnd(id))
    }

    private fun upsert(t: RemoteTransfer) {
        val i = transfers.indexOfFirst { it.id == t.id }
        if (i >= 0) transfers[i] = t else transfers.add(0, t)
    }

    private fun updateTransfer(id: String, f: (RemoteTransfer) -> RemoteTransfer) {
        val i = transfers.indexOfFirst { it.id == id }
        if (i >= 0) transfers[i] = f(transfers[i])
    }

    /** 收尾一条传输：failure == null 表示成功。 */
    /** 「知道了」：把那一行被拒的推送收掉（批次 290 §六 3）。 */
    fun dismissTransfer(id: String) { transfers.removeAll { it.id == id } }

    private fun markTransfer(id: String, failure: String?) {
        updateTransfer(id) { if (failure == null) it.copy(finished = true, failure = null) else it.copy(failure = failure) }
        fileHandles.remove(id)?.let { runCatching { it.close() } }
    }

    /** `file` 通道来的一块：在 WebRTC 回调线程上顺序写盘（同一条通道的回调本来就是串行的），进度贴回主线程。 */
    private fun receiveChunk(data: ByteArray) {
        val (id, payload) = RemoteFileCodec.decode(data) ?: return
        val h = fileHandles[id] ?: return
        val ok = runCatching { h.write(payload) }.isSuccess
        post {
            if (ok) updateTransfer(id) { it.copy(done = it.done + payload.size) }
            else markTransfer(id, str(R.string.remote_files_writeFailed))
        }
    }

    // MARK: 被控端消息（rc 通道）

    private fun handleHostMessage(m: RemoteHostMessage) {
        when (m) {
            is RemoteHostMessage.Displays -> {
                val valid = m.items.filter { it.id.isNotEmpty() }
                if (valid.isNotEmpty()) displays = valid
                m.current?.let { currentDisplay = it }
            }
            is RemoteHostMessage.Clip -> {
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                runCatching { cm.setPrimaryClip(android.content.ClipData.newPlainText("Umbra", m.text)) }
                toast = str(R.string.remote_clip_got)
            }
            is RemoteHostMessage.Pong -> {
                // 统计里没有 RTT 时（还没选中 candidate pair）用 DataChannel 的往返兜底。
                if (rttMs == null) {
                    val ms = (System.currentTimeMillis() - m.ts).toLong()
                    if (ms in 0 until 60_000) rttMs = ms.toInt()
                }
            }
            RemoteHostMessage.NeedPin -> { pinRequested = true; pinBusy = false }
            is RemoteHostMessage.PinResult -> {
                pinBusy = false
                when {
                    m.ok -> { pinRequested = false; pinError = null; pinLockedUntilMs = null }
                    m.lockedUntilMs != null -> { pinLockedUntilMs = m.lockedUntilMs; pinError = null }
                    else -> pinError = m.left?.let { str(R.string.remote_pin_wrongLeft, it.toString()) } ?: str(R.string.remote_pin_wrong)
                }
            }
            is RemoteHostMessage.Protected -> protectedApp = m.app
            RemoteHostMessage.Unprotected -> protectedApp = null
            is RemoteHostMessage.Denied -> toast =
                if (m.reason == "blacklist" && !m.app.isNullOrEmpty()) str(R.string.remote_denied_blacklistApp, m.app)
                else remoteString(RemoteCopy.deniedKey(m.reason))
            is RemoteHostMessage.Bye -> bye = m.bye
            is RemoteHostMessage.IdleDowngrade -> {
                // 被控端先发 rc_mode（服务端随后推 rc_state），再发这一条：本地先把档位落到只看，免得那几百毫秒里的输入白发。
                idleDowngraded = mode == "control"
                if (mode == "control") mode = "view"
                upgradePending = false
                hint = str(R.string.remote_toast_idleDowngrade, (m.minutes ?: RemoteFallback.CONTROL_IDLE_MIN).toString())
            }
            is RemoteHostMessage.Files -> fileLists[m.dir] = m.items.sortedByDescending { it.mtimeMs }
            is RemoteHostMessage.FileBegin -> if (m.size > 0) updateTransfer(m.id) { it.copy(size = m.size) }
            is RemoteHostMessage.FileEnd -> markTransfer(m.id, if (m.ok) null else (m.error ?: str(R.string.remote_files_failed)))
            is RemoteHostMessage.FilePutResult -> markTransfer(m.id, if (m.ok) null else (m.error ?: str(R.string.remote_files_failed)))
        }
    }

    // MARK: 前后台（RC-8.4）

    /**
     * 切后台：停止解码（关掉视频轨、停拉快照），保留连接 30 s；超时结束。
     * Android 不像 iOS 会挂起进程，计时器照常走、rc_end 照常送得出去（长连接在后台 60 秒后才断），所以不需要 iOS 那个
     * beginBackgroundTask；进程真被系统杀掉时由服务端的宽限判 controller_gone。
     */
    fun enterBackground() {
        if (phase == Phase.Ended || inBackground) return
        inBackground = true
        backgroundSince = now
        videoTrack?.let { runCatching { it.setEnabled(false) } }
        schedule("background", RemoteFallback.BACKGROUND_KEEP) { if (inBackground) end("background") }
    }

    fun enterForeground() {
        if (!inBackground) return
        inBackground = false
        tasks.remove("background")?.cancel()
        // 计时器万一没走（Doze 推迟）：回来时按单调时钟补判一次。
        val t = backgroundSince
        if (t != null && now - t > RemoteFallback.BACKGROUND_KEEP) {
            end("background")
            return
        }
        videoTrack?.let { runCatching { it.setEnabled(true) } }
        if (snapshotMode) AppGraph.scope.launch { snapshotOnce() }
    }

    // MARK: 网络类型（蜂窝默认省流，RC-3.5）

    private fun startNetworkMonitor() {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        fun judge(c: NetworkCapabilities?) =
            c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && !c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        cellular = runCatching { judge(cm.getNetworkCapabilities(cm.activeNetwork)) }.getOrDefault(false)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                cellular = judge(networkCapabilities)
            }
        }
        if (runCatching { cm.registerDefaultNetworkCallback(cb) }.isSuccess) netCallback = cb
    }

    // MARK: 计时

    /** 一个具名的延时动作（同名的会顶掉上一个）。每一个延时都跟着会话走、[finish] 时统一取消。 */
    private fun schedule(name: String, seconds: Double, body: () -> Unit) {
        tasks[name]?.cancel()
        tasks[name] = AppGraph.scope.launch {
            delay((seconds * 1000).toLong())
            tasks.remove(name)
            body()
        }
    }

    // MARK: WebRTC

    private fun rtcConfig(): PeerConnection.RTCConfiguration {
        val servers = iceServers.map {
            PeerConnection.IceServer.builder(it.urls).setUsername(it.username ?: "").setPassword(it.credential ?: "").createIceServer()
        }
        return PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            // 网络切换（Wi-Fi ↔ 蜂窝）时继续收集候选，配合被控端的 restartIce（RC-8.1）。
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
    }

    /**
     * 建 PeerConnection（answerer：等被控端的 offer）。iceServers 用 rc_state 给的那一份（含 TURN 临时凭证）。
     *
     * ⚠️ 只收视频、不发任何东西：不建本地音视频轨、不建采集器；音频设备模块关掉录音与播放（[PeerConnection.setAudioRecording]
     * / [PeerConnection.setAudioPlayout] = false）—— 本 App 不为远程申请麦克风 / 相机权限，也不去碰它们。
     */
    private fun preparePeer() {
        if (pc != null) return
        val r = runCatching {
            ensureWebRtcInitialized(ctx)
            val a = JavaAudioDeviceModule.builder(ctx)
                .setUseHardwareAcousticEchoCanceler(false)
                .setUseHardwareNoiseSuppressor(false)
                .createAudioDeviceModule()
            adm = a
            val f = PeerConnectionFactory.builder()
                .setAudioDeviceModule(a)
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(RemoteCenter.egl.eglBaseContext))
                .createPeerConnectionFactory()
            factory = f
            val p = f.createPeerConnection(rtcConfig(), PeerObserver(this))
            p?.setAudioRecording(false)
            p?.setAudioPlayout(false)
            pc = p
        }
        if (r.isFailure || pc == null) fallBackToSnapshot()
    }

    /** 被控端的 offer（首次 / ICE restart 都走这里）→ setRemote → 补上攒着的候选 → answer → setLocal → rc_answer。 */
    private fun handleOffer(sdp: String) {
        if (snapshotMode || phase == Phase.Ended) return
        if (pc == null) preparePeer()
        val p = pc ?: return
        p.setRemoteDescription(object : SdpAdapter() {
            override fun onSetSuccess() = post {
                if (pc !== p) return@post
                flushCandidates()
                makeAnswer(sdp)
            }

            override fun onSetFailure(error: String?) = post { if (pc === p) fallBackToSnapshot() }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    private fun makeAnswer(remoteSdp: String) {
        val p = pc ?: return
        val c = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }
        p.createAnswer(object : SdpAdapter() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc ?: return
                p.setLocalDescription(object : SdpAdapter() {
                    override fun onSetSuccess() = post {
                        val sid = sessionId ?: return@post
                        if (pc !== p) return@post
                        AppGraph.socket.send(RemoteSignal.answer(sid, desc.description))
                        safetyCode = RemoteSafetyCode.codeFromSdp(desc.description, remoteSdp)
                        // 同一会话里重新协商换了对端指纹：先前核过的不算数，重新比对 / 重新核。
                        val fp = RemoteSafetyCode.fingerprint(remoteSdp)
                        if (fp != hostFingerprint) { hostFingerprint = fp; codeVerified = false }
                        maybeAskVerify()
                    }
                }, desc)
            }
        }, c)
    }

    private fun handleRemoteCandidate(j: JsonObject) {
        val cand = RemoteJson.str(j["candidate"])?.takeIf { it.isNotEmpty() } ?: return
        val idx = (RemoteJson.int64(j["sdpMLineIndex"]) ?: 0).toInt()
        // sdpMid 给空串而不是 null：JNI 那头把 null 串转原生时会崩；空 mid 时 libwebrtc 按 mline 序号认。
        val c = IceCandidate(RemoteJson.str(j["sdpMid"]) ?: "", idx, cand)
        val p = pc
        if (p == null || p.remoteDescription == null) {
            pendingCandidates += c
            return
        }
        p.addIceCandidate(c)
    }

    private fun flushCandidates() {
        val p = pc ?: return
        pendingCandidates.forEach { p.addIceCandidate(it) }
        pendingCandidates.clear()
    }

    private fun closePeer() {
        videoTrack = null
        rcChannel?.let { runCatching { it.unregisterObserver(); it.close() } }
        fileChannel?.let { runCatching { it.unregisterObserver(); it.close() } }
        rcChannel = null
        fileChannel = null
        channelOpen = false
        val p = pc
        pc = null
        if (p != null) runCatching { p.close(); p.dispose() }
        factory?.let { runCatching { it.dispose() } }
        factory = null
        adm?.let { runCatching { it.release() } }
        adm = null
        pendingCandidates.clear()
    }

    // 下面几个由 [PeerObserver] / [ChannelObserver] 贴回主线程后调用。

    internal fun peerGenerated(c: IceCandidate) {
        val sid = sessionId ?: return
        AppGraph.socket.send(RemoteSignal.ice(sid, c.sdp, c.sdpMid, c.sdpMLineIndex))
    }

    internal fun peerIceState(s: PeerConnection.IceConnectionState) {
        when (s) {
            PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> {
                reconnecting = false
                tasks.remove("reconnect")?.cancel()
                onConnected()
            }
            PeerConnection.IceConnectionState.DISCONNECTED, PeerConnection.IceConnectionState.FAILED -> {
                if (!everConnected || phase == Phase.Ended) return
                reconnecting = true
                // 被控端会 restartIce（§14.4）；30 s 回不来就算断了（RC-8.2）。
                if (tasks["reconnect"] == null) schedule("reconnect", RemoteFallback.RECONNECT_GRACE) { if (reconnecting) end("ice_lost") }
            }
            else -> Unit
        }
    }

    internal fun peerTrack(t: org.webrtc.MediaStreamTrack?) {
        val v = t as? VideoTrack ?: return
        runCatching { v.setEnabled(!inBackground) }
        videoTrack = v
    }

    internal fun peerChannel(ch: DataChannel) {
        ch.registerObserver(ChannelObserver(this, ch))
        if (ch.label() == "file") fileChannel = ch else rcChannel = ch
        if (ch.state() == DataChannel.State.OPEN) channelOpened(ch)
    }

    internal fun channelState(ch: DataChannel) {
        val st = runCatching { ch.state() }.getOrNull() ?: return
        if (st == DataChannel.State.OPEN) channelOpened(ch)
        if (st == DataChannel.State.CLOSED && ch === rcChannel) channelOpen = false
    }

    private fun channelOpened(ch: DataChannel) {
        if (ch !== rcChannel || channelOpen) return
        channelOpen = true
        // 默认画质：蜂窝省流、别的自动（RC-3.5）。只在开头发一次；之后由人在工具条上改。
        val q = RemoteFallback.defaultQuality(cellular)
        quality = q
        sendCommand(RemoteCommand.quality(q))
        // 会话心跳另走 DataChannel（RC-8.3）：信令断了 P2P 还通时靠它知道对面还在。
        tasks["ping"]?.cancel()
        tasks["ping"] = AppGraph.scope.launch {
            while (isActive && phase != Phase.Ended) {
                sendCommand(RemoteCommand.ping(System.currentTimeMillis().toDouble()))
                delay(5_000)
            }
        }
    }

    internal fun channelText(text: String) {
        RemoteHostMessage.parse(text)?.let(::handleHostMessage)
    }

    /** ICE 连上了：第一次就报 `rc_stats{connected:true, path}`（服务端凭它进 active），之后 10 s 一次。 */
    private fun onConnected() {
        everConnected = true
        tasks.remove("fallback")?.cancel()
        if (connectedReported) return
        connectedReported = true
        phase = Phase.Active
        tasks["stats"]?.cancel()
        tasks["stats"] = AppGraph.scope.launch {
            var first = true
            while (isActive && phase != Phase.Ended) {
                reportStats(first)
                first = false
                delay((RemoteStatsRules.INTERVAL * 1000).toLong())
            }
        }
    }

    private suspend fun reportStats(connected: Boolean) {
        val p = pc ?: return
        val sid = sessionId ?: return
        val flat: Map<String, RemoteStatEntry> = suspendCancellableCoroutine { cont ->
            runCatching {
                p.getStats { report ->
                    val out = report.statsMap.mapValues { (_, s) -> RemoteStatEntry(s.type, s.members) }
                    if (cont.isActive) cont.resume(out)
                }
            }.onFailure { if (cont.isActive) cont.resume(emptyMap()) }
        }
        val sum = RemoteStatsRules.summarize(flat)
        if (sum != null) {
            path = sum.path
            sum.rttMs?.let { rttMs = it }
            fps = sum.fps
        }
        AppGraph.socket.send(RemoteSignal.stats(sid, connected, sum?.path ?: "direct", sum?.relayBytes ?: 0, rttMs))
    }

    // MARK: 回调转接

    /** PeerConnection 的回调落在 WebRTC 的信令线程上：一律贴回主线程（iOS RemotePeerBridge）。 */
    private class PeerObserver(private val client: RemoteClient) : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            state ?: return
            client.post { client.peerIceState(state) }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate ?: return
            client.post { client.peerGenerated(candidate) }
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        /** Plan B 的老路径；统一计划下走 onAddTrack / onTrack。几条都接，哪条先到用哪条。 */
        override fun onAddStream(stream: MediaStream?) {
            val t = stream?.videoTracks?.firstOrNull() ?: return
            client.post { client.peerTrack(t) }
        }
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(dc: DataChannel?) {
            dc ?: return
            client.post { client.peerChannel(dc) }
        }
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            val t = receiver?.track() ?: return
            client.post { client.peerTrack(t) }
        }
        override fun onTrack(transceiver: RtpTransceiver?) {
            val t = transceiver?.receiver?.track() ?: return
            client.post { client.peerTrack(t) }
        }
    }

    /**
     * DataChannel 的回调。⚠️ `onMessage` 里的 ByteBuffer 只在回调期间有效（原生内存，回调返回就释放）——
     * 必须当场拷出来，不能把 buffer 本身贴到别的线程去读。
     */
    private class ChannelObserver(private val client: RemoteClient, private val ch: DataChannel) : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) {}
        override fun onStateChange() = client.post { client.channelState(ch) }
        override fun onMessage(buffer: DataChannel.Buffer) {
            val bytes = ByteArray(buffer.data.remaining())
            buffer.data.get(bytes)
            if (buffer.binary) client.receiveChunk(bytes)
            else {
                val text = String(bytes, Charsets.UTF_8)
                client.post { client.channelText(text) }
            }
        }
    }

    /** SdpObserver 四个方法都要写；用不到的留空。 */
    private open class SdpAdapter : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }

    companion object {
        @Volatile private var webrtcReady = false

        /** `PeerConnectionFactory.initialize` 一个进程只调一次（加载 .so、设字段试验）。第一次真要建连接时才做 —— 冷启动不碰它。 */
        @Synchronized fun ensureWebRtcInitialized(ctx: Context) {
            if (webrtcReady) return
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext).createInitializationOptions())
            webrtcReady = true
        }
    }
}
