package com.geek.umbra.feature.reminder

import com.geek.umbra.net.UmbraJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * 重复提醒「点完成」只完成这一次：**三端 + 服务端共用夹具**（sam 11-12 定的统一方案）。
 * 夹具 = UmbraPC/tests/fixtures/reminderCompleteCases.json 的拷贝（PC 69f8e6d，期望值由 PC completeOnce 生成）；PC 改了要重新拷一份。
 * 用例里的规则 / 单位是线上的英文值，换成本端的显示值再喂给 [ReminderAdvance.completed]；不带节假日表。
 */
class ReminderCompleteFixtureTest {
    private fun str(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
    private fun long(o: JsonObject, k: String): Long? = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull

    @Test fun fixture() {
        val text = javaClass.classLoader!!.getResource("reminderCompleteCases.json")!!.readText()
        val cases = ((UmbraJson.parseToJsonElement(text) as JsonObject)["cases"] as JsonArray).map { it as JsonObject }
        // 读错文件 / 夹具被清空时先红：一条都没比也算「全过」。
        assertTrue("夹具条数", cases.size >= 22)
        for (c in cases) {
            val name = str(c, "name") ?: "?"
            val r = c["reminder"] as JsonObject
            val rem = Reminder(
                id = "x", text = "x", atMs = long(r, "atMs")!!,
                repeatRule = ReminderWire.ruleToDisplay(str(r, "repeatRule") ?: "none"),
                customFreq = ReminderWire.freqToDisplay(str(r, "customFreq") ?: "day"),
                customN = long(r, "customN") ?: 1, repeatEndMs = long(r, "repeatEndMs"),
                done = (r["done"] as? JsonPrimitive)?.booleanOrNull ?: false, tz = str(r, "tz") ?: "",
                anchorDay = (r["anchorDay"] as? JsonPrimitive)?.intOrNull,
                weekDays = (r["weekDays"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull },
                place = r["place"] as? JsonObject,
            )
            val got = ReminderAdvance.completed(rem, long(c, "nowMs")!!, fallbackTz = ZoneId.of("Asia/Shanghai"))
            val e = c["expect"] as JsonObject
            assertEquals("$name：at", long(e, "atMs"), got.atMs)
            assertEquals("$name：done", (e["done"] as JsonPrimitive).booleanOrNull, got.done)
        }
    }
}
