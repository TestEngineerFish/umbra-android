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
        "duty.pr.detailFollow" to "关注「%1\$@」· 检索：%2\$@",
        "duty.pr.detailFollowTopic" to "关注「%@」· 检索就用这个词",
        "duty.pr.detailPoints" to "每次约 %@ 点",
        "duty.pr.diffTime" to "时间",
        "duty.pr.diffTimeOf" to "%@时间",
        "duty.pr.diffQuiet" to "静默时段",
        "duty.pr.quietTo" to "静默时段改成 %@",
        "duty.pr.flowNote" to "定时：每个工作日 %@",
        "duty.pr.done" to "改好了",
        "duty.pr.doneStop" to "停掉了「%@」",
        "duty.pr.doneStopPlain" to "停掉了",
        "duty.pr.doneTime" to "%1\$@改到 %2\$@ 了",
        "duty.pr.doneTimePlain" to "改到 %@ 了",
        "duty.pr.doneQuiet" to "静默时段改到 %@ 了",
        "duty.pr.doneBlock" to "早报改成说：%@",
        "duty.pr.doneFollow" to "关注了「%@」",
        "duty.pr.doneFlow" to "建好了例程「%1\$@」，每个工作日 %2\$@ 跑",
        "duty.pr.doneFlowOnly" to "建好了例程「%@」，没有定时",
        "duty.pr.doneNew" to "加上了：%@",
        "duty.today.at" to "今天 %@",
        "duty.budgetToday.value" to "%1\$@ / %2\$@ 点",
        "duty.tier.saveBudget" to "今天的执勤预算用过六成了。先省着用：不再逐条判断、只做最要紧的，可有可无的条目先停，明天预算重算后恢复。",
        "duty.tier.saveWeek" to "周额度剩不到三成。先省着用：不再逐条判断、只做最要紧的，可有可无的条目先停，额度回到三成以上就恢复。",
        "duty.tier.essentialText" to "周额度剩不到 15%。只跑优先级 4 以上的条目，其余跳过，额度回到 15% 以上就恢复。",
        "duty.tier.stopBudget" to "今天的执勤预算用完了，执勤停到明天 00:00。早报照发，不花额度的部分不受影响。",
        "duty.tier.stopQuota" to "周额度用满了，执勤停到 %@。早报照发，不花额度的部分不受影响。",
        "duty.tier.stopQuotaPlain" to "周额度用满了，执勤停了。早报照发，不花额度的部分不受影响。",
        "duty.tier.stopMember" to "没有会员，执勤停了。开会员后接着跑。",
        "duty.tier.untilDay" to "%1\$@月%2\$@日 %3\$@",
        "duty.items.last" to "上一次：%@",
        "duty.items.lastFailed" to "%1\$@没跑成：%2\$@",
        "duty.items.lastFailedPlain" to "%@没跑成",
        "duty.outcome.ran" to "做了",
        "duty.outcome.skipped" to "跳过",
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
        fun change(k: String, d: String, est: Int = 0, name: String = "", blocks: List<String>? = null) =
            K.proposalChange(proposal(k, d, est), tr, dutyName = name, currentBlocks = blocks)
        // ③ 变化块（批次 261 §二 3）：三种写法各自成形，删除线 / 600 / 胶囊交给画法 —— 判据只回结构。
        assertEquals("tune 改时间 → 前后对比，标签带条目名", DutyKit.ProposalChange.Diff("早报时间", "08:00", "09:10"),
            change("tune", """{"patch": {"time": "09:10"}, "from": "08:00", "to": "09:10"}""", name = "早报"))
        assertEquals("tune 找不到条目名 → 标签只写「时间」", DutyKit.ProposalChange.Diff("时间", "08:00", "09:10"),
            change("tune", """{"patch": {"time": "09:10"}, "from": "08:00", "to": "09:10"}"""))
        assertEquals("tune 停掉先于时刻", DutyKit.ProposalChange.Text("停掉这一条（随时可以在执勤表里再打开）"),
            change("tune", """{"patch": {"enabled": false}, "from": "08:00", "to": "09:10"}"""))
        assertEquals("tune 什么都没给 → 不画变化块", DutyKit.ProposalChange.None, change("tune", """{"patch": {}}"""))
        assertEquals("quiet 前后对比", DutyKit.ProposalChange.Diff("静默时段", "22:30–07:30", "23:30–07:00"),
            change("quiet", """{"settings": {"quiet_start": "23:30", "quiet_end": "07:00"}, "from": "22:30–07:30"}"""))
        assertEquals("quiet 没有原值：一句话，不画悬空的删除线", DutyKit.ProposalChange.Text("静默时段改成 23:30–07:00"),
            change("quiet", """{"settings": {"quiet_start": "23:30", "quiet_end": "07:00"}}"""))
        assertEquals("block：节点链，早报原来没有的那块标「+」，认不出的原样",
            DutyKit.ProposalChange.Nodes(listOf(DutyKit.ProposalNode("天气"), DutyKit.ProposalNode("今天的提醒", true), DutyKit.ProposalNode("holiday", true)), ""),
            change("block", """{"patch": {"params": {"blocks": ["weather", "reminders", "holiday"]}}}""", blocks = listOf("weather")))
        assertEquals("block 不知道早报现在说哪几块 → 一颗都不标「+」",
            DutyKit.ProposalChange.Nodes(listOf(DutyKit.ProposalNode("天气")), ""),
            change("block", """{"patch": {"params": {"blocks": ["weather"]}}}"""))
        assertEquals("follow 有检索式（批次 261 §三 4）", DutyKit.ProposalChange.Text("关注「9 号线」· 检索：杭州地铁 9 号线"),
            change("follow", """{"topic": "9 号线", "query": "杭州地铁 9 号线"}"""))
        assertEquals("follow 没检索式：不和标题重复", DutyKit.ProposalChange.Text("关注「9 号线」· 检索就用这个词"),
            change("follow", """{"topic": "9 号线", "query": ""}"""))
        assertEquals("new：触发说人话 · 要求", DutyKit.ProposalChange.Text("每个工作日 18:00 · 今天做了什么"),
            change("new", """{"name": "x", "trigger": {"type": "recur", "cycle": "day", "workday_only": true, "time_hhmm": "18:00"}, "action": {"type": "say", "brief": "今天做了什么"}}"""))
        assertEquals("new 什么都没有 → 不画", DutyKit.ProposalChange.None, change("new", """{"name": "x"}"""))
        assertEquals("flow：节点链（不用 ①②③）+ 点数",
            DutyKit.ProposalChange.Nodes(listOf(DutyKit.ProposalNode("天气"), DutyKit.ProposalNode("提醒"), DutyKit.ProposalNode("汇总")), "每次约 4 点"),
            change("flow", """{"name": "f", "time_hhmm": "08:30", "nodes": ["天气", "提醒", "汇总"]}""", est = 4))
        assertEquals("flow 没有步骤只说点数", DutyKit.ProposalChange.Text("每次约 2 点"), change("flow", """{"nodes": []}""", est = 2))

        // ④ 主钮按种类写动词（「好」作废）。
        fun primary(k: String, d: String = "{}") = K.proposalPrimaryKey(proposal(k, d))
        assertEquals("主钮：加上 / 照改 / 停掉 / 照改 / 加上 / 建并定时 / 关注",
            listOf("duty.pr.yes.add", "duty.pr.yes.apply", "duty.pr.yes.stop", "duty.pr.yes.apply", "duty.pr.yes.add",
                "duty.pr.flowSchedule", "duty.pr.yes.follow"),
            listOf(primary("new"), primary("tune", """{"patch": {"time": "09:10"}}"""), primary("tune", """{"patch": {"enabled": false}}"""),
                primary("quiet"), primary("block"), primary("flow"), primary("follow")))
        assertEquals("做成例程：时间不进钮，写在卡上一行", "定时：每个工作日 08:00", K.proposalNote(proposal("flow", """{"time_hhmm": "08:00"}"""), tr))
        assertNull("别的种类没有定时那一行", K.proposalNote(proposal("new", "{}"), tr))

        // 吐司写具体结果（不写「照这条改好了」）。
        fun done(k: String, d: String, schedule: Boolean = true, name: String = "") = K.proposalDoneText(proposal(k, d), schedule, tr, dutyName = name)
        assertEquals("吐司 tune", "早报改到 09:10 了", done("tune", """{"patch": {"time": "09:10"}, "from": "08:00", "to": "09:10"}""", name = "早报"))
        assertEquals("吐司 tune 停掉", "停掉了「巡视」", done("tune", """{"patch": {"enabled": false}}""", name = "巡视"))
        assertEquals("吐司 quiet", "静默时段改到 23:00–07:00 了", done("quiet", """{"settings": {"quiet_start": "23:00", "quiet_end": "07:00"}}"""))
        assertEquals("吐司 建并定时（批次 263 字面）", "建好了例程「早上三件事」，每个工作日 08:00 跑",
            done("flow", """{"name": "早上三件事", "time_hhmm": "08:00"}"""))
        assertEquals("吐司 只建", "建好了例程「早上三件事」，没有定时", done("flow", """{"name": "早上三件事"}""", schedule = false))
        assertEquals("吐司 new", "加上了：每个工作日 07:50 说天气",
            done("new", """{"name": "说天气", "trigger": {"type": "recur", "cycle": "day", "workday_only": true, "time_hhmm": "07:50"}}"""))
        assertEquals("吐司 follow", "关注了「9 号线」", done("follow", """{"topic": "9 号线"}"""))

        // 超过 3 条折叠成「还有 N 条」。
        assertEquals("5 条摊开 3 张", 3, K.proposalShown(5, false).shown)
        assertEquals("5 条折起 2 张", 2, K.proposalShown(5, false).more)
        assertEquals("正好 3 条不折", 0, K.proposalShown(3, false).more)
        assertEquals("点过「还有 N 条」全摊开", 5, K.proposalShown(5, true).shown)
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

    private fun list(j: String) = DutyListDTO.from(obj(j))

    /** 批次 261 §四：页头状态、暂停到几时、上一次失败说原因、日志结局图标、聊天早报卡末尾拆两行。 */
    @Test fun pageCases() {
        val K = DutyKit
        val now = at8 // 某天 08:00（上海）
        val on = DutySettings(enabled = true, consented = true)
        assertEquals("开着、没暂停 → 执勤中", DutyKit.HeadState.On, K.headState(on, now))
        assertEquals("没确认过 → 没开（同总开关的画法）", DutyKit.HeadState.Off, K.headState(DutySettings(enabled = true, consented = false), now))
        assertEquals("暂停到一个时刻", DutyKit.HeadState.PausedUntil(now + 3_600_000), K.headState(on.copy(paused_until_ms = now + 3_600_000), now))
        assertEquals("十年之外 = 到你恢复", DutyKit.HeadState.PausedForever,
            K.headState(on.copy(paused_until_ms = now + DutyRules.FOREVER_THRESHOLD_MS + 1), now))
        assertEquals("暂停到点了 → 执勤中", DutyKit.HeadState.On, K.headState(on.copy(paused_until_ms = now - 1), now))
        assertEquals("同一天写「今天 HH:mm」", "今天 18:00", K.whenText(now + 10 * 3_600_000, now, tr))
        assertEquals("别的天写月-日 时:分", DutyRules.monthDayTime(now + 25 * 3_600_000), K.whenText(now + 25 * 3_600_000, now, tr))

        val failed = DutyShift(id = "s1", planned_at_ms = now, outcome = "failed", reason_text = "这条执勤要跑的流程已经删了")
        assertEquals("失败说原因", "今天 08:00没跑成：这条执勤要跑的流程已经删了", K.lastLine(failed, now, tr).text)
        assertEquals("失败那一行标红", true, K.lastLine(failed, now, tr).failed)
        assertEquals("失败没给原因：不留冒号", "今天 08:00没跑成", K.lastLine(DutyShift(id = "s2", planned_at_ms = now, outcome = "failed"), now, tr).text)
        assertEquals("没失败照旧", "上一次：做了", K.lastLine(DutyShift(id = "s3", planned_at_ms = now, outcome = "ran"), now, tr).text)

        assertEquals("说了 / 建了任务 = 对勾", "check", K.outcomeIcon(DutyShift(id = "a", planned_at_ms = now, outcome = "ran")))
        assertEquals("秘书判断后没事 = 横线圈", "minusCircle", K.outcomeIcon(DutyShift(id = "b", planned_at_ms = now, outcome = "skipped", decision_id = "d1")))
        assertEquals("规矩挡下的跳过 = 钟", "clock", K.outcomeIcon(DutyShift(id = "c", planned_at_ms = now, outcome = "skipped")))
        assertEquals("没跑成 = 圆叉", "xCircle", K.outcomeIcon(DutyShift(id = "d", planned_at_ms = now, outcome = "failed")))
        assertNull("没结案不画图标", K.outcomeIcon(DutyShift(id = "e", planned_at_ms = now, state = "deferred", outcome = null)))
        assertEquals("预算用完的「停」带调高预算", true, K.tierOffersBudget("stopped", DutyTierCode.Budget))
        assertEquals("没会员的「停」不给", false, K.tierOffersBudget("stopped", DutyTierCode.NoMembership))
        assertEquals("额度窗口满了的「停」不给", false, K.tierOffersBudget("stopped", DutyTierCode.Quota))
        assertEquals("老服务端没有 tier_code：不猜", false, K.tierOffersBudget("stopped", DutyTierCode.None))
        assertEquals("节省档不带", false, K.tierOffersBudget("save", DutyTierCode.BudgetTier))
        assertEquals("认不出的档位码归 other", DutyTierCode.Other, DutyTierCode.of("zzz"))
        // 今天执勤预算（按天，ae8cc4f）。
        assertEquals("预算数值：整数不带小数", "2.1 / 6 点", K.budgetText(DutyBudget(cap = 6.0, spent = 2.1), tr))
        assertEquals("进度一半", 0.5, K.budgetFraction(DutyBudget(cap = 6.0, spent = 3.0)), 0.0)
        assertEquals("超额封在 1", 1.0, K.budgetFraction(DutyBudget(cap = 6.0, spent = 9.0)), 0.0)
        assertEquals("cap 为 0 按用满", 1.0, K.budgetFraction(DutyBudget(cap = 0.0, spent = 0.0)), 0.0)
        val l = list("""{"tier": "stopped", "tier_code": "budget", "budget": {"cap": 6, "spent": 6.4, "period": "day"}}""")
        assertEquals("解出 tier_code", DutyTierCode.Budget, l.tier_code)
        assertEquals("解出 budget", DutyBudget(cap = 6.0, spent = 6.4), l.budget)
        assertNull("budget null → 不出进度条", list("""{"budget": null}""").budget)
        assertEquals("老服务端没有 tier_code", DutyTierCode.None, list("{}").tier_code)
        // 档位一句（批次 265，字面以协调方核过服务端行为后定的为准）。
        fun tier(j: String): DutyKit.TierCopy = K.tierCopy(list(j), at8, tr)
        assertEquals("节省·今天预算用过六成、周额度还够",
            "今天的执勤预算用过六成了。先省着用：不再逐条判断、只做最要紧的，可有可无的条目先停，明天预算重算后恢复。",
            tier("""{"tier": "save", "tier_code": "budget_tier", "budget": {"cap": 10, "spent": 6}, "week": {"used": 50, "total": 100}}""").text)
        assertEquals("节省·周额度剩不到三成", true,
            tier("""{"tier": "save", "budget": {"cap": 10, "spent": 1}, "week": {"used": 75, "total": 100}}""").text?.startsWith("周额度剩不到三成"))
        assertEquals("节省·两条同时成立用周额度那句", true,
            tier("""{"tier": "save", "budget": {"cap": 10, "spent": 8}, "week": {"used": 75, "total": 100}}""").text?.startsWith("周额度剩不到三成"))
        assertEquals("节省·算不出也用周额度那句", true, tier("""{"tier": "save"}""").text?.startsWith("周额度剩不到三成"))
        assertEquals("只保要紧", true, tier("""{"tier": "essential"}""").text?.startsWith("周额度剩不到 15%"))
        val sb = tier("""{"tier": "stopped", "tier_code": "budget"}""")
        assertEquals("停·budget", true, sb.text?.startsWith("今天的执勤预算用完了"))
        assertEquals("停·budget 带调高预算", DutyKit.TierAction.Budget, sb.action)
        fun quota(end: Long?): String? = K.tierCopy(
            DutyListDTO(tier = "stopped", tier_code = DutyTierCode.Quota, full = end?.let { DutyFull("week", it) }), at8, tr,
        ).text
        assertEquals("停·额度用满 同日写 HH:MM", "周额度用满了，执勤停到 11:00。早报照发，不花额度的部分不受影响。", quota(at8 + 3 * 3_600_000))
        assertEquals("停·额度用满 跨日写 M月D日 HH:MM", true, quota(at8 + 2 * 86_400_000)?.contains("停到 10月18日 08:00"))
        assertEquals("停·额度用满 没有 full：去掉「到 …」", "周额度用满了，执勤停了。早报照发，不花额度的部分不受影响。", quota(null))
        assertEquals("解出 full", DutyFull("week", 5), list("""{"full": {"kind": "week", "end_ms": 5}}""").full)
        assertEquals("解出 week，剩 25%", 0.25, list("""{"week": {"used": 75, "total": 100}}""").week?.left)
        val sm = tier("""{"tier": "stopped", "tier_code": "no_membership"}""")
        assertEquals("停·没会员", "没有会员，执勤停了。开会员后接着跑。", sm.text)
        assertEquals("停·没会员带开会员", DutyKit.TierAction.Membership, sm.action)
        assertEquals("停·认不出码：照服务端原话", "服务端原话", tier("""{"tier": "stopped", "tier_reason": "服务端原话"}""").text)
        assertNull("正常档不出", tier("""{"tier": "normal"}""").text)

        // 早报末尾条数：新消息照 proposals_n，老消息退回认那句话。
        val line = "另外，我有 2 条建议放在执勤页「秘书的提议」里，你看看要不要。"
        assertEquals("有 proposals_n 就用它", 3, K.forecastTail(line, 3).proposals)
        assertEquals("那句话照样从预报格里拿掉", "", K.forecastTail(line, 3).forecast)
        assertNull("proposals_n = 0 → 不出「看建议」", K.forecastTail(line, 0).proposals)
        assertEquals("老消息没字段：退回认那句话", 2, K.forecastTail(line, null).proposals)
        assertEquals("meta 解出 proposals_n", 4, DutyMeta.parse(obj("""{"kind": "brief", "proposals_n": 4}"""))?.proposalsN)
        assertNull("老消息没有 proposals_n", DutyMeta.parse(obj("""{"kind": "brief"}"""))?.proposalsN)

        // 聊天早报卡末尾：服务端把预报和「我有 N 条建议」用换行接在同一格里。
        val both = K.splitForecast("照这周的节奏，周四会用完额度；我先把可有可无的执勤停到周一重置，聊天的额度留给你。\n另外，我有 2 条建议放在执勤页「秘书的提议」里，你看看要不要。")
        assertEquals("拆出预报那句", "照这周的节奏，周四会用完额度；我先把可有可无的执勤停到周一重置，聊天的额度留给你。", both.forecast)
        assertEquals("拆出建议条数", 2, both.proposals)
        val only = K.splitForecast("另外，我有 3 条建议放在执勤页「秘书的提议」里，你看看要不要。")
        assertEquals("只有建议那句 → 预报为空", "", only.forecast)
        assertEquals("只有建议那句的条数", 3, only.proposals)
        assertNull("没有建议那句 → null，不出「看建议」", K.splitForecast("照这周的节奏，周四会用完额度。").proposals)
        assertEquals("空格子", "", K.splitForecast("").forecast)
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
