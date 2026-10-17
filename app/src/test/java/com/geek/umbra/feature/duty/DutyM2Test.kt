package com.geek.umbra.feature.duty

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iOS Tools/swifttests/DutyM2Tests.swift 逐条照抄（执勤 M2：巡视、点数上限、秘书的提议、早报卡修补）。
 */
class DutyM2Test {
    private val zh = mapOf(
        "duty.wd.0" to "一", "duty.wd.1" to "二", "duty.wd.2" to "三", "duty.wd.3" to "四", "duty.wd.4" to "五", "duty.wd.5" to "六", "duty.wd.6" to "日",
        "duty.list.sep" to "、",
        "duty.trig.daysWeek" to "每周%@",
        "duty.trig.daysWorkday" to "工作日",
        "duty.trig.daysEveryday" to "每天",
        "duty.trig.workday" to "每个工作日 %@",
        "duty.trig.interval" to "%1\$@ %2\$@–%3\$@ %4\$@",
        "duty.trig.everyH" to "每 %@ 小时",
        "duty.trig.everyM" to "每 %@ 分钟",
        "duty.cap.n" to "%@ 点",
        "duty.cap.none" to "不单独设限",
        "duty.block.weather" to "天气",
        "duty.block.reminders" to "今天的提醒",
        "duty.block.tasks" to "任务",
        "duty.block.remindersSoon" to "两小时内的提醒",
        "duty.block.followsHot" to "重要关注的新动向",
        "duty.pr.detailStop" to "停掉这一条（随时可以在执勤表里再打开）",
        "duty.pr.detailArrow" to "%1\$@ → %2\$@",
        "duty.pr.detailBlock" to "早报的块改成：%@",
        "duty.pr.detailFollow" to "关注「%1\$@」，检索：%2\$@",
        "duty.pr.detailFollowTopic" to "关注「%@」",
        "duty.pr.detailPoints" to "每次约 %@ 点",
    )
    private val tr = trOf(zh)
    private val at8 = 1_792_108_800_000L

    private fun patrol(extra: String = ""): DutyTrigger =
        DutyTrigger.from(obj("""{"type": "interval", "every_min": 120, "start_hhmm": "09:00", "end_hhmm": "21:00"$extra}"""))

    @Test fun intervalTextCases() {
        val K = DutyKit
        assertEquals("巡视预设原句", "每天 09:00–21:00 每 2 小时", K.trigger(patrol(""", "workday_only": false, "week_days": null"""), tr))
        assertEquals("不整小时写分钟，不写 1.5 小时", "每天 08:00–20:00 每 90 分钟",
            K.trigger(DutyTrigger(type = "interval", start_hhmm = "08:00", end_hhmm = "20:00", every_min = 90), tr))
        assertEquals("工作日先于星期几（同 PC）", "工作日 09:00–18:00 每 1 小时",
            K.trigger(DutyTrigger(type = "interval", week_days = listOf(0, 1, 2), workday_only = true, start_hhmm = "09:00", end_hhmm = "18:00", every_min = 60), tr))
        assertEquals("挑了几天：按周一起排、顿号分隔；cycle 是 day 也照 week_days 说", "每周一、三 09:00–12:00 每 30 分钟",
            K.trigger(DutyTrigger(type = "interval", week_days = listOf(2, 0), start_hhmm = "09:00", end_hhmm = "12:00", every_min = 30), tr))
        assertEquals("挑满 7 天 = 每天", "每天 09:00–21:00 每 12 小时",
            K.trigger(DutyTrigger(type = "interval", week_days = (0..6).toList(), start_hhmm = "09:00", end_hhmm = "21:00", every_min = 720), tr))
        assertEquals("180 分钟 → 3 小时", "每 3 小时", K.everyText(180, tr))
        assertEquals("0 不当整小时（不说成「每 0 小时」）", "每 0 分钟", K.everyText(0, tr))
        assertEquals("间隔可选值同 PC", listOf(30, 60, 90, 120, 180, 240, 360, 720), K.intervalOptions)
        assertEquals("点数上限 0", "不单独设限", K.pointsCapText(0, tr))
        assertEquals("点数上限 6", "6 点", K.pointsCapText(6, tr))
        assertEquals("点数可选值同 PC", listOf(0, 3, 6, 10, 20, 50), K.pointsCapOptions)
        assertTrue("编辑器有 M2 两块", "reminders_soon" in K.sayBlocks && "follows_hot" in K.sayBlocks)
        assertEquals("块 key：两小时内的提醒", "duty.block.remindersSoon", K.blockKey("reminders_soon"))
        assertEquals("块 key：重要关注的新动向", "duty.block.followsHot", K.blockKey("follows_hot"))
        assertEquals("块 key：老三块不变", "duty.block.weather", K.blockKey("weather"))
    }

    @Test fun intervalRoundTripCases() {
        val wire = patrol(""", "cycle": "day", "week_days": [4, 0], "workday_only": false, "first_date": "2026-10-16"""")
        assertEquals("线上挑了几天 → 端上记成 week（编辑器「哪些天」那一格用）", "week", wire.cycle)
        assertEquals("week_days 原样", listOf(4, 0), wire.week_days)
        val out = wire.json
        assertNull("回写不带 cycle（线上不认 week）", out["cycle"])
        assertEquals("回写 week_days 排好序", listOf(0, 4), out["week_days"].ints())
        assertEquals("回写 every_min", 120L, out["every_min"].strictLong())
        assertEquals("回写 first_date", "2026-10-16", out["first_date"].strictString())
        assertEquals("没有 end_date 回 null", JsonNull, out["end_date"])
        assertTrue("巡视不带别的种类的键", out["signal"] == null && out["time_hhmm"] == null && out["at_ms"] == null)
        assertEquals("巡视回写的键照 README",
            setOf("type", "every_min", "start_hhmm", "end_hhmm", "workday_only", "week_days", "first_date", "end_date"), out.keys)

        val wk = patrol(""", "week_days": [0, 1], "workday_only": true""")
        assertEquals("只在工作日：不算挑了几天", "day", wk.cycle)
        assertEquals("只在工作日 → week_days 回 null", JsonNull, wk.json["week_days"])
        val all7 = patrol(""", "week_days": [0, 1, 2, 3, 4, 5, 6]""")
        assertEquals("挑满 7 天 = 每天", "day", all7.cycle)

        val bare = DutyTrigger.from(obj("""{"type": "interval"}"""))
        assertEquals("巡视缺起点 → 09:00（不是窗口的 07:00）", "09:00", bare.start_hhmm)
        assertEquals("巡视缺终点 → 21:00", "21:00", bare.end_hhmm)
        assertEquals("缺间隔 → 120", 120, bare.every_min)
        val odd = DutyTrigger.from(obj("""{"type": "interval", "every_min": 10}"""))
        assertEquals("越界的间隔原样留着（交给校验指名道姓地说）", 10, odd.every_min)
        val win = DutyTrigger.from(obj("""{"type": "window"}"""))
        assertEquals("窗口的缺省不受巡视影响", "07:00", win.start_hhmm)

        val item = DutyItem.from(obj("""{"id": "d1", "points_cap": 6, "proposal_id": "p9", "trigger": {"type": "interval"}}"""))
        assertEquals("Duty.points_cap", 6, item.points_cap)
        assertEquals("Duty.proposal_id", "p9", item.proposal_id)
        assertEquals("老服务端没有 points_cap → 0", 0, DutyItem.from(obj("""{"id": "d1"}""")).points_cap)
        val presets = DutyPresetsDTO.from(obj("""{"items": [{"preset": "patrol", "name": "巡视", "points_cap": 6, "mode": "always", "trigger": {"type": "interval", "every_min": 120}}]}"""))
        assertEquals("预设 points_cap", 6, presets.items.first().points_cap)
        assertEquals("巡视预设的触发", "interval", presets.items.first().trigger?.type)
    }

    @Test fun draftCases() {
        val now = at8
        var d = DutyDraft.new("2026-10-16")
        d = d.copy(name = "巡视", action = d.action.copy(brief = "看看有没有要紧的"))
        assertEquals("新建点数上限缺省 0", 0, d.points_cap)
        d = d.setTriggerType("interval", now)
        assertEquals("切到巡视", "interval", d.trigger.type)
        assertEquals("切到巡视给 09:00–21:00", listOf("09:00", "21:00"), listOf(d.trigger.start_hhmm, d.trigger.end_hhmm))
        assertEquals("切到巡视给每 2 小时", 120, d.trigger.every_min)
        assertNull("巡视缺省能存", d.problem(now))
        d = d.copy(trigger = d.trigger.copy(start_hhmm = "10:00"))
        d = d.setTriggerType("interval", now)
        assertEquals("已经是巡视时再选一次不冲掉起点", "10:00", d.trigger.start_hhmm)
        d = d.copy(trigger = d.trigger.copy(end_hhmm = "10:00"))
        assertEquals("起点不早于终点不让存", "duty.edit.err.intervalRange", d.problem(now))
        d = d.copy(trigger = d.trigger.copy(end_hhmm = "21:00", every_min = 20))
        assertEquals("间隔小于 30 不让存", "duty.edit.err.everyMin", d.problem(now))
        d = d.copy(trigger = d.trigger.copy(every_min = 721))
        assertEquals("间隔大于 720 不让存", "duty.edit.err.everyMin", d.problem(now))
        d = d.copy(trigger = d.trigger.copy(every_min = 720))
        assertNull("720 能存", d.problem(now))
        d = d.copy(trigger = d.trigger.copy(cycle = "week", week_days = emptyList()))
        assertEquals("巡视选了每周却一天没挑不让存", "duty.edit.err.weekDays", d.problem(now))
        d = d.copy(trigger = d.trigger.copy(cycle = "day", start_hhmm = "9点"))
        assertEquals("起点认不出", "duty.edit.err.time", d.problem(now))
        d = d.copy(trigger = d.trigger.copy(start_hhmm = "09:00"), points_cap = 201)
        assertEquals("点数上限大于 200 不让存", "duty.edit.err.pointsCap", d.problem(now))
        d = d.copy(points_cap = -1)
        assertEquals("点数上限负数不让存", "duty.edit.err.pointsCap", d.problem(now))
        d = d.copy(points_cap = 6)
        assertNull("点数上限 6 能存", d.problem(now))
        assertEquals("回写 points_cap", 6L, d.payload["points_cap"].strictLong())
        assertEquals("回写的触发是巡视", "interval", (d.payload["trigger"] as JsonObject)["type"].strictString())

        val item = DutyItem(id = "d1", name = "巡视", preset = "patrol", trigger = DutyTrigger(type = "interval", every_min = 60),
            action = DutyAction(type = "say", brief = "看看"), mode = "always", points_cap = 10)
        val back = DutyDraft.of(item, "2026-10-16")
        assertEquals("改一条：点数上限带进草稿", 10, back.points_cap)
        assertEquals("改一条：间隔带进草稿", 60, back.trigger.every_min)
    }

    private fun proposal(kind: String, draft: String, est: Int = 0): DutyProposal =
        DutyProposal.from(obj("""{"id": "p1", "kind": "$kind", "title": "t", "why": "w", "est_points": $est, "draft": $draft}"""))

    @Test fun proposalCases() {
        val K = DutyKit
        fun detail(k: String, d: String, est: Int = 0) = K.proposalDetail(proposal(k, d, est), tr)
        assertEquals("tune 改时间", "08:00 → 09:10", detail("tune", """{"patch": {"time": "09:10"}, "from": "08:00", "to": "09:10"}"""))
        assertEquals("tune 停掉先于时刻", "停掉这一条（随时可以在执勤表里再打开）",
            detail("tune", """{"patch": {"enabled": false}, "from": "08:00", "to": "09:10"}"""))
        assertEquals("tune 什么都没给 → 不画细节", "", detail("tune", """{"patch": {}}"""))
        assertEquals("quiet", "22:30–07:30 → 23:30–07:00",
            detail("quiet", """{"settings": {"quiet_start": "23:30", "quiet_end": "07:00"}, "from": "22:30–07:30"}"""))
        assertEquals("quiet 没有原值：只说改成几点，不悬一个箭头", "23:30–07:00",
            detail("quiet", """{"settings": {"quiet_start": "23:30", "quiet_end": "07:00"}}"""))
        assertEquals("block：认得的说名字，认不出的原样", "早报的块改成：天气、今天的提醒、holiday",
            detail("block", """{"patch": {"params": {"blocks": ["weather", "reminders", "holiday"]}}}"""))
        assertEquals("follow", "关注「某手机」，检索：某手机 发布", detail("follow", """{"topic": "某手机", "query": "某手机 发布"}"""))
        assertEquals("follow 没检索式不留半句", "关注「某手机」", detail("follow", """{"topic": "某手机", "query": ""}"""))
        assertEquals("new：触发说人话 · 要求", "每个工作日 18:00 · 今天做了什么",
            detail("new", """{"name": "x", "trigger": {"type": "recur", "cycle": "day", "workday_only": true, "time_hhmm": "18:00"}, "action": {"type": "say", "brief": "今天做了什么"}}"""))
        assertEquals("new 没有触发：不留空的一段", "今天做了什么", detail("new", """{"name": "x", "action": {"type": "say", "brief": "今天做了什么"}}"""))
        assertEquals("flow 步骤 + 点数", "① 天气  ② 提醒  ③ 汇总 · 每次约 4 点",
            detail("flow", """{"name": "f", "time_hhmm": "08:30", "nodes": ["天气", "提醒", "汇总"]}""", est = 4))
        assertEquals("flow 没有步骤只说点数", "每次约 2 点", detail("flow", """{"nodes": []}""", est = 2))
        assertEquals("flow 定时钮的时刻", "08:30", K.proposalFlowTime(proposal("flow", """{"time_hhmm": "08:30"}""")))
        assertEquals("flow 时刻认不出 → 08:00", "08:00", K.proposalFlowTime(proposal("flow", """{"time_hhmm": "坏"}""")))
        assertEquals("种类小标 key", "duty.pr.kind.quiet", K.proposalKindKey("quiet"))

        val list = DutyProposalsDTO.from(obj("""
        {"items": [
          {"id": "p1", "kind": "tune", "title": "早报晚一点", "why": "你最近都 9 点后才看", "duty_id": "d1",
           "draft": {"from": "08:00", "to": "09:10"}, "est_points": 0, "state": "pending", "result": null,
           "created_at_ms": 1, "decided_at_ms": null},
          {"id": "p2", "kind": "teleport", "title": "认不出的种类"},
          {"id": "", "kind": "new", "title": "没 id"},
          {"id": 7, "kind": "follow", "title": "数字 id 也收", "draft": {"topic": "x"}}
        ]}
        """))
        assertEquals("认不出的种类、没 id 的丢掉；数字 id 收成字符串", listOf("p1", "7"), list.items.map { it.id })
        assertEquals("duty_id", "d1", list.items.first().duty_id)
        assertNull("decided_at_ms null → nil", list.items.first().decided_at_ms)
        assertEquals("items 不是数组 → 空，不让整区解不开", 0, DutyProposalsDTO.from(obj("""{"items": "坏"}""")).items.size)
        assertEquals("scan 新增条数", 2, DutyProposalScanDTO.from(obj("""{"ok": true, "added": [{"id": "a"}, {"id": "b"}]}""")).added)
        assertEquals("scan 没有 added → 0", 0, DutyProposalScanDTO.from(obj("""{"ok": true}""")).added)
    }

    @Test fun metaCases() {
        val run = DutyMeta.parseMeta(json("""
        {"duty": {"duty_id": "", "shift_id": "", "run_id": "fr-1", "flow_id": "f1", "name": "我的流程", "kind": "brief",
          "items": [{"id": "n1", "section": "news", "title": "新闻", "summary": "一句", "feedback": "not_interested"},
                    {"id": "n2", "section": "news", "title": "另一条", "summary": ""}]}}
        """))
        assertEquals("meta.duty.run_id", "fr-1", run?.runId)
        assertEquals("一键跑的卡：来源 id 用 run_id", "fr-1", run?.sourceId)
        assertEquals("items[].feedback = not_interested", true, run?.items?.first()?.notInterested)
        assertEquals("没写回的那条照旧", false, run?.items?.last()?.notInterested)
        assertEquals("没有 meta.duty.feedback → 没给过", "", run?.feedbackVerdict)

        val shift = DutyMeta.parseMeta(json("""
        {"duty": {"shift_id": "s1", "run_id": "fr-9", "kind": "brief", "feedback": {"verdict": "mute", "mute": "week"}, "items": []}}
        """))
        assertEquals("有班次时来源 id 用 shift_id", "s1", shift?.sourceId)
        assertEquals("meta.duty.feedback.verdict", "mute", shift?.feedbackVerdict)
        assertEquals("meta.duty.feedback.mute", "week", shift?.feedbackMute)
        val up = DutyMeta.parseMeta(json("""{"duty": {"shift_id": "s1", "feedback": {"verdict": "up", "mute": null}}}"""))
        assertEquals("verdict up 不带 mute", listOf("up", ""), listOf(up?.feedbackVerdict, up?.feedbackMute))
        val odd = DutyMeta.parseMeta(json("""{"duty": {"shift_id": "s1", "feedback": {"verdict": "meh"}}}"""))
        assertEquals("认不出的 verdict 当没给过（照旧给钮）", "", odd?.feedbackVerdict)
        val none = DutyMeta.parseMeta(json("""{"duty": {"kind": "brief"}}"""))
        assertEquals("班次 id、运行 id 都没有 → 空（一颗都不画）", "", none?.sourceId)
    }
}
