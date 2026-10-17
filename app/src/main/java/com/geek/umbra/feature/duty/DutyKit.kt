// 执勤 M1a / M2 的纯判据 —— 照搬 iOS Features/Duty/DutyKit.swift（契约 §16.8）：触发说人话、动作摘要、档位那一行、
// 日志行、「为什么」展开、通用编辑器的草稿与回写；巡视（interval）的说法与校验、每天最多几点、秘书的提议那一行细节。
//
// ⚠️ 纯 Kotlin，不 import android.*：用例在 app/src/test/.../duty/DutyKitTest.kt、DutyM2Test.kt（照抄 iOS swifttests）。
//
// 为什么文案走一个传进来的 [DutyTr] 而不是只回 key：触发「说人话」是**几段拼起来**的（星期几的名字、分隔符、时刻），
// 只回 key 的话用例只能断言 key，断言不到用户看到的那句话 —— 「每周一、三 08:00」拼成「每周一三08:00」这种错一条用例都抓不到。
// 页面传 [dutyTr]（DutyStrings.kt，key → R.string），用例传一张写死的中文模板表。
package com.geek.umbra.feature.duty

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 文案出口：key + 参数（全是字符串）→ 一句话。 */
typealias DutyTr = (key: String, args: List<String>) -> String

/** 无参数的便捷写法。 */
internal fun DutyTr.k(key: String): String = this(key, emptyList())

/** 带参数的便捷写法。 */
internal fun DutyTr.k(key: String, vararg args: String): String = this(key, args.toList())

object DutyKit {

    // MARK: 触发说人话

    /** 星期几（0 = 周一）→「一、三、五」。去重、按周一起排；认不出的数丢掉。 */
    fun weekdays(days: List<Int>, tr: DutyTr): String =
        days.filter { it in 0..6 }.toSortedSet().joinToString(tr.k("duty.list.sep")) { tr.k("duty.wd.$it") }

    /** "auto" → 「我通常上线的时候」，其余原样（HH:mm）。 */
    fun timeText(hhmm: String, tr: DutyTr): String = if (hhmm == DutyTrigger.AUTO) tr.k("duty.trig.auto") else hhmm

    private val DAY_FMT = DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.US)

    /** 'YYYY-MM-DD' → 那天是周几（0 = 周一），认不出 null。日期本身不带时区（按上海那天算，同服务端开班的口径）。 */
    fun weekday(day: String): Int? = runCatching { LocalDate.parse(day, DAY_FMT) }.getOrNull()?.dayOfWeek?.value?.minus(1)

    /** 触发 → 一句人话（例：「每个工作日 08:00」「工作日 07:00–10:00 我第一次打开时」）。null（老服务端没回）→ 空串。 */
    fun trigger(t: DutyTrigger?, tr: DutyTr): String {
        t ?: return ""
        return when (t.type) {
            "at" -> if (t.at_ms <= 0) tr.k("duty.trig.atUnset") else tr.k("duty.trig.at", DutyRules.monthDayTime(t.at_ms))
            "window" -> tr.k("duty.trig.window", windowDays(t, tr), t.start_hhmm, t.end_hhmm, signalText(t.signal, tr))
            // M2 巡视：「每天 09:00–21:00 每 2 小时」（同 PC `triggerText`）。
            "interval" -> tr.k("duty.trig.interval", intervalDays(t, tr), t.start_hhmm, t.end_hhmm, everyText(t.every_min, tr))
            else -> recur(t, tr)
        }
    }

    /** 窗口型「哪些天」：每周几 / 工作日 / 每天。 */
    private fun windowDays(t: DutyTrigger, tr: DutyTr): String {
        if (t.cycle == "week" && t.week_days.isNotEmpty()) return tr.k("duty.trig.daysWeek", weekdays(t.week_days, tr))
        return if (t.workday_only) tr.k("duty.trig.daysWorkday") else tr.k("duty.trig.daysEveryday")
    }

    /** 巡视型「哪些天」：工作日 → 挑了几天（不满 7 天）→ 每天。不看 cycle（线上永远是 day），直接按 week_days 判。 */
    private fun intervalDays(t: DutyTrigger, tr: DutyTr): String {
        if (t.workday_only) return tr.k("duty.trig.daysWorkday")
        val days = t.week_days.filter { it in 0..6 }.toSet()
        if (days.isNotEmpty() && days.size < 7) return tr.k("duty.trig.daysWeek", weekdays(days.toList(), tr))
        return tr.k("duty.trig.daysEveryday")
    }

    /** 巡视间隔的可选值（分钟，服务端收 30–720）。同 PC `EVERY_MINS`。 */
    val intervalOptions = listOf(30, 60, 90, 120, 180, 240, 360, 720)

    /** 间隔 → 「每 2 小时」/「每 90 分钟」：整小时写小时，否则写分钟（90 分钟说成「每 1.5 小时」反而要算一下）。 */
    fun everyText(min: Int, tr: DutyTr): String =
        if (min > 0 && min % 60 == 0) tr.k("duty.trig.everyH", (min / 60).toString()) else tr.k("duty.trig.everyM", min.toString())

    /** 窗口里等什么信号。认不出的按「由秘书决定」说 —— 那是不带信号时的语义。 */
    fun signalText(s: String, tr: DutyTr): String = when (s) {
        "first_seen_today" -> tr.k("duty.sig.firstSeen")
        "device_online" -> tr.k("duty.sig.deviceOnline")
        else -> tr.k("duty.sig.none")
    }

    private fun recur(t: DutyTrigger, tr: DutyTr): String {
        val time = timeText(t.time_hhmm, tr)
        val n = t.every_n.toString()
        // 「只在工作日」在每天那一档里本身就是说法（「每个工作日」）；别的周期上是一句附注。
        val suffix = if (t.workday_only) tr.k("duty.trig.workdaySuffix") else ""
        return when (t.cycle) {
            "week" -> {
                var days = t.week_days
                if (days.isEmpty()) weekday(t.first_date)?.let { days = listOf(it) }
                if (days.isEmpty()) return tr.k("duty.trig.weekAny", time) + suffix
                val names = weekdays(days, tr)
                (if (t.every_n > 1) tr.k("duty.trig.everyNWeeks", n, names, time) else tr.k("duty.trig.week", names, time)) + suffix
            }
            "month" -> {
                val dd = t.first_date.split("-").lastOrNull()?.toIntOrNull()?.toString() ?: "1"
                (if (t.every_n > 1) tr.k("duty.trig.everyNMonths", n, dd, time) else tr.k("duty.trig.month", dd, time)) + suffix
            }
            "year" -> {
                val md = if (t.first_date.length == 10) t.first_date.takeLast(5) else t.first_date
                tr.k("duty.trig.year", md, time) + suffix
            }
            else -> {
                if (t.every_n > 1) tr.k("duty.trig.everyNDays", n, time) + suffix
                else if (t.workday_only) tr.k("duty.trig.workday", time) else tr.k("duty.trig.daily", time)
            }
        }
    }

    // MARK: 动作摘要

    /** 太长的一句截到 [max] 个字符加省略号（列表上一行放不下一整段 brief）。按字符（码点）数，同 Swift 的 Character。 */
    fun clip(s: String, max: Int): String {
        val t = s.trim().replace("\n", " ")
        val cps = t.codePointCount(0, t.length)
        if (cps <= max) return t
        return t.substring(0, t.offsetByCodePoints(0, max)) + "…"
    }

    /**
     * 动作 → 一句摘要。`workflow` 本期置灰 —— 照实说「暂不可用」。
     * [flowName]：「跑一个流程」指向的那个流程的名字；找不到就退回不带名字的那句 —— 不把 flow_id 那串 uuid 印给人看。
     */
    fun action(a: DutyAction?, tr: DutyTr, flowName: String = ""): String {
        a ?: return ""
        return when (a.type) {
            "task" -> tr.k(if (a.confirm == "never") "duty.act.taskNow" else "duty.act.taskAsk", clip(a.goal, 40))
            "flow" -> if (flowName.isEmpty()) tr.k("duty.act.flow") else tr.k("duty.act.flowNamed", flowName)
            "workflow" -> tr.k("duty.act.workflow")
            else -> {
                val b = clip(a.brief, 40)
                if (b.isEmpty()) tr.k("duty.act.sayPlain") else tr.k("duty.act.say", b)
            }
        }
    }

    /** 要不要判断 → 一句。 */
    fun modeText(mode: String, tr: DutyTr): String = tr.k(if (mode == "judge") "duty.mode.judge" else "duty.mode.always")

    /** 优先级 1–5 → 「可有可无 / 低 / 普通 / 重要 / 务必」。越界的收回 1–5。 */
    fun priorityText(p: Int, tr: DutyTr): String = tr.k("duty.prio.${p.coerceIn(1, 5)}")

    /** 冷却的可选值（分钟）：0 = 不限。 */
    val cooldownOptions = listOf(0, 30, 60, 120, 240, 720, 1440)

    /** 冷却 → 「不限 / 30 分钟 / 2 小时」。 */
    fun cooldownText(min: Int, tr: DutyTr): String {
        if (min <= 0) return tr.k("duty.cooldown.none")
        if (min % 60 == 0) return tr.k("duty.cooldown.hours", (min / 60).toString())
        return tr.k("duty.cooldown.minutes", min.toString())
    }

    /** 「每天最多主动说几次」的可选值（契约 §16.1 默认 3）。 */
    val nudgeOptions = listOf(1, 2, 3, 4, 5, 6, 8, 10)

    /** 「每天最多几点」的可选值（M2 `points_cap`，服务端收 0–200；0 = 不单独设限）。同 PC `POINT_CAPS`。 */
    val pointsCapOptions = listOf(0, 3, 6, 10, 20, 50)

    /** 点数上限 → 「不单独设限」/「6 点」。 */
    fun pointsCapText(n: Int, tr: DutyTr): String = if (n > 0) tr.k("duty.cap.n", n.toString()) else tr.k("duty.cap.none")

    /** 「发一段话」能带上的块，按编辑器里的顺序。服务端还认 `tasks_attention` / `holiday`（预设自带）—— 编辑器不给开关，但原样留着。 */
    val sayBlocks = listOf("weather", "reminders", "tasks", "reminders_soon", "follows_hot")

    /** 块名 → 文案 key。 */
    fun blockKey(b: String): String = when (b) {
        "reminders_soon" -> "duty.block.remindersSoon"
        "follows_hot" -> "duty.block.followsHot"
        else -> "duty.block.$b"
    }

    // MARK: 档位那一行（契约 §16.8 第 2 条）

    data class TierLine(
        /** 非 normal 时那颗档位小标（「节省」「只保要紧」「停」，批次 261 §四：warning-soft 底，不用橙字）；normal = null。 */
        val headline: String?,
        /** 服务端给的原因 + 预报那句，去重、去空，用「 · 」接。没有就 null。 */
        val detail: String?,
    )

    /**
     * 预报那句在 normal 档也出：「照这周的节奏，周四会用完额度」**正是在还没降档的时候说才有用**。
     * 认不出的档照非 normal 处理，headline 退回「执勤在省着用」。
     */
    fun tierLine(tier: String, reason: String, forecastNote: String?, tr: DutyTr): TierLine {
        val known = listOf("save", "essential", "stopped")
        var headline: String? = null
        if (tier != "normal" && tier.isNotEmpty()) headline = if (tier in known) tr.k("duty.tier.$tier") else tr.k("duty.tier.other")
        val parts = mutableListOf<String>()
        for (p in listOf(reason, forecastNote ?: "")) {
            val t = p.trim()
            if (t.isNotEmpty() && t !in parts && t != headline) parts += t
        }
        return TierLine(headline, if (parts.isEmpty()) null else parts.joinToString(" · "))
    }

    /**
     * 档位小标之后要不要带「调高预算」（批次 261 §四：只有「停」带）：只在 `tier_code == budget`（今天的执勤预算用完了）时给 ——
     * 没会员 / 额度窗口满了的「停」调高预算没用。老服务端没有 `tier_code` 时不给 —— 分不出来就不猜。iOS DutyKit.swift:218
     */
    fun tierOffersBudget(tier: String, code: DutyTierCode): Boolean = tier == "stopped" && code == DutyTierCode.Budget

    /** 档位那一块的次钮（批次 265）：预算用完 →「调高预算」；没会员 →「开会员」；其余不出。 */
    enum class TierAction { None, Budget, Membership }

    /** 档位那一句 + 次钮。 */
    data class TierCopy(val text: String?, val action: TierAction)

    /**
     * 档位那一句 + 次钮（批次 265 §档位，字面以协调方核过服务端行为后定的为准）。iOS DutyKit.swift:27-57
     * - 节省：今天预算用过六成（spent ≥ 0.6 × cap）且周额度剩 ≥ 三成 → 「今天的执勤预算用过六成了…」；
     *   周额度剩不到三成（两条同时成立、或算不出时也是它）→ 「周额度剩不到三成…」；
     * - 只保要紧：「周额度剩不到 15%…」；
     * - 停：budget →「…停到明天 00:00…」+ 调高预算；quota →「周额度用满了，执勤停到 X…」（X 取 full.end_ms，没有就不写「到 X」）；
     *   no_membership →「没有会员，执勤停了…」+ 开会员；码认不出（老服务端）→ 服务端原话（`tierLine.detail`），不出钮。
     * 正常档 text = null。
     */
    fun tierCopy(l: DutyListDTO, nowMs: Long, tr: DutyTr): TierCopy = when (l.tier) {
        "normal", "" -> TierCopy(null, TierAction.None)
        "save" -> {
            val weekLow = l.week?.left?.let { it < 0.3 } ?: true
            val budgetHigh = l.budget?.let { it.cap > 0 && it.spent >= 0.6 * it.cap } ?: false
            TierCopy(tr.k(if (budgetHigh && !weekLow) "duty.tier.saveBudget" else "duty.tier.saveWeek"), TierAction.None)
        }
        "essential" -> TierCopy(tr.k("duty.tier.essentialText"), TierAction.None)
        "stopped" -> when (l.tier_code) {
            DutyTierCode.Budget -> TierCopy(tr.k("duty.tier.stopBudget"), TierAction.Budget)
            DutyTierCode.NoMembership -> TierCopy(tr.k("duty.tier.stopMember"), TierAction.Membership)
            DutyTierCode.Quota -> {
                val end = l.full?.end_ms
                if (end == null || end <= 0) TierCopy(tr.k("duty.tier.stopQuotaPlain"), TierAction.None)
                else TierCopy(tr.k("duty.tier.stopQuota", untilText(end, nowMs, tr)), TierAction.None)
            }
            else -> TierCopy(tierLine(l.tier, l.tier_reason, null, tr).detail, TierAction.None)
        }
        else -> TierCopy(tierLine(l.tier, l.tier_reason, null, tr).detail, TierAction.None)
    }

    /** 停到几时：同一天写「HH:MM」，跨日写「M月D日 HH:MM」（上海时区，同服务端开班口径）。 */
    fun untilText(ms: Long, nowMs: Long, tr: DutyTr): String {
        if (DutyRules.day(ms) == DutyRules.day(nowMs)) return DutyRules.hhmm(ms)
        val d = java.time.Instant.ofEpochMilli(ms).atZone(DutyRules.tz)
        return tr.k("duty.tier.untilDay", d.monthValue.toString(), d.dayOfMonth.toString(), DutyRules.hhmm(ms))
    }

    /** 「今天执勤预算」那一行的数值：「2.1 / 6 点」。整数不带小数，其余留一位。 */
    fun budgetText(b: DutyBudget, tr: DutyTr): String = tr.k("duty.budgetToday.value", pointText(b.spent), pointText(b.cap))

    /** 进度 0…1（超额封在 1；cap 为 0 按用满）。 */
    fun budgetFraction(b: DutyBudget): Double {
        if (b.cap <= 0) return 1.0
        return minOf(1.0, maxOf(0.0, b.spent / b.cap))
    }

    private fun pointText(v: Double): String {
        if (!v.isFinite() || v <= 0) return "0"
        // Swift `.rounded()` 是四舍五入（远离零）；v > 0，Math.round 同义
        val r = Math.round(v * 10) / 10.0
        return if (r == Math.rint(r)) r.toLong().toString() else String.format(Locale.ROOT, "%.1f", r)
    }

    // MARK: 页头状态（批次 261 §四：只有文字不够，图标 + 文字）

    /** 页头那一行的四种状态。iOS DutyKit.swift:86 */
    sealed interface HeadState {
        data object On : HeadState
        data class PausedUntil(val ms: Long) : HeadState
        data object PausedForever : HeadState
        data object Off : HeadState
    }

    /** 页头那一行：没开（总开关关着或没确认过）→ 暂停中 → 执勤中。 */
    fun headState(s: DutySettings, nowMs: Long): HeadState {
        if (!DutyRules.switchOn(s)) return HeadState.Off
        return when (val p = DutyRules.pause(s.paused_until_ms, nowMs)) {
            DutyPause.None -> HeadState.On
            is DutyPause.Until -> HeadState.PausedUntil(p.ms)
            DutyPause.Forever -> HeadState.PausedForever
        }
    }

    /** 一个时刻说人话：今天的写「今天 18:00」，别的天写「10-18 09:00」（暂停到、上一次失败用）。按上海时区判同一天。 */
    fun whenText(ms: Long, nowMs: Long, tr: DutyTr): String =
        if (DutyRules.day(ms) == DutyRules.day(nowMs)) tr.k("duty.today.at", DutyRules.hhmm(ms)) else DutyRules.monthDayTime(ms)

    /**
     * 日志 / 执勤表上结局的图标名（iOS `UmbraIconPath` 的名字；这个文件不碰 Compose，只回名字，页面上 [dutyOutcomeIconPath] 换成 path）。
     * 批次 261 §四：说了 / 建了任务 = 对勾，没事 = 横线圈，跳过 = 钟，没跑成 = 圆叉。
     * 「没事」= 交给秘书判断之后决定不发（`skipped` 且有 decision）；规矩直接拦下的跳过（没有 decision）、错过、被拦下都是钟。
     * 没结案的（进行中 / 延后 / 等信号）不画图标 —— 它们还不是结局。
     */
    fun outcomeIcon(s: DutyShift): String? = when (s.outcome) {
        "ran" -> "check"
        "failed" -> "xCircle"
        "skipped" -> if (s.decision_id.isEmpty()) "clock" else "minusCircle"
        "missed", "blocked" -> "clock"
        else -> null
    }

    /** 执勤表一行的「上一次」那一行。 */
    data class LastLine(val text: String, val failed: Boolean)

    /** 执勤表一行的「上一次」：失败要说原因（批次 261 §四），写成「今天 08:00没跑成：原因」；其余照旧「上一次：做了」。 */
    fun lastLine(s: DutyShift, nowMs: Long, tr: DutyTr): LastLine {
        if (s.outcome != "failed") return LastLine(tr.k("duty.items.last", tr.k(DutyRules.stateKey(s))), false)
        val w = whenText(s.planned_at_ms, nowMs, tr)
        val why = s.reason_text.trim()
        return LastLine(if (why.isEmpty()) tr.k("duty.items.lastFailedPlain", w) else tr.k("duty.items.lastFailed", w, why), true)
    }

    // MARK: 日志行（契约 §16.8 第 3 条）

    /** 延后到的时刻：和计划时刻同一天只写时分，跨天写「10-17 09:30」。 */
    fun deferText(until: Long, planned: Long): String =
        if (DutyRules.day(until) == DutyRules.day(planned)) DutyRules.hhmm(until) else DutyRules.monthDayTime(until)

    /** 日志一行的副文：「手动」「简版」小标 → 被并 / 延后 → 理由一行。`log` 用来找被并进的那一班。 */
    fun logSub(s: DutyShift, log: List<DutyShift>, tr: DutyTr): String? {
        val parts = mutableListOf<String>()
        if (s.manual) parts += tr.k("duty.tag.manual")
        if (s.simple) parts += tr.k("duty.tag.simple")
        if (s.merged_into.isNotEmpty()) {
            val main = log.firstOrNull { it.id == s.merged_into }
            parts += if (main != null) tr.k("duty.log.merged", DutyRules.hhmm(main.planned_at_ms), main.duty_name) else tr.k("duty.log.mergedAny")
        } else if (s.defer_until_ms > 0) {
            parts += tr.k("duty.log.deferred", deferText(s.defer_until_ms, s.planned_at_ms))
        }
        if (s.reason_text.isNotEmpty()) parts += s.reason_text
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }

    /** 日志按条目筛（null / 空 = 全部）。保持服务端顺序。 */
    fun filter(log: List<DutyShift>, dutyId: String?): List<DutyShift> =
        if (dutyId.isNullOrEmpty()) log else log.filter { it.duty_id == dutyId }

    // MARK: 「为什么」（契约 §16.2）

    /** 上下文摘要里认得的块，按这个顺序列；认不出的键排在后面、按键名排。 */
    val contextOrder = listOf("now", "holiday", "busy", "presence", "reminders_today", "tasks", "nudges_today", "candidates")

    /** 一段 JSON → 一句短话。数组：元素都带 name / title → 名字接起来，否则「N 条」；对象：一层「k：v」。 */
    fun valueText(v: JsonElement?, tr: DutyTr): String? {
        if (v.isNullish) return null
        if (v is kotlinx.serialization.json.JsonArray) {
            val names = v.mapNotNull { (it["name"] ?: it["title"]).str }.filter { it.isNotEmpty() }
            if (names.isNotEmpty() && names.size == v.size) return clip(names.joinToString(tr.k("duty.list.sep")), 120)
            return tr.k("duty.ctx.count", v.size.toString())
        }
        if (v is JsonObject) {
            val pairs = v.keys.sorted().mapNotNull { k -> scalarText(v[k], tr)?.let { "$k：$it" } }
            return if (pairs.isEmpty()) null else clip(pairs.joinToString("，"), 120)
        }
        v.bool?.let { return tr.k(if (it) "duty.ctx.yes" else "duty.ctx.no") }
        val s = v.str ?: ""
        return if (s.isEmpty()) null else clip(s, 120)
    }

    /** 对象里一格只取标量（再往下嵌就只说条数）—— 「为什么」是给人扫一眼的，不是 JSON 查看器。 */
    private fun scalarText(v: JsonElement?, tr: DutyTr): String? = when (v) {
        is kotlinx.serialization.json.JsonArray -> tr.k("duty.ctx.count", v.size.toString())
        is JsonObject -> null
        else -> valueText(v, tr)
    }

    /** 判断结果里与这一班有关的那句：现在发 / 并进别的一起发 / 延后到几点 / 跳过；找不到这一班 → null。 */
    fun verdict(result: JsonElement, shiftId: String, tr: DutyTr): String? {
        for (r in result["run"].arr ?: emptyList()) {
            if (r["shift_id"].str == shiftId) return tr.k("duty.why.run")
            if ((r["merge"].arr ?: emptyList()).any { it.str == shiftId }) return tr.k("duty.why.merged")
        }
        for (r in result["defer"].arr ?: emptyList()) {
            if (r["shift_id"].str != shiftId) continue
            val until = r["until"].str ?: ""
            val why = r["why"].str ?: ""
            return if (why.isEmpty()) tr.k("duty.why.defer", until) else tr.k("duty.why.deferWhy", until, why)
        }
        for (r in result["skip"].arr ?: emptyList()) {
            if (r["shift_id"].str != shiftId) continue
            val why = r["why"].str ?: ""
            return if (why.isEmpty()) tr.k("duty.why.skip") else tr.k("duty.why.skipWhy", why)
        }
        return null
    }

    /** 「为什么」展开的全部行：这一班的结论 → 总评 → 上下文摘要（一块一行）→ 模型 / 耗时 / 点数。 */
    fun decisionLines(d: DutyDecision, shiftId: String, tr: DutyTr): List<String> {
        val out = mutableListOf<String>()
        verdict(d.result, shiftId, tr)?.let { out += it }
        if (d.why.isNotEmpty()) out += tr.k("duty.why.overall", d.why)
        val ctx = d.context as? JsonObject
        if (ctx != null) {
            val keys = contextOrder.filter { ctx.containsKey(it) } + ctx.keys.filter { it !in contextOrder }.sorted()
            for (k in keys) {
                val text = valueText(ctx[k], tr) ?: continue
                val label = if (k in contextOrder) tr.k("duty.ctx.$k") else k
                out += "$label：$text"
            }
        }
        val meta = mutableListOf<String>()
        if (d.model_key.isNotEmpty()) meta += d.model_key
        if (d.ms > 0) meta += tr.k("duty.why.secs", String.format(Locale.ROOT, "%.1f", d.ms / 1000.0))
        if (d.points > 0) meta += tr.k("duty.why.points", d.points.toString())
        if (meta.isNotEmpty()) out += meta.joinToString(" · ")
        return out
    }

    // MARK: 秘书的提议（M2）

    /** 提议种类 → 小标的 key。兜底归「新条目」只为不印出 key。 */
    fun proposalKindKey(kind: String): String = when (kind) {
        "tune" -> "duty.pr.kind.tune"
        "quiet" -> "duty.pr.kind.quiet"
        "block" -> "duty.pr.kind.block"
        "flow" -> "duty.pr.kind.flow"
        "follow" -> "duty.pr.kind.follow"
        else -> "duty.pr.kind.new"
    }

    /** 「做成例程」那种的定时写几点：`draft.time_hhmm`，认不出给 08:00（同 PC）。 */
    fun proposalFlowTime(p: DutyProposal): String {
        val t = p.draft["time_hhmm"].str ?: ""
        return if (DutyRules.validHHMM(t)) t else "08:00"
    }

    /** 早报那几块在节点链里的名字：认得的走块名，认不出的原样（服务端以后加的块，印英文名比漏掉强）。 */
    private fun blockName(b: String, tr: DutyTr): String {
        val key = blockKey(b)
        val s = tr.k(key)
        return if (s == key) b else s
    }

    /** 变化块节点链里的一颗胶囊。`added` = 这一块是新加的（批次 261 §二：早报加一块时那颗带「+」、600 字重）。 */
    data class ProposalNode(val label: String, val added: Boolean = false)

    /**
     * 提议卡的「变化块」（批次 261 §二 3）：让人一眼看出「点了会变成什么」。三种写法 + 没什么可说。iOS DutyKit.swift:170
     * 原来是一行 faint 小字（`proposalDetail`），节点用 ①②③ 串起来 —— 稿上改成胶囊 + 箭头，前后对比用删除线，
     * 所以这里回结构、不回拼好的一句话：删除线 / 600 字重 / 胶囊是画法，拼成字符串就丢了。
     */
    sealed interface ProposalChange {
        /** 什么都画不出来（服务端没给够）：卡上不画这一块。 */
        data object None : ProposalChange
        /** 一句话：新条目、停掉、关注。 */
        data class Text(val text: String) : ProposalChange
        /** 前后对比「标签 旧值 → 新值」：改时间、静默时段。 */
        data class Diff(val label: String, val from: String, val to: String) : ProposalChange
        /** 节点链：早报的块、做成例程。`meta` 跟在链后（faint，「每次约 3 点」），空串不画。 */
        data class Nodes(val nodes: List<ProposalNode>, val meta: String) : ProposalChange
    }

    /** 「调整」里是不是「停掉这一条」（`patch.enabled == false`）。它先于改时刻判：两样都给时，停掉才是这条提议的本意。 */
    fun proposalIsStop(p: DutyProposal): Boolean = p.kind == "tune" && p.draft["patch"]["enabled"].bool == false

    /**
     * 一条提议的变化块（按种类，同 PC）。[dutyName]：提议指向的那一条叫什么（调整用来写「早报时间」，找不到给空串）；
     * [currentBlocks]：早报现在说哪几块（null = 不知道 —— 没读到早报，那就一颗都不标「+」，标错比不标更糟）。
     */
    fun proposalChange(p: DutyProposal, tr: DutyTr, dutyName: String = "", currentBlocks: List<String>? = null): ProposalChange {
        val d = p.draft
        return when (p.kind) {
            "tune" -> {
                if (proposalIsStop(p)) return ProposalChange.Text(tr.k("duty.pr.detailStop"))
                val from = d["from"].str ?: ""
                val to = d["to"].str ?: ""
                if (from.isEmpty() || to.isEmpty()) return ProposalChange.None
                val label = if (dutyName.isEmpty()) tr.k("duty.pr.diffTime") else tr.k("duty.pr.diffTimeOf", dutyName)
                ProposalChange.Diff(label, from, to)
            }
            "quiet" -> {
                val s = d["settings"]
                val range = "${s["quiet_start"].str ?: ""}–${s["quiet_end"].str ?: ""}"
                val from = d["from"].str ?: ""
                // 没有「原来是几点」就只说改成几点 —— 删除线那一格空着、箭头悬在半空，读起来像少了一截。
                if (from.isEmpty()) ProposalChange.Text(tr.k("duty.pr.quietTo", range))
                else ProposalChange.Diff(tr.k("duty.pr.diffQuiet"), from, range)
            }
            "block" -> {
                val blocks = (d["patch"]["params"]["blocks"].arr ?: emptyList()).mapNotNull { it.str }.filter { it.isNotEmpty() }
                if (blocks.isEmpty()) ProposalChange.None
                else ProposalChange.Nodes(blocks.map { b -> ProposalNode(blockName(b, tr), currentBlocks?.let { b !in it } ?: false) }, "")
            }
            "follow" -> {
                val topic = d["topic"].str ?: ""
                val query = d["query"].str ?: ""
                if (topic.isEmpty()) ProposalChange.None
                else ProposalChange.Text(if (query.isEmpty()) tr.k("duty.pr.detailFollowTopic", topic) else tr.k("duty.pr.detailFollow", topic, query))
            }
            "new" -> {
                val trig = trigger(DutyTrigger.parse(d["trigger"]), tr)
                val brief = clip(d["action"]["brief"].str ?: "", 40)
                val line = listOf(trig, brief).filter { it.isNotEmpty() }.joinToString(" · ")
                if (line.isEmpty()) ProposalChange.None else ProposalChange.Text(line)
            }
            "flow" -> {
                val nodes = (d["nodes"].arr ?: emptyList()).mapNotNull { it.str }.filter { it.isNotEmpty() }
                val pts = tr.k("duty.pr.detailPoints", p.est_points.toString())
                if (nodes.isEmpty()) ProposalChange.Text(pts) else ProposalChange.Nodes(nodes.map { ProposalNode(it) }, pts)
            }
            else -> ProposalChange.None
        }
    }

    /** 主钮的字面 key（批次 261 §二 4：「好」作废，按种类写动词）。「做成例程」的主钮是「建并定时」、次钮「只建」。 */
    fun proposalPrimaryKey(p: DutyProposal): String = when (p.kind) {
        "tune" -> if (proposalIsStop(p)) "duty.pr.yes.stop" else "duty.pr.yes.apply"
        "quiet" -> "duty.pr.yes.apply"
        "flow" -> "duty.pr.flowSchedule"
        "follow" -> "duty.pr.yes.follow"
        else -> "duty.pr.yes.add" // new / block
    }

    /** 「做成例程」卡上那一行定时（时间不进钮，写在卡上）。别的种类 null。 */
    fun proposalNote(p: DutyProposal, tr: DutyTr): String? =
        if (p.kind == "flow") tr.k("duty.pr.flowNote", proposalFlowTime(p)) else null

    /**
     * 点了主钮 / 次钮之后的吐司：写具体结果（批次 261 §二：「早报改到 09:10 了」），不写「照这条改好了」。
     * [schedule] 只对「做成例程」有意义（建并定时 / 只建）。iOS DutyKit.swift:260
     */
    fun proposalDoneText(p: DutyProposal, schedule: Boolean, tr: DutyTr, dutyName: String = ""): String {
        val d = p.draft
        val draftName = (d["name"].str ?: "").trim()
        return when (p.kind) {
            "tune" -> {
                if (proposalIsStop(p)) return if (dutyName.isEmpty()) tr.k("duty.pr.doneStopPlain") else tr.k("duty.pr.doneStop", dutyName)
                val to = d["to"].str ?: ""
                if (to.isEmpty()) tr.k("duty.pr.done")
                else if (dutyName.isEmpty()) tr.k("duty.pr.doneTimePlain", to) else tr.k("duty.pr.doneTime", dutyName, to)
            }
            "quiet" -> {
                val s = d["settings"]
                tr.k("duty.pr.doneQuiet", "${s["quiet_start"].str ?: ""}–${s["quiet_end"].str ?: ""}")
            }
            "block" -> {
                val blocks = (d["patch"]["params"]["blocks"].arr ?: emptyList()).mapNotNull { it.str }.filter { it.isNotEmpty() }
                if (blocks.isEmpty()) tr.k("duty.pr.done")
                else tr.k("duty.pr.doneBlock", blocks.joinToString(tr.k("duty.list.sep")) { blockName(it, tr) })
            }
            "follow" -> {
                val topic = d["topic"].str ?: ""
                if (topic.isEmpty()) tr.k("duty.pr.done") else tr.k("duty.pr.doneFollow", topic)
            }
            "flow" -> {
                val name = draftName.ifEmpty { p.title }
                if (schedule) tr.k("duty.pr.doneFlow", name, proposalFlowTime(p)) else tr.k("duty.pr.doneFlowOnly", name)
            }
            else -> {
                val trig = trigger(DutyTrigger.parse(d["trigger"]), tr)
                val what = listOf(trig, draftName).filter { it.isNotEmpty() }.joinToString(" ")
                tr.k("duty.pr.doneNew", what.ifEmpty { p.title })
            }
        }
    }

    /** 提议区一次最多摊开几张（批次 261 §一：超过 3 条折叠成「还有 N 条」）。 */
    const val PROPOSAL_FOLD = 3

    /** 摊开几张、折起几张。 */
    data class Shown(val shown: Int, val more: Int)

    /** [showAll] = 点过「还有 N 条」。 */
    fun proposalShown(count: Int, showAll: Boolean): Shown {
        val n = maxOf(0, count)
        if (showAll || n <= PROPOSAL_FOLD) return Shown(n, 0)
        return Shown(PROPOSAL_FOLD, n - PROPOSAL_FOLD)
    }

    // MARK: 聊天早报卡末尾（批次 261 §四 末行）

    /** 预报那句 + 建议条数（null = 不出「看建议」）。 */
    data class ForecastTail(val forecast: String, val proposals: Int?)

    /**
     * 早报卡末尾：预报那句 + 建议条数。新服务端带 `proposals_n`（0 = 没有）就用它；老消息没这个字段（null）退回认那句话。
     * 那句话两种情况都从预报格里拿掉（服务端照旧把它接在 `forecast_note` 里）。iOS DutyKit.swift:309
     */
    fun forecastTail(note: String, proposalsN: Int?): ForecastTail {
        val split = splitForecast(note)
        proposalsN ?: return split
        return ForecastTail(split.forecast, if (proposalsN > 0) proposalsN else null)
    }

    private val FIRST_INT = Regex("[0-9]+")

    /**
     * 早报卡末尾那一格（`meta.duty.forecast_note`）拆开：额度预报一句、「另外，我有 N 条建议…」一句。
     * 服务端把两句用换行接在同一格里（`duty_worker._proposal_note`）—— 稿上要分开画（建议那句不是预报，单起一行 + 「看建议」）。
     * 认的是「条建议」+「秘书的提议」两个片段同时在一行里，条数取那一行里第一个整数；
     * 认不出就当没有建议、整格照旧当预报画（宁可少一颗钮，不把预报吞掉）。
     */
    fun splitForecast(note: String): ForecastTail {
        val keep = mutableListOf<String>()
        var n: Int? = null
        for (raw in note.split("\n")) {
            // Swift `.whitespaces` = 制表符 + Unicode 空格类（Zs），不含换行
            val line = raw.trim { it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }
            if (line.isEmpty()) continue
            if (n == null && "条建议" in line && "秘书的提议" in line) {
                val v = FIRST_INT.find(line)?.value?.toIntOrNull()
                if (v != null && v > 0) {
                    n = v
                    continue
                }
            }
            keep += line
        }
        return ForecastTail(keep.joinToString("\n"), n)
    }

    // MARK: 关注

    /** 关注的状态 → 文案 key。 */
    fun followStateKey(s: String): String = when (s) {
        "quiet" -> "duty.follow.state.quiet"
        "ended" -> "duty.follow.state.ended"
        else -> "duty.follow.state.active"
    }

    /** 关注列表的顺序：进行中的在前（active → quiet → ended），同档按重要度高的在前，再按新建的在前。 */
    fun sortFollows(xs: List<DutyFollow>): List<DutyFollow> {
        val rank = mapOf("active" to 0, "quiet" to 1, "ended" to 2)
        return xs.sortedWith(
            compareBy<DutyFollow> { rank[it.state] ?: 0 }.thenByDescending { it.importance }.thenByDescending { it.created_at_ms },
        )
    }

    /** 早报卡上一条的「关注 / 不感兴趣」状态的键（同一张卡的同一条，跨 WS / 历史两条入口一致）。 */
    fun cardKey(shiftId: String, itemId: String): String = "$shiftId/$itemId"
}

// MARK: - 通用编辑器的草稿（契约 §16.8 第 1 条「自己定」）—— iOS DutyKit.swift:423

/**
 * 编辑器里改的那一份。页面只改这个值，存的时候 [payload] 一次回写 —— 不边改边 PATCH：
 * 触发切来切去的中间态（选了「每周」还没勾星期几）不是一条能跑的执勤。
 * 做成不可变 data class + 返回新值的「变更」函数：Compose 里放进 mutableStateOf 换整份，重组才认得出变化。
 */
data class DutyDraft(
    val name: String,
    val trigger: DutyTrigger,
    val action: DutyAction,
    val mode: String,
    val priority: Int,
    val cooldown_min: Int,
    val respect_quiet: Boolean,
    /** M2：这一条每天最多花几点（0 = 不单独设限）。 */
    val points_cap: Int,
) {
    /** 周期那一档在编辑器上的三种说法 + 一种「编辑器里改不了的」（每月 / 每年 / 每 N 天）。 */
    enum class RecurKind { Daily, Workday, Weekly, Other }

    val recurKind: RecurKind
        get() {
            if (trigger.every_n != 1) return RecurKind.Other
            return when (trigger.cycle) {
                "day" -> if (trigger.workday_only) RecurKind.Workday else RecurKind.Daily
                "week" -> RecurKind.Weekly
                else -> RecurKind.Other
            }
        }

    /** 切周期那一档。切到「每周」且还没选星期几时，默认选 first_date 那天（再认不出就周一）—— 先给一个能存的值。 */
    fun setRecurKind(k: RecurKind): DutyDraft {
        var t = trigger.copy(type = "recur", every_n = 1)
        t = when (k) {
            RecurKind.Daily -> t.copy(cycle = "day", workday_only = false)
            RecurKind.Workday -> t.copy(cycle = "day", workday_only = true)
            RecurKind.Weekly -> t.copy(
                cycle = "week", workday_only = false,
                week_days = t.week_days.ifEmpty { listOf(DutyKit.weekday(t.first_date) ?: 0) },
            )
            RecurKind.Other -> t
        }
        if (t.time_hhmm.isEmpty()) t = t.copy(time_hhmm = "09:00")
        return copy(trigger = t)
    }

    /** 切触发种类（四选一）。`at` 没定时刻时给「明天这个时候」—— 同上，先给一个能存的值。 */
    fun setTriggerType(type: String, nowMs: Long): DutyDraft {
        val was = trigger.type
        var t = trigger.copy(type = type)
        if (type == "at" && t.at_ms <= nowMs) t = t.copy(at_ms = nowMs + 24 * 3_600_000L)
        if (type == "window" || type == "interval") {
            // 窗口没有「我通常上线的时候」这一档；周期只认天 / 周。巡视同一套。
            if (t.cycle != "week") t = t.copy(cycle = "day")
        }
        if (type == "interval" && was != "interval") {
            // 从别的种类切过来：起止换成巡视的缺省（09:00–21:00）—— 窗口那套 07:00–10:00 每 2 小时只跑两班，不像巡视。
            t = t.copy(start_hhmm = "09:00", end_hhmm = "21:00")
            if (t.every_min !in DutyKit.intervalOptions) t = t.copy(every_min = 120)
        }
        if (type != "recur" && t.time_hhmm == DutyTrigger.AUTO) t = t.copy(time_hhmm = "09:00")
        return copy(trigger = t)
    }

    /** 勾 / 取消一个星期几（0 = 周一），结果按周一起排、不重复。 */
    fun toggleWeekday(d: Int): DutyDraft {
        if (d !in 0..6) return this
        val s = trigger.week_days.toMutableSet()
        if (d in s) s.remove(d) else s.add(d)
        return copy(trigger = trigger.copy(week_days = s.sorted()))
    }

    /**
     * 存之前查一遍：回一个文案 key（说哪一格不行），null = 能存。
     * 只查「服务端一定会拒、或者存了也永远不会跑」的那几种，其余交给服务端（它回的 detail 原话照样吐司出来）。
     */
    fun problem(nowMs: Long): String? {
        if (name.isBlank()) return "duty.edit.err.name"
        when (trigger.type) {
            "at" -> if (trigger.at_ms <= nowMs) return "duty.edit.err.atPast"
            "window" -> {
                if (!DutyRules.validHHMM(trigger.start_hhmm) || !DutyRules.validHHMM(trigger.end_hhmm)) return "duty.edit.err.time"
                // 窗口不许跨零点。"HH:mm" 等长，字典序就是时间序。
                if (trigger.start_hhmm >= trigger.end_hhmm) return "duty.edit.err.window"
                if (trigger.cycle == "week" && trigger.week_days.isEmpty()) return "duty.edit.err.weekDays"
            }
            "interval" -> {
                if (!DutyRules.validHHMM(trigger.start_hhmm) || !DutyRules.validHHMM(trigger.end_hhmm)) return "duty.edit.err.time"
                // 同窗口：不跨零点。说法另起一句 —— 「窗口的结束…」放在巡视上是一句对不上号的话。
                if (trigger.start_hhmm >= trigger.end_hhmm) return "duty.edit.err.intervalRange"
                if (trigger.every_min !in 30..720) return "duty.edit.err.everyMin"
                if (trigger.cycle == "week" && trigger.week_days.isEmpty()) return "duty.edit.err.weekDays"
            }
            else -> {
                if (trigger.time_hhmm != DutyTrigger.AUTO && !DutyRules.validHHMM(trigger.time_hhmm)) return "duty.edit.err.time"
                if (trigger.cycle == "week" && trigger.week_days.isEmpty()) return "duty.edit.err.weekDays"
            }
        }
        when (action.type) {
            "say" -> if (action.brief.isBlank()) return "duty.edit.err.brief"
            "task" -> if (action.goal.isBlank()) return "duty.edit.err.goal"
            // 「跑一个流程」没选流程：存了也跑不起来。早报那条的 flow 动作没有 flow_id —— 但早报不进这个编辑器。
            "flow" -> if (action.flow_id.isEmpty()) return "duty.edit.err.flow"
            // workflow 本期置灰：编辑器里选不到，万一是别的端建的也不让原样存回去。
            else -> return "duty.edit.err.workflow"
        }
        // 服务端收 0–200；编辑器只给那几档，越界只会是别的端写坏的 —— 照样拦下来说清是哪一格。
        if (points_cap !in 0..200) return "duty.edit.err.pointsCap"
        return null
    }

    /** 回写给 `POST /duties`（自建）/ `PATCH /duties/{id}` 的整份（契约 §16.1 字段名一字不差）。 */
    val payload: JsonObject
        get() {
            val a = action.copy(brief = action.brief.trim(), goal = action.goal.trim())
            return buildJsonObject {
                put("name", name.trim())
                put("trigger", trigger.json)
                put("action", a.json)
                put("mode", mode)
                put("priority", priority.coerceIn(1, 5))
                put("cooldown_min", maxOf(0, cooldown_min))
                put("respect_quiet", respect_quiet)
                put("points_cap", points_cap.coerceIn(0, 200))
            }
        }

    companion object {
        /** 新建（「自己定」）：每个工作日 09:00 说一段话，交给秘书判断（契约 §16.1：新建缺省 judge）。 */
        fun new(today: String) = DutyDraft(
            name = "",
            trigger = DutyTrigger(type = "recur", cycle = "day", workday_only = true, first_date = today, time_hhmm = "09:00"),
            action = DutyAction(type = "say"),
            mode = "judge", priority = 3, cooldown_min = 0, respect_quiet = true, points_cap = 0,
        )

        /** 改一条已有的。老服务端没回 trigger / action 的条目照默认值起步（存的时候会整份写上）。 */
        fun of(item: DutyItem, today: String): DutyDraft {
            val d = new(today)
            var t = item.trigger ?: d.trigger
            if (t.first_date.isEmpty()) t = t.copy(first_date = today)
            return DutyDraft(
                name = item.name, trigger = t, action = item.action ?: d.action, mode = item.mode,
                priority = item.priority, cooldown_min = item.cooldown_min, respect_quiet = item.respect_quiet,
                points_cap = item.points_cap,
            )
        }
    }
}
