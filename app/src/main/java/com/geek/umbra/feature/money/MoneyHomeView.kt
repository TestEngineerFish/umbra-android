// 记账首屏（统计）—— 照抄 iOS Features/Money/MoneyViews.swift:10-640 UmbraMoneyHomeView。
//
// 稿里统计屏还有「待确认」横幅（四期）、「预算」卡（三期），这一版都不画 —— 放一个点了没反应的入口比不放更糟。
// 经营账本（stats.hasBiz）整块换成另一屏（MoneyStatsBiz.kt），不是把四张卡一张一张藏起来。
package com.geek.umbra.feature.money

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraButton
import com.geek.umbra.designsystem.UmbraButtonKind
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraEmptyState
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraScreen
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraTopBarIcon
import com.geek.umbra.designsystem.UmbraType
import com.geek.umbra.designsystem.umbraPhaseTop
import com.geek.umbra.designsystem.umbraScreenPhase
import com.geek.umbra.navigation.Route
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** 环比箭头的两条 path（iOS MoneyViews.swift:256 内联写死，照抄）。 */
private const val ARROW_UP = "M12,19L12,5M6,11L12,5L18,11"
private const val ARROW_DOWN = "M12,5L12,19M6,13L12,19L18,13"

/** 等宽数字字体（iOS UmbraFont.mono）。不是字号字面量，只是字族。 */
internal val MoneyMono = FontFamily.Monospace

/**
 * 统计屏 / 经营统计屏卡内的行间线**满宽**：行外面套着卡的内衬（概览卡 16、其余 15），照原样画会左右各短一截。
 * 用 layout 把线往两边各撑出 pad，顶到卡边。⚠️ pad 要跟着卡内衬走，改卡内衬时记得同步。iOS: MoneyViews.swift:1318
 */
internal fun Modifier.moneyBleed(pad: Dp): Modifier = this.layout { m, c ->
    val px = pad.roundToPx()
    val p = m.measure(c.offset(horizontal = 2 * px))
    layout((p.width - 2 * px).coerceAtLeast(0), p.height) { p.place(-px, 0) }
}

/** 满宽出血的行间线（统计屏两张卡共用，不在经营那边另画一条）。 */
@Composable
internal fun MoneyBleedSeparator(pad: Dp, tint: Color = UmbraTheme.colors.borderSoft) {
    MoneyRowSeparator(Modifier.moneyBleed(pad), tint)
}

/**
 * 一行放不下就缩字号的文字（iOS `.lineLimit(1).minimumScaleFactor(x)`）。
 * 定宽的金额列钉死宽度而不给缩放，长了不是截断而是**折行顶高整行**（设计侧 184 §三末）。
 */
@Composable
internal fun MoneyFitText(
    text: String,
    color: Color,
    size: TextUnit,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal,
    mono: Boolean = false,
    minScale: Float = 0.7f,
    align: TextAlign = TextAlign.Start,
) {
    BasicText(
        text, modifier,
        style = TextStyle(color = color, fontSize = size, fontWeight = weight, fontFamily = if (mono) MoneyMono else null, textAlign = align),
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = size * minScale, maxFontSize = size, stepSize = 0.5.sp),
    )
}

/** 记账首屏。iOS: MoneyViews.swift:10 */
@Composable
fun MoneyHomeView() {
    val c = UmbraTheme.colors
    val router = AppGraph.router
    // 页面订阅这些流才会在拉取 / 断网 / 切账本时重画（screenPhase 本身读的是 .value）。
    val phase by MoneyStore.phase.watch()
    val loadedOnce by MoneyStore.loadedOnce.watch()
    val stats by MoneyStore.stats.watch()
    val entries by MoneyStore.entries.watch()
    val ym by MoneyStore.ym.watch()
    val ledger by MoneyStore.ledger.watch()
    val offline by AppGraph.http.offline.watch()
    val locked by AppGraph.guard.locked.watch()
    // ⚠️ 这一行不读 cats，所以并不订阅（by 委托不读就不订阅）；真正让分类名 / 色槽跟着变的是各子卡里的 watchMoneyCats()。
    @Suppress("UNUSED_VARIABLE") val cats by MoneyStore.cats.watch()
    @Suppress("UNUSED_VARIABLE") val recurRules by MoneyStore.recurRules.watch()

    /** 分类占比 / 趋势的「以表格查看」开关。纯视图状态，退出页面就复位，不进 store。 */
    var catTable by remember { mutableStateOf(false) }
    var trendTable by remember { mutableStateOf(false) }
    var trend12 by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { MoneyStore.loadIfNeeded() }

    // ⚠️ 标题槽换成账本切换器（G1 / switcherLayer）。UmbraScreen 的推入页标题槽画不了自定义件，
    // 所以用 leading 把「返回 + 切换器」一起画，title 留给读屏 / 页面名（不画出来）。
    val leading: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            com.geek.umbra.designsystem.UmbraBackButton { router.back() }
            MoneyLedgerSwitch()
        }
    }
    // 齿轮钉右角、「＋」在它左边（骨架 02 的满配行）。**不加 ⋯** —— 那颗现在没有内容可放。
    val actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        UmbraTopBarIcon(UmbraIconPaths.plus, "记一笔", { router.go(Route.MoneyAdd(null)) }, tint = c.orange)
        UmbraTopBarIcon(UmbraIconPaths.settings, "记账设置", { router.go(Route.MoneySettings) }, tint = c.muted)
    }

    // 经营账本仍在服务端：未登录时画「要登录」，不画一片离线。iOS: MoneyViews.swift:33
    // ⚠️ 与 iOS 的差异：iOS 整屏换成 NeedLoginView（没有切换器）；这里保留顶栏的切换器 ——
    // 否则切到经营账本又没登录的人，在这一屏上没有任何一条路切回生活账本。
    if (locked && !MoneyStore.isLocal(ledger)) {
        UmbraScreen(title = "记账", leading = leading, actions = actions) {
            item(key = "needLogin") { MoneyNeedLogin() }
        }
        return
    }

    // 读一下这几个值，phase 变化时整屏重算三态。
    val screen = remember(phase, loadedOnce, stats, offline) { MoneyStore.screenPhase(offline) }
    // 「这个月一笔都没有」才算空 —— 统计为 0 但有收入（或反过来）都不算。iOS: MoneyViews.swift:86
    val isEmpty = entries.isEmpty() && (stats?.let { it.expense == 0L && it.income == 0L } ?: true)

    UmbraScreen(
        title = "记账",
        leading = leading,
        actions = actions,
        refreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                MoneyStore.reload(silent = true)
                refreshing = false
            }
        },
        spacing = UmbraDimens.sp4,
        contentPadding = PaddingValues(start = UmbraDimens.pagePadX, end = UmbraDimens.pagePadX, top = UmbraDimens.sp2, bottom = 24.dp),
        topContent = umbraPhaseTop(screen, live = true) { MoneyStore.launch { MoneyStore.retry() } },
    ) {
        umbraScreenPhase(screen, { MoneyStore.launch { MoneyStore.retry() } }, "拿不到这个月的账") {
            val st = stats
            if (isEmpty) {
                // ⚠️ 与 iOS 的差异：iOS 空态整个替换内容区，连月份行一起没了 —— 翻到一个空月之后
                // 只能退出页面（而 pinnedYm 还留着，回来还是那个空月）。这里把月份行留在空态上面。
                item(key = "month") { MonthRow(ym) }
                item(key = "empty") {
                    UmbraEmptyState(
                        UmbraIconPaths.wallet, "这个月还没有记账",
                        hint = "记一笔，这里就会出现分类占比和月度趋势。",
                        action = "记一笔", onAction = { router.go(Route.MoneyAdd(null)) },
                    )
                }
            } else if (st != null) {
                item(key = "month") { MonthRow(ym) }
                // ⚠️ 判据取**服务端有没有给那三个数**（hasBiz），不取「当前账本是不是 business」：
                // 端上自己推的话，接一个还没升级的服务端会画出一屏每个数都是 0 的经营统计。
                if (st.hasBiz) {
                    item(key = "biz") {
                        MoneyStatsBiz(st, catName = { MoneyStore.catName(it) }) { slug ->
                            MoneyStore.listDir.value = "expense"
                            MoneyStore.listCat.value = slug
                            router.go(Route.MoneyList)
                        }
                    }
                    item(key = "recur") { RecurCard() }
                } else {
                    item(key = "summary") { SummaryCard(st) }
                    item(key = "recur") { RecurCard() }
                    item(key = "catShare") { CatShareCard(st, catTable) { catTable = !catTable } }
                    item(key = "trend") {
                        TrendCard(st, trend12, trendTable, onTrend12 = { trend12 = it }, onToggleTable = { trendTable = !trendTable })
                    }
                    item(key = "top") { TopCard(entries) }
                }
                item(key = "all") {
                    UmbraButton("全部流水", {
                        MoneyStore.listDir.value = "all"
                        MoneyStore.listCat.value = null
                        router.go(Route.MoneyList)
                    }, Modifier.fillMaxWidth(), kind = UmbraButtonKind.Secondary, height = 46.dp)
                }
            }
        }
    }
}

// MARK: 月份行

/**
 * 月份行。⚠️ 2026-09-10 起真的能翻了：sam 在统计页看到某个月对不上，而没有任何一条路能从那个数字走到那个月的明细。
 * ⚠️ 往后**不设上限**：周期记账会生成未来的账，封住「下一月」会让那些账变成看不见的。
 * 不在当月时右边补一颗「回本月」。iOS: MoneyViews.swift:133
 */
@Composable
private fun MonthRow(ym: String) {
    val c = UmbraTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        MonthArrow(back = true) { MoneyStore.launch { MoneyStore.shiftMonth(-1) } }
        Spacer(Modifier.width(14.dp - 6.dp))
        Text(MoneyFmt.ymLabel(ym), color = c.text, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
        Spacer(Modifier.width(14.dp - 6.dp))
        MonthArrow(back = false) { MoneyStore.launch { MoneyStore.shiftMonth(1) } }
        // atCurrentMonth 读的是 ym.value；ym 参数已在上层订阅，这里重组时会重新判。
        if (ym != MoneyFmt.ymNow()) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.heightIn(min = UmbraDimens.tapMin).clickable(role = Role.Button) { MoneyStore.launch { MoneyStore.goMonth(null) } }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) { Text("回本月", color = c.orangeText, style = TextStyle(fontSize = 13.sp)) }
        }
    }
}

/** 圆钮画 32、点 44（规范：热区用透明外框撑，不把控件画大）。只有一个箭头：读屏要说出往哪边翻。iOS: MoneyViews.swift:151 */
@Composable
private fun MonthArrow(back: Boolean, onClick: () -> Unit) {
    val c = UmbraTheme.colors
    Box(
        Modifier.size(UmbraDimens.tapMin).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (back) "上一个月" else "下一个月" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(c.card).border(UmbraDimens.borderW, c.border, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            UmbraIcon(UmbraIconPaths.chevronRight, c.faint, Modifier.rotate(if (back) 180f else 0f), size = 16.dp, strokeWidth = 2.2f)
        }
    }
}

// MARK: 周期记账入口卡

/**
 * 周期记账入口卡（照稿 mnVM 的 goRecur / recHint / recNext）：「N 条在跑」+ 最近要记的两条预览。
 * 没有规则时也显示 —— 入口藏起来，「房租不用手记」这个功能就永远没人发现。iOS: MoneyViews.swift:171
 */
@Composable
private fun RecurCard() {
    val c = UmbraTheme.colors
    val rules by MoneyStore.recurRules.watch()
    val loaded by MoneyStore.recurLoadedOnce.watch()
    val live = rules.filter { !it.paused && it.next_at_ms > 0 }
    val next2 = live.sortedBy { it.next_at_ms }.take(2)
    Column(
        Modifier.moneyCard().clickable(role = Role.Button) { AppGraph.router.go(Route.MoneyRecur) }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UmbraIcon(MoneySrc.badge("recur")!!.second, c.orangeText, size = 15.dp)
            Spacer(Modifier.width(8.dp))
            Text("周期记账", color = c.text, style = TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.weight(1f))
            // 「拿不到」和「你没建过」要分开说：周期单独失败时 recurRules 是空的，写「还没有」就是把连不上说成了你没建过。
            Text(
                if (!loaded) "暂时拿不到" else if (live.isEmpty()) "还没有" else "${live.size} 条在跑",
                color = c.muted, style = TextStyle(fontSize = 12.sp),
            )
            Spacer(Modifier.width(8.dp))
            UmbraIcon(UmbraIconPaths.chevronRight, c.faint, size = 14.dp, strokeWidth = 2.2f)
        }
        if (next2.isEmpty()) {
            Text(
                if (!loaded) "周期规则这次没拉到，点进去重试。" else "房租、订阅这类固定账，建一条规则到点自动记，不用再手记。",
                color = c.faint, style = TextStyle(fontSize = 12.sp),
            )
        } else {
            next2.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.name, color = c.text, style = TextStyle(fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(MoneyFmt.yuan(r.cents), color = c.muted, style = TextStyle(fontSize = 12.5.sp, fontWeight = UmbraType.weight560, fontFamily = MoneyMono))
                    Spacer(Modifier.width(8.dp))
                    Text("下次 ${MoneyRecurFmt.shortDate(r.next_date)}", color = c.orangeText, style = TextStyle(fontSize = 11.5.sp))
                }
            }
        }
    }
}

// MARK: 本月支出大卡

/** 本月支出大卡：大数 + 环比 + 收入 / 结余两小格。iOS: MoneyViews.swift:217 */
@Composable
private fun SummaryCard(st: MoneyStatsDTO) {
    val c = UmbraTheme.colors
    Column(Modifier.moneyCard().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("支出", color = c.muted, style = TextStyle(fontSize = 12.5.sp))
            Text(MoneyFmt.yuan(st.expense), color = c.text, style = TextStyle(fontSize = 34.sp, fontWeight = UmbraType.weight650))
        }
        MomRow(st)
        Column {
            MoneyBleedSeparator(16.dp)
            Row(Modifier.padding(top = 11.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("收入", color = c.muted, style = TextStyle(fontSize = 12.sp))
                    Text(MoneyFmt.yuan(st.income), color = c.success, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("结余", color = c.muted, style = TextStyle(fontSize = 12.sp))
                    Text(MoneyFmt.yuan(st.balance), color = c.text, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
                }
            }
        }
    }
}

/**
 * 环比行。prev_expense 三态：null = 上月没记录（不画箭头）；0 = 记过但支出 0（百分比会除零，直说「上月支出 ¥0.00」）；
 * 正数 = 正常算百分比。iOS: MoneyViews.swift:251
 */
@Composable
private fun MomRow(st: MoneyStatsDTO) {
    val c = UmbraTheme.colors
    val ym by MoneyStore.ym.watch()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val prev = st.prev_expense
        if (prev != null && prev > 0) {
            val up = st.expense > prev
            val pct = Math.round(Math.abs(st.expense - prev).toDouble() / prev.toDouble() * 100)
            val tone = if (up) c.dangerText else c.success
            UmbraIcon(if (up) ARROW_UP else ARROW_DOWN, tone, size = 14.dp, strokeWidth = 2.2f)
            Text("比上月${if (up) "多" else "少"} $pct%", color = tone, style = TextStyle(fontSize = 13.sp, fontWeight = UmbraType.weight560))
        } else if (prev == 0L) {
            Text("上月支出 ¥0.00", color = c.muted, style = TextStyle(fontSize = 13.sp))
        } else {
            Text("上月没有记录，无法对比", color = c.muted, style = TextStyle(fontSize = 13.sp))
        }
        // 本月还没过完的话补一句剩余天数 ——「比上月少 40%」在月中是个假象，这句是拆穿它用的。
        // ⚠️ 只在选中的月份真的是本月时才出（设计侧 164 §二）：过去的月份不会再长。
        val left = daysLeft()
        if (ym == MoneyFmt.ymNow() && left > 0) {
            Text("本月还剩 $left 天", color = c.faint, style = TextStyle(fontSize = 12.sp))
        }
    }
}

/** 本月还剩几天（真实当月，本机日历）。iOS: MoneyViews.swift:280 */
private fun daysLeft(): Int {
    val d = LocalDate.now()
    return d.lengthOfMonth() - d.dayOfMonth
}

// MARK: 分类占比

/** 分类占比卡：环形 + 排行（可点带筛选跳流水）/ 表格。iOS: MoneyViews.swift:289 */
@Composable
private fun CatShareCard(st: MoneyStatsDTO, table: Boolean, onToggle: () -> Unit) {
    val c = UmbraTheme.colors
    Column(Modifier.moneyCard().padding(15.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("分类占比", color = c.text, style = TextStyle(fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
            // 没数时不出切换钮：图和表都是空的，点它只会让自己的字来回变。
            if (st.by_cat.isNotEmpty()) MoneyPill(if (table) "看图" else "以表格查看", onClick = onToggle)
        }
        if (st.by_cat.isEmpty()) {
            // 卡内无数据走 compact 档（states.cardNoData），不用整屏态 —— 屏上别的卡还有数。
            MoneyCardNoData(UmbraIconPaths.columns, "这个月还没有支出分类")
        } else if (table) {
            CatTable(st)
        } else {
            Donut(st)
            Column {
                st.by_cat.forEachIndexed { i, row -> CatRow(row, st.expense, first = i == 0) }
            }
            Text(
                "环形只画金额前 5 的分类，其余合并成一块灰色；排行里是这个月有支出的全部分类。",
                color = c.muted, style = TextStyle(fontSize = 11.5.sp, lineHeight = (11.5 * 1.6).sp),
            )
        }
    }
}

/**
 * 环形图：Canvas 逐段描边。稿的取值：170 外径、描边 26（中线直径 144）。只画金额前 5，其余合并成灰色一段（稿明写）。
 * 先把每段的起止算好再画（iOS 那边是为了躲 ForEach 里累加状态的坑，这里照做也更清楚）。iOS: MoneyViews.swift:326
 */
@Composable
private fun Donut(st: MoneyStatsDTO) {
    val c = UmbraTheme.colors
    watchMoneyCats() // 色槽 / 图标 / 分类名读的是 cats 快照：在这一层订阅，同步改了分类而 st 没变时才不画旧的
    val total = maxOf(st.expense, 1L)
    val segs = ArrayList<Pair<Color, Long>>()
    st.by_cat.take(5).forEach { segs.add(moneySlotColor(MoneyStore.catSlot(it.cat)) to it.cents) }
    val rest = st.by_cat.drop(5).sumOf { it.cents }
    val grey = moneySlotColor(0)
    if (rest > 0) segs.add(grey to rest)
    var acc = 0L
    val arcs = segs.map { (color, cents) ->
        val from = acc.toFloat() / total
        acc += cents
        Triple(from, acc.toFloat() / total, color)
    }
    val track = c.chip
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.size(170.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(170.dp)) {
                val w = 26.dp.toPx()
                val inset = w / 2
                val arcSize = Size(size.width - w, size.height - w)
                val tl = Offset(inset, inset)
                drawArc(track, 0f, 360f, useCenter = false, topLeft = tl, size = arcSize, style = Stroke(w))
                // 从 12 点方向起（-90°），顺时针。
                arcs.forEach { (from, to, color) ->
                    drawArc(color, -90f + from * 360f, (to - from) * 360f, useCenter = false, topLeft = tl, size = arcSize, style = Stroke(w))
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(MoneyFmt.yuan(st.expense), color = c.text, style = TextStyle(fontSize = 17.sp, fontWeight = UmbraType.weight650))
                Text("支出合计", color = c.muted, style = TextStyle(fontSize = 11.sp))
            }
        }
    }
}

/** 排行的一行：可点，带「支出 + 这个分类」的筛选跳流水。iOS: MoneyViews.swift:366 */
@Composable
private fun CatRow(row: MoneyCatStatDTO, total: Long, first: Boolean) {
    val c = UmbraTheme.colors
    watchMoneyCats() // 色槽 / 图标 / 分类名读的是 cats 快照：在这一层订阅，同步改了分类而 st 没变时才不画旧的
    val slot = MoneyStore.catSlot(row.cat)
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = UmbraDimens.tapMin).clickable(role = Role.Button) {
                MoneyStore.listDir.value = "expense"
                MoneyStore.listCat.value = row.cat
                AppGraph.router.go(Route.MoneyList)
            }.padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(moneySlotColor(slot)))
            Spacer(Modifier.width(9.dp))
            // 分类色块（批次 003）：排行的图标进同色 tint 块，色点保留当图例锚。iOS 写死 22 / 圆角 6。
            MoneyCatBlock(slot, MoneyStore.catArt(row.cat), block = 22.dp, iconSize = 14.dp, corner = 6.dp)
            Spacer(Modifier.width(9.dp))
            Text(MoneyStore.catName(row.cat), color = c.text, style = TextStyle(fontSize = 14.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            Text(MoneyFmt.yuan(row.cents), color = c.text, style = TextStyle(fontSize = 14.sp, fontWeight = UmbraType.weight560, fontFamily = MoneyMono))
            Spacer(Modifier.width(9.dp))
            Text(pct(row.cents, total), color = c.muted, style = TextStyle(fontSize = 12.5.sp, fontFamily = MoneyMono, textAlign = TextAlign.End), modifier = Modifier.width(48.dp))
        }
        if (!first) Box(Modifier.align(Alignment.TopCenter)) { MoneyBleedSeparator(15.dp) }
    }
}

/** 「以表格查看」那张表 + 合计行。iOS: MoneyViews.swift:400 */
@Composable
private fun CatTable(st: MoneyStatsDTO) {
    val c = UmbraTheme.colors
    watchMoneyCats() // 色槽 / 图标 / 分类名读的是 cats 快照：在这一层订阅，同步改了分类而 st 没变时才不画旧的
    val head = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = c.faint)
    Column {
        Row(Modifier.padding(bottom = 7.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("分类", style = head, modifier = Modifier.weight(1f))
            Text("金额", style = head.copy(textAlign = TextAlign.End), modifier = Modifier.width(78.dp))
            Text("占比", style = head.copy(textAlign = TextAlign.End), modifier = Modifier.width(46.dp))
            Text("笔数", style = head.copy(textAlign = TextAlign.End), modifier = Modifier.width(34.dp))
        }
        st.by_cat.forEach { row ->
            // 这张表第一行**要**画线：它上面是表头行，这条线分的是表头和数据。
            Box {
                Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(MoneyStore.catName(row.cat), color = c.text, style = TextStyle(fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    // 金额列宽钉死 78：长了缩字号而不是折行顶高整行（设计侧 184 §三末）。
                    MoneyFitText(MoneyFmt.yuan(row.cents), c.text, 13.sp, Modifier.width(78.dp), mono = true, align = TextAlign.End)
                    Text(pct(row.cents, st.expense), color = c.muted, style = TextStyle(fontSize = 13.sp, fontFamily = MoneyMono, textAlign = TextAlign.End), modifier = Modifier.width(46.dp))
                    Text("${row.count}", color = c.muted, style = TextStyle(fontSize = 13.sp, fontFamily = MoneyMono, textAlign = TextAlign.End), modifier = Modifier.width(34.dp))
                }
                Box(Modifier.align(Alignment.TopCenter)) { MoneyBleedSeparator(15.dp) }
            }
        }
        // 合计行：占比恒 100%，笔数 = 支出笔数合计。
        Box {
            Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("合计", color = c.text, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
                MoneyFitText(MoneyFmt.yuan(st.expense), c.text, 13.sp, Modifier.width(78.dp), weight = FontWeight.SemiBold, mono = true, align = TextAlign.End)
                Text("100%", color = c.text, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = MoneyMono, textAlign = TextAlign.End), modifier = Modifier.width(46.dp))
                Text("${st.by_cat.sumOf { it.count }}", color = c.text, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = MoneyMono, textAlign = TextAlign.End), modifier = Modifier.width(34.dp))
            }
            // 合计行上面这条**故意用 border 而不是 borderSoft**：它分的是「明细」和「合计」两段内容，深一档才读得出「上面结束了」。
            Box(Modifier.align(Alignment.TopCenter)) { MoneyBleedSeparator(15.dp, c.border) }
        }
    }
}

// MARK: 月度趋势

/**
 * 月度趋势卡。一次拉的就是 12 个月，近 6 月是它的切片 —— 切档位不再打接口。
 * 柱标签**整排一起算**（MoneyTrend.barLabels）：带不带年取决于这一排有没有跨年。iOS: MoneyViews.swift:454
 */
@Composable
private fun TrendCard(st: MoneyStatsDTO, trend12: Boolean, table: Boolean, onTrend12: (Boolean) -> Unit, onToggleTable: () -> Unit) {
    val c = UmbraTheme.colors
    val pts = if (trend12) st.trend else st.trend.takeLast(6)
    val maxCents = maxOf(pts.maxOfOrNull { it.cents } ?: 1L, 1L)
    val labels = MoneyTrend.barLabels(pts.map { it.ym })
    Column(Modifier.moneyCard().padding(15.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("月度趋势", color = c.text, style = TextStyle(fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
            // 同分类占比：没数时三颗钮全不出。
            if (pts.isNotEmpty()) {
                MoneyPill("近 6 月", on = !trend12) { onTrend12(false) }
                MoneyPill("近 12 月", on = trend12) { onTrend12(true) }
            }
        }
        if (pts.isEmpty()) {
            MoneyCardNoData(UmbraIconPaths.sortLines, "还没有可比较的月份")
        } else if (table) {
            Column {
                // ⚠️ 每一行**可点，点了跳到那个月** —— sam 是在这张表上看到某个月对不上的，走到明细的入口就该在这里。
                // 一行一行挨着的可点行，行本身就该是 44（不能向可点的邻居借热区）。
                pts.forEachIndexed { i, p ->
                    Box {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = UmbraDimens.tapMin)
                                .clickable(role = Role.Button) { MoneyStore.launch { MoneyStore.goMonth(p.ym) } },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(MoneyFmt.ymLabel(p.ym), color = c.text, style = TextStyle(fontSize = 13.sp), modifier = Modifier.weight(1f))
                            Text(MoneyFmt.yuan(p.cents), color = if (p.ym == st.ym) c.orangeText else c.muted, style = TextStyle(fontSize = 13.sp, fontFamily = MoneyMono))
                        }
                        // 第一行不画线：这张表上面没有表头，再画一条就成了标题下面的一条横杠。
                        if (i > 0) Box(Modifier.align(Alignment.TopCenter)) { MoneyBleedSeparator(15.dp) }
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                pts.forEachIndexed { i, p ->
                    val cur = p.ym == st.ym
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // 柱顶只标当前月，其余的看表格（稿的规则；手机上没有悬停）。
                        MoneyFitText(if (cur) MoneyFmt.yuan(p.cents) else " ", c.orangeText, 10.5.sp, weight = FontWeight.SemiBold, mono = true)
                        val h = maxOf(4f, p.cents.toFloat() / maxCents * 104f)
                        Box(
                            Modifier.fillMaxWidth().padding(horizontal = 4.dp).height(h.dp)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(if (cur) c.orange else c.track),
                        )
                        MoneyFitText(labels[i], if (cur) c.text else c.faint, 11.sp, minScale = 0.75f)
                    }
                }
            }
        }
        if (pts.isNotEmpty()) MoneyPill(if (table) "看图" else "以表格查看", onClick = onToggleTable)
    }
}

// MARK: 大额支出

/** 大额支出卡（本月前 5）：点哪一行都去流水的支出筛选。iOS: MoneyViews.swift:547 */
@Composable
private fun TopCard(entries: List<MoneyEntryDTO>) {
    val c = UmbraTheme.colors
    watchMoneyCats() // 色槽 / 图标 / 分类名读的是 cats 快照：在这一层订阅，同步改了分类而 entries 没变时才不画旧的
    val top = entries.filter { it.direction == "expense" }.sortedByDescending { it.cents }.take(5)
    Column(Modifier.moneyCard().padding(15.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("大额支出", color = c.text, style = TextStyle(fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(bottom = 5.dp))
        // 本月没有支出时，这张卡原来只剩一行标题、底下一片空 —— 像是内容没渲染出来。
        if (top.isEmpty()) MoneyCardNoData(UmbraIconPaths.wallet, "这个月还没有支出")
        top.forEachIndexed { idx, e ->
            Box {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 46.dp).clickable(role = Role.Button) {
                        MoneyStore.listDir.value = "expense"
                        MoneyStore.listCat.value = null
                        AppGraph.router.go(Route.MoneyList)
                    }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 分类色块（批次 003）：同色 tint 底 + 色槽色描边图标（iOS 写死 30 / 圆角 9）。
                    MoneyCatBlock(MoneyStore.catSlot(e.cat), MoneyStore.catArt(e.cat), block = 30.dp, iconSize = 15.dp, corner = 9.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(e.merchant.ifEmpty { MoneyStore.catName(e.cat) }, color = c.text, style = TextStyle(fontSize = 14.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(topSub(e), color = c.faint, style = TextStyle(fontSize = 11.5.sp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(MoneyFmt.yuan(e.cents), color = c.text, style = TextStyle(fontSize = 15.sp, fontWeight = UmbraType.weight560, fontFamily = MoneyMono))
                }
                if (idx > 0) Box(Modifier.align(Alignment.TopCenter)) { MoneyBleedSeparator(15.dp) }
            }
        }
    }
}

/** 「餐饮 · 午饭 · 8月12日」（本机日历）。iOS: MoneyViews.swift:594 */
private fun topSub(e: MoneyEntryDTO): String {
    val d = Instant.ofEpochMilli(e.at_ms).atZone(ZoneId.systemDefault())
    val sub = if (e.sub.isEmpty()) "" else " · ${e.sub}"
    return "${MoneyStore.catName(e.cat)}$sub · ${d.monthValue}月${d.dayOfMonth}日"
}

/** 占比一位小数；分母为 0 时写 0%。iOS: MoneyViews.swift:635 */
private fun pct(cents: Long, total: Long): String {
    if (total <= 0) return "0%"
    return String.format(Locale.ROOT, "%.1f%%", cents.toDouble() / total.toDouble() * 100)
}
