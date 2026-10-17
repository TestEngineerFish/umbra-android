package com.geek.umbra.feature.inspiration

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException

/** 输入输出照抄 iOS Tools/swifttests/InspirationIntentRulesTests.swift；URLError 那几条换成对应的 Java 异常。 */
class InspirationIntentRulesTest {
    private val R = InspIntentRules

    @Test fun gate() {
        assertEquals(InspIntentGate.SignedOut, R.gate(true, "买牛奶"))
        assertEquals("未登录 + 空内容 → 仍然先说未登录", InspIntentGate.SignedOut, R.gate(true, "  "))
        assertEquals(InspIntentGate.Empty, R.gate(false, ""))
        assertEquals(InspIntentGate.Empty, R.gate(false, " \n\t "))
        assertEquals(InspIntentGate.Send("做个读书 App"), R.gate(false, "  做个读书 App \n"))
        for (g in listOf(InspIntentGate.SignedOut, InspIntentGate.Empty, InspIntentGate.Send("x"))) assertFalse(R.reply(g).ok)
        assertEquals("intent.insp.signedOut", R.reply(InspIntentGate.SignedOut).key)
    }

    @Test fun body() {
        val inApp = R.createBody("r", "t", "s", listOf("a"), true, null)
        assertNull("App 内新建：请求体里没有 source_channel 这个键", inApp["source_channel"])
        assertEquals(setOf("raw", "title", "summary", "tags", "research"), inApp.keys)
        val intent = R.createBody("r", "", "", emptyList(), false, R.sourceChannel)
        assertEquals(R.sourceChannel, (intent["source_channel"] as JsonPrimitive).content)
        assertEquals("r", (intent["raw"] as JsonPrimitive).content)
        assertEquals(false, (intent["research"] as JsonPrimitive).booleanOrNull)
    }

    @Test fun outcomes() {
        assertEquals(InspCreateOutcome.Saved, R.outcome(200))
        assertEquals(InspCreateOutcome.Saved, R.outcome(201))
        assertEquals("3xx → 没存上", InspCreateOutcome.Failed, R.outcome(302))
        assertEquals("401 → 未登录", InspCreateOutcome.SignedOut, R.outcome(401))
        assertEquals(InspCreateOutcome.Rejected(422), R.outcome(422))
        assertEquals(InspCreateOutcome.Rejected(403), R.outcome(403))
        assertEquals(InspCreateOutcome.ServerError(500), R.outcome(500))
        assertEquals(InspCreateOutcome.ServerError(503), R.outcome(503))
        assertEquals(InspCreateOutcome.SignedOut, R.outcome(InspTransportError.SignedOutBlocked))

        fun e(t: Throwable) = R.outcome(R.errorKind(t))
        assertEquals("断网 / 连不上主机 → 连不上", InspCreateOutcome.Offline, e(ConnectException("refused")))
        assertEquals("超时 → 说不准", InspCreateOutcome.Uncertain, e(SocketTimeoutException()))
        assertEquals("连接中途断了 → 说不准", InspCreateOutcome.Uncertain, e(java.io.IOException("unexpected end of stream")))
        assertEquals("任务被取消 → 说不准", InspCreateOutcome.Uncertain, e(CancellationException()))
        assertEquals("OkHttp Canceled → 说不准", InspCreateOutcome.Uncertain, e(java.io.IOException("Canceled")))
        assertEquals(InspCreateOutcome.Uncertain, e(InterruptedIOException("timeout")))
        assertEquals("找不到主机 → 连不上", InspCreateOutcome.Offline, e(UnknownHostException("x")))
        assertEquals("地址坏了 → 没配好", InspCreateOutcome.NotConfigured, e(MalformedURLException()))
        assertEquals("别的错误 → 不猜原因", InspCreateOutcome.Failed, e(IllegalStateException()))
    }

    @Test fun replies() {
        val all = listOf(InspCreateOutcome.Saved, InspCreateOutcome.SignedOut, InspCreateOutcome.NotConfigured, InspCreateOutcome.Offline,
            InspCreateOutcome.Uncertain, InspCreateOutcome.Rejected(422), InspCreateOutcome.ServerError(500), InspCreateOutcome.Failed)
        for (o in all) {
            assertEquals("$o", o == InspCreateOutcome.Saved, R.reply(o, false).ok)
            assertEquals("$o", o == InspCreateOutcome.Saved, R.reply(o, true).ok)
        }
        assertEquals("intent.insp.saved", R.reply(InspCreateOutcome.Saved, false).key)
        assertEquals("intent.insp.savedResearch", R.reply(InspCreateOutcome.Saved, true).key)
        assertEquals("intent.insp.offline", R.reply(InspCreateOutcome.Offline, true).key)
        val unsure = R.reply(InspCreateOutcome.Uncertain, false)
        assertEquals("intent.insp.uncertain", unsure.key)
        assertNotEquals(R.reply(InspCreateOutcome.Offline, false).key, unsure.key)
        val produced = all.flatMap { listOf(R.reply(it, false).key, R.reply(it, true).key) }.toMutableSet()
        for (g in listOf(InspIntentGate.SignedOut, InspIntentGate.Empty, InspIntentGate.Send("x"))) produced += R.reply(g).key
        assertEquals(R.allKeys.toSet(), produced)
    }

    /** 每一句都在 strings_ios.xml 里（这些 key 不经 stringResource 字面调用，靠这条核对）。 */
    @Test fun keysExistInStrings() {
        val xml = listOf("src/main/res/values/strings_ios.xml", "app/src/main/res/values/strings_ios.xml").map { File(it) }.firstOrNull { it.exists() } ?: return
        val text = xml.readText()
        for (k in R.allKeys) assertTrue(k, text.contains("name=\"${k.replace('.', '_')}\""))
    }
}
