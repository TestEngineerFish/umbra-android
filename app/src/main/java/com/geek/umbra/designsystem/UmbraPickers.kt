// 日期 / 时间选择面板 —— 对应 iOS DesignSystem/UmbraPickers.swift（批次 007 答复稿，tokens.dateTimePicker.ios）。
//
// 规则照搬 iOS 文件头：
// - 禁止系统原生 date/time picker 的替代品，统一从**字段整行**弹出；
// - **日期 = 月历网格**：恒 6 行 42 格、周一开头、切月不跳高；格高 44、圆角 12、16 号等宽数字；
//   选中 orange 实底白字，今天 1px border 描边 + 650 字重、不用橙（一屏两个橙分不清哪个是你选的）；邻月 faint 可点，点了翻月；
// - **时间 = 时 / 分滚轮**：选中行 orangeText 600、未选中 faint，orangeSoft 选中带垫在滚轮后面；
// - **一个值 = 一个字段**：「什么时候」是一个瞬间 → when 面板（两段切换，一次「确定」落两段，中途关掉不留半个值）；
//   只选日期的 date 面板点选即落值（底条只留全宽「取消」）；只选时间的 time 面板「确定」落值；
// - 结束日期复用 date 面板 + 下界：早于 min 的日子不可点（faint + 34% 透明），下方一行 faint 说明为什么；
//   「进来时值就已经不对」才转红字。
//
// 值一律用 epoch 毫秒 + 本机时区（iOS 是 Date + Calendar.current）。入口是 [UmbraPickers]，经 Router.present 弹底部面板 ——
// 其他模块（记账、周期规则…）直接复用，不必再写一份。
package com.geek.umbra.designsystem

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.navigation.UmbraSheet
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** 面板变体：只选日期（点选即落）/ 只选时间（确定落）/ 日期 + 时间（两段一次确定）。 */
enum class UmbraPickerKind { Date, Time, When }

/** when 面板的两段：点日期行开在日期段、点时间行开在时间段。 */
enum class UmbraWhenTab { Date, Time }

/** 面板文案的共用处（「今天 / 明天 / 8月5日 周三」）—— 提醒表单行、结束日期说明句、面板自己共用一份。 */
object UmbraDateText {
    /** 面板现在是不是英文界面（整个 App 共用这一张面板，所以字面在这里一次换掉）。 */
    val english: Boolean get() = AppCompatDelegate.getApplicationLocales().toLanguageTags().startsWith("en")

    private val zone: ZoneId get() = ZoneId.systemDefault()

    /**
     * 「今天」/「明天」/「8月5日 周三」（跨年带年份）；英文 Today / Tomorrow / Wed, Aug 5（跨年 Wed, Aug 5, 2027）。
     * 相对词只给最近两天。英文 locale 写死 en_US：跟系统区域走的话，区域设成中国的英文界面会念出「八月」。iOS: UmbraPickers.swift:476
     */
    fun dayText(day: LocalDate): String {
        val today = LocalDate.now(zone)
        if (day == today) return AppGraph.context.getString(R.string.picker_today)
        if (day == today.plusDays(1)) return AppGraph.context.getString(R.string.picker_tomorrow)
        val sameYear = day.year == today.year
        return if (english) day.format(DateTimeFormatter.ofPattern(if (sameYear) "EEE, MMM d" else "EEE, MMM d, yyyy", Locale.US))
        else day.format(DateTimeFormatter.ofPattern(if (sameYear) "M月d日 EEE" else "yyyy年M月d日 EEE", Locale.CHINA))
    }

    fun dayText(ms: Long): String = dayText(Instant.ofEpochMilli(ms).atZone(zone).toLocalDate())

    /** 按天数偏移取（提醒表单行上的显示口径）。 */
    fun dayLabel(offset: Long): String = dayText(LocalDate.now(zone).plusDays(offset))

    /** 某时刻离今天几天（按本机日历）。 */
    fun dayOffset(ms: Long): Long = ChronoUnit.DAYS.between(LocalDate.now(zone), Instant.ofEpochMilli(ms).atZone(zone).toLocalDate())

    fun hm(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
}

/** 弹面板的入口：经 Router.present(UmbraSheet)。三个入口对应三种变体，其他模块直接复用。 */
object UmbraPickers {
    /** 「什么时候」（日期 + 时间，一个瞬间一个值）。[tab] 决定面板开在哪一段。 */
    fun presentWhen(field: String, initialMs: Long, tab: UmbraWhenTab, onPick: (Long) -> Unit) =
        present(UmbraPickerKind.When, field, initialMs, null, null, tab, onPick)

    /** 只选日期（点选即落值）。结束日期这类传 [minDayMs] / [minNote]：早于下界的日子不可点。 */
    fun presentDate(field: String, initialMs: Long, minDayMs: Long? = null, minNote: String? = null, onPick: (Long) -> Unit) =
        present(UmbraPickerKind.Date, field, initialMs, minDayMs, minNote, UmbraWhenTab.Date, onPick)

    /** 只选时间（时 / 分滚轮 + 确定）；只换时分，年月日保持传进来的。 */
    fun presentTime(field: String, initialMs: Long, onPick: (Long) -> Unit) =
        present(UmbraPickerKind.Time, field, initialMs, null, null, UmbraWhenTab.Time, onPick)

    private fun present(kind: UmbraPickerKind, field: String, initialMs: Long, minDayMs: Long?, minNote: String?, tab: UmbraWhenTab, onPick: (Long) -> Unit) {
        AppGraph.router.present(UmbraSheet("picker-${System.nanoTime()}") { dismiss ->
            UmbraDateTimePanel(kind, field, initialMs, minDayMs, minNote, tab, onCancel = dismiss, onConfirm = { dismiss(); onPick(it) })
        })
    }
}

/**
 * 面板本体。初值的年月日 / 时分各自拆开，确定时只合并回自己那一部分（date 只换年月日、time 只换时分）。
 * 秒一律归零：面板落值都是分钟精度。iOS: UmbraPickers.swift:31
 */
@Composable
fun UmbraDateTimePanel(
    kind: UmbraPickerKind,
    field: String,
    initialMs: Long,
    minDayMs: Long? = null,
    minNote: String? = null,
    initialTab: UmbraWhenTab = UmbraWhenTab.Date,
    onCancel: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val c = UmbraTheme.colors
    val zone = remember { ZoneId.systemDefault() }
    val initial = remember(initialMs) { Instant.ofEpochMilli(initialMs).atZone(zone).toLocalDateTime() }
    var selDay by remember { mutableStateOf(initial.toLocalDate()) }
    var hour by remember { mutableIntStateOf(initial.hour) }
    var minute by remember { mutableIntStateOf(initial.minute) }
    var shownMonth by remember { mutableStateOf(initial.toLocalDate().withDayOfMonth(1)) }
    var tab by remember { mutableStateOf(initialTab) }
    val minDay = minDayMs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
    val english = UmbraDateText.english

    fun ms(dt: LocalDateTime) = dt.atZone(zone).toInstant().toEpochMilli()
    fun compose(day: LocalDate) = ms(day.atTime(initial.hour, initial.minute))
    fun composeTime(h: Int, m: Int) = ms(initial.toLocalDate().atTime(h, m))
    fun composeWhen() = ms(selDay.atTime(hour, minute))
    val hm = "%02d:%02d".format(hour, minute)

    Column(
        Modifier.fillMaxWidth().padding(start = UmbraDimens.pagePadX, end = UmbraDimens.pagePadX, top = 14.dp, bottom = UmbraDimens.sp3),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 顶条：待落值 + 字段名 + 「今天 / 现在」快捷（一步落完整值、直接关掉）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val pending = when (kind) {
                    UmbraPickerKind.Date -> UmbraDateText.dayText(selDay)
                    UmbraPickerKind.Time -> hm
                    UmbraPickerKind.When -> "${UmbraDateText.dayText(selDay)} $hm"
                }
                Text(pending, color = c.text, maxLines = 1, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"))
                Spacer(Modifier.height(2.dp))
                Text(field, color = c.faint, style = TextStyle(fontSize = 11.5.sp))
            }
            val today = LocalDate.now(zone)
            val quick: String? = when {
                kind == UmbraPickerKind.Date && minDay != null && today < minDay -> null // 今天早于下界：点了也是非法值
                kind == UmbraPickerKind.Date -> AppGraph.context.getString(R.string.picker_today)
                else -> if (english) "Now" else "现在"
            }
            if (quick != null) {
                Box(
                    Modifier.heightIn(min = UmbraDimens.tapMin).clickable(role = Role.Button) {
                        val now = LocalDateTime.now(zone).truncatedTo(ChronoUnit.MINUTES)
                        onConfirm(
                            when (kind) {
                                UmbraPickerKind.Date -> compose(now.toLocalDate())
                                UmbraPickerKind.Time -> composeTime(now.hour, now.minute)
                                UmbraPickerKind.When -> ms(now)
                            },
                        )
                    }.padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(quick, color = c.orangeText, style = TextStyle(fontSize = 15.sp, fontWeight = UmbraType.weight560)) }
            }
        }

        if (kind == UmbraPickerKind.When) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.chip).padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                listOf(UmbraWhenTab.Date to UmbraDateText.dayText(selDay), UmbraWhenTab.Time to hm).forEach { (t, label) ->
                    val on = tab == t
                    // 段的视觉 34、热区撑到 44（设计侧 171 §五）
                    Box(
                        Modifier.weight(1f).heightIn(min = 34.dp).clip(RoundedCornerShape(9.dp)).background(if (on) c.card else Color.Transparent)
                            .clickable(role = Role.Tab) { tab = t },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, maxLines = 1, color = if (on) c.text else c.muted,
                            style = TextStyle(fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, fontFeatureSettings = "tnum"))
                    }
                }
            }
        }

        if (kind == UmbraPickerKind.Date || (kind == UmbraPickerKind.When && tab == UmbraWhenTab.Date)) {
            CalendarGrid(shownMonth, selDay, minDay, english, onMonth = { shownMonth = it }) { day ->
                selDay = day
                if (day.withDayOfMonth(1) != shownMonth) shownMonth = day.withDayOfMonth(1) // 邻月的点了跟着翻月
                if (kind == UmbraPickerKind.Date) onConfirm(compose(day)) // date 变体点一下即落值
            }
        }
        if (kind == UmbraPickerKind.Time || (kind == UmbraPickerKind.When && tab == UmbraWhenTab.Time)) {
            TimeWheels(hour, minute, english, { hour = it }, { minute = it })
        }

        // 下界说明 / 非法初值：常态给 minNote（faint）；「进来时值就已经不对」才转红字。
        val badIncoming = minDay != null && selDay < minDay
        val hint: String? = when {
            kind == UmbraPickerKind.Time -> null
            badIncoming -> if (english) "This date is before ${UmbraDateText.dayText(minDay)}."
            else "现在这个日子早于 ${UmbraDateText.dayText(minDay)}，这条一次都不会执行。往后挑一天，或者先改前面的日期。"
            else -> minNote
        }
        if (hint != null) {
            Text(hint, color = if (badIncoming) c.dangerText else c.faint, style = TextStyle(fontSize = 12.sp, lineHeight = 19.sp), modifier = Modifier.fillMaxWidth())
        }

        // 底条：date 只留全宽「取消」；time / when 要点「确定」。
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.weight(1f).height(UmbraDimens.buttonH).clip(RoundedCornerShape(50)).background(c.chip).clickable(role = Role.Button, onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) { Text(AppGraph.context.getString(R.string.common_cancel), color = c.text, style = TextStyle(fontSize = 16.sp, fontWeight = UmbraType.weight560)) }
            if (kind != UmbraPickerKind.Date) {
                Box(
                    Modifier.weight(1f).height(UmbraDimens.buttonH).clip(RoundedCornerShape(50)).background(c.orange)
                        .clickable(role = Role.Button) { onConfirm(if (kind == UmbraPickerKind.Time) composeTime(hour, minute) else composeWhen()) },
                    contentAlignment = Alignment.Center,
                ) { Text(AppGraph.context.getString(R.string.picker_confirm), color = Color.White, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)) }
            }
        }
    }
}

/** 恒 6 行 42 格的月历（周一开头）。 */
@Composable
private fun CalendarGrid(
    shownMonth: LocalDate, selDay: LocalDate, minDay: LocalDate?, english: Boolean,
    onMonth: (LocalDate) -> Unit, onPick: (LocalDate) -> Unit,
) {
    val c = UmbraTheme.colors
    val today = LocalDate.now()
    val title = if (english) shownMonth.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.US))
    else "${shownMonth.year}年${shownMonth.monthValue}月"
    val heads = if (english) listOf("M", "T", "W", "T", "F", "S", "S") else listOf("一", "二", "三", "四", "五", "六", "日")
    val firstDow = shownMonth.dayOfWeek.value - 1 // 周一 = 0
    val gridStart = shownMonth.minusDays(firstDow.toLong())
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UmbraTopBarIcon(UmbraIconPaths.chevronLeft, if (english) "Previous month" else "上一个月", { onMonth(shownMonth.minusMonths(1)) }, tint = c.muted)
            Text(title, Modifier.weight(1f), color = c.text, textAlign = TextAlign.Center, style = TextStyle(fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"))
            UmbraTopBarIcon(UmbraIconPaths.chevronRight, if (english) "Next month" else "下一个月", { onMonth(shownMonth.plusMonths(1)) }, tint = c.muted)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            heads.forEach { h -> Text(h, Modifier.weight(1f).height(18.dp), color = c.faint, textAlign = TextAlign.Center, style = TextStyle(fontSize = 11.5.sp)) }
        }
        for (row in 0 until 6) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (col in 0 until 7) {
                    val day = gridStart.plusDays((row * 7 + col).toLong())
                    val on = day == selDay
                    val isToday = day == today
                    val other = day.month != shownMonth.month
                    val disabled = minDay != null && day < minDay
                    val shape = RoundedCornerShape(12.dp)
                    Box(
                        Modifier.weight(1f).height(44.dp).clip(shape).background(if (on) c.orange else Color.Transparent)
                            .border(UmbraDimens.borderW, if (isToday && !on) c.border else Color.Transparent, shape)
                            .clickable(enabled = !disabled, role = Role.Button) { onPick(day) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${day.dayOfMonth}",
                            Modifier.alpha(if (disabled) 0.34f else 1f),
                            color = when { disabled -> c.faint; on -> Color.White; other -> c.faint; else -> c.text },
                            style = TextStyle(
                                fontSize = 16.sp, fontFeatureSettings = "tnum",
                                fontWeight = when { on -> FontWeight.SemiBold; isToday -> UmbraType.weight650; else -> FontWeight.Normal },
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** 时 / 分两列滚轮（惯性 + 吸附）。中文每格带单位「时 / 分」，英文只写两位数、单位交给读屏。 */
@Composable
private fun TimeWheels(hour: Int, minute: Int, english: Boolean, onHour: (Int) -> Unit, onMinute: (Int) -> Unit) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier.fillMaxWidth().height(200.dp).clip(shape).background(c.card).border(UmbraDimens.borderW, c.border, shape),
        contentAlignment = Alignment.Center,
    ) {
        // 橙软选中带垫在滚轮后面（与 iOS 同：选中行从带上透出来）
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(40.dp).clip(RoundedCornerShape(UmbraDimens.radiusControl)).background(c.orangeSoft))
        Row(Modifier.fillMaxWidth()) {
            Wheel(24, hour, Modifier.weight(1f), { h -> if (english) "%02d".format(h) else "%02d 时".format(h) },
                { h -> if (english) "$h ${if (h == 1) "hour" else "hours"}" else "%02d 时".format(h) }, onHour)
            Wheel(60, minute, Modifier.weight(1f), { m -> if (english) "%02d".format(m) else "%02d 分".format(m) },
                { m -> if (english) "$m ${if (m == 1) "minute" else "minutes"}" else "%02d 分".format(m) }, onMinute)
        }
    }
}

/** 一列滚轮：上下各垫两格空白，中间那格是选中。滚停时把居中的那格回报出去。 */
@Composable
private fun Wheel(count: Int, value: Int, modifier: Modifier, label: (Int) -> String, voice: (Int) -> String, onChange: (Int) -> Unit) {
    val c = UmbraTheme.colors
    val itemH = 40.dp
    val state = rememberLazyListState(initialFirstVisibleItemIndex = value)
    val pxH = with(LocalDensity.current) { itemH.toPx() }
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex + if (state.firstVisibleItemScrollOffset > pxH / 2) 1 else 0 }
            .collect { idx -> onChange(idx.coerceIn(0, count - 1)) }
    }
    LazyColumn(modifier.height(itemH * 5), state = state, flingBehavior = rememberSnapFlingBehavior(state)) {
        items(2) { Spacer(Modifier.height(itemH)) }
        items(count) { i ->
            val on = i == value
            Box(Modifier.fillMaxWidth().height(itemH).semantics { contentDescription = voice(i) }, contentAlignment = Alignment.Center) {
                Text(label(i), color = if (on) c.orangeText else c.faint,
                    style = TextStyle(fontSize = 15.5.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, fontFeatureSettings = "tnum"))
            }
        }
        items(2) { Spacer(Modifier.height(itemH)) }
    }
}
