package com.geek.umbra.feature.task

import com.geek.umbra.model.TaskEvent
import com.geek.umbra.net.UmbraJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URI

/** 输入输出照抄 iOS Tools/swifttests/TaskStepRulesTests.swift。 */
class TaskStepRulesTest {
    private fun shot(raw: String, base: String = "https://umbra.example") = TaskStepRules.shotUrl(raw, base)

    @Test fun shotNormal() {
        assertEquals("https://umbra.example/files/abc", shot("/files/abc"))
        assertEquals("https://umbra.example/files/abc", shot("/files/abc", "https://umbra.example/"))
        assertEquals("https://h.example/umbra/files/abc", shot("/files/abc", "https://h.example/umbra"))
        assertEquals("http://192.168.1.5:8000/files/abc", shot("/files/abc", "http://192.168.1.5:8000"))
        assertEquals("https://umbra.example/files/abc", shot("  /files/abc \n"))
        assertEquals("https://umbra.example/files/abc", shot("https://umbra.example/files/abc"))
        assertEquals("同主机 http → https 升级算同源", "https://umbra.example/files/abc", shot("https://umbra.example/files/abc", "http://umbra.example"))
    }

    @Test fun shotHostSwaps() {
        for (bad in listOf(
            "@evil.com/x.png", ".evil.com/x.png", "httpx://evil.com/x.png", "http-evil:x.png",
            "//evil.com/x.png", "/\\evil.com/x.png", "https://evil.com/files/abc", "https://umbra.example.evil.com/x",
            "https://umbra.example@evil.com/x", "https://u:p@umbra.example/x", "https://umbra.example:8443/x",
            "http://umbra.example/x", "javascript:alert(1)", "file:///etc/passwd", "files/abc", "",
        )) assertNull("「$bad」不该被认", shot(bad))
        assertNull("服务端地址为空时不画", shot("/files/abc", ""))
        assertEquals("以 / 开头的路径里有 @ 也换不了主机", "umbra.example", URI(shot("/@evil.com/x")!!).host)
    }

    @Test fun tones() {
        val t = TaskStepRules::eventTone
        assertEquals(TaskEventTone.Danger, t("status", "failed"))
        assertEquals(TaskEventTone.Success, t("status", "done"))
        assertEquals(TaskEventTone.Warning, t("status", "suspended"))
        assertEquals(TaskEventTone.Neutral, t("status", "running"))
        assertEquals(TaskEventTone.Neutral, t("status", "pending"))
        assertEquals("人按的停 → 灰，不当出错", TaskEventTone.Neutral, t("status", "cancelled"))
        assertEquals(TaskEventTone.Neutral, t("status", null))
        assertEquals(TaskEventTone.Neutral, t("progress", "running"))
        assertEquals(TaskEventTone.Success, t("result", "done"))
        assertEquals(TaskEventTone.Danger, t("progress", "failed"))
        assertEquals(TaskEventTone.Success, t("result", null))
        assertEquals(TaskEventTone.Success, t("milestone", null))
        assertEquals(TaskEventTone.Warning, t("locate", null))
        assertEquals(TaskEventTone.Neutral, t("tool", null))
        assertEquals(TaskEventTone.Neutral, t("log", null))
        assertEquals(TaskEventTone.Neutral, t("progress", null))
        assertEquals(TaskEventTone.Neutral, t("unknown_type", null))
    }

    private fun decode(json: String) = TaskEventPayload.parse(UmbraJson.parseToJsonElement(json))

    @Test fun payloads() {
        assertEquals("JSON 文本里的 status 解得出来", "failed", decode("\"{\\\"status\\\": \\\"failed\\\", \\\"reason_kind\\\": \\\"quota\\\"}\"").status)
        assertNull(decode("\"{}\"").status)
        assertNull("坏文本不抛，当没有", decode("\"not json\"").status)
        assertNull(decode("\"{\\\"status\\\": 3}\"").status)
        assertNull(decode("\"[1,2]\"").status)
        assertEquals("将来直接给对象也认", "done", decode("{\"status\": \"done\"}").status)
        assertNull(decode("{\"status\": 1}").status)
        assertNull(decode("12").status)
        assertEquals("run_id 一并读出", "r-9", decode("\"{\\\"run_id\\\": \\\"r-9\\\"}\"").runId)

        // 挂在一条事件上：payload 坏了，事件本身照样解得出来（不能拖垮整个详情）。
        fun ev(json: String) = UmbraJson.decodeFromString(TaskEvent.serializer(), json)
        assertEquals("done", TaskEventPayload.parse(ev("{\"id\":1,\"type\":\"status\",\"payload\":\"{\\\"status\\\":\\\"done\\\"}\"}").payload).status)
        assertEquals(2L, ev("{\"id\":2,\"type\":\"status\",\"payload\":\"oops\"}").id)
        assertEquals(3L, ev("{\"id\":3,\"type\":\"status\",\"payload\":null}").id)
        assertEquals(4L, ev("{\"id\":4,\"type\":\"status\"}").id)
        assertEquals(5L, ev("{\"id\":5,\"type\":\"status\",\"payload\":[1]}").id)
    }

    @Test fun looksImage() {
        assertEquals(true, TaskStepRules.looksImage("/files/abc"))
        assertEquals(true, TaskStepRules.looksImage("https://x/a.PNG"))
        assertEquals(false, TaskStepRules.looksImage("https://x/a.txt"))
    }

    @Test fun inlineLinks() {
        org.junit.Assert.assertTrue(safeLink("查看结果", "https://example.com/x"))
        org.junit.Assert.assertFalse("只放 https", safeLink("看", "http://example.com"))
        org.junit.Assert.assertFalse("自定义 scheme 不放", safeLink("看", "shortcuts://run"))
        org.junit.Assert.assertFalse("显示 apple.com 实际指向别处", safeLink("apple.com", "https://evil.com"))
        org.junit.Assert.assertTrue("显示父域", safeLink("example.com", "https://docs.example.com/a"))
        org.junit.Assert.assertFalse("带 userinfo", safeLink("x", "https://a@b.com"))
    }
}
