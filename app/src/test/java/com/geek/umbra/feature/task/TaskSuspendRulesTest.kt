package com.geek.umbra.feature.task

import com.geek.umbra.app.Brand
import com.geek.umbra.model.TaskWaitingDevice
import com.geek.umbra.net.UmbraJson
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 输入输出照抄 iOS Tools/swifttests/TaskSuspendRulesTests.swift。 */
class TaskSuspendRulesTest {
    private val R = TaskSuspendRules
    private fun dev(name: String?, id: String = "d") = TaskWaitingDevice(id, name)
    private fun resp(status: Int, code: String? = null, state: String? = null, detail: String = "") = TaskRetryResponse(status, code, state, detail)

    @Test fun phrases() {
        assertEquals("我的 MacBook Pro", R.devicePhrase(listOf(dev("我的 MacBook Pro"))))
        assertEquals("我的 MacBook Pro 等 2 台设备", R.devicePhrase(listOf(dev("我的 MacBook Pro"), dev("办公室"))))
        assertEquals("办公室 等 3 台设备", R.devicePhrase(listOf(dev(""), dev("办公室"), dev(null))))
        assertEquals("能做这一步的设备", R.devicePhrase(emptyList()))
        assertEquals("能做这一步的设备", R.devicePhrase(listOf(dev(""), dev("  "))))
        assertEquals("只有 id → 不念 id", "能做这一步的设备", R.devicePhrase(listOf(dev(null, "mac-1"))))

        assertEquals("图片生成", R.capabilityNames(listOf("图片生成")))
        assertEquals("图片生成、设计", R.capabilityNames(listOf("图片生成", "设计")))
        assertEquals("图片生成、设计等 3 项", R.capabilityNames(listOf("图片生成", "设计", "写代码")))
        assertNull(R.capabilityNames(emptyList()))
        assertEquals(listOf("图片生成", "design"), R.capabilityList(TaskSuspendInfo(missingCapabilities = listOf("image_gen", "design"), labels = listOf("图片生成"))))
        assertEquals(listOf("image_gen"), R.capabilityList(TaskSuspendInfo(missingCapabilities = listOf("image_gen"))))
        assertEquals(listOf("设计"), R.capabilityList(TaskSuspendInfo(labels = listOf("", " ", "设计"))))

        assertEquals("第 3 步", R.stepPhrase(3))
        assertEquals("这一步", R.stepPhrase(null))
        assertEquals("这一步", R.stepPhrase(0))
    }

    @Test fun deviceKind() {
        val info = TaskSuspendInfo(reason = "device_offline", waitingDevices = listOf(dev("我的 MacBook Pro")), step = 3)
        val d = R.display("suspended", info)!!
        assertEquals("在等「我的 MacBook Pro」上线", d.title)
        assertEquals("第 3 步要用这台设备上的能力，它现在不在线。上线后会问你要不要接着跑。", d.body)
        assertEquals("已挂起 · 等「我的 MacBook Pro」上线", d.listMeta)
        assertEquals("等「我的 MacBook Pro」上线，上线后会问你要不要接着跑", d.cardText)
        assertNull(d.iosHint)
        assertFalse(d.ready)
        assertFalse(d.listMetaWarn)
        assertFalse(R.showsResume("suspended", info))

        val none = TaskSuspendInfo(reason = "device_offline")
        val n = R.display("suspended", none)!!
        assertEquals("无名不带书名号", "在等能做这一步的设备上线", n.title)
        assertEquals("已挂起 · 等能做这一步的设备上线", n.listMeta)
        assertEquals("等能做这一步的设备上线，上线后会问你要不要接着跑", n.cardText)
        assertEquals("这一步要用这台设备上的能力，它现在不在线。上线后会问你要不要接着跑。", n.body)

        assertNull(R.display("running", info))
        for (x in listOf(d, n)) for (s in listOf(x.title, x.body, x.listMeta, x.cardText)) {
            assertFalse(s, s.contains("自动接着跑") || s.contains("不用按重试") || s.contains("{{"))
        }
    }

    @Test fun capabilityKind() {
        val two = TaskSuspendInfo(reason = "no_capability", missingCapabilities = listOf("image_gen", "design"), labels = listOf("图片生成", "设计"), step = 1)
        val d = R.display("suspended", two)!!
        assertEquals("缺能力：图片生成、设计", d.title)
        assertEquals("哪台设备上都没有这几项能力。接上以后会问你要不要接着跑。", d.body)
        assertEquals("已挂起 · 缺能力：图片生成、设计", d.listMeta)
        assertEquals("缺能力：图片生成、设计，接上后会问你要不要接着跑", d.cardText)
        assertEquals("在电脑上打开 ${Brand.productName} 的「能力」页接上。", d.iosHint)

        val one = TaskSuspendInfo(reason = "no_capability", missingCapabilities = listOf("image_gen"), labels = listOf("图片生成"))
        assertEquals("哪台设备上都没有这项能力。接上以后会问你要不要接着跑。", R.display("suspended", one)!!.body)
        val three = TaskSuspendInfo(reason = "no_capability", missingCapabilities = listOf("a", "b", "c"), labels = listOf("图片生成", "设计", "写代码"))
        assertEquals("缺能力：图片生成、设计等 3 项", R.display("suspended", three)!!.title)

        val e = R.display("suspended", TaskSuspendInfo(reason = "no_capability"))!!
        assertEquals("缺一项程序或 Skill", e.title)
        assertEquals("这一步要用的程序或 Skill 哪台设备上都没有。接上以后会问你要不要接着跑。", e.body)
        assertEquals("已挂起 · 缺一项程序或 Skill", e.listMeta)
        assertEquals("缺一项程序或 Skill，接上后会问你要不要接着跑", e.cardText)
        assertEquals("在电脑上打开 ${Brand.productName} 的「能力」页接上。", e.iosHint)
        assertFalse(d.listMetaWarn); assertFalse(e.listMetaWarn)
    }

    @Test fun ready() {
        val dev1 = TaskSuspendInfo(reason = "device_offline", waitingDevices = listOf(dev("我的 MacBook Pro")), step = 3, resumeReady = true, readyReason = "device_online")
        val d = R.display("suspended", dev1)!!
        assertEquals("「我的 MacBook Pro」上线了，要接着跑吗？", d.title)
        assertEquals("按「接着跑」从第 3 步继续，已完成的里程碑不重做。不想要了就按「停止」。", d.body)
        assertEquals("已挂起 · 可以接着跑了", d.listMeta)
        assertEquals("「我的 MacBook Pro」上线了，可以接着跑了", d.cardText)
        assertTrue(d.ready); assertTrue(d.listMetaWarn)
        assertTrue(R.showsResume("suspended", dev1))
        assertFalse(R.showsResume("running", dev1))

        val cap = TaskSuspendInfo(reason = "no_capability", labels = listOf("图片生成"), step = 2, resumeReady = true, readyReason = "capability_added")
        val c = R.display("suspended", cap)!!
        assertEquals("需要的能力接上了，要接着跑吗？", c.title)
        assertEquals("按「接着跑」从第 2 步继续，已完成的里程碑不重做。不想要了就按「停止」。", c.body)
        assertEquals("需要的能力接上了，可以接着跑了", c.cardText)
        assertNull(c.iosHint)
        assertTrue(c.listMetaWarn)
        assertEquals("按「接着跑」从这一步继续，已完成的里程碑不重做。不想要了就按「停止」。",
            R.display("suspended", TaskSuspendInfo(reason = "no_capability", resumeReady = true, readyReason = "capability_added"))!!.body)

        val back = R.afterNotReady(dev1)
        assertFalse(back.resumeReady)
        assertEquals("在等「我的 MacBook Pro」上线", R.display("suspended", back)!!.title)
        assertFalse(R.display("suspended", back)!!.listMetaWarn)
        assertFalse(R.showsResume("suspended", back))

        assertTrue(R.needsAttention("failed", false))
        assertTrue(R.needsAttention("error", false))
        assertTrue(R.needsAttention("suspended", true))
        assertFalse(R.needsAttention("suspended", false))
        assertFalse(R.needsAttention("running", true))
    }

    @Test fun legacy() {
        assertEquals("在等能做这一步的设备上线", R.display("suspended", TaskSuspendInfo())!!.title)
        assertEquals("在等「甲」上线", R.display("suspended", TaskSuspendInfo(reason = "brand_new_reason", waitingDevices = listOf(dev("甲"))))!!.title)
        val oldReady = TaskSuspendInfo(resumeReady = true, readyReason = "device_online")
        assertEquals("能做这一步的设备上线了，要接着跑吗？", R.display("suspended", oldReady)!!.title)
        assertTrue(R.showsResume("suspended", oldReady))
        assertEquals(TaskSuspendKind.Capability, R.kind(TaskSuspendInfo(reason = "no_capability", resumeReady = true)))
    }

    @Test fun outcomes() {
        val sc = "state_conflict"
        assertEquals(TaskResumeOutcome.Ok, R.outcome(resp(200)))
        assertEquals(TaskResumeOutcome.NotReady, R.outcome(resp(409, sc, "not_ready", "现在还不能接着跑：需要的设备仍不在线")))
        assertEquals(TaskResumeOutcome.WrongStatus, R.outcome(resp(409, sc, "wrong_status")))
        assertEquals(TaskResumeOutcome.Missing, R.outcome(resp(404, "not_found")))
        assertEquals(TaskResumeOutcome.Offline, R.outcome(resp(0, detail = "offline")))
        assertEquals(TaskResumeOutcome.ServerError(503), R.outcome(resp(503)))
        assertEquals(TaskResumeOutcome.Unknown("新的一档"), R.outcome(resp(409, sc, "brand_new", "新的一档")))
        assertEquals(TaskResumeOutcome.Unknown("HTTP 400"), R.outcome(resp(400, detail = "HTTP 400")))

        val dv = TaskSuspendInfo(reason = "device_offline", waitingDevices = listOf(dev("我的 MacBook Pro")), resumeReady = true, readyReason = "device_online")
        val cp = TaskSuspendInfo(reason = "no_capability", resumeReady = true, readyReason = "capability_added")
        assertEquals("「我的 MacBook Pro」刚才又离线了，任务还挂着。", TaskResumeOutcome.NotReady.message(dv))
        assertEquals("能做这一步的设备刚才又离线了，任务还挂着。", TaskResumeOutcome.NotReady.message(TaskSuspendInfo(resumeReady = true)))
        assertEquals("需要的能力刚才又不可用了，任务还挂着。", TaskResumeOutcome.NotReady.message(cp))
        assertEquals("这个任务的状态刚变过，现在不能接着跑。详情已经刷新。", TaskResumeOutcome.WrongStatus.message(dv))
        assertEquals("这个任务已经不在了，可能在另一台设备上删掉了。", TaskResumeOutcome.Missing.message(dv))
        assertEquals("连不上服务器，没接上。联网后再按一次。", TaskResumeOutcome.Offline.message(dv))
        assertEquals("服务端这次没有响应（HTTP 502），过一会儿再按一次。", TaskResumeOutcome.ServerError(502).message(dv))
        assertEquals("原话", TaskResumeOutcome.Unknown("原话").message(dv))
        assertNull(TaskResumeOutcome.Ok.message(dv))

        assertFalse(TaskResumeOutcome.NotReady.keepsResume)
        assertFalse(TaskResumeOutcome.WrongStatus.keepsResume)
        assertFalse(TaskResumeOutcome.Missing.keepsResume)
        assertTrue(TaskResumeOutcome.Offline.keepsResume)
        assertTrue(TaskResumeOutcome.ServerError(500).keepsResume)
        assertTrue(TaskResumeOutcome.Unknown("x").keepsResume)
        assertTrue(TaskResumeOutcome.NotReady.refreshes && TaskResumeOutcome.WrongStatus.refreshes && TaskResumeOutcome.Ok.refreshes)
        assertFalse(TaskResumeOutcome.Offline.refreshes)

        assertEquals("/tasks/abc/resume", R.resumePath("abc"))
        assertEquals("/tasks/a%2Fb%3Fc/resume", R.resumePath("a/b?c"))
    }

    private fun obj(json: String) = UmbraJson.parseToJsonElement(json) as JsonObject

    @Test fun fromJson() {
        val i = TaskSuspendInfo.fromJson(obj("""{"suspend_reason":"device_offline","missing_capabilities":[],"missing_capability_labels":[],
            "waiting_devices":[{"id":"mac-1","name":"我的 MacBook"},{"id":"win-2","name":""}],"suspended_step":2,"resume_ready":false,"ready_reason":null}"""))
        assertEquals("device_offline", i.reason)
        assertEquals(2, i.waitingDevices.size)
        assertEquals(2, i.step)
        assertNull(i.readyReason)
        assertEquals("我的 MacBook 等 2 台设备", R.devicePhrase(i.waitingDevices))

        assertEquals("老服务端没这些键 → 全空、未就绪", TaskSuspendInfo(), TaskSuspendInfo.fromJson(obj("""{"status":"suspended"}""")))
        val junk = TaskSuspendInfo.fromJson(obj("""{"waiting_devices":["x",3],"suspended_step":"2","resume_ready":"yes"}"""))
        assertEquals(0, junk.waitingDevices.size)
        assertNull(junk.step)
        assertFalse(junk.resumeReady)
        assertEquals("中文名按位置对 id，null 那格记成空串不丢项", listOf("", "设计"),
            TaskSuspendInfo.fromJson(obj("""{"missing_capability_labels":[null,"设计"]}""")).labels)
    }
}
