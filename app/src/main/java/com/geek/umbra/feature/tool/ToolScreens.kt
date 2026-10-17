// 工具页（Tab 3 根页）—— 照 iOS Features/Tool/ToolHomeView.swift。
// 三组、每组两张同尺寸卡片；图标块 chip 底 + muted 图标（一屏六张橙卡等于没有重点）；
// 卡底压一行真实状态（ToolCardRules）；红角标只给要人处理的数（与底栏工具 Tab 同一判据）。
package com.geek.umbra.feature.tool

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraCountBadge
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraScreen
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.feature.inspiration.InspirationStore
import com.geek.umbra.feature.money.MoneyFmt
import com.geek.umbra.feature.money.MoneyStore
import com.geek.umbra.feature.phrases.PhraseStore
import com.geek.umbra.feature.task.TasksStore
import com.geek.umbra.feature.vault.VaultStore
import com.geek.umbra.navigation.Route
import com.geek.umbra.navigation.needsLogin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private data class ToolCard(
    val id: String,
    val label: String,
    val sub: String,
    val icon: String,
    val attn: Int = 0,
    val needsLogin: Boolean = false,
    val route: Route,
)

@Composable
fun ToolHomeScreen() {
    val router = AppGraph.router
    val locked by AppGraph.guard.locked.collectAsState()
    val tasks by TasksStore.items.collectAsState()
    val tasksLoaded by TasksStore.loadedOnce.collectAsState()
    val tasksFirst by TasksStore.firstLoading.collectAsState()
    val insps by InspirationStore.list.collectAsState()
    val inspLoaded by InspirationStore.loadedOnce.collectAsState()
    val inspFirst by InspirationStore.firstLoading.collectAsState()
    val phrases by PhraseStore.items.collectAsState()
    val vault by VaultStore.toolStatus.collectAsState()
    val stats by MoneyStore.stats.collectAsState()
    val loadedLedger by MoneyStore.loadedLedger.collectAsState()
    val moneyLoaded by MoneyStore.loadedOnce.collectAsState()
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 进页静默拉一遍：卡底是真数据，不拉就只能摆假的；顺带把各页预热（点进去秒开）。
    LaunchedEffect(Unit) {
        MoneyStore.loadIfNeeded()
        InspirationStore.load()
        if (!locked) launch { TasksStore.loadTasks() }
        launch { PhraseStore.sync() }
    }

    val cards = listOf(
        "记录" to listOf(
            ToolCard(
                "task", "任务",
                ToolCardRules.taskSub(
                    ToolCardRules.load(tasksLoaded, tasksFirst), tasks.size, TasksStore.runningCount(tasks),
                    TasksStore.failedCount(tasks), TasksStore.resumableCount(tasks), TasksStore.LIST_LIMIT,
                ),
                UmbraIconPaths.task, attn = TasksStore.attentionCount(tasks), needsLogin = Route.TaskList.needsLogin, route = Route.TaskList,
            ),
            ToolCard(
                "insp", "灵感",
                ToolCardRules.inspSub(ToolCardRules.load(inspLoaded, inspFirst), insps.size, InspirationStore.openCount(insps)),
                UmbraIconPaths.bulb, needsLogin = Route.InspList.needsLogin, route = Route.InspList,
            ),
        ),
        "账目与安全" to listOf(
            ToolCard(
                "money", "记账",
                ToolCardRules.moneySub(
                    loadedLedger?.displayName,
                    stats?.takeIf { moneyLoaded }?.let { MoneyFmt.ymLabel(it.ym) },
                    stats?.takeIf { moneyLoaded }?.let { MoneyFmt.yuan(it.expense) },
                ),
                UmbraIconPaths.wallet, needsLogin = Route.MoneyHome.needsLogin, route = Route.MoneyHome,
            ),
            ToolCard("vault", "保险箱", ToolCardRules.vaultSub(vault.unlocked, vault.count, vault.exists), UmbraIconPaths.lockKeyhole, route = Route.VaultHome),
        ),
        "输入辅助" to listOf(
            ToolCard("phrase", "常用语", ToolCardRules.phraseSub(phrases.size, PhraseStore.everSynced), UmbraIconPaths.messageText, route = Route.MePhrases),
            // iOS 是「小组件与轻点背面」；Android 没有轻点背面的系统 API，改讲桌面快捷方式（第 199 封第 11 条待设计侧定名）
            ToolCard("widgets", "小组件与快捷方式", "桌面小组件 · 长按图标记一笔", UmbraIconPaths.layoutGrid, route = Route.ToolWidgets),
        ),
    )

    UmbraScreen(
        title = "工具",
        refreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                // 四份并发拉：彼此不依赖，串行时最坏每份都等到超时（iOS 台账 refresh-serial-awaits）
                coroutineScope {
                    launch { MoneyStore.reload(silent = true) }
                    if (!locked) launch { TasksStore.loadTasks() }
                    launch { InspirationStore.refresh() }
                    launch { PhraseStore.sync() }
                }
                refreshing = false
            }
        },
        spacing = 18.dp,
    ) {
        item { Text("按类收着，每个入口一张卡", color = UmbraTheme.colors.muted, style = TextStyle(fontSize = 13.sp), modifier = Modifier.padding(start = 4.dp)) }
        cards.forEach { (group, list) ->
            item(key = group) {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(
                        group, color = UmbraTheme.colors.faint,
                        style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.08.em),
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                    // IntrinsicSize.Min：同一排两张卡取较高那张的高度，状态行才能都压在卡底（iOS 用 Spacer + minHeight 拉平）
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                        list.forEach { card ->
                            CardView(card, dim = card.needsLogin && locked, modifier = Modifier.weight(1f).fillMaxHeight()) { router.go(card.route) }
                        }
                    }
                }
            }
        }
    }
}

/** 一张卡：36 图标块 + 15.5/600 名称 + 卡底状态行（12.5 muted，最多 2 行）；最小高 126、圆角 20。 */
@Composable
private fun CardView(card: ToolCard, dim: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier.defaultMinSize(minHeight = 126.dp).clip(shape).background(c.card).border(1.dp, c.borderSoft, shape)
            .clickable(role = Role.Button, onClick = onClick)
            // 未登录的联网卡只压透明度、照样可点 —— 进去是「要登录」那一块
            .alpha(if (dim) 0.45f else 1f),
    ) {
        Column(Modifier.fillMaxHeight().padding(13.dp)) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(c.chip), contentAlignment = Alignment.Center) {
                UmbraIcon(card.icon, c.muted, size = 19.dp, strokeWidth = 1.8f)
            }
            Spacer(Modifier.height(9.dp))
            Text(card.label, color = c.text, style = TextStyle(fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(9.dp))
            Spacer(Modifier.weight(1f))
            Text(card.sub, color = c.muted, style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp), maxLines = 2)
        }
        if (card.attn > 0) UmbraCountBadge(card.attn, Modifier.align(Alignment.TopEnd).padding(12.dp))
    }
}

@Composable
fun ToolWidgetsScreen() {
    ToolWidgetsGuide()
}
