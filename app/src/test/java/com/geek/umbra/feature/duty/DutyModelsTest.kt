package com.geek.umbra.feature.duty

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iOS Tools/swifttests/DutyModelsTests.swift 逐条照抄（执勤 M0 契约 §15.2）。
 * 会再坏的边：早报 meta 解析与分节顺序；GET /duties / 日志 / Shift 宽松解码；上海时区与日志分组；总开关 / 暂停 / 百分比 / HH:mm / 勾选块。
 */
class DutyModelsTest {
    /** 2026-10-16 08:00 上海 = 00:00 UTC。 */
    private val at8 = 1_792_108_800_000L

    @Test fun metaCases() {
        val meta = json("""
        {"duty": {"duty_id": "d1", "shift_id": "s1", "name": "早报", "kind": "brief", "planned_at_ms": $at8, "simple": true,
          "items": [
            {"id": "n1", "section": "news", "title": "新动向", "summary": "一句", "source_url": null},
            {"id": "w1", "section": "weather", "title": "上海 多云", "summary": "18–24°C", "source_url": null},
            {"id": "r1", "section": "reminders", "title": "10:00 牙医", "summary": ""},
            "坏的",
            {"id": "x", "section": "tasks", "title": "", "summary": ""},
            {"id": 7, "section": "poem", "title": "认不出的节", "summary": "", "source_url": "https://example.com"}
          ]}}
        """)
        val m = DutyMeta.parseMeta(meta)
        assertEquals("duty_id", "d1", m?.dutyId)
        assertEquals("kind", "brief", m?.kind)
        assertEquals("brief 认得", true, m?.isBrief)
        assertEquals("simple", true, m?.simple)
        assertEquals("planned_at_ms", at8, m?.plannedAtMs)
        assertEquals("坏条目（不是字典、标题摘要都空）丢掉，好的留下", 4, m?.items?.size)
        assertEquals("分节按固定顺序，认不出的归最后「其他」", listOf("weather", "reminders", "news", "other"), m?.sections?.map { it.section })
        assertEquals("整数 id 收成字符串", "7", m?.items?.last()?.id)
        assertEquals("source_url 有就留", "https://example.com", m?.items?.last()?.sourceURL)
        assertNull("source_url null → nil", m?.items?.first()?.sourceURL)
        assertNull("没有 duty → 不是执勤消息", DutyMeta.parseMeta(json("""{"quote": {"id": 1}}""")))
        assertNull("没有 meta → nil", DutyMeta.parseMeta(null))
        assertNull("duty 不是字典 → nil", DutyMeta.parseMeta(json("""{"duty": "brief"}""")))
        val other = DutyMeta.parseMeta(json("""{"duty": {"name": "复盘", "kind": "note", "planned_at_ms": "x"}}"""))
        assertEquals("其它 kind 只画标头", false, other?.isBrief)
        assertEquals("planned_at_ms 认不出 → 0（标头不带时刻）", 0L, other?.plannedAtMs)
    }

    @Test fun decodeCases() {
        val list = """
        {"settings": {"enabled": true, "consented": false, "paused_until_ms": 0, "quiet_start": "22:30", "quiet_end": "07:30", "budget_pct": 15},
         "tier": "normal", "tier_reason": "", "estimate_weekly_pct": 7.5,
         "items": [{"id": "d1", "name": "早报", "preset": "morning_brief", "enabled": true, "paused_until_ms": 0,
                    "params": {"time": "08:00", "days": "workday", "city": "", "blocks": ["weather", "reminders", "tasks"], "email": false},
                    "next_at_ms": $at8, "last": null, "created_at_ms": 1, "updated_at_ms": 2}]}
        """
        val l = DutyListDTO.from(obj(list))
        assertEquals("estimate_weekly_pct", 7.5, l.estimate_weekly_pct, 0.0)
        assertEquals("settings.quiet_start", "22:30", l.settings.quiet_start)
        assertEquals("早报 params.time", "08:00", l.brief?.params?.time)
        assertEquals("next_at_ms", at8, l.brief?.next_at_ms)
        assertNull("last null → nil", l.brief?.last)
        val sparse = DutyListDTO.from(obj("""{"items": [{"id": 12, "preset": "morning_brief"}]}"""))
        assertEquals("id 是整数也收", "12", sparse.brief?.id)
        assertEquals("缺 settings → 契约默认值", 15.0, sparse.settings.budget_pct, 0.0)
        assertEquals("缺 params → 默认三块", listOf("weather", "reminders", "tasks"), sparse.brief?.params?.blocks)
        val shift = """
        {"id": "s1", "duty_id": "d1", "duty_name": "早报", "planned_at_ms": $at8, "state": "closed", "outcome": "skipped",
         "reason_code": "nothing", "reason_text": "今天没什么要说的", "manual": true, "simple": false, "message_id": null, "points": 0, "closed_at_ms": null}
        """
        val s = DutyShift.from(obj(shift))
        assertEquals("Shift.outcome", "skipped", s.outcome)
        assertEquals("Shift.manual", true, s.manual)
        assertNull("message_id null", s.message_id)
        assertNull("closed_at_ms null", s.closed_at_ms)
        val p = DutyParams(time = "07:45", days = "everyday", city = "杭州", blocks = listOf("tasks"), email = true).json
        assertEquals("回写 params.time", JsonPrimitive("07:45"), p["time"])
        assertEquals("回写 params.blocks", listOf("tasks"), p["blocks"].strictStringList())
        assertEquals("回写 params.email", true, p["email"].strictBool())
    }

    @Test fun timeAndLogCases() {
        val R = DutyRules
        assertEquals("上海 08:00", "08:00", R.hhmm(at8))
        assertEquals("0 → 空（标头不带时刻）", "", R.hhmm(0))
        assertEquals("标头：名字 · 时刻", listOf("早报", "08:00"), R.headerParts("早报", at8))
        assertEquals("都空 → 只剩「执勤」", emptyList<String>(), R.headerParts("", 0))
        val day = 86_400_000L
        val a = DutyShift(id = "a", planned_at_ms = at8)
        val b = DutyShift(id = "b", planned_at_ms = at8 - 3_600_000)
        val c = DutyShift(id = "c", planned_at_ms = at8 - day)
        val groups = R.groupByDay(listOf(a, b, c))
        assertEquals("按天分组，新的在前", listOf("2026-10-16", "2026-10-15"), groups.map { it.day })
        assertEquals("组内保持服务端顺序", listOf("a", "b"), groups.first().items.map { it.id })
        // 上海 10-15 23:30 = 10-15 15:30 UTC：按上海那天归 15 号；8:30 之后才是 16 号。
        assertEquals("上海 23:30 归当天（15 号）", "2026-10-15", R.day(at8 - 8 * 3_600_000 - 30 * 60_000))
        assertEquals("上海 0 点起是 16 号", "2026-10-16", R.day(at8 - 8 * 3_600_000))
        val replaced = c.copy(outcome = "failed")
        assertEquals("同 id 就地替换，不新增一行", listOf("a", "b", "c"), R.merge(replaced, listOf(a, b, c)).map { it.id })
        assertEquals("替换成新的结局", "failed", R.merge(replaced, listOf(a, b, c)).last().outcome)
        assertEquals("新的插到最前", listOf("n", "a", "b"), R.merge(DutyShift(id = "n", planned_at_ms = at8 + 1), listOf(a, b)).map { it.id })
        assertEquals("做了", "duty.outcome.ran", R.outcomeKey("ran"))
        assertEquals("没结案 → 进行中", "duty.outcome.pending", R.outcomeKey(null))
        assertEquals("认不出的结局 → 进行中", "duty.outcome.pending", R.outcomeKey("weird"))
    }

    @Test fun ruleCases() {
        val R = DutyRules
        assertEquals("默认 enabled 但没确认过 → 画成关", false, R.switchOn(DutySettings(enabled = true, consented = false)))
        assertEquals("开着且确认过 → 开", true, R.switchOn(DutySettings(enabled = true, consented = true)))
        assertEquals("第一次打开 → 先确认", true, R.needsConsent(turningOn = true, consented = false))
        assertEquals("确认过 → 不再问", false, R.needsConsent(turningOn = true, consented = true))
        assertEquals("关 → 不问", false, R.needsConsent(turningOn = false, consented = false))
        val now = 1_800_000_000_000L
        assertEquals("0 = 没暂停", DutyPause.None, R.pause(0, now))
        assertEquals("暂停到的时刻已过 → 没暂停", DutyPause.None, R.pause(now - 1, now))
        assertEquals("暂停 1 小时", DutyPause.Until(now + 3_600_000), R.pause(now + 3_600_000, now))
        assertEquals("十年之外 → 直到恢复", DutyPause.Forever, R.pause(now + R.FOREVER_THRESHOLD_MS, now))
        assertEquals("暂停四档与契约取值一致", listOf("hour", "today", "week", "forever"), R.pauseSpans)
        assertEquals("一位小数", "7.5%", R.pctText(7.5))
        assertEquals("整数不带小数点", "15%", R.pctText(15.0))
        assertEquals("四舍五入到一位后是整数 → 不带小数点", "7%", R.pctText(7.04))
        assertEquals("负数 → 0%", "0%", R.pctText(-1.0))
        assertEquals("NaN → 0%", "0%", R.pctText(Double.NaN))
        assertEquals("08:00", true, R.validHHMM("08:00"))
        assertEquals("23:59", true, R.validHHMM("23:59"))
        assertEquals("24 点认不出", false, R.validHHMM("24:00"))
        assertEquals("缺前导零认不出（契约是 HH:mm）", false, R.validHHMM("8:00"))
        assertEquals("60 分认不出", false, R.validHHMM("08:60"))
        assertEquals("关掉一块", listOf("weather", "reminders"), R.toggle("tasks", listOf("weather", "reminders", "tasks")))
        assertEquals("打开一块，按固定顺序", listOf("weather", "tasks"), R.toggle("weather", listOf("tasks")))
        assertEquals("预算最低 5%", 5, R.budgetOptions.first())
        assertEquals("预算最高 40%", 40, R.budgetOptions.last())
        assertTrue("默认 15% 在可选里", 15 in R.budgetOptions)
    }

    /** Android 增补（与 iOS 有意不同）：编辑器 M2 那两块新勾上能勾上 —— iOS 的 toggle 只从原 blocks 里挑非三老块，永远勾不上。 */
    @Test fun toggleNewUnknownBlock() {
        assertEquals(listOf("weather", "reminders_soon"), DutyRules.toggle("reminders_soon", listOf("weather")))
        assertEquals(listOf("weather"), DutyRules.toggle("reminders_soon", listOf("weather", "reminders_soon")))
    }
}
