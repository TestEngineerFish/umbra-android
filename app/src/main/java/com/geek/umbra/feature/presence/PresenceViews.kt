// 账号页「使用记录」（需求 §14.8，照 GitHub 贡献图；契约 §15.1）—— iOS: Features/Presence/PresenceViews.swift。
//
//   组头：「使用记录」+ 右侧年份切换 + ⋯（关于这份记录 / 停止·继续统计 / 清空记录）
//   卡一：「过去一年活跃 N 天 · 操作 M 次」+ 53 列 × 7 行的方格（横向可滚，默认滚到最右 = 今天）+ 点一格出那天的一句 + 图例
//   卡二（有画像时）：你的作息 —— 工作日 / 休息日两条 24 小时浓淡条
//   卡三：「活动」按月分组，默认本月，「显示更多」往前一个有数据的月份
//
// 账号页以后会嵌入 [PresenceRecordSection]（放在「账号」那一组之后）。只有时刻与计数，没有任何内容（§14.1 隐私）。
package com.geek.umbra.feature.presence

import androidx.compose.foundation.layout.widthIn
import com.geek.umbra.designsystem.UmbraDimens
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraButton
import com.geek.umbra.designsystem.UmbraExtraIcons
import com.geek.umbra.designsystem.UmbraFieldLabel
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraPresenceHeat
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraType
import com.geek.umbra.navigation.UmbraAlert
import com.geek.umbra.navigation.UmbraSheet
import com.geek.umbra.net.ApiResult
import com.geek.umbra.net.HttpResult
import com.geek.umbra.net.ServerSettings
import com.geek.umbra.net.UmbraJson
import com.geek.umbra.net.isOk
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 一次读取的结果：成功带值，失败带服务端原话（空串 = 没原话）。 */
private sealed interface PresenceLoad<out T> {
    data class Ok<T>(val value: T) : PresenceLoad<T>
    data class Failed(val why: String) : PresenceLoad<Nothing>
}

/** GET 并解码；失败时把服务端 detail 交回来（iOS sendJSONEither 那一档）。 */
private suspend fun <T> fetch(path: String, ser: KSerializer<T>, query: Map<String, Any?> = emptyMap()): PresenceLoad<T> =
    when (val r = AppGraph.http.raw("GET", path, query)) {
        is HttpResult.Ok -> runCatching { UmbraJson.decodeFromString(ser, r.body) }.getOrNull()?.let { PresenceLoad.Ok(it) } ?: PresenceLoad.Failed("")
        is HttpResult.Fail -> PresenceLoad.Failed(r.detail ?: "")
        else -> PresenceLoad.Failed("")
    }

/** 使用记录这一块的数据（iOS PresenceRecordModel）。页面级：账号页一进来就重读。 */
private class PresenceRecordModel {
    /** 当前看的年份；null = 过去一年。 */
    var year by mutableStateOf<Int?>(null)
    var calendar by mutableStateOf<PresenceCalendarDTO?>(null)
    var grid by mutableStateOf<PresenceKit.Grid?>(null)
    /** 读日历失败的原话；null = 没失败（空串 = 没原话）。 */
    var calendarError by mutableStateOf<String?>(null)
    /** 已展开的月份（新的在前）。 */
    var months by mutableStateOf<List<PresenceActivityDTO>>(emptyList())
    var loadingMonth by mutableStateOf(false)
    var monthError by mutableStateOf(false)
    var selected by mutableStateOf<PresenceKit.Cell?>(null)
    /** 作息画像。null = 没读到 / 老服务端没有这条（那一块就不画）。 */
    var profile by mutableStateOf<PresenceProfileDTO?>(null)

    suspend fun loadAll() = coroutineScope {
        val a = async { loadCalendar() }
        val b = async { loadFirstMonth() }
        val c = async { loadProfile() }
        a.await(); b.await(); c.await()
    }

    suspend fun loadProfile() {
        (fetch("/presence/profile", PresenceProfileDTO.serializer()) as? PresenceLoad.Ok)?.let { profile = it.value }
    }

    suspend fun loadCalendar() {
        when (val r = fetch("/presence/calendar", PresenceCalendarDTO.serializer(), mapOf("year" to year))) {
            is PresenceLoad.Ok -> {
                calendar = r.value
                grid = PresenceKit.grid(r.value.range.from, r.value.range.to, r.value.days)
                calendarError = if (grid == null) "" else null
                selected = null
            }
            is PresenceLoad.Failed -> calendarError = r.why
        }
    }

    /** 默认展开本月（上海时区的「本月」，同服务端切天）。 */
    suspend fun loadFirstMonth() {
        months = emptyList()
        loadMonth(PresenceKit.month(System.currentTimeMillis()))
    }

    /** 「显示更多」：往前一个有数据的月份（服务端给的 prev）。 */
    suspend fun loadMore() {
        val prev = months.lastOrNull()?.prev ?: return
        loadMonth(prev)
    }

    private suspend fun loadMonth(m: String) {
        if (loadingMonth) return
        loadingMonth = true
        when (val r = fetch("/presence/activity", PresenceActivityDTO.serializer(), mapOf("month" to m))) {
            is PresenceLoad.Ok -> {
                monthError = false
                if (months.none { it.month == r.value.month }) months = months + r.value
            }
            is PresenceLoad.Failed -> monthError = true
        }
        loadingMonth = false
    }

    /** 停止 / 继续统计 = 设置键 presence.learn，走 PUT /settings。回 false = 没改成。 */
    suspend fun setLearn(on: Boolean): Boolean {
        if (ServerSettings.put(mapOf("presence.learn" to JsonPrimitive(on))) !is ApiResult.Ok<JsonObject>) return false
        loadCalendar()
        return true
    }

    suspend fun clear(): Boolean {
        if (!AppGraph.http.call("DELETE", "/presence").isOk) return false
        profile = null
        loadAll()
        return true
    }
}

/** 方格一格的边长与间距：11 + 3 = 14 一列，53 列 ≈ 742：手机上横向滚（默认滚到最右）。 */
private val CELL = 11.dp
private val GAP = 3.dp
private val CardShape = RoundedCornerShape(18.dp)

/** 「presence.cat.x」这类 key → 资源。认不出的落「其他」（PresenceKit.categoryKey 已经兜过一次）。 */
private fun catRes(key: String): Int = when (key) {
    "presence.cat.chat" -> R.string.presence_cat_chat
    "presence.cat.money" -> R.string.presence_cat_money
    "presence.cat.vault" -> R.string.presence_cat_vault
    "presence.cat.phrases" -> R.string.presence_cat_phrases
    "presence.cat.inspirations" -> R.string.presence_cat_inspirations
    "presence.cat.reminders" -> R.string.presence_cat_reminders
    "presence.cat.tasks" -> R.string.presence_cat_tasks
    "presence.cat.knowledge" -> R.string.presence_cat_knowledge
    "presence.cat.files" -> R.string.presence_cat_files
    "presence.cat.settings" -> R.string.presence_cat_settings
    else -> R.string.presence_cat_other
}

/** 按界面语言、上海时区格式化日期（iOS setLocalizedDateFormatFromTemplate）。 */
private fun fmt(locale: Locale, skeleton: String): DateTimeFormatter =
    DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)

/** 使用记录整块。账号页嵌入它（自己带左右页边距，放进 LazyColumn 的一个 item 里即可）。 */
@Composable
fun PresenceRecordSection() {
    val model = remember { PresenceRecordModel() }
    LaunchedEffect(Unit) { model.loadAll() }
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Header(model)
        CalendarCard(model, locale)
        model.profile?.let { PresenceProfileCard(it) }
        ActivityCard(model, locale)
    }
}

@Composable
private fun Header(model: PresenceRecordModel) {
    val c = UmbraTheme.colors
    var yearOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    val learn = model.calendar?.learn ?: true
    val writeFailed = stringResource(R.string.presence_writeFailed)
    val cleared = stringResource(R.string.presence_cleared)
    val pastYear = stringResource(R.string.presence_pastYear)
    val yearFmt = stringResource(R.string.presence_year)
    fun yearLabel(y: Int?) = if (y == null) pastYear else String.format(yearFmt, y)
    val aboutTitle = stringResource(R.string.presence_about)
    val aboutBody = stringResource(R.string.presence_aboutBody)
    val ok = stringResource(R.string.presence_ok)
    val clearTitle = stringResource(R.string.presence_clearTitle)
    val clearBody = stringResource(R.string.presence_clearBody)
    val clearOk = stringResource(R.string.presence_clearOk)
    val cancel = stringResource(R.string.duty_cancel)

    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        UmbraFieldLabel(stringResource(R.string.presence_title), Modifier.weight(1f))
        // 年份切换：默认「过去一年」，往前是有数据的年份（服务端 years）。
        Box {
            Row(
                Modifier.heightIn(min = UmbraDimens.tapMin).clickable(role = Role.Button) { yearOpen = true }.padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(yearLabel(model.year), color = c.orangeText, style = TextStyle(fontSize = 13.sp, fontWeight = UmbraType.weight560))
                UmbraIcon(UmbraIconPaths.chevronDown, c.orangeText, size = 12.dp, contentDescription = stringResource(R.string.presence_yearPick))
            }
            DropdownMenu(expanded = yearOpen, onDismissRequest = { yearOpen = false }, containerColor = c.card, modifier = Modifier.widthIn(min = 180.dp)) {
                PresenceKit.yearOptions(model.calendar?.years ?: emptyList()).forEach { y ->
                    DropdownMenuItem(
                        text = { Text((if (y == model.year) "✓ " else "") + yearLabel(y), color = c.text) },
                        onClick = { yearOpen = false; model.year = y; AppGraph.scope.launch { model.loadCalendar() } },
                    )
                }
            }
        }
        // 右上「⋯」：关于 / 停止·继续统计 / 清空。
        Box {
            Box(Modifier.size(UmbraDimens.tapMin).clickable(role = Role.Button) { moreOpen = true }, contentAlignment = Alignment.Center) {
                UmbraIcon(UmbraExtraIcons.more, c.muted, size = 18.dp, contentDescription = stringResource(R.string.presence_more))
            }
            DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }, containerColor = c.card, modifier = Modifier.widthIn(min = 180.dp)) {
                DropdownMenuItem(text = { Text(aboutTitle, color = c.text) }, onClick = {
                    moreOpen = false
                    // 只有一颗「知道了」：系统弹窗那一档总带「取消」，这里用底部弹层。
                    AppGraph.router.present(UmbraSheet("presence-about") { dismiss ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(aboutTitle, color = c.text, style = UmbraType.sectionTitle)
                            Text(aboutBody, color = c.muted, style = TextStyle(fontSize = 14.sp, lineHeight = 21.sp))
                            UmbraButton(ok, dismiss, Modifier.fillMaxWidth())
                        }
                    })
                })
                DropdownMenuItem(text = { Text(stringResource(if (learn) R.string.presence_stop else R.string.presence_resume), color = c.text) }, onClick = {
                    moreOpen = false
                    AppGraph.scope.launch { if (!model.setLearn(!learn)) AppGraph.router.showToast(writeFailed) }
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.presence_clear), color = c.dangerText) }, onClick = {
                    moreOpen = false
                    AppGraph.router.confirm(UmbraAlert(title = clearTitle, message = clearBody, confirmLabel = clearOk, cancelLabel = cancel, destructive = true) {
                        AppGraph.scope.launch { AppGraph.router.showToast(if (model.clear()) cleared else writeFailed) }
                    })
                })
            }
        }
    }
}

@Composable
private fun CalendarCard(model: PresenceRecordModel, locale: Locale) {
    val c = UmbraTheme.colors
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(CardShape).background(c.card).border(1.dp, c.border, CardShape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val cal = model.calendar
        val g = model.grid
        val err = model.calendarError
        when {
            cal != null && g != null -> {
                val summary = model.year?.let { stringResource(R.string.presence_summaryYear, it, cal.total_days, cal.total_actions) }
                    ?: stringResource(R.string.presence_summary, cal.total_days, cal.total_actions)
                Text(summary, color = c.text, style = TextStyle(fontSize = 14.sp, fontWeight = UmbraType.weight560))
                if (!cal.learn) Text(stringResource(R.string.presence_stopped), color = c.warningText, style = TextStyle(fontSize = 12.sp))
                // 方格对读屏是一整块：念那句汇总；一天一天念 371 格没有意义。
                Box(Modifier.clearAndSetSemantics { contentDescription = summary }) { GridView(model, g, locale) }
                DetailLine(model, locale)
                Legend()
            }
            err != null -> {
                Text(err.ifEmpty { stringResource(R.string.presence_loadFailed) }, color = c.dangerText, style = TextStyle(fontSize = 13.sp))
                Box(Modifier.heightIn(min = UmbraDimens.tapMin).clickable(role = Role.Button) { AppGraph.scope.launch { model.loadCalendar() } }, contentAlignment = Alignment.CenterStart) {
                    Text(stringResource(R.string.presence_retry), color = c.orangeText, style = TextStyle(fontSize = 14.sp, fontWeight = UmbraType.weight560))
                }
            }
            else -> Text(stringResource(R.string.presence_loading), color = c.muted, style = TextStyle(fontSize = 13.sp))
        }
    }
}

@Composable
private fun GridView(model: PresenceRecordModel, g: PresenceKit.Grid, locale: Locale) {
    val c = UmbraTheme.colors
    val zh = locale.language == "zh"
    // 2026-10-12 是周一：加 row 天就是那一行的星期。中文「一 / 三 / 五」，英文 Mon / Wed / Fri。
    val weekdayFmt = remember(locale) { DateTimeFormatter.ofPattern(if (zh) "EEEEE" else "EEE", locale) }
    val monthFmt = remember(locale) { fmt(locale, "MMM") }
    val scroll = rememberScrollState()
    // 默认滚到最右（今天那一列）；换年份 / 重读之后表换了，再滚一次。
    LaunchedEffect(g, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        // 左侧「一 / 三 / 五」：固定不随方格滚（滚到最右时仍看得出是哪一行）。
        Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
            Spacer(Modifier.height(14.dp))
            for (row in 0 until 7) {
                Box(Modifier.height(CELL), contentAlignment = Alignment.CenterStart) {
                    if (row in PresenceKit.weekdayLabelRows) {
                        Text(LocalDate.of(2026, 10, 12).plusDays(row.toLong()).format(weekdayFmt), color = c.faint, style = TextStyle(fontSize = 9.5.sp, lineHeight = 11.sp))
                    }
                }
            }
        }
        Column(Modifier.horizontalScroll(scroll).padding(end = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Box(Modifier.width((CELL + GAP) * g.columns.size).height(14.dp)) {
                g.months.forEach { m ->
                    Text(LocalDate.of(2026, m.month, 1).format(monthFmt), color = c.faint, style = TextStyle(fontSize = 9.5.sp),
                        maxLines = 1, softWrap = false, modifier = Modifier.offset(x = (CELL + GAP) * m.column))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                g.columns.forEach { cells ->
                    Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
                        cells.forEach { cell -> CellView(model, cell) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CellView(model: PresenceRecordModel, cell: PresenceKit.Cell) {
    if (!cell.inRange) { Spacer(Modifier.size(CELL)); return }
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(2.5.dp)
    val sel = model.selected?.day == cell.day
    Box(
        Modifier.size(CELL).clip(shape).background(UmbraPresenceHeat.color(cell.level))
            .border(1.2.dp, if (sel) c.text else Color.Transparent, shape)
            // 点一格出那天的一句（手机上没有悬停；长按也落在同一处 —— 点就够了，不用等）。
            .clickable { model.selected = if (sel) null else cell },
    )
}

/** 点中那一格的一句：「10月16日 · 活跃约 2 小时 15 分 · 操作 42 次」；没点时是提示。 */
@Composable
private fun DetailLine(model: PresenceRecordModel, locale: Locale) {
    val c = UmbraTheme.colors
    val cell = model.selected
    if (cell == null) {
        Text(stringResource(R.string.presence_tapHint), color = c.muted, style = TextStyle(fontSize = 12.5.sp))
        return
    }
    val date = PresenceKit.date(cell.day)?.format(remember(locale) { fmt(locale, "MMMd") }) ?: cell.day
    val text = if (cell.level <= 0) stringResource(R.string.presence_dayNone, date) else {
        val (key, args) = PresenceKit.duration(cell.activeMin)
        val dur = when (key) {
            "presence.dur.m" -> stringResource(R.string.presence_dur_m, args[0])
            "presence.dur.h" -> stringResource(R.string.presence_dur_h, args[0])
            "presence.dur.hm" -> stringResource(R.string.presence_dur_hm, args[0], args[1])
            else -> stringResource(R.string.presence_dur_none)
        }
        stringResource(R.string.presence_dayDetail, date, dur, cell.actions)
    }
    Text(text, color = c.text, style = TextStyle(fontSize = 12.5.sp))
}

@Composable
private fun Legend() {
    val c = UmbraTheme.colors
    Row(Modifier.fillMaxWidth().clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.presence_less), color = c.faint, style = TextStyle(fontSize = 10.5.sp))
        for (lv in 0..4) Box(Modifier.size(CELL).clip(RoundedCornerShape(2.5.dp)).background(UmbraPresenceHeat.color(lv)))
        Text(stringResource(R.string.presence_more_legend), color = c.faint, style = TextStyle(fontSize = 10.5.sp))
    }
}

@Composable
private fun ActivityCard(model: PresenceRecordModel, locale: Locale) {
    val c = UmbraTheme.colors
    val monthTitleFmt = remember(locale) { fmt(locale, "yyyyMMMM") }
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(CardShape).background(c.card).border(1.dp, c.border, CardShape).padding(bottom = 6.dp)) {
        Text(stringResource(R.string.presence_activity), color = c.text, style = TextStyle(fontSize = 14.sp, fontWeight = UmbraType.weight560),
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp))
        model.months.forEach { m ->
            val title = PresenceKit.yearMonth(m.month)?.let { (y, mo) -> LocalDate.of(y, mo, 1).format(monthTitleFmt) } ?: m.month
            Text(title, color = c.muted, style = TextStyle(fontSize = 12.sp, fontWeight = UmbraType.weight560), modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 2.dp))
            if (m.items.isEmpty()) {
                Box(Modifier.padding(horizontal = 14.dp).heightIn(min = 40.dp), contentAlignment = Alignment.CenterStart) {
                    Text(stringResource(R.string.presence_monthEmpty), color = c.faint, style = TextStyle(fontSize = 14.sp))
                }
            }
            m.items.forEach { it ->
                Row(Modifier.padding(horizontal = 14.dp).heightIn(min = 40.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(catRes(PresenceKit.categoryKey(it.category)), it.count), color = c.text, style = TextStyle(fontSize = 15.sp), modifier = Modifier.weight(1f))
                    if (it.days > 0) Text(stringResource(R.string.presence_days, it.days), color = c.muted, style = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace))
                }
            }
        }
        if (model.monthError) {
            Text(stringResource(R.string.presence_loadFailed), color = c.dangerText, style = TextStyle(fontSize = 12.5.sp), modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
        }
        if (model.loadingMonth) {
            Text(stringResource(R.string.presence_loading), color = c.muted, style = TextStyle(fontSize = 12.5.sp), modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
        } else if (model.months.lastOrNull()?.prev != null || model.monthError) {
            Box(
                Modifier.fillMaxWidth().heightIn(min = UmbraDimens.tapMin).clickable(role = Role.Button) {
                    AppGraph.scope.launch { if (model.months.isEmpty()) model.loadFirstMonth() else model.loadMore() }
                },
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(if (model.monthError) R.string.presence_retry else R.string.presence_showMore), color = c.orangeText,
                    style = TextStyle(fontSize = 14.5.sp, fontWeight = UmbraType.weight560))
            }
        }
    }
}

/**
 * 你的作息（执勤 M1a 契约 §16.5）：两条 24 小时浓淡条 + 一句「通常 8:40–23:10 在线」；ready = false 时只写
 * 「再用几天就能看出来」。颜色与方格同一套 0–4 档：同一张页上「深 = 在得多」只有一种说法。
 */
@Composable
private fun PresenceProfileCard(profile: PresenceProfileDTO) {
    val c = UmbraTheme.colors
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(CardShape).background(c.card).border(1.dp, c.border, CardShape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.presence_profile_title), color = c.text, style = TextStyle(fontSize = 14.sp, fontWeight = UmbraType.weight560))
        if (profile.ready) {
            Strip(stringResource(R.string.presence_profile_workday), profile.workday)
            Strip(stringResource(R.string.presence_profile_restday), profile.restday)
            Row(Modifier.fillMaxWidth().clearAndSetSemantics { }) {
                for (h in listOf(0, 6, 12, 18)) Text("$h", color = c.faint, style = TextStyle(fontSize = 9.5.sp, fontFamily = FontFamily.Monospace), modifier = Modifier.weight(1f))
                Text("24", color = c.faint, style = TextStyle(fontSize = 9.5.sp, fontFamily = FontFamily.Monospace))
            }
            if (profile.unusual) Text(stringResource(R.string.presence_profile_unusual), color = c.warningText, style = TextStyle(fontSize = 12.sp))
        } else {
            Text(stringResource(R.string.presence_profile_notReady, profile.days_used), color = c.muted, style = TextStyle(fontSize = 13.sp))
        }
    }
}

@Composable
private fun Strip(label: String, part: PresenceProfileDTO.Part) {
    val c = UmbraTheme.colors
    val (key, args) = PresenceKit.profileLine(part)
    val text = if (key == "presence.profile.range") stringResource(R.string.presence_profile_range, args[0], args[1])
    else stringResource(R.string.presence_profile_unknown)
    Column(Modifier.clearAndSetSemantics { contentDescription = "$label $text" }, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("$label · $text", color = c.muted, style = TextStyle(fontSize = 12.5.sp))
        // 48 格平分一行宽：一格 = 半小时。格子之间不留缝，浓淡连成一条（它是一天的形状，不是 48 件事）。
        Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(3.dp))) {
            PresenceKit.normalizedSlots(part.slots).forEach { v ->
                Box(Modifier.weight(1f).height(12.dp).background(UmbraPresenceHeat.color(PresenceKit.profileLevel(v))))
            }
        }
    }
}
