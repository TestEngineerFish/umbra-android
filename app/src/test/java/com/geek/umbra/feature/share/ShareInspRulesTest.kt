package com.geek.umbra.feature.share

import com.geek.umbra.feature.inspiration.InspCreateOutcome
import com.geek.umbra.feature.inspiration.InspIntentGate
import com.geek.umbra.feature.inspiration.InspTransportError
import com.geek.umbra.net.UmbraUrl
import com.geek.umbra.net.UploadError
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 输入输出照抄 iOS Tools/swifttests/ShareInspirationRulesTests.swift。
 * 差异：source_channel 是 UmbraUrl.CLIENT_SRC（iOS 是 "ios"）；URLError 那几条换成 InspTransportError；
 * 另加 Android 独有的 isLink 与 UploadError 映射。
 */
class ShareInspRulesTest {
    private val R = ShareInspRules
    private val u = "https://example.com/a?b=1"

    @Test fun compose() {
        assertEquals("Safari 分享：纯文本就是链接本身 → 只出现一次", u, R.composeText(listOf(u), listOf(u)))
        assertEquals("字在前、链接在后", "好文章\n$u", R.composeText(listOf("好文章"), listOf(u)))
        assertEquals("先给链接再给标题 → 链接仍在后、只一次", "好文章\n$u", R.composeText(listOf(u, "好文章"), listOf(u)))
        assertEquals("字里已带着这条链接 → 不再补", "看这个 $u", R.composeText(listOf("看这个 $u"), listOf(u)))
        assertEquals("去首尾空白、去空串、去重", "第一段\n第二段", R.composeText(listOf("  第一段 \n", "", "第一段", "第二段"), emptyList()))
        assertEquals("只有链接：各占一行、重复只留一条", "$u\nhttps://b.example", R.composeText(emptyList(), listOf(u, " $u ", "https://b.example")))
        assertEquals("什么都没有 → 空串", "", R.composeText(emptyList(), emptyList()))
    }

    /** Android 独有：EXTRA_TEXT 整段是一条链接才算链接。 */
    @Test fun isLink() {
        assertTrue(R.isLink(u)); assertTrue(R.isLink("  $u\n")); assertTrue(R.isLink("HTTP://X.CN/p"))
        assertFalse("带说明文字的不是", R.isLink("看这个 $u"))
        assertFalse(R.isLink("example.com")); assertFalse(R.isLink("https://")); assertFalse(R.isLink(""))
    }

    @Test fun images() {
        assertEquals(4, R.maxImages)
        val one = R.pickImages(listOf("a"))
        assertEquals(listOf("a"), one.first); assertEquals(0, one.second); assertNull(R.droppedKey(one.second))
        val four = R.pickImages(listOf("a", "b", "c", "d"))
        assertEquals(4, four.first.size); assertEquals(0, four.second)
        val five = R.pickImages(listOf("a", "b", "c", "d", "e"))
        assertEquals("按分享顺序留前 4 张", listOf("a", "b", "c", "d"), five.first)
        assertEquals(1, five.second)
        assertEquals("share.insp.dropped", R.droppedKey(five.second))
        assertEquals(0, R.pickImages(emptyList<String>()).second)
    }

    @Test fun gate() {
        assertEquals("未登录 + 有字有图 → 挡下", InspIntentGate.SignedOut, R.gate(true, "有字", 2))
        assertEquals("未登录 + 什么都没有 → 仍先说未登录", InspIntentGate.SignedOut, R.gate(true, "", 0))
        assertEquals(InspIntentGate.Empty, R.gate(false, " \n ", 0))
        assertEquals("只有图 → 放行，正文落占位", InspIntentGate.Send(R.IMAGE_ONLY_RAW), R.gate(false, "  ", 1))
        assertEquals(InspIntentGate.Send("记一下"), R.gate(false, " 记一下 ", 3))
        assertFalse(R.IMAGE_ONLY_RAW.isEmpty())
    }

    @Test fun body() {
        val plain = R.createBody("r", true, emptyList())
        assertEquals(UmbraUrl.CLIENT_SRC, (plain["source_channel"] as JsonPrimitive).content)
        assertEquals(true, (plain["research"] as JsonPrimitive).booleanOrNull)
        assertNull("没图 → 没有 atts 这个键", plain["atts"])
        assertEquals(setOf("raw", "title", "summary", "tags", "research", "source_channel"), plain.keys)
        val atts = R.createBody("r", false, listOf("f1", "f2"))["atts"] as JsonArray
        assertEquals(listOf("f1", "f2"), atts.map { ((it as JsonObject)["file_id"] as JsonPrimitive).content })
        assertEquals(listOf("", ""), atts.map { ((it as JsonObject)["label"] as JsonPrimitive).content })
    }

    @Test fun outcomes() {
        assertNull(R.uploadOutcome(200))
        assertEquals(ShareSaveOutcome.ImageTooLarge, R.uploadOutcome(413))
        assertEquals(ShareSaveOutcome.Create(InspCreateOutcome.SignedOut), R.uploadOutcome(401))
        assertEquals("3xx 不算传上", ShareSaveOutcome.Create(InspCreateOutcome.Failed), R.uploadOutcome(302))
        assertEquals(ShareSaveOutcome.Create(InspCreateOutcome.ServerError(500)), R.uploadOutcome(500))
        assertEquals(ShareSaveOutcome.Saved, R.createOutcome(201))
        assertEquals(ShareSaveOutcome.Create(InspCreateOutcome.Rejected(409)), R.createOutcome(409))
        assertEquals(ShareSaveOutcome.Create(InspCreateOutcome.SignedOut), R.createOutcome(401))
        assertEquals("请求闸拦下 → 未登录", ShareSaveOutcome.Create(InspCreateOutcome.SignedOut), R.outcome(InspTransportError.SignedOutBlocked))
        assertEquals(ShareSaveOutcome.Create(InspCreateOutcome.Offline), R.outcome(InspTransportError.NotConnected))
        assertEquals("超时折回连不上", ShareSaveOutcome.Create(InspCreateOutcome.Offline), R.outcome(InspTransportError.TimedOut))
        assertEquals(ShareSaveOutcome.Create(InspCreateOutcome.NotConfigured), R.outcome(InspTransportError.BadUrl))
    }

    /** Android 独有：Uploads 的分档 → 分享这张表。 */
    @Test fun uploadErrorMapping() {
        assertEquals(ShareSaveOutcome.ImageTooLarge, R.uploadOutcome(UploadError.FileTooLarge))
        assertEquals(ShareSaveOutcome.ImageTooLarge, R.uploadOutcome(UploadError.BodyTooLarge))
        assertEquals(ShareSaveOutcome.Create(InspCreateOutcome.SignedOut), R.uploadOutcome(UploadError.Unauthorized))
        assertEquals(ShareSaveOutcome.Create(InspCreateOutcome.SignedOut), R.uploadOutcome(UploadError.SessionInvalid))
        assertEquals(ShareSaveOutcome.Create(InspCreateOutcome.Offline), R.uploadOutcome(UploadError.Offline))
        assertFalse(R.reply(R.uploadOutcome(UploadError.StorageFull(1, 2, 3))).ok)
        assertFalse(R.reply(R.uploadOutcome(UploadError.Server)).ok)
    }

    @Test fun replies() {
        val all = listOf(
            ShareSaveOutcome.Saved, ShareSaveOutcome.ImageTooLarge, ShareSaveOutcome.Create(InspCreateOutcome.Saved),
            ShareSaveOutcome.Create(InspCreateOutcome.SignedOut), ShareSaveOutcome.Create(InspCreateOutcome.NotConfigured),
            ShareSaveOutcome.Create(InspCreateOutcome.Offline), ShareSaveOutcome.Create(InspCreateOutcome.Rejected(422)),
            ShareSaveOutcome.Create(InspCreateOutcome.ServerError(500)), ShareSaveOutcome.Create(InspCreateOutcome.Failed),
        )
        for (o in all) assertEquals("结果 $o → 关不关面板", o == ShareSaveOutcome.Saved || o == ShareSaveOutcome.Create(InspCreateOutcome.Saved), R.reply(o).ok)
        assertEquals("share.insp.tooLarge", R.reply(ShareSaveOutcome.ImageTooLarge).key)
        assertEquals("share.insp.signedOut", R.reply(ShareSaveOutcome.Create(InspCreateOutcome.SignedOut)).key)
        assertEquals("share.insp.renewInApp", R.reply(ShareSaveOutcome.Create(InspCreateOutcome.SignedOut), hostCanRenew = true).key)
        assertFalse(R.reply(ShareSaveOutcome.Create(InspCreateOutcome.SignedOut), hostCanRenew = true).ok)
        assertEquals("share.insp.offline", R.reply(ShareSaveOutcome.Create(InspCreateOutcome.Offline), hostCanRenew = true).key)
        val gates = listOf(InspIntentGate.SignedOut, InspIntentGate.Empty, InspIntentGate.Send("x"))
        for (g in gates) assertFalse("执行前那一道（$g）一律不是成功", R.reply(g).ok)
        val produced = (all.map { R.reply(it).key } + gates.map { R.reply(it).key } + listOfNotNull(R.droppedKey(1)) +
            R.reply(ShareSaveOutcome.Create(InspCreateOutcome.SignedOut), hostCanRenew = true).key).toSet()
        assertEquals("给出的 key 与 allKeys 一一对应", R.allKeys.toSet(), produced)
    }

    /** 每个 key 在中英两份 strings_share.xml 里都有（iOS swiftcheck 核 xcstrings 中英都有）。 */
    @Test fun keysInStrings() {
        for (lang in listOf("values", "values-en")) {
            val xml = listOf("src/main/res/$lang/strings_share.xml", "app/src/main/res/$lang/strings_share.xml").map { File(it) }.firstOrNull { it.exists() } ?: continue
            val text = xml.readText()
            for (k in R.allKeys) assertTrue("$lang 缺 $k", text.contains("name=\"${k.replace('.', '_')}\""))
        }
    }
}
