// 网络层纯判据 —— 不依赖 Android，有 JUnit 用例（app/src/test/.../NetRulesTest.kt）。
// 逐条照抄 iOS Networking/ 下的 SessionRevoke.swift、SessionGuardRules.swift、ChatSocketRules.swift、
// InitiatorRules.swift、URLRules.swift、Ledger.swift。改这里先改用例。
package com.geek.umbra.net

import java.net.URI

/** URL 相关。 */
object UmbraUrl {
    /**
     * 往 URL 里放值（令牌、id、用户输入）一律用这一档 —— 等价于 JS 的 encodeURIComponent，
     * 只放行 `A-Za-z0-9 - _ . ! ~ * ' ( )`。服务端把 `+` 当空格，令牌里有 `+` 时用宽松编码会被判失效。
     */
    fun value(raw: String): String {
        val sb = StringBuilder()
        for (b in raw.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (c < 0x80 && (ch.isLetterOrDigit() || ch in "-_.!~*'()")) sb.append(ch)
            else sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xF])
        }
        return sb.toString()
    }

    /** 路径段：同 value（`/` 也要转 —— id 里的 `/` `#` `?` 会改变路径形状）。 */
    fun segment(raw: String): String = value(raw)

    /** 取 host 并归一：小写、去末尾点、去 `www.`（AutoFill 同站判断用）。 */
    fun host(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return ""
        val h = runCatching { URI(s).host }.getOrNull()
            ?: runCatching { URI("https://$s").host }.getOrNull() ?: s
        var out = h.lowercase()
        if (out.endsWith(".")) out = out.dropLast(1)
        if (out.startsWith("www.")) out = out.drop(4)
        return out
    }

    /** 同站：相等，或一方是另一方按标签边界的子域。 */
    fun sameSite(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || a.endsWith(".$b") || b.endsWith(".$a")
    }

    /**
     * 一条完整地址是不是自己的服务端（[UmbraHttp.bytes] 据此决定带不带登录令牌、过不过请求闸）：
     * 以服务端地址打头（路径前缀按整段比），或与它同主机同端口（含 http→https 升级）。`a.com.evil.com` 不算 `a.com`。
     */
    fun isServer(server: String, full: String): Boolean {
        if (server.isEmpty()) return false
        val base = server.trimEnd('/')
        return full == base || full.startsWith("$base/") || full.startsWith("$base?") || followsRedirect(server, full)
    }

    /** 重定向只跟同主机：同 scheme 同端口，或 http:80 → https:443 升级。跨源 3xx 一律不跟。 */
    fun followsRedirect(from: String, to: String): Boolean {
        val a = runCatching { URI(from) }.getOrNull() ?: return false
        val b = runCatching { URI(to) }.getOrNull() ?: return false
        val sa = a.scheme?.lowercase() ?: return false
        val sb = b.scheme?.lowercase() ?: return false
        if (sa !in setOf("http", "https") || sb !in setOf("http", "https")) return false
        val ha = a.host?.lowercase() ?: return false
        val hb = b.host?.lowercase() ?: return false
        if (ha.isEmpty() || ha != hb) return false
        fun port(u: URI, s: String) = if (u.port > 0) u.port else if (s == "https") 443 else 80
        val pa = port(a, sa); val pb = port(b, sb)
        if (sa == sb) return pa == pb
        return sa == "http" && sb == "https" && pa == 80 && pb == 443
    }

    /** `https://x` → `wss://x/ws/chat?src=android&session=…`；未登录不带 session。 */
    fun chatSocket(server: String, session: String): String {
        val base = when {
            server.startsWith("https://", true) -> "wss://" + server.substring(8)
            server.startsWith("http://", true) -> "ws://" + server.substring(7)
            else -> server
        }.trimEnd('/')
        val q = if (session.isEmpty()) "" else "&session=" + value(session)
        return "$base/ws/chat?src=$CLIENT_SRC$q"
    }

    /**
     * WS / 统计里的端标识。服务端 routes/ws_chat.py 认得 ios / pc；android 是否被认要核对，
     * 认不得时退回 "ios"（见 docs/Android开发文档.md §六）。改这一处即可。
     */
    const val CLIENT_SRC = "android"
}

/** 账本：凡是记账接口，统一出口自动追加 `ledger=`（漏一处会把经营账记进生活账）。 */
enum class Ledger(val wire: String, val displayName: String) {
    Personal("personal", "生活账本"), Business("business", "经营账本");

    val other get() = if (this == Business) Personal else Business

    companion object {
        fun of(s: String?) = entries.firstOrNull { it.wire == s } ?: Personal
        private val prefixes = listOf("/money/", "/assist/money-shot")
        fun matches(path: String) = path == "/money" || prefixes.any { path.startsWith(it) }
    }
}

/** 会话失效判定。 */
object SessionRevokeRules {
    const val SESSION_HEADER = "X-Umbra-Session"
    const val ERROR_HEADER = "X-Umbra-Error"
    const val CODE = "session_invalid"
    const val CLOSE_CODE = 4001

    /** 401 + `X-Umbra-Error: session_invalid`；或任意状态码下 `X-Umbra-Session: invalid`。 */
    fun http(status: Int, errorCode: String?, sessionHeader: String?): Boolean {
        if (status == 401 && errorCode == CODE) return true
        return (sessionHeader ?: "").trim().lowercase() == "invalid"
    }

    fun wsFrame(type: String?) = type == CODE
    fun wsClose(code: Int, reason: String?) = code == CLOSE_CODE && reason == CODE

    fun bearer(authorization: String?): String =
        if (authorization != null && authorization.startsWith("Bearer ")) authorization.removePrefix("Bearer ") else ""

    /** 失效信号只认「发请求时带的那把 == 本机当前这把」：迟到的旧响应不许误报失效、也不许把人登回去。 */
    fun stillCurrent(sent: String, current: String) = sent.isNotEmpty() && sent == current
}

/** 只触发一次的闩。重新登录拿到新令牌后 rearm。 */
class SessionRevokeLatch {
    var fired = false; private set
    fun accept(sent: String, current: String): Boolean {
        if (fired || !SessionRevokeRules.stillCurrent(sent, current)) return false
        fired = true
        return true
    }
    fun rearm() { fired = false }
}

enum class RenewOutcome { Renewed, Rejected, Unreachable }

/** 静默续期的冷却：刚续上 30 秒内又被拒 → 直接登出；IdP 连不上后 30 秒内的信号先忽略。 */
class SessionRenewState {
    enum class Decision { Ignore, Renew, Revoke }
    var lastRenewedAtMs: Long? = null; private set
    var offlineUntilMs = 0L; private set

    fun decide(nowMs: Long, canRenew: Boolean): Decision {
        if (!canRenew) return Decision.Revoke
        if (nowMs < offlineUntilMs) return Decision.Ignore
        val t = lastRenewedAtMs
        if (t != null && nowMs - t < COOLDOWN_MS) return Decision.Revoke
        return Decision.Renew
    }

    /** 返回 true = 该登出了。 */
    fun record(o: RenewOutcome, nowMs: Long): Boolean = when (o) {
        RenewOutcome.Renewed -> { lastRenewedAtMs = nowMs; offlineUntilMs = 0; false }
        RenewOutcome.Unreachable -> { offlineUntilMs = nowMs + COOLDOWN_MS; false }
        RenewOutcome.Rejected -> true
    }

    fun reset() { lastRenewedAtMs = null; offlineUntilMs = 0 }

    companion object { const val COOLDOWN_MS = 30_000L }
}

/** 登录门。 */
enum class Gate { Checking, Open, Login, Register, SessionInvalid }
enum class SessionLock { Undecided, Open, Locked }
enum class GuardStep { None, Lock, Unlock }
enum class DeepLinkAction { Go, Hold, Drop }

object SessionGuardRules {
    fun lock(gate: Gate, revoked: Boolean): SessionLock = when {
        revoked -> SessionLock.Locked
        gate == Gate.Checking -> SessionLock.Undecided
        gate == Gate.Open -> SessionLock.Open
        else -> SessionLock.Locked
    }

    /** 第一次定下来（settled=false）一律执行那一档，冷启动登着也跑一次「开」。 */
    fun step(locked: Boolean, settled: Boolean, target: SessionLock): GuardStep = when (target) {
        SessionLock.Undecided -> GuardStep.None
        SessionLock.Locked -> if (locked && settled) GuardStep.None else GuardStep.Lock
        SessionLock.Open -> if (locked || !settled) GuardStep.Unlock else GuardStep.None
    }

    fun deepLink(lock: SessionLock, needsLogin: Boolean): DeepLinkAction = when (lock) {
        SessionLock.Open -> DeepLinkAction.Go
        SessionLock.Undecided -> DeepLinkAction.Hold
        SessionLock.Locked -> if (needsLogin) DeepLinkAction.Drop else DeepLinkAction.Go
    }

    fun showsTabsWhileChecking(lastLocked: Boolean, standalone: Boolean) = !lastLocked || standalone

    fun showsAuthScreen(gate: Gate, standalone: Boolean): Boolean = when (gate) {
        Gate.Checking, Gate.Open -> false
        Gate.Login, Gate.Register -> !standalone
        Gate.SessionInvalid -> true
    }

    /** 刚手动登出过的，即使问出来是 open（服务器没有账号功能）也落登录屏。 */
    fun gateAfterSignOut(asked: Gate) = if (asked == Gate.Open) Gate.Login else asked
}

/** 请求闸：未登录时只放行 `/auth/` 前缀与 `/health`。 */
object SessionRequestGate {
    fun relativePath(path: String, basePath: String): String? {
        val base = basePath.trimEnd('/')
        if (base.isEmpty()) return path
        if (path == base) return "/"
        if (!path.startsWith("$base/")) return null
        return path.substring(base.length)
    }

    fun isOpen(relative: String): Boolean {
        val segs = relative.split('/')
        if (segs.any { it == ".." || it == "." }) return false
        if (relative == "/health") return true
        return relative.startsWith("/auth/")
    }

    fun blocks(signedOut: Boolean, path: String, basePath: String): Boolean {
        if (!signedOut) return false
        val rel = relativePath(path, basePath) ?: return true
        return !isOpen(rel)
    }
}

/** 「人引起的请求」打标。 */
object InitiatorRules {
    const val HEADER = "X-Umbra-Initiator"
    const val USER = "user"
    const val INPUT_WINDOW_MS = 3_000L
    const val PING_INTERVAL_MS = 5 * 60_000L

    fun tags(nowMs: Long, lastInputMs: Long, background: Boolean): Boolean {
        if (background || lastInputMs <= 0) return false
        val d = nowMs - lastInputMs
        return d in 0..INPUT_WINDOW_MS
    }

    fun shouldPing(nowMs: Long, lastPingMs: Long, foreground: Boolean, signedIn: Boolean): Boolean {
        if (!foreground || !signedIn) return false
        if (lastPingMs <= 0 || lastPingMs > nowMs) return true
        return nowMs - lastPingMs >= PING_INTERVAL_MS
    }
}

/** 长连接关闭的判定。 */
object ChatSocketRules {
    enum class Verdict { SessionInvalid, GateRejected, OfflineRetry }

    fun verdict(code: Int, reason: String?): Verdict = when {
        SessionRevokeRules.wsClose(code, reason) -> Verdict.SessionInvalid
        code == SessionRevokeRules.CLOSE_CODE -> Verdict.GateRejected
        else -> Verdict.OfflineRetry
    }

    /** 退避：1、2、4……封顶 30 秒。 */
    fun backoffSeconds(attempt: Int): Long = minOf(30L, 1L shl attempt.coerceIn(0, 5))

    const val READY = "ws_ready"
    /** 老服务端不发 ws_ready：连上后这么久没被关就算认了。 */
    const val LEGACY_GRACE_MS = 2_000L
}

/** 服务端 `reason_kind`。 */
enum class ReasonKind(val wire: String) {
    InsufficientBalance("insufficient_balance"), NeedMembership("need_membership"),
    QuotaExhausted("quota_exhausted"), RateLimited("rate_limited"), UpstreamError("upstream_error"),
    AuthFailed("auth_failed"), BadRequest("bad_request"), Unavailable("unavailable"), Unknown("unknown");

    companion object {
        fun of(s: String?): ReasonKind? = if (s.isNullOrEmpty()) null else entries.firstOrNull { it.wire == s } ?: Unknown
    }
}
