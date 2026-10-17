package com.geek.umbra.feature.knowledge

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

/**
 * KnowledgeRules 用例。输入输出照抄 iOS Tools/swifttests/KnowledgeRulesTests.swift。
 * iOS 那几件平台专属的（JSONSerialization / NSNumber 的页码、HistoryCitations 的 Codable 壳、URLError、KbMultipart 拼体、
 * URL.standardized）换成 Android 对应的：kotlinx JsonElement、IOException / CancellationException、readCapped（边读边数）。
 */
class KnowledgeRulesTest {
    private fun cit(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) ->
            when (v) {
                is String -> put(k, v)
                is Number -> put(k, JsonPrimitive(v))
                null -> put(k, JsonNull)
            }
        }
    }

    private val good = cit("kb_id" to "k1", "path" to "合同/采购.pdf", "title" to "采购合同", "heading_path" to "采购合同 › 第四条 付款", "page" to 3)

    @Test fun citations() {
        val parsed = KbCitations.parse(JsonArray(listOf(good)))
        assertEquals(1, parsed.size)
        assertEquals(3, parsed.first().page)
        assertEquals("k1", parsed.first().kbId)
        assertEquals(emptyList<KbCitation>(), KbCitations.parse(null))
        assertEquals(emptyList<KbCitation>(), KbCitations.parse(JsonPrimitive("oops")))
        assertEquals(0, KbCitations.parse(JsonArray(listOf(cit("path" to "a.md")))).size)
        assertEquals(0, KbCitations.parse(JsonArray(listOf(cit("kb_id" to "k1")))).size)
        assertEquals(0, KbCitations.parse(JsonArray(listOf(cit("kb_id" to "", "path" to "a.md")))).size)
        assertEquals(1, KbCitations.parse(JsonArray(listOf(JsonPrimitive(42), JsonPrimitive("x"), good))).size)
        assertEquals(1, KbCitations.parse(JsonArray(listOf(good, good))).size)
        val otherPage = JsonObject(good + ("page" to JsonPrimitive(4)))
        assertEquals(2, KbCitations.parse(JsonArray(listOf(good, otherPage))).size)
        val noTitle = KbCitations.parse(JsonArray(listOf(cit("kb_id" to "k1", "path" to "资料/说明书.md"))))
        assertEquals("说明书.md", noTitle.first().title)
        assertEquals(0, noTitle.first().page)
        assertEquals("", noTitle.first().headingPath)
        assertEquals(2, KbCitations.parse(JsonArray(listOf(cit("kb_id" to "k", "path" to "a.pdf", "page" to 2.0)))).first().page)
        assertEquals(0, KbCitations.parse(JsonArray(listOf(cit("kb_id" to "k", "path" to "a.pdf", "page" to -1)))).first().page)
    }

    @Test fun labels() {
        fun c(t: String, h: String) = KbCitation("k", "p", t, h, 0)
        assertEquals("采购合同 › 第四条 付款", KbCitations.label(c("采购合同", "采购合同 › 第四条 付款")))
        assertEquals("采购合同", KbCitations.label(c("采购合同", "采购合同")))
        assertEquals("采购.pdf › 第四条 付款", KbCitations.label(c("采购.pdf", "第四条 付款")))
        assertEquals("采购.pdf", KbCitations.label(c("采购.pdf", "")))
        assertEquals("采购 › 采购合同 › 附件", KbCitations.label(c("采购", "采购合同 › 附件")))
    }

    private fun o(s: Int, code: String? = null, state: String? = null, d: String = "") = KnowledgeRules.outcome(KbWriteResponse(s, code, state, d))

    @Test fun outcomes() {
        assertEquals(KbOutcome.Ok, o(200))
        assertEquals(KbOutcome.Ok, o(201))
        assertEquals(KbOutcome.ChangedSince, o(412, "state_conflict", "blob_changed"))
        assertEquals(KbOutcome.ChangedSince, o(412))
        assertEquals(KbOutcome.Busy, o(409, "state_conflict", "parsing"))
        assertEquals(KbOutcome.Disabled, o(503, "feature_disabled"))
        assertEquals(KbOutcome.Missing, o(404, "not_found"))
        assertEquals(KbOutcome.Missing, o(404))
        assertEquals(KbOutcome.Duplicate, o(409, "duplicate"))
        assertEquals(KbOutcome.TooLarge, o(413, "file_too_large"))
        assertEquals(KbOutcome.TooLarge, o(413))
        val full = KbWriteResponse(413, "storage_full", null, "存储已满", used = 1_000, limit = 1_024, incoming = 100)
        assertEquals(KbOutcome.StorageFull(1_000, 1_024, 100), KnowledgeRules.outcome(full))
        assertEquals("kb.fail.storageFull", KnowledgeRules.messageKey(KbOutcome.StorageFull(1, 2, 3)))
        assertFalse(o(413, "storage_full") == KbOutcome.TooLarge)
        assertEquals(KbOutcome.Auth, o(401, "session_invalid"))
        assertEquals(KbOutcome.Auth, o(401))
        assertEquals(KbOutcome.Auth, o(403))
        assertEquals(KbOutcome.Offline, o(0, null, null, "The Internet connection appears to be offline."))
        assertEquals(KbOutcome.Failed("路径非法"), o(500, "rejected", null, "  路径非法 "))
        assertEquals(KbOutcome.Failed("HTTP 502"), o(502))
        assertEquals("kb.fail.changedSince", KnowledgeRules.messageKey(KbOutcome.ChangedSince))
        assertEquals("kb.fail.server", KnowledgeRules.messageKey(KbOutcome.Failed("x")))
        assertEquals("", KnowledgeRules.messageKey(KbOutcome.Ok))
    }

    @Test fun states() {
        assertEquals(KbDocState.Queued, KbDocState.of("queued"))
        assertEquals(KbDocState.Parsing, KbDocState.of("parsing"))
        assertEquals(KbDocState.Embedding, KbDocState.of("embedding"))
        assertEquals(KbDocState.Ready, KbDocState.of("ready"))
        assertEquals(KbDocState.Failed, KbDocState.of("failed"))
        assertEquals(KbDocState.Queued, KbDocState.of("rebuilding"))
        assertEquals(KbDocState.Queued, KbDocState.of(""))
        assertFalse(KbDocState.Ready.busy)
        assertFalse(KbDocState.Failed.busy)
        assertTrue(KbDocState.Parsing.busy)
        assertTrue(KbDocState.Queued.busy)
        assertEquals(KbTone.Success, KbDocState.Ready.tone)
        assertEquals(KbTone.Danger, KbDocState.Failed.tone)
        assertEquals(KbTone.Working, KbDocState.Embedding.tone)
        assertEquals("kb.state.failed", KbDocState.Failed.labelKey)
    }

    @Test fun paths() {
        assertEquals("/kb/k1/raw/%E5%90%88%E5%90%8C/a%20b.pdf", KnowledgeRules.rawPath("k1", "合同/a b.pdf"))
        assertEquals("/kb/k1/raw/x%231%3F.md", KnowledgeRules.rawPath("k1", "x#1?.md"))
    }

    @Test fun limits() {
        assertFalse(KnowledgeRules.overLimit(32L * 1024 * 1024))
        assertTrue(KnowledgeRules.overLimit(32L * 1024 * 1024 + 1))
        assertEquals("512 B", KnowledgeRules.humanBytes(512))
        assertEquals("2 KB", KnowledgeRules.humanBytes(2048))
        assertEquals("3.5 MB", KnowledgeRules.humanBytes(3L * 1024 * 1024 + 512 * 1024))
    }

    /** 页码只收「正好是整数、而且装得下」的（kb-citation-page-double-trap）。 */
    @Test fun pageTrap() {
        fun page(v: JsonPrimitive): Int? = KbCitations.parse(JsonArray(listOf(JsonObject(mapOf("kb_id" to JsonPrimitive("k"), "path" to JsonPrimitive("a.pdf"), "page" to v))))).firstOrNull()?.page
        assertEquals(0, page(JsonPrimitive(1e20)))
        assertEquals(0, page(JsonPrimitive(-1e20)))
        assertEquals(0, page(JsonPrimitive(Double.NaN)))
        assertEquals(0, page(JsonPrimitive(Double.POSITIVE_INFINITY)))
        assertEquals(0, page(JsonPrimitive(2.5)))
        assertEquals(0, page(JsonPrimitive(-1)))
        assertEquals(7, page(JsonPrimitive(7)))
        assertEquals(0, page(JsonPrimitive("3")))
        // 真实形状：服务端 JSON 文本解出来（WS 帧那一路）。
        val wire = Json.parseToJsonElement("""[{"kb_id":"k","path":"a.pdf","page":1e20},{"kb_id":"k","path":"b.pdf","page":4}]""")
        assertEquals(listOf(0, 4), KbCitations.parse(wire).map { it.page })
        // 历史那一路：混了 null / 数字、kb_id 是数字的项丢掉，好的留下；citations 不是数组 → 零项。
        val mixed = Json.parseToJsonElement("""[null, 42, {"kb_id":7,"path":"a.pdf"}, {"kb_id":"k","path":"a.pdf","title":"甲","page":3}]""")
        assertEquals(1, KbCitations.parse(mixed).size)
        assertEquals(0, KbCitations.parse(Json.parseToJsonElement("\"oops\"")).size)
        assertEquals(0, KbCitations.parse(Json.parseToJsonElement("{}")).size)
    }

    @Test fun transport() {
        fun t(e: Throwable) = KnowledgeRules.outcome(KnowledgeRules.transport(e))
        assertEquals(KbOutcome.Offline, t(IOException("offline")))
        assertEquals(KbOutcome.Offline, t(java.net.SocketTimeoutException("timeout")))
        assertEquals(KbOutcome.Cancelled, t(CancellationException("x")))
        assertEquals(KbOutcome.Unreadable, t(KbFileError(KbFileError.Kind.Unreadable)))
        assertEquals(KbOutcome.TooLarge, t(KbFileError(KbFileError.Kind.TooLarge)))
        val disk = t(IllegalStateException("No space left on device"))
        assertTrue(disk is KbOutcome.Local)
        assertFalse(disk == KbOutcome.Offline)
        assertEquals("kb.fail.local", KnowledgeRules.messageKey(KbOutcome.Local("x")))
        assertEquals("", KnowledgeRules.messageKey(KbOutcome.Cancelled))
        assertEquals("kb.readFail", KnowledgeRules.messageKey(KbOutcome.Unreadable))
        assertEquals(KbOutcome.Auth, KnowledgeRules.outcome(KbWriteResponse(0, KnowledgeRules.LocalCode.SIGNED_OUT, null, "")))
        assertEquals(KbOutcome.Offline, KnowledgeRules.outcome(KbWriteResponse(0, "bad url", null, "")))
    }

    /** 边读边数（iOS KbMultipart.assemble 那道）：大小元数据说谎时也挡得住；跨块不丢不重；读不到 → Unreadable。 */
    @Test fun readCapped() {
        assertArrayEquals("hello".toByteArray(), KnowledgeRules.readCapped(ByteArrayInputStream("hello".toByteArray()), 5))
        try { KnowledgeRules.readCapped(ByteArrayInputStream("hello".toByteArray()), 4); fail() } catch (e: KbFileError) { assertEquals(KbFileError.Kind.TooLarge, e.kind) }
        val blob = ByteArray((1 shl 16) * 2 + 17) { (it and 0xff).toByte() }
        assertArrayEquals(blob, KnowledgeRules.readCapped(ByteArrayInputStream(blob), blob.size.toLong()))
        val broken = object : InputStream() { override fun read(): Int = throw IOException("gone") }
        try { KnowledgeRules.readCapped(broken, 100); fail() } catch (e: KbFileError) { assertEquals(KbFileError.Kind.Unreadable, e.kind) }
    }

    @Test fun loadSeq() {
        val s = KbLoadSeq()
        val poll = s.next()
        val afterDelete = s.next()
        assertTrue(s.isLatest(afterDelete))
        assertFalse(s.isLatest(poll))
        val again = s.next()
        assertFalse(s.isLatest(afterDelete))
        assertTrue(s.isLatest(again))
    }

    @Test fun dotSegments() {
        assertEquals("%2E%2E", KnowledgeRules.segment(".."))
        assertEquals("%2E", KnowledgeRules.segment("."))
        assertEquals("a.b", KnowledgeRules.segment("a.b"))
        assertEquals("...", KnowledgeRules.segment("..."))
        assertEquals("..a", KnowledgeRules.segment("..a"))
        assertEquals("/kb/k/raw/a/%2E%2E/secret.md", KnowledgeRules.rawPath("k", "a/../secret.md"))
        assertEquals("/kb/%2E%2E/raw/x.md", KnowledgeRules.rawPath("..", "x.md"))
        // 实跑一遍「下游规范化」：编过的不再被折叠；字面 .. 会被折叠（前提）。
        assertEquals("https://h/kb/k/raw/a/%2E%2E/secret.md", java.net.URI("https://h/kb/k/raw/a/%2E%2E/secret.md").normalize().toString())
        assertEquals("https://h/kb/k/raw/secret.md", java.net.URI("https://h/kb/k/raw/a/../secret.md").normalize().toString())
    }

    @Test fun deleteVersion() {
        assertEquals("abc123", KnowledgeRules.ifMatch("abc123"))
        assertEquals("abc", KnowledgeRules.ifMatch(" abc \n"))
        assertNull(KnowledgeRules.ifMatch(null))
        assertNull(KnowledgeRules.ifMatch(""))
        assertNull(KnowledgeRules.ifMatch("   "))
        assertEquals(KbOutcome.NoVersion, KnowledgeRules.outcome(KbWriteResponse(0, KnowledgeRules.LocalCode.NO_VERSION, null, "")))
        assertEquals("kb.fail.noVersion", KnowledgeRules.messageKey(KbOutcome.NoVersion))
    }

    @Test fun notOwner() {
        assertEquals(KbOutcome.Failed("只有这台服务器的主人能做"), KnowledgeRules.outcome(KbWriteResponse(403, "not_owner", null, " 只有这台服务器的主人能做 ")))
        assertEquals(KbOutcome.Failed("HTTP 403"), KnowledgeRules.outcome(KbWriteResponse(403, "not_owner", null, "")))
        assertEquals(KbOutcome.Auth, KnowledgeRules.outcome(KbWriteResponse(403, null, null, "")))
    }

    @Test fun poll() {
        assertFalse(KnowledgeRules.shouldPoll(false, KbOutcome.Ok))
        assertTrue(KnowledgeRules.shouldPoll(true, KbOutcome.Ok))
        assertFalse(KnowledgeRules.shouldPoll(true, KbOutcome.Missing))
        assertFalse(KnowledgeRules.shouldPoll(true, KbOutcome.Disabled))
        assertFalse(KnowledgeRules.shouldPoll(true, KbOutcome.Auth))
        assertTrue(KnowledgeRules.shouldPoll(true, KbOutcome.Offline))
        assertTrue(KnowledgeRules.shouldPoll(true, KbOutcome.Failed("HTTP 502")))
    }

    @Test fun uploadEnd() {
        fun r(status: Int, code: String? = null) = KbWriteResponse(status, code, null, "")
        fun end(x: KbWriteResponse, indexed: Boolean? = null, changed: Boolean? = null, cancelled: Boolean = false) =
            KnowledgeRules.uploadEnd(x, indexed, changed, cancelled)
        val L = KnowledgeRules.LocalCode
        assertEquals(KbUploadEnd(KbUploadEnd.Say.Uploaded, true), end(r(201)))
        assertEquals(KbUploadEnd(KbUploadEnd.Say.Uploaded, true), end(r(201), changed = true))
        assertEquals(KbUploadEnd(KbUploadEnd.Say.Unchanged, true), end(r(201), changed = false))
        assertEquals(KbUploadEnd(KbUploadEnd.Say.Unindexed, true), end(r(201), indexed = false))
        assertEquals(KbUploadEnd(KbUploadEnd.Say.Uploaded, true), end(r(201), cancelled = true))
        assertEquals(KbUploadEnd(KbUploadEnd.Say.Cancelled, true), end(r(0, L.CANCELLED), cancelled = true))
        assertEquals(KbUploadEnd(KbUploadEnd.Say.Cancelled, true), end(r(0, null), cancelled = true))
        assertEquals(KbUploadEnd(KbUploadEnd.Say.Cancelled, false), end(r(0, L.UNREADABLE), cancelled = true))
        assertEquals(KbUploadEnd(KbUploadEnd.Say.Silent, true), end(r(0, L.CANCELLED)))
        assertEquals(KbUploadEnd(KbUploadEnd.Say.TooLarge, false), end(r(0, L.TOO_LARGE)))
        assertEquals(KbUploadEnd(KbUploadEnd.Say.TooLarge, true), end(r(413, "file_too_large")))
        assertEquals(KbUploadEnd(KbUploadEnd.Say.Fail(KbOutcome.Unreadable), false), end(r(0, L.UNREADABLE)))
        assertEquals(KbUploadEnd(KbUploadEnd.Say.Fail(KbOutcome.Offline), true), end(r(0, null)))
        assertEquals(KbUploadEnd(KbUploadEnd.Say.Fail(KbOutcome.Failed("HTTP 500")), true), end(r(500)))
    }

    @Test fun previewSweep() {
        val tmp = Files.createTempDirectory("kbsweep").toFile()
        try {
            val root = KbPreviewTemp.root(tmp)
            assertEquals(0, KbPreviewTemp.sweep(root))
            val old = File(root, "old"); val fresh = File(root, "fresh")
            listOf(old, fresh).forEach { it.mkdirs(); File(it, "合同.pdf").writeText("x") }
            val now = System.currentTimeMillis()
            old.setLastModified(now - KbPreviewTemp.STALE_AFTER_MS - 60_000)
            fresh.setLastModified(now - 5_000)
            assertEquals(1, KbPreviewTemp.sweep(root, now))
            assertFalse(old.exists())
            assertTrue(fresh.exists())
        } finally { tmp.deleteRecursively() }
    }

    @Test fun pickFilter() {
        assertTrue(KnowledgeRules.isPickable("合同.pdf"))
        assertTrue(KnowledgeRules.isPickable("合同.PDF"))
        assertTrue(KnowledgeRules.isPickable("说明.Markdown"))
        assertTrue(KnowledgeRules.isPickable("a.tar.txt"))
        assertFalse(KnowledgeRules.isPickable("run.sh"))
        assertFalse(KnowledgeRules.isPickable("app.log"))
        assertFalse(KnowledgeRules.isPickable("旧版.doc"))
        assertFalse(KnowledgeRules.isPickable("README"))
        assertFalse(KnowledgeRules.isPickable(".md"))
        assertFalse(KnowledgeRules.isPickable("a.pdf.zip"))
    }

    @Test fun nameClash() {
        val have = listOf("合同.pdf", "资料/说明.md", "Notes.TXT")
        assertEquals(KbNameCheck.Free, KnowledgeRules.nameCheck("新.pdf", have))
        assertEquals(KbNameCheck.Clash("合同.pdf"), KnowledgeRules.nameCheck("合同.pdf", have))
        assertEquals(KbNameCheck.Clash("Notes.TXT"), KnowledgeRules.nameCheck("notes.txt", have))
        assertEquals(KbNameCheck.Free, KnowledgeRules.nameCheck("说明.md", have))
        // NFD / NFC 算同名，回库里那份的**字节**（NFC）。
        val nfd = "é.md"; val nfc = "é.md"
        val hit = KnowledgeRules.nameCheck(nfd, listOf(nfc))
        assertTrue(hit is KbNameCheck.Clash)
        assertArrayEquals(nfc.toByteArray(), (hit as KbNameCheck.Clash).existing.toByteArray())
        assertEquals(KbNameCheck.Unknown, KnowledgeRules.nameCheck("合同.pdf", null))
        assertEquals(KbNameCheck.Free, KnowledgeRules.nameCheck("合同.pdf", emptyList()))
        fun up(c: KbNameCheck, ch: KbClashChoice) = KnowledgeRules.uploadName("notes.txt", c, ch, have)
        assertEquals("notes.txt", up(KbNameCheck.Free, KbClashChoice.Cancel))
        assertEquals("Notes.TXT", up(KbNameCheck.Clash("Notes.TXT"), KbClashChoice.Overwrite))
        assertEquals("notes (2).txt", up(KbNameCheck.Clash("Notes.TXT"), KbClashChoice.KeepBoth))
        assertNull(up(KbNameCheck.Clash("Notes.TXT"), KbClashChoice.Cancel))
        assertEquals("notes.txt", up(KbNameCheck.Unknown, KbClashChoice.Overwrite))
        assertNull(up(KbNameCheck.Unknown, KbClashChoice.KeepBoth))
        assertNull(up(KbNameCheck.Unknown, KbClashChoice.Cancel))
    }

    @Test fun keepBoth() {
        val k = KnowledgeRules::keepBothName
        assertEquals("合同 (2).pdf", k("合同.pdf", listOf("合同.pdf")))
        assertEquals("合同 (3).pdf", k("合同.pdf", listOf("合同.pdf", "合同 (2).pdf")))
        assertEquals("合同 (4).pdf", k("合同.pdf", listOf("合同.pdf", "合同 (2).pdf", "合同 (3).pdf")))
        assertEquals("README (2)", k("README", listOf("README")))
        assertEquals("a.tar (2).md", k("a.tar.md", listOf("a.tar.md")))
        assertEquals(".md (2)", k(".md", listOf(".md")))
        assertEquals("Notes (3).txt", k("Notes.txt", listOf("notes.TXT", "NOTES (2).txt")))
        assertEquals("报告 (2019) (2).pdf", k("报告 (2019).pdf", listOf("报告 (2019).pdf")))
        assertEquals("合同 (2).PDF", k("合同.PDF", listOf("合同.PDF")))
    }

    @Suppress("unused")
    private val keep = buildJsonArray { }
}
