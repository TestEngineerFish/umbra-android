// 「/」快捷输入的浮层面板与前缀芯片（iOS: Features/Chat/SlashPanel.swift 的 SlashPanelView / SlashChipView）。
//
// 输入框以「/」开头时浮在输入条上方：分组渲染、整卡最大高 296 可滚、行高 ≥56，底部脚注写清共几个动作。
// 空态给一句话 +「当普通消息发」出口（不写「暂无数据」）。iOS 是玻璃底 + 模态阴影；Android 用 card 底 + 阴影。
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraType

@Composable
fun SlashPanel(query: String, onPick: (SlashAction) -> Unit, onSendPlain: () -> Unit, modifier: Modifier = Modifier) {
    val c = UmbraTheme.colors
    val groups = SlashCatalog.filtered(query)
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.fillMaxWidth().shadow(24.dp, shape).clip(shape).background(c.card).border(UmbraDimens.borderW, c.border, shape)) {
        Column(Modifier.heightIn(max = 296.dp).verticalScroll(rememberScrollState())) {
            if (groups.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("没有叫「$query」的动作", color = c.text, style = TextStyle(fontSize = 15.sp, fontWeight = UmbraType.weight560))
                    Text("还没有这个能力。接入 Skill / MCP 之后它会自动出现在这张表里。", color = c.muted, style = TextStyle(fontSize = 12.5.sp, lineHeight = 20.sp))
                    Box(Modifier.padding(top = 2.dp).clickable(role = Role.Button, onClick = onSendPlain).padding(vertical = 11.dp)) {
                        Text("当普通消息发", color = c.orangeText, style = TextStyle(fontSize = 15.sp, fontWeight = UmbraType.weight560))
                    }
                }
            } else groups.forEach { g ->
                Text(g.name, color = c.faint, style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.06.em), modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 11.dp, bottom = 3.dp))
                g.items.forEachIndexed { i, a ->
                    if (i > 0) Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(UmbraDimens.borderW).background(c.borderSoft))
                    Row(
                        Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).clickable { onPick(a) }.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        UmbraIcon(a.icon, c.muted, size = 19.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(a.label, color = c.text, style = TextStyle(fontSize = 15.5.sp, fontWeight = UmbraType.weight560))
                            Text(a.desc, color = c.faint, style = TextStyle(fontSize = 12.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        a.tag?.let {
                            Text(it, color = c.faint, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.5.sp),
                                modifier = Modifier.border(UmbraDimens.borderW, c.border, RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 2.dp))
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(UmbraDimens.borderW).background(c.borderSoft))
        Text("共 ${SlashCatalog.total} 个动作。新接入的 Skill / MCP 会自动出现在这里。", color = c.muted, style = TextStyle(fontSize = 11.sp),
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 9.dp, bottom = 10.dp))
    }
}

/** 输入框左侧的动作芯片：orange 描边 + orange-soft 底，图标 + 动作名 + 弱化的 ×；整枚就是删除按钮（热区撑到 44）。 */
@Composable
fun SlashChip(action: SlashAction, onDelete: () -> Unit) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.heightIn(min = UmbraDimens.tapMin).clickable(role = Role.Button, onClick = onDelete), contentAlignment = Alignment.Center) {
        Row(
            Modifier.height(26.dp).clip(shape).background(c.orangeSoft).border(UmbraDimens.borderW, c.orange, shape).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            UmbraIcon(action.icon, c.orangeText, size = 13.dp)
            Text(action.label, color = c.orangeText, style = TextStyle(fontSize = 13.sp, fontWeight = UmbraType.weight560))
            UmbraIcon(UmbraIconPaths.x, c.orangeText, Modifier.alpha(0.75f), size = 12.dp, strokeWidth = 2.2f)
        }
    }
}
