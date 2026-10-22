// 聊天 · 会话列表（iOS: Features/Chat/ChatContactsView.swift）。聊天 Tab 根页。
//
// 数据全部来自 ChatStore：联系人 / 预览 / 未读 / 长连接状态。没有 mock —— 连不上时就是空预览 + 离线态。
// 取值：分区标题 600/12；行 48 头像（秘书圆形橙底、设备圆角 14 chip 底）、图标 22、名字 560/16、在线徽标 560/11。
package com.geek.umbra.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraGroupCard
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraRevealSwipeRow
import com.geek.umbra.designsystem.UmbraRowDivider
import com.geek.umbra.designsystem.UmbraScreen
import com.geek.umbra.designsystem.UmbraStatusDot
import com.geek.umbra.designsystem.UmbraSwipeAction
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraType
import com.geek.umbra.feature.me.DevicesStore
import com.geek.umbra.navigation.Route
import com.geek.umbra.navigation.UmbraAlert
import com.geek.umbra.net.SocketState
import kotlinx.coroutines.launch
import java.net.URI

@Composable
fun ChatContactsPage() {
    val c = UmbraTheme.colors
    val devices by ChatStore.devices.collectAsState()
    val previews by ChatStore.previews.collectAsState()
    val unread by ChatStore.unread.collectAsState()
    val ws by AppGraph.socket.state.collectAsState()
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { DevicesStore.load() }

    UmbraScreen(
        title = "聊天",
        refreshing = refreshing,
        onRefresh = { scope.launch { refreshing = true; DevicesStore.reload(); refreshing = false } },
    ) {
        item(key = "head") {
            // 分区标题行：左「联系人」，右侧是连接状态 —— 状态不占一整行
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("联系人", color = c.faint, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.06.em))
                Spacer(Modifier.weight(1f))
                ConnChip(ws)
            }
        }
        item(key = "list") {
            UmbraGroupCard {
                val contacts = ChatStore.contacts(devices)
                contacts.forEachIndexed { i, conv ->
                    if (i > 0) UmbraRowDivider()
                    ContactRow(conv, devices.firstOrNull { it.conversation == conv }, previews[conv], conv in unread)
                }
            }
        }
    }
}

/**
 * 连接状态小组件。⚠️「未连接」和「登录失效」不许合成一句：前者是等，后者是去「我 › 连接」重新登录。
 * 只显示 host（+ 端口），不显示协议。
 */
@Composable
private fun ConnChip(ws: SocketState) {
    val c = UmbraTheme.colors
    val online = ws == SocketState.Online
    val label = when (ws) {
        SocketState.Online -> "已连接"
        SocketState.Connecting -> "连接中"
        SocketState.Offline -> "未连接"
        SocketState.Unauthorized -> "登录失效 · 去「我 › 连接」"
    }
    val server = remember { serverHost(AppGraph.http.server) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
        UmbraStatusDot(online, if (online) c.success else c.muted)
        Spacer(Modifier.width(6.dp))
        Text(
            "$label · $server", color = if (online) c.success else c.muted, style = TextStyle(fontSize = 12.sp),
            maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
        )
    }
}

private fun serverHost(raw: String): String {
    val u = runCatching { URI(raw) }.getOrNull() ?: return raw
    val h = u.host ?: return raw
    return if (u.port > 0) "$h:${u.port}" else h
}

@Composable
private fun ContactRow(conv: String, dev: com.geek.umbra.model.KnownDevice?, preview: ConvPreview?, unread: Boolean) {
    val c = UmbraTheme.colors
    val isSecretary = conv == ChatStore.MAIN_CONV
    val row: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().background(c.card)
                .clickable {
                    ChatStore.switchConversation(conv)
                    AppGraph.router.go(Route.ChatThread(conv))
                }
                .defaultMinSize(minHeight = UmbraDimens.rowMinH)
                .padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(isSecretary, dev?.online ?: false)
            Spacer(Modifier.width(UmbraDimens.sp4))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ChatStore.convLabel(conv), color = c.text, style = TextStyle(fontSize = 16.sp, fontWeight = UmbraType.weight560), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    // 在线：绿点 +「已连接」文字；离线只有空心圈 —— 状态不只靠颜色
                    if (dev != null && dev.online) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(c.success))
                        Spacer(Modifier.width(4.dp))
                        Text("已连接", color = c.success, style = TextStyle(fontSize = 11.sp, fontWeight = UmbraType.weight560))
                    } else if (dev != null) {
                        Box(Modifier.size(7.dp).clip(CircleShape).border(1.5.dp, c.faint, CircleShape))
                    }
                    Spacer(Modifier.weight(1f))
                    Text(ChatTimeRules.label(preview?.at), color = c.faint, style = TextStyle(fontSize = 12.sp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 预览单行：换行压成空格
                    val pv = preview?.text?.takeIf { it.isNotEmpty() }?.replace("\n", " ")
                        ?: if (isSecretary) "你的 AI 秘书" else "还没有对话"
                    Text(pv, color = c.muted, style = UmbraType.rowSub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    if (unread) {
                        // 未读用圆点而不是数字（只记有没有，编不出一个真实的条数）；颜色橙 —— 未读不是错误（设计侧 191）
                        Box(Modifier.size(8.dp).clip(CircleShape).background(c.orange))
                    } else if (dev != null && !dev.online && dev.last_seen != null) {
                        Text(stringResource(R.string.chat_device_lastSeen, ChatTimeRules.label(dev.last_seen)), color = c.faint, style = TextStyle(fontSize = 12.sp), maxLines = 1)
                    }
                }
            }
        }
    }
    if (dev == null) { row(); return }
    // 只有设备行能左滑：露出「移除」钮（公共件，拖到底不执行），点了再过确认（破坏性操作必过确认）。
    val remove = UmbraSwipeAction("移除", UmbraIconPaths.trash, c.danger, c.onDanger) {
        AppGraph.router.confirm(
            UmbraAlert(
                title = "从联系人移除「${ChatStore.convLabel(conv)}」？",
                message = "只是从列表里拿掉，设备重新连上会再次出现。",
                confirmLabel = "移除", destructive = true,
            ) {
                DevicesStore.forget(dev.device_id)
                AppGraph.router.showToast("已移除")
            },
        )
    }
    UmbraRevealSwipeRow(trailing = listOf(remove), resetKey = dev.device_id) { row() }
}

@Composable
private fun Avatar(isSecretary: Boolean, online: Boolean) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(if (isSecretary) 24.dp else 14.dp)
    Box(Modifier.size(48.dp).clip(shape).background(if (isSecretary) c.orange else c.chip), contentAlignment = Alignment.Center) {
        UmbraIcon(
            if (isSecretary) UmbraIconPaths.robot else UmbraIconPaths.monitor,
            if (isSecretary) c.onAccent else if (online) c.muted else c.faint, size = 22.dp,
        )
    }
}
