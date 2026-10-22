// 灵感 · 列表 / 详情 / 新建（iOS: Features/Inspiration/InspirationViews.swift）。
//
// 服务端状态取值 open / done / archived；界面文案用设计稿的中文（待办 / 已实现 / 归档）。
// 设计稿里有、服务端没有的「关联任务」整块不画 —— 画一个永远为空的分节等于告诉用户功能坏了。
package com.geek.umbra.feature.inspiration

import androidx.compose.foundation.layout.widthIn
import com.geek.umbra.app.Brand
import com.geek.umbra.designsystem.UmbraDimens
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraButton
import com.geek.umbra.designsystem.UmbraButtonKind
import com.geek.umbra.designsystem.UmbraEmptyState
import com.geek.umbra.designsystem.UmbraFieldLabel
import com.geek.umbra.designsystem.UmbraFilterChips
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraMenuItem
import com.geek.umbra.designsystem.UmbraMoreMenu
import com.geek.umbra.designsystem.UmbraPhaseFailedCard
import com.geek.umbra.designsystem.UmbraScreen
import com.geek.umbra.designsystem.UmbraScreenPhase
import com.geek.umbra.designsystem.UmbraSkeleton
import com.geek.umbra.designsystem.UmbraSwitch
import com.geek.umbra.designsystem.UmbraTagPill
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraTopBarIcon
import com.geek.umbra.designsystem.UmbraTopBarText
import com.geek.umbra.designsystem.UmbraType
import com.geek.umbra.designsystem.umbraPhaseTop
import com.geek.umbra.feature.chat.ChatApi
import com.geek.umbra.feature.task.CollapsingSearch
import com.geek.umbra.feature.task.SimpleMarkdown
import com.geek.umbra.feature.task.TaskTime
import com.geek.umbra.feature.task.VisiblePolling
import com.geek.umbra.navigation.Route
import com.geek.umbra.navigation.UmbraAlert
import com.geek.umbra.net.BackgroundWork
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 排序（iOS UmbraInspSort）。 */
enum class InspSort(val label: String) { Recent("最近记录"), Updated("最近更新"), Tag("标签分组") }

/** 状态 → （中文, 底色, 字色）。open 也给一份，详情页要显示「待办」徽标。字色走 *Text 那支。 */
@Composable
private fun statusChrome(s: String): Triple<String, Color, Color> {
    val c = UmbraTheme.colors
    return when (s) {
        "done" -> Triple("已实现", c.successSoft, c.success)
        "archived" -> Triple("归档", c.chip, c.faint)
        else -> Triple("待办", c.warningSoft, c.warningText)
    }
}

/** 来源渠道 → 中文。认不出来的照原样显示 —— 服务端以后加了新渠道，原样显示至少还能看出是什么。 */
private fun sourceLabel(raw: String?): String = when (raw) {
    "chat", "assistant" -> "聊天"
    "ios", "mobile", "android" -> "手机端"
    "pc", "desktop" -> "电脑端"
    "hotkey" -> "快捷键"
    null, "" -> "未知来源"
    else -> raw
}

private val CardShape = RoundedCornerShape(18.dp)

private fun confirmDelete(onConfirm: () -> Unit) {
    AppGraph.router.confirm(
        UmbraAlert(
            title = "确认删除这条灵感？",
            message = "删除后移入回收站，保留 30 天，之后彻底删除。",
            confirmLabel = "移入回收站",
            destructive = true,
            onConfirm = onConfirm,
        ),
    )
}

/** 改状态：等结果，失败才说话（成功时徽标当场就变了，那就是反馈）。 */
private fun setStatus(i: Inspiration, to: String) {
    val ok = InspirationStore.setStatus(i.id, to)
    InspWriteRules.afterStatus(ok)?.let { AppGraph.router.showToast(it) }
}

// MARK: - 列表

@Composable
fun InspListView() {
    val c = UmbraTheme.colors
    val router = AppGraph.router
    val list by InspirationStore.list.collectAsState()
    val loadedOnce by InspirationStore.loadedOnce.collectAsState()
    val firstLoading by InspirationStore.firstLoading.collectAsState()
    val refreshing by InspirationStore.refreshing.collectAsState()
    val offline by AppGraph.http.offline.collectAsState()

    /** "" = 全部 */
    var status by remember { mutableStateOf("") }
    var tag by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(InspSort.Recent) }
    var sortOpen by remember { mutableStateOf(false) }

    // 5 秒轮询；首轮之后不是人点的：标成后台路径。
    VisiblePolling(periodMs = 5_000) { first ->
        if (first) InspirationStore.load() else withContext(BackgroundWork) { InspirationStore.load() }
    }

    val phase = UmbraScreenPhase.resolve(list.isNotEmpty(), firstLoading, loadedOnce, offline)
    val retry: () -> Unit = { AppGraph.scope.launch { InspirationStore.retry() } }
    val dim = if (phase is UmbraScreenPhase.Content && phase.offline) 0.6f else 1f
    val searchQ = query.trim()
    val isFiltered = searchQ.isNotEmpty() || tag != null || status.isNotEmpty()
    val allTags = InspirationStore.allTags(list)
    val rows = list.filter { i ->
        (status.isEmpty() || i.status == status) && (tag == null || tag in i.tags) &&
            (searchQ.isEmpty() || (i.title + i.raw + i.summary).contains(searchQ, ignoreCase = true))
    }.let { out ->
        when (sort) {
            InspSort.Recent -> out // 本机已按最近记录排好
            InspSort.Updated -> out.sortedByDescending { it.updated_at ?: "" }
            InspSort.Tag -> out.sortedBy { it.tags.firstOrNull() ?: "" }
        }
    }

    UmbraScreen(
        title = "灵感",
        onBack = { router.back() },
        refreshing = refreshing,
        onRefresh = { AppGraph.scope.launch { InspirationStore.refresh() } },
        // 右侧动作组顺序是规矩：主动作「＋」更靠左，次级「排序」更靠右（iosShell.toolbar.right）。
        actions = {
            UmbraTopBarIcon(UmbraIconPaths.plus, "新建", { router.go(Route.InspEdit(null)) }, tint = c.orange)
            Box {
                UmbraTopBarIcon(UmbraIconPaths.sortLines, "排序", { sortOpen = true }, tint = c.orange)
                DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }, containerColor = c.card, modifier = Modifier.widthIn(min = 180.dp)) {
                    InspSort.entries.forEach { o ->
                        DropdownMenuItem(
                            text = { Text((if (sort == o) "✓ " else "") + o.label, color = c.text) },
                            onClick = { sortOpen = false; sort = o },
                        )
                    }
                }
            }
        },
        topContent = umbraPhaseTop(phase, live = true, retry),
        spacing = 10.dp,
    ) {
        when (phase) {
            UmbraScreenPhase.Skeleton -> item("skel") { UmbraSkeleton() }
            is UmbraScreenPhase.Failed -> item("fail") { UmbraPhaseFailedCard(phase.offline, retry, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), "拿不到灵感列表") }
            is UmbraScreenPhase.Content -> {
                item("status") {
                    UmbraFilterChips(Modifier.alpha(dim)) {
                        listOf("" to "全部", "open" to "待办", "done" to "已实现", "archived" to "归档").forEach { (k, label) ->
                            UmbraTagPill(label, status == k, { status = k }, count = if (k.isEmpty()) list.size else list.count { it.status == k })
                        }
                    }
                }
                if (allTags.isNotEmpty()) {
                    item("tags") {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).alpha(dim), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("标签", color = c.faint, style = UmbraType.fieldLabel.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold))
                            allTags.forEach { t -> UmbraTagPill(t, tag == t, { tag = if (tag == t) null else t }) }
                            if (tag != null) {
                                Box(Modifier.heightIn(min = UmbraDimens.tapMin).clickable(role = Role.Button) { tag = null }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                                    Text("清除", color = c.orangeText, style = TextStyle(fontSize = 12.5.sp, fontWeight = UmbraType.weight560))
                                }
                            }
                        }
                    }
                }
                item("search") { CollapsingSearch("搜索灵感", query, { query = it }, Modifier.alpha(dim), trailingNote = sort.label) }
                if (rows.isEmpty()) {
                    item("empty") {
                        // 空态说「怎么开始」，无结果说「改什么条件」（states.emptyVsNoResult）。
                        if (isFiltered) {
                            UmbraEmptyState(
                                UmbraIconPaths.filter,
                                if (searchQ.isEmpty()) "这个筛选下没有灵感" else "没有匹配「$searchQ」的灵感",
                                hint = "灵感是有的，只是不符合当前的状态、标签或关键词。",
                                action = "清掉筛选",
                                // 三个条件一起清 —— 只清一个的话人点完还是空屏，会以为按钮坏了。
                                onAction = { query = ""; tag = null; status = "" },
                            )
                        } else {
                            UmbraEmptyState(
                                UmbraIconPaths.bulb, "还没有灵感",
                                hint = "在任意端发一条「记个灵感：…」，秘书会自动补标题和标签；也可以点右上角手动添加。",
                                action = "记一条灵感", onAction = { router.go(Route.InspEdit(null)) },
                            )
                        }
                    }
                } else {
                    for (i in rows) item(key = "i-${i.key}") { Box(Modifier.alpha(dim)) { InspCard(i) } }
                }
            }
        }
    }
}

/** 列表卡：标题、状态徽标（非待办）、摘要 2 行、整理中 / 调研中软 chip、标签、来源 · 时间。长按出快捷菜单。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InspCard(i: Inspiration) {
    val c = UmbraTheme.colors
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier.fillMaxWidth().clip(CardShape).background(c.card).border(1.dp, c.border, CardShape)
                .combinedClickable(onClick = { AppGraph.router.go(Route.InspDetail(i.id.toString())) }, onLongClick = { menu = true })
                .padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                // 标题空着时用原文前 20 字顶着 —— 后台补整理那几秒里显示「（还没有标题）」会让人以为没记上。
                Text(i.displayTitle, color = c.text, style = TextStyle(fontSize = 16.sp, fontWeight = UmbraType.weight560, lineHeight = 22.sp), modifier = Modifier.weight(1f))
                if (i.status != "open") StatusBadge(i.status)
            }
            Text(i.summary.ifEmpty { i.raw }, color = c.muted, style = TextStyle(fontSize = 14.sp, lineHeight = 21.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // 后台正在补标题 / 标签时给一个轻提示，免得用户以为标签功能坏了。
                    if (i.organizeState == "pending") SoftChip("整理中")
                    if (i.researchInFlight) SoftChip("调研中")
                    i.tags.forEach { t ->
                        Text(t, color = c.muted, style = TextStyle(fontSize = 11.5.sp),
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(c.chip).padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
                Spacer(Modifier.weight(0.001f))
                Text("${sourceLabel(i.source_channel)} · ${TaskTime.relative(i.created_at)}", color = c.faint, style = TextStyle(fontSize = 11.5.sp), maxLines = 1)
            }
        }
        // 长按 = 快捷菜单；删除仍必进确认弹窗。
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.card, modifier = Modifier.widthIn(min = 180.dp)) {
            DropdownMenuItem(text = { Text(if (i.status == "done") "标回待办" else "标记已实现", color = c.text) },
                onClick = { menu = false; setStatus(i, if (i.status == "done") "open" else "done") })
            DropdownMenuItem(text = { Text(if (i.status == "archived") "取消归档" else "归档", color = c.text) },
                onClick = { menu = false; setStatus(i, if (i.status == "archived") "open" else "archived") })
            DropdownMenuItem(text = { Text("编辑", color = c.text) }, onClick = { menu = false; AppGraph.router.go(Route.InspEdit(i.id.toString())) })
            DropdownMenuItem(text = { Text("删除", color = c.dangerText) }, onClick = {
                menu = false
                // 等结果再说话；0 条（别处已删）说「已经不在了」，不说「已移入回收站」。
                confirmDelete { AppGraph.router.showToast(InspWriteRules.afterDelete(InspirationStore.delete(i.id)).toast) }
            })
        }
    }
}

/** 「整理中 / 调研中」：faint 字 + chip 底 —— 它是状态说明，不是标签，不该和用户自己打的标签抢注意力。 */
@Composable
private fun SoftChip(text: String) {
    val c = UmbraTheme.colors
    Text(text, color = c.faint, style = TextStyle(fontSize = 11.5.sp),
        modifier = Modifier.clip(RoundedCornerShape(50)).background(c.chip).padding(horizontal = 8.dp, vertical = 2.dp))
}

@Composable
private fun StatusBadge(s: String, big: Boolean = false) {
    val (label, bg, fg) = statusChrome(s)
    Text(label, color = fg, style = TextStyle(fontSize = if (big) 11.5.sp else 11.sp, fontWeight = FontWeight.SemiBold),
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = if (big) 9.dp else 8.dp, vertical = if (big) 3.dp else 2.dp))
}

// MARK: - 详情

@Composable
fun InspDetailView(id: String) {
    val c = UmbraTheme.colors
    val router = AppGraph.router
    val list by InspirationStore.list.collectAsState()
    val loadedOnce by InspirationStore.loadedOnce.collectAsState()
    val item = list.firstOrNull { it.id.toString() == id }

    // 编辑草稿。editing = 编辑态。**就地编辑**：同页切换，不推新页。
    var dRaw by remember { mutableStateOf("") }
    var dTitle by remember { mutableStateOf("") }
    var dTags by remember { mutableStateOf("") }
    /** 点「编辑」那一刻定格的原值（#69）：保存时只上行和它不同的字段。 */
    var dSnap by remember { mutableStateOf<InspWriteRules.EditSnapshot?>(null) }
    var editing by remember { mutableStateOf(false) }
    /** 删成之后正在退页：这一条从列表里消失的那一瞬不该画「这条灵感不在了」—— 是自己删的。 */
    var deleting by remember { mutableStateOf(false) }

    // 只在「有活儿在跑」时轮询（调研排队 / 进行中，或标题还没补上）。主渠道是 WS inspiration_*，轮询是兜底。
    val watching = item != null && (item.researchInFlight || item.organizeState == "pending")
    LaunchedEffect(Unit) { InspirationStore.load() }
    VisiblePolling(watching, periodMs = 5_000) { first ->
        if (!watching) awaitCancellation()
        if (!first) withContext(BackgroundWork) { InspirationStore.load() }
    }

    fun startEdit(i: Inspiration) {
        dRaw = i.raw; dTitle = i.title; dTags = InspWriteRules.tagsText(i.tags); dSnap = InspWriteRules.snapshot(i); editing = true
    }

    /**
     * 就地编辑的保存：成功才退出编辑态；失败留在编辑态、草稿一个字不动。那一条已不在（列表里没有了）也留着草稿，
     * 如实说已在别处删除 —— 不一声不吭退出编辑态把草稿扔掉。
     */
    fun saveEdit() {
        val i = item
        val snap = dSnap
        if (i == null || snap == null) { router.showToast(InspWriteRules.saveGone); return }
        if (dRaw.isBlank()) { router.showToast("内容不能是空的"); return }
        val patch = InspWriteRules.editPatch(snap, dRaw, dTitle, dTags)
        if (patch.isEmpty) { editing = false; router.showToast(InspWriteRules.unchanged); return }
        val out = InspWriteRules.afterUpdate(InspirationStore.update(i.id, patch))
        if (out.leave) editing = false
        router.showToast(out.toast)
    }

    /** 删除：等结果再退页、再说话。退页走 back(ifShowing)：人可能已经切到别处，裸 back 会把那一页弹掉。 */
    fun delete(i: Inspiration) {
        if (deleting) return
        deleting = true
        val out = InspWriteRules.afterDelete(InspirationStore.delete(i.id))
        router.showToast(out.toast)
        if (!out.leave) { deleting = false; return }
        val here = router.top == Route.InspDetail(id)
        router.back(ifShowing = Route.InspDetail(id))
        if (!here) deleting = false
    }

    UmbraScreen(
        title = if (editing) "编辑灵感" else "灵感详情",
        onBack = if (editing) null else ({ router.back() }),
        // 编辑态藏返回：会丢改动的出口只留「取消」一个。
        leading = if (editing) ({ UmbraTopBarText("取消", { editing = false }) }) else null,
        actions = {
            if (editing) UmbraTopBarText("保存", { saveEdit() }, strong = true)
            else if (item != null) {
                UmbraTopBarText("编辑", { startEdit(item) }, strong = true)
                // 「⋯」：破坏性操作不占详情页底部，收进这里。
                UmbraMoreMenu(listOf(UmbraMenuItem("删除", danger = true) { confirmDelete { delete(item) } }))
            }
        },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        spacing = 0.dp,
        bottomBar = if (!editing && item != null) ({ InspDetailBottomBar(item) }) else null,
    ) {
        when {
            editing -> item("form") {
                InspForm(dRaw, { dRaw = it }, dTitle, { dTitle = it }, dTags, { dTags = it }, InspirationStore.allTags(list), isNew = false)
            }
            item != null -> inspDetailContent(item)
            // 本机账本还没读过（冷进详情的第一帧）：别先闪一下「这条灵感不在了」
            !loadedOnce -> item("skel") { UmbraSkeleton() }
            !deleting -> item("missing") {
                // 灵感被别的端删了：如实说明并给出口，而不是留一个空白页。
                UmbraEmptyState(UmbraIconPaths.bulb, "这条灵感不在了", hint = "可能是在别的设备上删掉了。", action = "回到灵感列表", onAction = { router.back() })
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.inspDetailContent(i: Inspiration) {
    item("head") {
        val c = UmbraTheme.colors
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(i.displayTitle, color = c.text, style = TextStyle(fontSize = 23.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                StatusBadge(i.status, big = true)
                Text(TaskTime.absolute(i.created_at), color = c.faint, style = TextStyle(fontSize = 12.5.sp))
            }
        }
    }
    item("raw") {
        val c = UmbraTheme.colors
        DetailSection("原文") {
            SelectionContainer {
                Text(i.raw, color = c.text, style = TextStyle(fontSize = 15.5.sp, lineHeight = 25.5.sp),
                    modifier = Modifier.fillMaxWidth().clip(CardShape).background(c.card).border(1.dp, c.border, CardShape).padding(13.dp))
            }
        }
    }
    // 秘书整理是异步补的。**正在补的时候要说一句**：这一节空着会让人以为没在整理，转头就手动去填标题了。
    if (i.summary.isEmpty() && i.organizeState == "pending") {
        item("org") {
            val c = UmbraTheme.colors
            DetailSection("秘书整理") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = c.orange, strokeWidth = 2.dp)
                    Text("秘书正在补标题和标签…", color = c.muted, style = TextStyle(fontSize = 14.sp))
                }
            }
        }
    } else if (i.summary.isNotEmpty()) {
        item("org") {
            val c = UmbraTheme.colors
            DetailSection("秘书整理") {
                SelectionContainer {
                    Text(i.summary, color = c.text, style = TextStyle(fontSize = 15.sp, lineHeight = 24.75.sp),
                        modifier = Modifier.fillMaxWidth().clip(CardShape).background(c.orangeSoft).padding(13.dp))
                }
            }
        }
    }
    item("research") { DetailSection("秘书调研") { ResearchSection(i) } }
    item("tags") {
        val c = UmbraTheme.colors
        DetailSection("标签") {
            if (i.tags.isEmpty()) Text("还没有标签。编辑里可以加。", color = c.muted, style = TextStyle(fontSize = 13.sp))
            else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                i.tags.forEach { t ->
                    Text(t, color = c.muted, style = TextStyle(fontSize = 13.sp),
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(c.chip).padding(horizontal = 11.dp, vertical = 5.dp))
                }
            }
        }
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        UmbraFieldLabel(title)
        content()
    }
}

/**
 * 调研四态各画各的，**不合并成一个「有内容就显示」**：「还没查过」和「查了但失败了」对用户是两件事 ——
 * 前者该给按钮，后者该说清楚为什么、再给重试。合成一个的话失败会静默成「没查过」。
 */
@Composable
private fun ResearchSection(i: Inspiration) {
    val c = UmbraTheme.colors
    val boxed = Modifier.fillMaxWidth().clip(CardShape).background(c.card).border(1.dp, c.border, CardShape).padding(13.dp)
    when {
        i.researchState == "queued" || i.researchState == "running" -> Row(
            Modifier.fillMaxWidth().clip(CardShape).background(c.card).padding(13.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            CircularProgressIndicator(Modifier.size(16.dp), color = c.orange, strokeWidth = 2.dp)
            Text(if (i.researchState == "queued") "排队中，马上开始…" else "正在查，通常几十秒。可以先去别处，回来再看。", color = c.muted, style = TextStyle(fontSize = 14.sp))
        }
        i.researchState == "done" && i.researchText.isNotEmpty() -> Column(boxed, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 服务端产出的是 Markdown（要点 + 链接）。
            SelectionContainer { SimpleMarkdown(i.researchText, 15f) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(i.research_at?.let { "查于 " + TaskTime.relative(it) } ?: "", color = c.faint, style = TextStyle(fontSize = 11.5.sp), modifier = Modifier.weight(1f))
                Box(Modifier.heightIn(min = UmbraDimens.tapMin).clickable(role = Role.Button) { research(i) }.padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                    Text("重新查一次", color = c.orangeText, style = TextStyle(fontSize = 12.5.sp, fontWeight = UmbraType.weight560))
                }
            }
        }
        i.researchState == "failed" -> Column(boxed, verticalArrangement = Arrangement.spacedBy(9.dp)) {
            // 失败原因是服务端写进 research 字段的 —— 原样显示，别翻译成「出错了」这种等于没说的话。
            Text(i.researchText.ifEmpty { "调研没能完成。" }, color = c.muted, style = TextStyle(fontSize = 14.sp))
            UmbraButton("再试一次", { research(i) }, Modifier.fillMaxWidth(), kind = UmbraButtonKind.Secondary, height = 40.dp)
        }
        else -> Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("让秘书上网摸个底：已经有什么现成的、坑在哪、值不值得做。几条要点加参考链接，不写长报告。", color = c.muted, style = TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp))
            UmbraButton("帮我查查", { research(i) }, Modifier.fillMaxWidth(), kind = UmbraButtonKind.Secondary, height = 40.dp)
        }
    }
}

/** 「帮我查查 / 重新查一次 / 再试一次」：状态乐观先推到排队中，那一句等送没送达再说。 */
private fun research(i: Inspiration) {
    AppGraph.scope.launch {
        val ok = InspirationStore.requestResearch(i.id)
        AppGraph.router.showToast(InspWriteRules.afterResearch(ok))
    }
}

@Composable
private fun InspDetailBottomBar(i: Inspiration) {
    val c = UmbraTheme.colors
    Column(Modifier.fillMaxWidth().background(c.bg)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSoft))
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            UmbraButton("让${Brand.productName}去做这件事", {
                // 跳到与秘书的会话，挂「创建任务」芯片 + 填灵感原文 + 亮来源横幅。**不自动发送** —— 发之前让用户能改一句。
                ChatApi.startDraft(
                    i.raw.ifEmpty { i.title },
                    chip = "创建任务",
                    banner = i.title.ifEmpty { i.raw.take(18) },
                )
            }, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UmbraButton(if (i.status == "done") "标回待办" else "标记已实现", { setStatus(i, if (i.status == "done") "open" else "done") },
                    Modifier.weight(1f), kind = UmbraButtonKind.Secondary, height = 44.dp)
                UmbraButton(if (i.status == "archived") "取消归档" else "归档", { setStatus(i, if (i.status == "archived") "open" else "archived") },
                    Modifier.weight(1f), kind = UmbraButtonKind.Secondary, height = 44.dp)
            }
        }
    }
}

// MARK: - 新建（编辑已有灵感走详情页的就地编辑；路由仍接受 id，带 id 进来也能编）

@Composable
fun InspEditView(id: String?) {
    val router = AppGraph.router
    val list by InspirationStore.list.collectAsState()
    val existing = id?.let { rid -> list.firstOrNull { it.id.toString() == rid } }

    var raw by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var tagsText by remember { mutableStateOf("") }
    var research by remember { mutableStateOf(false) }
    /** 带 id 进来时表单已经用那一条填过、快照已定格。 */
    var loaded by remember { mutableStateOf(false) }
    var snap by remember { mutableStateOf<InspWriteRules.EditSnapshot?>(null) }
    /** 这一页的保存已经成功：之后保存钮一直禁用，页面再出现时自己退掉（防切回来再点一次存出第二条）。 */
    var committed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (committed) { router.back(ifShowing = Route.InspEdit(id)); return@LaunchedEffect }
        InspirationStore.load()
    }
    // 带 id 进来时列表可能还没拉到那一条：拉到了再补填，只填一次（填过之后人可能已经改了）。
    LaunchedEffect(existing?.id) {
        val e = existing
        if (!loaded && e != null) {
            loaded = true
            raw = e.raw; title = e.title; tagsText = InspWriteRules.tagsText(e.tags); snap = InspWriteRules.snapshot(e)
        }
    }

    /**
     * 保存：等结果再决定去留（#60）。走哪条路按**路由**分（editMode）：带 id 就只可能是改，那一条不在了就说
     * 「已在别处删除」、草稿留着 —— 绝不落进新建，库里多出一条一样的。
     */
    fun save() {
        if (committed) return
        val body = raw.trim()
        if (body.isEmpty()) { router.showToast("内容还是空的，写点什么才能存"); return }
        val name = title.trim()
        val out = when (val mode = InspWriteRules.editMode(id, if (existing == null) null else snap)) {
            InspWriteRules.EditMode.Gone -> { router.showToast(InspWriteRules.saveGone); return }
            is InspWriteRules.EditMode.Update -> {
                val e = existing ?: run { router.showToast(InspWriteRules.saveGone); return }
                val patch = InspWriteRules.editPatch(mode.snapshot, raw, title, tagsText)
                if (patch.isEmpty) { router.back(); router.showToast(InspWriteRules.unchanged); return }
                InspWriteRules.afterUpdate(InspirationStore.update(e.id, patch))
            }
            InspWriteRules.EditMode.Create -> {
                val ok = InspirationStore.create(body, name, InspWriteRules.parseTags(tagsText), "", research)
                InspWriteRules.afterSave(ok, research, name.isEmpty())
            }
        }
        if (out.leave) {
            committed = true
            router.back(ifShowing = Route.InspEdit(id))
        }
        router.showToast(out.toast)
    }

    val canSave = raw.isNotBlank()
    UmbraScreen(
        title = if (id == null) "记一条灵感" else "编辑灵感",
        // 左上角是「取消」不是返回箭头 —— 放弃这次编辑，不是回上一页。
        leading = { UmbraTopBarText("取消", { router.back() }) },
        actions = { UmbraTopBarText(if (committed) "已保存" else "保存", { save() }, enabled = canSave && !committed, strong = true) },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        spacing = 0.dp,
    ) {
        item("form") {
            InspForm(raw, { raw = it }, title, { title = it }, tagsText, { tagsText = it }, InspirationStore.allTags(list), isNew = id == null,
                research = research, onResearch = { research = it })
        }
    }
}

/** 表单（详情就地编辑与新建共用）：内容 / 标题 / 标签（已有标签 chips 点选）/「顺便查一查」（只在新建时）。 */
@Composable
private fun InspForm(
    raw: String, onRaw: (String) -> Unit,
    title: String, onTitle: (String) -> Unit,
    tagsText: String, onTags: (String) -> Unit,
    allTags: List<String>, isNew: Boolean,
    research: Boolean = false, onResearch: ((Boolean) -> Unit)? = null,
) {
    val c = UmbraTheme.colors
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Field("内容") { Input(raw, onRaw, "想到什么就写什么，不用组织语言。", multiline = true) }
        Field("标题") { Input(title, onTitle, "留空由秘书补") }
        Field("标签") {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                if (allTags.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        allTags.take(10).forEach { t ->
                            val cur = InspWriteRules.parseTags(tagsText)
                            val on = t in cur
                            Box(Modifier.heightIn(min = UmbraDimens.tapMin).clickable(role = Role.Checkbox) {
                                onTags((if (on) cur - t else cur + t).joinToString("，"))
                            }, contentAlignment = Alignment.Center) {
                                Text(t, color = if (on) c.orangeText else c.muted, style = TextStyle(fontSize = 12.5.sp),
                                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) c.orangeSoft else c.card)
                                        .border(1.dp, if (on) c.orange else c.border, RoundedCornerShape(50)).padding(horizontal = 11.dp, vertical = 5.dp))
                            }
                        }
                    }
                }
                Input(tagsText, onTags, "逗号分隔，可留空")
            }
        }
        // **默认关**：每条都自动查会烧 token，还会把灵感列表变成一堆没人读的半成品报告。
        if (isNew && onResearch != null) {
            Row(
                Modifier.fillMaxWidth().clip(CardShape).background(c.card).border(1.dp, c.border, CardShape).padding(13.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("顺便查一查", color = c.text, style = TextStyle(fontSize = 15.5.sp, fontWeight = UmbraType.weight560))
                    Text("记完之后让秘书上网摸个底，几条要点加参考链接。也可以之后在详情里随时点。", color = c.muted, style = TextStyle(fontSize = 12.5.sp, lineHeight = 19.sp))
                }
                UmbraSwitch(research, onResearch)
            }
        }
    }
}

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) { UmbraFieldLabel(label); content() }
}

/** 表单输入框：card 底、圆角 12、borderSoft 描边；正文框最少 150 高。 */
@Composable
private fun Input(value: String, onValue: (String) -> Unit, placeholder: String, multiline: Boolean = false) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(12.dp)
    BasicTextField(
        value = value, onValueChange = onValue, singleLine = !multiline,
        textStyle = TextStyle(fontSize = 16.sp, color = c.text, lineHeight = if (multiline) 26.sp else 22.sp),
        cursorBrush = SolidColor(c.orange),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = if (multiline) 150.dp else 48.dp).clip(shape).background(c.card)
                    .border(1.dp, c.borderSoft, shape).padding(12.dp),
                contentAlignment = if (multiline) Alignment.TopStart else Alignment.CenterStart,
            ) {
                if (value.isEmpty()) Text(placeholder, color = c.faint, style = TextStyle(fontSize = 16.sp))
                inner()
            }
        },
    )
}
