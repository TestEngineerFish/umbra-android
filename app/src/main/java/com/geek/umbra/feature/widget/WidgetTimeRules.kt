// 小组件的快照模型与「随时间重算」的判据 —— 照抄 iOS Utils/WidgetSnapshot.swift（umbra-ios#85）。
// 纯 Kotlin（不 import android.*），配 JUnit 用例 WidgetTimeRulesTest.kt（输入输出照抄 Tools/swifttests/WidgetSnapshotTests.swift）。
//
// ## 为什么要「随时间重算」（iOS 文件头原话的要点）
//
// 小组件恰恰是**不开 App 的日子里**被看的。原来「已过期 / 今天」在写快照那一刻就筛定了：
//   · 22:00 写的「交周报 23:00」第二天 09:00 仍画「23:00」、不标已过期；
//   · 今天 10:00 的提醒在昨晚写快照时属于「明天」被筛掉；
//   · 任务小组件的「今天完成」第二天仍是昨天的数。
// 现在存**绝对时刻**、放「已过期 + 未来 48 小时」，「已过期 / 今天」在每一帧按那一刻现算。
//
// Android 差异：小组件和主 App 同进程，提醒那一份不落快照文件，渲染时直接从 ReminderStore 现取、
// 经 [WidgetTimeRules.snapshotRows] → [WidgetTimeRules.visible] 两步算（与 iOS 同一套口径，用例同一份）。
// 任务那一份仍要落盘（任务没有本地持久化，进程被杀后小组件还得有东西画），见 WidgetBridge。
package com.geek.umbra.feature.widget

import com.geek.umbra.feature.task.TaskTime
import com.geek.umbra.model.TaskItem
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 任务快照：给小号小组件。执行中任务 + 今天完成数，都是服务端列表算出来的真实值（iOS: WidgetSnapshot.swift:19）。 */
@Serializable
data class TaskWidgetSnapshot(
    /** 执行中任务（没有则为 null —— 小组件转而显示今天完成数，不画假任务）。 */
    val runningId: String? = null,
    /** 执行中任务的**短标题**（TaskItem.title：有 name 用 name，没有才退回 goal）。整段需求描述小组件放不下。 */
    val runningTitle: String? = null,
    val stepsDone: Int = 0,
    val stepsTotal: Int = 0,
    /** 一句话状态行：「第 3/5 步」/「执行中」（与进行中通知同一句，同源同 tick）。 */
    val statusLine: String? = null,
    /** 写入那一天完成了几个。**只对 savedAt 那一天成立** —— 画的时候经 [WidgetTimeRules.todayDone] 判跨日。 */
    val todayDone: Int = 0,
    /** 写入时刻（毫秒）。超过 10 分钟没更新时小组件要显示「更新于」。 */
    val savedAtMs: Long = 0,
) {
    val percent: Double? get() = if (stepsTotal > 0) stepsDone.toDouble() / stepsTotal else null
}

/** 提醒快照的一行（iOS: UmbraReminderSnapshot.Row）。Android 只在内存里用，不落盘，所以没有旧快照兜底那一支。 */
data class ReminderWidgetRow(
    val id: String,
    val text: String,
    /** 写入那一刻的「18:00」。 */
    val time: String,
    /** 写入那一刻算的过期。 */
    val overdue: Boolean,
    /** 提醒存着的那个时刻（重复提醒没推进时可能已在过去）。null = 旧快照（iOS 才有，Android 不会出现）。 */
    val atMs: Long? = null,
    /** 重复提醒在 48 小时窗口里接下来的几次（都晚于 atMs）。不重复的是 null。 */
    val later: List<Long>? = null,
    /** 窗口之外还有下一次：窗口里的都过了时，这一行**不画**（推进会把它推到窗口外），而不是画成「已过期」。 */
    val continues: Boolean? = null,
)

object WidgetTimeRules {
    /** 往后看多远。两天：哪怕一整天不开 App，第二天的提醒也都在里面。 */
    const val HORIZON_MS = 48L * 3600 * 1000
    /** 最多放几行（小组件只画 4 行，多放的给「过了几个钟头、前面几条已勾 / 已过」时补位）。 */
    const val MAX_ROWS = 12
    /** 一条重复提醒最多带几次后续（「每小时」两天是 48 次）。 */
    const val MAX_LATER = 48

    /** 一条未完成的提醒。[upcoming] 是重复提醒从「推进会推到的那一次」起往后的时刻（取 MAX_LATER + 1 个）；不重复的给空。 */
    data class ReminderSource(val id: String, val text: String, val atMs: Long, val repeating: Boolean, val upcoming: List<Long>)

    /** 小组件这一帧画的一行。 */
    data class Shown(val id: String, val text: String, val time: String, val overdue: Boolean)

    /**
     * 这一行在 [nowMs] 这一刻落在哪个时刻；null = 这一刻不画（iOS: WidgetSnapshot.swift:93）。
     * 取「接下来第一个还没到的那一次」；全都过了：重复的窗口外还有下一次就不画，否则停在最后那一次，算已过期。
     */
    fun effectiveAt(r: ReminderWidgetRow, nowMs: Long): Long? {
        val at = r.atMs ?: return null
        val all = listOf(at) + (r.later ?: emptyList())
        all.firstOrNull { it >= nowMs }?.let { return it }
        return if (r.continues == true) null else all.last()
    }

    /** 未完成项 → 快照行：只留「已过期 + 未来 48 小时内有一次」的，按落点排，最多 MAX_ROWS 行。 */
    fun snapshotRows(items: List<ReminderSource>, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<ReminderWidgetRow> {
        val edge = nowMs + HORIZON_MS
        val out = mutableListOf<Pair<Long, ReminderWidgetRow>>()
        for (s in items) {
            var row = ReminderWidgetRow(s.id, s.text, "", false, atMs = s.atMs)
            if (s.repeating) {
                val inWindow = s.upcoming.filter { it <= edge }.take(MAX_LATER)
                row = row.copy(later = inWindow.filter { it > s.atMs }, continues = s.upcoming.size > inWindow.size)
            }
            val eff = effectiveAt(row, nowMs) ?: continue
            if (eff > edge) continue
            out += eff to row.copy(time = hhmm(eff, zone), overdue = eff < nowMs)
        }
        return out.sortedBy { it.first }.take(MAX_ROWS).map { it.second }
    }

    /** 这一帧：「已过期 + 今天」，按落点排。旧快照的行（没有 atMs）原样照画写入时的结果。 */
    fun visible(rows: List<ReminderWidgetRow>, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<Shown> {
        val tomorrow = startOfNextDay(nowMs, zone)
        val out = mutableListOf<Pair<Long, Shown>>()
        rows.forEachIndexed { i, r ->
            if (r.atMs == null) {
                // 旧快照：没有时刻可算，排在它原来的位置（用一个不会和真时刻冲突的序号）。
                out += i.toLong() * 1000 to Shown(r.id, r.text, r.time, r.overdue)
                return@forEachIndexed
            }
            val eff = effectiveAt(r, nowMs) ?: return@forEachIndexed
            if (eff >= tomorrow) return@forEachIndexed
            out += eff to Shown(r.id, r.text, hhmm(eff, zone), eff < nowMs)
        }
        return out.sortedBy { it.first }.map { it.second }
    }

    /**
     * 哪些时刻要换一帧：每一次到点（那一行从「今天」变「已过期」）、每一个零点（「今天」换了一天）。
     * 只看 now 之后、HORIZON 以内；去重、排好、最多 60 个。Android 只取第一个排 AlarmManager（WidgetRefresh）。
     */
    fun refreshDates(rows: List<ReminderWidgetRow>, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<Long> {
        val edge = nowMs + HORIZON_MS
        val set = sortedSetOf<Long>()
        for (r in rows) for (d in listOfNotNull(r.atMs) + (r.later ?: emptyList())) if (d in (nowMs + 1)..edge) set += d
        var day = LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        while (true) {
            val next = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            if (next > edge) break
            if (next > nowMs) set += next
            day = day.plusDays(1)
        }
        return set.take(60)
    }

    /** 任务小组件的「今天完成」：快照不是今天写的就是 0 —— 那个数是写入那一天的。 */
    fun todayDone(s: TaskWidgetSnapshot, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Int =
        if (sameDay(s.savedAtMs, nowMs, zone)) s.todayDone else 0

    /** 「更新于」那一行。没过 10 分钟不画（null）；跨日要带上是哪天 —— 只打「22:00」读起来像今天。 */
    fun staleText(savedAtMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String? {
        if (nowMs - savedAtMs <= 10 * 60 * 1000) return null
        val t = hhmm(savedAtMs, zone)
        if (sameDay(savedAtMs, nowMs, zone)) return "更新于 $t"
        val saved = LocalDate.ofInstant(Instant.ofEpochMilli(savedAtMs), zone)
        val today = LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        if (saved == today.minusDays(1)) return "更新于 昨天 $t"
        return "更新于 ${saved.monthValue}月${saved.dayOfMonth}日 $t"
    }

    /** 「09:05」，按时区。定长格式固定 Locale.US，免得被地区设置带歪（iOS 用 en_US_POSIX）。 */
    fun hhmm(ms: Long, zone: ZoneId): String =
        DateTimeFormatter.ofPattern("HH:mm", Locale.US).withZone(zone).format(Instant.ofEpochMilli(ms))

    /** 下一个零点（「今天」的上界）。 */
    fun startOfNextDay(nowMs: Long, zone: ZoneId): Long =
        LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun sameDay(a: Long, b: Long, zone: ZoneId) =
        LocalDate.ofInstant(Instant.ofEpochMilli(a), zone) == LocalDate.ofInstant(Instant.ofEpochMilli(b), zone)
}

/** 任务列表 → 小组件快照 / 状态行（iOS: WidgetBridge.swift:198 `syncTasks` 与 :393 `line(for:)`）。纯函数。 */
object WidgetTaskRules {
    /**
     * 执行中只认 `status == "running"`（iOS 原样：`$0.status == "running"`）。
     * 「今天完成」= done 且 updated_at 是今天（按本机时区判日界，服务端存 UTC —— TaskTime.parse 认 SQLite 那种无时区串按 UTC）。
     */
    fun snapshot(items: List<TaskItem>, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): TaskWidgetSnapshot {
        val running = items.firstOrNull { it.status == "running" }
        val today = LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        val todayDone = items.count { it.status == "done" && TaskTime.parse(it.updated_at)?.let { d -> LocalDate.ofInstant(d, zone) == today } == true }
        return TaskWidgetSnapshot(
            runningId = running?.id,
            runningTitle = running?.title,
            stepsDone = running?.steps_done ?: 0,
            stepsTotal = running?.steps_total ?: 0,
            statusLine = running?.let { line(it) },
            todayDone = todayDone,
            savedAtMs = nowMs,
        )
    }

    /** 「第 3/5 步」；服务端没给步骤数就用状态词，不编进度。 */
    fun line(task: TaskItem): String {
        val t = task.steps_total
        if (t != null && t > 0) return "第 ${minOf((task.steps_done ?: 0) + 1, t)}/$t 步"
        return "执行中"
    }

    /** 「40%」。没有步骤数时给 "…"（服务端没给就不编百分比）。iOS: WidgetShared.swift `percentText`。 */
    fun percentText(done: Int, total: Int): String =
        if (total > 0) "${Math.round(done.toDouble() / total * 100)}%" else "…"
}
