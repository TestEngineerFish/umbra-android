// 分类管理（money.cats）+ 单个分类的子类管理（money.cat）+ 经营账本的只读科目表。
// 照 iOS Features/Money/MoneyCatsView.swift / MoneyAccountsView.swift 逐段移植。
//
// 结构照稿：一级行**点进** money.cat（子类的看/加/改名/删都在那页），
// 行上左滑 = 改名 / 换图标 / 停用（批次 003 定稿的交互）；右上角 + 新增分类
// （先选记在哪一边，再起名字 —— 稿的两步弹层）。
//
// 三条不变的口径（iOS 文件头）：
//   · slug 永不变，改名只改显示名，历史数据不动；
//   · 停用不是删除：选择器里不再出现，历史流水照旧归它，随时能启用；
//     用词是「停用 ↔ 启用」，不是「停用 ↔ 恢复」——「恢复」在这个 App 里归回收站；
//     locked（其他 / 其他-收入）是兜底分类，不给停用；
//   · 子类只是**选择器候选**：加/改名/删都只影响以后记账 —— 流水里存的是中文字符串，历史行一个字不动。
//
// 批次 020 §三 的拖拽排序：**收入与支出各排各的**（两个分组各自一套顺序）。排序**不碰色槽**：
// 颜色跟实体走、不跟位置走，拖一下就换色的话，环形图和历史流水的颜色会在用户没改任何数据时集体变掉。
//
// ⚠️ Android 差异：
//   · iOS 的 List.onMove 是「长按整行拖」。Compose 没有现成的可拖 LazyColumn，这里每个方向的分组画成
//     一张卡（一个 LazyColumn item），行尾放一个把手，`detectDragGesturesAfterLongPress` 记偏移自己排 ——
//     整行长按留给系统的点击 / 左滑，不和它抢。
//   · iOS 的 alert 里能放 TextField；Android 的 UmbraAlert 只有两颗钮，所以起名 / 改名走底部弹层（moneyAskText）。
//   · iOS 的 UmbraSheet(items:) / UmbraSheet(icons:) 在 Android 地基里没有现成件（Router 的 UmbraSheet 只有
//     自画内容），这里先写成 internal 的 moneyPresentList / moneyPresentIcons，周期与设置页共用；
//     地基补上通用件后整份替换。左滑行已换成 designsystem 的 UmbraRevealSwipeRow。
package com.geek.umbra.feature.money

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraButton
import com.geek.umbra.designsystem.UmbraButtonKind
import com.geek.umbra.designsystem.UmbraChoice
import com.geek.umbra.designsystem.UmbraChoiceItem
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraExtraIcons
import com.geek.umbra.designsystem.UmbraGroupCard
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraPrompt
import com.geek.umbra.designsystem.UmbraRevealSwipeRow
import com.geek.umbra.designsystem.UmbraRowDivider
import com.geek.umbra.designsystem.UmbraScreen
import com.geek.umbra.designsystem.UmbraSectionLabel
import com.geek.umbra.designsystem.UmbraSwipeAction
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraTopBarIcon
import com.geek.umbra.designsystem.UmbraType
import com.geek.umbra.designsystem.umbraPhaseTop
import com.geek.umbra.designsystem.umbraScreenPhase
import com.geek.umbra.navigation.Route
import com.geek.umbra.navigation.UmbraAlert
import com.geek.umbra.navigation.UmbraSheet
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 停用确认的标题与正文。**两个入口共用一份**（列表页左滑、分类详情页）—— 同一句话写两遍，改一处忘另一处只是时间问题。
 * 「这个月 N 笔」那个限定不能去掉：手机上只有本月数据，报总数会是编的。iOS: MoneyCatsView.swift:26
 */
private object DisableCatCopy {
    fun title(name: String) = "停用「$name」？"

    fun body(used: Int) = "以后记账选不到它。已经记在它下面的账照旧（这个月 $used 笔），统计也不变。" +
        "随时能在分类管理里启用。"
}

/** 写操作失败的统一吐司：不甩锅给用户，只说下一步。iOS: MoneyCatsView.swift:370 */
private const val SAVE_FAIL = "没存上，检查网络后再试"

// MARK: - 分类管理（money.cats）

/** 分类管理页。iOS: MoneyCatsView.swift:35 UmbraMoneyCatsView */
@Composable
fun MoneyCatsView() {
    val c = UmbraTheme.colors
    val cats by MoneyStore.cats.watch()
    val entries by MoneyStore.entries.watch()
    val storePhase by MoneyStore.phase.watch()
    val catsLoaded by MoneyStore.catsLoadedOnce.watch()
    val offline by AppGraph.http.offline.watch()
    val locked by AppGraph.guard.locked.watch()
    val ledger by MoneyStore.ledger.watch()
    val scope = rememberCoroutineScope()

    // 生活账本是本机账本、不看登录；经营账本未登录时不画页面（请求会被请求闸拦成离线，看着像「这里没东西」）。
    if (locked && !MoneyStore.isLocal(ledger)) {
        UmbraScreen("分类管理", onBack = { AppGraph.router.back() }) { item(key = "login") { MoneyNeedLogin() } }
        return
    }

    // 判据用 catsScreenPhase 而不是 screenPhase：统计挂了但分类好着的时候这一屏是能用的。
    // 这一屏能**冷进**（记一笔的分类选择器里点「管理分类」），那时 store 还是 Idle —— 不能画成「一个分类都没有」。
    val phase = remember(storePhase, catsLoaded, cats, offline) { MoneyStore.catsScreenPhase(offline) }

    /** 防连点。 */
    var busy by remember { mutableStateOf(false) }
    /** 重新启用之后「原来的颜色已经给了「宠物」」那句提示：(被启用的 slug, 抢主 slug)。只在服务端真的回了 slot_taken_by 时出现。 */
    var slotHint by remember { mutableStateOf<Pair<String, String>?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    /** 同一时刻只开一行左滑（iOS 系统 List 的行为）。 */
    val openRow = remember { mutableStateOf<Any?>(null) }

    LaunchedEffect(Unit) { MoneyStore.loadIfNeeded() }

    /** 统一的写操作壳：防连点 + 成败吐司。跑在进程作用域里，页面关了也跑完（同 iOS 的 Task）。 */
    fun run(toast: String, op: suspend () -> Boolean) {
        if (busy) return
        busy = true
        MoneyStore.launch {
            val ok = op()
            busy = false
            AppGraph.router.showToast(if (ok) toast else SAVE_FAIL)
        }
    }

    /** 本月这个分类下几笔（列表 meta 与停用确认共用）。「本月」两个字不能省 —— 手机上只有本月数据。 */
    fun usedThisMonth(slug: String) = entries.count { it.cat == slug }

    fun confirmDisable(cat: MoneyCatDTO) {
        AppGraph.router.confirm(
            UmbraAlert(
                title = DisableCatCopy.title(cat.name),
                message = DisableCatCopy.body(usedThisMonth(cat.slug)),
                confirmLabel = "停用",
                destructive = true,
            ) { run("已停用「${cat.name}」，随时能在分类管理里启用") { MoneyStore.updateCat(cat.slug, enabled = false) } },
        )
    }

    fun startRename(cat: MoneyCatDTO) {
        UmbraPrompt.present(
            key = "money-cat-rename", title = "重命名「${cat.name}」", message = "改名不影响历史数据。",
            placeholder = "显示名", initial = cat.name, confirmLabel = "保存", cancelLabel = "取消",
        ) { text ->
            val name = text.trim()
            if (name.isEmpty() || name == cat.name) return@present
            run("已改名为「$name」") { MoneyStore.updateCat(cat.slug, name = name) }
        }
    }

    /** 「换图标」（批次 006 稿）：八格网格，当前项橙描边；选中即换。列表页左滑和 money.cat 右上菜单共用这一张。 */
    fun askIcon(cat: MoneyCatDTO) {
        moneyPresentIcons(cat.icon ?: "") { p -> run("已换成「${p.label}」") { MoneyStore.updateCat(cat.slug, icon = p.k) } }
    }

    /** 新增分类的第二步：起名字。重名先在本地拦（**跨方向查** —— 两个「宠物」会让流水列表没法读）；服务端还有一道。 */
    fun askName(dir: String) {
        UmbraPrompt.present(
            key = "money-cat-add-name", title = "新增${if (dir == "income") "收入" else "支出"}分类",
            // 批次 006 拍板：新建**不加图标步**，这句副文案要把「没丢东西，只是挪了地方」说出来。
            message = "先起个名字。图标和子类都能之后在分类管理里改。",
            placeholder = "分类名，例如「宠物」", initial = "", confirmLabel = "创建分类", cancelLabel = "取消",
        ) { text ->
            val name = text.trim()
            if (name.isEmpty()) { AppGraph.router.showToast("分类得有个名字"); return@present }
            if (MoneyStore.cats.value.any { it.name == name }) {
                AppGraph.router.showToast("已经有一个叫「$name」的分类了")
                return@present
            }
            run("已加上分类「$name」") { MoneyStore.createCat(name, dir) != null }
        }
    }

    /** 稿的两步：先选边，再起名。 */
    fun askAdd() {
        UmbraChoice.present(
            title = "新增分类",
            items = listOf(
                UmbraChoiceItem("支出分类") { askName("expense") },
                UmbraChoiceItem("收入分类") { askName("income") },
            ),
            subtitle = "先选记在哪一边。",
            key = "money-cat-add",
        )
    }

    /**
     * 拖完了：把这个方向的新顺序发给服务端。停用的分类不在这一屏的分组里，所以清单是
     * 「可见的新顺序 + 这个方向已停用的那些（保持原相对顺序）」—— 显式写出来才看得懂。iOS: MoneyCatsView.swift:196
     */
    fun moveCats(dir: String, from: Int, to: Int) {
        val all = MoneyStore.cats.value
        val visible = all.filter { it.direction == dir && it.enabled }.map { it.slug }
        val next = MoneyCatOrder.moved(visible, from, to)
        val hidden = all.filter { it.direction == dir && !it.enabled }.map { it.slug }
        MoneyStore.launch {
            val ok = MoneyStore.reorderCats(dir, next + hidden)
            if (!ok) AppGraph.router.showToast("顺序没存上，检查网络后再试")
        }
    }

    /** 这次提示挂在哪个分类上（拿它的 direction 决定提示行插在哪个分组后面）。 */
    val hintCat = slotHint?.let { h -> cats.firstOrNull { it.slug == h.first } }

    UmbraScreen(
        title = "分类管理",
        onBack = { AppGraph.router.back() },
        actions = { UmbraTopBarIcon(UmbraIconPaths.plus, "新建", { askAdd() }, tint = c.orange) },
        refreshing = refreshing,
        onRefresh = { scope.launch { refreshing = true; MoneyStore.reload(silent = true); refreshing = false } },
        topContent = umbraPhaseTop(phase, live = true) { MoneyStore.launch { MoneyStore.retry() } },
    ) {
        umbraScreenPhase(phase, { MoneyStore.launch { MoneyStore.retry() } }, "拿不到分类") {
            for ((title, dir) in listOf("支出" to "expense", "收入" to "income")) {
                item(key = "sec-$dir") {
                    CatSection(
                        title = title,
                        rows = cats.filter { it.direction == dir && it.enabled },
                        meta = { cat ->
                            val subs = cat.subList.size
                            (if (subs > 0) "$subs 个子类" else "没有子类") + " · 这个月 ${usedThisMonth(cat.slug)} 笔"
                        },
                        openRow = openRow,
                        onDisable = ::confirmDisable,
                        onIcon = ::askIcon,
                        onRename = ::startRename,
                        onMove = { from, to -> moveCats(dir, from, to) },
                    )
                }
                if (hintCat?.direction == dir) {
                    item(key = "hint-$dir") {
                        val h = slotHint
                        val taker = h?.let { p -> cats.firstOrNull { it.slug == p.second } }
                        if (h != null && taker != null) {
                            SlotHintCard(
                                name = hintCat.name, takerName = taker.name,
                                onSwap = {
                                    // 换槽面板在分类详情页（稿 03 节的落点）。用 store 上的一个标志把「进去就把面板打开」带过去。
                                    MoneyStore.slotSwapFor.value = hintCat.slug
                                    slotHint = null
                                    AppGraph.router.go(Route.MoneyCat(hintCat.slug))
                                },
                                onDismiss = { slotHint = null },
                            )
                        }
                    }
                }
            }
            val hidden = cats.filter { !it.enabled }
            if (hidden.isNotEmpty()) {
                item(key = "sec-disabled") {
                    Column {
                        UmbraSectionLabel("已停用")
                        Spacer(Modifier.height(6.dp))
                        UmbraGroupCard {
                            hidden.forEachIndexed { i, cat ->
                                if (i > 0) UmbraRowDivider()
                                DisabledRow(cat) {
                                    run("已启用「${cat.name}」") {
                                        // 启用有**第三种结果**：成了，但它原来的槽已经被别人拿走，服务端给它重新分了一个
                                        // （回 slot_taken_by）。那时要挂提示行 ——「字段不出现 = 没发生」。
                                        val r = MoneyStore.patchCat(cat.slug, enabled = true) ?: return@run false
                                        r.slot_taken_by?.let { slotHint = cat.slug to it }
                                        true
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item(key = "foot") {
                Text(
                    "点分类进子类管理；左滑分类行可以改名、停用；长按一行可以拖动排序，收入和支出各排各的 —— 这个顺序管记一笔的分类网格、流水筛选和预算列表，不影响颜色和统计。改名只改显示名，不影响历史数据 —— 流水里存的是稳定标识。停用的分类不再出现在选择器里，历史账目仍归它，统计不变。",
                    color = c.muted, style = TextStyle(fontSize = 12.sp, lineHeight = (12 * 1.65).sp),
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

/**
 * 一个方向的分组卡 + 拖动排序。**一组一套顺序** —— 就是「收入与支出各排各的」：跨卡拖不动，
 * 不用额外写「不许跨方向」的判断（iOS 一个 Section 挂一个 onMove，同一个道理）。
 *
 * 拖动的做法：长按行尾把手起拖，记下被拖行的下标与累计 dy；目标下标 = 起点 + round(dy / 行高)。
 * 被拖那行跟手平移，夹在起点与目标之间的行各让出一行高；松手才发请求（store 是乐观更新，列表当场就是新顺序）。
 */
@Composable
private fun CatSection(
    title: String,
    rows: List<MoneyCatDTO>,
    meta: (MoneyCatDTO) -> String,
    openRow: MutableState<Any?>,
    onDisable: (MoneyCatDTO) -> Unit,
    onIcon: (MoneyCatDTO) -> Unit,
    onRename: (MoneyCatDTO) -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    // 手势回调是在组合时捕获的，列表在拖动期间可能被静默刷新替换 —— 回调里一律读最新的那份。
    val current by rememberUpdatedState(rows)
    var dragIdx by remember { mutableIntStateOf(-1) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    var rowH by remember { mutableFloatStateOf(1f) }

    fun target(): Int = if (dragIdx < 0) -1 else (dragIdx + (dragDy / rowH).roundToInt()).coerceIn(0, (current.size - 1).coerceAtLeast(0))

    fun finish() {
        val from = dragIdx
        val to = target()
        dragIdx = -1
        dragDy = 0f
        if (from >= 0 && to >= 0 && from != to) onMove(from, to)
    }

    Column {
        UmbraSectionLabel(title)
        Spacer(Modifier.height(6.dp))
        UmbraGroupCard {
            val t = target()
            rows.forEachIndexed { i, cat ->
                key(cat.slug) {
                    val shift = when {
                        dragIdx < 0 -> 0f
                        i == dragIdx -> dragDy
                        dragIdx < t && i in (dragIdx + 1)..t -> -rowH
                        dragIdx > t && i in t until dragIdx -> rowH
                        else -> 0f
                    }
                    Column(
                        Modifier.zIndex(if (i == dragIdx) 1f else 0f)
                            .graphicsLayer { translationY = shift }
                            .onSizeChanged { if (it.height > 0) rowH = it.height.toFloat() },
                    ) {
                        if (i > 0) UmbraRowDivider()
                        // 左滑按钮顺序照 iOS swipeActions（声明顺序 = 从最外侧往里），这里按屏幕左→右排。
                        // 兜底分类没有停用键 —— 没有键比「有键但点了报错」诚实。
                        val c = UmbraTheme.colors
                        val actions = buildList {
                            add(UmbraSwipeAction("改名", UmbraIconPaths.textLines, c.warning, c.onWarning) { onRename(cat) })
                            add(UmbraSwipeAction("换图标", UmbraIconPaths.image, c.orange, c.onAccent) { onIcon(cat) })
                            if (!cat.locked) add(UmbraSwipeAction("停用", UmbraIconPaths.ban, c.danger, c.onDanger) { onDisable(cat) })
                        }
                        UmbraRevealSwipeRow(trailing = actions, openRow = openRow, rowKey = cat.slug) {
                            CatRow(
                                cat = cat, meta = meta(cat), dragging = i == dragIdx,
                                handle = Modifier.pointerInput(cat.slug) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            val idx = current.indexOfFirst { it.slug == cat.slug }
                                            if (idx >= 0) {
                                                openRow.value = null
                                                dragIdx = idx
                                                dragDy = 0f
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            }
                                        },
                                        onDrag = { change, d -> change.consume(); if (dragIdx >= 0) dragDy += d.y },
                                        onDragEnd = { finish() },
                                        onDragCancel = { dragIdx = -1; dragDy = 0f },
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 一级行：**点进 money.cat**（稿 mcVM 的 open）。色块 = 同色 tint 底 + 色槽色描边图标（批次 003）。iOS: MoneyCatsView.swift:208 */
@Composable
private fun CatRow(cat: MoneyCatDTO, meta: String, dragging: Boolean, handle: Modifier) {
    val c = UmbraTheme.colors
    Row(
        Modifier.fillMaxWidth().background(if (dragging) c.hover else c.card)
            .clickable { AppGraph.router.go(Route.MoneyCat(cat.slug)) }
            // 带副文（「N 个子类 · 这个月 N 笔」），走 60 那一档。
            .defaultMinSize(minHeight = UmbraDimens.rowMinHSub)
            .padding(start = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // iOS 34 色块、圆角 10、图标 18（MoneyCatsView.swift:212）。
        MoneyCatBlock(cat.slot, MoneyCatArt.icon(cat.slug, cat.icon), block = 34.dp, iconSize = 18.dp, corner = UmbraDimens.radiusControl)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    cat.name, color = c.text, style = TextStyle(fontSize = 15.sp, fontWeight = UmbraType.weight560),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (cat.locked) {
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "兜底分类", color = c.faint, style = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(c.chip).padding(horizontal = 7.dp, vertical = 1.dp),
                    )
                }
            }
            Text(meta, color = c.muted, style = TextStyle(fontSize = 12.sp))
        }
        Spacer(Modifier.width(6.dp))
        UmbraIcon(UmbraIconPaths.chevronRight, c.faint, size = 15.dp, strokeWidth = 2.2f)
        // 拖动把手（Android 补的，见文件头）：热区 44，长按起拖。
        Box(handle.size(UmbraDimens.tapMin), contentAlignment = Alignment.Center) {
            UmbraIcon(UmbraExtraIcons.grip, c.faint, size = 18.dp, contentDescription = "拖动排序")
        }
    }
}

/**
 * 重新启用后的那句交代（`slotPool` ⑥）：它原来的槽已经被别人拿走，服务端给它重新分了一个。
 * 稿明写**不静默地把颜色抢回来** —— 所以这句提示 + 一颗「换槽」是这条路上唯一的交代。iOS: MoneyCatsView.swift:156
 */
@Composable
private fun SlotHintCard(name: String, takerName: String, onSwap: () -> Unit, onDismiss: () -> Unit) {
    val c = UmbraTheme.colors
    Column(Modifier.moneyCard().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            UmbraIcon(UmbraIconPaths.alertTriangle, c.warningText, size = 15.dp, modifier = Modifier.padding(top = 1.dp))
            Spacer(Modifier.width(9.dp))
            Text(
                "「$name」原来的颜色已经给了「$takerName」，可以在这里换槽。",
                color = c.warningText, style = TextStyle(fontSize = 13.sp, lineHeight = (13 * 1.6).sp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            UmbraButton("换槽", onSwap, Modifier.width(84.dp), kind = UmbraButtonKind.Secondary, height = 36.dp)
            UmbraButton("知道了", onDismiss, Modifier.width(84.dp), kind = UmbraButtonKind.Secondary, height = 36.dp)
        }
    }
}

/** 已停用的一行：ban 图标（只表示停用，不表示失败）+ 名字 + 「启用」。iOS: MoneyCatsView.swift:265 */
@Composable
private fun DisabledRow(cat: MoneyCatDTO, onEnable: () -> Unit) {
    val c = UmbraTheme.colors
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = UmbraDimens.rowMinH).padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // iOS 30 方块、圆角 9（MoneyCatsView.swift:273）。
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(c.chip), contentAlignment = Alignment.Center) {
            UmbraIcon(UmbraIconPaths.ban, c.faint, size = 15.dp)
        }
        Spacer(Modifier.width(11.dp))
        Text(cat.name, color = c.muted, style = TextStyle(fontSize = 15.sp), modifier = Modifier.weight(1f))
        UmbraButton("启用", onEnable, Modifier.width(72.dp), kind = UmbraButtonKind.Secondary, height = 32.dp)
    }
}

// MARK: - 单个分类（money.cat）：子类管理

/**
 * 子类的看 / 加 / 改名 / 删（照稿 mcdVM）。右上角菜单里是分类级操作 —— 和列表页左滑**共用同一对语义**，只是入口不同。
 * iOS: MoneyCatsView.swift:379 UmbraMoneyCatDetailView
 */
@Composable
fun MoneyCatDetailView(slug: String) {
    val c = UmbraTheme.colors
    val cats by MoneyStore.cats.watch()
    val entries by MoneyStore.entries.watch()
    val slotPool by MoneyStore.slotPool.watch()
    val locked by AppGraph.guard.locked.watch()
    val ledger by MoneyStore.ledger.watch()
    val scope = rememberCoroutineScope()

    val cat = cats.firstOrNull { it.slug == slug }
    val title = cat?.name ?: "分类"

    if (locked && !MoneyStore.isLocal(ledger)) {
        UmbraScreen(title, onBack = { AppGraph.router.back() }) { item(key = "login") { MoneyNeedLogin() } }
        return
    }

    /** 「N 笔在用」按全部历史数（服务端口径），进页拉一次、每次改动后重拉。和列表页「本月 N 笔」是两个口径。 */
    var used by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var busy by remember { mutableStateOf(false) }
    /** 换槽面板展开着没有。**就地一张卡**，不另起一层浮层（iOS 注释：面板是「网格 + 一句代价 + 一颗主钮」）。 */
    var swapping by remember { mutableStateOf(false) }
    /** 面板里当前选中的槽号。null = 面板没开过 / 刚关。 */
    var swapPick by remember { mutableStateOf<Int?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val openRow = remember { mutableStateOf<Any?>(null) }
    val subs = cat?.subList ?: emptyList()

    suspend fun loadUsed() {
        val items = MoneyStore.subsUsed(slug) ?: return
        used = items.associate { it.label to it.used }
    }

    LaunchedEffect(slug) { loadUsed() }
    // 从分类管理页那颗「换槽」进来的：进页就把面板打开，标志读完立刻清空
    //（留着的话下次从别处进这一页会莫名其妙又展开一次）。
    LaunchedEffect(Unit) {
        if (MoneyStore.slotSwapFor.value == slug) {
            MoneyStore.slotSwapFor.value = null
            swapPick = MoneyStore.cats.value.firstOrNull { it.slug == slug }?.slot
            swapping = true
        }
    }

    /** 可分配的九个彩色槽。服务端说了算（slotPool），拿不到才用兜底 ——「8 永不分配」这条口径端上硬编码等于第二份真相。 */
    val colorSlots = slotPool?.colorSlots?.takeIf { it.isNotEmpty() } ?: MoneyLocalRules.colorSlots
    /** 服务端的中性槽取值（0）。稿里叫「中性槽 8」= CSS 变量 --c8，同一个东西的两个名字。 */
    val neutralSlot = slotPool?.neutralSlot ?: MoneyLocalRules.NEUTRAL_SLOT

    /**
     * 这个方向里占着 slot 的那个**在用**分类。**必须带方向** —— 出厂数据里收入与支出共用 1–5，
     * 不带方向的话「工资」会被写成「吃饭」那一格的占用者。
     */
    fun owner(slot: Int): MoneyCatDTO? {
        val me = cat ?: return null
        if (slot == neutralSlot) return null
        return cats.firstOrNull { it.direction == me.direction && it.slot == slot && it.enabled }
    }

    /** 统一的写操作壳；成功后重拉「N 笔在用」。 */
    fun run(toast: String, op: suspend () -> Boolean) {
        if (busy) return
        busy = true
        MoneyStore.launch {
            val ok = op()
            busy = false
            AppGraph.router.showToast(if (ok) toast else SAVE_FAIL)
            if (ok) loadUsed()
        }
    }

    // MARK: 子类操作（文案照稿 mcdVM 逐句）

    fun askAddSub() {
        UmbraPrompt.present(
            key = "money-sub-add", title = "新增子类", message = "加在「${cat?.name ?: ""}」下面。",
            placeholder = "子类名，例如「夜宵」", initial = "", confirmLabel = "加上", cancelLabel = "取消",
        ) { text ->
            val label = text.trim()
            if (label.isEmpty()) { AppGraph.router.showToast("子类得有个名字"); return@present }
            if (label in subs) { AppGraph.router.showToast("「$label」已经在这个分类里了"); return@present }
            run("已加上「$label」") { MoneyStore.addSub(slug, label) }
        }
    }

    fun askRenameSub(old: String) {
        UmbraPrompt.present(
            key = "money-sub-rename", title = "子类改名", message = "只影响以后记账，已经记下的账目还写着「$old」。",
            placeholder = "新名字", initial = old, confirmLabel = "改名", cancelLabel = "取消",
        ) { text ->
            val new = text.trim()
            if (new.isEmpty()) { AppGraph.router.showToast("名字不能空着"); return@present }
            if (new == old) return@present
            if (new in subs) { AppGraph.router.showToast("「$new」已经在这个分类里了"); return@present }
            run("已改成「$new」") { MoneyStore.renameSub(slug, old, new) }
        }
    }

    fun confirmDeleteSub(s: String) {
        val n = used[s] ?: 0
        AppGraph.router.confirm(
            UmbraAlert(
                title = "删掉子类「$s」？",
                message = if (n > 0) "有 $n 笔账记在这个子类上，它们不会变，只是以后记账选不到了。" else "以后记账就选不到它了。",
                confirmLabel = "删除",
                destructive = true,
            ) { run("已删掉「$s」") { MoneyStore.deleteSub(slug, s) } },
        )
    }

    // MARK: 分类级操作（右上角菜单，语义同列表页左滑）

    fun askRenameCat(me: MoneyCatDTO) {
        UmbraPrompt.present(
            key = "money-cat-rename", title = "分类改名", message = "改名不影响历史数据。",
            placeholder = "显示名", initial = me.name, confirmLabel = "保存", cancelLabel = "取消",
        ) { text ->
            val name = text.trim()
            if (name.isEmpty() || name == me.name) return@present
            run("已改名为「$name」") { MoneyStore.updateCat(slug, name = name) }
        }
    }

    /** 换槽落地：**一次 PATCH**。零和由服务端在同一个写事务里做，端上发两次去模拟会留下半截状态。iOS: MoneyCatsView.swift:631 */
    fun commitSwap(slot: Int) {
        if (busy) return
        busy = true
        MoneyStore.launch {
            val r = MoneyStore.patchCat(slug, slot = slot)
            busy = false
            if (r == null) { AppGraph.router.showToast(SAVE_FAIL); return@launch }
            swapping = false
            swapPick = null
            // 被踢成中性灰的那个，用名字说。slot_taken_by 是 slug —— 找不到名字（并发删了）就退回不说，别编一个。
            val loser = r.slot_taken_by?.let { l -> MoneyStore.cats.value.firstOrNull { it.slug == l }?.name }
            AppGraph.router.showToast(if (loser != null) "换好了，「$loser」改用中性灰" else "换好了")
        }
    }

    /**
     * 合并第二步：二次确认，**写全后果**。数字取**服务端**那份（catUsage）：手机上只有本月流水，拿本地的会数少，
     * 而这个数字要写进一个不能撤销的确认里 —— 拿不到就退回不带数字的那一句。iOS: MoneyCatsView.swift:815
     */
    fun confirmMerge(me: MoneyCatDTO, into: MoneyCatDTO) {
        val u = MoneyStore.catUsage.value[me.slug]
        val text = if (u != null) {
            "「${me.name}」名下 ${u.entries} 笔账会改挂到「${into.name}」，${u.subs} 个子类一起带过去，同名的会合并。合并不能撤销。"
        } else {
            "「${me.name}」名下的账会改挂到「${into.name}」，子类一起带过去，同名的会合并。合并不能撤销。"
        }
        AppGraph.router.confirm(
            UmbraAlert(title = "把「${me.name}」并进「${into.name}」？", message = text, confirmLabel = "合并", destructive = true) {
                if (busy) return@UmbraAlert
                busy = true
                MoneyStore.launch {
                    when (val r = MoneyStore.mergeCat(me.slug, into.slug)) {
                        is MoneyResult.Ok -> {
                            busy = false
                            AppGraph.router.showToast("已把「${me.name}」并进「${into.name}」，${r.value.entries} 笔账改挂")
                            // 这个分类已经不存在了，不能继续停在它自己的页面上。⚠️ 只退**这一页**：
                            // 合并要等一个来回，人可能已经自己返回或推了别的页。
                            AppGraph.router.back(ifShowing = Route.MoneyCat(slug))
                        }
                        is MoneyResult.Failed -> {
                            busy = false
                            // 服务端那句拒绝照原样吐 —— 在端上再编一份只会和服务端漂开。
                            AppGraph.router.showToast(r.detail.ifEmpty { "没合并成，检查网络后再试" })
                        }
                    }
                }
            },
        )
    }

    /**
     * 合并第一步：挑目标。候选 = **同方向、在用、不是自己**；**兜底分类留在候选里** ——「其他」本来就是那个收容所。
     * iOS 这里要等上一层 sheet 收起动画走完再开（0.45s）；Android 的弹层是直接出栈的，不需要等。
     */
    fun askMergeTarget(me: MoneyCatDTO) {
        val targets = MoneyStore.cats.value.filter { it.direction == me.direction && it.enabled && it.slug != me.slug }
        if (targets.isEmpty()) {
            AppGraph.router.showToast("这个方向下没有别的在用分类可以并进去")
            return
        }
        UmbraChoice.present(
            title = "把「${me.name}」并进哪个分类？",
            items = targets.map { t -> UmbraChoiceItem(t.name, note = if (t.locked) "兜底分类" else null) { confirmMerge(me, t) } },
            subtitle = "旧账会改挂过去，合并不能撤销。",
            key = "money-cat-merge",
        )
    }

    /** 删除：端上只在「没账没子类没规则」时才画得出这颗钮，但仍然过一道确认 —— 破坏性操作一律要挡一道。 */
    fun confirmDeleteCat(me: MoneyCatDTO) {
        AppGraph.router.confirm(
            UmbraAlert(
                title = "删掉分类「${me.name}」？",
                message = "它名下没有账、没有子类、也没有周期规则，删掉之后彻底消失。",
                confirmLabel = "删除", destructive = true,
            ) {
                if (busy) return@UmbraAlert
                busy = true
                MoneyStore.launch {
                    val why = MoneyStore.deleteCat(me.slug)
                    busy = false
                    if (why != null) { AppGraph.router.showToast(why); return@launch }
                    AppGraph.router.showToast("已删掉「${me.name}」")
                    AppGraph.router.back(ifShowing = Route.MoneyCat(slug))
                }
            },
        )
    }

    /** 详情页里的停用确认：成功吐司不带尾巴（这一页停用后马上退回去），停用后只退这一页。 */
    fun confirmDisableCat(me: MoneyCatDTO) {
        val n = MoneyStore.entries.value.count { it.cat == me.slug }
        AppGraph.router.confirm(
            UmbraAlert(title = DisableCatCopy.title(me.name), message = DisableCatCopy.body(n), confirmLabel = "停用", destructive = true) {
                run("已停用「${me.name}」") {
                    val ok = MoneyStore.updateCat(me.slug, enabled = false)
                    // 稿 mCatDisable(back:true)：停用的分类不该继续停在它自己的编辑页上。
                    if (ok) AppGraph.router.back(ifShowing = Route.MoneyCat(slug))
                    ok
                }
            },
        )
    }

    fun openMenu() {
        val me = MoneyStore.cats.value.firstOrNull { it.slug == slug } ?: return
        val items = mutableListOf(
            UmbraChoiceItem("分类改名", note = "不影响历史数据") { askRenameCat(me) },
            UmbraChoiceItem("换图标", note = "只换样子，历史不变") {
                moneyPresentIcons(me.icon ?: "") { p -> run("已换成「${p.label}」") { MoneyStore.updateCat(slug, icon = p.k) } }
            },
            // 换槽（批次 020 §二）：面板就地展开在这一页上，不再开一层。
            UmbraChoiceItem("改色槽", note = "换过来原来那个会变中性灰") { swapPick = me.slot; swapping = true },
        )
        if (!me.locked) {
            items += UmbraChoiceItem("停用这个分类", note = "选择器里不再出现，历史不变", destructive = true) { confirmDisableCat(me) }
            // 批次 020 §四：这一格是**二选一**。没账没子类没规则的才给「删除」，否则给「合并到…」。
            // **拿不到用量时按「有账」处理**：最坏是把「删除」藏起来，反过来会给出一颗按下去必然报错的删除钮。
            if (MoneyStore.catUsage.value[slug]?.isEmpty == true) {
                items += UmbraChoiceItem("删除这个分类", note = "没有账、没有子类，删掉就没了", destructive = true) { confirmDeleteCat(me) }
            } else {
                items += UmbraChoiceItem("合并到…", note = "旧账改挂过去，不能撤销", destructive = true) { askMergeTarget(me) }
            }
        }
        UmbraChoice.present(title = me.name, items = items, key = "money-cat-menu")
    }

    val footN = entries.count { it.cat == slug }

    UmbraScreen(
        title = title,
        onBack = { AppGraph.router.back() },
        actions = {
            // 顺序照 iosShell.toolbar.right：主动作「＋」在左，⋯ 贴最右。
            UmbraTopBarIcon(UmbraIconPaths.plus, "新建", { askAddSub() }, tint = c.orange)
            UmbraTopBarIcon(UmbraExtraIcons.more, "更多", { openMenu() }, tint = c.muted)
        },
        refreshing = refreshing,
        onRefresh = { scope.launch { refreshing = true; MoneyStore.reload(silent = true); loadUsed(); refreshing = false } },
    ) {
        if (swapping && cat != null) {
            item(key = "swap") {
                val pick = swapPick ?: cat.slot
                // 选中的槽现在归谁（不含自己）。null = 空槽 / 选的是中性槽 / 选的就是自己那格。
                val holder = owner(pick)
                val taken = if (holder?.slug == cat.slug) null else holder
                SwapPanel(
                    cat = cat, pick = pick, taken = taken,
                    // 中性槽**永远在最后一格**（稿 03）。filter 是防呆：万一服务端把中性号也列进 colorSlots。
                    slots = colorSlots.filter { it != neutralSlot } + neutralSlot,
                    neutralSlot = neutralSlot, owner = ::owner,
                    onPick = { swapPick = it },
                    onCommit = { if (pick != cat.slot) commitSwap(pick) },
                )
            }
        }
        if (subs.isEmpty()) {
            item(key = "empty") {
                Text(
                    "还没有子类。右上角 + 加一个 —— 记账时选中这个分类，它会出现在那一排子类胶囊里。",
                    color = c.muted, style = TextStyle(fontSize = 13.sp, lineHeight = (13 * 1.6).sp),
                    modifier = Modifier.moneyCard().padding(horizontal = 14.dp, vertical = 13.dp),
                )
            }
        } else {
            item(key = "subs") {
                UmbraGroupCard {
                    subs.forEachIndexed { i, s ->
                        key(s) {
                            if (i > 0) UmbraRowDivider()
                            UmbraRevealSwipeRow(
                                trailing = listOf(
                                    UmbraSwipeAction("改名", UmbraIconPaths.textLines, c.warning, c.onWarning) { askRenameSub(s) },
                                    UmbraSwipeAction("删除", UmbraIconPaths.trash, c.danger, c.onDanger) { confirmDeleteSub(s) },
                                ),
                                openRow = openRow, rowKey = s,
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().background(c.card).defaultMinSize(minHeight = UmbraDimens.rowMinH)
                                        .padding(horizontal = 14.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(s, color = c.text, style = TextStyle(fontSize = 15.sp), modifier = Modifier.weight(1f))
                                    Spacer(Modifier.width(10.dp))
                                    val n = used[s] ?: 0
                                    Text(if (n > 0) "$n 笔在用" else "还没用过", color = c.faint, style = TextStyle(fontSize = 12.sp))
                                }
                            }
                        }
                    }
                }
            }
        }
        item(key = "foot") {
            // 页脚照稿的意思，「N 笔账」带「这个月」限定（手机上只有本月数据，同分类管理）。
            Text(
                "左滑一行可以改名或删除。「${cat?.name ?: ""}」名下这个月有 $footN 笔账，改名、删子类、停用分类都不会动它们。",
                color = c.faint, style = TextStyle(fontSize = 12.sp, lineHeight = (12 * 1.65).sp),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/**
 * 换槽面板（批次 020 §二，稿 03 节「零和这件事要画出来」）。九个彩色槽 + 中性槽，一格 44 起。
 * 空槽只写「空」，已占用的写**占用者的名字**；已占用的槽照旧可以选，但选中时要把代价当场写出来：谁会失去颜色。
 * 不做「共用一个颜色」：共用会让环形图出现两段一模一样的颜色。iOS: MoneyCatsView.swift:545
 */
@Composable
private fun SwapPanel(
    cat: MoneyCatDTO,
    pick: Int,
    taken: MoneyCatDTO?,
    slots: List<Int>,
    neutralSlot: Int,
    owner: (Int) -> MoneyCatDTO?,
    onPick: (Int) -> Unit,
    onCommit: () -> Unit,
) {
    val c = UmbraTheme.colors
    Column {
        UmbraSectionLabel("给「${cat.name}」换槽")
        Spacer(Modifier.height(6.dp))
        Column(Modifier.moneyCard().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                slots.chunked(5).forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        line.forEach { n -> SlotCell(n, cat, pick, neutralSlot, owner, onPick, Modifier.weight(1f)) }
                        repeat(5 - line.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            if (taken != null) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(c.warningSoft).padding(11.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    UmbraIcon(UmbraIconPaths.alertTriangle, c.warningText, size = 15.dp, modifier = Modifier.padding(top = 1.dp))
                    Spacer(Modifier.width(9.dp))
                    Text(
                        "这个槽现在是「${taken.name}」的。换过来之后「${taken.name}」会变成中性灰 —— 一个槽同时只能属于一个分类。",
                        color = c.warningText, style = TextStyle(fontSize = 12.5.sp, lineHeight = (12.5 * 1.65).sp),
                    )
                }
            }
            // 选中性槽时不能说「换成这个颜色」—— 中性灰不是一种颜色，那句话是假的。
            UmbraButton(
                if (pick == neutralSlot) "改用中性灰" else "换成这个颜色", onCommit, Modifier.fillMaxWidth(),
                enabled = pick != cat.slot, height = 44.dp,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "空槽直接给，已占用的槽也能选 —— 一个槽同时只属于一个分类，换过来原来那个就变中性灰。中性槽可以有多个分类：它不承诺区分，只承诺不撒谎。",
            color = c.muted, style = TextStyle(fontSize = 11.5.sp, lineHeight = (11.5 * 1.6).sp),
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/**
 * 一格：色点 + 一行字。字的优先级是「现在」>「占用者的名字」>「空」/「中性灰」。
 * **别人**占着这一格时必须画成后者：停用的分类保留原槽号，而那个号可能已被一个在用的分类拿走 ——
 * 画成「现在」的话，点下去会在没有任何预告的情况下把真正的占用者踢成中性灰。
 */
@Composable
private fun SlotCell(n: Int, cat: MoneyCatDTO, pick: Int, neutralSlot: Int, owner: (Int) -> MoneyCatDTO?, onPick: (Int) -> Unit, modifier: Modifier) {
    val c = UmbraTheme.colors
    val holder = owner(n)
    val other = if (holder?.slug == cat.slug) null else holder
    val mine = other == null && cat.slot == n
    val on = pick == n
    val label = other?.name ?: if (mine) "现在" else if (n == neutralSlot) "中性灰" else "空"
    val shape = RoundedCornerShape(UmbraDimens.radiusControl)
    Column(
        modifier.heightIn(min = UmbraDimens.tapMin).clip(shape).background(if (on) c.orangeSoft else c.card)
            .border(UmbraDimens.borderW, if (on) c.orange else c.border, shape)
            .clickable(role = Role.Button) { onPick(n) }.padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(moneySlotColor(n)))
        Text(
            label, color = if (on) c.orangeText else if (other != null) c.text else c.faint,
            style = TextStyle(fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

// MARK: - 经营账本的科目表（只读）

/**
 * 经营账本的**科目表**（只读）。iOS: MoneyAccountsView.swift
 *
 * 为什么是另一张屏，而不是把分类管理的控件一个个藏起来：新建、改名、停用、合并、删除、调序、子类七件全部不出现
 * （不是画出来点了报错）—— 把七个控件一个一个藏起来，漏一个就是一颗按下去必然报错的钮。
 * ⒜ 科目行右侧是扣除档位，不是色槽；⒝ 没有子类那一层，行**点不开**；⒞ 代价要在屏上说清楚：不能加自己的科目。
 */
@Composable
fun MoneyAccountsView() {
    val c = UmbraTheme.colors
    val cats by MoneyStore.cats.watch()
    val storePhase by MoneyStore.phase.watch()
    val catsLoaded by MoneyStore.catsLoadedOnce.watch()
    val offline by AppGraph.http.offline.watch()
    val locked by AppGraph.guard.locked.watch()
    val ledger by MoneyStore.ledger.watch()

    if (locked && !MoneyStore.isLocal(ledger)) {
        UmbraScreen("科目", onBack = { AppGraph.router.back() }) { item(key = "login") { MoneyNeedLogin() } }
        return
    }

    LaunchedEffect(Unit) { MoneyStore.loadIfNeeded() }
    // 判据同分类管理：用 catsScreenPhase，统计挂了但科目好着时这一屏能用。
    val phase = remember(storePhase, catsLoaded, cats, offline) { MoneyStore.catsScreenPhase(offline) }

    UmbraScreen(title = "科目", onBack = { AppGraph.router.back() }, topContent = umbraPhaseTop(phase, live = true) { MoneyStore.launch { MoneyStore.reload() } }) {
        umbraScreenPhase(phase, { MoneyStore.launch { MoneyStore.reload() } }, "拿不到科目") {
            item(key = "note") {
                // ⒞ 那句代价放在表**上面**，不是挂在某个禁用控件上。⚠️ 不写方位词（positionWordsDontMove）。
                Text(
                    "经营账本的科目是固定的一套，跟着年度 B 表走 —— 不能新建、改名或停用。" +
                        "每个科目带一个扣除档位，申报时按它算。",
                    color = c.muted, style = TextStyle(fontSize = 12.sp, lineHeight = (12 * 1.65).sp),
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            for ((title, dir) in listOf("支出" to "expense", "收入" to "income")) {
                val rows = cats.filter { it.direction == dir && it.enabled }
                if (rows.isEmpty()) continue
                item(key = "acc-$dir") {
                    Column {
                        UmbraSectionLabel(title)
                        Spacer(Modifier.height(6.dp))
                        UmbraGroupCard {
                            rows.forEachIndexed { i, a ->
                                if (i > 0) UmbraRowDivider()
                                // ⚠️ 没有点击、没有左滑、没有拖动 —— 这三样正是生活那一屏上改名 / 停用 / 子类 / 调序的入口。
                                Row(
                                    Modifier.fillMaxWidth().defaultMinSize(minHeight = UmbraDimens.rowMinH)
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(a.name, color = c.text, style = TextStyle(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.width(UmbraDimens.sp3).weight(1f))
                                    // ⚠️ 念的是**服务端给的那句**，不是端上按档位键拼的 —— 它是税口径，抄一份就是第二份真相。
                                    // 服务端没给（老服务端）就空着：空着是「这一版没有这句话」，端上自己编是「这一版说错了」。
                                    Text(
                                        a.deduct_label ?: "", color = c.muted,
                                        style = TextStyle(fontSize = 12.sp, lineHeight = (12 * 1.6).sp, textAlign = TextAlign.End),
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// MARK: - 记账几屏共用的浮层与左滑（Android 地基缺件，见文件头）

/**
 * 「换图标」sheet（批次 006 稿，iOS `UmbraSheet(icons:)`）：八格网格（一行四格），当前项橙描边；选中即换、就地生效。
 * 列表页左滑和 money.cat 右上菜单共用这一张。
 */
internal fun moneyPresentIcons(current: String, onPick: (MoneyCatArt.Pick) -> Unit) {
    AppGraph.router.present(UmbraSheet("money-cat-icon") { dismiss ->
        val c = UmbraTheme.colors
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("换图标", color = c.text, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
                Text("只换样子，名字和已记的账不动。", color = c.muted, style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp))
            }
            MoneyCatArt.pickList.chunked(4).forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    line.forEach { p ->
                        val on = p.k == current
                        val shape = RoundedCornerShape(14.dp)
                        Column(
                            Modifier.weight(1f).clip(shape).background(c.card)
                                .border(if (on) 1.5.dp else UmbraDimens.borderW, if (on) c.orange else c.border, shape)
                                .clickable(role = Role.Button) { dismiss(); onPick(p) }.padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            UmbraIcon(p.d, if (on) c.orange else c.text, size = 22.dp)
                            Text(p.label, color = if (on) c.orangeText else c.muted, style = TextStyle(fontSize = 12.sp, fontWeight = UmbraType.weight560))
                        }
                    }
                    repeat(4 - line.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            UmbraButton("取消", dismiss, Modifier.fillMaxWidth(), kind = UmbraButtonKind.Secondary)
        }
    })
}


