// 记账 · 流水屏 —— 照抄 iOS Features/Money/MoneyViews.swift:642-1286 UmbraMoneyListView。
//
// iOS 的 List(insetGrouped) + swipeActions → 这里是 UmbraScreen 的 LazyColumn + 每行一个 SwipeToDismissBox：
// 左滑**只露出按钮**（编辑 / 移到另一个账本 / 删除），不滑到底自动执行；一次只开一行（iOS 系统 swipeActions 的行为）。
// 每个按日分组画成一张圆角卡（insetGrouped 的样子）：组头在卡外，行在卡内、行间满宽分隔线。
package com.geek.umbra.feature.money

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraEmptyState
import com.geek.umbra.designsystem.UmbraFilterChips
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraExtraIcons
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraRevealSwipeRow
import com.geek.umbra.designsystem.UmbraScreen
import com.geek.umbra.designsystem.UmbraSegment
import com.geek.umbra.designsystem.UmbraSegmentedControl
import com.geek.umbra.designsystem.UmbraSwipeAction
import com.geek.umbra.designsystem.UmbraTagPill
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraTopBarIcon
import com.geek.umbra.designsystem.UmbraType
import com.geek.umbra.designsystem.umbraPhaseTop
import com.geek.umbra.designsystem.umbraScreenPhase
import com.geek.umbra.navigation.Route
import com.geek.umbra.navigation.UmbraAlert
import com.geek.umbra.net.Ledger
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 方向分段：值与 MoneyStore.listDir 一致。 */
private val DIRS = listOf("all" to "全部", "expense" to "支出", "income" to "收入")

/** 按日分组（或那一筛开着时的「往季」/「本季」）。iOS: MoneyViews.swift:910 */
private data class DayGroup(
    val day: String,
    val items: List<MoneyEntryDTO>,
    val spend: Long,
    /** 这一组是不是**往季**那一组 —— 只影响行上那一格念季度还是念截止日。 */
    val past: Boolean = false,
)

/** 流水屏。iOS: MoneyViews.swift:644 */
@Composable
fun MoneyListView() {
    val c = UmbraTheme.colors
    val router = AppGraph.router
    val phase by MoneyStore.phase.watch()
    val loadedOnce by MoneyStore.loadedOnce.watch()
    val stats by MoneyStore.stats.watch()
    val entries by MoneyStore.entries.watch()
    val listDir by MoneyStore.listDir.watch()
    val listCat by MoneyStore.listCat.watch()
    val rcptOnly by MoneyStore.rcptOnly.watch()
    val rcptRows by MoneyStore.rcptRows.watch()
    val rcptTotal by MoneyStore.rcptTotal.watch()
    val loadedYm by MoneyStore.loadedYm.watch()
    // 当前账本：**直接订阅正本**，不在这一层留镜像（镜像只在切换那一瞬间不一致）。
    val ledger by MoneyStore.ledger.watch()
    // ⚠️ 这一行不读 cats，所以并不订阅（by 委托不读就不订阅）；分类名 / 色槽跟着变靠 CatChips、EntryRow 里的 watchMoneyCats()。
    @Suppress("UNUSED_VARIABLE") val cats by MoneyStore.cats.watch()
    val offline by AppGraph.http.offline.watch()
    var refreshing by remember { mutableStateOf(false) }
    /** 当前左滑开着的那一行（一次只开一行）。 */
    val openRow = remember { mutableStateOf<Any?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { MoneyStore.loadIfNeeded() }

    val screen = remember(phase, loadedOnce, stats, offline) { MoneyStore.screenPhase(offline) }

    // ⚠️ 这一筛开着时，源是**整季**那一份（rcptRows），不是本月这一份 ——
    // 一个按季说话的界面读一个月的数据，它不会报错，只会在人翻到别的月份时安静地少给几行。iOS: MoneyViews.swift:895
    val src = if (rcptOnly) rcptRows ?: emptyList() else entries
    val filtered = src.filter { e -> (listDir == "all" || e.direction == listDir) && (listCat == null || e.cat == listCat) }
    val groups = groupsOf(filtered, rcptOnly)
    // 动态字号档位（批次 227 §二 3）：iOS 按 dynamicTypeSize 分档；Android 按系统字体缩放近似 ——
    // ≥1.3 记作 xxLarge 起，≥1.6 记作 AX1 起（流水行改纵排）。
    val fontScale = LocalDensity.current.fontScale
    val stacks = fontScale >= 1.6f
    val titleLines = if (fontScale >= 1.6f) 3 else if (fontScale >= 1.3f) 2 else 1
    val subLines = if (fontScale >= 1.3f) 2 else 1

    UmbraScreen(
        title = "流水",
        onBack = { router.back() },
        actions = {
            // 「导出账目」的入口（D3 / 162 §一）：分享钮，＋ 左边。不是 ⋯ 菜单（只有一项的菜单是一层壳），也不放进设置。
            // 一笔都没有时不给这颗钮：点了只会得到一张说「共 0 笔」的面板。
            // ⚠️ 待补票那一筛开着时看的是那一筛的源（整季含往季），不是本月。
            UmbraTopBarIcon(
                UmbraIconPaths.shareUp, "导出",
                {
                    presentMoneyExport(
                        filtered = filtered,
                        ym = loadedYm,
                        // 「全部」那一档不进范围句 —— 它不是一个筛选条件，是「没有筛选」。
                        dirLabel = if (listDir == "expense") "支出" else if (listDir == "income") "收入" else null,
                        catLabel = listCat?.let { MoneyStore.catName(it) },
                        rcpt = exportRcpt(rcptOnly, rcptRows, rcptTotal),
                    )
                },
                tint = c.orange,
                enabled = src.isNotEmpty(),
            )
            UmbraTopBarIcon(UmbraIconPaths.plus, "记一笔", { router.go(Route.MoneyAdd(null)) }, tint = c.orange)
        },
        refreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                MoneyStore.reload(silent = true)
                refreshing = false
            }
        },
        spacing = 0.dp,
        contentPadding = PaddingValues(start = UmbraDimens.pagePadX, end = UmbraDimens.pagePadX, top = 2.dp, bottom = 24.dp),
        // 三档挂在列表外面：骨架 / 错误卡整屏换掉列表，离线条钉在列表上方不跟着滚。
        topContent = umbraPhaseTop(screen, live = true) { MoneyStore.launch { MoneyStore.retry() } },
    ) {
        umbraScreenPhase(screen, { MoneyStore.launch { MoneyStore.retry() } }, "拿不到流水") {
            item(key = "dir") {
                UmbraSegmentedControl(
                    DIRS.map { UmbraSegment(it.second) },
                    selected = DIRS.indexOfFirst { it.first == listDir }.coerceAtLeast(0),
                    onSelect = { MoneyStore.listDir.value = DIRS[it].first },
                    modifier = Modifier.padding(bottom = UmbraDimens.sp2),
                )
            }
            // 分类胶囊只列**当前方向下真的出现过**的分类（列全量的话大多数点了都是空结果）。
            // ⚠️ **经营账本里这一排整块不渲染**（noCategoryFilterInBiz）：它在那个账本里既是错的词表，又是一颗死控件。
            if (ledger != Ledger.Business) {
                item(key = "cats") { CatChips(entries, listDir, listCat) }
            }
            if (filtered.isEmpty()) {
                item(key = "empty") { EmptyStates(rcptOnly, entries.isEmpty()) }
            } else {
                // ⚠️ 这一筛开着时不按天分组，按「往季 / 本季」分；组头只挂条数不挂合计。
                groups.forEachIndexed { gi, g ->
                    item(key = "h-$gi-${g.day}") {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = if (gi == 0) 4.dp else 18.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            Text(g.day, color = c.muted, style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
                            Text(
                                if (rcptOnly) "${g.items.size} 笔" else if (g.spend > 0) "支出 ${MoneyFmt.yuan(g.spend)}" else "仅收入",
                                color = c.faint, style = TextStyle(fontSize = 12.sp, fontFamily = MoneyMono),
                            )
                        }
                    }
                    g.items.forEachIndexed { i, e ->
                        item(key = "e-${e.id}") {
                            val first = i == 0
                            val last = i == g.items.lastIndex
                            val r = UmbraDimens.radiusCard
                            val shape = RoundedCornerShape(
                                topStart = if (first) r else 0.dp, topEnd = if (first) r else 0.dp,
                                bottomStart = if (last) r else 0.dp, bottomEnd = if (last) r else 0.dp,
                            )
                            SwipeRow(e, shape, first, ledger, openRow) {
                                EntryRow(e, g.past, stacks, titleLines, subLines)
                            }
                        }
                    }
                }
                item(key = "foot") {
                    Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(UmbraDimens.sp3)) {
                        FootChip(filtered, rcptOnly, rcptTotal, (rcptRows?.size ?: 0) < rcptTotal)
                        RcptBar(rcptOnly, rcptRows, rcptTotal)
                        // ⚠️ 这句话跟着行的行为走：行上多了「点开看详情」/ 左滑多了一项，这里就得说出来 ——
                        // 一个看不见的手势等于没有。
                        Text(
                            "点一行看详情，左滑可以编辑、移到另一个账本或删除。",
                            color = c.muted, style = TextStyle(fontSize = 11.5.sp), modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

// MARK: 筛选

/** 分类胶囊：「全部分类」+ 当前方向下真的出现过的那些分类。iOS: MoneyViews.swift:873 */
@Composable
private fun CatChips(entries: List<MoneyEntryDTO>, listDir: String, listCat: String?) {
    watchMoneyCats() // 胶囊上的分类名读 cats 快照：在这一层订阅，同步改了分类而流水没变时才不画旧名
    val seen = ArrayList<String>()
    for (e in entries) {
        if (listDir != "all" && e.direction != listDir) continue
        if (e.cat !in seen) seen.add(e.cat)
    }
    UmbraFilterChips(Modifier.padding(bottom = UmbraDimens.sp2)) {
        UmbraTagPill("全部分类", selected = listCat == null, onClick = { MoneyStore.listCat.value = null })
        seen.forEach { slug ->
            UmbraTagPill(MoneyStore.catName(slug), selected = listCat == slug, onClick = { MoneyStore.listCat.value = slug })
        }
    }
}

/**
 * 待补票那一筛开着时，导出要说出它真正的口径（台账 export-rcpt-filter-mislabeled）：截至哪一季、服务端一共几笔、拿回来几笔。
 * 上界取 ReceiptKit.pendingThrough —— 和 store 拉那一份时传的是同一个值。iOS: MoneyViews.swift:885
 */
private fun exportRcpt(rcptOnly: Boolean, rows: List<MoneyEntryDTO>?, total: Int): MoneyExportRcpt? {
    if (!rcptOnly || rows == null) return null
    val now = System.currentTimeMillis()
    val (y, q) = ReceiptKit.currentQuarter(now, MoneyFmt.tzOffsetMin)
    return MoneyExportRcpt(
        through = ReceiptKit.pendingThrough(now, MoneyFmt.tzOffsetMin),
        throughSpoken = ReceiptKit.quarterLabel(y, q),
        total = total, fetched = rows.size,
    )
}

/** 日分组的格式「8月19日 周三」（本机时区、中文星期）。 */
private val DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 EEE", Locale.CHINA)

/**
 * 分组。那一筛开着时换成「往季 / 本季」两组（未来季那一笔两组都不进：一笔还没发生的账谈不上「待补票」）；
 * 否则按日分组 —— 服务端已按 at_ms 降序排好，这里只分组不重排（排序两处做迟早对不上）。iOS: MoneyViews.swift:918
 */
private fun groupsOf(filtered: List<MoneyEntryDTO>, rcptOnly: Boolean): List<DayGroup> {
    if (rcptOnly) {
        val now = System.currentTimeMillis()
        val past = ArrayList<MoneyEntryDTO>()
        val cur = ArrayList<MoneyEntryDTO>()
        for (e in filtered) {
            when (ReceiptKit.bucket(e.receipt_state, e.at_ms, e.tz_offset_min, now)) {
                ReceiptKit.PendingBucket.Past -> past.add(e)
                ReceiptKit.PendingBucket.Current -> cur.add(e)
                ReceiptKit.PendingBucket.Future, ReceiptKit.PendingBucket.NotPending -> {}
            }
        }
        return listOf(DayGroup("往季", past, 0, past = true), DayGroup("本季", cur, 0)).filter { it.items.isNotEmpty() }
    }
    val zone = ZoneId.systemDefault()
    val out = ArrayList<Pair<String, ArrayList<MoneyEntryDTO>>>()
    for (e in filtered) {
        val label = DAY_FMT.format(Instant.ofEpochMilli(e.at_ms).atZone(zone))
        if (out.lastOrNull()?.first == label) out.last().second.add(e) else out.add(label to arrayListOf(e))
    }
    return out.map { (k, items) -> DayGroup(k, items, items.filter { it.direction == "expense" }.sumOf { it.cents }) }
}

// MARK: 空态

/**
 * 「空」和「无结果」是两件不同的事（states.emptyVsNoResult）：空态说「怎么开始」并给主动作，无结果说「改什么条件」并给「清掉筛选」。
 * ⚠️ 待补票那一筛有自己的空态（receiptTodoIsAFilter）：他刚刚做完的事正是把它清空，不能再请他去清一个筛选。iOS: MoneyViews.swift:702
 */
@Composable
private fun EmptyStates(rcptOnly: Boolean, monthEmpty: Boolean) {
    when {
        rcptOnly -> UmbraEmptyState(
            UmbraIconPaths.wallet, ReceiptKit.PENDING_EMPTY,
            hint = "一笔账的票据状态是「待补票」时会回到这里 —— 记账时选了「待补票」，或者从生活账本挪进来的那一笔。",
            action = "退出这一筛", onAction = { MoneyStore.rcptOnly.value = false },
        )
        monthEmpty -> UmbraEmptyState(
            UmbraIconPaths.wallet, "这个月还没有记录",
            hint = "记一笔，流水会按天分组出现在这里。",
            action = "记一笔", onAction = { AppGraph.router.go(Route.MoneyAdd(null)) },
        )
        else -> UmbraEmptyState(
            UmbraIconPaths.filter, "这个筛选条件下没有记录",
            hint = "这个月是有记录的，只是不符合当前的方向或分类。放宽条件再看看。",
            action = "清掉筛选", onAction = { MoneyStore.listDir.value = "all"; MoneyStore.listCat.value = null },
        )
    }
}

// MARK: 行

/**
 * 流水一行的左滑（批次 201：「记账流水那三颗钮照搬进同一个件」）：移到另一个账本 / 编辑 / 删除，走 designsystem 的
 * [UmbraRevealSwipeRow] —— 只露按钮、拖到底不执行，删除仍过确认弹窗；同一时刻只开一行（[openRow]）。
 * 整行可点 → 详情；摊开时点行身只合上（公共件盖的那一层管）。
 */
@Composable
private fun SwipeRow(
    e: MoneyEntryDTO,
    shape: Shape,
    first: Boolean,
    ledger: Ledger,
    openRow: MutableState<Any?>,
    content: @Composable () -> Unit,
) {
    val c = UmbraTheme.colors
    val actions = listOf(
        // 「移到另一个账本」（G3）：沿用现有行操作多一项，不新画形制。不进详情（挪这一下不可撤销地清掉科目）。
        if (ledger == Ledger.Business) UmbraSwipeAction("移回生活", UmbraIconPaths.wallet, c.muted) { confirmMove(e, ledger) }
        else UmbraSwipeAction("移到经营", UmbraIconPaths.briefcase, c.muted) { confirmMove(e, ledger) },
        UmbraSwipeAction("编辑", UmbraExtraIcons.edit, c.warning, c.onWarning) { AppGraph.router.go(Route.MoneyAdd(e.id)) },
        // 删除放最靠边那一个。
        UmbraSwipeAction("删除", UmbraIconPaths.trash, c.danger, c.onDanger) { confirmDelete(e) },
    )
    UmbraRevealSwipeRow(trailing = actions, openRow = openRow, rowKey = e.id, modifier = Modifier.fillMaxWidth().clip(shape).background(c.card)) {
        Box(Modifier.fillMaxWidth().background(c.card).clickable(role = Role.Button) { showMoneyEntryDetail(e) }) {
            content()
            if (!first) MoneyRowSeparator(Modifier.align(Alignment.TopCenter))
        }
    }
}

/** 流水的一行。iOS: MoneyViews.swift:989 */
@Composable
private fun EntryRow(e: MoneyEntryDTO, past: Boolean, stacks: Boolean, titleLines: Int, subLines: Int) {
    val c = UmbraTheme.colors
    watchMoneyCats() // 色槽 / 图标 / 分类名读 cats 快照：这一行参数没变会被跳过，得在这一层订阅分类
    val income = e.direction == "income"
    Row(
        Modifier.fillMaxWidth().heightIn(min = UmbraDimens.rowMinH).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = if (stacks) Alignment.Top else Alignment.CenterVertically,
    ) {
        // 分类色块（批次 003）：同色 tint + 色槽色。方向由右侧金额颜色表达，图标块只回答「这是哪个分类」。
        // ⚠️⚠️ **经营的流水行左边没有色块**（receiptStatusInList ③）：科目没有色槽，灰方块是说不出话的占位符；
        // 副产物：行左边有没有色块本身就说明了这是哪个账本。iOS 写死 32 / 圆角 10。
        if (!e.isBiz) {
            MoneyCatBlock(MoneyStore.catSlot(e.cat), MoneyStore.catArt(e.cat), block = 32.dp, iconSize = 16.dp, corner = 10.dp)
            Spacer(Modifier.width(11.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // ⚠️⚠️ **那一格自己说出它空着**（C9）：判据是「经营的行 + 科目空」，生活账本没有「缺科目」这回事。走琥珀不走红：它是待办不是错误。
                // 只念、不单独可点：这一行本身整行可点 + 可左滑，再给那一格一个点击目标就是两个目标叠在一处。
                if (e.isBiz && e.cat.isEmpty()) {
                    Text(MoneyCopy.CAT_MISSING, color = c.warningText, style = TextStyle(fontSize = 14.5.sp, fontWeight = UmbraType.weight560), maxLines = titleLines, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                } else {
                    Text(
                        MoneyStore.catName(e.cat) + (if (e.sub.isEmpty()) "" else " · ${e.sub}"),
                        color = c.text, style = TextStyle(fontSize = 14.5.sp), maxLines = titleLines, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                // 来源徽章只是标记，不给点击 —— 14px 高的命中区必然误触，而且它嵌在本身可左滑的行里（稿的原话）。
                MoneySrc.badge(e.src)?.let { (label, icon) ->
                    val recur = e.src == "recur"
                    val fg = if (recur) c.orangeText else c.faint
                    Spacer(Modifier.width(6.dp))
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(if (recur) c.orangeSoft else c.chip).padding(horizontal = 6.dp, vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        UmbraIcon(icon, fg, size = 10.dp, strokeWidth = 2.4f)
                        Spacer(Modifier.width(3.dp))
                        Text(label, color = fg, style = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold))
                    }
                }
            }
            if (e.merchant.isNotEmpty()) {
                Text(e.merchant, color = c.faint, style = TextStyle(fontSize = 12.sp), maxLines = subLines, overflow = TextOverflow.Ellipsis)
            }
            ReceiptLine(e, past)
            if (stacks) AmountBlock(e, income, stacked = true)
        }
        if (!stacks) {
            Spacer(Modifier.width(6.dp))
            AmountBlock(e, income, stacked = false)
        }
    }
}

/**
 * 票据状态那一行（G11 / receiptStatusInList）。⚠️ 只有不正常的那个挂徽章 —— 「有票」不画（人人都有的徽章等于没有）。
 * ⚠️ 「待补票」季末前 5 天转琥珀，是**渲染时算的**（库里只有 pending）。两档文字一样，只有颜色是强度 —— 状态永不只靠颜色表意。
 * ⚠️ 往季那一组念的是**季度**，不是截止日（那个截止日已经过去了，年份 + 季号永远是真的）。iOS: MoneyViews.swift:966
 */
@Composable
private fun ReceiptLine(e: MoneyEntryDTO, past: Boolean) {
    if (e.receipt != ReceiptState.Pending) return
    val c = UmbraTheme.colors
    val tone = ReceiptKit.pendingTone(e.at_ms, e.tz_offset_min, System.currentTimeMillis())
    val (dm, dd) = ReceiptKit.dueDate(e.at_ms, e.tz_offset_min)
    val (qy, qq) = ReceiptKit.quarterOf(e.at_ms, e.tz_offset_min)
    val amber = tone == ReceiptKit.Tone.Amber
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "待补票", color = if (amber) c.warningText else c.faint,
            style = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (amber) c.warningSoft else c.chip).padding(horizontal = 6.dp, vertical = 1.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(if (past) ReceiptKit.quarterLabel(qy, qq) else "$dm 月 $dd 日前", color = c.faint, style = TextStyle(fontSize = 10.5.sp))
    }
}

/**
 * 行的尾值：金额 +（不可扣时）「不进成本」。横排时在右侧一列靠右；纵排时落到下面靠左、不截断。
 * ⚠️ 不可扣那一笔金额转 faint + 念「不进成本」—— 一条减项事实，说在金额那一侧。
 * ⚠️⚠️ 判据走 ReceiptState.notCost(raw, income)，先问 receipt_state 在不在：用 `receipt == NoReceipt` 的话
 * 生活账本里每一条支出都会转灰（receipt 把 null 折成了「无票不可扣」）。iOS: MoneyViews.swift:1103
 */
@Composable
private fun AmountBlock(e: MoneyEntryDTO, income: Boolean, stacked: Boolean) {
    val c = UmbraTheme.colors
    val notCost = ReceiptState.notCost(e.receipt_state, income)
    Column(horizontalAlignment = if (stacked) Alignment.Start else Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            MoneyFmt.signed(e.cents, income),
            color = if (notCost) c.faint else if (income) c.success else c.text,
            style = TextStyle(fontSize = 15.5.sp, fontWeight = UmbraType.weight560, fontFamily = MoneyMono),
            maxLines = if (stacked) Int.MAX_VALUE else 1,
        )
        if (notCost) Text("不进成本", color = c.faint, style = TextStyle(fontSize = 10.5.sp))
    }
}

// MARK: 行操作

/**
 * 删除确认。「移入回收站，保留 30 天」+ 跨端后果说在前面。
 * 「随时可以恢复」删掉了（批次 029 点名禁）：它是假的 —— 只有 30 天。iOS: MoneyViews.swift:1129
 */
private fun confirmDelete(e: MoneyEntryDTO) {
    val router = AppGraph.router
    val name = e.merchant.ifEmpty { MoneyStore.catName(e.cat) }
    router.confirm(
        UmbraAlert(
            title = "删除「$name · ${MoneyFmt.yuan(e.cents)}」这一笔？",
            message = "删除后移入回收站，保留 30 天。其它设备上的这条也会一并删掉。",
            confirmLabel = "删除",
            destructive = true,
        ) {
            MoneyStore.launch {
                val ok = MoneyStore.delete(e.id)
                router.showToast(if (ok) "已移入回收站" else "没删掉，稍后再试")
            }
        },
    )
}

/**
 * 「移到另一个账本」的确认（G3 / consequenceSpeaksAtTheAction）。不是一句「已挪走」的吐司 —— 要说的是「按下去会发生什么」：
 * 科目会清、票据状态会落「待补票」、以及它会出现在「待补票」的哪一组。
 * ⚠️ 挪完那一句和按下之前那一句，对「是不是往季那一笔」的说法必须一致 —— 用的是同一个 isPast。iOS: MoneyViews.swift:1165
 */
private fun confirmMove(e: MoneyEntryDTO, current: Ledger) {
    val router = AppGraph.router
    val to = current.other
    val name = e.merchant.ifEmpty { MoneyStore.catName(e.cat) }
    val nowQ = ReceiptKit.currentQuarter(System.currentTimeMillis(), e.tz_offset_min)
    val itsQ = ReceiptKit.quarterOf(e.at_ms, e.tz_offset_min)
    val isPast = nowQ != itsQ
    val hasCat = e.cat.trim().isNotEmpty()
    val lines = ArrayList<String>()
    if (to == Ledger.Business) {
        if (hasCat) lines.add("它现在的分类会清掉 —— 经营账本用的是税表科目，要重新选一个。")
        lines.add("票据状态会落「待补票」。")
        lines.add(
            if (isPast) "这一笔会立刻出现在「待补票」的「往季」那一组里（${itsQ.first} 年第 ${itsQ.second} 季）。"
            else "这一笔会立刻出现在「待补票」里。",
        )
    } else {
        lines.add("发票号、税率这些经营字段在生活账本上不显示，但一直留着 —— 再移回经营时不用重填。")
    }
    router.confirm(
        UmbraAlert(
            title = if (to == Ledger.Business) "把「$name · ${MoneyFmt.yuan(e.cents)}」移到经营账本？"
            else "把「$name · ${MoneyFmt.yuan(e.cents)}」移回生活账本？",
            message = lines.joinToString("\n"),
            confirmLabel = "移过去",
        ) {
            MoneyStore.launch {
                when (val r = MoneyStore.moveEntry(e.id, to)) {
                    is MoneyResult.Ok -> {
                        // ⚠️⚠️ 挪进经营之后科目是空的（一条没有科目的经营账上不了税表）：把「记一笔」推上来、停在科目那一格。
                        // ⚠️ **先切账本再推** —— 那一笔现在在经营账本里，而那一屏保存时走的是当前账本那条路。
                        if (to == Ledger.Business && hasCat) {
                            MoneyStore.pickLedger(to)
                            // 挪过去的那一笔可能不在经营账本的当前月里：把它的月份留给 store（umbra-ios#37）。
                            MoneyStore.hint(e.id, e.ym)
                            router.go(Route.MoneyAdd(e.id, focusAccount = true))
                        }
                        if (to == Ledger.Business && isPast) {
                            router.showToast("已移入经营账本 · 待补票（${itsQ.first} 年第 ${itsQ.second} 季）")
                        } else {
                            router.showToast(if (to == Ledger.Business) "已移入经营账本 · 票据状态待补票" else "已移回生活账本")
                        }
                    }
                    // 服务端那句话是分档写好的（没这一笔 / 已经在那边了）—— 拿不到才兜一句，不把三种失败合成一句。
                    is MoneyResult.Failed -> router.showToast(r.detail.ifEmpty { "没移过去，稍后再试" })
                }
            }
        },
    )
}

// MARK: 底部

/**
 * 「本季待补票」那一筛的入口（G5 / receiptTodoIsAFilter）：它不是一页，是这张列表的一个筛选态。
 * ⚠️ 拿不到（rcptRows == null）时这颗钮不出，而不是写「0 笔待补票」—— 那是一个看起来像答案的假话。
 * 进 / 出这一筛都把别的筛选放掉：那一筛换掉的是整份行的集合。iOS: MoneyViews.swift:1233
 */
@Composable
private fun RcptBar(rcptOnly: Boolean, rows: List<MoneyEntryDTO>?, total: Int) {
    val pending = if (rows == null) 0 else total
    if (!rcptOnly && pending <= 0) return
    val c = UmbraTheme.colors
    val past = (rows ?: emptyList()).count {
        ReceiptKit.bucket(it.receipt_state, it.at_ms, it.tz_offset_min, System.currentTimeMillis()) == ReceiptKit.PendingBucket.Past
    }
    // iOS 写死圆角 12（与合计条同一个）。
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.fillMaxWidth().heightIn(min = UmbraDimens.tapMin).clip(shape).background(if (rcptOnly) c.orange else c.orangeSoft)
            .clickable(role = Role.Button) {
                MoneyStore.rcptOnly.value = !rcptOnly
                MoneyStore.listDir.value = "all"
                MoneyStore.listCat.value = null
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (rcptOnly) "退出这一筛" else ReceiptKit.pendingLabel(pending, past),
            // 实底橙上的字：iOS 用 .white；这里用 card（亮色下是白，暗色下深色字对实底橙对比更稳）。
            color = if (rcptOnly) c.card else c.orangeText,
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
        )
    }
}

/**
 * 合计条（通栏一条）。⚠️ 那一筛开着时不念「本月」也不念「本季」（含往季之后那个限定词是假的）；
 * 被截断时要明说：前面那个数是服务端的总数，后半句的 N 是屏上这几行（设计侧 218 §五 151§四2）。iOS: MoneyViews.swift:1256
 */
@Composable
private fun FootChip(filtered: List<MoneyEntryDTO>, rcptOnly: Boolean, rcptTotal: Int, truncated: Boolean) {
    val c = UmbraTheme.colors
    val exp = filtered.filter { it.direction == "expense" }.sumOf { it.cents }
    val inc = filtered.filter { it.direction == "income" }.sumOf { it.cents }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.chip).padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (rcptOnly) {
                if (truncated) "$rcptTotal 笔${ReceiptKit.truncatedSuffix(filtered.size)}" else "${filtered.size} 笔"
            } else "共 ${filtered.size} 笔",
            color = c.muted, style = TextStyle(fontSize = 12.5.sp), modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "支出 ${MoneyFmt.yuan(exp)} · 收入 ${MoneyFmt.yuan(inc)}",
            color = c.text, style = TextStyle(fontSize = 12.5.sp, fontWeight = UmbraType.weight560, fontFamily = MoneyMono),
        )
    }
}

