// 周期记账（二期）：规则列表（money.recur）+ 新建/编辑（money.recur.edit），照 iOS Features/Money/MoneyRecurViews.swift。
//
// 触发在服务端（拍板 D5；生活账本由 MoneyLocal 在本机补记）：这两页只管规则的增删改停，
// 到点写流水、停机补记都不是界面的事 —— 这两页没有任何「生成」逻辑。
//
// 编辑器照稿只有 每天/每周/每月/每年 四档（every_n 是服务端备用列，「每 N 个」等设计补稿再放开）。
// 批次 004 补上收入侧：编辑器顶部「记在哪边」分段控件，切了整组换分类芯片、不混排 ——
// 服务端只校 direction 本身合法，「分类属于该方向」这道门就由这个选择器挡。
package com.geek.umbra.feature.money

import com.geek.umbra.designsystem.UmbraPickers
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraBottomBar
import com.geek.umbra.designsystem.UmbraButton
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraFieldLabel
import com.geek.umbra.designsystem.UmbraGroupCard
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraMenuItem
import com.geek.umbra.designsystem.UmbraMoreMenu
import com.geek.umbra.designsystem.UmbraRowDivider
import com.geek.umbra.designsystem.UmbraScreen
import com.geek.umbra.designsystem.UmbraSegment
import com.geek.umbra.designsystem.UmbraSegmentedControl
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraTopBarIcon
import com.geek.umbra.designsystem.UmbraTopBarText
import com.geek.umbra.designsystem.UmbraType
import com.geek.umbra.navigation.Route
import com.geek.umbra.navigation.UmbraAlert
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

// MARK: - 展示工具

/**
 * 周期规则的展示工具（iOS MoneyRecurFmt）。公开：首页的周期卡也要用 shortDate / cycleText。
 * 日期一律按**公历**（java.time 的 LocalDate 就是 ISO 历）：日本历 / 佛历手机上拼「年 / 月 / 号」会落到别的年里。
 */
object MoneyRecurFmt {
    /** "2026-09-05" → "9月5日"。列表「下次」与预览句用。iOS: MoneyRecurViews.swift:15 */
    fun shortDate(s: String): String {
        val p = s.split("-")
        val m = p.getOrNull(1)?.toIntOrNull()
        val d = p.getOrNull(2)?.toIntOrNull()
        if (p.size != 3 || m == null || d == null) return s
        return "${m}月${d}日"
    }

    /** "2026-09-05" → "2026年9月5日"。结束日期要带年 —— 它常在一年以外。 */
    fun fullDate(s: String): String {
        val p = s.split("-")
        val y = p.getOrNull(0)?.toIntOrNull()
        val m = p.getOrNull(1)?.toIntOrNull()
        val d = p.getOrNull(2)?.toIntOrNull()
        if (p.size != 3 || y == null || m == null || d == null) return s
        return "${y}年${m}月${d}日"
    }

    /** 0 = 周一（规则的 week_day 口径）。 */
    val weekNames = listOf("一", "二", "三", "四", "五", "六", "日")

    /**
     * 规则的周期一句话："每天 08:30" / "每周一 09:00" / "每月 5 号 09:00" / "每年 3月1日 09:00"。
     * every_n > 1 的规则界面还建不了，但服务端存在就要显示得出（"每 3 个月 5 号"）。iOS: MoneyRecurViews.swift:32
     */
    fun cycleText(r: MoneyRecurDTO): String {
        val day = r.first_date.takeLast(2).toIntOrNull() ?: 1
        val mo = r.first_date.drop(5).take(2).toIntOrNull() ?: 1
        val n = r.every_n
        return when (r.cycle) {
            "day" -> (if (n > 1) "每 $n 天" else "每天") + " ${r.time_hhmm}"
            "week" -> (if (n > 1) "每 $n 周的周" else "每周") + weekNames[r.week_day.coerceIn(0, 6)] + " ${r.time_hhmm}"
            "year" -> (if (n > 1) "每 $n 年" else "每年") + " ${mo}月${day}日 ${r.time_hhmm}"
            else -> (if (n > 1) "每 $n 个月" else "每月") + " $day 号 ${r.time_hhmm}"
        }
    }

    /**
     * 首次~结束（含当天）里最后一个应记日期；一笔都没有回 null。
     * **必须和服务端 money_recur.last_occur_in_range 算得一样**（服务端是正本，这里只为保存前的当场拦截 ——
     * 拦不住的服务端还会再拦一道 400）。iOS: MoneyRecurViews.swift:47
     */
    fun lastOccur(cycle: String, first: LocalDate, end: LocalDate, weekDay: Int): LocalDate? {
        if (end < first) return null
        return when (cycle) {
            "day" -> end
            "week" -> {
                // DayOfWeek.value：1=周一…7=周日；规则的 0=周一 → +1。
                val target = weekDay + 1
                var d = end
                repeat(7) {
                    if (d.dayOfWeek.value == target) return if (d >= first) d else null
                    d = d.minusDays(1)
                }
                null
            }
            "year" -> {
                for (y in end.year downTo first.year) {
                    val c = monthDay(y, first.monthValue, first.dayOfMonth)
                    if (c <= end && c >= first) return c
                }
                null
            }
            else -> {
                var y = end.year
                var m = end.monthValue
                repeat(25) {
                    val c = monthDay(y, m, first.dayOfMonth)
                    if (c <= end && c >= first) return c
                    m -= 1
                    if (m == 0) { m = 12; y -= 1 }
                }
                null
            }
        }
    }

    /** 某年某月的「d 号」，短月顺延到月底（每月 31 号遇 2 月 → 2/28-29，不跳过）。 */
    private fun monthDay(year: Int, month: Int, day: Int): LocalDate {
        val f = LocalDate.of(year, month, 1)
        return f.withDayOfMonth(minOf(day, f.lengthOfMonth()))
    }

    /**
     * ⚠️ 这两个是**上行字段**（first_date / end_date / time_hhmm），一律走 MoneyWireDate：
     * 系统设成 12 小时制时 time_hhmm 会被写成 `8:30 PM`，阿拉伯语地区是 `٠٨:٣٠`。
     */
    fun dateString(ms: Long): String = MoneyWireDate.dateString(ms)

    fun timeString(ms: Long): String = MoneyWireDate.timeString(ms)

    /** 本地日期 → 线上 "YYYY-MM-DD"（经 MoneyWireDate，不自己拼）。 */
    internal fun wireDate(d: LocalDate): String = dateString(d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())

    /** 本地时刻 → 线上 "HH:mm"（经 MoneyWireDate）。 */
    internal fun wireTime(t: LocalTime): String =
        timeString(LocalDate.now().atTime(t).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
}

// MARK: - 规则列表（money.recur）

/** 周期记账列表。iOS: MoneyRecurViews.swift:107 UmbraMoneyRecurListView */
@Composable
fun MoneyRecurListView() {
    val c = UmbraTheme.colors
    val rules by MoneyStore.recurRules.watch()
    val firstLoading by MoneyStore.recurFirstLoading.watch()
    val loadedOnce by MoneyStore.recurLoadedOnce.watch()
    val cats by MoneyStore.cats.watch()
    val offline by AppGraph.http.offline.watch()
    val locked by AppGraph.guard.locked.watch()
    val ledger by MoneyStore.ledger.watch()
    val scope = rememberCoroutineScope()

    if (locked && !MoneyStore.isLocal(ledger)) {
        UmbraScreen("周期记账", onBack = { AppGraph.router.back() }) { item(key = "login") { MoneyNeedLogin() } }
        return
    }

    var busy by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val openRow = remember { mutableStateOf<Any?>(null) }

    LaunchedEffect(Unit) { MoneyStore.reloadRecur() }

    // 周期是**单独一条请求**（不在整页成败守门里），所以它有自己的标志位：原来这条请求失败时列表是空的，
    // 画出来就是「还没有周期记账」—— 把「拿不到」说成「你没建过」。
    val phase = remember(rules, firstLoading, loadedOnce, offline) { MoneyStore.recurScreenPhase(offline) }

    fun toggle(r: MoneyRecurDTO) {
        if (busy) return
        busy = true
        MoneyStore.launch {
            val ok = MoneyStore.pauseRecur(r.id, !r.paused)
            busy = false
            AppGraph.router.showToast(if (ok) (if (r.paused) "已重新开始" else "已停，不再自动记") else "没存上，检查网络后再试")
        }
    }

    /** 删除确认照稿逐句：已生成的流水**留着不动**（那些是真花过的钱），只是想停一停就用「停止」。 */
    fun confirmDelete(r: MoneyRecurDTO) {
        val body = if (r.done_count > 0) {
            "以后不再自动记这一笔。已经记进流水的 ${r.done_count} 条留着不动 —— 那些是真花过的钱。只想暂时停一停就用「停止」。"
        } else {
            "以后不再自动记这一笔。只想暂时停一停就用「停止」。"
        }
        AppGraph.router.confirm(
            UmbraAlert(title = "删除「${r.name}」这条周期记账？", message = body, confirmLabel = "删除规则", destructive = true) {
                MoneyStore.launch {
                    val ok = MoneyStore.deleteRecur(r.id)
                    AppGraph.router.showToast(if (ok) "已删除规则" else "没删掉，检查网络后再试")
                }
            },
        )
    }

    UmbraScreen(
        title = "周期记账",
        onBack = { AppGraph.router.back() },
        actions = { UmbraTopBarIcon(UmbraIconPaths.plus, "新建", { AppGraph.router.go(Route.MoneyRecurEdit(null)) }, tint = c.orange) },
        refreshing = refreshing,
        onRefresh = { scope.launch { refreshing = true; MoneyStore.reloadRecur(); refreshing = false } },
        topContent = { MoneyOfflineTop(phase) },
    ) {
        moneyPhase(phase, "拿不到周期规则", retry = { MoneyStore.launch { MoneyStore.retryRecur() } }) {
            if (rules.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "还没有周期记账。房租、保险、宽带这类固定账建一条规则，到点自动记进流水 —— 不手动停就一直跑。",
                        color = c.muted, style = TextStyle(fontSize = 13.sp, lineHeight = (13 * 1.6).sp),
                        modifier = Modifier.moneyCard().padding(horizontal = 14.dp, vertical = 13.dp),
                    )
                }
            } else {
                item(key = "rules") {
                    UmbraGroupCard {
                        rules.forEachIndexed { i, r ->
                            key(r.id) {
                                if (i > 0) UmbraRowDivider()
                                // iOS swipeActions 声明顺序是 删除、停止（最外侧在前），屏幕上左→右是 停止、删除。
                                MoneySwipeRow(
                                    key = r.id, openRow = openRow,
                                    actions = listOf(
                                        MoneySwipeAction(
                                            if (r.paused) "开始" else "停止",
                                            if (r.paused) UmbraIconPaths.play else UmbraIconPaths.pauseCircle,
                                            MoneySwipeTone.Warning,
                                        ) { toggle(r) },
                                        MoneySwipeAction("删除", UmbraIconPaths.trash, MoneySwipeTone.Danger) { confirmDelete(r) },
                                    ),
                                ) {
                                    // 分类名 / 图标在这里取：cats 变了（改名、换图标）这一层会重组，行跟着拿到新值。
                                    val k = cats.firstOrNull { it.slug == r.cat }
                                    RuleRow(r, catName = k?.name ?: r.cat, catArt = MoneyCatArt.icon(r.cat, k?.icon))
                                }
                            }
                        }
                    }
                }
            }
            item(key = "foot") {
                // 稿的页脚原话（「不推通知」「带周期标记」都是拍板过的行为）。数「在跑」的口径同首页那张卡。
                val live = rules.count { !it.paused && it.next_at_ms > 0 }
                Text(
                    "共 ${rules.size} 条 · $live 条在跑。自动记入不推通知，在流水里带「周期」标记；左滑可以停掉或删除。",
                    color = c.faint, style = TextStyle(fontSize = 12.sp, lineHeight = (12 * 1.65).sp),
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

/** 一条规则。分类名查不到时回退成 slug 本身（同 MoneyStore.catName），别让那行消失。iOS: MoneyRecurViews.swift:167 */
@Composable
private fun RuleRow(r: MoneyRecurDTO, catName: String, catArt: String) {
    val c = UmbraTheme.colors
    Row(
        Modifier.fillMaxWidth().background(c.card).clickable { AppGraph.router.go(Route.MoneyRecurEdit(r.id)) }
            // 带两行副文，走「独立卡带第二行 60 起」那一档（iosShell.list.row）。
            .defaultMinSize(minHeight = UmbraDimens.rowMinHSub)
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .alpha(if (r.paused) 0.62f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // iOS 34 方块、圆角 10（MoneyRecurViews.swift:170）。
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(UmbraDimens.radiusControl)).background(if (r.paused) c.chip else c.orangeSoft),
            contentAlignment = Alignment.Center,
        ) { UmbraIcon(catArt, if (r.paused) c.faint else c.orangeText, size = 17.dp) }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    r.name, color = c.text, style = TextStyle(fontSize = 15.sp, fontWeight = UmbraType.weight560),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    catName, color = c.muted, style = TextStyle(fontSize = 10.5.sp), maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(c.chip).padding(horizontal = 7.dp, vertical = 1.dp),
                )
            }
            Text(
                "${MoneyRecurFmt.cycleText(r)} · ${history(r)}", color = c.faint, style = TextStyle(fontSize = 11.5.sp),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(6.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                MoneyFmt.yuan(r.cents), color = c.text,
                style = TextStyle(fontSize = 14.5.sp, fontWeight = UmbraType.weight560, fontFamily = FontFamily.Monospace),
            )
            // 停用时给一句完整的说明，别和名称旁的态重复（稿原话）。
            Text(
                nextText(r), color = if (r.paused || r.next_at_ms == 0L) c.faint else c.orangeText,
                style = TextStyle(fontSize = 11.sp),
            )
        }
    }
}

/** 「已自动记 N 笔 · 最近 M月d日」—— 两个值都是服务端从流水现算的**真值**。 */
private fun history(r: MoneyRecurDTO): String {
    if (r.done_count <= 0) return "还没记过"
    val d = Instant.ofEpochMilli(r.last_done_ms).atZone(ZoneId.systemDefault()).toLocalDate()
    return "已自动记 ${r.done_count} 笔 · 最近 ${d.monthValue}月${d.dayOfMonth}日"
}

private fun nextText(r: MoneyRecurDTO): String = when {
    r.paused -> "已停，不再自动记"
    r.next_at_ms == 0L -> "已到结束日期"
    else -> "下次 ${MoneyRecurFmt.shortDate(r.next_date)}"
}

// MARK: - 新建 / 编辑规则（money.recur.edit）

/** 新建 / 编辑一条周期规则。id == null = 新建。iOS: MoneyRecurViews.swift:266 UmbraMoneyRecurEditView */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoneyRecurEditView(id: String?) {
    val c = UmbraTheme.colors
    val rules by MoneyStore.recurRules.watch()
    val cats by MoneyStore.cats.watch()
    val locked by AppGraph.guard.locked.watch()
    val ledger by MoneyStore.ledger.watch()
    val title = if (id == null) "新建周期记账" else "编辑周期记账"

    if (locked && !MoneyStore.isLocal(ledger)) {
        UmbraScreen(title, onBack = { AppGraph.router.back() }) { item(key = "login") { MoneyNeedLogin() } }
        return
    }

    val editing = id?.let { rid -> rules.firstOrNull { it.id == rid } }

    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var merchant by remember { mutableStateOf("") }
    /** 记在哪边（批次 004）。切方向时分类跳到那一侧第一个、sub 清空 —— sub 挂在旧分类名下，跟着过去就是脏数据。 */
    var dir by remember { mutableStateOf("expense") }
    var cat by remember { mutableStateOf("housing") }
    /** 编辑时保住原规则的 sub（编辑器没画 sub，不该因为没画就抹掉）；切方向清空。 */
    var sub by remember { mutableStateOf("") }
    var cycle by remember { mutableStateOf("month") }
    var weekDay by remember { mutableIntStateOf(0) }
    var firstDate by remember { mutableStateOf(LocalDate.now().plusDays(1)) }
    var time by remember { mutableStateOf(LocalTime.of(9, 0)) }
    var endsOnDate by remember { mutableStateOf(false) }
    var endDate by remember { mutableStateOf(LocalDate.now().plusYears(1)) }
    var showFirstPick by remember { mutableStateOf(false) }
    var showTimePick by remember { mutableStateOf(false) }
    var showEndPick by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var seeded by remember { mutableStateOf(false) }

    // 编辑：拿规则的值灌进表单，只灌一次（规则表是异步拉的，冷进时等它到了再灌）。
    LaunchedEffect(editing != null) {
        val r = editing ?: return@LaunchedEffect
        if (seeded) return@LaunchedEffect
        seeded = true
        name = r.name
        amount = String.format(Locale.ROOT, "%.2f", r.cents / 100.0)
        merchant = r.merchant
        dir = r.direction
        cat = r.cat
        sub = r.sub
        cycle = r.cycle
        weekDay = r.week_day
        // ⚠️ 解析服务端的字段也走 MoneyWireDate：佛历手机把 `2026-09-05` 读成公元 1483 年，人什么都没改、
        // 点一下保存，first_date 就被写回成 `1483-09-05`。
        MoneyWireDate.parse(r.first_date, r.time_hhmm)?.let { ms ->
            val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDateTime()
            firstDate = t.toLocalDate()
            time = t.toLocalTime().withSecond(0).withNano(0)
        }
        endsOnDate = r.end_kind == "date"
        if (endsOnDate) MoneyWireDate.parse(r.end_date)?.let { ms -> endDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate() }
    }

    /**
     * ⚠️ **一条规则的金额必须大于 0**（`> 0`，不是 `>= 0`）—— 一笔账可以是 0（它真发生过），
     * 一条规则不能是 0：一条每月自动记 ¥0 的规则会一直往账本里灌空行。
     */
    val cents = MoneyAmount.cents(amount)?.takeIf { it > 0 }

    /** 「一笔都记不到」当场拦（稿的两句原话）。服务端还有一道同款 400 兜底。iOS: MoneyRecurViews.swift:544 */
    val endBadText: String? = run {
        if (!endsOnDate) return@run null
        if (MoneyRecurFmt.lastOccur(cycle, firstDate, endDate, weekDay) != null) return@run null
        val f = MoneyRecurFmt.fullDate(MoneyRecurFmt.wireDate(firstDate))
        if (endDate < firstDate) return@run "结束日期早于首次日期（$f），这样一笔都不会记。"
        "从 $f 到 ${MoneyRecurFmt.fullDate(MoneyRecurFmt.wireDate(endDate))} 之间没有符合这个周期的日子，一笔都不会记。把结束日期往后挪，或者换个周期。"
    }

    val canSave = name.isNotBlank() && cents != null && endBadText == null && !busy

    fun pickWeek() {
        moneyPresentList(
            key = "money-recur-week", title = "每周几",
            items = MoneyRecurFmt.weekNames.mapIndexed { i, w -> MoneySheetItem("周$w", checked = weekDay == i) { weekDay = i } },
        )
    }

    fun pickEnd() {
        moneyPresentList(
            key = "money-recur-end", title = "结束",
            items = listOf(
                MoneySheetItem("不设结束", checked = !endsOnDate) { endsOnDate = false },
                MoneySheetItem("到某天结束", checked = endsOnDate) { endsOnDate = true },
            ),
        )
    }

    fun save() {
        val n = name.trim()
        if (n.isEmpty()) { AppGraph.router.showToast("先给这条规则起个名字"); return }
        val cc = cents ?: run { AppGraph.router.showToast("金额要大于 0"); return }
        endBadText?.let { bad ->
            AppGraph.router.showToast(if (bad.startsWith("结束日期早于")) "结束日期要晚于首次日期" else "这个区间里一笔都记不到")
            return
        }
        if (busy) return
        val firstWire = MoneyRecurFmt.wireDate(firstDate)
        val timeWire = MoneyRecurFmt.wireTime(time)
        val endWire = if (endsOnDate) MoneyRecurFmt.wireDate(endDate) else ""
        val everyN = editing?.every_n ?: 1
        val tz = MoneyFmt.tzOffsetMin
        // 本地再过一道与服务端 `_validate` 同口径的校验（MoneyLocalRules.validateRule）：上面三句只拦了
        // 看得见的那三种，其余（时刻 / 时区 / 周几越界）理论上到不了这里，真到了就别发一个必然 400 的请求。
        // ⚠️ Android 补的一道，iOS 页面里没有；提示是 Android 自拟的（iOS 没有这一句）。
        val probe = MoneyRecurDTO(
            id = editing?.id ?: "probe", name = n, cents = cc, direction = dir, cat = cat, sub = sub,
            merchant = merchant.trim(), cycle = cycle, every_n = everyN, week_day = weekDay, first_date = firstWire,
            time_hhmm = timeWire, tz_offset_min = tz, end_kind = if (endsOnDate) "date" else "never", end_date = endWire,
        )
        if (runCatching { MoneyLocalRules.validateRule(probe) }.getOrNull() == null) {
            AppGraph.router.showToast("这条规则有一项填得不对，没存")
            return
        }
        busy = true
        // 字段名照 iOS saveRecur 的 body —— 服务端那张表（本机账本走同一份字段）。
        val body = buildJsonObject {
            put("name", n)
            put("cents", cc)
            put("direction", dir)
            put("cat", cat)
            put("sub", sub)
            put("merchant", merchant.trim())
            put("cycle", cycle)
            put("every_n", everyN)
            put("week_day", weekDay)
            put("first_date", firstWire)
            put("time_hhmm", timeWire)
            put("tz_offset_min", tz)
            put("end_kind", if (endsOnDate) "date" else "never")
            put("end_date", endWire)
        }
        MoneyStore.launch {
            val ok = MoneyStore.saveRecur(editing?.id, body)
            busy = false
            if (!ok) { AppGraph.router.showToast("没存上，检查网络后再试"); return@launch }
            AppGraph.router.showToast(if (id == null) "已建好，到点自动记" else "已保存，改动只影响以后的")
            // ⚠️ 异步回应之后不用裸 back()：请求在飞时人可能已经返回 / 切 tab 推了别的页。只在眼前仍是这一页时才退。
            AppGraph.router.back(ifShowing = Route.MoneyRecurEdit(id))
        }
    }

    fun askDelete() {
        val r = editing ?: return
        val body = if (r.done_count > 0) {
            "以后不再自动记这一笔。已经记进流水的留着不动 —— 那些是真花过的钱。只想暂时停一停就用列表里的「停止」。"
        } else {
            "以后不再自动记这一笔。只想暂时停一停就用列表里的「停止」。"
        }
        AppGraph.router.confirm(
            UmbraAlert(title = "删除「${r.name}」这条周期记账？", message = body, confirmLabel = "删除规则", destructive = true) {
                MoneyStore.launch {
                    val ok = MoneyStore.deleteRecur(r.id)
                    AppGraph.router.showToast(if (ok) "已删除规则" else "没删掉，检查网络后再试")
                    if (ok) AppGraph.router.back(ifShowing = Route.MoneyRecurEdit(id))
                }
            },
        )
    }

    UmbraScreen(
        title = title,
        onBack = { AppGraph.router.back() },
        leading = { UmbraTopBarText("取消", { AppGraph.router.back() }) },
        actions = {
            // ⋯ 只装破坏性动作（iosShell.toolbar）。新建态没有可删的东西，不出这颗。
            if (editing != null) UmbraMoreMenu(listOf(UmbraMenuItem("删除这条规则", danger = true) { askDelete() }))
        },
        contentPadding = PaddingValues(start = UmbraDimens.pagePadX, end = UmbraDimens.pagePadX, top = UmbraDimens.sp2, bottom = 24.dp),
        spacing = 13.dp,
        bottomBar = {
            // 底部只留「保存」这一颗。「删除」被骨架 iosShell.toolbar.noDestructiveAtBottom 挪进了右上角 ⋯。
            UmbraBottomBar {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    UmbraButton(if (id == null) "建好，到点自动记" else "保存修改", { save() }, Modifier.fillMaxWidth(), enabled = canSave)
                    if (editing != null) {
                        Text("改动只影响以后的 —— 已经自动记进流水的不变。", color = c.muted, style = TextStyle(fontSize = 11.5.sp))
                    }
                }
            }
        },
    ) {
        // MARK: 名字 / 金额 / 备注 / 分类
        item(key = "basic") {
            Column(Modifier.moneyCard()) {
                // 「记在哪边」是词表定稿（记一笔、周期记账、新增分类三处同词）。
                UmbraFieldLabel("记在哪边", Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp))
                UmbraSegmentedControl(
                    segments = listOf(UmbraSegment("支出"), UmbraSegment("收入")),
                    selected = if (dir == "income") 1 else 0,
                    onSelect = { i ->
                        val d = if (i == 1) "income" else "expense"
                        if (d != dir) {
                            dir = d
                            cat = MoneyStore.enabledCats(d).firstOrNull()?.slug ?: if (d == "income") "other_in" else "other"
                            sub = ""
                        }
                    },
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 7.dp, bottom = if (dir == "income") 7.dp else 12.dp),
                )
                if (dir == "income") {
                    Text(
                        "收入侧的周期规则，比如工资每月 10 号自动入账。", color = c.muted,
                        style = TextStyle(fontSize = 11.5.sp, lineHeight = (11.5 * 1.55).sp),
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
                    )
                }
                UmbraRowDivider()
                FieldRow("名字") { InlineField(name, { name = it }, "例如「房租」", Modifier.weight(1f)) }
                UmbraRowDivider()
                FieldRow("金额") {
                    Spacer(Modifier.weight(1f))
                    Text("¥", color = c.muted, style = TextStyle(fontSize = 14.sp))
                    InlineField(
                        amount, { amount = it }, "0.00", Modifier.widthIn(max = 140.dp),
                        mono = true, faint = cents == null, keyboard = KeyboardType.Decimal,
                    )
                }
                UmbraRowDivider()
                FieldRow("备注") { InlineField(merchant, { merchant = it }, "可不填", Modifier.weight(1f)) }
                UmbraRowDivider()
                // 分类芯片跟着「记在哪边」整组换（批次 004）：数据驱动，含兜底分类 —— 记一笔的选择器就是这个口径。
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    cats.filter { it.direction == dir && it.enabled }.forEach { k ->
                        val on = cat == k.slug
                        val pill = RoundedCornerShape(50)
                        Box(Modifier.heightIn(min = UmbraDimens.tapMin).clickable(role = Role.Button) { cat = k.slug }, contentAlignment = Alignment.Center) {
                            Row(
                                Modifier.height(34.dp).clip(pill).background(if (on) c.orangeSoft else c.card)
                                    .border(UmbraDimens.borderW, if (on) c.orange else c.border, pill).padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                UmbraIcon(MoneyCatArt.icon(k.slug, k.icon), if (on) c.orangeText else moneySlotColor(k.slot), size = 13.dp)
                                Text(k.name, color = if (on) c.orangeText else c.muted, style = TextStyle(fontSize = 13.sp, fontWeight = UmbraType.weight560))
                            }
                        }
                    }
                }
            }
        }

        // MARK: 周期
        item(key = "cycle") {
            val cycles = listOf("day" to "每天", "week" to "每周", "month" to "每月", "year" to "每年")
            Column(Modifier.moneyCard()) {
                UmbraSegmentedControl(
                    segments = cycles.map { UmbraSegment(it.second) },
                    selected = cycles.indexOfFirst { it.first == cycle }.coerceAtLeast(0),
                    onSelect = { cycle = cycles[it].first },
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                )
                if (cycle == "week") {
                    UmbraRowDivider()
                    ChipRow("每周几") { Chip("周${MoneyRecurFmt.weekNames[weekDay.coerceIn(0, 6)]}") { pickWeek() } }
                }
                if (cycle == "month" || cycle == "year") {
                    UmbraRowDivider()
                    ChipRow("从哪天开始") { Chip(MoneyRecurFmt.fullDate(MoneyRecurFmt.wireDate(firstDate))) { showFirstPick = true } }
                }
                UmbraRowDivider()
                // 首次日期 / 时间是两个值（改时间不动日期），各自单开（批次 007：一个值 = 一个字段）。
                ChipRow(if (cycle == "day") "每天几点" else "时间") { Chip(MoneyRecurFmt.wireTime(time), mono = true) { showTimePick = true } }
                // 只有落在 29/30/31 号才解释顺延，别在别的日期上唠叨（稿原话）。
                if (cycle == "month" && firstDate.dayOfMonth >= 29) {
                    Text(
                        "碰到没有 ${firstDate.dayOfMonth} 号的月份（比如 2 月），顺延到当月最后一天记，不跳过。",
                        color = c.muted, style = TextStyle(fontSize = 11.5.sp),
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 11.dp),
                    )
                }
            }
        }

        // MARK: 结束
        item(key = "end") {
            Column(Modifier.moneyCard()) {
                ChipRow("结束") { Chip(if (endsOnDate) "到某天结束" else "不设结束") { pickEnd() } }
                if (endsOnDate) {
                    UmbraRowDivider()
                    ChipRow("结束日期") { Chip(MoneyRecurFmt.fullDate(MoneyRecurFmt.wireDate(endDate))) { showEndPick = true } }
                    if (endBadText != null) {
                        Text(
                            endBadText, color = c.dangerText, style = TextStyle(fontSize = 11.5.sp, fontWeight = UmbraType.weight560),
                            modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 11.dp),
                        )
                    } else {
                        Text(
                            "结束日期含当天：当天该记的那笔照记，之后不再记。", color = c.muted, style = TextStyle(fontSize = 11.5.sp),
                            modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 11.dp),
                        )
                    }
                }
            }
        }

        // MARK: 预览
        item(key = "preview") {
            Row(Modifier.padding(horizontal = 3.dp), verticalAlignment = Alignment.Top) {
                MoneySrc.badge("recur")?.let { UmbraIcon(it.second, c.orangeText, size = 13.dp, modifier = Modifier.padding(top = 2.dp)) }
                Spacer(Modifier.width(8.dp))
                Text(
                    previewText(cycle, weekDay, firstDate, time, endsOnDate, endDate), color = c.muted,
                    style = TextStyle(fontSize = 12.5.sp, lineHeight = (12.5 * 1.55).sp),
                )
            }
        }
    }

    // 暂用 M3 DatePicker/TimePicker；提醒代理在另一分支做 UmbraDateTimePanel，合并后统一替换。
    if (showFirstPick) {
        RecurDatePickerDialog(initial = firstDate, min = null, minNote = null, onDismiss = { showFirstPick = false }) { firstDate = it }
    }
    if (showTimePick) {
        RecurTimePickerDialog(initial = time, onDismiss = { showTimePick = false }) { time = it }
    }
    if (showEndPick) {
        // 结束日期带下界：早于首次日期的日子在面板里就点不动，比事后标红省一步。
        RecurDatePickerDialog(
            initial = endDate, min = firstDate,
            minNote = "早于首次日期 ${MoneyRecurFmt.fullDate(MoneyRecurFmt.wireDate(firstDate))} 的日子选不了。含当天：当天该记的那笔照记。",
            onDismiss = { showEndPick = false },
        ) { endDate = it }
    }
}

/**
 * 预览句照稿 mreVM：只排得到一笔时别说「之后每年一笔」—— 那句承诺了循环，跟「最后一笔」自相矛盾。
 * iOS: MoneyRecurViews.swift:561
 */
private fun previewText(cycle: String, weekDay: Int, firstDate: LocalDate, time: LocalTime, endsOnDate: Boolean, endDate: LocalDate): String {
    val t = MoneyRecurFmt.wireTime(time)
    val tail: String
    if (endsOnDate) {
        val lastAt = MoneyRecurFmt.lastOccur(cycle, firstDate, endDate, weekDay)
        if (lastAt != null) {
            if (lastAt == firstDate) {
                return "只在 ${MoneyRecurFmt.fullDate(MoneyRecurFmt.wireDate(firstDate))} $t 记这一笔，之后不再记。"
            }
            tail = "最后一笔记在 ${MoneyRecurFmt.fullDate(MoneyRecurFmt.wireDate(lastAt))} $t，之后不再记。"
        } else {
            tail = "这个区间里一笔都记不到。"
        }
    } else {
        tail = "直到你停掉。"
    }
    val mo = firstDate.monthValue
    val d = firstDate.dayOfMonth
    return when (cycle) {
        "day" -> "每天 $t 自动记一笔，$tail"
        "week" -> "每周${MoneyRecurFmt.weekNames[weekDay.coerceIn(0, 6)]} $t 自动记一笔，$tail"
        "year" -> "首次 ${mo}月${d}日 $t，之后每年这一天一笔，$tail"
        else -> "首次 ${mo}月${d}日 $t，之后每月 $d 号一笔，$tail"
    }
}

// MARK: 小件

/** 表单行：左标签 15 + 右侧内容，行高 48（iOS `.padding(.horizontal, 14).frame(minHeight: 48)`）。 */
@Composable
private fun FieldRow(label: String, trailing: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val c = UmbraTheme.colors
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = UmbraDimens.rowMinH).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(label, color = c.text, style = TextStyle(fontSize = 15.sp))
        trailing()
    }
}

/** 右对齐的行内输入框（iOS `TextField` + `.multilineTextAlignment(.trailing)`）。 */
@Composable
private fun InlineField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier,
    mono: Boolean = false,
    faint: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
) {
    val c = UmbraTheme.colors
    val style = if (mono) {
        TextStyle(fontSize = 15.5.sp, fontWeight = UmbraType.weight560, fontFamily = FontFamily.Monospace, color = if (faint) c.faint else c.text, textAlign = TextAlign.End)
    } else {
        TextStyle(fontSize = 14.5.sp, color = c.text, textAlign = TextAlign.End)
    }
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true, textStyle = style,
        cursorBrush = SolidColor(c.orange), keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier,
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                if (value.isEmpty()) Text(placeholder, color = c.faint, style = style.copy(color = c.faint))
                inner()
            }
        },
    )
}

/** 「标签 … 芯片」一行，行高 48。 */
@Composable
private fun ChipRow(label: String, chips: @Composable () -> Unit) {
    val c = UmbraTheme.colors
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = UmbraDimens.rowMinH).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = c.text, style = TextStyle(fontSize = 15.sp), modifier = Modifier.weight(1f))
        chips()
    }
}

/** 值芯片：36 高、圆角 10、chip 底；热区补到 44（同 MoneyAddView.timeChip）。 */
@Composable
private fun Chip(label: String, mono: Boolean = false, onClick: () -> Unit) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(UmbraDimens.radiusControl)
    Box(Modifier.heightIn(min = UmbraDimens.tapMin).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(
            Modifier.height(36.dp).clip(shape).background(c.chip).border(UmbraDimens.borderW, c.border, shape).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label, color = c.text,
                style = TextStyle(fontSize = 14.sp, fontWeight = UmbraType.weight560, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default),
            )
        }
    }
}

/**
 * 日期选择 —— 走共用的 UmbraDateTimePanel（iOS umbraDatePicker）。弹层由 Router 托管，
 * 所以这里弹出去之后立刻 onDismiss 把「要弹」的旗子放下；取消就是什么都不回。
 */
@Composable
private fun RecurDatePickerDialog(initial: LocalDate, min: LocalDate?, minNote: String?, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    LaunchedEffect(Unit) {
        val zone = ZoneId.systemDefault()
        UmbraPickers.presentDate(
            "日期", initial.atStartOfDay(zone).toInstant().toEpochMilli(),
            minDayMs = min?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(), minNote = minNote,
        ) { ms -> onPick(Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()) }
        onDismiss()
    }
}

/** 时刻选择（24 小时制，线上 time_hhmm 也是 24 小时）—— 走共用的 UmbraDateTimePanel 时分滚轮。 */
@Composable
private fun RecurTimePickerDialog(initial: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    LaunchedEffect(Unit) {
        val zone = ZoneId.systemDefault()
        val ms = LocalDate.now(zone).atTime(initial).atZone(zone).toInstant().toEpochMilli()
        UmbraPickers.presentTime("时间", ms) { picked -> onPick(Instant.ofEpochMilli(picked).atZone(zone).toLocalTime().withSecond(0).withNano(0)) }
        onDismiss()
    }
}
