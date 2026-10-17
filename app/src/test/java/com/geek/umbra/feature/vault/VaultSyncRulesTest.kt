// VaultSyncRules.kt 的**行为**用例：逐条照抄 iOS Tools/swifttests/VaultSyncRulesTests.swift（umbra-ios #17 / #18 / #28 与审查台账）。
//
// 这几条都不能靠扫文本：`force` 这个字面量在不在，和它什么时候为 true 是两件事；
// 「有没有一个剔孤儿的函数」，和它在孤儿上返回什么也是两件事。
// 下面喂的是**假服务端**：一个永远回冲突的 PUT、一个永远合并不成的 merge ——
// 断言的是「一次 force:true 都没有发出去」，不是「代码里写了什么」。
// iOS 每一个 expect 在这里都是一个 expect（= assertTrue），文案照抄，方便两端对着看。
package com.geek.umbra.feature.vault

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/** 一条断言（iOS 的 `expect(cond, what)`）。 */
private fun expect(cond: Boolean, what: String) = assertTrue(what, cond)

/** 假服务端记下的每一次 PUT。 */
private class FakeServer {
    val sent = mutableListOf<Pair<String, Boolean>>()
    var encodes = 0
    val forces: List<Boolean> get() = sent.map { it.second }
}

/** 一条测试用记录（iOS `item(_:atts:)`）。 */
private fun item(id: String, atts: List<String>): VItem = VItem(
    id = id, typeId = "t", title = id, icon = null, favorite = null, tags = null, blocks = emptyList(),
    attachments = atts.map { VAtt(it, it, "image/png", 1.0, 0.0) },
    createdAt = 0.0, updatedAt = 0.0, revision = 1.0, deleted = null,
)

/** iOS `snap.data["v", default: VData(...)].items.append(it)`：Kotlin 的快照不可变，返回新的一份。 */
private fun VSnapshot.appendItem(vid: String, it: VItem): VSnapshot {
    val d = data[vid] ?: VData.EMPTY
    return copy(data = data + (vid to d.copy(items = d.items + it)))
}

/** 库 "v" 里的记录 id，排好序（iOS `ids` / `vIds`）。 */
private fun vIds(s: VSnapshot?): List<String> = (s?.data?.get("v")?.items ?: emptyList()).map { it.id }.sorted()

/** pushed 里那份 blob（断言只关心 rev 时拿来凑等式）。 */
private val VaultPushOutcome.blobOrEmpty: String get() = (this as? VaultPushOutcome.Pushed)?.blob ?: ""

/**
 * 假服务端：一份快照 + 一个 rev，PUT 按 `vault_sync.py` 的乐观锁判（baseRev 对不上就回冲突、带回当前那份）。
 * blob 不加密（加密不是这里要测的），就是快照的 JSON。
 */
private class CloudSim(var rev: Int, var snap: VSnapshot) {
    var puts = 0
    val blob: String get() = snap.encode()
    fun put(blob: String, baseRev: Int, force: Boolean): VaultPutReply {
        puts += 1
        if (baseRev != rev && !force) return VaultPutReply.Conflict(rev, this.blob)
        snap = VSnapshot.parse(blob)!!
        rev += 1
        return VaultPutReply.Ok(rev)
    }
}

/**
 * 一台已经解锁的手机，**照 VaultCore 的接线**：pullRecord 先问 `pullAdopt`，推送走 `VaultSyncRules.run`，
 * 合并走 `VaultSyncRules.merge`。`AdoptCloud` 那一格做的正是 #28 之前的 pullRecord：只顶 syncRev、不合并内容。
 */
private class PhoneSim(var snap: VSnapshot, var syncRev: Int, val cloud: CloudSim) {
    var unlocked = true

    fun pullMerge(rev: Int, blob: String): VaultMergeResult {
        if (rev == syncRev) return VaultMergeResult.Failed(VaultMergeFailure.NothingNewer)
        val remote = VSnapshot.parse(blob)!!
        snap = VaultSyncRules.merge(snap, remote)
        syncRev = rev
        return VaultMergeResult.Merged
    }

    fun pullRecord() {
        when (VaultSyncRules.pullAdopt(unlocked, VCacheRead.Missing, "K", false)) {
            VaultSyncRules.PullAdopt.MergeInstead -> pullMerge(cloud.rev, cloud.blob)
            VaultSyncRules.PullAdopt.AdoptCloud, VaultSyncRules.PullAdopt.AdoptCloudKeepFile -> syncRev = cloud.rev // #28 之前：顶 rev、不合并
            VaultSyncRules.PullAdopt.KeepLocal -> Unit
        }
    }

    suspend fun save(it: VItem) {
        snap = snap.appendItem("v", it)
        VaultSyncRules.run(
            sameUnlock = { true },
            encode = { snap.encode() },
            send = { blob, force ->
                val r = cloud.put(blob, syncRev, force)
                if (r is VaultPutReply.Ok) syncRev = r.rev
                r
            },
            merge = { rev, blob -> pullMerge(rev, blob ?: cloud.blob) },
        )
    }
}

/**
 * 一台已解锁的手机，pullMerge 拆成「发出」和「回来」两步，好把别的事插进网络往返中间（审查台账 2026-10-05 第二轮）。
 * `legacy` 照**原来的** VaultCore 写：await 前取 `cur = snapshot`、回来按「rev == 此刻 syncRev」判；
 * 否则照**现在的** VaultCore 写：回来后重取 snapshot、经 `VaultSyncRules.mergeGate` 判（按这一趟的基线、看解锁代次）。
 * legacy 那一支是复现用的 —— 断言它真的丢了 Y，证明下面那几条用例测得到这个形状。
 */
private class RacePhone(val legacy: Boolean, snap: VSnapshot, var syncRev: Int, val cloud: CloudSim) {
    var snap: VSnapshot? = snap
    var gen = 0
    /** 本机缓存（encode 时写，pending）。 */
    var cache: VSnapshot? = null

    data class Pending(val cur: VSnapshot?, val base: Int, val gen: Int, val rev: Int, val blob: String)

    /** GET 发出：服务端当场答了（rev / blob 定格在这一刻），回包还在路上。 */
    fun beginPull() = Pending(snap, syncRev, gen, cloud.rev, cloud.blob)

    /** GET 回包落地。 */
    fun finishPull(p: Pending): VaultMergeResult = land(p.rev, p.blob, p.base, false, p.gen, p.cur)

    fun land(rev: Int, blob: String, base: Int, viaConflict: Boolean, g: Int, legacyCur: VSnapshot?): VaultMergeResult {
        val remote = VSnapshot.parse(blob)!!
        if (legacy) {
            val cur = legacyCur ?: return VaultMergeResult.Failed(VaultMergeFailure.Unreadable)
            if (rev == syncRev) return VaultMergeResult.Failed(VaultMergeFailure.NothingNewer)
            snap = VaultSyncRules.merge(cur, remote); syncRev = rev
            return VaultMergeResult.Merged
        }
        when (VaultSyncRules.mergeGate(rev, base, viaConflict, syncRev, g == gen)) {
            VaultSyncRules.MergeGate.Proceed -> Unit
            VaultSyncRules.MergeGate.NothingNewer -> return VaultMergeResult.Failed(VaultMergeFailure.NothingNewer)
            VaultSyncRules.MergeGate.Superseded -> return VaultMergeResult.Failed(VaultMergeFailure.Superseded)
        }
        val cur = snap ?: return VaultMergeResult.Failed(VaultMergeFailure.Superseded)
        snap = VaultSyncRules.merge(cur, remote)
        syncRev = rev
        return VaultMergeResult.Merged
    }

    fun save(it: VItem) { snap = snap?.appendItem("v", it) }
    fun lock() { snap = null; gen += 1 }

    /** push：`midFlight` 在**第一趟 PUT 已经发出、回包还没到**时调一次（GET 回包恰好在那时落地）。 */
    suspend fun push(midFlight: (() -> Unit)? = null): VaultPushOutcome {
        var sentBase = syncRev
        var first = true
        val g = gen
        return VaultSyncRules.run(
            sameUnlock = { legacy || gen == g },
            encode = encode@{
                val s = snap ?: return@encode null
                cache = s
                s.encode()
            },
            send = { blob, force ->
                sentBase = syncRev
                val r = cloud.put(blob, sentBase, force)
                if (first) { first = false; midFlight?.invoke() }
                if (r is VaultPutReply.Ok) syncRev = r.rev
                r
            },
            merge = { rev, blob ->
                val cur = snap
                land(rev, blob ?: cloud.blob, sentBase, true, gen, cur)
            },
        )
    }

    /** 用户点「重试」：syncNow = 拉合并 → 推。 */
    suspend fun syncNow(): VaultPushOutcome {
        finishPull(beginPull())
        return push()
    }
}

/**
 * 推送结局落地时改本机缓存的那几行，**照 VaultCore.push() 写**；`legacy` 照原来的写（复现用）。
 * `disk` 是本机缓存文件：(快照, pending)。
 */
private class CachePhone(val legacy: Boolean, var snap: VSnapshot, var syncRev: Int) {
    var seq = 0
    var disk: Pair<VSnapshot, Boolean>? = null
    var pendingPush = false

    data class Encoded(val blob: String, val seq: Int, val base: Int)

    /** encodeForPush：先落盘（pending），序号 +1。 */
    fun encode(): Encoded {
        disk = snap to true
        seq += 1
        return Encoded(snap.encode(), seq, syncRev)
    }

    fun landPushed(rev: Int, blob: String, mySeq: Int) {
        syncRev = rev
        if (legacy || VaultSyncRules.pushedIsLatest(mySeq, seq)) {
            disk = VSnapshot.parse(blob)!! to false
            pendingPush = false
        }
    }

    fun landOffline() { pendingPush = true }
    fun save(it: VItem) { snap = snap.appendItem("v", it) }
}

/** 保险箱同步判据的行为用例：冲突重试 / 孤儿附件 / 账号选取 / pullRecord / 竞态 / 缓存 / Secret Key，照 iOS 逐条。 */
class VaultSyncRulesTest {

    /** #17 冲突重试什么时候才许 force：合并不成一次 force 都不许发；合并都成才在最后一趟 force；每一趟重新编码。 */
    @Test fun forceCases() = runBlocking<Unit> {
        // ① #17 的原型：永远冲突 + 永远合并不成（断网 / 云端换过主密码）。原来的循环第三次无条件 force，把云端那份整份盖掉。
        for (why in listOf(VaultMergeFailure.Unreachable, VaultMergeFailure.KeyMismatch, VaultMergeFailure.Unreadable, VaultMergeFailure.NothingNewer)) {
            val srv = FakeServer()
            val out = VaultSyncRules.run(
                sameUnlock = { true },
                encode = { srv.encodes += 1; "blob${srv.encodes}" },
                send = { blob, force -> srv.sent += blob to force; VaultPutReply.Conflict(9, "remote") },
                merge = { _, _ -> VaultMergeResult.Failed(why) },
            )
            expect(srv.sent.none { it.second }, "合并不成（$why）时发出了 force:true —— 云端那份会被整份盖掉")
            expect(srv.sent.size == 1, "合并不成（$why）之后还在重试：发了 ${srv.sent.size} 次 PUT，应当停在第一次")
            expect(out == VaultPushOutcome.MergeFailed(why), "合并不成（$why）的结局应当是 mergeFailed，得到 $out")
        }

        // ② 合并**每次都成**、服务端仍连着回冲突：最后一次才 force，前两次不 force。
        run {
            val srv = FakeServer()
            var merges = 0
            VaultSyncRules.run(
                sameUnlock = { true },
                encode = { srv.encodes += 1; "blob${srv.encodes}" },
                send = { blob, force ->
                    srv.sent += blob to force
                    if (force) VaultPutReply.Ok(12) else VaultPutReply.Conflict(10 + srv.sent.size, "remote")
                },
                merge = { _, _ -> merges += 1; VaultMergeResult.Merged },
            )
            expect(srv.forces == listOf(false, false, true), "合并都成功时 force 的顺序应当是 [否, 否, 是]，得到 ${srv.forces}")
            // ③ **每一趟都重新编码**：原来 blob 在循环外只编一次，冲突合并之后推上去的还是合并前那份。
            expect(srv.encodes == 3, "三趟 PUT 只编了 ${srv.encodes} 次 blob —— 合并后推的会是合并前那份")
            expect(srv.sent.map { it.first }.toSet().size == 3, "三趟推的是同一份 blob：${srv.sent.map { it.first }}")
        }

        // ④ 第一次合并成、第二次合并不成：停在第二次，不 force。
        run {
            val srv = FakeServer()
            var merges = 0
            val out = VaultSyncRules.run(
                sameUnlock = { true },
                encode = { "b" },
                send = { blob, force -> srv.sent += blob to force; VaultPutReply.Conflict(5, null) },
                merge = { _, _ ->
                    merges += 1
                    if (merges == 1) VaultMergeResult.Merged else VaultMergeResult.Failed(VaultMergeFailure.Unreachable)
                },
            )
            expect(srv.sent.size == 2 && srv.sent.none { it.second }, "先成后不成：应当发 2 次、都不 force，得到 ${srv.forces}")
            expect(out == VaultPushOutcome.MergeFailed(VaultMergeFailure.Unreachable), "先成后不成的结局，得到 $out")
        }

        // ⑤ 冲突响应里带回来的那份要直接喂给 merge（省一次 GET、少一条会失败的路）。
        run {
            var got: Pair<Int, String?>? = null
            VaultSyncRules.run(
                sameUnlock = { true },
                encode = { "b" },
                send = { _, _ -> if (got == null) VaultPutReply.Conflict(42, "cloud-blob") else VaultPutReply.Ok(43) },
                merge = { rev, blob -> got = rev to blob; VaultMergeResult.Merged },
            )
            expect(got?.first == 42 && got?.second == "cloud-blob", "冲突带回的 rev / blob 没有原样交给 merge：$got")
        }

        // ⑥ 其余几条路：一次就过、断网、编不出来。
        run {
            val srv = FakeServer()
            val out = VaultSyncRules.run(
                sameUnlock = { true },
                encode = { "b" },
                send = { blob, force -> srv.sent += blob to force; VaultPutReply.Ok(7) },
                merge = { _, _ -> VaultMergeResult.Merged },
            )
            expect(out == VaultPushOutcome.Pushed(7, "b") && srv.sent.size == 1 && !srv.sent[0].second, "一次就过：$out")
        }
        run {
            val out = VaultSyncRules.run({ true }, { "b" }, { _, _ -> VaultPutReply.Unreachable }, { _, _ -> VaultMergeResult.Merged })
            expect(out == VaultPushOutcome.Offline, "断网应当是 offline，得到 $out")
        }
        run {
            val srv = FakeServer()
            val out = VaultSyncRules.run(
                sameUnlock = { true },
                encode = { null },
                send = { blob, force -> srv.sent += blob to force; VaultPutReply.Ok(1) },
                merge = { _, _ -> VaultMergeResult.Merged },
            )
            expect(out == VaultPushOutcome.EncodeFailed && srv.sent.isEmpty(), "编不出 blob 时不该发 PUT：$out")
        }

        // ⑦ 判据本身：没合并过就永远不 force，哪怕到了最后一次。
        expect(!VaultSyncRules.force(VaultSyncRules.MAX_ATTEMPTS - 1, false), "最后一次、一次都没合并成功时给了 force")
        expect(!VaultSyncRules.force(0, true), "第一次就给了 force")
    }

    /** 审查台账 p2：`GaveUp` 什么时候才走得到；推送结局之后提示条上那句原因怎么改。 */
    @Test fun problemCases() = runBlocking<Unit> {
        // ① 合并每次都成、服务端**按协议认 force**（force 时不判冲突）：第三趟带 force 必然写进去 —— 结局是 pushed，**不是** gaveUp。
        run {
            val cloud = CloudSim(1, VSnapshot(1, emptyList(), emptyMap()))
            val enc = VSnapshot(1, emptyList(), emptyMap()).encode()
            val out = VaultSyncRules.run(
                sameUnlock = { true },
                encode = { enc },
                send = { blob, force ->
                    // 每趟之前「另一台设备」都抢先写了一版：不 force 就一定冲突。
                    cloud.rev += 1
                    cloud.put(blob, 0, force)
                },
                merge = { _, _ -> VaultMergeResult.Merged },
            )
            expect(out is VaultPushOutcome.Pushed, "服务端认 force 时，合并都成功的三趟应当以 pushed 收尾，得到 $out")
        }
        // ② 只有服务端**不认 force**（第三趟带着 force 还回冲突）才落到 gaveUp。
        run {
            val srv = FakeServer()
            val out = VaultSyncRules.run(
                sameUnlock = { true },
                encode = { "b" },
                send = { blob, force -> srv.sent += blob to force; VaultPutReply.Conflict(3, null) },
                merge = { _, _ -> VaultMergeResult.Merged },
            )
            expect(out == VaultPushOutcome.GaveUp && srv.sent.lastOrNull()?.second == true,
                "gaveUp 应当只在「带了 force 仍冲突」时出现：$out，force 序列 ${srv.forces}")
        }

        // ③ 断网不许留着上一次的原因：提示条「syncProblem 优先」，旧原因会盖住「离线」那句。
        for (o in listOf(VaultPushOutcome.Offline, VaultPushOutcome.MergeFailed(VaultMergeFailure.Unreachable))) {
            expect(VaultSyncRules.problemAfter(o, false, null) == VaultSyncRules.ProblemUpdate.Clear,
                "$o 之后应当清掉上一次的原因，得到 ${VaultSyncRules.problemAfter(o, false, null)}")
            // 唯独「云端换过主密码」不清：断网证明不了钥匙对上了，清了按钮就变回「重试」（#28）。
            expect(VaultSyncRules.problemAfter(o, true, null) == VaultSyncRules.ProblemUpdate.Keep,
                "$o 之后把「重新解锁」那一档清掉了 —— 按钮会变回重试")
        }
        // ④ 其余各档：推上去了才清；编不出来不盖 encodeForPush 自己写的原因。
        expect(VaultSyncRules.problemAfter(VaultPushOutcome.Pushed(1, "b"), true, null) == VaultSyncRules.ProblemUpdate.Clear, "推上去了应当清")
        expect(VaultSyncRules.problemAfter(VaultPushOutcome.EncodeFailed, false, null) == VaultSyncRules.ProblemUpdate.Keep, "编不出来时盖掉了已有原因")
        expect(VaultSyncRules.problemAfter(VaultPushOutcome.MergeFailed(VaultMergeFailure.KeyMismatch), false, null) == VaultSyncRules.ProblemUpdate.Reunlock, "keyMismatch")
        expect(VaultSyncRules.problemAfter(VaultPushOutcome.MergeFailed(VaultMergeFailure.Unreadable), false, null) == VaultSyncRules.ProblemUpdate.CannotMerge, "unreadable")
        expect(VaultSyncRules.problemAfter(VaultPushOutcome.MergeFailed(VaultMergeFailure.NothingNewer), false, null) == VaultSyncRules.ProblemUpdate.CannotMerge, "nothingNewer")
        expect(VaultSyncRules.problemAfter(VaultPushOutcome.Rejected, false, null) == VaultSyncRules.ProblemUpdate.Rejected, "rejected")
        expect(VaultSyncRules.problemAfter(VaultPushOutcome.GaveUp, false, null) == VaultSyncRules.ProblemUpdate.GaveUp, "gaveUp")
    }

    /** #18 推之前剔掉孤儿附件；画布版推之前内联字节一律清空、清单不动。 */
    @Test fun pruneCases() {
        // #18 的原型：电脑端彻底删除了 X（墓碑行、清单清空），而手机这份里 A 的字节还在。
        val tomb = item("X", emptyList())
        val keep = item("Y", listOf("B"))
        val trashed = item("Z", listOf("C")) // 在回收站里：内容（含附件清单）原封不动，字节必须留着
        val snap = VSnapshot(1, emptyList(), mapOf(
            "v1" to VData(emptyList(), listOf(tomb, keep, trashed), mapOf("A" to "AAAA", "B" to "BBBB", "C" to "CCCC")),
            "v2" to VData(emptyList(), listOf(item("W", listOf("D"))), mapOf("D" to "DDDD")),
        ))
        val (pruned, n) = VaultSyncRules.pruneOrphanAttachments(snap)
        val v1 = pruned.data["v1"]?.attachments ?: emptyMap()
        expect(v1["A"] == null, "没人引用的附件 A 推之前还在快照里 —— 彻底删除的字节会被推回云端")
        expect(v1["B"] == "BBBB" && v1["C"] == "CCCC", "被引用的附件被剔掉了：${v1.keys.sorted()}")
        expect(pruned.data["v2"]?.attachments == mapOf("D" to "DDDD"), "没有孤儿的库被动了")
        expect(n == 1, "应当剔掉 1 份字节，得到 $n")

        // 画布版：推之前内联字节一律去掉（字节走 files kind=vault，PC 本机留原件）。
        val snap2 = VSnapshot(2, emptyList(), mapOf(
            "v1" to VData(emptyList(), listOf(keep), mapOf("B" to "BBBB")),
            "v2" to VData(emptyList(), emptyList(), emptyMap()),
        ))
        val (s2, stripped) = VaultSyncRules.stripInlineAttachments(snap2)
        expect(stripped == 1 && s2.data["v1"]?.attachments?.isEmpty() == true, "推之前内联附件字节应当清空，去掉 $stripped 份")
        expect(s2.data["v1"]?.items?.firstOrNull()?.attachments?.map { it.id } == listOf("B"), "清的是字节，附件元数据（清单）必须原封不动")
        expect(VaultSyncRules.stripInlineAttachments(s2).second == 0, "已经空的再清一次应当是 0")

        // 没有孤儿时返回 null（调用方据此不去改 snapshot）。
        expect(VaultSyncRules.prunedAttachments(mapOf("B" to "x"), listOf(listOf("B"))) == null, "没有孤儿时不该返回新字典")
        expect(VaultSyncRules.prunedAttachments(mapOf("B" to "x", "Q" to "y"), listOf(listOf("B"), emptyList())) == mapOf("B" to "x"),
            "一个孤儿 + 一个被引用")
        // 引用了、但字节还没到（那台还没拿到）：不凭空造一个 key。
        expect(VaultSyncRules.prunedAttachments(mapOf("Q" to "y"), listOf(listOf("B"))) == emptyMap<String, String>(), "只有孤儿时应当剔空")
    }

    private fun acc(id: String, user: String) =
        VBlock(id, "account", null, mapOf("username" to js(user), "password" to js("pw-$user")))

    /** #14 QuickType 点回来该填哪一个账号：对得上才填，对不上不退回第一块；画布版取 acct 节点。 */
    @Test fun accountCases() {
        val note = VBlock("n", "note", null, emptyMap())
        val it = item("R", emptyList()).copy(blocks = listOf(note, acc("a", "alice@x.com"), acc("b", "bob@x.com")))

        expect(it.accountBlocks.map { b -> b.id } == listOf("a", "b"), "accountBlocks 应当按顺序只取账号块")
        // #14 的原型：点 bob@，填进去的必须是 bob@ 那一块。
        expect(it.account("bob@x.com")?.id == "b", "点了 bob@，取到的是 ${it.account("bob@x.com")?.id ?: "nil"}")
        expect(it.account("alice@x.com")?.id == "a", "点了 alice@")
        // 身份库过期（账号改了名）：**不许**退回第一块替他填。
        expect(it.account("carol@x.com") == null, "对不上的用户名退回了第一块 —— 这正是 #14 那种填错")
        // 系统没给用户名时才退回第一块。
        expect(it.account("")?.id == "a", "空用户名应当退回第一块")
        val none = item("N", emptyList()).copy(blocks = listOf(note))
        expect(none.account("") == null, "没有账号块时应当是 nil")
        // 画布版（2026-10-12，方案 §八）：AutoFill 取的是 acct 节点，blocks 里什么都没有。
        var canvas = item("C", emptyList())
        canvas = canvas.copy(extra = canvas.extra + ("fmt" to jn(2.0)))
        val n1 = VNode.defaultAcct("n1").let { n -> n.copy(data = n.data + ("user" to js("alice@x.com")) + ("pwd" to js("pw-a"))) }
        val n2 = VNode.defaultAcct("n2").let { n -> n.copy(y = 300.0, data = n.data + ("user" to js("bob@x.com")) + ("pwd" to js("pw-b"))) }
        canvas = canvas.withNodes(listOf(n2, n1))
        expect(canvas.accounts.map { a -> a.id } == listOf("n1", "n2"), "画布版按画布位置取 acct 节点：${canvas.accounts.map { a -> a.id }}")
        expect(canvas.account("bob@x.com")?.pwd == "pw-b", "画布版点 bob@ 填 bob@ 的密码")
        expect(canvas.account("nobody") == null && canvas.accountBlocks.isEmpty(), "画布版对不上不填；blocks 为空")
    }

    /** #28 解锁态 pullRecord 不许跳过合并直接顶 rev；冷启动不许覆盖待推缓存；钥匙与 record 不是一对不许推；merge 本身的胜负规则。 */
    @Test fun pullCases() = runBlocking<Unit> {
        // ── 判据本身 ──
        val rec = { v: String -> "{\"v\":1,\"salt\":\"s\",\"verifier\":\"$v\",\"enc\":\"e\"}" }
        val pendingK1 = VCache(rec("K1"), 10, null, true)
        val cleanK1 = VCache(rec("K1"), 10, null, false)
        val legacyK1 = VCache(rec("K1"), 10, null, null)
        for (c in listOf(VCacheRead.Missing, VCacheRead.Unreadable, VCacheRead.Ok(pendingK1), VCacheRead.Ok(cleanK1))) {
            for (prefer in listOf(false, true)) {
                expect(VaultSyncRules.pullAdopt(true, c, "K2", prefer) == VaultSyncRules.PullAdopt.MergeInstead,
                    "解锁态 pullRecord 必须转去合并（cache=$c, prefer=$prefer）—— 这正是 #28")
            }
        }
        expect(VaultSyncRules.pullAdopt(false, VCacheRead.Missing, "K1", false) == VaultSyncRules.PullAdopt.AdoptCloud,
            "锁着、没有缓存：应当采用云端那份")
        expect(VaultSyncRules.pullAdopt(false, VCacheRead.Ok(cleanK1), "K1", false) == VaultSyncRules.PullAdopt.AdoptCloud,
            "锁着、缓存没有待推：应当采用云端那份")
        expect(VaultSyncRules.pullAdopt(false, VCacheRead.Ok(legacyK1), "K1", false) == VaultSyncRules.PullAdopt.AdoptCloud,
            "老缓存文件（没有 pending 那一格）应当按「没有待推」处理")
        expect(VaultSyncRules.pullAdopt(false, VCacheRead.Ok(pendingK1), "K1", false) == VaultSyncRules.PullAdopt.KeepLocal,
            "#28 场景 C：缓存里有待推的离线改动，冷启动 pullRecord 不许拿云端那份覆盖它")
        expect(VaultSyncRules.pullAdopt(false, VCacheRead.Ok(pendingK1), "K1", true) == VaultSyncRules.PullAdopt.KeepLocal,
            "同一把钥匙时 preferCloudKey 不该让待推改动被丢掉（两份合得到一起）")
        expect(VaultSyncRules.pullAdopt(false, VCacheRead.Ok(pendingK1), "K2", false) == VaultSyncRules.PullAdopt.KeepLocal,
            "云端换过主密码、本机有待推：用户没点头之前留本机那份")
        expect(VaultSyncRules.pullAdopt(false, VCacheRead.Ok(pendingK1), "K2", true) == VaultSyncRules.PullAdopt.AdoptCloud,
            "云端换过主密码、用户已选「以云端为准」：应当采用云端那份")

        // ── 缓存文件向后兼容 ──
        run {
            val old = VCache.parse("""{"blob":"b","rev":3}""")
            expect(old?.rev == 3 && old?.pending == null && old?.trashCount == null, "最老的缓存文件（只有 blob / rev）解不开了：$old")
            val mid = VCache.parse("""{"blob":"b","rev":3,"trashCount":2}""")
            expect(mid?.trashCount == 2 && mid?.pending == null, "带 trashCount 的缓存文件解不开了：$mid")
            val round = VCache.parse(pendingK1.encode())
            expect(round == pendingK1, "pending 写进去再读出来对不上：$round")
            expect(pendingK1.record?.verifier == "K1", "缓存里那份 record 读不出 verifier")
        }

        // ── 推送前钥匙与 record 是一对 ──
        expect(VaultSyncRules.mayEncode("K1", "K1"), "同一把钥匙应当许推")
        expect(!VaultSyncRules.mayEncode("K2", "K1"), "#28 场景 B：record 换成了新 verifier、AUK 还是旧的 —— 不许编出一份谁都解不开的密文")
        expect(!VaultSyncRules.mayEncode("K1", null), "没解锁过不许推")
        expect(!VaultSyncRules.mayEncode(null, "K1"), "没有 record 不许推")

        // ── 提示条那颗钮 ──
        expect(VaultSyncRules.retryAction(false) == VaultSyncRules.RetryAction.SyncNow, "平常那一档应当只 syncNow")
        expect(VaultSyncRules.retryAction(true) == VaultSyncRules.RetryAction.Relock, "云端换过主密码那一档不许给「重试」")

        // ── 场景 A：解锁 → PC 推 rev+1 → 手机回首页（pullRecord）→ 手机保存 ──
        run {
            val base = VSnapshot(1, emptyList(), mapOf("v" to VData(emptyList(), listOf(item("old", emptyList())), emptyMap())))
            val cloud = CloudSim(10, base)
            val phone = PhoneSim(base, 10, cloud)
            // PC 新增了一条，推到 rev 11。
            val pcSnap = base.appendItem("v", item("pc-new", emptyList()))
            cloud.snap = pcSnap; cloud.rev = 11
            phone.pullRecord()
            phone.save(item("phone-new", emptyList()))
            expect(vIds(cloud.snap) == listOf("old", "pc-new", "phone-new"),
                "#28 场景 A：手机保存之后云端应当同时有 PC 那条和手机那条，得到 ${vIds(cloud.snap)}")
        }

        // ── 场景 B：云端换了主密码 → 点钮：一次 PUT 都不许发 ──
        run {
            val cloud = CloudSim(11, VSnapshot(1, emptyList(), emptyMap()))
            if (VaultSyncRules.retryAction(true) == VaultSyncRules.RetryAction.SyncNow) {
                // 走到这里说明钮还是「重试」：照原来那样拿新 record + 旧钥匙去推。
                VaultSyncRules.run(
                    sameUnlock = { true },
                    encode = { if (VaultSyncRules.mayEncode("K2", "K1")) "x" else null },
                    send = { _, force -> cloud.put("{\"v\":1,\"vaults\":[],\"data\":{}}", 11, force) },
                    merge = { _, _ -> VaultMergeResult.Merged },
                )
            }
            // 第二道独立的闸：就算有一条路走到了推送，钥匙对不上也不许发。
            val out = VaultSyncRules.run(
                sameUnlock = { true },
                encode = { if (VaultSyncRules.mayEncode("K2", "K1")) "x" else null },
                send = { _, force -> cloud.put("{\"v\":1,\"vaults\":[],\"data\":{}}", 11, force) },
                merge = { _, _ -> VaultMergeResult.Merged },
            )
            expect(cloud.puts == 0, "#28 场景 B：verifier 换过之后还发了 ${cloud.puts} 次 PUT —— 云端会变成谁都解不开的一份")
            expect(out == VaultPushOutcome.EncodeFailed, "钥匙对不上时应当停在编码这一步，得到 $out")
        }

        // ── 场景 C：离线改动落盘 → 杀 App → 联网冷启动 ──
        run {
            val base = VSnapshot(1, emptyList(), mapOf("v" to VData(emptyList(), listOf(item("old", emptyList())), emptyMap())))
            val offline = base.appendItem("v", item("offline-new", emptyList()))
            // 推之前落的那份：pending、基线 rev 10。
            val onDisk = VCache(rec("K1"), 10, null, true)
            val diskBytes = onDisk.encode()
            // 冷启动：云端已经被 PC 推到 11。
            val pcSnap = base.appendItem("v", item("pc-new", emptyList()))
            val cloud = CloudSim(11, pcSnap)
            var cache = VCache.parse(diskBytes)!!
            var syncRev = 0
            when (VaultSyncRules.pullAdopt(false, VCacheRead.Ok(cache), "K1", false)) {
                VaultSyncRules.PullAdopt.AdoptCloud -> {
                    cache = VCache(rec("K1") + "cloud", cloud.rev, null, false)
                    syncRev = cloud.rev
                }
                VaultSyncRules.PullAdopt.KeepLocal -> syncRev = cache.rev
                VaultSyncRules.PullAdopt.AdoptCloudKeepFile -> syncRev = cloud.rev // 不写盘：cache 原样
                VaultSyncRules.PullAdopt.MergeInstead -> Unit
            }
            expect(cache == onDisk, "#28 场景 C：冷启动的 pullRecord 覆盖了带待推标记的缓存")
            // 解锁后经 pullMerge → push：两边的改动都要在云端。
            val phone = PhoneSim(offline, syncRev, cloud)
            VaultSyncRules.run(
                sameUnlock = { true },
                encode = { phone.snap.encode() },
                send = { blob, force ->
                    val r = cloud.put(blob, phone.syncRev, force)
                    if (r is VaultPutReply.Ok) phone.syncRev = r.rev
                    r
                },
                merge = { rev, blob -> phone.pullMerge(rev, blob ?: cloud.blob) },
            )
            expect(vIds(cloud.snap) == listOf("offline-new", "old", "pc-new"),
                "#28 场景 C：补推之后云端应当同时有离线那条和 PC 那条，得到 ${vIds(cloud.snap)}")
        }

        // ── merge 本身：revision 高者胜、相等时 updatedAt 晚者胜、远端独有的库与附件并进来 ──
        run {
            val a = item("x", emptyList()).copy(revision = 2.0, title = "local")
            val b = item("x", emptyList()).copy(revision = 3.0, title = "remote")
            val c = item("y", emptyList()).copy(revision = 1.0, updatedAt = 5.0, title = "local")
            val d = item("y", emptyList()).copy(revision = 1.0, updatedAt = 4.0, title = "remote")
            val local = VSnapshot(1, emptyList(), mapOf("v" to VData(emptyList(), listOf(a, c), mapOf("A" to "1"))))
            val remote = VSnapshot(1, listOf(VVaultInfo("v2", "n", "o", "i", 0.0, "k")),
                mapOf("v" to VData(emptyList(), listOf(b, d), mapOf("A" to "2", "B" to "3"))))
            val m = VaultSyncRules.merge(local, remote)
            val byId = (m.data["v"]?.items ?: emptyList()).associate { it.id to it.title }
            expect(byId["x"] == "remote", "revision 高的应当胜出")
            expect(byId["y"] == "local", "revision 相等时 updatedAt 晚的应当胜出")
            expect(m.data["v"]?.attachments == mapOf("A" to "1", "B" to "3"), "附件字节应当取并集、本机已有的不被覆盖")
            expect(m.vaults.map { it.id } == listOf("v2"), "远端独有的库应当并进来")
        }
    }

    /** 审查台账 2026-10-05（第二轮）：pullMerge 在路上时本机变了 —— GET 在飞期间保存的不许丢、晚到回包不许顶回去、上锁后不许并回明文。 */
    @Test fun raceCases() = runBlocking<Unit> {
        val base = VSnapshot(1, emptyList(), mapOf("v" to VData(emptyList(), listOf(item("old", emptyList())), emptyMap())))
        val pcSnap = base.appendItem("v", item("pc", emptyList()))

        // ── ① 台账原型：GET 在飞（云端已被 PC 推到 11）→ 本机保存 Y → PUT 发出 → GET 落地 → PUT 回冲突 → 重试 ──
        for (legacy in listOf(true, false)) {
            val cloud = CloudSim(11, pcSnap)
            val phone = RacePhone(legacy, base, 10, cloud)
            val get = phone.beginPull()
            phone.save(item("Y", emptyList()))
            val first = phone.push(midFlight = { phone.finishPull(get) })
            var final = first
            if (first !is VaultPushOutcome.Pushed) final = phone.syncNow()
            val yInCloud = vIds(cloud.snap).contains("Y")
            val yInCache = vIds(phone.cache).contains("Y")
            if (legacy) {
                // 复现：原来的写法确实把 Y 丢了 —— 这条红了说明这组用例测不到那个形状了。
                expect(first == VaultPushOutcome.MergeFailed(VaultMergeFailure.NothingNewer) && !yInCloud && !yInCache,
                    "复现失败：原写法应当先报 nothingNewer、重试后云端与缓存都没有 Y，得到 $first / 云端 ${vIds(cloud.snap)} / 缓存 ${vIds(phone.cache)}")
            } else {
                expect(first == VaultPushOutcome.Pushed(12, first.blobOrEmpty),
                    "vault-pullmerge-stale-snapshot：冲突应当按那次 PUT 的 baseRev 判、合并后推上去，得到 $first")
                expect(vIds(cloud.snap) == listOf("Y", "old", "pc") && vIds(phone.snap) == listOf("Y", "old", "pc"),
                    "vault-pullmerge-stale-snapshot：GET 在飞期间保存的 Y 丢了 —— 云端 ${vIds(cloud.snap)}，内存 ${vIds(phone.snap)}（结局 $final）")
            }
        }

        // ── ② GET 回包晚于那次保存推成功才落地：不许把内存顶回旧快照、不许把 syncRev 顶回旧值 ──
        run {
            val cloud = CloudSim(10, base)
            val phone = RacePhone(false, base, 10, cloud)
            val get = phone.beginPull()             // 服务端答的是 rev 10（没有新的）……
            cloud.snap = pcSnap; cloud.rev = 11     // ……之后 PC 推了一版
            phone.save(item("Y", emptyList()))
            val out = phone.push()                  // 冲突 → 合并 PC → 推成 12
            val late = phone.finishPull(get)        // 这时那份 rev 10 的回包才到
            expect(out == VaultPushOutcome.Pushed(12, out.blobOrEmpty), "② 保存应当推成功，得到 $out")
            expect(late == VaultMergeResult.Failed(VaultMergeFailure.Superseded) && phone.syncRev == 12 && vIds(phone.snap) == listOf("Y", "old", "pc"),
                "② 晚到的 GET 回包把本机顶回去了：$late，syncRev ${phone.syncRev}，内存 ${vIds(phone.snap)}")
        }

        // ── ③ vault-late-merge-after-lock：GET 在飞时上锁，回包不许把明文填回内存 ──
        run {
            val cloud = CloudSim(11, pcSnap)
            val phone = RacePhone(false, base, 10, cloud)
            val get = phone.beginPull()
            phone.lock()
            val r = phone.finishPull(get)
            expect(r == VaultMergeResult.Failed(VaultMergeFailure.Superseded) && phone.snap == null,
                "③ 上锁之后回包又把明文并回了内存：$r，内存 ${vIds(phone.snap)}")
            // 锁上又解开（代次又变了，snap 又有了）：上一轮的回包照样作废。
            val phone2 = RacePhone(false, base, 10, cloud)
            val get2 = phone2.beginPull()
            phone2.lock(); phone2.snap = base; phone2.gen += 1
            expect(phone2.finishPull(get2) == VaultMergeResult.Failed(VaultMergeFailure.Superseded), "③ 锁上又解开之后，上一轮的回包被并进来了")
        }

        // ── ④ 判据本身 ──
        expect(VaultSyncRules.mergeGate(11, 10, true, 11, true) == VaultSyncRules.MergeGate.Proceed,
            "冲突回包要和那次 PUT 的 baseRev 比，不是和此刻的 syncRev 比")
        expect(VaultSyncRules.mergeGate(10, 10, true, 10, true) == VaultSyncRules.MergeGate.NothingNewer,
            "冲突却带回同一版：自相矛盾，应当 nothingNewer")
        expect(VaultSyncRules.mergeGate(11, 10, false, 10, true) == VaultSyncRules.MergeGate.Proceed,
            "平常的 GET：有新的应当并")
        expect(VaultSyncRules.mergeGate(10, 10, false, 10, true) == VaultSyncRules.MergeGate.NothingNewer,
            "平常的 GET：没有新的")
        expect(VaultSyncRules.mergeGate(13, 10, false, 12, true) == VaultSyncRules.MergeGate.Superseded,
            "GET 发出后 syncRev 被推走了：回包作废")
        expect(VaultSyncRules.mergeGate(11, 10, true, 10, false) == VaultSyncRules.MergeGate.Superseded,
            "代次变了（上过锁）：冲突回包也作废")
        expect(VaultSyncRules.problemAfter(VaultPushOutcome.MergeFailed(VaultMergeFailure.Superseded), false, null) == VaultSyncRules.ProblemUpdate.Keep,
            "回包作废不是错误，不该改提示条")
    }

    /** vault-old-password-rejected-after-cloud-prompt：提示「两份只能留一份」之后照提示输旧密码要能解开本机那份；派生钥匙是惰性的。 */
    @Test fun unlockPickCases() {
        // 云端换过主密码（新 = N），本机有旧密钥（旧 = O）加密的待推改动。
        fun pick(pw: String, prefer: Boolean, hasCloud: Boolean = true): VaultSyncRules.UnlockPick =
            VaultSyncRules.unlockPick(prefer, { pw == "O" }, if (hasCloud) ({ pw == "N" }) else null)
        // 台账原型：先输新密码 → 提示「两份只能留一份，输旧密码可以先看」→ 照提示输旧密码。
        expect(pick("N", false) == VaultSyncRules.UnlockPick.PromptCloud, "第一次输新密码应当提示两份只能留一份")
        expect(pick("O", true) == VaultSyncRules.UnlockPick.Local,
            "提示之后照提示输正确的旧密码，应当解开本机那份 —— 原来第一次必报「不正确」")
        expect(pick("N", true) == VaultSyncRules.UnlockPick.Cloud, "提示之后再输一次新密码，应当以云端为准")
        expect(pick("x", true) == VaultSyncRules.UnlockPick.Wrong && pick("x", false) == VaultSyncRules.UnlockPick.Wrong, "两份都对不上才是不正确")
        expect(pick("O", false) == VaultSyncRules.UnlockPick.Local, "没提示过时输旧密码照常解本机那份")
        expect(pick("O", true, hasCloud = false) == VaultSyncRules.UnlockPick.Local, "没有待选的云端那份时只看本机")
        expect(pick("N", false, hasCloud = false) == VaultSyncRules.UnlockPick.Wrong, "没有云端那份时新密码就是不对")
        // 惰性：PBKDF2 一次将近一秒，本机那份就对得上时不许再算云端那份。
        var cloudTried = 0
        VaultSyncRules.unlockPick(false, { true }, { cloudTried += 1; true })
        expect(cloudTried == 0, "本机那份已经对上了还去派生云端那把钥匙（多等一秒）")
        var localTried = 0
        VaultSyncRules.unlockPick(true, { localTried += 1; true }, { true })
        expect(localTried == 0, "以云端为准且云端对上了，还去派生本机那把钥匙")
    }

    /** vault-vjson-int-trap：装不进整型的整数值按 Double 写（Swift 的样子），不崩；VNum 向零取整、越界回 null。 */
    @Test fun numberCases() {
        // 原来 `String(Int(x))`：6.2e19 是整数值、装不进 Int → Fatal error，渲染即闪退。
        expect(jn(6.2e19).vString == "6.2e+19", "装不进 Int 的整数值应当按 Double 写出来，得到 ${jn(6.2e19).vString}")
        expect(jn(-1e300).vString == "-1e+300", "极小的负数：${jn(-1e300).vString}")
        expect(jn(42.0).vString == "42" && jn(-7.0).vString == "-7", "装得进的整数照旧不带小数点")
        expect(jn(1.5).vString == "1.5", "小数照旧")
        expect(jn(9007199254740992.0).vString == "9007199254740992", "2^53 装得进 Int，应当是整数写法")
        // 从真的 JSON 解出来（电脑端 Block.data 存得进任意数字）。
        val blk = runCatching {
            VBlock.from(VaultJson.parseToJsonElement("""{"id":"b","type":"note","data":{"n":62000000000000000000,"m":1e308}}"""))
        }.getOrNull()
        expect(blk?.data?.get("n").vString == "6.2e+19" && blk?.data?.get("m").vString == "1e+308",
            "从 JSON 解出的超大数：${blk?.data?.get("n").vString} / ${blk?.data?.get("m").vString}")
        expect(VNum.int(Double.POSITIVE_INFINITY) == null && VNum.int(Double.NEGATIVE_INFINITY) == null && VNum.int(Double.NaN) == null
            && VNum.long(Double.POSITIVE_INFINITY) == null && VNum.long(Double.NaN) == null, "无穷 / NaN 应当是 nil")
        // iOS 的 Int 是 64 位：对应 Kotlin 的 VNum.long；VNum.int（32 位）同样得是 null。
        expect(VNum.int(9.3e18) == null && VNum.int(-9.3e18) == null && VNum.long(9.3e18) == null && VNum.long(-9.3e18) == null, "越界应当是 nil")
        expect(VNum.int(3.9) == 3 && VNum.int(-3.9) == -3 && VNum.long(3.9) == 3L && VNum.long(-3.9) == -3L, "向零取整")
    }

    /** vault-cache-read-failure-adopts-cloud：缓存读不出来 / record 解不开时不许走 AdoptCloud 把它盖掉。 */
    @Test fun cacheReadCases() {
        val rec = { v: String -> "{\"v\":1,\"salt\":\"s\",\"verifier\":\"$v\",\"enc\":\"e\"}" }
        expect(VaultSyncRules.pullAdopt(false, VCacheRead.Unreadable, "K1", false) == VaultSyncRules.PullAdopt.AdoptCloudKeepFile,
            "缓存文件读不出来：不许走 adoptCloud 把它盖掉（里面可能有待推的改动）")
        expect(VaultSyncRules.pullAdopt(false, VCacheRead.Unreadable, "K1", true) == VaultSyncRules.PullAdopt.AdoptCloudKeepFile,
            "读不出来时 preferCloudKey 也不许盖（不知道里面有什么）")
        val garbled = VCache("not-a-record", 3, null, true)
        expect(VaultSyncRules.pullAdopt(false, VCacheRead.Ok(garbled), "K1", false) == VaultSyncRules.PullAdopt.AdoptCloudKeepFile,
            "缓存里的 record 解不开：同样不许盖")
        expect(VaultSyncRules.pullAdopt(false, VCacheRead.Missing, "K1", false) == VaultSyncRules.PullAdopt.AdoptCloud,
            "真的没有缓存：照旧采用云端那份并落盘")
        val ok = VCache(rec("K1"), 3, null, false)
        expect(VCacheRead.Ok(ok).cacheOrNull == ok && VCacheRead.Unreadable.cacheOrNull == null && VCacheRead.Missing.cacheOrNull == null,
            "VCacheRead.cache 取值不对")
    }

    /** 审查台账 2026-10-06：推送这一侧的竞态（push 在路上时本机变了）—— 换钥匙后不许混推、作废回包不认、并发推送不冲掉待推、坏缓存先备份。 */
    @Test fun pushRaceCases() = runBlocking<Unit> {
        val base = VSnapshot(1, emptyList(), mapOf("v" to VData(emptyList(), listOf(item("old", emptyList())), emptyMap())))

        // ── ① vault-push-stale-key-after-relock ──
        // 第一趟 PUT 在路上时：用户「锁定并重新解锁」，以云端为准用新主密码（钥匙 K2、verifier V2）解开。
        // 冲突回来，pullMerge 是在**新一轮**里跑的 —— 它合法地成了。原来第二趟 encode 用的是 push 开头取的 K1：外壳 V2、里面 K1 —— 谁都解不开。
        for (legacy in listOf(true, false)) {
            var key = "K1"; var recordVerifier = "V1"; var unlockedVerifier = "V1"; var gen = 0
            val pushKey = key // 原来：push 开头 `guard let a = auk`
            val g = gen
            val sent = mutableListOf<String>()
            val out = VaultSyncRules.run(
                sameUnlock = { legacy || gen == g },
                encode = encode@{
                    if (!VaultSyncRules.mayEncode(recordVerifier, unlockedVerifier)) return@encode null
                    // 现在的 encodeForPush 当场取 auk；原来用 push 开头那把。
                    "$recordVerifier|${if (legacy) pushKey else key}"
                },
                send = { blob, _ ->
                    sent += blob
                    if (sent.size == 1) {
                        gen += 1                                                                 // lock()
                        key = "K2"; recordVerifier = "V2"; unlockedVerifier = "V2"; gen += 1    // unlock(.cloud)
                        VaultPutReply.Conflict(11, "cloud")
                    } else VaultPutReply.Ok(12)
                },
                merge = { _, _ -> VaultMergeResult.Merged },
            )
            val mixed = sent.contains("V2|K1")
            if (legacy) {
                expect(mixed, "复现失败：原写法应当推出一份「外壳 V2、里面 K1」的密文，实际发了 $sent")
            } else {
                expect(!mixed && sent == listOf("V1|K1") && out == VaultPushOutcome.Superseded,
                    "vault-push-stale-key-after-relock：换钥匙重新解开之后，上一轮的 push 还在编 / 推：发了 $sent，结局 $out")
            }
        }

        // ── ② PUT 在路上时上锁，回包是 ok：不许拿它改新一轮的 syncRev / 缓存 ──
        run {
            var gen = 0
            val g = gen
            var landed = false
            val out = VaultSyncRules.run(
                sameUnlock = { gen == g },
                encode = { "b" },
                send = { _, _ -> gen += 1; landed = true; VaultPutReply.Ok(9) },
                merge = { _, _ -> VaultMergeResult.Merged },
            )
            expect(landed && out == VaultPushOutcome.Superseded, "② 上锁之后落地的 ok 应当作废，得到 $out")
            expect(VaultSyncRules.problemAfter(VaultPushOutcome.Superseded, false, null) == VaultSyncRules.ProblemUpdate.Keep,
                "② 作废不是错误，不该改提示条")
        }
        // 没变过代次时照常推。
        run {
            val out = VaultSyncRules.run({ true }, { "b" }, { _, _ -> VaultPutReply.Ok(3) }, { _, _ -> VaultMergeResult.Merged })
            expect(out == VaultPushOutcome.Pushed(3, "b"), "代次没变时照常推：$out")
        }

        // ── ③ vault-encodefailed-keeps-stale-problem：编不出来要换掉提示条上的旧原因 ──
        expect(VaultSyncRules.problemAfter(VaultPushOutcome.EncodeFailed, false, VaultEncodeFailure.CannotEncode) == VaultSyncRules.ProblemUpdate.CannotEncode,
            "③ 序列化 / 加密失败时提示条原样留着上一次的原因（#42 同类）")
        expect(VaultSyncRules.problemAfter(VaultPushOutcome.EncodeFailed, true, VaultEncodeFailure.CannotEncode) == VaultSyncRules.ProblemUpdate.CannotEncode,
            "③ 加密失败时还挂着「重新解锁」—— 钥匙对得上才走到加密这一步")
        expect(VaultSyncRules.problemAfter(VaultPushOutcome.EncodeFailed, false, VaultEncodeFailure.KeyMismatch) == VaultSyncRules.ProblemUpdate.Reunlock,
            "③ 钥匙对不上应当给「锁定并重新解锁」")
        expect(VaultSyncRules.problemAfter(VaultPushOutcome.EncodeFailed, false, null) == VaultSyncRules.ProblemUpdate.Keep,
            "③ 没记原因（锁着时）不该写一句回提示条")

        // ── ④ vault-concurrent-push-clears-pending-flag ──
        // A 落盘 S1 → PUT_A（服务端收下）→ 存 Y → B 落盘 S2（含 Y）→ PUT_B 断网 → A 的 ok 落地。两种落地顺序都要对。
        for (legacy in listOf(true, false)) {
            for (aFirst in listOf(true, false)) {
                val cloud = CloudSim(10, base)
                val phone = CachePhone(legacy, base, 10)
                phone.save(item("X", emptyList()))
                val a = phone.encode()
                val ra = cloud.put(a.blob, a.base, false)
                phone.save(item("Y", emptyList()))
                phone.encode() // B：PUT 发不出去
                if (ra !is VaultPutReply.Ok) { expect(false, "④ 前提：A 应当推成功"); continue }
                val revA = ra.rev
                if (aFirst) { phone.landPushed(revA, a.blob, a.seq); phone.landOffline() }
                else { phone.landOffline(); phone.landPushed(revA, a.blob, a.seq) }
                // 杀掉 App：只剩盘上那份和云端那份。
                val yOnDisk = vIds(phone.disk?.first).contains("Y")
                val pendingOnDisk = phone.disk?.second == true
                if (legacy) {
                    expect(!yOnDisk && !pendingOnDisk && !vIds(cloud.snap).contains("Y"),
                        "复现失败：原写法应当把缓存写成不含 Y、pending=false（顺序 aFirst=$aFirst），得到 ${vIds(phone.disk?.first)} / $pendingOnDisk")
                } else {
                    expect(yOnDisk && pendingOnDisk && phone.pendingPush && phone.syncRev == revA,
                        "vault-concurrent-push-clears-pending-flag（aFirst=$aFirst）：A 的 ok 把还没推上去的 Y 从缓存里冲掉了 / 待推标记被清：盘上 ${vIds(phone.disk?.first)}，pending $pendingOnDisk，内存 pendingPush ${phone.pendingPush}，syncRev ${phone.syncRev}")
                }
            }
        }
        // 只有一趟时照常写干净。
        run {
            val phone = CachePhone(false, base, 10)
            phone.save(item("X", emptyList()))
            val a = phone.encode()
            phone.landPushed(11, a.blob, a.seq)
            expect(phone.disk?.second == false && !phone.pendingPush && vIds(phone.disk?.first) == listOf("X", "old"),
                "④ 只有一趟推送时，推成功应当把缓存写干净")
        }
        expect(VaultSyncRules.pushedIsLatest(3, 3) && !VaultSyncRules.pushedIsLatest(2, 3), "pushedIsLatest 判错")
        // 一次都没落过盘（-1）不算最新。
        expect(!VaultSyncRules.pushedIsLatest(-1, 0), "没落过盘的序号被当成最新")

        // ── ⑤ vault-unreadable-cache-overwritten-on-next-save ──
        val good = VCache("{\"v\":1,\"salt\":\"s\",\"verifier\":\"K\",\"enc\":\"e\"}", 3, null, true)
        val garbled = VCache("not-a-record", 3, null, true)
        expect(VaultSyncRules.mustBackUpBeforeOverwrite(VCacheRead.Unreadable), "⑤ 读不出来的缓存应当先备份再覆盖")
        expect(VaultSyncRules.mustBackUpBeforeOverwrite(VCacheRead.Ok(garbled)), "⑤ record 解不开的缓存应当先备份再覆盖（和 pullAdopt 同一口径）")
        expect(!VaultSyncRules.mustBackUpBeforeOverwrite(VCacheRead.Ok(good)) && !VaultSyncRules.mustBackUpBeforeOverwrite(VCacheRead.Missing),
            "⑤ 读得出 / 没有的缓存不该备份")
        // pullAdopt 认「读不出」的，mustBackUpBeforeOverwrite 也要认 —— 两边口径一漂，就有一种坏法从缝里漏过去。
        for (r in listOf(VCacheRead.Missing, VCacheRead.Unreadable, VCacheRead.Ok(good), VCacheRead.Ok(garbled))) {
            val keepFile = VaultSyncRules.pullAdopt(false, r, "K", false) == VaultSyncRules.PullAdopt.AdoptCloudKeepFile
            expect(keepFile == VaultSyncRules.mustBackUpBeforeOverwrite(r), "⑤ pullAdopt 与 mustBackUpBeforeOverwrite 对 $r 的口径不一致")
        }
        val B = VaultSyncRules.CacheBackup
        // iOS 用 Date(timeIntervalSince1970: 1_791_000_000.123)；Kotlin 的 name 收毫秒。
        val t0 = 1_791_000_000_123L
        val n0 = B.name(t0); val n1 = B.name(t0 + 1); val n2 = B.name(t0 + 86_400_000L)
        expect(n0 == "umbra-vault-cache.unreadable-20261003T040000.123Z.json", "⑤ 备份名格式变了：$n0")
        expect(n0 < n1 && n1 < n2, "⑤ 备份名的字典序应当就是时间序：$n0 / $n1 / $n2")
        expect(B.isBackup(n0) && !B.isBackup("umbra-vault-cache.json") && !B.isBackup("other.json"), "⑤ isBackup 判错")
        val dir = listOf("umbra-vault-cache.json", "other.txt",
            B.name(t0), B.name(t0 + 1000), B.name(t0 + 2000), B.name(t0 + 3000), B.name(t0 + 4000))
        val gone = B.prune(dir)
        expect(gone.sorted() == listOf(B.name(t0), B.name(t0 + 1000)), "⑤ 应当只删最旧的两份、留最近 ${B.KEEP} 份：删了 $gone")
        expect(!gone.contains("umbra-vault-cache.json") && !gone.contains("other.txt"), "⑤ prune 动了不是备份的文件")
        expect(B.prune(dir.take(4)).isEmpty(), "⑤ 没超过上限时不该删")
    }

    /** 本机建库（单机化 ③）：Secret Key 形制与 PC 同形（U1 + 26 位 Base32，5 一组），内置类型表与 PC 逐字相同。 */
    @Test fun secretKeyCases() {
        val bytes = ByteArray(16) { (it * 17 and 0xff).toByte() }
        val sk = VaultSecretKey.format(bytes)
        expect(sk.startsWith("U1-"), "Secret Key 版本前缀应是 U1-：$sk")
        val body = sk.drop(3).replace("-", "")
        expect(body.length == 26, "16 字节 Base32 应是 26 字符（无填充）：${body.length}")
        expect(sk.split("-").drop(1).map { it.length } == listOf(5, 5, 5, 5, 5, 1), "应每 5 字符一组：$sk")
        expect(body.all { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567" }, "只许 Base32 字符：$body")
        expect(VaultSecretKey.base32Encode("foobar".toByteArray()) == "MZXW6YTBOI", "RFC 4648 向量 foobar → MZXW6YTBOI")
        val types = VaultDefaults.types(listOf("t1", "t2", "t3", "t4"))
        expect(types.map { it.name } == listOf("登录", "银行卡", "证件/文档", "安全笔记"), "内置类型应与 PC defaultTypes 同名同序")
        expect(types.map { it.icon } == listOf("🔑", "💳", "🪪", "📝"), "内置类型图标应与 PC 相同")
        expect(types.map { it.order } == listOf(0.0, 1.0, 2.0, 3.0) && types.map { it.id } == listOf("t1", "t2", "t3", "t4"), "内置类型顺序与 id 应按入参")
    }

    /** 2026-10-12：重装后要显式输入 Secret Key —— 钥匙串有值不够，还要这次安装里确认过的标记；四格真值表全钉。 */
    @Test fun secretKeyOnFileCases() {
        expect(VaultSyncRules.secretKeyOnFile(true, true), "钥匙串有值 + 确认过 = 有")
        expect(!VaultSyncRules.secretKeyOnFile(true, false), "钥匙串有值但没确认（重装）= 没有，要画输入框")
        expect(!VaultSyncRules.secretKeyOnFile(false, true), "标记在但钥匙串没值 = 没有")
        expect(!VaultSyncRules.secretKeyOnFile(false, false), "两样都没有 = 没有")
    }
}
