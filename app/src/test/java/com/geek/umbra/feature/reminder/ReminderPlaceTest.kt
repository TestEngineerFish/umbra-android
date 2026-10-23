package com.geek.umbra.feature.reminder

import com.geek.umbra.net.UmbraJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 地点提醒（服务端 place，geek-99 10-23）：Android 不做触发，只保证两件事 ——
 * ① 编辑一条带 place 的提醒再写回去，place 逐字原样（含 Android 不认识的子字段）；
 * ② at_ms = 0 的地点提醒不进时间那一套（排闹钟、过期、推进、按日期分组、小组件）。
 */
class ReminderPlaceTest {
    /** 服务端下行的一行：place 里多一个 Android 不认识的子字段，透传要连它一起带回去。 */
    private val serverRow = """
        {"id":"p1","text":"到公司打卡","note":"","at_ms":0,"repeat_rule":"none","custom_freq":"day","custom_n":1,
         "ahead_minutes":0,"done":false,"source":"manual","tz":"Asia/Shanghai","updated_at_ms":1000,"deleted":false,
         "place":{"lat":31.2304,"lng":121.4737,"radius_m":150,"on":"arrive","name":"公司","address":"人民广场 1 号","future_key":{"x":[1,2]}}}
    """.trimIndent()

    private fun parse(json: String): ReminderDTO = UmbraJson.decodeFromString(ReminderDTO.serializer(), json)
    private val original: JsonObject get() = UmbraJson.parseToJsonElement(serverRow).jsonObject["place"]!!.jsonObject

    @Test fun placeSurvivesEditAndWriteBack() {
        val fromServer = Reminder.fromDto(parse(serverRow))
        // 走一遍本机缓存（冷启动读回来的是这一份）。
        val cached = ReminderCodec.decode(ReminderCodec.encode(fromServer))!!
        // 编辑页：只改正文，按 store 最新那行合并。
        val edited = ReminderDraftMerge.merge(cached, cached.copy(text = "到公司先打卡"), cached)
        val up = edited.toDto().toJson()
        assertEquals("到公司先打卡", up["text"].toString().trim('"'))
        assertEquals("place 要逐字带回去", original, up["place"])
        // 普通提醒上行不写 place 键（不写 = 服务端保留库里那份；写 null 才是清掉）。
        val plain = Reminder(id = "t1", text = "交房租", atMs = 1_790_000_000_000L).toDto().toJson()
        assertFalse("普通提醒不该带 place 键", "place" in plain)
        // 下行 place: null（普通提醒总带这个键）解出来就是按时间提醒。
        val nullRow = serverRow.substringBefore(",\n \"place\"") + ",\"place\":null}"
        assertNull(Reminder.fromDto(parse(nullRow)).place)
    }

    @Test fun placeReminderSkipsTimeLogic() {
        val r = Reminder.fromDto(parse(serverRow))
        val now = 1_790_000_000_000L
        assertFalse(ReminderPlace.timed(r))
        assertNull("不推进", ReminderAdvance.applied(r, now))
        assertEquals("不排闹钟", null to null, ReminderNotifier.nextFires(r, null, now))
        // 不重复的 at_ms = 0 本来就落在过去、排不上；带重复的才会从 1970 一路推到「下一次」—— 这一格守的是它。
        assertEquals("带重复也不排", null to null, ReminderNotifier.nextFires(r.copy(repeatRule = "每天", aheadMinutes = 5), null, now))
        assertNull("带重复也不推进", ReminderAdvance.applied(r.copy(repeatRule = "每天"), now))
        assertFalse("不算过期", r.overdue)
        assertFalse("不给「再等 10 分钟」", r.canSnoozeNow)
        assertEquals("单开「到地方时」一组，不按日期分（批次 288）", "到地方时", r.group)
        assertEquals("到达 公司 时", r.timeLabel)
        // 同一条去掉 place 就回到时间那一套（判据确实看的是 place，不是别的格）。
        val timed = r.copy(place = null)
        assertTrue(timed.overdue)
        assertEquals("已过期", timed.group)
        // 小组件按源码核：过滤里带了 ReminderPlace.timed（它的取数要读 ReminderStore，JVM 上起不来）。
        val widget = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
            .resolve("com/geek/umbra/feature/widget/ReminderWidget.kt").readText()
        assertTrue("小组件没滤掉地点提醒", "items.filter { !it.done && ReminderPlace.timed(it) }" in widget)
    }

    /** 批次 288 的字面：行上一句、英文、到达 / 离开、地名截 10 字、范围 米 / 公里。 */
    @Test fun placeCopyFollowsBatch288() {
        fun p(json: String) = UmbraJson.parseToJsonElement(json).jsonObject
        val home = p("""{"on":"arrive","name":"家","radius_m":200}""")
        assertEquals("到达 家 时 · 在手机上提醒", ReminderPlace.row(home, english = false))
        assertEquals("Arriving at 家 · Reminds on iPhone", ReminderPlace.row(home, english = true))
        assertEquals("Leaving Home", ReminderPlace.head(p("""{"on":"leave","name":"Home"}"""), english = true))
        assertEquals("离开 人民广场 1 号 时", ReminderPlace.head(p("""{"on":"leave","name":"  ","address":"人民广场 1 号"}"""), english = false))
        assertEquals("到达 指定地点 时", ReminderPlace.head(p("""{"on":"arrive"}"""), english = false))
        // 地名超过 10 个字截断，「到达」「时」不截。
        assertEquals("到达 一二三四五六七八九十… 时", ReminderPlace.head(p("""{"on":"arrive","name":"一二三四五六七八九十一"}"""), english = false))
        assertEquals("到达 一二三四五六七八九十 时", ReminderPlace.head(p("""{"on":"arrive","name":"一二三四五六七八九十"}"""), english = false))
        // 范围：不到 1000 写米，满 1000 写公里（一位小数，整数不写 .0）。
        assertEquals("200 米", ReminderPlace.radius(home, english = false))
        assertEquals("450 米", ReminderPlace.rangeText(450.0, false))
        assertEquals("1 公里", ReminderPlace.rangeText(1000.0, false))
        assertEquals("1.2 公里", ReminderPlace.rangeText(1200.0, false))
        assertEquals("2 公里", ReminderPlace.rangeText(2000.0, false))
        assertEquals("1.2 km", ReminderPlace.rangeText(1200.0, true))
        assertEquals("950 m", ReminderPlace.rangeText(950.0, true))
        assertNull("没给半径", ReminderPlace.radius(p("""{"on":"arrive","name":"家"}"""), false))
    }
}
