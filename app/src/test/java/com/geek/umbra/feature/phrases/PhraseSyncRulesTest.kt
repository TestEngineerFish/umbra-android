package com.geek.umbra.feature.phrases

import com.geek.umbra.net.UmbraJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PhraseSyncRules 的行为用例 —— 照抄 iOS Tools/swifttests/PhraseSyncRulesTests.swift。
 * 这条 bug 是**时序**（回包回来之前本地被改过），所以搭一个和 PhraseStore 同形的 store（版本号 + syncing 闸 +
 * 每次改动后起一次 sync），网络换成逐条 LWW + 墓碑的假服务端，往返途中插一段用户操作；store 里跑的循环就是
 * PhraseSyncRules.run 本身。单线程 runBlocking：途中那几次改动各自 launch 的 sync 在 yield 时跑到闸上。
 */
class PhraseSyncRulesTest {
    private data class FakePhrase(val id: String, var content: String, var updatedAt: Int)
    private data class FakeTomb(val id: String, val deletedAt: Int)
    private class FakeBundle(val items: List<FakePhrase>, val deleted: List<FakeTomb>)

    private class FakeServer {
        val items = mutableMapOf<String, FakePhrase>()
        val tombs = mutableMapOf<String, FakeTomb>()
        val duringFlight = mutableListOf<() -> Unit>()
        var failNext = false
        var calls = 0

        suspend fun roundtrip(inItems: List<FakePhrase>, inTombs: List<FakeTomb>): FakeBundle? {
            calls += 1
            if (failNext) { failNext = false; runHook(); return null }
            for (t in inTombs) if ((tombs[t.id]?.deletedAt ?: Int.MIN_VALUE) < t.deletedAt) tombs[t.id] = t
            for (i in inItems) if ((items[i.id]?.updatedAt ?: Int.MIN_VALUE) < i.updatedAt) items[i.id] = i.copy()
            for ((id, t) in tombs.toMap()) if ((items[id]?.updatedAt ?: Int.MAX_VALUE) <= t.deletedAt) items.remove(id)
            val out = FakeBundle(items.values.sortedBy { it.id }.map { it.copy() }, tombs.values.sortedBy { it.id })
            runHook()
            yield() // 让途中那几次改动各自开的 sync 有机会跑到闸上
            return out
        }

        private fun runHook() { if (duringFlight.isNotEmpty()) duringFlight.removeAt(0)() }
    }

    private class FakeStore(val server: FakeServer, val scope: CoroutineScope) {
        var items = mutableListOf<FakePhrase>()
        var tombs = mutableListOf<FakeTomb>()
        var syncing = false
        var revision = 0
        var lastOutcome: PhraseSyncRules.Outcome? = null
        var clock = 1_000
        private fun tick(): Int { clock += 1; return clock }
        private fun commitLocal() { revision += 1 }

        fun add(id: String, content: String) { items.add(FakePhrase(id, content, tick())); commitLocal(); scope.launch { sync() } }
        fun update(id: String, content: String) {
            val p = items.firstOrNull { it.id == id } ?: return
            p.content = content; p.updatedAt = tick(); commitLocal(); scope.launch { sync() }
        }
        fun delete(id: String) {
            items.removeAll { it.id == id }
            if (tombs.none { it.id == id }) tombs.add(FakeTomb(id, tick()))
            commitLocal(); scope.launch { sync() }
        }

        suspend fun sync() {
            if (syncing) return
            syncing = true
            try {
                lastOutcome = PhraseSyncRules.run(
                    revision = { revision },
                    push = { server.roundtrip(items.map { it.copy() }, tombs.toList()) },
                    apply = { merged -> items = merged.items.toMutableList(); tombs = merged.deleted.toMutableList() },
                )
            } finally { syncing = false }
        }

        val ids get() = items.map { it.id }.sorted()
        val tombIds get() = tombs.map { it.id }.sorted()
    }

    private suspend fun seeded(scope: CoroutineScope): FakeStore {
        val s = FakeStore(FakeServer(), scope)
        s.items = mutableListOf(FakePhrase("A", "a", 1), FakePhrase("B", "b", 1))
        s.sync()
        return s
    }

    private suspend fun settle() = repeat(20) { yield() }

    @Test fun verdicts() {
        assertEquals(PhraseSyncRules.Verdict.Apply, PhraseSyncRules.verdict(3, 3, 1))
        assertEquals(PhraseSyncRules.Verdict.Resend, PhraseSyncRules.verdict(3, 4, 1))
        assertEquals(PhraseSyncRules.Verdict.Apply, PhraseSyncRules.verdict(3, 3, PhraseSyncRules.maxRounds))
        assertEquals(PhraseSyncRules.Verdict.Hold, PhraseSyncRules.verdict(3, 5, PhraseSyncRules.maxRounds))
        assertTrue(PhraseSyncRules.maxRounds >= 2)
    }

    @Test fun issueRepro() = runBlocking {
        val s = seeded(this)
        s.server.duringFlight.add { s.delete("B"); s.add("C", "途中新建的那段话") }
        s.items.removeAll { it.id == "A" }
        s.tombs.add(FakeTomb("A", 1_500))
        s.revision += 1
        s.sync(); settle()
        assertEquals(listOf("C"), s.ids)
        assertEquals("途中删除的墓碑被回包冲掉了", listOf("A", "B"), s.tombIds)
        assertEquals(listOf("C"), s.server.items.keys.sorted())
        assertEquals("途中新建的那段话", s.items.first().content)
        assertEquals(PhraseSyncRules.Outcome.Applied, s.lastOutcome)
    }

    @Test fun midFlightAdd() = runBlocking {
        val s = seeded(this)
        s.server.duringFlight.add { s.add("N", "新的") }
        s.sync(); settle()
        assertEquals(listOf("A", "B", "N"), s.ids)
        assertEquals("新的", s.server.items["N"]?.content)
    }

    @Test fun midFlightDelete() = runBlocking {
        val s = seeded(this)
        s.server.duringFlight.add { s.delete("A") }
        s.sync(); settle()
        assertEquals(listOf("B"), s.ids); assertEquals(listOf("A"), s.tombIds); assertNull(s.server.items["A"])
    }

    @Test fun midFlightEdit() = runBlocking {
        val s = seeded(this)
        s.server.duringFlight.add { s.update("A", "改过的") }
        s.sync(); settle()
        assertEquals("改过的", s.items.first { it.id == "A" }.content)
        assertEquals("改过的", s.server.items["A"]?.content)
    }

    @Test fun quietFlight() = runBlocking {
        val s = seeded(this)
        val before = s.server.calls
        s.server.items["X"] = FakePhrase("X", "别的端加的", 5)
        s.sync()
        assertEquals(1, s.server.calls - before)
        assertEquals(listOf("A", "B", "X"), s.ids)
        assertEquals(PhraseSyncRules.Outcome.Applied, s.lastOutcome)
    }

    @Test fun failure() = runBlocking {
        val s = seeded(this)
        s.add("L", "本地的"); settle()
        val items = s.items.map { it.copy() }; val tombs = s.tombs.toList()
        s.server.failNext = true
        s.server.items["X"] = FakePhrase("X", "x", 5)
        s.sync()
        assertEquals(PhraseSyncRules.Outcome.Failed, s.lastOutcome)
        assertEquals(items, s.items); assertEquals(tombs, s.tombs)
    }

    @Test fun keepTyping() = runBlocking {
        val s = seeded(this)
        val before = s.server.calls
        var n = 0
        repeat(PhraseSyncRules.maxRounds + 3) { s.server.duringFlight.add { n += 1; s.add("K$n", "第 $n 条") } }
        s.sync(); settle()
        assertEquals("没在上限处停下", PhraseSyncRules.maxRounds, s.server.calls - before)
        assertEquals(PhraseSyncRules.Outcome.Deferred, s.lastOutcome)
        assertEquals(2 + PhraseSyncRules.maxRounds, s.items.size)
        s.server.duringFlight.clear()
        s.sync()
        assertEquals(PhraseSyncRules.Outcome.Applied, s.lastOutcome)
        assertEquals(2 + PhraseSyncRules.maxRounds, s.server.items.size)
    }

    // MARK: umbra-pc#83：内容与顺序分开盖戳（真的 Phrase 与真的 stamp）

    private fun P(id: String, order: Int, ts: Long = 100, content: String? = null) =
        Phrase(id, id.uppercase(), content ?: "c-$id", null, order, ts)

    private fun by(list: List<Phrase>, id: String) = list.firstOrNull { it.id == id }

    @Test fun stamp() {
        val now = 9_000L
        run {
            val prev = listOf(P("a", 0), P("b", 1), P("c", 2), P("d", 3))
            val next = listOf(prev[2], prev[0], prev[1], prev[3]).mapIndexed { i, p -> p.copy(order = i) }
            val out = PhraseSyncRules.stamp(next, prev, now)
            for (id in listOf("a", "b", "c")) { assertEquals(100L, by(out, id)?.updatedAt); assertEquals(now, by(out, id)?.orderUpdatedAt) }
            assertEquals(100L, by(out, "d")?.updatedAt); assertEquals(100L, by(out, "d")?.orderUpdatedAt)
        }
        run {
            val prev = listOf(P("a", 0), P("x", 1), P("c", 2))
            val out = PhraseSyncRules.stamp(listOf(prev[1], prev[2]).mapIndexed { i, p -> p.copy(order = i) }, prev, now)
            assertEquals(listOf(100L, 100L), out.map { it.updatedAt }); assertEquals(listOf(now, now), out.map { it.orderUpdatedAt })
        }
        run {
            val prev = listOf(P("a", 0), P("b", 1))
            val out = PhraseSyncRules.stamp(listOf(P("n", 0, 0), prev[0], prev[1]).mapIndexed { i, p -> p.copy(order = i) }, prev, now)
            assertEquals(now, out[0].updatedAt); assertEquals(now, out[0].orderUpdatedAt)
            assertEquals(listOf(100L, 100L), out.drop(1).map { it.updatedAt })
        }
        run {
            val prev = listOf(P("a", 0), P("b", 1))
            val out = PhraseSyncRules.stamp(prev, prev, now)
            assertEquals(listOf(100L, 100L), out.map { it.updatedAt }); assertEquals(listOf(100L, 100L), out.map { it.orderUpdatedAt })
        }
        run {
            val prev = listOf(P("a", 0), P("b", 1))
            val out = PhraseSyncRules.stamp(listOf(prev[0], prev[1].copy(name = "B2")), prev, now)
            assertEquals(100L, out[0].updatedAt); assertEquals(now, out[1].updatedAt)
            assertEquals(100L, out[1].orderUpdatedAt); assertEquals(100L, out[1].orderStamp)
        }
        run {
            val prev = listOf(P("a", 0), P("b", 1))
            val out = PhraseSyncRules.stamp(prev + P("n", 2, 0), prev, now)
            assertEquals(listOf(100L, 100L), out.take(2).map { it.updatedAt }); assertEquals(listOf(100L, 100L), out.take(2).map { it.orderUpdatedAt })
        }
        run {
            val prev = listOf(P("a", 0), P("x", 1), P("c", 2))
            val out = PhraseSyncRules.stamp(prev.drop(1), prev, now)
            assertEquals(listOf(100L, 100L), out.map { it.updatedAt }); assertEquals(listOf(100L, 100L), out.map { it.orderUpdatedAt })
        }
        run {
            val a = P("a", 0).copy(keyword = "")
            assertEquals("触发词 \"\" → null 被当成改了内容", 100L, PhraseSyncRules.stamp(listOf(a.copy(keyword = null)), listOf(a), now)[0].updatedAt)
        }
        run {
            val old = listOf(P("a", 0, 300), P("b", 1, 400))
            assertEquals(listOf(300L, 400L), PhraseSyncRules.stamp(old, old, now).map { it.orderUpdatedAt })
            val out = PhraseSyncRules.stamp(listOf(old[1], old[0]).mapIndexed { i, p -> p.copy(order = i) }, old, now)
            assertEquals(listOf(400L, 300L), out.map { it.updatedAt }); assertEquals(listOf(now, now), out.map { it.orderUpdatedAt })
        }
        run {
            val z = listOf(P("a", 0, 0))
            assertEquals(now, PhraseSyncRules.stamp(z, z, now)[0].updatedAt)
        }
    }

    @Test fun wire() {
        val p = Phrase.decode(UmbraJson.parseToJsonElement("""{"id":"a","name":"A","content":"c","order":0,"updatedAt":123}"""))!!
        assertEquals("a", p.id); assertNull(p.orderUpdatedAt); assertEquals(123L, p.orderStamp)
        assertEquals(456L, Phrase.decode(UmbraJson.parseToJsonElement("""{"id":"a","name":"A","content":"c","keyword":"","order":0,"updatedAt":123,"orderUpdatedAt":456}"""))!!.orderStamp)
        assertEquals(123L, p.wire()["orderUpdatedAt"]!!.jsonPrimitive.long)
        val q = P("q", 3, 100).copy(orderUpdatedAt = 777)
        assertEquals(777L, q.wire()["orderUpdatedAt"]!!.jsonPrimitive.long)
        assertEquals(100L, q.wire()["updatedAt"]!!.jsonPrimitive.long)
        assertTrue("keyword 空时不带键", !q.wire().containsKey("keyword") && !p.copy(keyword = "").wire().containsKey("keyword"))
        assertEquals("日报", P("k", 0).copy(keyword = "日报").wire()["keyword"]!!.jsonPrimitive.content)
        assertEquals(777L, PhraseBundle.decodeLossy(PhraseBundle(listOf(q), emptyList()).encode())!!.first.items.first().orderUpdatedAt)
        // 逐条丢坏、deleted 缺键当空。
        val lossy = PhraseBundle.decodeLossy(UmbraJson.parseToJsonElement("""{"items":[{"id":"a","name":"A","content":"c","order":0,"updatedAt":1},{"id":"b"}]}"""))!!
        assertEquals(listOf("a"), lossy.first.items.map { it.id }); assertTrue(lossy.second); assertTrue(lossy.first.deleted.isEmpty())
    }

    /** umbra-pc#83 端到端：B 改了 x 的内容；本机拿着旧内容拖了一下顺序 → x 的内容是 B 的、顺序是本机的。 */
    @Test fun pc83() {
        val server = linkedMapOf<String, Phrase>()
        fun serverSync(items: List<Phrase>): List<Phrase> {
            for (it in items) {
                val ts = it.updatedAt; val ots = it.orderUpdatedAt ?: ts
                val prev = server[it.id]
                if (prev == null) { server[it.id] = it.copy(orderUpdatedAt = ots); continue }
                var cur = prev
                if (ts > prev.updatedAt) cur = cur.copy(name = it.name, content = it.content, keyword = it.keyword, updatedAt = ts)
                if (ots > prev.orderStamp) cur = cur.copy(order = it.order, orderUpdatedAt = ots)
                server[it.id] = cur
            }
            return server.values.sortedBy { it.order }
        }
        val base = listOf(P("a", 0, 5_000), P("x", 1, 5_000, "X-old"), P("c", 2, 5_000))
        serverSync(base)
        serverSync(listOf(base[1].copy(content = "X-EDITED-ON-B", updatedAt = 7_000)))
        val list = listOf(base[2], base[0], base[1]).mapIndexed { i, p -> p.copy(order = i) }
        val merged = serverSync(PhraseSyncRules.stamp(list, base, 9_000))
        assertEquals("X-EDITED-ON-B", by(merged, "x")?.content)
        assertEquals(listOf("c", "a", "x"), merged.map { it.id })
    }
}
