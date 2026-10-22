// 存储容量的两屏（iOS Features/Me/StorageView.swift，设计侧批次 234 / 235）：
//   「我 › 账号」里的「存储」一格（[StorageSummaryCard]）、「账号 › 管理存储」一页（[StoragePage]）。
// 两屏读同一份 `GET /storage`；判据全在 StorageRules（纯函数、有用例），这个文件只画。
package com.geek.umbra.feature.account

import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraErrorCard
import com.geek.umbra.designsystem.UmbraExtraIcons
import com.geek.umbra.designsystem.UmbraFieldLabel
import com.geek.umbra.designsystem.UmbraGroupCard
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraProgressBar
import com.geek.umbra.designsystem.UmbraRevealSwipeRow
import com.geek.umbra.designsystem.UmbraRowDivider
import com.geek.umbra.designsystem.UmbraScreen
import com.geek.umbra.designsystem.UmbraStripAction
import com.geek.umbra.designsystem.UmbraSwipeAction
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraTopBarIcon
import com.geek.umbra.feature.knowledge.KbNet
import com.geek.umbra.feature.knowledge.KbOutcome
import com.geek.umbra.feature.knowledge.KnowledgeRules
import com.geek.umbra.feature.knowledge.kbFailText
import com.geek.umbra.feature.me.MeNote
import com.geek.umbra.navigation.Route
import com.geek.umbra.navigation.UmbraAlert
import com.geek.umbra.net.ApiResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

private val router get() = AppGraph.router

/** 毫秒 → 「9月28日」（副行里的日期；同一年不写年，稿上就是这个样子）。 */
private fun storageDay(ms: Long): String {
    if (ms <= 0) return "—"
    val z = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    return "${z.monthValue}月${z.dayOfMonth}日"
}

/** 读数的三个派生值一处算：到期超出、色档、档位芯片（StorageView.swift:21）。账号页那一格和管理页头部各算一遍会漂。 */
private class StorageFace(s: StorageDTO) {
    val warn = StorageRules.warn(s.warn)
    val overLimit = StorageRules.isOverLimit(s.tier, s.used, s.limit)
    val tone = StorageRules.tone(warn, overLimit)
    val tier = StorageRules.tierLabel(s.tier, overLimit)
}

/** 「used / limit · 档位 · N%」+ 用量条 +（可选）四类两列 + 保险箱整句 + 状态行（StorageView.swift:48）。 */
@Composable
fun StorageUsageHeader(storage: StorageDTO, showKinds: Boolean = true) {
    val c = UmbraTheme.colors
    val s = storage
    val face = StorageFace(s)
    // 条色是**填充**，不是前景字，所以用实心那支。
    val fill = when (face.tone) {
        StorageRules.Tone.Normal -> c.orange
        StorageRules.Tone.Warning -> c.warning
        StorageRules.Tone.Danger -> c.danger
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = c.text, fontWeight = FontWeight.SemiBold)) { append(StorageRules.fmt(s.used)) }
                    withStyle(SpanStyle(color = c.muted)) { append(" / ${StorageRules.fmt(s.limit)}") }
                },
                style = TextStyle(fontSize = 15.sp), maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                face.tier, color = if (face.overLimit) c.warningText else c.muted,
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.clip(RoundedCornerShape(percent = 50)).background(if (face.overLimit) c.warningSoft else c.chip).padding(horizontal = 8.dp, vertical = 1.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(StorageRules.pctText(s.pct), color = c.muted, style = TextStyle(fontSize = 13.sp))
        }
        UmbraProgressBar(
            StorageRules.barFraction(s.pct, s.used).toFloat(), fill, height = 6.dp,
            modifier = Modifier.semantics { contentDescription = "已用 ${StorageRules.fmt(s.used)}，共 ${StorageRules.fmt(s.limit)}" },
        )
        if (showKinds) {
            val (vaultCell, vaultNote) = StorageRules.vaultCell(s.tier, s.by_kind.vault ?: 0)
            val cells = listOf(
                "聊天与附件" to StorageRules.fmt(StorageRules.filesBytes(s.by_kind)),
                "知识库" to StorageRules.fmt(s.by_kind.kb ?: 0),
                "保险箱" to vaultCell,
                "回收站" to StorageRules.fmt(s.trash),
            )
            // 两列四格（稿 grid-template-columns: 1fr 1fr）。
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                cells.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { (k, v) ->
                            Row(Modifier.weight(1f)) {
                                Text(k, color = c.muted, style = TextStyle(fontSize = 13.sp), maxLines = 1)
                                Spacer(Modifier.weight(1f))
                                Text(v, color = c.text, style = TextStyle(fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            // 保险箱那一格两列放不下整句：整句另起一行放格外（235 §四 iOS 那条）。
            if (vaultNote != null) Text(vaultNote, color = c.muted, style = TextStyle(fontSize = 13.sp, lineHeight = 20.sp))
        }
        StorageRules.statusLine(face.warn, s.tier, s.pct, s.limit, face.overLimit)?.let { (kind, text) ->
            // 状态行的字压在卡底上：warning / danger 用 *Text 那一支，info 用 muted。
            val (icon, color) = when (kind) {
                StorageRules.LineKind.Warning -> UmbraIconPaths.alertTriangle to c.warningText
                StorageRules.LineKind.Full -> UmbraIconPaths.xCircle to c.dangerText
                StorageRules.LineKind.Info -> UmbraIconPaths.info to c.muted
            }
            Row(verticalAlignment = Alignment.Top) {
                UmbraIcon(icon, color, size = 15.dp, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.width(7.dp))
                Text(text, color = color, style = TextStyle(fontSize = 14.sp, lineHeight = 23.sp))
            }
        }
    }
}

/**
 * 存储的条幅（234 §五）：warn 档那条**可关、带「不再提示」**；会员到期那条**不可关、带「续费会员」**。
 * 能关的是消息，条件还成立的是读数 —— warn 那条能关是因为那一格的用量条和百分比一直都在。
 */
@Composable
fun StorageBanner(text: String, actionTitle: String? = null, action: (() -> Unit)? = null, onClose: (() -> Unit)? = null) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(c.warningSoft).border(UmbraDimens.borderW, c.warning, shape)
            .padding(start = 14.dp, end = if (onClose != null) 4.dp else 14.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        UmbraIcon(UmbraIconPaths.alertTriangle, c.warningText, size = 16.dp, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(text, color = c.warningText, style = TextStyle(fontSize = 14.sp, lineHeight = 23.sp))
            if (actionTitle != null && action != null) UmbraStripAction(actionTitle, c.warningText, action)
        }
        if (onClose != null) {
            Box(Modifier.size(UmbraDimens.tapMin).clickable(role = Role.Button, onClick = onClose), contentAlignment = Alignment.Center) {
                UmbraIcon(UmbraIconPaths.x, c.warningText, size = 15.dp, strokeWidth = 2.2f, contentDescription = "关闭")
            }
        }
    }
}

/** 账号页的「存储」分组：用量头（带四类）+「管理存储」+ 免费档「开会员」（235 §三）。画成一张卡：行模板装不下条和两列。 */
@Composable
fun StorageSummaryCard(storage: StorageDTO, onManage: () -> Unit, onUpgrade: () -> Unit) {
    val c = UmbraTheme.colors
    Column {
        UmbraFieldLabel("存储", Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(UmbraDimens.sp2))
        UmbraGroupCard {
            Box(Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp)) { StorageUsageHeader(storage, showKinds = true) }
            UmbraRowDivider()
            CardRow("管理存储", c.text, onManage)
            if (StorageRules.showsUpgrade(storage.tier)) {
                UmbraRowDivider()
                CardRow("开会员", c.orangeText, onUpgrade)
            }
        }
    }
}

/** 和设置行同一副尺寸（14 / 11 / 48），只是落在自己的卡里。 */
@Composable
private fun CardRow(label: String, tint: Color, action: () -> Unit) {
    val c = UmbraTheme.colors
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = UmbraDimens.rowMinH).clickable(onClick = action).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = tint, style = TextStyle(fontSize = 16.sp), modifier = Modifier.weight(1f))
        UmbraIcon(UmbraIconPaths.chevronRight, c.faint, size = 15.dp, strokeWidth = 2.2f)
    }
}

// MARK: - 管理存储（StorageView.swift:238）

/**
 * 「账号 › 管理存储」：顶部同一块用量条；四组依次聊天与附件 / 知识库 / 保险箱 / 回收站，组头「总量 · N 项」，
 * 组内按大小倒序，列不完的末尾「其余 N 项」；左滑一行删除（回收站：找回 / 彻底删除）；右上 ⋯ 清空回收站；下拉刷新。
 */
@Composable
fun StoragePage() {
    val c = UmbraTheme.colors
    var storage by remember { mutableStateOf<StorageDTO?>(null) }
    var lists by remember { mutableStateOf<Map<String, StorageItemsDTO>>(emptyMap()) }
    var loaded by remember { mutableStateOf(false) }
    // 第一次就没拿到：画错误卡。刷新失败但手上还有旧数据时只吐司，列表留着。
    var failText by remember { mutableStateOf<String?>(null) }
    // 一次只跑一个写动作（删 / 找回 / 清空）：两个并发删完各自 reload，列表会抖。
    var busy by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        val (s, f, k, t) = coroutineScope {
            val a = async { AccountApi.storage() }
            val b = async { AccountApi.storageItems("files") }
            val d = async { AccountApi.storageItems("kb") }
            val e = async { AccountApi.storageItems("trash") }
            listOf(a.await(), b.await(), d.await(), e.await())
        }
        val firstErr = listOf(s, f, k, t).firstOrNull { it is ApiResult.Err } as? ApiResult.Err
        if (firstErr == null) {
            storage = (s as ApiResult.Ok<*>).value as StorageDTO
            @Suppress("UNCHECKED_CAST")
            lists = mapOf(
                "files" to (f as ApiResult.Ok<StorageItemsDTO>).value,
                "kb" to (k as ApiResult.Ok<StorageItemsDTO>).value,
                "trash" to (t as ApiResult.Ok<StorageItemsDTO>).value,
            )
            failText = null
        } else {
            val say = accountFailToast("没读到存储用量", firstErr)
            if (storage == null) failText = say else router.showToast(say)
        }
        loaded = true
    }

    /** 跑一个写动作：同一时刻只跑一个，跑完刷新。 */
    fun act(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block() } finally { busy = false }
            reload()
        }
    }

    LaunchedEffect(Unit) { if (!loaded) reload() }
    val trash = lists["trash"]
    val trashCount = trash?.count ?: 0

    UmbraScreen(
        title = "管理存储", onBack = { router.back() }, refreshing = refreshing,
        onRefresh = { scope.launch { refreshing = true; reload(); refreshing = false } },
        actions = {
            var open by remember { mutableStateOf(false) }
            Box {
                UmbraTopBarIcon(UmbraExtraIcons.more, "更多", { open = true })
                DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.card, modifier = Modifier.widthIn(min = 180.dp)) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (trashCount > 0) "清空回收站 · ${StorageRules.fmt(trash?.bytes ?: 0)}" else "清空回收站 · 空",
                                color = if (trashCount == 0 || busy) c.faint else c.dangerText,
                            )
                        },
                        enabled = trashCount > 0 && !busy,
                        onClick = {
                            open = false
                            val tr = lists["trash"] ?: return@DropdownMenuItem
                            val copy = StorageRules.clearTrashAlert(tr.count, tr.bytes)
                            router.confirm(UmbraAlert(copy.title, copy.body, confirmLabel = copy.button, destructive = true) {
                                act {
                                    when (val r = AccountApi.purgeStorageTrash()) {
                                        is ApiResult.Ok -> router.showToast(if (r.value.second > 0) "已清空回收站，腾出 ${StorageRules.fmt(r.value.second)}" else "已清空回收站")
                                        is ApiResult.Err -> router.showToast(accountFailToast("没清成", r))
                                    }
                                }
                            })
                        },
                    )
                }
            }
        },
    ) {
        val s = storage
        when {
            s != null -> {
                val face = StorageFace(s)
                if (face.overLimit) item("banner") {
                    StorageBanner(StorageRules.expiredBanner(s.limit), "续费会员", { router.go(Route.SetShop) })
                }
                item("head") {
                    Column {
                        UmbraGroupCard { Box(Modifier.padding(14.dp)) { StorageUsageHeader(s, showKinds = false) } }
                        Spacer(Modifier.height(6.dp))
                        MeNote("组内从大到小。左滑一行删除：文件删了马上腾出，知识库文档先进回收站。下拉刷新。", size = 12f)
                    }
                }
                StorageRules.groups.forEach { (kind, label) ->
                    item("g-$kind") {
                        StorageGroup(kind, label, s, lists[kind], onDelete = { item, keepDays ->
                            val copy = if (kind == "kb") StorageRules.deleteKbDocAlert(item.name, item.bytes, keepDays)
                            else StorageRules.deleteFileAlert(item.name, item.bytes)
                            router.confirm(UmbraAlert(copy.title, copy.body, confirmLabel = copy.button, destructive = true) {
                                act { if (kind == "kb") deleteKbDoc(item) else deleteFile(item) }
                            })
                        }, onPurge = { item ->
                            val copy = StorageRules.purgeAlert(item.name, item.bytes)
                            router.confirm(UmbraAlert(copy.title, copy.body, confirmLabel = copy.button, destructive = true) {
                                act {
                                    val ok = AccountApi.purgeKbDoc(item.id)
                                    router.showToast(if (ok) "已彻底删除「${item.name}」" else "没删成「${item.name}」，联网后再试")
                                }
                            })
                        }, onRestore = { item ->
                            // 找回不确认（235 §二）：它是把东西放回去，手滑的代价是再删一次。restored:0 不算成功。
                            act {
                                val n = AccountApi.restoreKbDoc(item.id)
                                router.showToast(
                                    when {
                                        n != null && n > 0 -> StorageRules.restoredToast(item.name, item.source)
                                        n == null -> "没找回「${item.name}」，联网后再试"
                                        else -> "「${item.name}」已经不在回收站里了"
                                    },
                                )
                            }
                        })
                    }
                }
            }
            failText != null -> item("fail") {
                UmbraErrorCard(title = "拿不到存储用量", body = failText, actions = listOf("重试" to { scope.launch { reload() }; Unit }))
            }
            else -> item("loading") { MePlaceholder("正在读取…") }
        }
    }
}

@Composable
private fun MePlaceholder(text: String) = com.geek.umbra.feature.me.MePlaceholderRow(text)

/** 一组：组头「标签 …… 总量 · N 项」+ 条目（左滑）/ 保险箱一行 / 空态 +「其余 N 项」。 */
@Composable
private fun StorageGroup(
    kind: String, label: String, s: StorageDTO, list: StorageItemsDTO?,
    onDelete: (StorageItemDTO, Int?) -> Unit, onPurge: (StorageItemDTO) -> Unit, onRestore: (StorageItemDTO) -> Unit,
) {
    val c = UmbraTheme.colors
    val vaultBytes = s.by_kind.vault ?: 0
    val total = if (kind == "vault") vaultBytes else (list?.bytes ?: 0)
    val count = if (kind == "vault") 0 else (list?.count ?: 0)
    Column {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            UmbraFieldLabel(label)
            Spacer(Modifier.weight(1f))
            StorageRules.groupMeta(total, count)?.let { Text(it, color = c.muted, style = TextStyle(fontSize = 12.sp)) }
        }
        Spacer(Modifier.height(6.dp))
        UmbraGroupCard {
            when {
                // 保险箱列不出（listable = false）：只画一行，没有条目、没有左滑（235 §二）。
                kind == "vault" -> PlainLine(StorageRules.vaultRow(s.tier, vaultBytes))
                list != null && list.items.isNotEmpty() -> {
                    StorageRules.sorted(list.items).forEachIndexed { i, item ->
                        if (i > 0) UmbraRowDivider(Modifier.padding(start = 14.dp))
                        val actions = if (kind == "trash") listOf(
                            // 破坏性动作不滑到底执行，点了还要过确认弹窗。
                            UmbraSwipeAction("彻底删除", UmbraIconPaths.trash, c.danger, c.onDanger) { onPurge(item) },
                            UmbraSwipeAction("找回", UmbraIconPaths.repeatArrows, c.muted) { onRestore(item) },
                        ) else listOf(UmbraSwipeAction("删除", UmbraIconPaths.trash, c.danger, c.onDanger) { onDelete(item, list.keep_days) })
                        UmbraRevealSwipeRow(trailing = actions) { ItemRow(kind, item) }
                    }
                    StorageRules.restLine(list.rest)?.let { (text, size) ->
                        // 这一行不能操作（234 §六）：它说的是「列表之外还有」，不是一条条目。
                        UmbraRowDivider(Modifier.padding(start = 14.dp))
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp)) {
                            Text(text, color = c.muted, style = TextStyle(fontSize = 13.sp), modifier = Modifier.weight(1f))
                            Text(size, color = c.muted, style = TextStyle(fontSize = 13.sp))
                        }
                    }
                }
                else -> PlainLine(StorageRules.emptyText(kind))
            }
        }
    }
}

@Composable
private fun PlainLine(text: String) {
    Text(text, color = UmbraTheme.colors.muted, style = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp))
}

/** 一行：名字 / 副行 / 大小。 */
@Composable
private fun ItemRow(kind: String, item: StorageItemDTO) {
    val c = UmbraTheme.colors
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = UmbraDimens.rowMinHSub).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(item.name, color = c.text, style = TextStyle(fontSize = 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(StorageRules.subline(kind, item, storageDay(item.at_ms)), color = c.muted, style = TextStyle(fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Text(StorageRules.fmt(item.bytes), color = c.muted, style = TextStyle(fontSize = 15.sp), maxLines = 1)
    }
}

/** `DELETE /storage/files/{id}`：当场腾出，不进回收站。 */
private suspend fun deleteFile(item: StorageItemDTO) {
    when (val r = AccountApi.deleteStorageFile(item.id)) {
        is ApiResult.Ok -> router.showToast(if (r.value > 0) "已删除「${item.name}」，腾出 ${StorageRules.fmt(r.value)}" else "已删除「${item.name}」")
        is ApiResult.Err -> router.showToast(accountFailToast("没删成", r))
    }
}

/**
 * 知识库文档：走知识库自己那条 `DELETE /kb/{kb}/docs/{doc}`（带 If-Match，进回收站）。`/storage/items` 不回 blob_sha，
 * 所以先把那个库的文档列表拉一遍拿 sha —— 不绕开「没有 sha 就不发」那道保护（kb-delete-fail-open-without-sha）。
 */
private suspend fun deleteKbDoc(item: StorageItemDTO) {
    val ctx = AppGraph.context
    val kbId = item.kb_id ?: run { router.showToast("这份文档没带库的 id，到知识库里删"); return }
    val (lr, docs) = KbNet.fetchKbDocs(kbId)
    val doc = docs?.docs?.firstOrNull { it.doc_id == item.id }
    if (doc == null) {
        val o = KnowledgeRules.outcome(lr)
        router.showToast(if (o == KbOutcome.Ok) ctx.getString(R.string.kb_fail_missing) else kbFailText(o))
        return
    }
    when (val o = KnowledgeRules.outcome(KbNet.deleteKbDoc(kbId, doc.doc_id, doc.blob_sha))) {
        KbOutcome.Ok -> router.showToast(ctx.getString(R.string.kb_deleted))
        KbOutcome.Cancelled -> Unit
        else -> router.showToast(kbFailText(o))
    }
}
