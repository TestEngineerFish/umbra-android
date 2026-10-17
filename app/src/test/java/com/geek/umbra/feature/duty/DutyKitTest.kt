package com.geek.umbra.feature.duty

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iOS Tools/swifttests/DutyKitTests.swift 逐条照抄（执勤 M1a 契约 §16）。
 * 断言的是**拼好的那句话**（文案出口换成一张写死的中文模板表，同 xcstrings 的中文值）。
 */
class DutyKitTest {
    /** 中文模板表 —— 与 iOS 用例里的那张逐字相同。 */
    private val zh = mapOf(
        "duty.wd.0" to "一", "duty.wd.1" to "二", "duty.wd.2" to "三", "duty.wd.3" to "四", "duty.wd.4" to "五", "duty.wd.5" to "六", "duty.wd.6" to "日",
        "duty.list.sep" to "、",
        "duty.trig.auto" to "我通常上线的时候",
        "duty.trig.at" to "%@ 一次",
        "duty.trig.atUnset" to "一次（还没定时间）",
        "duty.trig.window" to "%1\$@ %2\$@–%3\$@ %4\$@",
        "duty.trig.daysWeek" to "每周%@",
        "duty.trig.daysWorkday" to "工作日",
        "duty.trig.daysEveryday" to "每天",
        "duty.sig.firstSeen" to "我第一次打开时",
        "duty.sig.deviceOnline" to "设备上线时",
        "duty.sig.none" to "由秘书挑时候",
        "duty.trig.workdaySuffix" to "（节假日跳过）",
        "duty.trig.week" to "每周%1\$@ %2\$@",
        "duty.trig.everyNWeeks" to "每 %1\$@ 周的周%2\$@ %3\$@",
        "duty.trig.weekAny" to "每周 %@",
        "duty.trig.month" to "每月 %1\$@ 号 %2\$@",
        "duty.trig.everyNMonths" to "每 %1\$@ 个月的 %2\$@ 号 %3\$@",
        "duty.trig.year" to "每年 %1\$@ %2\$@",
        "duty.trig.everyNDays" to "每 %1\$@ 天 %2\$@",
        "duty.trig.workday" to "每个工作日 %@",
        "duty.trig.daily" to "每天 %@",
        "duty.act.say" to "发一段话：%@",
        "duty.act.sayPlain" to "发一段话",
        "duty.act.taskNow" to "建任务：%@",
        "duty.act.taskAsk" to "先问我，再建任务：%@",
        "duty.act.flow" to "跑执勤流程",
        "duty.act.workflow" to "跑电脑上的工作流（暂不可用）",
        "duty.mode.judge" to "秘书判断要不要做",
        "duty.mode.always" to "到点就做",
        "duty.prio.1" to "可有可无", "duty.prio.3" to "普通", "duty.prio.5" to "务必",
        "duty.cooldown.none" to "不限",
        "duty.cooldown.hours" to "%@ 小时",
        "duty.cooldown.minutes" to "%@ 分钟",
        "duty.tier.save" to "节省档：不再调用判断，汇总换快档",
        "duty.tier.essential" to "只保要紧：周额度剩得不多，只做重要的",
        "duty.tier.stopped" to "额度不够，执勤先出简版",
        "duty.tier.other" to "执勤在省着用额度",
        "duty.tag.manual" to "手动",
        "duty.tag.simple" to "简版",
        "duty.log.merged" to "并进 %1\$@ %2\$@",
        "duty.log.mergedAny" to "和别的条目并成一条发了",
        "duty.log.deferred" to "延后到 %@",
        "duty.why.run" to "判断：现在就发",
        "duty.why.merged" to "判断：和别的条目并成一条发",
        "duty.why.defer" to "判断：延后到 %@",
        "duty.why.deferWhy" to "判断：延后到 %1\$@ —— %2\$@",
        "duty.why.skip" to "判断：这次不发",
        "duty.why.skipWhy" to "判断：这次不发 —— %@",
        "duty.why.overall" to "总的看：%@",
        "duty.why.secs" to "%@ 秒",
        "duty.why.points" to "%@ 点",
        "duty.ctx.now" to "现在", "duty.ctx.busy" to "忙不忙", "duty.ctx.tasks" to "任务", "duty.ctx.candidates" to "这批候选",
        "duty.ctx.nudges_today" to "今天已主动说过",
        "duty.ctx.yes" to "是", "duty.ctx.no" to "否",
        "duty.ctx.count" to "%@ 条",
    )

    /** 认不出的 key 原样吐出来。 */
    private val tr = trOf(zh)

    /** 2026-10-16 08:00 上海 = 00:00 UTC（周五）。 */
    private val at8 = 1_792_108_800_000L

    @Test fun triggerCases() {
        val K = DutyKit
        assertEquals("每个工作日", "每个工作日 08:00", K.trigger(DutyTrigger(cycle = "day", workday_only = true, time_hhmm = "08:00"), tr))
        assertEquals("每天", "每天 21:00", K.trigger(DutyTrigger(cycle = "day", time_hhmm = "21:00"), tr))
        assertEquals("time_hhmm=auto 说成「我通常上线的时候」", "每个工作日 我通常上线的时候",
            K.trigger(DutyTrigger(cycle = "day", workday_only = true, time_hhmm = "auto"), tr))
        assertEquals("每周几：去重、按周一起排、顿号分隔", "每周一、三、五 17:00",
            K.trigger(DutyTrigger(cycle = "week", week_days = listOf(4, 0, 2, 2), time_hhmm = "17:00"), tr))
        assertEquals("周报：每周日 20:00", "每周日 20:00", K.trigger(DutyTrigger(cycle = "week", week_days = listOf(6), time_hhmm = "20:00"), tr))
        assertEquals("每周但没给星期几 → 按 first_date 那天（10-16 是周五）", "每周五 09:00",
            K.trigger(DutyTrigger(cycle = "week", first_date = "2026-10-16", time_hhmm = "09:00"), tr))
        assertEquals("每 2 周", "每 2 周的周一 09:00", K.trigger(DutyTrigger(cycle = "week", every_n = 2, week_days = listOf(0), time_hhmm = "09:00"), tr))
        assertEquals("每月 1 号", "每月 1 号 09:00", K.trigger(DutyTrigger(cycle = "month", first_date = "2026-11-01", time_hhmm = "09:00"), tr))
        assertEquals("每年", "每年 10-17 09:00", K.trigger(DutyTrigger(cycle = "year", first_date = "2026-10-17", time_hhmm = "09:00"), tr))
        assertEquals("每 N 天 + 只在工作日的附注", "每 3 天 09:00（节假日跳过）",
            K.trigger(DutyTrigger(cycle = "day", every_n = 3, workday_only = true, time_hhmm = "09:00"), tr))
        assertEquals("一次性", "10-16 08:30 一次", K.trigger(DutyTrigger(type = "at", at_ms = at8 + 30 * 60_000), tr))
        assertEquals("一次性没定时间不画 01-01", "一次（还没定时间）", K.trigger(DutyTrigger(type = "at", at_ms = 0), tr))
        assertEquals("窗口：需求原句「工作日 7–10 点我第一次打开时」", "工作日 07:00–10:00 我第一次打开时",
            K.trigger(DutyTrigger(type = "window", cycle = "day", workday_only = true, start_hhmm = "07:00", end_hhmm = "10:00", signal = "first_seen_today"), tr))
        assertEquals("窗口：每周几 + 不等信号", "每周六、日 09:00–12:00 由秘书挑时候",
            K.trigger(DutyTrigger(type = "window", cycle = "week", week_days = listOf(5, 6), start_hhmm = "09:00", end_hhmm = "12:00", signal = "none"), tr))
        assertEquals("窗口：设备上线", "每天 09:00–12:00 设备上线时",
            K.trigger(DutyTrigger(type = "window", start_hhmm = "09:00", end_hhmm = "12:00", signal = "device_online"), tr))
        assertEquals("老服务端没回 trigger → 空串", "", K.trigger(null, tr))
        assertEquals("10-19 是周一", 0, K.weekday("2026-10-19"))
        assertNull("认不出 → nil", K.weekday("坏的"))
    }

    @Test fun actionCases() {
        val K = DutyKit
        assertEquals("say 摘要", "发一段话：今天做了什么、明天有哪些提醒", K.action(DutyAction(type = "say", brief = "今天做了什么、明天有哪些提醒"), tr))
        assertEquals("say 没写要求", "发一段话", K.action(DutyAction(type = "say", brief = "  "), tr))
        assertEquals("task 直接建", "建任务：整理周报", K.action(DutyAction(type = "task", goal = "整理周报", confirm = "never"), tr))
        assertEquals("task 先问", "先问我，再建任务：整理周报", K.action(DutyAction(type = "task", goal = "整理周报", confirm = "ask"), tr))
        assertEquals("workflow 照实说暂不可用", "跑电脑上的工作流（暂不可用）", K.action(DutyAction(type = "workflow"), tr))
        assertEquals("太长截断加省略号", "长".repeat(40) + "…", K.clip("长".repeat(45), 40))
        assertEquals("换行压成空格", "两 行", K.clip("两\n行", 40))
        assertEquals("judge", "秘书判断要不要做", K.modeText("judge", tr))
        assertEquals("always", "到点就做", K.modeText("always", tr))
        assertEquals("优先级越界收回 5", "务必", K.priorityText(9, tr))
        assertEquals("优先级越界收回 1", "可有可无", K.priorityText(0, tr))
        assertEquals("冷却 0 = 不限", "不限", K.cooldownText(0, tr))
        assertEquals("整小时按小时说", "2 小时", K.cooldownText(120, tr))
        assertEquals("不满一小时按分钟", "30 分钟", K.cooldownText(30, tr))
        assertTrue("默认 3 次在可选里", 3 in K.nudgeOptions)
    }

    @Test fun draftCases() {
        val now = at8
        var d = DutyDraft.new("2026-10-16")
        assertEquals("新建缺省 judge（契约 §16.1）", "judge", d.mode)
        assertEquals("新建缺省每个工作日", DutyDraft.RecurKind.Workday, d.recurKind)
        assertEquals("没名字不让存", "duty.edit.err.name", d.problem(now))
        d = d.copy(name = "  晚间复盘 ")
        assertEquals("say 没写要求不让存", "duty.edit.err.brief", d.problem(now))
        d = d.copy(action = d.action.copy(brief = "今天做了什么"))
        assertNull("能存", d.problem(now))
        val p = d.payload
        assertEquals("回写字段名照契约", setOf("name", "trigger", "action", "mode", "priority", "cooldown_min", "respect_quiet", "points_cap"), p.keys)
        assertEquals("名字去掉首尾空白", "晚间复盘", p["name"].strictString())
        val t = p["trigger"] as kotlinx.serialization.json.JsonObject
        assertEquals("trigger.type", "recur", t["type"].strictString())
        assertEquals("trigger.time_hhmm", "09:00", t["time_hhmm"].strictString())
        assertEquals("trigger.workday_only", true, t["workday_only"].strictBool())
        assertNull("recur 不带 at 的键", t["at_ms"])
        assertNull("recur 不带 window 的键", t["start_hhmm"])
        val a = p["action"] as kotlinx.serialization.json.JsonObject
        assertEquals("say 只带 say 自己的键", setOf("type", "brief", "blocks", "city"), a.keys)

        d = d.setRecurKind(DutyDraft.RecurKind.Weekly)
        assertEquals("切到每周", "week", d.trigger.cycle)
        assertEquals("每周默认选 first_date 那天（周五）", listOf(4), d.trigger.week_days)
        d = d.toggleWeekday(0).toggleWeekday(4).toggleWeekday(2)
        assertEquals("勾选星期几按周一起排", listOf(0, 2), d.trigger.week_days)
        assertEquals("回写 week_days", listOf(0, 2), (d.payload["trigger"] as kotlinx.serialization.json.JsonObject)["week_days"].ints())
        d = d.toggleWeekday(0).toggleWeekday(2)
        assertEquals("每周一天都没选不让存", "duty.edit.err.weekDays", d.problem(now))
        d = d.setRecurKind(DutyDraft.RecurKind.Daily)
        assertEquals("切到每天", false, d.trigger.workday_only)
        assertEquals("非每周 week_days 回 null", JsonNull, (d.payload["trigger"] as kotlinx.serialization.json.JsonObject)["week_days"])

        d = d.copy(trigger = d.trigger.copy(time_hhmm = "auto"))
        assertNull("auto 是合法时间", d.problem(now))
        d = d.setTriggerType("window", now)
        assertEquals("切出 recur 时 auto 换回固定时刻", "09:00", d.trigger.time_hhmm)
        d = d.copy(trigger = d.trigger.copy(start_hhmm = "10:00", end_hhmm = "09:00"))
        assertEquals("窗口结束早于开始不让存", "duty.edit.err.window", d.problem(now))
        d = d.copy(trigger = d.trigger.copy(end_hhmm = "12:00"))
        assertNull("窗口能存", d.problem(now))
        assertEquals("window 只带 window 的键",
            setOf("type", "cycle", "week_days", "workday_only", "start_hhmm", "end_hhmm", "signal", "device_id"),
            (d.payload["trigger"] as kotlinx.serialization.json.JsonObject).keys)
        d = d.setTriggerType("at", now)
        assertEquals("切到一次性默认明天这个时候", now + 24 * 3_600_000L, d.trigger.at_ms)
        assertEquals("at 只带 at_ms", setOf("type", "at_ms"), (d.payload["trigger"] as kotlinx.serialization.json.JsonObject).keys)
        d = d.copy(trigger = d.trigger.copy(at_ms = now - 1))
        assertEquals("一次性定在过去不让存", "duty.edit.err.atPast", d.problem(now))
        d = d.copy(trigger = d.trigger.copy(at_ms = now + 1))

        d = d.copy(action = DutyAction(type = "task", goal = "  ", confirm = "never"))
        assertEquals("task 没写目标不让存", "duty.edit.err.goal", d.problem(now))
        d = d.copy(action = d.action.copy(goal = "整理本周记账"))
        val ta = d.payload["action"] as kotlinx.serialization.json.JsonObject
        assertEquals("task 只带 task 的键", setOf("type", "goal", "confirm"), ta.keys)
        assertEquals("confirm 原样回写", "never", ta["confirm"].strictString())
        d = d.copy(action = DutyAction(type = "workflow"))
        assertEquals("workflow 本期不让存", "duty.edit.err.workflow", d.problem(now))

        val item = DutyItem(
            id = "d9", name = "周报", preset = "weekly_report",
            trigger = DutyTrigger(cycle = "week", week_days = listOf(6), first_date = "", time_hhmm = "20:00"),
            action = DutyAction(type = "task", goal = "周报", confirm = "never"), mode = "judge", priority = 4,
            cooldown_min = 60, respect_quiet = false,
        )
        val e = DutyDraft.of(item, "2026-10-16")
        assertEquals("编辑：名字", "周报", e.name)
        assertEquals("编辑：每周", DutyDraft.RecurKind.Weekly, e.recurKind)
        assertEquals("编辑：缺 first_date 补今天", "2026-10-16", e.trigger.first_date)
        assertEquals("编辑：优先级", 4, e.priority)
        assertEquals("编辑：遵守静默", false, e.respect_quiet)
        val m = e.copy(trigger = e.trigger.copy(cycle = "month"))
        assertEquals("每月在编辑器里归「改不了的那种」", DutyDraft.RecurKind.Other, m.recurKind)
    }

    @Test fun tierCases() {
        val K = DutyKit
        val n = K.tierLine("normal", "", "照这周的节奏，周四会用完额度", tr)
        assertNull("normal 不出橙字", n.headline)
        assertEquals("normal 照样出预报", "照这周的节奏，周四会用完额度", n.detail)
        val s = K.tierLine("save", "周额度剩 25%", "周额度剩 25%", tr)
        assertEquals("节省档", "节省档：不再调用判断，汇总换快档", s.headline)
        assertEquals("原因与预报一样时只出一次", "周额度剩 25%", s.detail)
        assertEquals("停", "额度不够，执勤先出简版", K.tierLine("stopped", "", null, tr).headline)
        assertEquals("认不出的档不画 key", "执勤在省着用额度", K.tierLine("weird", "", null, tr).headline)
        assertEquals("什么都没有", DutyKit.TierLine(null, null), K.tierLine("normal", "", null, tr))
    }

    @Test fun logCases() {
        val K = DutyKit
        val main = DutyShift(id = "m", duty_id = "d1", duty_name = "早报", planned_at_ms = at8)
        val merged = DutyShift(id = "x", duty_id = "d2", duty_name = "复盘", planned_at_ms = at8, merged_into = "m")
        val lost = DutyShift(id = "y", duty_id = "d2", planned_at_ms = at8, merged_into = "zz")
        val deferred = DutyShift(id = "z", duty_id = "d2", planned_at_ms = at8, state = "deferred", outcome = null,
            reason_text = "你正在和我聊别的", defer_until_ms = at8 + 90 * 60_000)
        val nextDay = DutyShift(id = "w", duty_id = "d2", planned_at_ms = at8, defer_until_ms = at8 + 26 * 3_600_000L)
        val log = listOf(main, merged, lost, deferred, nextDay)
        assertEquals("被并写「并进 08:00 早报」", "并进 08:00 早报", K.logSub(merged, log, tr))
        assertEquals("主班次不在日志里不编一个时刻", "和别的条目并成一条发了", K.logSub(lost, log, tr))
        assertEquals("延后同一天只写时分，理由接后面", "延后到 09:30 · 你正在和我聊别的", K.logSub(deferred, log, tr))
        assertEquals("延后跨天写日期", "延后到 10-17 10:00", K.logSub(nextDay, log, tr))
        assertEquals("小标在前", "手动 · 简版", K.logSub(DutyShift(id = "q", planned_at_ms = at8, manual = true, simple = true), log, tr))
        assertNull("什么都没有 → nil", K.logSub(DutyShift(id = "q", planned_at_ms = at8), log, tr))
        assertEquals("按条目筛保持顺序", listOf("x", "y", "z", "w"), K.filter(log, "d2").map { it.id })
        assertEquals("不筛 = 全部", 5, K.filter(log, null).size)
        assertEquals("空 = 全部", 5, K.filter(log, "").size)
        assertEquals("没结案且 deferred → 延后", "duty.outcome.deferred", DutyRules.stateKey(deferred))
        assertEquals("等信号", "duty.outcome.waiting", DutyRules.stateKey(DutyShift(id = "a", planned_at_ms = 0, state = "waiting", outcome = null)))
        assertEquals("结了案按结局", "duty.outcome.ran", DutyRules.stateKey(main))
        assertEquals("blocked 有自己的字", "duty.outcome.blocked", DutyRules.outcomeKey("blocked"))
    }

    @Test fun decisionCases() {
        val d = DutyDecision.from(obj("""
        {"id": "dc1", "at_ms": $at8, "why": "早上你一般不忙",
         "context": {"now": "2026-10-16 07:59 周五", "busy": false, "tasks": [{"id": "t1"}, {"id": "t2"}],
                     "candidates": [{"shift_id": "s1", "name": "早报"}, {"shift_id": "s2", "name": "复盘"}],
                     "nudges_today": {"used": 1, "cap": 3}, "zz_extra": 7, "holiday": null},
         "result": {"run": [{"shift_id": "s1", "merge": ["s3"]}],
                    "defer": [{"shift_id": "s2", "until": "09:30", "why": "你正在和我聊别的"}],
                    "skip": [{"shift_id": "s4", "why": ""}]},
         "model_key": "decide", "ms": 820, "points": 2}
        """))
        assertEquals("decision.id", "dc1", d.id)
        val K = DutyKit
        assertEquals("run", "判断：现在就发", K.verdict(d.result, "s1", tr))
        assertEquals("被 merge 进去的那一班", "判断：和别的条目并成一条发", K.verdict(d.result, "s3", tr))
        assertEquals("defer 带理由", "判断：延后到 09:30 —— 你正在和我聊别的", K.verdict(d.result, "s2", tr))
        assertEquals("skip 理由空", "判断：这次不发", K.verdict(d.result, "s4", tr))
        assertNull("不在这次判断里 → nil", K.verdict(d.result, "nope", tr))
        assertEquals("「为什么」全部行：固定顺序、null 不画、认不出的键排后面", listOf(
            "判断：延后到 09:30 —— 你正在和我聊别的",
            "总的看：早上你一般不忙",
            "现在：2026-10-16 07:59 周五",
            "忙不忙：否",
            "任务：2 条",
            "今天已主动说过：cap：3，used：1",
            "这批候选：早报、复盘",
            "zz_extra：7",
            "decide · 0.8 秒 · 2 点",
        ), K.decisionLines(d, "s2", tr))
        val bad = DutyDecision.from(obj("""{"id": 5, "context": "坏的", "result": [1]}"""))
        assertEquals("形状不对也解得开（id 整数收成字符串）", "5", bad.id)
        assertEquals("形状不对 → 没有行，不崩", emptyList<String>(), K.decisionLines(bad, "s", tr))
    }

    @Test fun decodeCases() {
        val item = DutyItem.from(obj("""
        {"id": "d2", "name": "晚间复盘", "preset": "evening_review", "enabled": true, "paused_until_ms": 0, "params": {},
         "next_at_ms": $at8, "last": null, "created_at_ms": 1, "updated_at_ms": 2,
         "trigger": {"type": "recur", "cycle": "day", "every_n": 1, "week_days": null, "workday_only": false,
                     "first_date": "2026-10-17", "time_hhmm": "21:00", "end_date": null},
         "action": {"type": "say", "brief": "今天做了什么", "blocks": ["reminders"], "city": ""},
         "mode": "judge", "priority": 2, "cooldown_min": 0, "grace_min": 30, "max_defer_min": 90, "respect_quiet": 1,
         "created_by": "preset"}
        """))
        assertEquals("Duty.trigger", "21:00", item.trigger?.time_hhmm)
        assertEquals("week_days null → 空", emptyList<Int>(), item.trigger?.week_days)
        assertEquals("Duty.action", "今天做了什么", item.action?.brief)
        assertEquals("Duty.mode", "judge", item.mode)
        assertEquals("Duty.priority", 2, item.priority)
        assertEquals("respect_quiet 是 1 也收", true, item.respect_quiet)
        assertEquals("Duty.created_by", "preset", item.created_by)
        assertEquals("不是早报", false, item.isBrief)
        val old = DutyItem.from(obj("""{"id": "b", "preset": "morning_brief"}"""))
        assertNull("M0 老服务端没有 trigger → nil", old.trigger)
        assertEquals("缺 mode 按 always（M0 早报就是这样跑的）", "always", old.mode)
        assertEquals("早报", true, old.isBrief)

        val shift = DutyShift.from(obj("""
        {"id": "s1", "duty_id": "d1", "planned_at_ms": $at8, "state": "done", "outcome": "ran", "decision_id": "dc1",
         "defer_until_ms": 0, "merged_into": "s0", "feedback": "up", "task_id": "t9"}
        """))
        assertEquals("Shift.decision_id", "dc1", shift.decision_id)
        assertEquals("Shift.merged_into", "s0", shift.merged_into)
        assertEquals("Shift.feedback", "up", shift.feedback)
        assertEquals("Shift.task_id", "t9", shift.task_id)

        val list = DutyListDTO.from(obj("""
        {"settings": {"daily_nudges": 5}, "tier": "essential", "tier_reason": "周额度剩 12%",
         "forecast": {"runs_out_at_ms": $at8, "note": "周四会用完"}, "items": []}
        """))
        assertEquals("tier 四档", "essential", list.tier)
        assertEquals("settings.daily_nudges", 5, list.settings.daily_nudges)
        assertEquals("forecast.runs_out_at_ms", at8, list.forecast?.runs_out_at_ms)
        assertEquals("forecast.note", "周四会用完", list.forecast?.note)
        val none = DutyListDTO.from(obj("""{"forecast": {"runs_out_at_ms": null, "note": ""}, "settings": {"dailyNudges": 2}}"""))
        assertNull("runs_out_at_ms null = 不会用完", none.forecast?.runs_out_at_ms)
        assertEquals("设置键原名 dailyNudges 也收", 2, none.settings.daily_nudges)
        assertEquals("缺键按契约默认 3", 3, DutyListDTO.from(obj("{}")).settings.daily_nudges)

        val presets = DutyPresetsDTO.from(obj("""
        {"items": [{"preset": "evening_review", "name": "晚间复盘", "desc": "每天 21:00",
                    "trigger": {"type": "recur", "cycle": "day", "time_hhmm": "21:00"}, "action": {"type": "say"},
                    "mode": "judge", "priority": 2},
                   {"name": "没有 preset 的坏项"}]}
        """))
        assertEquals("预设：坏项丢掉", listOf("evening_review"), presets.items.map { it.preset })
        assertEquals("预设：priority", 2, presets.items.first().priority)

        val follows = DutyFollowsDTO.from(obj("""
        {"items": [{"id": "f1", "topic": "某手机发布", "query": "某手机 发布会", "kind": "news", "ref_id": "", "importance": 4,
                    "state": "quiet", "last_checked_ms": 1, "last_new_ms": 2, "created_at_ms": 3},
                   {"id": "f2", "topic": "旧的", "state": "ended", "importance": 5, "created_at_ms": 9},
                   {"id": "f3", "topic": "新的", "importance": 9, "created_at_ms": 5}]}
        """))
        assertEquals("关注：三条", 3, follows.items.size)
        assertEquals("重要度越界收回 5", 5, follows.items.last().importance)
        assertEquals("进行中在前、结束的在最后", listOf("f3", "f1", "f2"), DutyKit.sortFollows(follows.items).map { it.id })
        assertEquals("quiet", "duty.follow.state.quiet", DutyKit.followStateKey("quiet"))
        assertEquals("认不出按关注中", "duty.follow.state.active", DutyKit.followStateKey("weird"))
        val fb = DutyFeedbackDTO.from(obj("""{"ok": true, "duty": {"id": "d2", "enabled": false}}"""))
        assertEquals("反馈回包带改完的 Duty", false, fb.duty?.enabled)
        assertEquals("{} 也算 ok", true, DutyOKDTO.from(obj("{}")).ok)
    }

    @Test fun metaCases() {
        val m = DutyMeta.parseMeta(json("""
        {"duty": {"duty_id": "d1", "shift_id": "s1", "name": "早报", "kind": "brief", "planned_at_ms": $at8,
                  "forecast_note": "照这周的节奏，周四会用完额度",
          "items": [
            {"id": "c1", "section": "follow_check", "title": "「某话题」两周没新消息了，还关注吗？", "summary": "", "follow_id": "f1"},
            {"id": "u1", "section": "follow", "title": "某手机定档", "summary": "下周发布", "topic_key": "news:phone",
             "follow_id": "f1", "is_update": true, "prev": {"message_id": 41, "item_id": "n3"}},
            {"id": "n1", "section": "news", "title": "新闻", "summary": "一句", "topic_key": "news:x", "follow_id": null,
             "is_update": false, "prev": {"message_id": "坏", "item_id": "n0"}},
            {"id": "w1", "section": "weather", "title": "多云", "summary": ""}
          ]}}
        """))
        assertEquals("早报 meta.forecast_note", "照这周的节奏，周四会用完额度", m?.forecastNote)
        assertEquals("「你关注的」在新闻前，「还关注吗」永远最后", listOf("weather", "follow", "news", "follow_check"), m?.sections?.map { it.section })
        val u = m?.items?.first { it.id == "u1" }
        assertEquals("item.follow_id", "f1", u?.followId)
        assertEquals("item.is_update", true, u?.isUpdate)
        assertEquals("item.prev", DutyMeta.Item.Prev(41, "n3"), u?.prev)
        assertEquals("item.topic_key", "news:phone", u?.topicKey)
        val n = m?.items?.first { it.id == "n1" }
        assertNull("follow_id null → nil", n?.followId)
        assertNull("prev 指不回去（message_id 认不出）→ 当没有", n?.prev)

        val notice = DutyNotice.parseMeta(json("""
        {"duty_notice": {"kind": "quota_out", "resume_at_ms": $at8, "reset_card": {"item_id": "12", "name": "周重置卡"}}}
        """))
        assertEquals("duty_notice.kind", true, notice?.isQuotaOut)
        assertEquals("duty_notice.resume_at_ms", at8, notice?.resumeAtMs)
        assertEquals("重置卡：字符串形的整数 id 也收", DutyNotice.Card(12, "周重置卡"), notice?.resetCard)
        val noCard = DutyNotice.parseMeta(json("""{"duty_notice": {"kind": "quota_out", "reset_card": null}}"""))
        assertNull("没卡 → 不画按钮", noCard?.resetCard)
        val badCard = DutyNotice.parseMeta(json("""{"duty_notice": {"kind": "quota_out", "reset_card": {"item_id": "x"}}}"""))
        assertNull("卡 id 认不出 → 不画按钮（点了也用不成）", badCard?.resetCard)
        assertNull("没有 duty_notice → nil", DutyNotice.parseMeta(json("""{"duty": {}}""")))
        assertEquals("卡上一条的键", "s1/n1", DutyKit.cardKey("s1", "n1"))
        // 回写 JSON 里的字面：不让 JsonPrimitive 的类型漂（字符串仍是字符串）
        assertEquals(JsonPrimitive("say"), DutyAction().json["type"])
    }
}
