package com.geek.umbra.feature.task

import com.geek.umbra.app.Brand
import com.geek.umbra.net.UmbraJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务挂起两档 + 接着跑：**两端共享夹具**（设计侧批次 223 / 224「两端逐字同源」）。
 * 夹具 = UmbraPC/tests/fixtures/taskSuspendCases.json 的拷贝（app/src/test/resources/）；PC 改了要重新拷一份。
 * 照 iOS Tools/swifttests/TaskSuspendFixtureTests.swift：用真实的 TaskSuspendRules 渲染，逐条和期望比。
 */
class TaskSuspendFixtureTest {
    private fun str(e: Any?): String? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

    @Test fun fixture() {
        val text = javaClass.classLoader!!.getResource("taskSuspendCases.json")!!.readText()
        val doc = UmbraJson.parseToJsonElement(text) as JsonObject
        val cases = (doc["cases"] as JsonArray).map { it as JsonObject }
        val resumes = (doc["resumeCases"] as JsonArray).map { it as JsonObject }
        // 读错文件 / 夹具被清空时先红：一条都没比也算「全过」，正是这类闸门最贵的坏法。
        assertTrue("夹具条数", cases.size >= 15 && resumes.size >= 9)

        for (c in cases) {
            val name = str(c["name"]) ?: "?"
            val status = str(c["status"]) ?: ""
            val i = TaskSuspendInfo.fromJson((c["fields"] as? JsonObject) ?: JsonObject(emptyMap()))
            val d = TaskSuspendRules.display(status, i)
            val e = c["expect"] as? JsonObject
            if (e == null) {
                assertNull("$name：不是挂起 → 不画原因", d)
                assertEquals(false, TaskSuspendRules.showsResume(status, i))
                continue
            }
            assertEquals("$name·标题", str(e["title"]), d?.title)
            assertEquals("$name·正文", str(e["body"]), d?.body)
            assertEquals("$name·列表行", str(e["listRow"]), d?.listMeta)
            assertEquals("$name·聊天卡", str(e["chatLine"]), d?.cardText)
            val ready = TaskSuspendRules.showsResume(status, i)
            val detailButtons = (e["detailButtons"] as? JsonArray)?.map { str(it) } ?: emptyList()
            assertEquals("$name·详情的钮", detailButtons, (if (ready) listOf(TaskSuspendCopy.resume) else emptyList()) + listOf(TaskSuspendCopy.stop, TaskSuspendCopy.copyDetail))
            val chatButtons = (e["chatButtons"] as? JsonArray)?.map { str(it) } ?: emptyList()
            assertEquals("$name·聊天的钮", chatButtons, if (ready) listOf(TaskSuspendCopy.resume) else emptyList())
            // 夹具是 PC 仓的原样拷贝，句子里写的是代号；对外产品名以 Brand 为准（品牌收口 10-22），比之前换掉。
            assertEquals("$name·iOS 独有那句", str((e["ios"] as? JsonObject)?.get("hint"))?.replace(Brand.codename, Brand.productName), d?.iosHint)
        }

        for (c in resumes) {
            val name = str(c["name"]) ?: "?"
            val r = (c["response"] as? JsonObject) ?: JsonObject(emptyMap())
            val e = (c["expect"] as? JsonObject) ?: JsonObject(emptyMap())
            val resp = TaskRetryResponse((r["status"] as? JsonPrimitive)?.intOrNull ?: -1, str(r["code"]), str(r["state"]), str(r["detail"]) ?: "")
            val o = TaskSuspendRules.outcome(resp)
            val info = TaskSuspendInfo.fromJson((c["fields"] as? JsonObject) ?: JsonObject(emptyMap()))
            assertEquals("接着跑·$name·那一行", str(e["failLine"]), o.message(info))
            assertEquals("接着跑·$name·留不留钮", (e["keepButton"] as? JsonPrimitive)?.booleanOrNull ?: false, o.keepsResume)
        }
    }
}
