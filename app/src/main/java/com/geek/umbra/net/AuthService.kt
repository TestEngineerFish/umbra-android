package com.geek.umbra.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 账号与会话 —— 对应 iOS Networking/AuthService.swift + SessionRevoke + Features/Auth/AuthGate.swift。
 *
 * 三样状态：
 * - [login]：本机登录态（Unknown / Out / SignedIn / Invalid）；
 * - [gate]：登录门（Checking / Open / Login / Register / SessionInvalid），决定是否盖登录屏；
 * - [revoked]：会话被服务端判失效（只有重新登录能清掉）。
 */
class AuthService(
    private val http: UmbraHttp,
    private val session: SessionStore,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
) {
    private val _login = MutableStateFlow<LoginState>(LoginState.Unknown)
    val login: StateFlow<LoginState> = _login

    private val _gate = MutableStateFlow(Gate.Checking)
    val gate: StateFlow<Gate> = _gate

    private val _revoked = MutableStateFlow(false)
    val revoked: StateFlow<Boolean> = _revoked

    /** 上一次 refresh 失败的原因（账号页未登录时那行副文）。 */
    var lastRefreshError: String? = null; private set

    /** 「登录已失效」只弹一次 —— 外壳读它弹窗，弹完置 false。 */
    val revokeNotice = MutableStateFlow(false)

    var config: AuthConfig? = null; private set

    private val latch = SessionRevokeLatch()
    private val renewState = SessionRenewState()
    private val renewMutex = Mutex()
    private var inflightRenew: CompletableDeferred<RenewOutcome>? = null
    private val tokens = OidcTokens()
    private var pendingPkce: Pair<OidcRules.Pkce, OidcClient>? = null

    val signedIn: Boolean get() = _login.value is LoginState.SignedIn
    val hasToken: Boolean get() = session.token.isNotEmpty()
    val user: AuthUser? get() = (_login.value as? LoginState.SignedIn)?.user

    init {
        http.onSessionInvalid = { sent -> scope.launch { report(sent) } }
    }

    // MARK: - 登录门

    /** `GET /auth/config`：404 / 断网都当 local（老服务端没有这条）。 */
    suspend fun fetchConfig(): AuthConfig = withContext(BackgroundWork) {
        val c = http.get<AuthConfig>("/auth/config") ?: AuthConfig()
        config = c
        c
    }

    /** 启动 / 回前台时问门。判据见 docs/reference/01 §2「登录门」。 */
    suspend fun decideGate() {
        if (_revoked.value) { _gate.value = Gate.SessionInvalid; return }
        if (hasToken) {
            refresh()
            _gate.value = when (_login.value) {
                LoginState.Invalid -> Gate.SessionInvalid
                else -> Gate.Open // 有令牌但服务器问不到：上次登录还算数
            }
            return
        }
        val c = fetchConfig()
        val asked = if (c.isOidc) Gate.Login else withContext(BackgroundWork) {
            when (val r = http.raw("GET", "/auth/invite-required")) {
                is HttpResult.Ok -> {
                    val o = runCatching { UmbraJson.parseToJsonElement(r.body) as JsonObject }.getOrNull()
                    if (o?.bool("invite_required") == false) Gate.Register else Gate.Login
                }
                is HttpResult.Fail -> if (r.status == 503) Gate.Open else Gate.Login
                else -> Gate.Login
            }
        }
        val userSignedOut = prefs[Prefs.K.lastSignedOutByUser] ?: false
        _login.value = LoginState.Out
        _gate.value = if (userSignedOut) SessionGuardRules.gateAfterSignOut(asked) else asked
    }

    /** `GET /auth/me`。断网 / 解析失败保持原状态；await 回来核对令牌没被换过（防「按了登出又被登回来」）。 */
    suspend fun refresh() {
        val sent = session.token
        if (sent.isEmpty()) { _login.value = LoginState.Out; return }
        val r = withContext(BackgroundWork) { http.raw("GET", "/auth/me") }
        if (session.token != sent) return
        when (r) {
            is HttpResult.Ok -> {
                val o = runCatching { UmbraJson.parseToJsonElement(r.body) as JsonObject }.getOrNull()
                val u = AuthUser.parse(o?.obj("user"))
                if (u != null) {
                    val devices = o!!.arr("devices").mapNotNull { (it as? JsonObject) }.map {
                        AuthDevice(it.str("device").orEmpty(), it.long("created_at_ms"), it.long("last_seen_ms"))
                    }
                    _login.value = LoginState.SignedIn(u, devices)
                    lastRefreshError = null
                } else lastRefreshError = "账号信息认不出"
            }
            is HttpResult.Fail -> {
                lastRefreshError = r.detail ?: "HTTP ${r.status}"
                if (_revoked.value) _login.value = LoginState.Invalid
            }
            is HttpResult.Offline -> lastRefreshError = "连不上服务端"
            is HttpResult.Blocked -> Unit
        }
    }

    // MARK: - 登录 / 注册 / 登出

    suspend fun loginLocal(email: String, password: String): AuthFailure? {
        val body = buildJsonObject { put("email", email.trim()); put("password", password); put("device", prefs.deviceName) }
        val r = http.raw("POST", "/auth/login", body = body.toString().toRequestBodyJson(), authed = false)
        if (r !is HttpResult.Ok) return AuthFailure.of(r)
        val o = runCatching { UmbraJson.parseToJsonElement(r.body) as JsonObject }.getOrNull()
        val token = o?.str("token") ?: return AuthFailure.Other(r.status, "回包里没有令牌")
        signedInWith(token)
        return null
    }

    suspend fun register(email: String, password: String, displayName: String, invite: String): Pair<AuthFailure?, Boolean> {
        val body = buildJsonObject {
            put("email", email.trim()); put("password", password); put("display_name", displayName.trim()); put("invite", invite.trim())
        }
        val r = http.raw("POST", "/auth/register", body = body.toString().toRequestBodyJson(), authed = false)
        if (r !is HttpResult.Ok) return AuthFailure.of(r) to false
        val o = runCatching { UmbraJson.parseToJsonElement(r.body) as JsonObject }.getOrNull()
        return null to (o?.bool("verification_sent") ?: false)
    }

    suspend fun forgot(email: String): AuthFailure? {
        val r = http.raw("POST", "/auth/forgot", body = buildJsonObject { put("email", email.trim()) }.toString().toRequestBodyJson(), authed = false)
        return if (r is HttpResult.Ok) null else AuthFailure.of(r)
    }

    /** 登出：先注销推送（Android 暂无）→ POST /auth/logout（失败不管）→ 清本机令牌 → Out。 */
    suspend fun logout() {
        http.raw("POST", "/auth/logout")
        session.token = ""
        prefs.setNow(Prefs.K.lastSignedOutByUser, true)
        _login.value = LoginState.Out
        _gate.value = SessionGuardRules.gateAfterSignOut(_gate.value.let { if (it == Gate.Open) Gate.Open else it })
    }

    private suspend fun signedInWith(token: String) {
        session.token = token
        latch.rearm()
        renewState.reset()
        _revoked.value = false
        prefs.setNow(Prefs.K.lastSignedOutByUser, false)
        prefs.setNow(Prefs.K.standalone, false)
        refresh()
        _gate.value = Gate.Open
    }

    /** 「去登录」：离开单机模式，登录屏盖上来。 */
    fun goToLogin() {
        prefs.standalone = false
        if (_gate.value == Gate.Open && !hasToken) _gate.value = Gate.Login
        scope.launch { if (_gate.value == Gate.Open && hasToken && !signedIn) decideGate() }
    }

    /** 「先单机使用」。 */
    fun useStandalone() { prefs.standalone = true }

    // MARK: - OIDC

    /** 返回授权页地址，调用方用 Custom Tabs 打开；端点校验不过返回 null。 */
    fun beginOidc(c: AuthConfig): String? {
        if (!OidcRules.validate(c)) return null
        val p = OidcRules.newPkce()
        val client = OidcClient(c.issuer!!, c.client_id!!, c.token_endpoint!!, c.scope?.takeIf { it.isNotBlank() } ?: OidcRules.DEFAULT_SCOPE)
        pendingPkce = p to client
        return OidcRules.authorizeUrl(c, p)
    }

    suspend fun finishOidc(callback: String): AuthFailure? {
        val (p, client) = pendingPkce ?: return AuthFailure.Rejected("登录已过期，请重新点一次登录")
        val code = OidcRules.codeFrom(callback, p.state) ?: return AuthFailure.Rejected("回调校验没通过")
        pendingPkce = null
        return when (val r = tokens.exchange(client, code, p.verifier)) {
            is OidcTokens.Result.Ok -> {
                signedInWith(r.access)
                session.refreshToken = r.refresh
                session.oidcClient = UmbraJson.encodeToString(OidcClient.serializer(), client)
                null
            }
            OidcTokens.Result.Rejected -> AuthFailure.Rejected("身份提供方拒绝了这次登录")
            OidcTokens.Result.Unreachable -> AuthFailure.Offline
        }
    }

    // MARK: - 会话失效（唯一入口）

    /**
     * 服务端说「这把令牌失效了」。先过只触发一次的闩（发请求时那把 == 当前这把）；
     * OIDC 有刷新令牌就静默续期一次（并发共用一次在途续期 —— 刷新令牌会轮换，两次并发后一次必被拒）；
     * 续不上才真登出。
     */
    suspend fun report(sent: String) {
        val current = session.token
        if (!SessionRevokeRules.stillCurrent(sent, current)) return
        val canRenew = session.refreshToken != null && session.oidcClient != null
        when (renewState.decide(System.currentTimeMillis(), canRenew)) {
            SessionRenewState.Decision.Ignore -> return
            SessionRenewState.Decision.Renew -> {
                val outcome = renewOnce()
                val revoke = renewState.record(outcome, System.currentTimeMillis())
                if (!revoke) return
            }
            SessionRenewState.Decision.Revoke -> Unit
        }
        if (!latch.accept(sent, session.token)) return
        session.token = ""
        _revoked.value = true
        _login.value = LoginState.Invalid
        _gate.value = Gate.SessionInvalid
        revokeNotice.value = true
    }

    private suspend fun renewOnce(): RenewOutcome {
        // 锁里只做「取在途的 / 登记新的」，不在锁里 await —— 否则后面释放在途记录时要再拿这把锁，互相等死。
        val (existing, waiter) = renewMutex.withLock {
            val e = inflightRenew
            if (e != null) e to e else CompletableDeferred<RenewOutcome>().also { inflightRenew = it }.let { null to it }
        }
        if (existing != null) return existing.await()
        val outcome = run {
            val rt = session.refreshToken
            val cl = session.oidcClient?.let { runCatching { UmbraJson.decodeFromString(OidcClient.serializer(), it) }.getOrNull() }
            if (rt == null || cl == null) return@run RenewOutcome.Rejected
            when (val r = tokens.refresh(cl, rt)) {
                is OidcTokens.Result.Ok -> {
                    session.token = r.access
                    // 刷新令牌会轮换：回包带了新的就换上；没带沿用旧的。IdP 配置照旧存着。
                    session.refreshToken = r.refresh ?: rt
                    session.oidcClient = UmbraJson.encodeToString(OidcClient.serializer(), cl)
                    RenewOutcome.Renewed
                }
                OidcTokens.Result.Rejected -> RenewOutcome.Rejected
                OidcTokens.Result.Unreachable -> RenewOutcome.Unreachable
            }
        }
        renewMutex.withLock { inflightRenew = null }
        waiter.complete(outcome)
        return outcome
    }
}

private fun String.toRequestBodyJson() = toRequestBody(UmbraHttp.JSON)
