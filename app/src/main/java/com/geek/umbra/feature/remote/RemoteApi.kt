// 远程控制的入口 —— 照搬 iOS `Features/Remote/RemoteEntryViews.swift`（契约 §14.7 / 需求 §11.2 第 1 条）：
//   · 设备详情：「远程查看」「远程控制」「看一眼」三行 + 「远程会话记录」+ 本月中继用量（[RemoteDeviceSections]）；
//   · 设备会话的导航栏：一颗显示器图标（菜单里同样三项，[RemoteDeviceMenu]）；
//   · 求助卡「实时接管」（source=locate, ask_id）与任务详情「看实时画面」（source=task, run_id）—— 在聊天 / 任务模块里，
//     发起走 [RemoteLauncher.launch]（桩签名只给 deviceId，这里自己去取设备再过闸）；
//   · 全屏会话层 [RemoteSessionHost]：外壳最上层调一次（iOS fullScreenCover）。
//
// 能不能点、点了说什么，全由 [RemoteEntryRules.gate]（纯判据、有用例）判：离线 / 没开 / 缺权限 / 只能看 / 非会员。
// 非会员置灰 + 点了跳商城（Route.SetShop）。
package com.geek.umbra.feature.remote

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
                else -> AppGraph.router.showToast(str(R.string.remote_gate_offline))
            }
        }
    }

    /** 已经拿着设备的入口（设备详情 / 设备会话导航栏）。 */
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
            is RemoteGate.Disabled -> AppGraph.router.showToast(g.text ?: remoteString(g.key))
        }
    }

    /** 一行入口的副行：能点不写；不能点写那一句原因（§11.2：「置灰 + 一句原因」）。 */
    internal fun reason(entry: RemoteEntry, device: KnownDevice, member: Boolean?): String? =
        when (val g = RemoteEntryRules.gate(entry, device.online, device.remoteManifest, member)) {
            RemoteGate.Ok -> null
            RemoteGate.NeedMembership -> str(R.string.remote_gate_need_membership)
            is RemoteGate.Disabled -> g.text ?: remoteString(g.key)
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
 * 设备详情里的「远程」组 + 会话记录 + 本月中继用量（iOS: RemoteEntryViews.swift:99 `RemoteDeviceSections`）。
 * 调用方放在自己的列表 item 里；这里画成几组纵向排开的设置分组（形制同 `settingSection`：组头 + 分组卡 + 组尾）。
 */
@Composable
fun RemoteDeviceSections(device: KnownDevice) {
    val m = remember(device.device_id) { RemoteDeviceModel() }
    LaunchedEffect(device.device_id) { m.load(device.device_id) }
    val c = UmbraTheme.colors

    val entryRows = listOf(
        Triple(RemoteEntry.View, R.string.remote_entry_view, R.string.remote_entry_viewSub),
        Triple(RemoteEntry.Control, R.string.remote_entry_control, R.string.remote_entry_controlSub),
        Triple(RemoteEntry.Peek, R.string.remote_entry_peek, R.string.remote_entry_peekSub),
    ).map { (e, label, sub) ->
        val why = RemoteLauncher.reason(e, device, m.member)
        UmbraSettingRow(
            label = stringResource(label), sub = why ?: stringResource(sub),
            tint = if (why == null) c.orangeText else c.faint, chevron = why == null,
            action = { RemoteLauncher.launch(e, device) },
        )
    }

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
        RemoteSection(stringResource(R.string.remote_section_title), entryRows, stringResource(R.string.remote_section_footer))
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

// MARK: - 设备会话导航栏那颗显示器

/** 设备会话右上角：一颗显示器图标，菜单里三项（同设备详情）。不能点的那一项点了吐那一句原因（iOS: RemoteEntryViews.swift:180）。 */
@Composable
fun RemoteDeviceMenu(device: KnownDevice) {
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { RemoteLauncher.refreshMembership() }
    val c = UmbraTheme.colors
    Box {
        UmbraTopBarIcon(UmbraIconPaths.deviceDesktop, stringResource(R.string.remote_menu_a11y), { open = true }, tint = c.orange)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.card, modifier = Modifier.widthIn(min = 180.dp)) {
            listOf(
                Triple(RemoteEntry.View, R.string.remote_entry_view, UmbraIconPaths.eye),
                Triple(RemoteEntry.Control, R.string.remote_entry_control, RemoteIcons.pointer),
                Triple(RemoteEntry.Peek, R.string.remote_entry_peek, RemoteIcons.viewfinder),
            ).forEach { (e, label, icon) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label), color = c.text) },
                    leadingIcon = { UmbraIcon(icon, c.text, size = 18.dp) },
                    onClick = { open = false; RemoteLauncher.launch(e, device) },
                )
            }
        }
    }
}

// MARK: - 全屏会话层

/** 全屏会话层，外壳最上层调一次（iOS fullScreenCover(item:)）。没有会话时什么都不画。 */
@Composable
fun RemoteSessionHost() {
    val client by RemoteCenter.client.collectAsState()
    val c = client ?: return
    androidx.compose.runtime.key(c.id) { RemoteSessionView(c) }
}
