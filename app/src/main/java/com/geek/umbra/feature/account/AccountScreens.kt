// 账号模块的页面入口（函数签名不改，分发表在调）。对照 iOS Features/Me/AccountViews.swift：
//   set.account     账号页（头卡 + 存储条幅 + 会员 / 商城 / 积分 / 未验证 / 账号 + 使用记录 + 存储卡 + 设备 / 密码 / 注销；登出收在 ⋯）
//   set.password    改登录密码（三格 +「改完之后」三条 + 当前密码错那一档）
//   set.sessions    登录设备
//   set.deactivate  注销（**一屏，不是弹窗** —— 输入框装不进系统弹窗）
// 商城 / 道具历史见 ShopViews.kt，管理存储见 StorageViews.kt。用户可见文案与 PC `account.*` 逐字一致。
package com.geek.umbra.feature.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraButton
import com.geek.umbra.designsystem.UmbraButtonKind
import com.geek.umbra.designsystem.UmbraErrorCard
import com.geek.umbra.designsystem.UmbraFieldLabel
import com.geek.umbra.designsystem.UmbraHeroCard
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraMenuItem
import com.geek.umbra.designsystem.UmbraMoreMenu
import com.geek.umbra.designsystem.UmbraScreen
import com.geek.umbra.designsystem.UmbraSettingRow
import com.geek.umbra.designsystem.UmbraTextField
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraColorScheme
import com.geek.umbra.designsystem.settingSection
import com.geek.umbra.feature.me.copyPlainText
import com.geek.umbra.feature.me.bleedHorizontal
import com.geek.umbra.feature.me.footnoteItem
import com.geek.umbra.feature.me.introItem
import com.geek.umbra.feature.me.openWebLink
import com.geek.umbra.feature.me.presentPrompt
import com.geek.umbra.feature.presence.PresenceRecordSection
import com.geek.umbra.navigation.Route
import com.geek.umbra.navigation.UmbraAlert
import com.geek.umbra.net.ApiResult
import com.geek.umbra.net.AuthDevice
import com.geek.umbra.net.AuthUser
import com.geek.umbra.net.LoginState
import com.geek.umbra.net.Prefs
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val router get() = AppGraph.router

/** 注销留期（服务端 auth.DEACTIVATE_KEEP_MS 是真值；端上只用来显示）。iOS AuthConst.deactivateKeepDays。 */
private const val DEACTIVATE_KEEP_DAYS = 30

/** 登录密码至少几位（服务端 auth.MIN_PASSWORD 是真值；端上**只提示，不拦截**）。iOS AuthConst.minPasswordHint。 */
private const val MIN_PASSWORD_HINT = 10

/** 毫秒 → 「2026-09-08」。注册于那一行要的是日期不是相对时间：它是一个要被报出去的事实。 */
private fun day(ms: Long?): String {
    if (ms == null || ms <= 0) return "—"
    return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
}

/** 毫秒 → 「2026-09-08 14:32」（登录设备那一屏要到分钟）。 */
private fun minute(ms: Long?): String {
    if (ms == null || ms <= 0) return "—"
    return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}

/** ISO（会员到期 / 窗口重置时间）→ 「10-17 17:00」（本地时区）。解析不了回「—」。 */
private fun isoMinute(iso: String): String {
    val d = runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull() ?: return "—"
    return d.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}

// MARK: - 账号页

@Composable
fun AccountScreen() {
    val colors = UmbraTheme.colors
    val login by AppGraph.auth.login.collectAsState()
    val prefs by AppGraph.prefs.snapshot.collectAsState()
    val scope = rememberCoroutineScope()
    // 积分 / 会员 / 存储：null = 还没问到 / 这台服务器没有这项功能（旧服务端），那一格不画。
    var credits by remember { mutableStateOf<CreditsDTO?>(null) }
    var membership by remember { mutableStateOf<MembershipDTO?>(null) }
    var storage by remember { mutableStateOf<StorageDTO?>(null) }
    // warn 档条幅这一次关掉了没有（「×」）：只活在这一次进账号页里；「不再提示」是另一件事，落在 Prefs。
    var warnClosed by remember { mutableStateOf(false) }
    var resendNote by remember { mutableStateOf("") }
    // OIDC 登录的服务器：验证邮箱、改密码、注销都在 IdP 那边，那几组不画（画了也是 503）。
    // 地基的 auth.config 只在「无令牌问门」时才拉过，登着进来可能是 null —— 这里补问一次。
    var oidc by remember { mutableStateOf(AppGraph.auth.config?.isOidc ?: false) }

    LaunchedEffect(Unit) {
        if (AppGraph.auth.config == null) AppGraph.auth.fetchConfig()?.let { oidc = it.isOidc }
        AppGraph.auth.refresh()
        credits = AccountApi.credits()
        membership = AccountApi.membership()
        // 存储：优先取 /membership 顺带回的那份，没有再问 /storage；都没有 = 旧服务端，不画那一格。
        storage = membership?.storage ?: (AccountApi.storage() as? ApiResult.Ok)?.value
    }

    val s = login
    if (s !is LoginState.SignedIn) {
        // 还没问出来 / 没登录：**不在这里画一个登录表单** —— 登录屏是这条链路唯一的入口。给的只是一颗钮。
        // 2026-10-12 真机那次：钮按下去毫无反应。现在先再问一次服务端；仍不是登着就清掉本机会话、再放下单机标记，登录屏盖上来。
        val err = AppGraph.auth.lastRefreshError
        UmbraScreen(title = "账号", onBack = { router.back() }) {
            introItem("这台设备上还没有登录。提醒、保险箱、常用语这些本机功能照常用；聊天、任务、记账要登录。")
            settingSection("login", "登录", listOf(
                UmbraSettingRow(AppGraph.context.getString(R.string.account_loginAgain), chevron = true, action = {
                    scope.launch {
                        AppGraph.auth.refresh()
                        if (AppGraph.auth.signedIn) return@launch
                        AppGraph.auth.logout()
                        AppGraph.auth.goToLogin()
                    }
                }),
            ), footer = err?.let { AppGraph.context.getString(R.string.account_refreshFailed) + "\n" + it })
        }
        return
    }
    val user = s.user
    val devices = s.devices
    val warnSilenced = prefs[Prefs.K.storageWarnSilenced] ?: false

    /** 「开会员」/「续费会员」去哪：服务端配了商城地址就开它，没配进站内商城页。 */
    val openShop: () -> Unit = {
        val url = MembershipRules.shopUrl(membership?.shop_url)
        if (url != null) openWebLink(url) else router.go(Route.SetShop)
    }

    UmbraScreen(
        title = "账号", onBack = { router.back() },
        actions = {
            // 登出收在右上 ⋯（破坏性动作不占详情页底部）。
            UmbraMoreMenu(listOf(UmbraMenuItem("登出这台设备", danger = true) {
                router.confirm(UmbraAlert(
                    title = "要登出这台设备吗？",
                    message = "聊天、任务这些都在服务器上，这台设备上不留副本，重新登录就都回来了。保险箱和这台设备上的其它设置都不会动。",
                    confirmLabel = "登出", destructive = true,
                ) { AppGraph.scope.launch { AppGraph.auth.logout() } })
            }))
        },
    ) {
        item("hero") {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                UmbraHeroCard(
                    UmbraIconPaths.user, user.displayName.ifEmpty { user.email },
                    badge = if (user.deactivating) "注销留期内" else null, badgeOn = false, sub = user.email,
                )
                // 存储的条幅放内容区第一张卡（头卡之后、分组之前，234 §五）。两条互斥。
                storage?.let { st ->
                    if (StorageRules.isOverLimit(st.tier, st.used, st.limit)) {
                        StorageBanner(StorageRules.expiredBanner(st.limit), "续费会员", openShop)
                    } else if (!warnClosed && !warnSilenced) {
                        StorageRules.warnBanner(StorageRules.warn(st.warn), st.pct, st.used, st.limit)?.let { text ->
                            // 「不再提示」是一次不可逆的隐藏，所以它必须有家：那一格的用量条和百分比一直都在。
                            StorageBanner(text, "不再提示", { AppGraph.prefs.set(Prefs.K.storageWarnSilenced, true) }, onClose = { warnClosed = true })
                        }
                    }
                }
            }
        }
        beforeSections(user, membership, credits, oidc, colors,
            onRenew = { on -> scope.launch { AccountApi.setAutoRenew(on); membership = AccountApi.membership() } },
            onResend = { scope.launch { resendNote = resend() } })
        // 使用记录（执勤 M0 / 需求 §14.8）：紧跟「账号」那一组之后。组件在 presence 包，已存在于 main。
        item("presence") { androidx.compose.foundation.layout.Box(Modifier.bleedHorizontal(16.dp)) { PresenceRecordSection() } }
        storage?.let { st -> item("storage") { StorageSummaryCard(st, onManage = { router.go(Route.SetStorage) }, onUpgrade = openShop) } }
        afterSections(devices, oidc, colors.dangerText)
        footnoteItem(resendNote.ifEmpty { null }, key = "resend")
    }
}

/** 分组前半段：会员 → 商城 → 积分 → 邮箱未验证 → 账号。「存储」卡夹在中间，后半段见 [afterSections]。 */
private fun androidx.compose.foundation.lazy.LazyListScope.beforeSections(
    user: AuthUser, membership: MembershipDTO?, credits: CreditsDTO?, oidc: Boolean, colors: UmbraColorScheme,
    onRenew: (Boolean) -> Unit, onResend: () -> Unit,
) {
    // 行模板的 tint / valueTint 是数据，在 LazyListScope 里取不到主题 —— 由页面把当前色板传进来。前景字一律 *Text 那一支。
    val orangeTint = colors.orangeText
    val dangerTint = colors.dangerText
    fun toneTint(t: MembershipRules.Tone) = when (t) {
        MembershipRules.Tone.Danger -> colors.dangerText
        MembershipRules.Tone.Warning -> colors.orangeText
        MembershipRules.Tone.Ok -> null
    }
    val now = Instant.now()
    membership?.let { m ->
        val rows = mutableListOf<UmbraSettingRow>()
        val mem = m.member
        if (mem != null) {
            rows += UmbraSettingRow("${mem.tier_name} · ${MembershipRules.srcLabel(mem.src)}", sub = "到期 ${isoMinute(mem.end_at)} · 剩 ${MembershipRules.daysLeft(mem.end_at, now)} 天")
            for ((label, key) in listOf("5 小时额度" to "5h", "本周额度" to "week")) {
                val w = m.quota?.get(key)
                val pct = MembershipRules.clampPct(w?.pct ?: 0)
                rows += UmbraSettingRow(label, value = MembershipRules.windowText(pct, w?.resets_at?.let(::isoMinute)), mono = true,
                    valueTint = toneTint(MembershipRules.tone(pct)))
            }
            // 会员购物折扣：有折才出这一行。
            ShopRules.discountText(m.discount_pct)?.let { rows += UmbraSettingRow("会员购物", value = it, mono = true) }
            rows += UmbraSettingRow("到期自动续费", toggle = mem.auto_renew, onToggle = { onRenew(!mem.auto_renew) })
            MembershipRules.shopUrl(m.shop_url)?.let { url -> rows += UmbraSettingRow("续费", tint = orangeTint, chevron = true, action = { openWebLink(url) }) }
        } else {
            rows += UmbraSettingRow("还没有会员", sub = if (m.trial_days > 0) "第一次用 AI 会自动送 ${m.trial_days} 天试用。" else null)
            MembershipRules.shopUrl(m.shop_url)?.let { url -> rows += UmbraSettingRow("开会员", tint = orangeTint, chevron = true, action = { openWebLink(url) }) }
        }
        settingSection("member", "会员", rows, footer = "会员包含联网功能和服务端 AI 额度。额度按 5 小时和每周两个窗口算，用完到点自动重置。")
        // 商城有自己的一页；这里只留一条门。
        settingSection("shop", "商城", listOf(
            UmbraSettingRow("去商城", sub = "重置卡、加量包用积分买；会员和充值收单接入后开放。", chevron = true, action = { router.go(Route.SetShop) }),
        ))
    }
    credits?.let { c ->
        val rows = mutableListOf(UmbraSettingRow("可用积分", value = c.balance.toString(), mono = true))
        CreditsRules.topupUrl(c.topup_url)?.let { url -> rows += UmbraSettingRow("去充值", tint = orangeTint, chevron = true, action = { openWebLink(url) }) }
        c.ledger.take(5).forEach { e ->
            rows += UmbraSettingRow(CreditsRules.kindLabel(e.kind), sub = e.note.ifEmpty { null }, value = CreditsRules.deltaText(e.delta), mono = true,
                valueTint = if (e.delta < 0) dangerTint else null)
        }
        settingSection("credits", "积分", rows, footer = "积分用来买插件、重置卡和加量包，不扣 AI 用量。余额以服务器为准。")
    }
    // 未验证提示条**不能省**：「忘了密码」只发给验证过的邮箱，这个事实唯一能说出口的地方就是这里。已验证的什么都不显示。
    if (!user.emailVerified && !oidc) {
        settingSection("verify", "邮箱还没验证", listOf(
            UmbraSettingRow("重发验证信", tint = orangeTint, action = onResend),
        ), footer = "「忘了密码」只发给验证过的邮箱 —— 现在忘了密码是找不回来的。")
    }
    settingSection("account", "账号", listOf(
        UmbraSettingRow("显示名", value = user.displayName.ifEmpty { "未设置" }, chevron = true, action = { editName(user) }),
        UmbraSettingRow("邮箱", value = user.email, mono = true),
        // ID 给真值：这一行存在的意义就是报给运维。点一下复制（组脚那句的兑现）。
        UmbraSettingRow("账号 ID", value = user.id.toString(), mono = true, action = { copyPlainText(user.id.toString()); router.showToast("已复制") }),
        UmbraSettingRow("注册于", value = day(user.createdAtMs)),
        UmbraSettingRow("服务器", value = AppGraph.prefs.serverUrl, mono = true),
    ), footer = "报障时把账号 ID 报给我们，点一下可以复制。服务器地址是这个版本写死的；连接状态在「我」页的「连接」里看。")
}

/** 分组后半段：设备 → 密码 → 注销（OIDC 不画后两组）。 */
private fun androidx.compose.foundation.lazy.LazyListScope.afterSections(devices: List<AuthDevice>, oidc: Boolean, dangerTint: androidx.compose.ui.graphics.Color) {
    // 「登录设备」和「执行端设备」是两个概念，不许都叫「设备」。
    settingSection("devices", "设备", listOf(
        UmbraSettingRow("登录设备", value = "${devices.size} 台", chevron = true, action = { router.go(Route.SetSessions) }),
    ), footer = "有几台机器登录了这个账号。和「设备与能力」里的执行端设备不是一回事，两个数天生不相等。")
    if (oidc) return
    settingSection("password", "密码", listOf(
        UmbraSettingRow("改登录密码", chevron = true, action = { router.go(Route.SetPassword) }),
    ), footer = "改完之后其它设备会退出，这台不用重新登录。")
    // 注销在最下，单开一组，红字。**实心红只出现在注销那一屏底部的最终动作上。**
    settingSection("deactivate", null, listOf(
        UmbraSettingRow("注销账号", tint = dangerTint, action = { router.go(Route.SetDeactivate) }),
    ), footer = "留 $DEACTIVATE_KEEP_DAYS 天，期间用这个账号登录，注销就取消了。")
}

/** 改显示名（iOS alert + TextField）。**只改显示名，不碰邮箱和密码**。失败要出声（台账 deactivate-failure-silent 同形）。 */
private fun editName(user: AuthUser) {
    presentPrompt(
        title = "显示名", message = "可以留空，留空就显示邮箱。改这个不影响邮箱和密码。",
        initial = user.displayName.takeIf { it != user.email }.orEmpty(), placeholder = "留空就显示邮箱",
        confirmLabel = "保存", allowEmpty = true,
    ) { name ->
        AppGraph.scope.launch {
            val r = AccountApi.setDisplayName(name)
            if (r is ApiResult.Err) router.showToast(accountFailToast("显示名没改成", r))
        }
    }
}

/** 重发验证信。冷却中**明说还要等**（这一条要 Bearer，问的人就是账号本人）。回那句脚注。 */
private suspend fun resend(): String {
    val r = AccountApi.resendVerify()
    if (r !is ApiResult.Ok) return "这一封没发出去 —— 这台服务器的发信通道多半有问题。"
    val v = r.value
    return when {
        v.already_verified -> { AppGraph.auth.refresh(); "" }
        v.cooling -> "刚发过一封，等一分钟再点。"
        v.sent -> "验证信已经发出去了，去邮箱点那条链接。"
        else -> "这一封没发出去 —— 这台服务器的发信通道多半有问题。"
    }
}

// MARK: - 商城 / 道具历史 / 管理存储

@Composable
fun ShopScreen() = ShopPage()

@Composable
fun ShopHistoryScreen() = ShopHistoryPage()

@Composable
fun StorageScreen() = StoragePage()

// MARK: - 改登录密码（三格装不进系统弹窗，所以是一屏；底部不给实心红 —— 改密码不是破坏性操作）

@Composable
fun PasswordScreen() {
    val c = UmbraTheme.colors
    var cur by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var wrong by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 两个框对不对得上**在端上比**：服务端只收一个新密码，让用户为打错一个字等一次往返没有必要。
    val mismatch = pw2.isNotEmpty() && pw != pw2
    val ready = cur.isNotEmpty() && pw.isNotEmpty() && !mismatch && !busy

    fun save() {
        if (!ready) return
        busy = true; wrong = false
        scope.launch {
            when (val r = AccountApi.changePassword(cur, pw)) {
                is ApiResult.Ok -> {
                    // 回执给准数：服务端回 logged_out: N。
                    router.showToast(if (r.value > 0) "密码改好了，另外 ${r.value} 台设备已经退出登录。" else "密码改好了。没有别的设备在登录。")
                    // 不用裸 back()：人可能已经自己返回了，只在眼前仍是这一页时才退（umbra-ios#38）。
                    router.back(ifShowing = Route.SetPassword)
                }
                // 当前密码不对是 400 不是 401 —— 令牌是好的，错的是表单里那一格。
                is ApiResult.Err -> wrong = true
            }
            busy = false
        }
    }

    UmbraScreen(title = "改登录密码", onBack = { router.back() }) {
        item("form") {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (wrong) {
                    // 第三段那颗钮是「知道了」；正文写明邮件重置那条路改完**所有设备**都会退出。
                    UmbraErrorCard(
                        title = "当前密码不对",
                        body = "错的是「当前密码」那一格，令牌和登录都是好的。记不清的话走邮件重置 —— 那条路不需要当前密码，但改完之后所有设备都会退出，包括这台。",
                        actions = listOf("知道了" to { wrong = false }),
                    )
                }
                SecureField("当前密码", cur, { cur = it })
                // 新密码那格下面重复一次「和保险箱主密码是两回事」：改密码正是最容易把两把密码想成同一把的时刻。
                SecureField("新密码", pw, { pw = it }, hint = "至少 $MIN_PASSWORD_HINT 位。它只管登录，和保险箱的主密码是两回事。")
                SecureField("再打一遍", pw2, { pw2 = it })
                if (mismatch) Text("两次打的不一样。", color = c.dangerText, style = TextStyle(fontSize = 12.5.sp))
                // 「改完之后」三条。② 是与重置的分歧点，刻意的。
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.track).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    UmbraFieldLabel("改完之后")
                    listOf(
                        "其它设备会退出登录，下次连上服务器时生效，离线的可能过几小时才知道。",
                        "这台不用重新登录。",
                        "保险箱不受影响，主密码是另一把，这里改不到它。",
                    ).forEach { Text(it, color = c.muted, style = TextStyle(fontSize = 12.5.sp, lineHeight = 21.sp)) }
                }
                UmbraButton("改密码", { save() }, Modifier.fillMaxWidth(), enabled = ready)
                // 「必答未答时提交键置灰并给一行原因，不弹 alert」是设计稿明写的。
                if (!ready && !busy) Text(if (mismatch) "两次打的不一样。" else "三格都要填。", color = c.faint, style = TextStyle(fontSize = 12.sp))
            }
        }
    }
}

/** 一格密码框：字段名 + 高 40 圆角 10 bg 底 1px border 的框 + 可选提示。 */
@Composable
private fun SecureField(label: String, value: String, onChange: (String) -> Unit, hint: String? = null) {
    val c = UmbraTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        UmbraFieldLabel(label)
        UmbraTextField(
            value, onChange, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        )
        if (hint != null) Text(hint, color = c.faint, style = TextStyle(fontSize = 11.5.sp, lineHeight = 19.sp))
    }
}

// MARK: - 登录设备（只有三个字段，没有 IP、没有地理位置；不画「异地登录」，也不给单台踢下线 —— 没那个接口）

@Composable
fun SessionsScreen() {
    val login by AppGraph.auth.login.collectAsState()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    val devices = (login as? LoginState.SignedIn)?.devices.orEmpty()
    UmbraScreen(
        title = "登录设备", onBack = { router.back() }, refreshing = refreshing,
        onRefresh = { scope.launch { refreshing = true; AppGraph.auth.refresh(); refreshing = false } },
    ) {
        introItem("有几台机器登录了这个账号。和「设备与能力」里的执行端设备不是一回事，两个数天生不相等。")
        val rows = if (devices.isEmpty()) listOf(UmbraSettingRow("只有这一台。", enabled = false))
        else devices.map { d -> UmbraSettingRow(d.device.ifEmpty { "没名字的设备" }, sub = "登录于 ${minute(d.createdAtMs)} · 最后活跃 ${minute(d.lastSeenMs)}") }
        settingSection("sessions", null, rows, footer = "这里只看，不能单独踢下线 —— 要让所有设备一起退出，走注销那条路。")
    }
}

// MARK: - 注销（一屏 —— 要打出账号邮箱才能按最终那颗；这一步要挡的是「顺手点下去」）

@Composable
fun DeactivateScreen() {
    val c = UmbraTheme.colors
    val login by AppGraph.auth.login.collectAsState()
    val email = (login as? LoginState.SignedIn)?.user?.email.orEmpty()
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 一字不差打出邮箱（不分大小写、去首尾空白）。
    val ok = email.isNotEmpty() && typed.trim().lowercase() == email.lowercase() && !busy

    UmbraScreen(title = "注销账号", onBack = { router.back() }) {
        item("body") {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // 正文只写到留期为止 —— 30 天之后是删号还是只封号，口径没定。
                Text("账号会进入注销状态，留 $DEACTIVATE_KEEP_DAYS 天。这期间用这个账号登录，注销就取消了。",
                    color = c.text, style = TextStyle(fontSize = 14.sp, lineHeight = 24.sp))
                listOf(
                    // 其它设备不是「立即」：鉴权路由发现账号在注销态才拒，离线的设备可能过几小时才知道。
                    "其它设备会在下次连上服务器时退出登录 —— 不是立刻，离线的可能过几小时才知道。",
                    "这台设备上的东西一样都不动，包括保险箱。要清得你自己来。",
                    // 不写「删掉」（不准），也不写「数据还在」（会被读成能找回）。
                    "聊天、任务、记录还在服务器上，但留期一过就没有任何入口能看到它们 —— 不是能找回，是拿不到了。邮箱会腾出来，能用同一个邮箱重新注册，但那是一个空账号。",
                ).forEach { Text(it, color = c.muted, style = TextStyle(fontSize = 12.5.sp, lineHeight = 21.sp)) }
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    UmbraFieldLabel("打出 $email 才能按「注销账号」")
                    UmbraTextField(typed, { typed = it }, mono = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false))
                }
                // **实心红只出现在这里** —— 这是最终动作。
                UmbraButton("注销账号", {
                    if (!ok) return@UmbraButton
                    busy = true
                    scope.launch {
                        // 失败要出声（台账 deactivate-failure-silent）。成功时落「未登录」，登录屏接管。
                        val r = AccountApi.deactivate()
                        if (r is ApiResult.Err) router.showToast(accountFailToast("注销没有成功", r))
                        busy = false
                    }
                }, Modifier.fillMaxWidth(), kind = UmbraButtonKind.DangerSolid, enabled = ok)
                if (!ok && !busy) Text("要一字不差地打出你的邮箱。", color = c.muted, style = TextStyle(fontSize = 12.sp))
            }
        }
    }
}

