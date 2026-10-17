// 远程控制的入口 —— 照搬 iOS `Features/Remote/RemoteEntryViews.swift`（契约 §14.7 / 需求 §11.2 第 1 条；
// 形制照设计侧批次 262 §「两处裁定」与 264，iOS 稿《批次 262 · 远程控制 iOS》01）：
//   · 设备详情：hero 卡下两颗钮 ——「看一眼」次钮 +「远程连接」主钮（点开动作表：远程查看 / 远程控制）。
//     不用三行设置行：设置行读成「去改设置」，而这是这一页的主动作。整台连不了时两颗都灰，原因写在钮下；
//     「远程会话记录」+ 本月中继用量照旧在后面（[RemoteDeviceSections]：设备详情页把它放在 hero 卡紧下面那一格）；
//   · 设备会话的导航栏：一颗显示器图标，弹的是同一张动作表（[RemoteDeviceMenu]）；
//   · 求助卡「实时接管」（source=locate, ask_id）直接请求控制、任务详情「看实时画面」（source=task, run_id）直接请求查看 ——
//     在聊天 / 任务模块里，发起走 [RemoteLauncher.launch]（桩签名只给 deviceId，这里自己去取设备再过闸）；
//   · 全屏会话层 [RemoteSessionHost]：外壳最上层调一次（iOS fullScreenCover）。
//
// 能不能点、点了说什么，全由 [RemoteEntryRules.deviceGate] / [RemoteEntryRules.gate]（纯判据、有用例）判：
// 离线 / 没开 / 缺屏幕录制 / 非会员三颗全灰，缺辅助功能只灰「远程控制」。非会员（批次 264：看一眼也要）原因后面跟
// 「去开通会员」，跳商城（Route.SetShop）。
package com.geek.umbra.feature.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import com.geek.umbra.designsystem.UmbraRemoteColors
import com.geek.umbra.designsystem.UmbraRemoteDimens
import com.geek.umbra.designsystem.UmbraRemoteType
import com.geek.umbra.designsystem.UmbraStripAction
import com.geek.umbra.navigation.UmbraSheet
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraGroupCard
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraRowDivider
import com.geek.umbra.designsystem.UmbraSectionLabel
import com.geek.umbra.designsystem.UmbraSettingRow
import com.geek.umbra.designsystem.UmbraSettingRowView
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraTone
import com.geek.umbra.designsystem.UmbraTopBarIcon
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.model.KnownDevice
import com.geek.umbra.navigation.Route
import com.geek.umbra.net.BackgroundWork
import com.geek.umbra.net.HttpResult
import com.geek.umbra.net.UmbraJson
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// MARK: - 设备上报的 remote manifest

/** 这台设备的 `remote` provider（§14.1）。没上报 = null（没开远程访问 / PC 版本太旧，入口按「没开」说）。 */
val KnownDevice.remoteManifest: RemoteManifest?
    get() {
        val p = remote ?: return null
        return RemoteManifest(
            available = p.available, reason = p.unavailable_reason, text = p.unavailable_text ?: "",
            skills = p.skills.map { it.name }, control = p.features?.control, unattended = p.features?.unattended,
            displays = (p.displays ?: emptyList()).filter { it.id.isNotEmpty() }.map {
                RemoteDisplayInfo(it.id, it.name ?: "", it.primary ?: false, it.width ?: 0, it.height ?: 0)
            },
        )
    }

// MARK: - 发起

/** 四个入口共用的「按了之后」：过闸 → 发起 / 跳商城 / 说为什么不能（iOS: RemoteEntryViews.swift:30）。 */
object RemoteLauncher {
    /** 会员状态的本进程缓存（null = 还没问到）。设备详情 / 设备会话导航栏每次出现时刷新一次；没问到不挡，服务端发起时还会判。 */
    @Volatile var member: Boolean? = null

    /** `GET /membership`：`member` 字段非空即会员（iOS `m.member != nil`）。没问到保持原值。 */
    suspend fun refreshMembership() {
        val r = withContext(BackgroundWork) { AppGraph.http.raw("GET", "/membership") }
        if (r !is HttpResult.Ok) return
        val o = runCatching { UmbraJson.parseToJsonElement(r.body).jsonObject }.getOrNull() ?: return
        member = o["member"].let { it != null && it !is JsonNull }
    }

    /**
     * 桩签名：别的模块只知道 deviceId（求助卡 / 任务详情）。先取 `/devices/all` 找到那台再过同一道闸。
     * 设备表**没拉到**（null）时不在本地挡，直接发起 —— 发不出去会当场说「没连上服务器」，服务端拒了 rc_error 也说得清；
     * 拉到了却没有这台，按「对方离线」说（那台已经不在这个账号的设备里了）。
     */
    fun launch(deviceId: String, mode: String, source: String = "manual", runId: String? = null, askId: String? = null) {
        val entry = RemoteEntryRules.entry(mode, source)
        AppGraph.scope.launch {
            if (member == null) refreshMembership()
            val all = AppGraph.http.get("/devices/all", ListSerializer(KnownDevice.serializer()))
            val d = all?.firstOrNull { it.device_id == deviceId }
            when {
                d != null -> launch(entry, d, source, runId, askId)
                all == null -> RemoteCenter.start(RemoteLaunch(deviceId, "", RemoteEntryRules.request(entry).mode, source, runId, askId))
                // 设备表里已经没有这台：连名字都拿不到，说「对方离线」（拒绝码那一句，不带名字）。
                else -> AppGraph.router.showToast(str(R.string.remote_err_offline))
            }
        }
    }

    /** 已经拿着设备的入口（设备详情 / 设备会话导航栏 / 动作表里那两项）。 */
    internal fun launch(entry: RemoteEntry, device: KnownDevice, source: String? = null, runId: String? = null, askId: String? = null) {
        when (val g = RemoteEntryRules.gate(entry, device.online, device.remoteManifest, member)) {
            RemoteGate.Ok -> {
                val r = RemoteEntryRules.request(entry)
                RemoteCenter.start(RemoteLaunch(device.device_id, device.device_name, r.mode, source ?: r.source, runId, askId, device.remoteManifest))
            }
            RemoteGate.NeedMembership -> {
                AppGraph.router.showToast(str(R.string.remote_gate_need_membership))
                AppGraph.router.jump(Route.SetShop)
            }
            is RemoteGate.Disabled -> AppGraph.router.showToast(g.text ?: copy(g.key, device))
        }
    }

    /** 不能点的那一句（批次 262 置灰原因表）：能点回 null（iOS: RemoteEntryViews.swift:56）。 */
    internal fun reason(gate: RemoteGate, device: KnownDevice): String? = when (gate) {
        RemoteGate.Ok -> null
        RemoteGate.NeedMembership -> str(R.string.remote_gate_need_membership)
        is RemoteGate.Disabled -> gate.text ?: copy(gate.key, device)
    }

    /** gate key → 一句话。离线那句带设备名（「「书房 Mac」离线，上线后才能连」）。 */
    private fun copy(key: String, device: KnownDevice): String =
        if (key in RemoteCopy.gateKeysWithName) remoteString(key, device.device_name) else remoteString(key)

    /**
     * 「远程连接」那张动作表（设备详情的主钮、设备会话导航栏的显示器图标共用 —— 批次 262：同一张；iOS: RemoteEntryViews.swift:72）。
     * 两项：远程查看 / 远程控制，项下一句说明；不可用的那项置灰、原因写在项下。查看和控制对方看到的是两张不同的同意卡，
     * 放进动作表是为了每次都由人明确选档。整台连不了时不弹这张（两颗钮本来就灰着），点到了也只吐那一句原因。
     * Android：走 Router 的底部弹层（平台差异答复第 6 条），项的形制照 iOS UmbraSheetLayer（标题 13/600、项 16、项下 12.5 muted）。
     */
    fun presentConnect(device: KnownDevice) {
        val whole = RemoteEntryRules.deviceGate(device.online, device.remoteManifest, member)
        if (whole != RemoteGate.Ok) {
            if (whole == RemoteGate.NeedMembership) launch(RemoteEntry.View, device)
            else AppGraph.router.showToast(reason(whole, device) ?: "")
            return
        }
        val items = listOf(
            Triple(RemoteEntry.View, R.string.remote_entry_view, R.string.remote_entry_viewSub),
            Triple(RemoteEntry.Control, R.string.remote_entry_control, R.string.remote_entry_controlSub),
        ).map { (e, label, sub) ->
            val why = reason(RemoteEntryRules.gate(e, device.online, device.remoteManifest, member), device)
            ConnectItem(str(label), why ?: str(sub), disabled = why != null) { launch(e, device) }
        }
        val title = AppGraph.context.getString(R.string.remote_entry_sheetTitle, device.device_name)
        AppGraph.router.present(UmbraSheet("remote-connect-${System.nanoTime()}") { dismiss -> ConnectSheet(title, items, dismiss) })
    }

    private fun str(id: Int) = AppGraph.context.getString(id)
}

// MARK: - 设备详情里的那几组

/** 设备详情页「远程」那几组要的数据：会话记录、本月中继用量、会员状态（iOS: RemoteEntryViews.swift:80）。 */
private class RemoteDeviceModel {
    /** null = 还没问到 / 没问到（和「一条都没有」分开说）。 */
    var sessions by mutableStateOf<List<RemoteSessionRecord>?>(null)
    var relay by mutableStateOf<RemoteRelayStatus?>(null)
    var loaded by mutableStateOf(false)
    var member by mutableStateOf<Boolean?>(null)

    suspend fun load(deviceId: String) = coroutineScope {
        RemoteLauncher.refreshMembership()
        member = RemoteLauncher.member
        val s = async { fetchSessions(deviceId) }
        val r = async { fetchRelay() }
        sessions = s.await()
        relay = r.await()
        loaded = true
    }

    /** 这台设备的远程会话记录（最近 30 天，新的在前）。null = 没问到（断网 / 服务端没开远程控制 503）。 */
    private suspend fun fetchSessions(deviceId: String): List<RemoteSessionRecord>? {
        val r = AppGraph.http.raw("GET", "/remote/sessions", mapOf("days" to 30, "device_id" to deviceId))
        if (r !is HttpResult.Ok) return null
        return runCatching { UmbraJson.parseToJsonElement(r.body).jsonObject }.getOrNull()?.let(RemoteSessionRecord::parseList)
    }

    /** 本月中继用量。null = 没问到 / 服务端没开远程控制（设备详情就不画那一组）。 */
    private suspend fun fetchRelay(): RemoteRelayStatus? {
        val r = AppGraph.http.raw("GET", "/remote/relay")
        if (r !is HttpResult.Ok) return null
        return runCatching { UmbraJson.parseToJsonElement(r.body).jsonObject }.getOrNull()?.let(RemoteRelayStatus::parse)
    }
}

/**
 * 设备详情里 hero 卡下那两颗 + 会话记录 + 本月中继用量（iOS: RemoteEntryViews.swift:118 `RemoteDeviceSections.history`
 * + :170 `RemoteHeroActions`）。调用方（我 › 设备详情）把它放在 hero 卡紧下面那一个列表 item 里 —— iOS 把两颗钮塞进 hero 卡那一格，
 * Android 的设备详情不归这个模块改，入口就画在这一格的最前面，排出来的位置一样。下面几组形制同 `settingSection`。
 */
@Composable
fun RemoteDeviceSections(device: KnownDevice) {
    val m = remember(device.device_id) { RemoteDeviceModel() }
    LaunchedEffect(device.device_id) { m.load(device.device_id) }
    val c = UmbraTheme.colors

    // 会话记录（最近 30 天，RC-12.2）：只有时间、档位、时长、直连 / 中继 —— 服务端本来就不记画面和输入。
    val historyRows = mutableListOf<UmbraSettingRow>()
    val list = m.sessions
    if (list != null) {
        if (list.isEmpty()) historyRows += UmbraSettingRow(stringResource(R.string.remote_history_empty), tint = c.faint)
        val now = System.currentTimeMillis()
        for (r in list.take(20)) {
            historyRows += UmbraSettingRow(
                label = whenText(r.startedAtMs), sub = recordSub(r, now),
                value = stringResource(if (r.everControlled) R.string.remote_history_control else R.string.remote_history_view),
            )
        }
    } else {
        historyRows += UmbraSettingRow(stringResource(if (m.loaded) R.string.remote_history_failed else R.string.remote_history_loading), tint = c.faint)
    }

    Column(verticalArrangement = Arrangement.spacedBy(UmbraDimens.groupGap)) {
        // hero 卡下那两颗（批次 262）：设备详情页把这一格排在 hero 卡紧下面，所以入口就画在最前。
        RemoteHeroActions(device, m.member)
        RemoteSection(stringResource(R.string.remote_history_title), historyRows, stringResource(R.string.remote_history_footer))
        m.relay?.let { relay ->
            val used = RemoteBytes.format(relay.usedBytes, binary = true)
            val limit = RemoteBytes.format(relay.limitBytes, binary = true)
            RemoteSection(
                stringResource(R.string.remote_relay_title),
                listOf(
                    UmbraSettingRow(stringResource(R.string.remote_relay_used), value = "$used / $limit", mono = true),
                    UmbraSettingRow(stringResource(R.string.remote_relay_buy), tint = c.orangeText, chevron = true, action = { AppGraph.router.jump(Route.SetShop) }),
                ),
                stringResource(if (relay.turnConfigured) R.string.remote_relay_footer else R.string.remote_relay_noTurn),
                footerTone = if (relay.fraction >= 0.9) UmbraTone.Warning else null,
            )
        }
    }
}

/** 一组：组头 + 分组卡 + 组尾。版式逐项照 designsystem 的 `settingSection`（那个是 LazyListScope 扩展，这里在别人的 item 里画）。 */
@Composable
private fun RemoteSection(header: String, rows: List<UmbraSettingRow>, footer: String?, footerTone: UmbraTone? = null) {
    val c = UmbraTheme.colors
    Column {
        UmbraSectionLabel(header)
        Spacer(Modifier.height(6.dp))
        UmbraGroupCard {
            rows.forEachIndexed { i, r ->
                if (i > 0) UmbraRowDivider(Modifier.padding(start = 14.dp))
                UmbraSettingRowView(r)
            }
        }
        if (footer != null) {
            Spacer(Modifier.height(6.dp))
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.Top) {
                if (footerTone != null) {
                    UmbraIcon(UmbraIconPaths.alertTriangle, if (footerTone == UmbraTone.Danger) c.dangerText else c.warningText, size = 13.dp, modifier = Modifier.padding(top = 2.dp))
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    footer,
                    color = when (footerTone) { UmbraTone.Danger -> c.dangerText; UmbraTone.Warning -> c.warningText; else -> c.muted },
                    style = TextStyle(fontSize = if (footerTone != null) 13.sp else 12.sp, lineHeight = 17.sp),
                )
            }
        }
    }
}

/** 「10月17日 14:05」（iOS `.dateTime.month().day().hour().minute()`）。 */
private fun whenText(ms: Long): String {
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "MMMdHHmm")
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ms))
}

/** 「sam 的 iPhone · 12 分钟 · 中继 · 对方结束」（iOS: RemoteEntryViews.swift:157）。 */
private fun recordSub(r: RemoteSessionRecord, now: Long): String {
    val ctx = AppGraph.context
    val parts = mutableListOf<String>()
    if (r.controllerName.isNotEmpty()) parts += r.controllerName
    val (minutes, hours) = RemoteSessionRecord.durationParts(r.durationSec(now))
    parts += when {
        hours > 0 -> ctx.getString(R.string.remote_history_hm, hours.toInt(), minutes.toInt())
        minutes > 0 -> ctx.getString(R.string.remote_history_m, minutes.toInt())
        else -> ctx.getString(R.string.remote_history_lt1m)
    }
    when (r.path) {
        "relay" -> parts += ctx.getString(R.string.remote_path_relay)
        "direct" -> parts += ctx.getString(R.string.remote_path_direct)
        "snapshot" -> parts += ctx.getString(R.string.remote_path_snapshot)
    }
    if (r.state != "ended") parts += ctx.getString(R.string.remote_history_live)
    else if (r.endReason.isNotEmpty()) parts += remoteString(RemoteCopy.endReasonKey(r.endReason))
    if (r.fileCount > 0) parts += ctx.getString(R.string.remote_history_files, r.fileCount)
    return parts.joinToString(" · ")
}

// MARK: - 设备详情 hero 卡下那两颗

/**
 * hero 卡下两颗等分（批次 262 §「两处裁定」；iOS: RemoteEntryViews.swift:170 `RemoteHeroActions`）：「看一眼」次钮 +「远程连接」主钮（动作表）。
 * 整台连不了（离线 / 没开远程访问 / 缺屏幕录制 / 非会员）时两颗都灰，原因写在钮下；非会员再跟一行「去开通会员」。
 * `member` 由调用方传（[RemoteDeviceModel.member] 是 Compose 状态）：不读 [RemoteLauncher.member] —— 那是个静态缓存、改了界面不会跟着画。
 */
@Composable
private fun RemoteHeroActions(device: KnownDevice, member: Boolean?) {
    val c = UmbraTheme.colors
    val whole = RemoteEntryRules.deviceGate(device.online, device.remoteManifest, member)
    val off = whole != RemoteGate.Ok
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HeroButton(stringResource(R.string.remote_entry_peek), UmbraIconPaths.eye, primary = false, disabled = off, Modifier.weight(1f)) {
                RemoteLauncher.launch(RemoteEntry.Peek, device)
            }
            HeroButton(stringResource(R.string.remote_entry_connect), UmbraIconPaths.deviceDesktop, primary = true, disabled = off, Modifier.weight(1f)) {
                RemoteLauncher.presentConnect(device)
            }
        }
        RemoteLauncher.reason(whole, device)?.let { why ->
            Text(why, color = c.muted, style = UmbraRemoteType.heroReason)
            if (whole == RemoteGate.NeedMembership) {
                UmbraStripAction(stringResource(R.string.remote_gate_join), c.orangeText, { AppGraph.router.jump(Route.SetShop) })
            }
        }
    }
}

/** 50 高等分钮（稿：主钮实心橙、次钮 card 底 1px 描边；置灰整颗 45% 不透明、点不动）。 */
@Composable
private fun HeroButton(title: String, icon: String, primary: Boolean, disabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(UmbraRemoteDimens.heroButtonRadius)
    val fg = if (primary) UmbraRemoteColors.fg else c.text
    Row(
        modifier.heightIn(min = UmbraRemoteDimens.heroButtonH).alpha(if (disabled) UmbraRemoteColors.heroDisabledAlpha else 1f)
            .clip(shape).background(if (primary) c.orange else c.card)
            .then(if (primary) Modifier else Modifier.border(UmbraDimens.borderW, c.border, shape))
            .clickable(enabled = !disabled, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UmbraIcon(icon, fg, size = UmbraRemoteDimens.heroIcon, strokeWidth = 2f)
        Spacer(Modifier.width(7.dp))
        Text(title, color = fg, style = UmbraRemoteType.heroButton)
    }
}

// MARK: - 「远程连接」动作表

/** 动作表的一项：标签 + 项下一句（说明 / 置灰原因）+ 置灰（iOS UmbraSheetItem 的 sub / disabled，批次 262）。 */
private class ConnectItem(val label: String, val sub: String, val disabled: Boolean, val action: () -> Unit)

/**
 * 「连到「X」」那张表（形制照 iOS `UmbraSheetLayer`：标题 13/600 → 项最小高 52、发丝线分隔、项下小字 muted → 取消胶囊）。
 * 置灰的项点不动，原因就写在项下 —— 不用「点了吐一句原因」：动作表里看得见的灰项，人一眼就知道为什么不能点。
 */
@Composable
private fun ConnectSheet(title: String, items: List<ConnectItem>, dismiss: () -> Unit) {
    val c = UmbraTheme.colors
    Column(Modifier.fillMaxWidth().padding(bottom = UmbraDimens.sp3)) {
        Text(title, color = c.text, style = UmbraRemoteType.sheetTitle, modifier = Modifier.padding(start = UmbraDimens.pagePadX, end = UmbraDimens.pagePadX, top = UmbraDimens.sp5, bottom = UmbraDimens.sp4))
        items.forEach { item ->
            UmbraRowDivider()
            Column(
                Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    .clickable(enabled = !item.disabled, role = Role.Button) { dismiss(); item.action() }
                    .padding(horizontal = UmbraDimens.pagePadX, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(item.label, color = if (item.disabled) c.faint else c.text, style = UmbraRemoteType.sheetItem)
                // 项下那一句（置灰原因写在项下面）是要读到的话 → muted，不是 faint（设计侧 227 §二2）。
                Text(item.sub, color = c.muted, style = UmbraRemoteType.sheetSub)
            }
        }
        // 取消：独立胶囊，text 色、600；不用橙 —— 取消不是要推销的动作。
        Box(
            Modifier.padding(start = UmbraDimens.pagePadX, end = UmbraDimens.pagePadX, top = UmbraDimens.sp4).fillMaxWidth().height(50.dp)
                .clip(RoundedCornerShape(50)).background(c.chip).clickable(role = Role.Button, onClick = dismiss),
            contentAlignment = Alignment.Center,
        ) { Text("取消", color = c.text, style = UmbraRemoteType.sheetCancel) }
    }
}

// MARK: - 设备会话导航栏那颗显示器

/** 设备会话右上角：一颗显示器图标，点开是和设备详情「远程连接」同一张动作表（批次 262；iOS: RemoteEntryViews.swift:244）。 */
@Composable
fun RemoteDeviceMenu(device: KnownDevice) {
    LaunchedEffect(Unit) { RemoteLauncher.refreshMembership() }
    UmbraTopBarIcon(UmbraIconPaths.deviceDesktop, stringResource(R.string.remote_menu_a11y), { RemoteLauncher.presentConnect(device) }, tint = UmbraTheme.colors.orange)
}

// MARK: - 全屏会话层

/** 全屏会话层，外壳最上层调一次（iOS fullScreenCover(item:)）。没有会话时什么都不画。 */
@Composable
fun RemoteSessionHost() {
    val client by RemoteCenter.client.collectAsState()
    val c = client ?: return
    androidx.compose.runtime.key(c.id) { RemoteSessionView(c) }
}
