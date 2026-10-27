// 保险箱同步里几条**不看就会丢数据**的判据（iOS VaultSyncRules.swift，umbra-ios #17 / #18 / #28）。
// 纯 Kotlin，VaultSyncRulesTest 照抄 iOS Tools/swifttests/VaultSyncRulesTests.swift 的场景（含假服务端的竞态）。
//
// 为什么非得是行为用例：`force` 这个字面量在不在，和它什么时候为 true 是两件事；
// 「有没有一个剔孤儿附件的函数」，和「它在孤儿上返回什么、有没有在推之前被调到」也是两件事。
package com.geek.umbra.feature.vault

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** 一次 PUT /vault/sync 的结果，已经从 JSON 里拆好。 */
sealed interface VaultPutReply {
    /** 写进去了，服务端给的新 rev。 */
    data class Ok(val rev: Int) : VaultPutReply
    /** baseRev 对不上。服务端当场把当前那份带回来了（合并直接用它，不再多发一次 GET）。 */
    data class Conflict(val rev: Int, val blob: String?) : VaultPutReply
    /** 请求没发出去 / 响应不是 JSON（断网、超时、502）。 */
    data object Unreachable : VaultPutReply
    /** 服务端明确回了不是 ok、也不是 conflict 的东西。 */
    data object Rejected : VaultPutReply
    /**
     * 426 `upgrade_required`：云端的保险箱格式比这一版认识的新（方案 §三第 3 条，为以后的 fmt 4 留的口子）。
     * 和 [Rejected] 分开：那一种给［重试］，这一种重试没用，给「去应用商店升级」。
     */
    data object UpgradeRequired : VaultPutReply
}

enum class VaultMergeFailure {
    /** 拿不到云端那份。 */
    Unreachable,
    /** 云端那份解不开。 */
    Unreadable,
    /** **云端换过主密码 / Secret Key**：用户要做的是重新解锁，不是等。 */
    KeyMismatch,
    /** 冲突说云端有新的，可拉回来的 rev 和本机一样 —— 自相矛盾。 */
    NothingNewer,
    /** 网络往返期间本机状态变了，回包作废（不出声，下一次同步自然会再拉）。 */
    Superseded,
}

/** 把云端那份合并进本机这一步的结果。**每一条早退都必须落到 Failed 并说清是哪一种。** */
sealed interface VaultMergeResult {
    data object Merged : VaultMergeResult
    data class Failed(val why: VaultMergeFailure) : VaultMergeResult
}

/** 一次推送最后落在哪儿。 */
sealed interface VaultPushOutcome {
    data class Pushed(val rev: Int, val blob: String) : VaultPushOutcome
    /** 推到一半上过锁 / 换过钥匙重新解开：后面的一律不做、回包不认。 */
    data object Superseded : VaultPushOutcome
    /** 推不上去（多半断网）。改动已在本机缓存里，标待推。 */
    data object Offline : VaultPushOutcome
    /** 有冲突、而这一趟没能把云端那份合并进来 —— **就此停下，不 force**。 */
    data class MergeFailed(val why: VaultMergeFailure) : VaultPushOutcome
    /** 本机这份编不出来（原因调用方另记 [VaultEncodeFailure]）。 */
    data object EncodeFailed : VaultPushOutcome
    data object Rejected : VaultPushOutcome
    /** 服务端说要先升级（426 `upgrade_required`）：不再重试，本机改动留着，升级后再推。 */
    data object UpgradeRequired : VaultPushOutcome
    /** 三趟都回了冲突（按现在的协议走不到：第三趟一定带 force；走到了说明服务端没认 force）。 */
    data object GaveUp : VaultPushOutcome
}

/** `encodeForPush` 为什么没编出来。 */
enum class VaultEncodeFailure {
    /** 钥匙和 record 不是一对（umbra-ios#28 场景 B）：要重新解锁。 */
    KeyMismatch,
    /** 快照编不出来 / AES 加密失败：这一次的改动**没进本机缓存**，只在内存里。 */
    CannotEncode,
}

object VaultSyncRules {
    /** 解锁页要不要画 Secret Key 输入框：存储里有值 **且** 这次安装里确认过。 */
    fun secretKeyOnFile(keychainHasValue: Boolean, confirmed: Boolean): Boolean = keychainHasValue && confirmed

    /** 最多试几次 PUT。 */
    const val MAX_ATTEMPTS = 3

    /**
     * 这一趟 PUT 带不带 `force`：只有「到了最后一次，而且之前每一次冲突都真的合并成功了」才给。
     * ⚠️ 原来是无条件 `attempt == 2` —— 合并静默失败时第三次照样 force，盖掉本机从来没读到过的东西。
     */
    fun force(attempt: Int, mergedAtLeastOnce: Boolean): Boolean = attempt == MAX_ATTEMPTS - 1 && mergedAtLeastOnce

    /**
     * 推送的重试循环。三样从外面注入（假服务端能直接喂进来）：`encode` **每一趟都重新编**；`send` 发一次 PUT；
     * `merge` 把冲突回来的那份合并进本机。`sameUnlock` 每一趟 encode 之前、每次 PUT 回来之后都问（没有默认值）。
     * 调用方要保证三个闭包在同一个线程（主线程）上跑 —— 它们读写的都是 Store 的状态。
     */
    suspend fun run(
        sameUnlock: () -> Boolean,
        encode: () -> String?,
        send: suspend (blob: String, force: Boolean) -> VaultPutReply,
        merge: suspend (rev: Int, blob: String?) -> VaultMergeResult,
    ): VaultPushOutcome {
        var merged = false
        for (attempt in 0 until MAX_ATTEMPTS) {
            if (!sameUnlock()) return VaultPushOutcome.Superseded
            val blob = encode() ?: return VaultPushOutcome.EncodeFailed
            val reply = send(blob, force(attempt, merged))
            if (!sameUnlock()) return VaultPushOutcome.Superseded
            when (reply) {
                is VaultPutReply.Ok -> return VaultPushOutcome.Pushed(reply.rev, blob)
                VaultPutReply.Unreachable -> return VaultPushOutcome.Offline
                VaultPutReply.Rejected -> return VaultPushOutcome.Rejected
                VaultPutReply.UpgradeRequired -> return VaultPushOutcome.UpgradeRequired
                is VaultPutReply.Conflict -> when (val m = merge(reply.rev, reply.blob)) {
                    VaultMergeResult.Merged -> merged = true
                    is VaultMergeResult.Failed -> return VaultPushOutcome.MergeFailed(m.why)
                }
            }
        }
        return VaultPushOutcome.GaveUp
    }

    // MARK: 推之前剔掉孤儿附件（umbra-ios#18）

    /** 一个库的附件字节收敛到「真的被某条记录引用着」的那些。没有孤儿时返回 null（不用动）。 */
    fun prunedAttachments(bytes: Map<String, String>, referencedBy: List<List<String>>): Map<String, String>? {
        val referenced = referencedBy.flatten().toSet()
        if (bytes.keys.none { it !in referenced }) return null
        return bytes.filterKeys { it in referenced }
    }

    /** 整份快照做一遍 [prunedAttachments]。返回 (新快照, 剔掉几份)。 */
    fun pruneOrphanAttachments(snap: VSnapshot): Pair<VSnapshot, Int> {
        var n = 0
        val data = snap.data.mapValues { (_, d) ->
            val kept = prunedAttachments(d.attachments, d.items.map { it.attachments.map { a -> a.id } }) ?: return@mapValues d
            n += d.attachments.size - kept.size
            d.copy(attachments = kept)
        }
        return snap.copy(data = data) to n
    }

    /** **推之前把内联的附件字节全部去掉**（画布版 v2：附件字节走 files kind=vault，手机自己不能加附件）。 */
    fun stripInlineAttachments(snap: VSnapshot): Pair<VSnapshot, Int> {
        var n = 0
        val data = snap.data.mapValues { (_, d) ->
            if (d.attachments.isEmpty()) d else { n += d.attachments.size; d.copy(attachments = emptyMap()) }
        }
        return snap.copy(data = data) to n
    }

    /** 这一趟推上去的那份是不是**本机最后一次落盘**的那份。是才许把缓存写成「已同步」。 */
    fun pushedIsLatest(pushedSeq: Int, latestSeq: Int): Boolean = pushedSeq == latestSeq

    /** 盘上那份缓存是不是「要先挪去备份、才许覆盖」的那种：**文件在、却读不出来 / 里面的 record 解不开**。 */
    fun mustBackUpBeforeOverwrite(r: VCacheRead): Boolean = when (r) {
        VCacheRead.Missing -> false
        VCacheRead.Unreadable -> true
        is VCacheRead.Ok -> r.cache.record == null
    }

    /** 读不出来的缓存挪去的备份文件怎么起名、留几份。挪走留着而不是删：读不出来 ≠ 里面没东西。 */
    object CacheBackup {
        const val KEEP = 3
        const val PREFIX = "umbra-vault-cache.unreadable-"
        const val SUFFIX = ".json"
        private val fmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSS'Z'").withZone(ZoneOffset.UTC)

        /** 时间戳用 UTC、补零到毫秒 —— 字典序就是时间序。 */
        fun name(atMs: Long): String = PREFIX + fmt.format(Instant.ofEpochMilli(atMs)) + SUFFIX

        fun isBackup(fileName: String): Boolean = fileName.startsWith(PREFIX) && fileName.endsWith(SUFFIX)

        /** 目录里这些文件名中，该删掉的那几份（最旧的、超出 keep 的）。别的文件一个都不碰。 */
        fun prune(fileNames: List<String>, keep: Int = KEEP): List<String> =
            fileNames.filter { isBackup(it) }.sortedDescending().drop(maxOf(keep, 0))
    }

    // MARK: 合并

    /**
     * 按 id 合并两份快照：库与类型取并集；记录 revision 高者胜（相等时 updatedAt 晚者胜）；附件字节取并集
     * （拉取这一侧分不开「远端删了」还是「那台还没拿到」，误删比多留贵；孤儿在推之前剔）。
     */
    fun merge(local: VSnapshot, remote: VSnapshot, deviceName: String = ""): VSnapshot {
        val vaults = local.vaults.toMutableList()
        for (rv in remote.vaults) if (vaults.none { it.id == rv.id }) vaults += rv
        val data = local.data.toMutableMap()
        for ((vid, rdata) in remote.data) {
            val ld = data[vid] ?: VData.EMPTY
            if (ld.isTree || rdata.isTree) { data[vid] = mergeTree(ld, rdata, deviceName); continue }
            val types = ld.types.toMutableList()
            for (t in rdata.types) if (types.none { it.id == t.id }) types += t
            val byId = LinkedHashMap<String, VItem>()
            for (it in ld.items) byId[it.id] = it
            for (rit in rdata.items) {
                val c = byId[rit.id]
                if (c == null || rit.revision > c.revision || (rit.revision == c.revision && rit.updatedAt > c.updatedAt)) byId[rit.id] = rit
            }
            val atts = ld.attachments.toMutableMap()
            for ((aid, b) in rdata.attachments) if (atts[aid] == null) atts[aid] = b
            data[vid] = VData(types, byId.values.toList(), atts, extra = rdata.extra + ld.extra)
        }
        val v = if (data.values.any { it.isTree }) maxOf(local.v, remote.v, 3) else local.v
        return VSnapshot(v, vaults, data)
    }

    /**
     * 有一边是树形（fmt 3）的库：fmt 2 那一边先按确定性迁移迁成树（同一份 fmt 2 在哪迁都一样，所以「云端还是 fmt 2」时
     * 合并就是普通的 rev 大者胜，方案 §二第 6 条），再按 [VaultMerge3] 逐对象合并。fmt 取两边大的。
     * ⚠️ 本机那一边是 fmt 2 时，Store 应当已经先走过「备份 → 迁移 → 自检」那一套（[VaultStore] 的 migrateLocal）；
     * 这里再迁一次只是兜底，保证合并永远拿两棵树比。
     */
    fun mergeTree(local: VData, remote: VData, deviceName: String = ""): VData {
        val lt = local.tree ?: VaultMigrate3.migrate(local.items.map { it.toJson() })
        val rt = remote.tree ?: VaultMigrate3.migrate(remote.items.map { it.toJson() })
        return VData(emptyList(), emptyList(), emptyMap(), maxOf(local.fmt, remote.fmt, 3), VaultMerge3.merge(lt, rt, deviceName), remote.extra + local.extra)
    }

    // MARK: fmt 3 迁移的时机与备份（方案 §二第 2 条、§六）

    /**
     * 本机的 fmt 2 现在迁不迁：**没登录的本机库直接迁**（没有别的设备要照顾）；登录着的，要么云端已经是 fmt 3，
     * 要么服务端开关 `fmtTarget >= 3`。两个都还不知道（这一轮没拉到云端）就先不迁 —— 宁可晚一点，别抢在开关前面
     * 把旧版手机上的保险箱变成只读。
     */
    fun shouldMigrate(signedIn: Boolean, cloudFmt: Int?, fmtTarget: Int?): Boolean =
        !signedIn || (cloudFmt ?: 2) >= 3 || (fmtTarget ?: 2) >= 3

    /**
     * 迁移前那份 fmt 2 本机缓存的原样拷贝（方案 §二第 2 条：拷贝的本来就是密文；只留最近一份，保留 90 天）。
     * 名字带 UTC 时间戳，字典序 = 时间序；和「读不出来的缓存」那组备份（[CacheBackup]）前缀不同，互不清理。
     */
    object Fmt2Backup {
        const val PREFIX = "umbra-vault-cache.fmt2-"
        const val SUFFIX = ".bak.json"
        const val KEEP_MS = 90L * 24 * 3600 * 1000
        private val fmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSS'Z'").withZone(ZoneOffset.UTC)

        fun name(atMs: Long): String = PREFIX + fmt.format(Instant.ofEpochMilli(atMs)) + SUFFIX
        fun isBackup(fileName: String): Boolean = fileName.startsWith(PREFIX) && fileName.endsWith(SUFFIX)

        /** 文件名里的时间（毫秒）；不是这种文件 / 解析不了回 null。 */
        fun timeOf(fileName: String): Long? {
            if (!isBackup(fileName)) return null
            val stamp = fileName.removePrefix(PREFIX).removeSuffix(SUFFIX)
            return runCatching { Instant.from(fmt.parse(stamp)).toEpochMilli() }.getOrNull()
        }

        /** 该删的：除了最新的那一份全删；最新那份超过 90 天也删。别的文件一个不碰。 */
        fun prune(fileNames: List<String>, nowMs: Long): List<String> {
            val mine = fileNames.filter { isBackup(it) }.sortedDescending()
            val old = mine.drop(1)
            val newest = mine.firstOrNull()?.takeIf { n -> timeOf(n)?.let { nowMs - it > KEEP_MS } ?: true }
            return old + listOfNotNull(newest)
        }
    }

    enum class MergeGate { Proceed, NothingNewer, Superseded }

    /**
     * pullMerge 拿到云端那份之后、**解密之前**问这一句：解锁代次变过 → 作废；GET 回包而 syncRev 已不是这一趟的基线 → 作废；
     * 云端 rev 就是基线 → 没有新东西。冲突回包不看 syncRev（它就是那次 PUT 自己的回答，必须并）。
     */
    fun mergeGate(fetchedRev: Int, baseRev: Int, viaConflict: Boolean, syncRevNow: Int, sameUnlock: Boolean): MergeGate {
        if (!sameUnlock) return MergeGate.Superseded
        if (!viaConflict && syncRevNow != baseRev) return MergeGate.Superseded
        return if (fetchedRev == baseRev) MergeGate.NothingNewer else MergeGate.Proceed
    }

    /** `pullRecord` 拉到云端那份之后该怎么处置。 */
    enum class PullAdopt {
        /** 已解锁：一个字都不许动，改走 syncNow → pullMerge。 */
        MergeInstead,
        /** 采用云端那份：record / syncRev / 缓存都换成它。 */
        AdoptCloud,
        /** 本机缓存里有没推上去的改动：用缓存那份解锁，**不覆盖缓存**。 */
        KeepLocal,
        /** 本机缓存在、但读不出来：用云端那份解锁，**但不许写盘**。 */
        AdoptCloudKeepFile,
    }

    fun pullAdopt(unlocked: Boolean, cache: VCacheRead, cloudVerifier: String, preferCloudKey: Boolean): PullAdopt {
        if (unlocked) return PullAdopt.MergeInstead
        val c = when (cache) {
            VCacheRead.Missing -> return PullAdopt.AdoptCloud
            VCacheRead.Unreadable -> return PullAdopt.AdoptCloudKeepFile
            is VCacheRead.Ok -> cache.cache
        }
        val local = c.record ?: return PullAdopt.AdoptCloudKeepFile
        if (c.pending != true) return PullAdopt.AdoptCloud
        if (local.verifier != cloudVerifier && preferCloudKey) return PullAdopt.AdoptCloud
        return PullAdopt.KeepLocal
    }

    enum class UnlockPick { Local, Cloud, PromptCloud, Wrong }

    /**
     * 这个密码该解哪一份。`matchesLocal` / `matchesCloud` 是**惰性**的（PBKDF2 60 万次一次将近一秒，用不着就别算）；
     * `matchesCloud == null` = 没有一份「换过主密码的云端」在等着。认定以云端为准时先试云端，不对再试本机。
     */
    fun unlockPick(preferCloud: Boolean, matchesLocal: () -> Boolean, matchesCloud: (() -> Boolean)?): UnlockPick {
        if (preferCloud && matchesCloud != null && matchesCloud()) return UnlockPick.Cloud
        if (matchesLocal()) return UnlockPick.Local
        if (!preferCloud && matchesCloud != null && matchesCloud()) return UnlockPick.PromptCloud
        return UnlockPick.Wrong
    }

    /** 要套在密文外面的那份 record 的 verifier，是不是解锁时验过的那一个。没解锁过一律不许。 */
    fun mayEncode(recordVerifier: String?, unlockedVerifier: String?): Boolean {
        if (recordVerifier == null || unlockedVerifier.isNullOrEmpty()) return false
        return recordVerifier == unlockedVerifier
    }

    enum class RetryAction { SyncNow, Relock }

    fun retryAction(needsReunlock: Boolean): RetryAction = if (needsReunlock) RetryAction.Relock else RetryAction.SyncNow

    enum class ProblemUpdate { Clear, Keep, Reunlock, CannotMerge, Rejected, GaveUp, CannotEncode, UpgradeRequired }

    /** 一次推送之后 syncProblem / needsReunlock 怎么改。断网清掉旧原因，唯独「云端换过主密码」不清。 */
    fun problemAfter(outcome: VaultPushOutcome, needsReunlock: Boolean, encodeFailure: VaultEncodeFailure?): ProblemUpdate = when (outcome) {
        is VaultPushOutcome.Pushed -> ProblemUpdate.Clear
        VaultPushOutcome.Superseded -> ProblemUpdate.Keep
        VaultPushOutcome.Offline -> if (needsReunlock) ProblemUpdate.Keep else ProblemUpdate.Clear
        is VaultPushOutcome.MergeFailed -> when (outcome.why) {
            VaultMergeFailure.Unreachable -> if (needsReunlock) ProblemUpdate.Keep else ProblemUpdate.Clear
            VaultMergeFailure.KeyMismatch -> ProblemUpdate.Reunlock
            VaultMergeFailure.Unreadable, VaultMergeFailure.NothingNewer -> ProblemUpdate.CannotMerge
            VaultMergeFailure.Superseded -> ProblemUpdate.Keep
        }
        VaultPushOutcome.Rejected -> ProblemUpdate.Rejected
        VaultPushOutcome.UpgradeRequired -> ProblemUpdate.UpgradeRequired
        VaultPushOutcome.GaveUp -> ProblemUpdate.GaveUp
        VaultPushOutcome.EncodeFailed -> when (encodeFailure) {
            VaultEncodeFailure.KeyMismatch -> ProblemUpdate.Reunlock
            VaultEncodeFailure.CannotEncode -> ProblemUpdate.CannotEncode
            null -> ProblemUpdate.Keep
        }
    }
}
