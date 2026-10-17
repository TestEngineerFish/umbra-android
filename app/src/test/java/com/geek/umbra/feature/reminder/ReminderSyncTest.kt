package com.geek.umbra.feature.reminder

import com.geek.umbra.net.UmbraJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * ReminderSync / ReminderModel 的行为用例 —— 照抄 iOS Tools/swifttests/ReminderSyncTests.swift 与 ReminderMergeTests.swift。
 * 本机缓存的那几条（旧缓存缺键、某一格写坏只丢它）按 Android 缓存的形状改写，判据同 iOS。
 */
class ReminderSyncTest {
    private fun wire(r: Reminder): JsonObject = r.toDto().toJson()

    private fun serverDTO(atts: List<ReminderAttDTO>? = null, weekDays: List<Int>? = null, anchor: Int? = null) = ReminderDTO(
        id = "r1", text = "交房租", note = "", at_ms = 1_790_000_000_000L, repeat_rule = "none", custom_freq = "day", custom_n = 1,
        ahead_minutes = 0, done = false, source = "manual", tz = "Asia/Shanghai", updated_at_ms = 1_790_000_000_000L, deleted = false,
        atts = atts, anchor_day = anchor, week_days = weekDays,
    )

    private fun dto(id: String, text: String = "服务端", updated: Long, deleted: Boolean = false, tz: String = "Asia/Shanghai", createdAt: String? = null) = ReminderDTO(
        id = id, text = text, note = "", at_ms = 1_790_000_000_000L, repeat_rule = "none", custom_freq = "day", custom_n = 1,
        ahead_minutes = 0, done = false, source = "manual", tz = tz, updated_at_ms = updated, deleted = deleted, created_at = createdAt,
    )

    private fun local(id: String, text: String = "本地", updated: Long, dirty: Boolean) =
        Reminder(id = id, text = text, atMs = 1_790_000_000_000L, createdAtMs = 1_700_000_000_000L, updatedAtMs = updated, dirty = dirty)

    private fun row(text: String = "交房租", note: String = "", done: Boolean = false, atts: List<String> = emptyList()) =
        Reminder(id = "r1", text = text, atMs = 1_790_000_000_000L, note = note, done = done, updatedAtMs = 1_000, dirty = false,
            atts = atts.map { ReminderAtt(it, "") }, attsTouched = false)

    // MARK: ReminderSyncTests

    @Test fun attsWire() {
        val r = Reminder.fromDto(serverDTO(atts = emptyList())).copy(done = true)
        val j = wire(r)
        assertFalse("没动过附件时上行仍带了 atts 键", j.containsKey("atts"))
        assertTrue(j["done"]?.jsonPrimitive?.content == "true" && j["id"]?.jsonPrimitive?.content == "r1")
        assertFalse(r.toDto().toJson().toString().contains("\"atts\""))
        val cleared = Reminder.fromDto(serverDTO(atts = listOf(ReminderAttDTO("f1", "相册图片")))).let { it.withAtts(emptyList()) }
        assertEquals(0, (wire(cleared)["atts"] as JsonArray).size)
        val added = Reminder.fromDto(serverDTO(atts = listOf(ReminderAttDTO("f1", "相册图片")))).let { it.withAtts(it.atts + ReminderAtt("f2", "拍的照片")) }
        assertEquals(listOf("f1", "f2"), (wire(added)["atts"] as JsonArray).map { (it as JsonObject)["file_id"]!!.jsonPrimitive.content })
        assertNull(ReminderAttsWire.upstream(listOf(ReminderAtt("f1", "x")), false))
        assertEquals(0, ReminderAttsWire.upstream(emptyList(), true)?.size)
        assertEquals(Reminder.maxAtts, ReminderAttsWire.upstream((0 until Reminder.maxAtts + 2).map { ReminderAtt("f$it", "") }, true)?.size)
    }

    @Test fun touch() {
        val base = Reminder.fromDto(serverDTO(atts = listOf(ReminderAttDTO("f1", "相册图片"))))
        assertFalse(base.attsTouched)
        assertEquals(listOf("f1"), base.atts.map { it.fileId })
        assertFalse(base.copy(text = "交房租（改）", done = true, atMs = 0).attsTouched)
        assertFalse("附件值没变却被算成动过", base.withAtts(base.atts).attsTouched)
        assertTrue(base.withAtts(base.atts.filter { it.fileId != "f1" }).attsTouched)
        assertTrue("新建带图的提醒没被标成动过", Reminder(id = "n1", text = "新的", atMs = 0, atts = listOf(ReminderAtt("f9", "相册图片"))).attsTouched)
        val freshEmpty = Reminder(id = "n2", text = "新的", atMs = 0)
        assertFalse(freshEmpty.attsTouched); assertFalse(wire(freshEmpty).containsKey("atts"))
        val removed = base.withAtts(emptyList())
        assertEquals(true, ReminderCodec.decode(ReminderCodec.encode(removed))?.attsTouched)
    }

    @Test fun legacyCache() {
        val legacy = """{"id":"r1","text":"交房租","at":780000000,"repeatRule":"不重复","customFreq":"天","customN":1,"aheadMinutes":0,"note":"","done":true,"source":"手动添加","createdAt":780000000,"updatedAtMs":1790000000000,"dirty":true,"atts":[]}"""
        val r = ReminderCodec.decode(UmbraJson.parseToJsonElement(legacy))!!
        assertFalse("旧缓存的 attsTouched 应默认 false", r.attsTouched)
        assertFalse(wire(r).containsKey("atts"))
        val v1 = ReminderCodec.decode(UmbraJson.parseToJsonElement("""{"id":"r0","text":"老的","at":780000000,"repeatRule":"每年"}"""))!!
        assertTrue(!v1.attsTouched && v1.atts.isEmpty())
        assertEquals("自定义", v1.repeatRule); assertEquals("年", v1.customFreq); assertEquals(1L, v1.customN)
        assertTrue("缺 dirty / updatedAtMs 的存量行按 createdAt 标脏", v1.dirty && v1.updatedAtMs == 780000000L)
    }

    @Test fun settle() {
        val pushed = Reminder.fromDto(serverDTO(atts = listOf(ReminderAttDTO("f1", "")))).withAtts(emptyList()).copy(dirty = true, updatedAtMs = 2_000)
        val ok = ReminderAttsWire.settled(pushed, 2_000)
        assertTrue(!ok.dirty && !ok.attsTouched)
        val kept = ReminderAttsWire.settled(pushed.copy(updatedAtMs = 2_500), 2_000)
        assertTrue(kept.dirty && kept.attsTouched)
    }

    @Test fun reject() {
        val server = serverDTO().copy(text = "服务端的字", updated_at_ms = 3_000)
        val pushed = Reminder.fromDto(serverDTO()).copy(text = "本地 v1", dirty = true, updatedAtMs = 2_000)
        val lost = ReminderMerge.rejected(pushed, 2_000, server)!!
        assertTrue(lost.text == "服务端的字" && !lost.dirty)
        val kept = ReminderMerge.rejected(pushed.copy(text = "本地 v2", updatedAtMs = 4_000), 2_000, server)!!
        assertTrue(kept.text == "本地 v2" && kept.dirty)
    }

    @Test fun draftMerge() {
        val base = row()
        val latest = row(note = "PC 改的备注", done = true, atts = listOf("pc1")).copy(updatedAtMs = 5_000)
        val edited = base.copy(text = "交房租（改）")
        val m = ReminderDraftMerge.merge(base, edited, latest)
        assertEquals("交房租（改）", m.text); assertTrue(m.done); assertEquals("PC 改的备注", m.note)
        assertEquals(listOf("pc1"), m.atts.map { it.fileId })
        assertTrue(!m.attsTouched && !wire(m).containsKey("atts"))
        val base2 = row(atts = listOf("a", "b"))
        val m2 = ReminderDraftMerge.merge(base2, base2.withAtts(listOf(ReminderAtt("a", ""), ReminderAtt("new", ""))), row(atts = listOf("a", "b", "pc")))
        assertEquals(listOf("a", "pc", "new"), m2.atts.map { it.fileId }); assertTrue(m2.attsTouched)
        val m3 = ReminderDraftMerge.merge(row(), row().withAtts(listOf(ReminderAtt("mine", ""))), row(atts = (0 until Reminder.maxAtts).map { "s$it" }))
        assertEquals(Reminder.maxAtts, m3.atts.size)
        assertEquals("交房租（改）", ReminderDraftMerge.merge(base, edited, null).text)
        val latest4 = row(text = "别端改的正文").copy(updatedAtMs = 9_000)
        assertEquals("交房租（改）", ReminderDraftMerge.merge(base, edited, latest4).text)
        val m5 = ReminderDraftMerge.merge(base, base.copy(note = "只改备注"), latest4)
        assertTrue(m5.text == "别端改的正文" && m5.note == "只改备注")
    }

    @Test fun afterUpload() {
        val sent = row(text = "v1", atts = listOf("a"))
        val current = sent.copy(text = "  v2  ").withAtts(emptyList())
        val e = ReminderDraftMerge.afterUpload(sent, listOf(ReminderAtt("a", ""), ReminderAtt("up1", "")), current)
        assertEquals("v2", e.text); assertEquals(listOf("up1"), e.atts.map { it.fileId })
        assertEquals("v1", ReminderDraftMerge.afterUpload(sent, sent.atts, sent.copy(text = "   ")).text)
    }

    @Test fun aheadWire() {
        for ((localM, want) in listOf(525_600 to 10_080, Int.MAX_VALUE to 10_080, 10_080 to 10_080, 30 to 30, 0 to 0, -5 to 0)) {
            val r = Reminder.fromDto(serverDTO()).copy(aheadMinutes = localM)
            assertEquals(want, wire(r)["ahead_minutes"]!!.jsonPrimitive.int)
            assertEquals(localM, r.aheadMinutes)
        }
    }

    @Test fun anchorWire() {
        for ((raw, want) in listOf(31 to 31, 29 to 29, 1 to 1, 0 to null, 32 to null, -1 to null, null to null)) {
            assertEquals("下行 anchor_day = $raw", want, Reminder.fromDto(serverDTO(anchor = raw)).anchorDay)
        }
        val json = """{"id":"r1","text":"x","note":"","at_ms":1,"repeat_rule":"monthly","custom_freq":"day","custom_n":1,"ahead_minutes":0,"done":false,"source":"manual","tz":"","updated_at_ms":1,"deleted":false,"anchor_day":31}"""
        assertEquals(31, UmbraJson.decodeFromString(ReminderDTO.serializer(), json).anchor_day)
        val r = Reminder.fromDto(serverDTO())
        assertFalse("本机没有锚定日时上行不该带 anchor_day 键", wire(r).containsKey("anchor_day"))
        assertEquals(31, wire(r.copy(anchorDay = 31))["anchor_day"]!!.jsonPrimitive.int)
        for (bad in listOf(0, 32, -1)) assertFalse(wire(r.copy(anchorDay = bad)).containsKey("anchor_day"))
        assertEquals(31, ReminderCodec.decode(ReminderCodec.encode(r.copy(anchorDay = 31)))?.anchorDay)
        val legacy = ReminderCodec.decode(UmbraJson.parseToJsonElement("""{"id":"r1","text":"交房租","at":780000000,"repeatRule":"每月"}"""))!!
        assertNull(legacy.anchorDay)
        val bad = ReminderCodec.decode(UmbraJson.parseToJsonElement("""{"id":"r1","text":"交房租","at":780000000,"repeatRule":"每月","anchorDay":"31"}"""))
        assertNotNull("anchorDay 写坏了应只丢这一格", bad); assertNull(bad!!.anchorDay)
    }

    @Test fun weekDaysWire() {
        assertEquals(listOf(0, 2, 4), Reminder.fromDto(serverDTO(weekDays = listOf(4, 0, 2, 2, 7, -1))).weekDays)
        assertNull(Reminder.fromDto(serverDTO(weekDays = null)).weekDays)
        val json = """{"id":"r1","text":"x","note":"","at_ms":1,"repeat_rule":"weekly","custom_freq":"day","custom_n":1,"ahead_minutes":0,"done":false,"source":"manual","tz":"","updated_at_ms":1,"deleted":false,"week_days":[1,3]}"""
        assertEquals(listOf(1, 3), UmbraJson.decodeFromString(ReminderDTO.serializer(), json).week_days)
        val r = Reminder.fromDto(serverDTO())
        assertFalse(wire(r).containsKey("week_days"))
        assertEquals(0, (wire(r.copy(weekDays = emptyList()))["week_days"] as JsonArray).size)
        assertEquals(listOf(0, 2, 4), (wire(r.copy(weekDays = listOf(0, 2, 4)))["week_days"] as JsonArray).map { it.jsonPrimitive.int })
        assertEquals(listOf(0, 2, 4), ReminderCodec.decode(ReminderCodec.encode(r.copy(weekDays = listOf(0, 2, 4))))?.weekDays)
        assertNull(ReminderCodec.decode(UmbraJson.parseToJsonElement("""{"id":"r1","text":"健身","at":780000000,"repeatRule":"每周"}"""))!!.weekDays)
        val bad = ReminderCodec.decode(UmbraJson.parseToJsonElement("""{"id":"r1","text":"健身","at":780000000,"repeatRule":"每周","weekDays":"一三五"}"""))
        assertNotNull(bad); assertNull(bad!!.weekDays)
        val base = r.copy(weekDays = listOf(0))
        val latest = base.copy(weekDays = listOf(1, 3))
        assertEquals(listOf(1, 3), ReminderDraftMerge.merge(base, base.copy(text = "改了正文"), latest).weekDays)
        assertEquals(listOf(0, 4), ReminderDraftMerge.merge(base, base.copy(text = "改了正文", weekDays = listOf(0, 4)), latest).weekDays)
    }

    @Test fun permanentRefusal() {
        for (s in listOf(400, 404, 409, 410, 413, 422)) assertTrue("HTTP $s 应算永久拒收", ReminderPushFail.isPermanent(s))
        for (s in listOf(401, 403, 408, 425, 429, 500, 502, 503, 200, 204, 302, 0, 600)) assertFalse("HTTP $s 不该算永久拒收", ReminderPushFail.isPermanent(s))
        assertEquals("ahead_minutes 最多 10080（7 天）", ReminderPushFail.detail("""{"detail":"  ahead_minutes 最多 10080（7 天）\n","status":400}"""))
        assertNull(ReminderPushFail.detail("""{"detail":[{"loc":["body","at_ms"],"msg":"x"}]}"""))
        assertNull(ReminderPushFail.detail("""{"detail":"   "}"""))
        assertNull(ReminderPushFail.detail("<html>502</html>"))
        assertEquals(201, ReminderPushFail.detail("{\"detail\":\"" + "长".repeat(300) + "\"}")?.length)
        assertEquals(ReminderPushResult.Refused(ReminderPushRejection(422, "提醒正文不能为空")), ReminderPushFail.failure<Int>(422, """{"detail":"提醒正文不能为空"}"""))
        assertEquals(ReminderPushResult.RetryLater, ReminderPushFail.failure<Int>(503, """{"detail":"提醒同步未启用"}"""))
        assertEquals(ReminderPushResult.RetryLater, ReminderPushFail.failure<Int>(401, ""))
        val old = ReminderCodec.decode(UmbraJson.parseToJsonElement("""{"id":"r1","text":"x","at":0,"repeatRule":"不重复"}"""))
        assertTrue(old != null && old.pushRejection == null)
        val bad = ReminderCodec.decode(UmbraJson.parseToJsonElement("""{"id":"r1","text":"x","at":0,"repeatRule":"不重复","pushRejection":"坏"}"""))
        assertTrue(bad != null && bad.pushRejection == null)
        val r = row().copy(pushRejection = ReminderPushRejection(400, null))
        assertEquals(ReminderPushRejection(400, null), ReminderCodec.decode(ReminderCodec.encode(r))?.pushRejection)
        assertTrue(!wire(r).containsKey("pushRejection") && !wire(r).containsKey("push_rejection"))
        assertNull(ReminderMerge.fromServer(serverDTO(), r).pushRejection)
    }

    @Test fun serverSwitch() {
        val a = "https://a.example"; val b = "https://b.example"
        assertFalse(ReminderServerSwitch.switched(null, a)); assertFalse(ReminderServerSwitch.switched("", a))
        assertFalse(ReminderServerSwitch.switched(a, a)); assertTrue(ReminderServerSwitch.switched(a, b))
        val clean = row().copy(id = "clean")
        val edited = row().copy(id = "edited", dirty = true, pendingSinceMs = null)
        val advanced = row().copy(id = "advanced", dirty = true, pendingSinceMs = 5_000)
        val rejected = row().copy(id = "rejected", dirty = true, pushRejection = ReminderPushRejection(400, null))
        assertFalse(ReminderServerSwitch.isLocalChange(clean)); assertTrue(ReminderServerSwitch.isLocalChange(edited))
        assertFalse(ReminderServerSwitch.isLocalChange(advanced)); assertTrue(ReminderServerSwitch.isLocalChange(rejected))
        val p = ReminderServerSwitch.plan(listOf(clean, edited, advanced, rejected), mapOf("gone" to 7L))
        assertEquals(setOf("clean", "advanced"), p.dropIds.toSet()); assertEquals(3, p.askCount)
        val none = ReminderServerSwitch.plan(listOf(clean, advanced), emptyMap())
        assertTrue(none.askCount == 0 && none.dropIds.size == 2)
        assertEquals(ReminderServerSwitch.Plan(emptyList(), 0), ReminderServerSwitch.plan(emptyList(), emptyMap()))
    }

    // MARK: ReminderMergeTests

    @Test fun merge() {
        val mine = listOf(local("a", "本地新", 5_000, true), local("b", "本地旧", 1_000, true), local("c", "本地干净", 9_000, false),
            local("d", "要被删", 1_000, false), local("e", "删后又改", 8_000, true))
        val out = ReminderMerge.apply(mine, listOf(dto("a", updated = 4_000), dto("b", updated = 2_000), dto("c", updated = 3_000), dto("n", "别端新建", 1)),
            listOf(ReminderTombDTO("d", 2_000), ReminderTombDTO("e", 7_000)))
        val by = out.associateBy { it.id }
        assertTrue(by["a"]?.text == "本地新" && by["a"]?.dirty == true)
        assertTrue(by["b"]?.text == "服务端" && by["b"]?.dirty == false)
        assertEquals("服务端", by["c"]?.text)
        assertNotNull(by["n"]); assertNull(by["d"]); assertEquals("删后又改", by["e"]?.text)
        assertEquals(out.map { it.atMs }.sorted(), out.map { it.atMs })
    }

    @Test fun pendingTombs() {
        assertTrue(ReminderMerge.apply(emptyList(), listOf(dto("x", updated = 4_000)), emptyList(), mapOf("x" to 5_000L)).isEmpty())
        assertEquals(listOf("x"), ReminderMerge.apply(emptyList(), listOf(dto("x", updated = 6_000)), emptyList(), mapOf("x" to 5_000L)).map { it.id })
        assertEquals(listOf("y"), ReminderMerge.apply(emptyList(), listOf(dto("y", updated = 1)), emptyList()).map { it.id })
    }

    @Test fun rejectedTomb() {
        val pushed = local("r", "本地编辑", 2_000, true).copy(note = "x")
        assertNull(ReminderMerge.rejected(pushed, 2_000, dto("r", updated = 3_000, deleted = true)))
        val kept = ReminderMerge.rejected(pushed.copy(updatedAtMs = 4_000), 2_000, dto("r", updated = 3_000, deleted = true))
        assertTrue(kept?.updatedAtMs == 4_000L && kept.dirty)
        val back = ReminderMerge.rejected(pushed, 2_000, dto("r", updated = 3_000))
        assertTrue(back?.text == "服务端" && back.createdAtMs == pushed.createdAtMs)
    }

    @Test fun tombPush() {
        fun n(status: Int, s: String) = ReminderTombPush.receipt(status, s)?.deleted
        assertEquals(1, n(200, """{"deleted": 1}""")); assertEquals(0, n(200, """{"deleted": 0}""")); assertEquals(1, n(200, "ok"))
        assertNull(ReminderTombPush.receipt(503, """{"deleted": 1}""")); assertNull(ReminderTombPush.receipt(404, ""))
    }

    @Test fun deleteRejectedRow() {
        val row = """{"id":"r1","text":"别端刚改过","note":"","at_ms":1790000000000,"repeat_rule":"none","custom_freq":"day","custom_n":1,"repeat_end_ms":null,"ahead_minutes":0,"done":false,"source":"manual","tz":"Asia/Shanghai","updated_at_ms":5000,"deleted":false,"created_at":"2026-10-01 08:00:00","anchor_day":null}"""
        val withRow = ReminderTombPush.receipt(200, """{"deleted":0,"reminder":$row}""")!!
        assertTrue(withRow.deleted == 0 && withRow.reminder?.text == "别端刚改过")
        val next = ReminderMerge.afterDelete(withRow, null)
        assertTrue(next is ReminderMerge.DeleteFollowUp.Restore)
        val v = (next as ReminderMerge.DeleteFollowUp.Restore).reminder
        assertTrue(v.id == "r1" && v.text == "别端刚改过" && v.updatedAtMs == 5_000L && !v.dirty)
        assertEquals(ReminderMerge.DeleteFollowUp.RefetchAll, ReminderMerge.afterDelete(ReminderTombPush.receipt(200, """{"deleted":0}""")!!, null))
        val bad = ReminderTombPush.receipt(200, """{"deleted":0,"reminder":{"id":"r1"}}""")!!
        assertTrue(bad.deleted == 0 && bad.reminder == null)
        assertEquals(ReminderMerge.DeleteFollowUp.RefetchAll, ReminderMerge.afterDelete(bad, null))
        val junk = ReminderTombPush.receipt(200, """{"deleted":0,"reminder":"x"}""")!!
        assertTrue(junk.deleted == 0 && junk.reminder == null)
        val ok = ReminderTombPush.receipt(200, """{"deleted":1,"reminder":$row}""")!!
        assertNull(ok.reminder); assertEquals(ReminderMerge.DeleteFollowUp.Done, ReminderMerge.afterDelete(ok, null))
        assertEquals(ReminderMerge.DeleteFollowUp.Done, ReminderMerge.afterDelete(ReminderDeleteReceipt(0, dto("r1", updated = 5_000, deleted = true)), null))
        val srv = ReminderDeleteReceipt(0, dto("r1", updated = 5_000))
        assertEquals(ReminderMerge.DeleteFollowUp.Done, ReminderMerge.afterDelete(srv, local("r1", updated = 6_000, dirty = true)))
        val restored = ReminderMerge.afterDelete(srv, local("r1", updated = 4_000, dirty = false)) as ReminderMerge.DeleteFollowUp.Restore
        assertTrue(restored.reminder.text == "服务端" && restored.reminder.createdAtMs == 1_700_000_000_000L)
    }

    @Test fun watermark() {
        assertEquals(9_000L, ReminderWatermark.since(9_000, "https://a", "https://a"))
        assertEquals(0L, ReminderWatermark.since(9_000, "https://a", "https://b"))
        assertEquals(0L, ReminderWatermark.since(9_000, null, "https://a"))
        assertEquals(0L, ReminderWatermark.since(-5, "https://a", "https://a"))
    }

    @Test fun createdAt() {
        assertEquals(1_791_162_123_000L, ReminderWire.serverCreatedAt("2026-10-05 01:02:03"))
        assertEquals(1_791_162_123_000L, ReminderWire.serverCreatedAt("2026-10-05T01:02:03Z"))
        assertTrue(ReminderWire.serverCreatedAt(null) == null && ReminderWire.serverCreatedAt("") == null && ReminderWire.serverCreatedAt("昨天") == null)
        val r = Reminder.fromDto(dto("c", updated = 1, createdAt = "2026-10-05 01:02:03"))
        assertEquals(1_791_162_123_000L, r.createdAtMs)
        val mine = local("c", updated = 1, dirty = false)
        assertEquals(mine.createdAtMs, ReminderMerge.apply(listOf(mine), listOf(dto("c", updated = 2)), emptyList()).first().createdAtMs)
        assertFalse(wire(r).containsKey("created_at"))
    }

    @Test fun tz() {
        val other = if (TimeZone.getDefault().id == "Pacific/Kiritimati") "America/Adak" else "Pacific/Kiritimati"
        val r = Reminder.fromDto(dto("t", updated = 1, tz = other))
        assertEquals(other, r.tz)
        assertEquals(other, wire(r)["tz"]!!.jsonPrimitive.content)
        assertEquals(TimeZone.getDefault().id, wire(Reminder.fromDto(dto("t2", updated = 1, tz = "")))["tz"]!!.jsonPrimitive.content)
        assertEquals(TimeZone.getDefault().id, Reminder(id = "n", text = "x", atMs = 0).tz)
        assertEquals(other, ReminderCodec.decode(ReminderCodec.encode(r))?.tz)
        assertEquals("", ReminderCodec.decode(UmbraJson.parseToJsonElement("""{"id":"l","text":"老","at":0,"repeatRule":"不重复"}"""))?.tz)
    }

    @Test fun ahead() {
        assertEquals("无", Reminder.aheadText(0)); assertEquals("15 分钟", Reminder.aheadText(15)); assertEquals("30 分钟", Reminder.aheadText(30))
        assertEquals("2 小时", Reminder.aheadText(120)); assertEquals("2 天", Reminder.aheadText(2_880)); assertEquals("90 分钟", Reminder.aheadText(90))
        for ((m, want) in listOf(5 to "5 min", 15 to "15 min", 60 to "1 hour", 1440 to "1 day", 2_880 to "2 days", 120 to "2 hours", 90 to "90 min", 1 to "1 min")) {
            assertEquals(want, Reminder.aheadTextEn(m))
        }
    }

    @Test fun cacheDecode() {
        val good = local("g", updated = 1, dirty = true)
        val raw = "[${ReminderCodec.encode(good)}, {\"id\":\"bad\"}, {\"id\":\"bad2\",\"text\":\"x\",\"at\":\"不是数\",\"repeatRule\":\"不重复\"}]"
        val got = ReminderCodec.decodeList(UmbraJson.parseToJsonElement(raw))!!
        assertEquals(listOf("g"), got.first.map { it.id }); assertEquals(2, got.second); assertTrue(got.first.first().dirty)
        assertNull(ReminderCodec.decodeList(UmbraJson.parseToJsonElement("{}")))
        assertTrue(ReminderCodec.decodeList(UmbraJson.parseToJsonElement("[]"))!!.first.isEmpty())
        assertEquals(JsonPrimitive("g"), ReminderCodec.encode(good)["id"])
    }
}
