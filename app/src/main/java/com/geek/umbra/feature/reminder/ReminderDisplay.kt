// 提醒模型的界面侧派生（分组、时间文案、选项显示名、结束日校验、「再来一条」）。
// 对应 iOS ReminderViews.swift 开头的 `extension UmbraReminder`。跟本机日历走，所以不放进纯判据。
package com.geek.umbra.feature.reminder

import androidx.appcompat.app.AppCompatDelegate
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraDateText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** 界面是不是英文（App 内切语言走 AppCompat）。iOS `LanguageManager.effectiveLanguage == .english`。 */
internal val reminderEnglish: Boolean
    get() = AppCompatDelegate.getApplicationLocales().toLanguageTags().startsWith("en")

private fun str(id: Int, vararg args: Any): String = AppGraph.context.getString(id, *args)

private val zone: ZoneId get() = ZoneId.systemDefault()
private fun dateOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

/** 判据在 aheadText：不在菜单五档里的分钟数照实说。 */
val Reminder.aheadLabel: String get() = Reminder.aheadText(Reminder.aheadClamped(aheadMinutes))

/** 提前通知正文里的时长：中文 aheadLabel，英文 aheadTextEn（「5 min / 1 day」）。 */
val Reminder.aheadNotifText: String
    get() {
        val m = Reminder.aheadClamped(aheadMinutes)
        return if (reminderEnglish) Reminder.aheadTextEn(m) else Reminder.aheadText(m)
    }

/** 「每 3 天」/「每周」/「不重复」—— 与 PC repeatLabel 逐字同一套，按提醒时区判星期几。 */
val Reminder.repeatLabel: String
    get() = ReminderAdvance.repeatText(repeatRule, customFreq, customN, weekDays, atMs, ReminderAdvance.zone(tz), reminderEnglish)

/** 「重复」那一行右边的值：「不重复」时补回选项名。 */
val Reminder.repeatRowValue: String get() = if (repeatRule == "不重复") ReminderOptions.repeatOptionText(repeatRule) else repeatLabel

/** 「提前提醒」那一行右边的值。 */
val Reminder.aheadRowValue: String get() = ReminderOptions.aheadOptionText(Reminder.aheadClamped(aheadMinutes))

/** 选项的显示名（存储值仍是中文那一份，这里只管屏上念什么）。iOS ReminderViews.swift:77-140 */
object ReminderOptions {
    fun repeatOptionText(rule: String): String = when (rule) {
        "不重复" -> str(R.string.reminder_rule_none)
        "每天" -> str(R.string.reminder_rule_daily)
        "每周" -> str(R.string.reminder_rule_weekly)
        "每月" -> str(R.string.reminder_rule_monthly)
        "工作日" -> str(R.string.reminder_rule_weekdays)
        "自定义" -> str(R.string.reminder_rule_custom)
        else -> rule
    }

    fun endOptionText(v: String): String = when (v) {
        "永不" -> str(R.string.reminder_ends_never)
        "指定日期" -> str(R.string.reminder_ends_onDate)
        else -> v
    }

    fun aheadOptionText(minutes: Int): String = when (minutes) {
        0 -> str(R.string.reminder_ahead_none)
        5 -> str(R.string.reminder_ahead_5m)
        15 -> str(R.string.reminder_ahead_15m)
        60 -> str(R.string.reminder_ahead_1h)
        1440 -> str(R.string.reminder_ahead_1d)
        else -> when {
            !reminderEnglish -> Reminder.aheadText(minutes)
            minutes <= 0 -> str(R.string.reminder_ahead_none)
            else -> Reminder.aheadTextEn(minutes) + " before"
        }
    }

    /** 自定义频率单位的显示名：英文按 N 取单复数，中文原样。 */
    fun customFreqText(freq: String, n: Long): String {
        if (!reminderEnglish) return freq
        val names = mapOf("小时" to ("hour" to "hours"), "天" to ("day" to "days"), "周" to ("week" to "weeks"), "月" to ("month" to "months"), "年" to ("year" to "years"))
        val (one, many) = names[freq] ?: (freq to freq)
        return if (maxOf(1L, n) == 1L) one else many
    }

    /** 结束日期面板底部那句：日期跟界面语言格式化，英文相对词嵌进句子时小写。 */
    fun endMinNote(minDayMs: Long): String {
        val day = dateOf(minDayMs)
        val text = UmbraDateText.dayText(day)
        val today = LocalDate.now(zone)
        val relative = day == today || day == today.plusDays(1)
        return str(R.string.reminder_endPicker_minNote, if (reminderEnglish && relative) text.lowercase() else text)
    }
}

/**
 * 选「指定日期」那一刻给的默认结束日 —— 跨端统一规则（PC reminderKit.defaultRepeatEnd，两端逐字一致）：
 * 每天/工作日 +1 月；每周 +3 月；每月 +1 年；自定义 小时 +7 天、天 +min(n,6) 月、周 +3×min(n,4) 月、
 * 月 +min(n,3) 年、年 +5×min(n,2) 年。起点取 at 与现在里更晚的；落点本机那天 23:59:59.999。iOS: ReminderViews.swift:155
 */
val Reminder.defaultRepeatEnd: Long
    get() {
        val base = ZonedDateTime.ofInstant(Instant.ofEpochMilli(maxOf(atMs, System.currentTimeMillis())), zone)
        val n = maxOf(1L, customN)
        val end = when (repeatRule) {
            "每周" -> base.plusMonths(3)
            "每月" -> base.plusYears(1)
            "自定义" -> when (customFreq) {
                "小时" -> base.plusDays(7)
                "周" -> base.plusMonths(3 * minOf(n, 4))
                "月" -> base.plusYears(minOf(n, 3))
                "年" -> base.plusYears(5 * minOf(n, 2))
                else -> base.plusMonths(minOf(n, 6))
            }
            else -> base.plusMonths(1)
        }
        return ReminderAdvance.endOfDay(end.toInstant().toEpochMilli())
    }

/** 地点提醒没有时刻（at_ms = 0），永远不算过期。 */
val Reminder.overdue: Boolean get() = !done && ReminderPlace.timed(this) && atMs < System.currentTimeMillis()

/** 「再等 10 分钟 / 稍后提醒」只对今天（含已过期）的有意义：明天的推到「现在 + 10 分钟」等于提前。已完成的不给。 */
val Reminder.canSnoozeNow: Boolean
    get() {
        if (done || !ReminderPlace.timed(this)) return false // 地点提醒没有时刻，推 10 分钟无从谈起
        val endOfToday = LocalDate.now(zone).atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()
        return atMs <= endOfToday
    }

/** 结束重复的校验（与 PC repeatEndError 同）：早于提醒当天 / 早于今天都不许存。null = 合法。 */
val Reminder.repeatEndProblem: String?
    get() {
        val end = repeatEndMs
        if (repeatRule == "不重复" || end == null || !ReminderPlace.timed(this)) return null
        if (dateOf(end) < dateOf(atMs)) return str(R.string.reminder_endProblem_beforeAt)
        if (dateOf(end) < LocalDate.now(zone)) return str(R.string.reminder_endProblem_past)
        return null
    }

/** 「再来一条」：内容、备注、重复、提前量、周几照抄；时间回一小时后；结束日重给默认；附件不抄（谁引用谁清）。 */
fun Reminder.cloneAsNew(): Reminder {
    var c = Reminder(
        id = UUID.randomUUID().toString().uppercase(), text = text, atMs = System.currentTimeMillis() + 3_600_000,
        repeatRule = repeatRule, customFreq = customFreq, customN = customN, aheadMinutes = aheadMinutes, note = note,
        weekDays = Reminder.normWeekDays(weekDays),
    )
    if (repeatRule != "不重复" && repeatEndMs != null) c = c.copy(repeatEndMs = c.defaultRepeatEnd)
    return c
}

/** 这一套重复到头了吗（稿 reminderList.repeatEnded，判定式取「甲」：所有过期条目都算）。 */
val Reminder.repeatEnded: Boolean
    get() = repeatRule != "不重复" && repeatEndMs != null && System.currentTimeMillis() > repeatEndMs

/** 列表分组。过期在最前；地点提醒没有日期，单列一组放最后。 */
val Reminder.group: String
    get() {
        if (done) return if (repeatEnded) "重复已结束" else "已完成"
        if (!ReminderPlace.timed(this)) return "地点提醒"
        val now = System.currentTimeMillis()
        if (atMs < now) return "已过期"
        val d = dateOf(atMs); val today = LocalDate.now(zone)
        if (d == today) return "今天"
        if (d == today.plusDays(1)) return "明天"
        if (atMs <= ZonedDateTime.now(zone).plusDays(7).toInstant().toEpochMilli()) return "本周"
        return "更远"
    }

/** 「今天 09:30」/「明天 09:30」/「昨天 09:30」/「7月31日 09:30」/「2027年7月31日 09:30」。 */
val Reminder.timeLabel: String
    get() {
        place?.let { return ReminderPlace.label(it) } // 地点提醒：「到达 公司 时 · 在 iPhone 上提醒」
        val z = Instant.ofEpochMilli(atMs).atZone(zone)
        val t = z.format(DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA))
        val d = z.toLocalDate(); val today = LocalDate.now(zone)
        if (d == today) return "今天 $t"
        if (d == today.plusDays(1)) return "明天 $t"
        if (d == today.minusDays(1)) return "昨天 $t"
        val p = if (d.year == today.year) "M月d日 HH:mm" else "yyyy年M月d日 HH:mm"
        return z.format(DateTimeFormatter.ofPattern(p, Locale.CHINA))
    }

val Reminder.createdLabel: String
    get() = Instant.ofEpochMilli(createdAtMs).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm", Locale.CHINA))

/** 重复徽章的「· 到 8月30日」后缀。 */
val Reminder.repeatEndSuffix: String
    get() {
        val end = repeatEndMs
        if (repeatRule == "不重复" || end == null) return ""
        val z = Instant.ofEpochMilli(end).atZone(zone)
        val p = if (z.year == LocalDate.now(zone).year) "M月d日" else "yyyy年M月d日"
        return " · 到 ${z.format(DateTimeFormatter.ofPattern(p, Locale.CHINA))}"
    }

/** 提醒编辑页保存后的两句吐司（编辑与新建逐字同源）。 */
object ReminderSaveToast {
    fun partial(n: Int): String {
        if (!reminderEnglish) return "提醒存好了，还有 $n 张图没传上"
        return if (n == 1) "Reminder saved. 1 image didn't upload." else "Reminder saved. $n images didn't upload."
    }
    val saved: String get() = if (reminderEnglish) "Saved" else "已保存"
}
