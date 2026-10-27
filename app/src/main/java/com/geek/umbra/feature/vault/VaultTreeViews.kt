// 保险箱 · 树形（fmt 3）的手机界面：逐层推入（设计侧 293 §九，稿 `公用/批次 293 · 保险箱树形` 的 iOS 01–04；Android 同一套结构）。
//
// - 最上层就是身份库的根：类别带底色方块、行尾子节点数；根上的非类别节点照常列。
// - 点类别推一屏（这一层的子节点）；非类别节点进详情，有子节点就在详情底部列一组。
// - 多账号一屏：每行名称 + 账户，行尾复制钮直接复制密码，点行进单个账号；左滑 / 长按删除，走确认框。
// - 单个账号页：名称、网址、账户、密码、备注；明文 8 秒倒数，复制后 60 秒清剪贴板。删除不放在页底。
// 路由沿用 VaultRecord / VaultEdit（树形库时 id 是节点 id，多账号里的一条是 `<nodeId>:<entryId>`），不另开路由。
package com.geek.umbra.feature.vault

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geek.umbra.app.AppGraph
import com.geek.umbra.app.Brand
import com.geek.umbra.designsystem.UmbraButton
import com.geek.umbra.designsystem.UmbraButtonKind
import com.geek.umbra.designsystem.UmbraChoice
import com.geek.umbra.designsystem.UmbraChoiceItem
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraEmptyState
import com.geek.umbra.designsystem.UmbraExtraIcons
import com.geek.umbra.designsystem.UmbraFieldLabel
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraRevealSwipeRow
import com.geek.umbra.designsystem.UmbraScreen
import com.geek.umbra.designsystem.UmbraSearchField
import com.geek.umbra.designsystem.UmbraStripAction
import com.geek.umbra.designsystem.UmbraSwipeAction
import com.geek.umbra.designsystem.UmbraSwitch
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraTopBarIcon
import com.geek.umbra.designsystem.UmbraTopBarText
import com.geek.umbra.designsystem.UmbraType
import com.geek.umbra.navigation.Route
import com.geek.umbra.navigation.UmbraAlert
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// MARK: - 小件

/** 复制敏感值后的吐司（293 §五第 4 条的字面）。 */
private fun copied(what: String, value: String) {
    VaultClipboard.copySensitive(value)
    AppGraph.router.showToast("已复制$what · 60 秒后清空剪贴板")
}

/** 行首图标：类别用 36 的底色方块（PC 的图标名 / 图片 / 首字），其余种类一颗弱色线条图标。 */
@Composable
private fun NodeLead(n: Node3) {
    val c = UmbraTheme.colors
    if (n.kind == "cat") {
        VaultRecordIcon(n.data["icon"].vString.ifEmpty { null }, VaultTreeUi.title(n).take(1), side = 36.dp, corner = 10.dp)
        return
    }
    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { UmbraIcon(kindIcon(n.kind), c.muted, size = 18.dp, strokeWidth = 1.9f) }
}

private fun kindIcon(kind: String): String = when (kind) {
    "multi" -> UmbraExtraIcons.users
    "cat" -> UmbraIconPaths.folder
    else -> VaultNodeKind.icon(kind)
}

/**
 * 列表一行（293 §三 / §九）：行高 60、两行字。类别标题 600、副标题 muted、行尾子节点数；账号第二行等宽；其余第二行 faint。
 * 左滑露「删除」（只露钮、不滑到底执行），长按出菜单。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NodeRow(n: Node3, t: Tree3, kids: Int, onClick: () -> Unit) {
    val c = UmbraTheme.colors
    val sub = VaultTreeUi.sub(n, t, kids)
    UmbraRevealSwipeRow(
        trailing = listOf(UmbraSwipeAction("删除", UmbraIconPaths.trash, c.danger, c.onDanger) { confirmTrashNode(n, t, leaving = false) }),
        resetKey = n.id, modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().background(c.card).combinedClickable(onClick = onClick, onLongClick = { nodeMenu(n, t) })
                .heightIn(min = 60.dp).padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NodeLead(n)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(VaultTreeUi.title(n), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontSize = 16.sp, fontWeight = if (n.kind == "cat") FontWeight.SemiBold else FontWeight.Normal))
                if (sub.isNotEmpty()) Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (n.kind == "cat") c.muted else c.faint,
                    style = TextStyle(fontSize = 13.sp, fontFamily = if (n.kind == "acct") FontFamily.Monospace else FontFamily.Default))
            }
            if (n.kind == "cat" && kids > 0) { Text("$kids", color = c.faint, style = TextStyle(fontSize = 14.sp)); Spacer(Modifier.width(6.dp)) }
            UmbraIcon(UmbraIconPaths.chevronRight, c.faint, size = 14.dp, strokeWidth = 2.2f)
        }
    }
}

/** 一组行装进一张圆角卡（稿上 14 圆角、1px 描边，行间细线）。 */
@Composable
private fun RowsCard(rows: List<@Composable () -> Unit>) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().clip(shape).border(UmbraDimens.borderW, c.border, shape).background(c.card)) {
        rows.forEachIndexed { i, r ->
            if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 14.dp).heightIn(min = UmbraDimens.borderW).background(c.borderSoft))
            r()
        }
    }
}

/** 进一个节点（类别推一屏、别的进详情；路由同一个，页面按种类分）。 */
private fun openNode(n: Node3, find: String = "") = AppGraph.router.go(Route.VaultRecord(n.id, find))

/** 长按菜单：账号给「复制账户 / 复制密码」，都给「删除」。 */
private fun nodeMenu(n: Node3, t: Tree3) {
    val items = mutableListOf<UmbraChoiceItem>()
    if (n.kind == "acct") {
        val user = n.data["user"].vString; val pwd = n.data["pwd"].vString
        if (user.isNotEmpty()) items += UmbraChoiceItem("复制账户") { copied("账户", user) }
        if (pwd.isNotEmpty()) items += UmbraChoiceItem("复制密码") { copied("密码", pwd) }
    }
    items += UmbraChoiceItem("删除", destructive = true) { confirmTrashNode(n, t, leaving = false) }
    UmbraChoice.present(title = VaultTreeUi.title(n), items = items)
}

/** 删除确认（293 §八）：带子节点的写「和它下面的 N 个节点一起」，账号数为 0 时自动填充那半句不写。[leaving] = 删的就是当前页，删完返回。 */
private fun confirmTrashNode(n: Node3, t: Tree3, leaving: Boolean) {
    if (!VaultStore.guardWritable()) return
    val (title, body) = VaultTreeUi.deleteCopy(n, VaultTrash3.deleteImpact(t, n.id))
    AppGraph.router.confirm(UmbraAlert(title = title, message = body, confirmLabel = "删除", destructive = true) {
        AppGraph.scope.launch {
            if (VaultStore.trashNode(n.id)) {
                if (leaving) AppGraph.router.back()
                AppGraph.router.showToast("已移入回收站 · 保留 30 天")
            }
        }
    })
}

private fun confirmTrashEntry(n: Node3, e: Entry3, leaving: Boolean) {
    if (!VaultStore.guardWritable()) return
    val (title, body) = VaultTreeUi.deleteEntryCopy(e)
    AppGraph.router.confirm(UmbraAlert(title = title, message = body, confirmLabel = "删除", destructive = true) {
        AppGraph.scope.launch {
            if (VaultStore.trashEntry(n.id, e.id)) {
                if (leaving) AppGraph.router.back()
                AppGraph.router.showToast("已移入回收站 · 保留 30 天")
            }
        }
    })
}

/** 「+」：选种类，加到 [parent] 下（null = 最上层）。菜单第一行写明加到哪（293 §二第 7 条）。 */
private fun addMenu(parent: Node3?) {
    if (!VaultStore.guardWritable()) return
    val where = parent?.let { "加到「${VaultTreeUi.title(it)}」下" } ?: "加到最上层"
    UmbraChoice.present(
        title = "添加节点",
        subtitle = "$where。图片和附件要在电脑上加。",
        items = VaultTreeUi.ADDABLE.map { k -> UmbraChoiceItem(Fmt3.kindName(k)) { AppGraph.router.go(Route.VaultEdit(VaultTreeUi.newId(k, parent?.id))) } },
    )
}

/**
 * 只读条幅（295 §四）：内容上方一条 warning 条幅，三角感叹图标、`warningSoft` 底；［去升级］跳应用商店。
 * 本机改动留着，升级后再推（店里没有这个包时退回网页）。
 */
@Composable
internal fun VaultUpgradeBanner() {
    val c = UmbraTheme.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(UmbraDimens.radiusControl)).background(c.warningSoft).padding(start = UmbraDimens.sp4, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.Top) {
        UmbraIcon(UmbraIconPaths.alertTriangle, c.warningText, size = 15.dp, strokeWidth = 2f, modifier = Modifier.padding(top = 1.dp))
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("保险箱已在别的设备上升级成新格式", color = c.warningText, style = TextStyle(fontSize = 12.5.sp, fontWeight = UmbraType.weight560))
            Text("这台要先升级${Brand.productName}才能改。现在只能看，不能改。", color = c.warningText, style = TextStyle(fontSize = 12.sp, lineHeight = 18.sp))
        }
        UmbraStripAction("去升级", c.warningText, { openStore() })
    }
}

/**
 * 迁移失败的条幅（设计侧 296 §三第 2 条，warning 同 295）：数据一点没改、照常能用；［再试一次］当场重走一遍迁移。
 * 稿上还有［查看日志］：Android 没有给人看的日志页（失败原因只写进 logcat），这颗先不放。
 */
@Composable
internal fun VaultMigrateFailedBanner() {
    val c = UmbraTheme.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(UmbraDimens.radiusControl)).background(c.warningSoft).padding(start = UmbraDimens.sp4, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.Top) {
        UmbraIcon(UmbraIconPaths.alertTriangle, c.warningText, size = 15.dp, strokeWidth = 2f, modifier = Modifier.padding(top = 1.dp))
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("保险箱没能升级成新格式", color = c.warningText, style = TextStyle(fontSize = 12.5.sp, fontWeight = UmbraType.weight560))
            Text("升级前的自检没通过，数据一点没改，照常能用。下次解锁会再试一次。", color = c.warningText, style = TextStyle(fontSize = 12.sp, lineHeight = 18.sp))
        }
        UmbraStripAction("再试一次", c.warningText, { AppGraph.scope.launch { VaultStore.retryMigration() } })
    }
}

private fun openStore() {
    val ctx = AppGraph.context
    val pkg = ctx.packageName
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(market) }.onFailure {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** 页头下的一行小字（「个人 · 17 个节点」「工作 › 云服务」）。 */
@Composable
private fun SubHead(text: String) {
    if (text.isEmpty()) return
    Text(text, color = UmbraTheme.colors.muted, style = TextStyle(fontSize = 13.sp), modifier = Modifier.padding(horizontal = 4.dp))
}

/** 搜索结果（293 §五「搜索」）：节点写它的第二行，多账号里的账号写「多账号里的账号 · 账户」；下面一行是路径。 */
@Composable
private fun HitRow(h: VaultTreeUi.Hit, query: String) {
    val c = UmbraTheme.colors
    Row(
        Modifier.fillMaxWidth().background(c.card).clickable { openNode(h.node, if (h.entryId != null) h.title else query) }
            .heightIn(min = 60.dp).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            UmbraIcon(if (h.entryId != null) UmbraIconPaths.user else kindIcon(h.node.kind), c.muted, size = 18.dp, strokeWidth = 1.9f)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(h.title, color = c.text, style = TextStyle(fontSize = 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (h.sub.isNotEmpty()) Text(h.sub, color = c.faint, style = TextStyle(fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (h.path.isNotEmpty()) Text(h.path.joinToString(" › "), color = c.faint, style = TextStyle(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        UmbraIcon(UmbraIconPaths.chevronRight, c.faint, size = 14.dp, strokeWidth = 2.2f)
    }
}

// MARK: - 最上层（iOS 01）

@Composable
internal fun VaultTreeHomeContent(st: VaultState, noteClosed: Boolean, onCloseNote: () -> Unit) {
    val se by VaultSession.state.collectAsState()
    val signedOut by AppGraph.guard.locked.collectAsState()
    val router = AppGraph.router
    val c = UmbraTheme.colors
    val t = st.tree ?: return
    var query by rememberSaveable { mutableStateOf("") }
    val idx = remember(t) { VaultTreeIndex.children(t.nodes) }
    val roots = idx[null] ?: emptyList()
    val hits = remember(t, query) { VaultTreeUi.search(t, query) }
    UmbraScreen(
        title = "保险箱",
        onBack = { router.back() },
        refreshing = st.loading,
        onRefresh = { AppGraph.scope.launch { VaultStore.syncNow() } },
        actions = {
            UmbraTopBarIcon(UmbraIconPaths.plus, "添加节点", { addMenu(null) }, tint = c.orange)
            UmbraTopBarIcon(UmbraExtraIcons.more, "更多", { presentMoreSheet(VaultAudit(emptyList())) }, tint = c.orange)
        },
    ) {
        if (st.upgradeRequired) item("upgrade") { VaultUpgradeBanner() }
        if (VaultLockNote.shows(se.autoLockMinutes, noteClosed, se.lockNoteSilenced)) {
            item("note") { UnlockedNote(se.autoLockMinutes, onSilence = { VaultSession.silenceLockNote() }, onClose = onCloseNote) }
        }
        if ((st.offline && !signedOut) || st.syncProblem.isNotEmpty()) item("offline") { OfflineNote(st) }
        item("profile") { ProfileCard(st) { switchProfile(st) {} } }
        item("search") { UmbraSearchField(query, { query = it }, placeholder = "搜索节点和每个账号") }
        if (query.isNotBlank()) {
            if (hits.isEmpty()) item("none") {
                UmbraEmptyState(UmbraIconPaths.filter, "没有匹配「${query.trim()}」的节点", hint = "密码和遮盖的内容不参与搜索，可以试试名称、账户或网址。",
                    action = "清掉搜索", onAction = { query = "" })
            } else item("hits") { RowsCard(hits.map { h -> @Composable { HitRow(h, query) } }) }
            return@UmbraScreen
        }
        if (roots.isEmpty()) {
            item("empty") {
                UmbraEmptyState(UmbraIconPaths.lockKeyhole, "保险箱里还没有节点", hint = "加一个类别、账号或多账号，会列在这里。",
                    action = "添加节点", onAction = { addMenu(null) })
            }
        } else item("rows") { RowsCard(roots.map { n -> @Composable { NodeRow(n, t, idx[n.id]?.size ?: 0) { openNode(n) } } }) }
    }
}

// MARK: - 一个节点（类别一屏 / 多账号一屏 / 其余详情）

@Composable
internal fun VaultTreeNodeContent(id: String, find: String) {
    val st by VaultStore.state.collectAsState()
    val router = AppGraph.router
    val t = st.tree ?: return
    val (nodeId, entryId) = VaultTreeUi.splitId(id)
    val n = t.node(nodeId)?.takeIf { it.live }
    if (n == null) {
        UmbraScreen(title = "", onBack = { router.back() }) {
            item("missing") {
                UmbraEmptyState(UmbraIconPaths.lockKeyhole, "这个节点不在了", hint = "可能在别的设备上删掉了，回收站里能找回。", action = "回到上一层", onAction = { router.back() })
            }
        }
        return
    }
    when {
        entryId != null -> EntryScreen(st, t, n, entryId)
        n.kind == "cat" -> LevelScreen(st, t, n)
        n.kind == "multi" -> MultiScreen(st, t, n, find)
        else -> DetailScreen(st, t, n)
    }
}

/** 节点所在的路径（从根往下，不含自己）。 */
private fun pathOf(t: Tree3, n: Node3): String = VaultTreeIndex.ancestors(t.nodes, n.id).joinToString(" › ") { VaultTreeUi.title(it) }

/** 类别推入的一屏（iOS 02）：页头下写副标题，列这一层；右上「+」加到这一层。 */
@Composable
private fun LevelScreen(st: VaultState, t: Tree3, n: Node3) {
    val router = AppGraph.router
    val c = UmbraTheme.colors
    val idx = remember(t) { VaultTreeIndex.children(t.nodes) }
    val kids = idx[n.id] ?: emptyList()
    UmbraScreen(
        title = VaultTreeUi.title(n),
        onBack = { router.back() },
        refreshing = st.loading,
        onRefresh = { AppGraph.scope.launch { VaultStore.syncNow() } },
        actions = {
            UmbraTopBarIcon(UmbraIconPaths.plus, "添加节点", { addMenu(n) }, tint = c.orange)
            UmbraTopBarIcon(UmbraExtraIcons.more, "更多", { moreMenu(n, t) }, tint = c.orange)
        },
    ) {
        if (st.upgradeRequired) item("upgrade") { VaultUpgradeBanner() }
        item("sub") { SubHead(n.data["sub"].vString.trim().ifEmpty { pathOf(t, n) }) }
        if (kids.isEmpty()) {
            item("empty") {
                UmbraEmptyState(UmbraIconPaths.folder, "「${VaultTreeUi.title(n)}」下面还没有节点", hint = "加一个账号、多账号或别的节点，会列在这一栏。",
                    action = "添加节点", onAction = { addMenu(n) })
            }
        } else item("rows") { RowsCard(kids.map { k -> @Composable { NodeRow(k, t, idx[k.id]?.size ?: 0) { openNode(k) } } }) }
    }
}

/** 右上「⋯」：编辑（名称 / 副标题 / 开关）、加子节点、删除。 */
private fun moreMenu(n: Node3, t: Tree3) {
    UmbraChoice.present(
        title = VaultTreeUi.title(n),
        items = listOf(
            UmbraChoiceItem("编辑") { if (VaultStore.guardWritable()) AppGraph.router.go(Route.VaultEdit(n.id)) },
            UmbraChoiceItem("在下面加一个节点") { addMenu(n) },
            UmbraChoiceItem("删除", destructive = true) { confirmTrashNode(n, t, leaving = true) },
        ),
    )
}

/** 子节点那一组（非类别节点有子节点时列在详情底部，293 §九）。 */
private fun androidx.compose.foundation.lazy.LazyListScope.childGroup(t: Tree3, n: Node3) {
    val idx = VaultTreeIndex.children(t.nodes)
    val kids = idx[n.id] ?: return
    if (kids.isEmpty()) return
    item("kidsHead") { UmbraFieldLabel("下面的节点 · ${kids.size}", Modifier.padding(horizontal = 4.dp)) }
    item("kids") { RowsCard(kids.map { k -> @Composable { NodeRow(k, t, idx[k.id]?.size ?: 0) { openNode(k) } } }) }
}

/** 多账号一屏（iOS 03）：大标题下「多账号 · N 个」、框内查找；每行名称 + 账户，行尾复制钮复制密码，点行进单个账号。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MultiScreen(st: VaultState, t: Tree3, n: Node3, find: String) {
    val router = AppGraph.router
    val c = UmbraTheme.colors
    val live = n.entries.filter { it.live }
    var q by rememberSaveable(n.id) { mutableStateOf(find.trim()) }
    val shown = if (q.isBlank()) live else live.filter { e -> listOf(e.name, e.user, e.url, e.note).any { it.contains(q.trim(), ignoreCase = true) } }
    UmbraScreen(
        title = VaultTreeUi.title(n),
        onBack = { router.back() },
        refreshing = st.loading,
        onRefresh = { AppGraph.scope.launch { VaultStore.syncNow() } },
        actions = {
            UmbraTopBarIcon(UmbraIconPaths.plus, "加一个账号", { if (VaultStore.guardWritable()) router.go(Route.VaultEdit("${n.id}:new")) }, tint = c.orange)
            UmbraTopBarIcon(UmbraExtraIcons.more, "更多", { moreMenu(n, t) }, tint = c.orange)
        },
    ) {
        if (st.upgradeRequired) item("upgrade") { VaultUpgradeBanner() }
        item("sub") { SubHead("多账号 · ${live.size} 个") }
        if (live.isNotEmpty()) item("find") { UmbraSearchField(q, { q = it }, placeholder = "在 ${live.size} 个账号里查找") }
        when {
            live.isEmpty() -> item("empty") {
                UmbraEmptyState(UmbraExtraIcons.users, "「${VaultTreeUi.title(n)}」里还没有账号", hint = "名称必填，搜索和自动填充靠它认；网址可选，自动填充先按它匹配。",
                    action = "加一个", onAction = { if (VaultStore.guardWritable()) router.go(Route.VaultEdit("${n.id}:new")) })
            }
            shown.isEmpty() -> item("none") { VaultNote("没有匹配「${q.trim()}」的账号。", size = 13f) }
            else -> item("rows") {
                RowsCard(shown.map { e -> @Composable { EntryRow(n, e) } })
            }
        }
        item("foot") { VaultNote(VaultTreeUi.multiFoot(n), size = 12.5f) }
        childGroup(t, n)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(n: Node3, e: Entry3) {
    val c = UmbraTheme.colors
    UmbraRevealSwipeRow(
        trailing = listOf(UmbraSwipeAction("删除", UmbraIconPaths.trash, c.danger, c.onDanger) { confirmTrashEntry(n, e, leaving = false) }),
        resetKey = e.id, modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().background(c.card)
                .combinedClickable(
                    onClick = { AppGraph.router.go(Route.VaultRecord("${n.id}:${e.id}")) },
                    onLongClick = {
                        UmbraChoice.present(title = e.name, items = buildList {
                            if (e.user.isNotEmpty()) add(UmbraChoiceItem("复制账户") { copied("账户", e.user) })
                            if (e.pwd.isNotEmpty()) add(UmbraChoiceItem("复制密码") { copied("密码", e.pwd) })
                            add(UmbraChoiceItem("删除", destructive = true) { confirmTrashEntry(n, e, leaving = false) })
                        })
                    },
                )
                .heightIn(min = 60.dp).padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(e.name.ifEmpty { "未命名" }, color = c.text, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(e.user.ifEmpty { VaultAutofill3.NO_USER }, color = c.faint, style = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (e.pwd.isNotEmpty()) {
                Box(Modifier.size(UmbraDimens.tapMin).clickable(role = Role.Button) { copied("密码", e.pwd) }, contentAlignment = Alignment.Center) {
                    UmbraIcon(UmbraIconPaths.copy, c.muted, size = 18.dp, strokeWidth = 1.8f, contentDescription = "复制密码")
                }
            }
            UmbraIcon(UmbraIconPaths.chevronRight, c.faint, size = 14.dp, strokeWidth = 2.2f)
        }
    }
}

/** 非类别节点的详情：节点卡（沿用画布版那张）、位置、关联、下面的节点。 */
@Composable
private fun DetailScreen(st: VaultState, t: Tree3, n: Node3) {
    val router = AppGraph.router
    val c = UmbraTheme.colors
    // 节点卡读附件元数据走 VItem.attachments：拿库这一层的 atts 拼一个只读的壳。
    val shell = remember(t) { VItem("tree", "", "", attachments = t.atts.filter { !it.gone }.map { it.asVAtt() }) }
    UmbraScreen(
        title = VaultTreeUi.title(n),
        onBack = { router.back() },
        actions = {
            if (n.kind in VaultTreeUi.ADDABLE) UmbraTopBarText("编辑", { if (VaultStore.guardWritable()) router.go(Route.VaultEdit(n.id)) }, strong = true)
            UmbraTopBarIcon(UmbraExtraIcons.more, "更多", { moreMenu(n, t) }, tint = c.orange)
        },
    ) {
        if (st.upgradeRequired) item("upgrade") { VaultUpgradeBanner() }
        item("sub") { SubHead(pathOf(t, n)) }
        item("card") { VaultNodeCard(VaultTreeUi.asVNode(n), shell, emptyList(), null) }
        val links = t.links.filter { !it.gone && (it.a == n.id || it.b == n.id) }
            .mapNotNull { l -> t.node(if (l.a == n.id) l.b else l.a)?.takeIf { it.live }?.let { it to l.label } }
        if (links.isNotEmpty()) {
            item("linksHead") { UmbraFieldLabel("关联", Modifier.padding(horizontal = 4.dp)) }
            item("links") {
                RowsCard(links.map { (o, label) ->
                    @Composable {
                        Row(Modifier.fillMaxWidth().clickable { openNode(o) }.heightIn(min = 52.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(VaultTreeUi.title(o), color = c.text, style = TextStyle(fontSize = 15.sp), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (label.isNotEmpty()) Text(label, color = c.faint, style = TextStyle(fontSize = 13.sp))
                            Spacer(Modifier.width(6.dp))
                            UmbraIcon(UmbraIconPaths.chevronRight, c.faint, size = 14.dp, strokeWidth = 2.2f)
                        }
                    }
                })
            }
        }
        childGroup(t, n)
        item("tail") { VaultNote("敏感值显示 8 秒后会自动重新遮盖；复制出来的内容 60 秒后自动清空剪贴板。删除在右上「⋯」里。") }
    }
}

// MARK: - 单个账号（iOS 04）

/** 敏感值一行：点眼睛看 8 秒，真的倒数（「N 秒后遮盖」），到 0 自动遮回；复制走敏感剪贴板。 */
@Composable
private fun SecretRow(label: String, value: String) {
    val c = UmbraTheme.colors
    var left by remember { mutableIntStateOf(0) }
    LaunchedEffect(left > 0) { while (left > 0) { delay(1_000); left-- } }
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = c.faint, style = TextStyle(fontSize = 12.5.sp))
            Text(if (value.isEmpty()) "—" else if (left > 0) value else "•".repeat(10), color = if (value.isEmpty()) c.faint else c.text,
                style = TextStyle(fontSize = 16.sp, fontFamily = FontFamily.Monospace), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (value.isNotEmpty()) {
            if (left > 0) Text("$left 秒后遮盖", color = c.faint, style = TextStyle(fontSize = 12.sp))
            Box(Modifier.size(UmbraDimens.tapMin).clickable(role = Role.Button) { left = if (left > 0) 0 else 8 }, contentAlignment = Alignment.Center) {
                UmbraIcon(if (left > 0) UmbraIconPaths.eyeOff else UmbraIconPaths.eye, c.muted, size = 19.dp, contentDescription = if (left > 0) "遮盖" else "显示")
            }
            Box(Modifier.size(UmbraDimens.tapMin).clickable(role = Role.Button) { copied(label, value) }, contentAlignment = Alignment.Center) {
                UmbraIcon(UmbraIconPaths.copy, c.muted, size = 18.dp, strokeWidth = 1.8f, contentDescription = "复制$label")
            }
        }
    }
}

@Composable
private fun PlainRow(label: String, value: String, mono: Boolean = false, copy: Boolean = false, multiline: Boolean = false) {
    val c = UmbraTheme.colors
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = c.faint, style = TextStyle(fontSize = 12.5.sp))
            Text(value.ifEmpty { "—" }, color = if (value.isEmpty()) c.faint else c.text,
                style = TextStyle(fontSize = if (multiline) 15.sp else 16.sp, lineHeight = if (multiline) 24.sp else 22.sp, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default),
                maxLines = if (multiline) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
        }
        if (copy && value.isNotEmpty()) {
            Box(Modifier.size(UmbraDimens.tapMin).clickable(role = Role.Button) { copied(label, value) }, contentAlignment = Alignment.Center) {
                UmbraIcon(UmbraIconPaths.copy, c.muted, size = 18.dp, strokeWidth = 1.8f, contentDescription = "复制$label")
            }
        }
    }
}

@Composable
private fun EntryScreen(st: VaultState, t: Tree3, n: Node3, entryId: String) {
    val router = AppGraph.router
    val e = n.entries.firstOrNull { it.id == entryId && it.live }
    UmbraScreen(
        title = e?.name ?: "",
        onBack = { router.back() },
        actions = { if (e != null) UmbraTopBarText("编辑", { if (VaultStore.guardWritable()) router.go(Route.VaultEdit("${n.id}:${e.id}")) }, strong = true) },
    ) {
        if (e == null) {
            item("missing") { UmbraEmptyState(UmbraExtraIcons.users, "这个账号不在了", hint = "可能在别的设备上删掉了，回收站里能找回。", action = "回到上一层", onAction = { router.back() }) }
            return@UmbraScreen
        }
        if (st.upgradeRequired) item("upgrade") { VaultUpgradeBanner() }
        item("sub") { SubHead((VaultTreeIndex.ancestors(t.nodes, n.id).map { VaultTreeUi.title(it) } + VaultTreeUi.title(n)).joinToString(" › ")) }
        item("fields") {
            RowsCard(listOf(
                { PlainRow("名称", e.name) },
                { PlainRow("网址", e.url, mono = true) },
                { PlainRow("账户", e.user, mono = true, copy = true) },
                { SecretRow("密码", e.pwd) },
            ))
        }
        if (e.note.isNotEmpty()) item("note") { RowsCard(listOf { PlainRow("备注", e.note, multiline = true) }) }
        item("foot") { VaultNote(VaultTreeUi.entryFoot(n, e), size = 12.5f) }
    }
}

// MARK: - 编辑 / 新建

/**
 * 树形库的编辑页。三种 id：
 * - `new:<kind>:<parentId>`：新建节点，「保存」时才落库；
 * - `<nodeId>:<entryId>` / `<nodeId>:new`：多账号里的一条（名称必填，［保存］［放弃］，293 §五第 3 条）；
 * - 节点 id：改名称 / 副标题 / 开关 / 内容（文本、账号、表格格子沿用画布版的编辑件）。
 */
@Composable
internal fun VaultTreeEditContent(id: String?) {
    val st by VaultStore.state.collectAsState()
    val t = st.tree ?: return
    val router = AppGraph.router
    if (id == null) { LaunchedEffect(Unit) { router.back() }; return }
    val spec = VaultTreeUi.parseNew(id)
    val (nodeId, entryId) = if (spec == null) VaultTreeUi.splitId(id) else ("" to null)
    if (entryId != null) {
        val n = t.node(nodeId)?.takeIf { it.live && it.kind == "multi" }
        if (n == null) { LaunchedEffect(Unit) { router.back() }; return }
        EntryEditor(n, n.entries.firstOrNull { it.id == entryId && it.live })
        return
    }
    val base = if (spec != null) Node3("", spec.kind, "", spec.parent, data = VaultNodeDefaults.data(spec.kind), set = VaultNodeDefaults.set(spec.kind))
    else t.node(nodeId)?.takeIf { it.live }
    if (base == null) { LaunchedEffect(Unit) { router.back() }; return }
    NodeEditorScreen(base, isNew = spec != null)
}

@Composable
private fun NodeEditorScreen(base: Node3, isNew: Boolean) {
    val router = AppGraph.router
    val c = UmbraTheme.colors
    var draft by remember(base.id, isNew) { mutableStateOf(base) }
    var dirty by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    fun edit(n: Node3) { draft = n; dirty = true; VaultSession.touch() }

    /** 落库：新建的建出来，已有的存一笔。 */
    suspend fun save() {
        if (done) return
        done = true
        if (isNew) VaultStore.createNode(draft.kind, draft.parent, draft.title.trim(), draft.data, draft.set)
        else VaultStore.saveNode(draft.copy(title = draft.title.trim()))
    }
    // 改动自动保存（293 §四「没有保存按钮」那一条在手机上落成「离开就存」，右上「完成」是现在就存）。
    OnLeave { if (dirty && !done) { val n = draft; AppGraph.scope.launch { if (isNew) VaultStore.createNode(n.kind, n.parent, n.title.trim(), n.data, n.set) else VaultStore.saveNode(n.copy(title = n.title.trim())) } } }

    UmbraScreen(
        title = if (isNew) "新建${Fmt3.kindName(draft.kind)}" else "编辑${Fmt3.kindName(draft.kind)}",
        onBack = { router.back() },
        actions = { UmbraTopBarText("完成", { AppGraph.scope.launch { if (dirty || isNew) save(); dirty = false; router.back() } }, strong = true) },
    ) {
        item("title") {
            Column(verticalArrangement = Arrangement.spacedBy(UmbraDimens.sp2)) {
                UmbraFieldLabel("名称")
                VaultInput(draft.title, { edit(draft.copy(title = it)) }, placeholder = Fmt3.kindName(draft.kind), onCard = false, height = 44.dp)
            }
        }
        when (draft.kind) {
            "cat" -> item("sub") {
                Column(verticalArrangement = Arrangement.spacedBy(UmbraDimens.sp2)) {
                    UmbraFieldLabel("副标题")
                    VaultInput(draft.data["sub"].vString, { edit(draft.copy(data = draft.data + ("sub" to js(it)))) }, placeholder = "可以空着：空着时行上写有几个节点", onCard = false, height = 44.dp)
                }
            }
            "multi" -> item("autofill") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = UmbraDimens.tapMin), verticalAlignment = Alignment.CenterVertically) {
                        Text("参与自动填充", color = c.text, style = TextStyle(fontSize = 15.sp), modifier = Modifier.weight(1f))
                        UmbraSwitch(draft.flag("autofill", true), { edit(draft.copy(set = draft.set + ("autofill" to jb(it)))) })
                    }
                    VaultNote(VaultTreeUi.multiFoot(draft))
                }
            }
            "acct", "text", "table" -> item("body") { NodeEditor(VaultTreeUi.asVNode(draft)) { v -> edit(draft.copy(data = v.data, set = v.set)) } }
            else -> item("pc") { VaultNote("这种节点的内容要在电脑上改，这里只能改名称。") }
        }
        if (isNew && draft.kind == "multi") item("hint") { VaultNote("建好以后在多账号那一屏右上「+」加账号。") }
    }
}

/** 多账号里新增 / 修改一条（293 §五第 2、3 条）：名称必填，网址可选；密码行带［生成］；［保存］［放弃］。 */
@Composable
private fun EntryEditor(n: Node3, existing: Entry3?) {
    val router = AppGraph.router
    val c = UmbraTheme.colors
    var e by remember(existing?.id) { mutableStateOf(existing ?: Entry3(VaultCrypto.rid("e"))) }
    var showError by remember { mutableStateOf(false) }
    fun set(x: Entry3) { e = x; VaultSession.touch(); if (x.name.isNotBlank()) showError = false }
    fun save() {
        if (e.name.isBlank()) { showError = true; return }
        val toSave = e.copy(name = e.name.trim(), url = e.url.trim())
        AppGraph.scope.launch { if (VaultStore.saveEntry(n.id, toSave)) router.back() }
    }
    UmbraScreen(title = if (existing == null) "加一个账号" else "改账号", onBack = { router.back() }) {
        item("name") {
            Column(verticalArrangement = Arrangement.spacedBy(UmbraDimens.sp2)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    UmbraFieldLabel("名称")
                    Spacer(Modifier.width(8.dp))
                    Text("必填 · 搜索和自动填充靠它认", color = c.faint, style = TextStyle(fontSize = 12.sp))
                }
                VaultInput(e.name, { set(e.copy(name = it)) }, placeholder = "例如：阿里云", onCard = false, height = 44.dp, error = showError)
                if (showError) Text(VaultTreeUi.NAME_REQUIRED, color = c.dangerText, style = TextStyle(fontSize = 12.5.sp))
            }
        }
        item("url") {
            Column(verticalArrangement = Arrangement.spacedBy(UmbraDimens.sp2)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    UmbraFieldLabel("网址")
                    Spacer(Modifier.width(8.dp))
                    Text("可选 · 自动填充先按它匹配", color = c.faint, style = TextStyle(fontSize = 12.sp))
                }
                VaultInput(e.url, { set(e.copy(url = it)) }, mono = true, onCard = false, height = 44.dp)
            }
        }
        item("user") { EditField("账户", e.user, mono = true) { set(e.copy(user = it)) } }
        item("pwd") { EditField("密码", e.pwd, mono = true, secure = true) { set(e.copy(pwd = it)) } }
        item("note") { EditField("备注", e.note, multiline = true) { set(e.copy(note = it)) } }
        item("buttons") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                UmbraButton("放弃", { router.back() }, Modifier.weight(1f), kind = UmbraButtonKind.Secondary)
                UmbraButton("保存", { save() }, Modifier.weight(1f))
            }
        }
    }
}
