package com.geek.umbra.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

enum class SocketState { Connecting, Online, Offline, Unauthorized }

/**
 * 唯一一条长连接 `/ws/chat` —— 聊天、各模块刷新信号、远程控制信令（`rc_*`）全走它。
 * 对应 iOS Networking/ChatWebSocket.swift + ChatSocketRules.swift。
 *
 * 状态机要点（每条都是 iOS 实机踩过的）：
 * - 握手成功（101）只算「连接中」，**见到 `ws_ready` 才在线**：101 不代表凭证被认，服务端可能下一帧就 4001；
 *   老服务端不发 ws_ready —— 连上后 2 秒没被关算认了；本进程见过它发 ws_ready 后不再走这条退回规则；
 * - 关闭码 4001 + reason `session_invalid` → 走会话失效入口；其他 4001 → 未授权，**不重连**；其余退避重连 1/2/4…30 秒；
 * - **只发文本帧**（服务端 `receive_text`，二进制帧会被断开）；
 * - 断线回调会接连到达（onFailure + onClosed），用 [epoch] 过滤不是当前这条连接的事件，否则重连会排好几次、退避翻倍。
 *
 * 心跳交给 OkHttp 的 pingInterval（WebSocket 协议层 ping/pong）：30 秒一颗，上一颗没回就判死并走 onFailure → 退避重连。
 * 与 iOS「连续 2 颗没回」相比少容忍一颗，代价是弱网下多一次重连，可接受。
 */
class ChatSocket(
    baseClient: OkHttpClient,
    private val prefs: Prefs,
    private val session: SessionStore,
    private val auth: AuthService,
    private val scope: CoroutineScope,
) {
    private val client = baseClient.newBuilder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _state = MutableStateFlow(SocketState.Offline)
    val state: StateFlow<SocketState> = _state

    /** 每一帧原样广播（含 rc_*）。各模块按 type 自己挑。extraBufferCapacity 防慢消费者丢帧。 */
    private val _frames = MutableSharedFlow<JsonObject>(extraBufferCapacity = 256)
    val frames: SharedFlow<JsonObject> = _frames

    /** 连接代次：每次新建连接 +1。卡片补发合并（ChatCardResend）用它判断「是在哪条连接上处理的」。 */
    @Volatile var epoch = 0; private set

    private var ws: WebSocket? = null
    private var attempt = 0
    private var retryJob: Job? = null
    private var graceJob: Job? = null
    /** 停止重连：会话失效 / 未登录 / 主动断开。 */
    @Volatile private var halted = true
    private val serversSpeakingReady = mutableSetOf<String>()

    fun start() {
        halted = false
        if (ws == null) open()
    }

    /** 主动断开（登出、切后台久了）。不重连。 */
    fun stop() {
        halted = true
        retryJob?.cancel(); graceJob?.cancel()
        epoch++
        ws?.close(1000, null); ws = null
        _state.value = SocketState.Offline
    }

    /** 「重新连接」按钮。 */
    fun reconnect() {
        stop(); attempt = 0; start()
    }

    /** 发一帧。没连上返回 false —— 调用方据此给「没发出去」，不能假装发了。 */
    fun send(obj: JsonObject): Boolean {
        val w = ws ?: return false
        if (_state.value != SocketState.Online) return false
        return w.send(obj.toString())
    }

    private fun open() {
        if (halted) return
        val token = session.token
        if (token.isEmpty() || auth.revoked.value) { halted = true; _state.value = SocketState.Offline; return }
        val my = ++epoch
        _state.value = SocketState.Connecting
        val url = UmbraUrl.chatSocket(prefs.serverUrl, token)
        val serverKey = prefs.serverUrl.lowercase()
        val req = Request.Builder().url(url).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (my != epoch) return
                // 101 只算连接中。老服务端兜底：2 秒内没被关就认。
                if (serverKey !in serversSpeakingReady) {
                    graceJob?.cancel()
                    graceJob = scope.launch {
                        delay(ChatSocketRules.LEGACY_GRACE_MS)
                        if (my == epoch && _state.value == SocketState.Connecting) online()
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (my != epoch) return
                val o = runCatching { UmbraJson.parseToJsonElement(text) as JsonObject }.getOrNull() ?: return
                val type = o.str("type")
                when {
                    type == ChatSocketRules.READY -> { serversSpeakingReady += serverKey; online() }
                    SessionRevokeRules.wsFrame(type) -> { halted = true; scope.launch { auth.report(token) } }
                }
                _frames.tryEmit(o)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (my != epoch) return
                ended(code, reason, token)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (my != epoch) return
                // 握手阶段被拒（HTTP 401/403）等同 4001
                val code = response?.code?.let { if (it == 401 || it == 403) SessionRevokeRules.CLOSE_CODE else 1006 } ?: 1006
                ended(code, response?.header(SessionRevokeRules.ERROR_HEADER), token)
            }
        })
    }

    private fun online() {
        graceJob?.cancel()
        attempt = 0
        _state.value = SocketState.Online
    }

    private fun ended(code: Int, reason: String?, sentToken: String) {
        ws = null
        graceJob?.cancel()
        when (ChatSocketRules.verdict(code, reason)) {
            ChatSocketRules.Verdict.SessionInvalid -> {
                halted = true; _state.value = SocketState.Unauthorized
                scope.launch { auth.report(sentToken) }
            }
            ChatSocketRules.Verdict.GateRejected -> { halted = true; _state.value = SocketState.Unauthorized }
            ChatSocketRules.Verdict.OfflineRetry -> {
                _state.value = SocketState.Offline
                if (halted) return
                val wait = ChatSocketRules.backoffSeconds(attempt++)
                retryJob?.cancel()
                retryJob = scope.launch { delay(wait * 1000); if (!halted && ws == null) open() }
            }
        }
    }
}
