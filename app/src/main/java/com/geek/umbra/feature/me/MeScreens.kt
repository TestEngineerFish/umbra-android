// 我模块的页面入口。路由分发表（navigation/RouteScreens.kt）只调这里的函数；函数签名不要改（改了要同步改分发表）。
//
// 对照 iOS Features/Me/MeViews.swift：首页 + 设备与能力 + 设备详情 + 能力 + 连接 / 通知 / 通用 / 关于。
// 与设计稿的差异都是「服务端没有这个数据」而不是漏做（MeViews.swift:10-13）：
//   · 设备详情没有「延迟 / 心跳 / 已注册 / 系统授权」—— /devices/all 只回六项；
//   · 通知页没有分类开关 —— 服务端没有推送订阅接口，这一页只做本机通知权限的入口。
package com.geek.umbra.feature.me

import android.content.Intent
import android.content.res.Resources
import android.provider.Settings
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.geek.umbra.BuildConfig
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.Appearance
import com.geek.umbra.designsystem.UmbraChoice
import com.geek.umbra.designsystem.UmbraChoiceItem
import com.geek.umbra.designsystem.UmbraHeroCard
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraMenuItem
import com.geek.umbra.designsystem.UmbraMoreMenu
import com.geek.umbra.designsystem.UmbraScreen
import com.geek.umbra.designsystem.UmbraSettingRow
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.settingSection
import com.geek.umbra.feature.task.TaskTime
import com.geek.umbra.model.KnownDevice
import com.geek.umbra.navigation.Route
import com.geek.umbra.navigation.UmbraAlert
import com.geek.umbra.net.ImagePurpose
import com.geek.umbra.net.LoginState
import com.geek.umbra.net.Prefs
import com.geek.umbra.net.ServerSettings
import com.geek.umbra.net.SocketState
import com.geek.umbra.net.UmbraHttp
import kotlinx.coroutines.launch
import java.net.URI

private val router get() = AppGraph.router

// MARK: - 我 · 首页（iOS MeViews.swift:19）

@Composable
fun MeHomeScreen() {
    val c = UmbraTheme.colors
    val look = appearanceValue()
    // 账号那一行要跟着登录状态变：**订阅它**，登出之后不许还显示上一个人（MeViews.swift:22）。
    val login by AppGraph.auth.login.collectAsState()
    val ws by AppGraph.socket.state.collectAsState()
    val verdict by AppGraph.http.authVerdict.collectAsState()
    val revoked by AppGraph.auth.revoked.collectAsState()
    val devices by DevicesStore.devices.collectAsState()
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { DevicesStore.load() }

    // 「我」首页那一行显示谁登着。**问不出来时留空，不写「未登录」**：启动那一瞬间写死一句会闪一下假话（MeViews.swift:95）。
    val accountValue: String? = when (val s = login) {
        is LoginState.SignedIn -> s.user.displayName.ifEmpty { s.user.email }
        LoginState.Out, LoginState.Invalid -> "未登录"
        LoginState.Unknown -> null
    }
    val conn = connRow(ws, verdict, revoked)

    UmbraScreen(
        title = "我",
        refreshing = refreshing,
        onRefresh = { scope.launch { refreshing = true; DevicesStore.reload(); refreshing = false } },
    ) {
        item(key = "hero") {
            UmbraHeroCard(
                UmbraIconPaths.smartphone, AppGraph.prefs.deviceName,
                badge = "此设备", badgeOn = false, sub = serverHost(), subMono = true,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        // 账号排在最前：它是「我是谁」，其余是「这台设备怎么用」。它和「连接」是两节，不合并（MeViews.swift:109）。
        settingSection("acct", "账号", listOf(
            UmbraSettingRow("账号", value = accountValue, chevron = true, action = { router.go(Route.SetAccount) }),
            // 商城（2026-10-11 sam：要独立入口）。联网页：未登录由路由拦截画「要登录」。
            UmbraSettingRow(AppGraph.context.getString(R.string.me_shop), chevron = true, action = { router.go(Route.SetShop) }),
        ))
        settingSection("dev", "设备", listOf(
            UmbraSettingRow("设备与能力", value = if (devices.isEmpty()) null else "${devices.size} 台", chevron = true, action = { router.go(Route.MeDevices) }),
        ))
        settingSection("assist", "助手", listOf(
            UmbraSettingRow("记忆", chevron = true, action = { router.go(Route.MeMemory) }),
            UmbraSettingRow(AppGraph.context.getString(R.string.kb_title), chevron = true, action = { router.go(Route.KbList) }),
            UmbraSettingRow(AppGraph.context.getString(R.string.duty_title), chevron = true, action = { router.go(Route.MeDuty) }),
        ))
        settingSection("set", "设置", listOf(
            UmbraSettingRow(
                "连接", value = conn.text, valueIcon = conn.icon, valueIconSpins = conn.spins,
                valueTint = if (ws == SocketState.Online && conn.text == "已连接") c.success else c.muted,
                chevron = true, action = { router.go(Route.SetConn) },
            ),
            UmbraSettingRow("通知", chevron = true, action = { router.go(Route.SetNotify) }),
            UmbraSettingRow("通用", chevron = true, action = { router.go(Route.SetGeneral) }),
            // 外观（主题插件化二期，iOS MeViews.swift:143）：浅 / 深 / 跟随系统 + 主题。值写「跟随系统 · 默认」这种两段，两个独立选项一眼都看得见。
            UmbraSettingRow("外观", value = look, chevron = true, action = { router.go(Route.SetAppearance) }),
            // 回收站的正门（原来只能从保险箱里进）。
            UmbraSettingRow("回收站", chevron = true, action = { router.go(Route.VaultTrash) }),
            UmbraSettingRow("关于", chevron = true, action = { router.go(Route.SetAbout) }),
        ))
        footnoteItem("电脑操作授权、computer-use 这类开关是执行设备的属性，在「设备与能力」里只读查看，要改去电脑上改。")
    }
}

/** 「连接」那一行的字与图标。 */
private data class ConnFace(val text: String, val icon: String, val spins: Boolean)

/**
 * 「连接」那一行四档（MeViews.swift:65-93，设计侧 227 §一4）：已连接 = 对勾、连接中 = 旋转弧、未连接 = 圆叉、
 * 登录失效 = 三角。**「登录失效」不能写成「未连接」** —— 那一档等多久都不会好，他要进去重新登录。
 *
 * iOS 读的是长连接状态；Android 的长连接被 4001 判掉时只落到 Unauthorized，HTTP 那边的「凭证被拒」
 * （authVerdict / revoked）在 iOS 是由 markWebSocketRejected 同步过去的 —— 这里两样一起看，免得长连接还挂着
 * 「连接中」时把一台登录已失效的手机说成在连。
 */
private fun connRow(ws: SocketState, verdict: UmbraHttp.AuthVerdict, revoked: Boolean): ConnFace {
    if (revoked || verdict == UmbraHttp.AuthVerdict.SessionInvalid || ws == SocketState.Unauthorized) {
        return ConnFace("登录失效", UmbraIconPaths.alertTriangle, false)
    }
    return when (ws) {
        SocketState.Online -> ConnFace("已连接", UmbraIconPaths.check, false)
        SocketState.Connecting -> ConnFace("连接中", UmbraIconPaths.spinnerArc, true)
        SocketState.Offline -> ConnFace("未连接", UmbraIconPaths.xCircle, false)
        SocketState.Unauthorized -> ConnFace("登录失效", UmbraIconPaths.alertTriangle, false)
    }
}

/** 服务器 host[:port]（MeViews.swift:54）；解不开回原串。 */
private fun serverHost(): String {
    val raw = AppGraph.prefs.serverUrl
    val u = runCatching { URI(raw) }.getOrNull() ?: return raw
    val h = u.host ?: return raw
    return if (u.port > 0) "$h:${u.port}" else h
}

// MARK: - 设备与能力（MeViews.swift:153）

@Composable
fun DevicesScreen() {
    val devices by DevicesStore.devices.collectAsState()
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { DevicesStore.load() }
    UmbraScreen(
        title = "设备与能力", onBack = { router.back() }, refreshing = refreshing,
        onRefresh = { scope.launch { refreshing = true; DevicesStore.reload(); refreshing = false } },
    ) {
        introItem("装了 Umbra 客户端的机器就是执行设备。Android 手机不是执行设备，这里只看状态。")
        val footer = if (devices.isEmpty()) "还没有设备注册上来。在电脑上装好 Umbra 客户端并填上同一个服务端地址就会出现。"
        else "能力、授权都在设备详情里只读查看。"
        if (devices.isEmpty()) {
            item(key = "none") { MeNote(footer) }
        } else {
            settingSection("devs", "设备", devices.map { d ->
                UmbraSettingRow(d.device_name, sub = deviceSub(d), chevron = true, action = { router.go(Route.DeviceDetail(d.device_id)) })
            }, footer = footer)
        }
    }
}

/** 「平台 · 在线/离线 · 最后在线 … · N 个 provider」（MeViews.swift:178）。 */
private fun deviceSub(d: KnownDevice): String {
    val state = if (d.online) "在线" else "离线 · 最后在线 ${TaskTime.relative(d.last_seen)}"
    return "${d.platform} · $state · ${d.providers.size} 个 provider"
}

// MARK: - 设备详情（MeViews.swift:186）

@Composable
fun DeviceDetailScreen(id: String) {
    val c = UmbraTheme.colors
    val devices by DevicesStore.devices.collectAsState()
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { DevicesStore.load() }
    val d = devices.firstOrNull { it.device_id == id }
    if (d == null) {
        UmbraScreen(title = "设备", onBack = { router.back() }) {
            introItem("这台设备不在列表里了，可能已经被移除。")
        }
        return
    }
    // 「允许在这里直接吩咐它」：工程里是**全局**开关（MeViews.swift:191）—— 一个只存在于这台手机、
    // 服务端不知道的 per-device 开关，价值不足以换一处新的状态源。
    val prefs by AppGraph.prefs.snapshot.collectAsState()
    val allowSend = prefs[Prefs.K.allowDeviceSend] ?: false
    // 「用这台电脑转换文件」（批次 269 §四）：先开系统文件选择器，选完弹面板（电脑已选好）；横条续看也走同一张面板。
    val convert = com.geek.umbra.feature.convert.rememberConvertEntry(d)
    val banners by com.geek.umbra.feature.convert.ConvertCenter.banners.collectAsState()
    UmbraScreen(
        title = d.device_name, onBack = { router.back() }, refreshing = refreshing,
        onRefresh = { scope.launch { refreshing = true; DevicesStore.reload(); com.geek.umbra.feature.convert.ConvertCenter.refresh(); refreshing = false } },
        actions = {
            // 破坏性动作收在右上 ⋯（iosShell.toolbar.noDestructiveAtBottom）；点了仍要再过确认弹窗。
            UmbraMoreMenu(listOf(UmbraMenuItem("从联系人列表移除", danger = true) {
                router.confirm(UmbraAlert(
                    title = "把「${d.device_name}」从联系人列表移除？",
                    message = "它下次上线会重新出现，聊天记录不会删除。",
                    confirmLabel = "移除", destructive = true,
                ) {
                    DevicesStore.forget(d.device_id)
                    router.back()
                    router.showToast("已从联系人列表移除")
                })
            }))
        },
    ) {
        // 顶上那一条「X 转好了」+［去看看］：这台手机发起的、在这台电脑上转完还没看过的那件（批次 269 §四）。
        banners[d.device_id]?.let { b ->
            item(key = "convert-banner") {
                com.geek.umbra.feature.convert.ConvertBannerView(b) {
                    com.geek.umbra.feature.convert.ConvertCenter.seen(b.jobId)
                    convert.present(null, resumeJob = b.jobId)
                }
            }
        }
        item(key = "hero") {
            UmbraHeroCard(
                UmbraIconPaths.monitor, d.device_name,
                badge = if (d.online) "在线" else "离线", badgeOn = d.online,
                sub = if (d.online) "在线中" else "最后在线 ${TaskTime.relative(d.last_seen)}",
            )
        }
        // 远程那一组放最前（§11.2：hero 卡下方就是入口）。远程模块的桩把入口、会话记录、中继用量画在一起。
        item(key = "remote") { com.geek.umbra.feature.remote.RemoteDeviceSections(d) }
        // 「用这台电脑转换文件」：离线时整行置灰、点不动，副行说为什么（转换要那台电脑在线）。
        settingSection("convert", null, listOf(
            UmbraSettingRow(
                AppGraph.context.getString(R.string.convert_entry),
                sub = if (d.online) null else AppGraph.context.getString(R.string.convert_entryOffline),
                tint = if (d.online) null else c.faint, chevron = d.online,
                action = if (d.online) ({ convert.pickFile() }) else null,
            ),
        ))
        settingSection("info", "信息", listOf(
            UmbraSettingRow("平台", value = d.platform),
            UmbraSettingRow("设备 ID", value = d.device_id, mono = true, action = {
                copyPlainText(d.device_id); router.showToast("已复制设备 ID")
            }),
            UmbraSettingRow("最后在线", value = if (d.last_seen == null) "—" else TaskTime.absolute(d.last_seen)),
        ))
        capabilitySection(d)
        settingSection("sess", "会话", listOf(
            UmbraSettingRow(
                "允许在这里直接吩咐设备", sub = "打开后设备会话底部出现输入框", toggle = allowSend,
                onToggle = { AppGraph.prefs.allowDeviceSend = it },
            ),
        ), footer = "关掉时设备会话是只读的 —— 秘书派下来的任务照样推进来，你只是不能在这里直接吩咐它。这个开关对所有设备生效。")
        footnoteItem("系统授权（辅助功能、屏幕录制、computer-use 总开关）是执行设备的属性，服务端目前不上报，要看要改都去电脑上。")
    }
}

/** 「能力」一组（MeViews.swift:266）。 */
private fun androidx.compose.foundation.lazy.LazyListScope.capabilitySection(d: KnownDevice) {
    if (d.providers.isEmpty()) {
        settingSection("caps", "能力", listOf(UmbraSettingRow("（还没上报能力）", enabled = false)), footer = "这台设备还没上报任何能力。")
        return
    }
    settingSection("caps", "能力", d.providers.map { p ->
        UmbraSettingRow(
            p.display_name.ifEmpty { p.provider },
            sub = "${p.kind} · ${p.skills.size} 个技能",
            value = if (p.available) "已就绪" else p.unavailable_reason.ifEmpty { "不可用" },
            chevron = true, action = { router.go(Route.MeCaps) },
        )
    }, footer = "要新增程序或改技能命令模板，去电脑上的「能力」页。")
}

// MARK: - 能力（只读，MeViews.swift:289）

@Composable
fun CapsScreen() {
    val caps by CapsStore.capabilities.collectAsState()
    val loading by CapsStore.loading.collectAsState()
    val failed by CapsStore.failed.collectAsState()
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { CapsStore.load() }
    UmbraScreen(
        title = "能力", onBack = { router.back() }, refreshing = refreshing,
        onRefresh = { scope.launch { refreshing = true; CapsStore.load(); refreshing = false } },
    ) {
        introItem("设备上报的 provider 与 skill，手机上一律只读。要改这些去电脑上的「能力」页。")
        if (caps.isEmpty()) {
            // 「读不出来」和「没有」分开说（台账 capabilities-failure-shown-empty）：拉失败时写「还没有能力上报」，
            // 人会以为电脑那边的引擎出了事，去查一个没坏的东西。
            if (failed && !loading) {
                settingSection("caps", "能力", listOf(UmbraSettingRow("（这次没读到）", enabled = false)),
                    footer = "连不上服务器，或者服务器这次没答上来 —— 不代表设备没有能力。下拉再读一次。")
            } else {
                settingSection("caps", "能力", listOf(UmbraSettingRow(if (loading) "正在读取…" else "（还没有能力上报）", enabled = false)),
                    footer = "设备引擎未就绪或暂无 Provider 时这里会空着。")
            }
            return@UmbraScreen
        }
        // 一台设备的一个 provider = 一组。组头带设备名：两台机器可能都有同名 provider（MeViews.swift:318）。
        caps.forEachIndexed { ci, cap ->
            cap.providers.forEachIndexed { pi, p ->
                settingSection(
                    "p-$ci-$pi", "${p.display_name.ifEmpty { p.provider }} · ${cap.device_name}",
                    if (p.skills.isEmpty()) listOf(UmbraSettingRow("（这个 provider 没有技能）", enabled = false))
                    else p.skills.map { s -> UmbraSettingRow(s.name, sub = s.description.ifEmpty { null }, value = if (p.available) "已就绪" else "不可用") },
                    footer = if (p.available) null else p.unavailable_reason.ifEmpty { "当前不可用。" },
                )
            }
        }
    }
}

// MARK: - 连接（MeViews.swift:340）

@Composable
fun ConnScreen() {
    val c = UmbraTheme.colors
    val ws by AppGraph.socket.state.collectAsState()
    val verdict by AppGraph.http.authVerdict.collectAsState()
    val revoked by AppGraph.auth.revoked.collectAsState()
    val badToken = verdict == UmbraHttp.AuthVerdict.BadToken
    val sessionInvalid = revoked || verdict == UmbraHttp.AuthVerdict.SessionInvalid
    // 服务端不认这台手机带的凭证（总闸 401 或会话失效）：两档的下一步都只有重新登录（MeViews.swift:394）。
    val credentialRejected = sessionInvalid || badToken
    val notRecognized = stringResource(R.string.conn_loginNotRecognized)
    val status = when {
        badToken || sessionInvalid -> notRecognized
        else -> when (ws) {
            SocketState.Online -> "已连接"
            SocketState.Connecting -> "连接中"
            SocketState.Offline -> "未连接"
            SocketState.Unauthorized -> notRecognized
        }
    }
    // 401 那一档必须分成因（MeViews.swift:422）。这两句里不许出现「上面 / 下面」，一律点组名和钮名。
    val why: String? = when {
        badToken -> stringResource(R.string.conn_loginNotRecognizedWhy)
        sessionInvalid -> "HTTP 401 · 服务器在跑，它只是不认这台设备的登录了（多半是在别处改过密码，或者这次登录太久了）。重新登录一次就好。"
        ws == SocketState.Unauthorized -> stringResource(R.string.conn_wsLoginNotRecognizedWhy)
        ws == SocketState.Offline -> "服务没跑、网络不通都会这样。"
        else -> null
    }
    UmbraScreen(title = "连接", onBack = { router.back() }) {
        // 地址只读：不给 chevron、不给点击 —— 一个点了没反应的箭头比没有箭头更糟。留在页面上是为了报障。
        settingSection("srv", "服务端", listOf(
            UmbraSettingRow("服务端地址", value = AppGraph.prefs.serverUrl, mono = true),
            UmbraSettingRow("连接状态", sub = why, value = status),
        ), footer = "服务器地址是这个版本写死的，报障时把它一起报给我们。")
        settingSection("act", null, listOf(
            if (credentialRejected) {
                // 凭证被拒那两档的钮是**重新登录**，不是重连（稿 062：标题、原因、钮三样一起跟着分）。
                UmbraSettingRow("重新登录", tint = c.orangeText, action = { goToLogin() })
            } else {
                UmbraSettingRow("重新连接", tint = c.orangeText, action = {
                    AppGraph.socket.reconnect()
                    router.showToast("正在重连")
                })
            },
        ))
    }
}

/**
 * 「重新登录」（iOS AuthService.goToLogin = signOut）。iOS 文件头那条：不能只 refresh —— 问不到服务器时门是开的，
 * 钮按下去什么都不发生。Android 地基的 `auth.goToLogin()` 只翻单机标记、在「无令牌」时才落登录屏；
 * 凭证被拒时手上还攥着那把服务端已经不认的令牌，所以先 logout（清令牌、落 Out）再 goToLogin，登录屏一定盖上来。
 */
fun goToLogin() {
    AppGraph.scope.launch {
        AppGraph.auth.logout()
        AppGraph.auth.goToLogin()
    }
}

// MARK: - 通知（MeViews.swift:472）

@Composable
fun NotifyScreen() {
    val c = UmbraTheme.colors
    val ctx = AppGraph.context
    // 订阅权限变化（结果回来 / 回到前台）并读它重算状态
    val tick by com.geek.umbra.app.NotificationPermission.tick.collectAsState()
    val status = remember(tick) { com.geek.umbra.app.NotificationPermission.status() }
    var authorized by remember { mutableStateOf(NotificationManagerCompat.from(ctx).areNotificationsEnabled()) }
    androidx.compose.runtime.LaunchedEffect(tick) { authorized = status == com.geek.umbra.app.NotificationPermission.Status.Granted }
    // 回到前台（从系统设置回来）重读一次 —— iOS onAppear 时 refreshAuthorization。
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) authorized = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    UmbraScreen(title = "通知", onBack = { router.back() }) {
        settingSection("perm", "系统权限", listOf(
            // Android 13+ 是 POST_NOTIFICATIONS 运行时权限；12 及以下默认开、用户可在系统设置里关 —— 两种都由 areNotificationsEnabled 说了算。
            UmbraSettingRow("通知权限", value = if (authorized) "已授权" else "未授权"),
            // 没问过时写「开启通知」并当场弹授权框；问过了才跳系统设置（iOS 4bad681）
            UmbraSettingRow(
                if (status == com.geek.umbra.app.NotificationPermission.Status.NotAsked) "开启通知" else "去系统设置",
                tint = c.orangeText, chevron = true, action = { com.geek.umbra.app.NotificationPermission.enableTapped() },
            ),
        ), footer = "推送只是提醒你一下。真正的清单以服务端为准，打开应用时会自动补齐漏掉的。")
        footnoteItem("分类开关（提醒到点 / 任务完成 / 任务失败 / 待确认）要等服务端有推送订阅接口才能做。现在还没有，所以这里不摆一排点了不生效的开关。")
    }
}

/** 本 App 的系统通知设置页（iOS openSettingsURLString）。 */
private fun openNotificationSettings() {
    val ctx = AppGraph.context
    val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(i) }.onFailure {
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

// MARK: - 通用（MeViews.swift:513）

/** 界面语言三档，存 `appLanguage`，取值照 iOS AppLanguage 的 rawValue（system / chinese / english）。 */
enum class AppLanguage(val wire: String) {
    System("system"), Chinese("chinese"), English("english");
    companion object { fun of(s: String?) = entries.firstOrNull { it.wire == s } ?: System }
}

/** 「跟随系统」时实际是哪种（iOS LanguageManager.resolveSystemLanguage）：系统首选英文 → English，其余中文。 */
private fun systemIsEnglish(): Boolean =
    Resources.getSystem().configuration.locales[0]?.language == "en"

/**
 * 切语言：写偏好 + AppCompatDelegate.setApplicationLocales（Android 13+ 走系统的按应用语言，12 及以下由
 * AppCompat 记住，manifest 里已挂 AppLocalesMetadataHolderService autoStoreLocales）。会重建 Activity，立即生效。
 */
fun applyLanguage(l: AppLanguage) {
    AppGraph.prefs.set(Prefs.K.language, l.wire)
    val list = when (l) {
        AppLanguage.System -> LocaleListCompat.getEmptyLocaleList()
        AppLanguage.Chinese -> LocaleListCompat.forLanguageTags("zh-CN")
        AppLanguage.English -> LocaleListCompat.forLanguageTags("en")
    }
    AppCompatDelegate.setApplicationLocales(list)
}

/** 「外观」那一行的值：外观档 · 主题名（两个独立选项，主题插件化二期；iOS MeViews.swift `appearanceValue`）。 */
@Composable
internal fun appearanceValue(): String {
    val prefs by AppGraph.prefs.snapshot.collectAsState()
    val applied by com.geek.umbra.feature.theme.ThemeStore.applied.collectAsState()
    return "${Appearance.of(prefs[Prefs.K.appearance]).label()} · ${com.geek.umbra.feature.theme.ThemeStore.shownName(applied.id, applied.name)}"
}

/** 外观的显示名（iOS UmbraAppearance.label）。 */
internal fun Appearance.label() = when (this) {
    Appearance.Light -> "浅色"
    Appearance.Dark -> "深色"
    Appearance.System -> "跟随系统"
}

@Composable
fun GeneralScreen() {
    val prefs by AppGraph.prefs.snapshot.collectAsState()
    val settings by ServerSettings.settings.collectAsState()
    val tiers by ServerSettings.imageTiers.collectAsState()
    // 这一页原来不碰设置镜像：**不拉的话那一行永远是「还没拉到」**（MeViews.swift:542）。
    LaunchedEffect(Unit) { if (ServerSettings.settings.value == null) ServerSettings.load() }
    val lang = AppLanguage.of(prefs[Prefs.K.language])
    val look = appearanceValue()
    // 「跟随系统」要标出实际解析成了哪种 —— 只写「跟随系统」看不出现在到底是中文还是英文（MeViews.swift:549）。
    val langLabel = when (lang) {
        AppLanguage.Chinese -> "中文"
        AppLanguage.English -> "English"
        AppLanguage.System -> if (systemIsEnglish()) "跟随系统 · English" else "跟随系统 · 中文"
    }
    UmbraScreen(title = "通用", onBack = { router.back() }) {
        settingSection("look", "外观与语言", listOf(
            UmbraSettingRow("界面语言", value = langLabel, chevron = true, action = {
                // 设计稿只列了中文 / English，多一项「跟随系统」—— 系统本来就有这一档，藏起来等于把已有能力关掉。
                UmbraChoice.present(
                    title = "界面语言",
                    items = listOf(
                    UmbraChoiceItem("中文", checked = lang == AppLanguage.Chinese) { applyLanguage(AppLanguage.Chinese) },
                    UmbraChoiceItem("English", checked = lang == AppLanguage.English) { applyLanguage(AppLanguage.English) },
                    UmbraChoiceItem("跟随系统", checked = lang == AppLanguage.System) { applyLanguage(AppLanguage.System) },
                ),
                    subtitle = "切换后立即生效。",
                )
            }),
            // 外观（含主题）挪成了一页（主题插件化二期，iOS MeViews.swift:565）：这一行推过去，不再就地弹三档。
            UmbraSettingRow("外观", value = look, chevron = true, action = { router.go(Route.SetAppearance) }),
        ), footer = "切换语言后立即生效；跟随系统时会随日夜自动切换。")
        // 发图画质自成一组：组尾那三句只跟它有关，挂在「外观与语言」下面会解释错上面两行（MeViews.swift:532）。
        settingSection("img", AppGraph.context.getString(R.string.imgQ_secSend), listOf(
            imageQualityRow(ImagePurpose.Chat, AppGraph.context.getString(R.string.imgQ_rowChat), settings, tiers),
        ), footer = AppGraph.context.getString(R.string.imgQ_foot))
    }
}

// MARK: - 关于（MeViews.swift:601）

@Composable
fun AboutScreen() {
    // 版本号从构建配置读，**不写死** —— 写死的版本号在下一次发版时一定忘了改（MeViews.swift:619）。
    val version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android"
    UmbraScreen(title = "关于", onBack = { router.back() }) {
        settingSection("about", null, listOf(
            UmbraSettingRow("Umbra", value = version),
            UmbraSettingRow("客户端 ID", value = AppGraph.prefs.clientId, mono = true, action = {
                copyPlainText(AppGraph.prefs.clientId); router.showToast("已复制")
            }),
        ))
        footnoteItem("手机端只做「接头与随手记」：收通知、随手记、随手查、随手答一句。要坐下来配的东西在电脑上做。")
    }
}
