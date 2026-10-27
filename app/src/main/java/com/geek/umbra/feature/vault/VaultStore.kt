// 保险箱的进程级 Store —— 照抄 iOS VaultCore.swift `VaultStore`（同步客户端 + 内存明文）。
//
// 零知识端到端加密：解锁时用主密码 + Secret Key 派生 AUK，从服务器拉密文快照解密展示；改动后加密回推。
// 加解密在 VaultCrypto（有跨端向量用例），同步判据在 VaultSyncRules（有行为用例）；这里只管状态与 IO。
//
// 线程：所有状态读写都在主线程（AppGraph.scope 是 Main.immediate）——同 iOS 的 @MainActor。PBKDF2 / 整份快照解密
// 放 Dispatchers.Default，回来再改状态；「回来时还是不是那一轮」由解锁代次 [unlockGen] 判。
// 推之前的编码（encodeForPush）刻意同步做：它和「落盘序号」「代次」必须在同一拍里，拆成异步就要再造一套锁。
package com.geek.umbra.feature.vault

import com.geek.umbra.app.Brand
import com.geek.umbra.app.AppGraph
import com.geek.umbra.model.TrashList
import com.geek.umbra.net.HttpResult
import com.geek.umbra.net.UmbraHttp
import com.geek.umbra.net.UmbraUrl
import com.geek.umbra.net.await
import com.geek.umbra.net.dataOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/** 界面读的那一份状态。字段与 iOS VaultStore 的 @Published 一一对应。 */
data class VaultState(
    val unlocked: Boolean = false,
    val vaults: List<VVaultInfo> = emptyList(),
    val curVaultId: String = "",
    val types: List<VType> = emptyList(),
    val items: List<VItem> = emptyList(),
    val loading: Boolean = false,
    val error: String = "",
    /** 本机有没有一把**确认过的** Secret Key。false 时解锁页画出输入框。 */
    val hasSecretKey: Boolean = false,
    val recordExists: Boolean = false,
    /** 这次用的是本机缓存（拉不到服务端）。界面要如实说出来。 */
    val offline: Boolean = false,
    /** 有本地改动还没推上去。 */
    val pendingPush: Boolean = false,
    /** 这一次**没推上去、又不是断网**的原因（云端换过主密码、合并不进来…）。空串 = 没有。 */
    val syncProblem: String = "",
    /** syncProblem 是「云端换过主密码」那一档：提示条上给「锁定并重新解锁」，不给「重试」。 */
    val needsReunlock: Boolean = false,
    /** 回收站里有几条。**锁着时也有值**（读的是本机缓存里那个明文数字）。 */
    val trashCount: Int = 0,
    /** 快照每换一次 +1：回收站行这类从私有快照现算的东西靠它触发重组。 */
    val snapTick: Int = 0,
    /** 当前身份库是树形（fmt 3）时的正文；null = 还是 fmt 2（界面走 [items] 那一套）。 */
    val tree: Tree3? = null,
    /**
     * 只读：保险箱格式比这一版认识的新（快照 v > 3 / 库 fmt > 3，或 PUT 回了 426 `upgrade_required`）。
     * 界面顶上挂 295 §四 的条幅，想改东西时吐司说一次、不进编辑态；本机已有的改动留着，升级后再推。
     */
    val upgradeRequired: Boolean = false,
    /** 这一轮解锁里迁移自检没过（数据一点没改）：首页挂 296 §三第 2 条那张条幅，［再试一次］。 */
    val migrateFailed: Boolean = false,
)

/** 工具页卡片要的三样（iOS ToolCardRules.vaultSub 的输入）。 */
data class VaultToolStatus(val exists: Boolean, val unlocked: Boolean, val count: Int)

object VaultStore {
    private val _state = MutableStateFlow(
        VaultState(hasSecretKey = VaultSyncRules.secretKeyOnFile(VaultKeychain.hasValue, VaultKeychain.confirmed)),
    )
    val state: StateFlow<VaultState> = _state

    /** 工具页读的：是否存在（云端认过有 / 本机有确认过的 SK）、是否解锁、条数。 */
    val toolStatus: StateFlow<VaultToolStatus> = _state.map {
        VaultToolStatus(it.recordExists || it.hasSecretKey, it.unlocked, it.tree?.nodes?.count { n -> n.live } ?: it.items.size)
    }.stateIn(AppGraph.scope, SharingStarted.Eagerly, VaultToolStatus(false, false, 0))

    private inline fun set(f: (VaultState) -> VaultState) = _state.update(f)
    val s: VaultState get() = _state.value

    private var auk: ByteArray? = null
    private var record: VRecord? = null
    private var snapshot: VSnapshot? = null
    private var syncRev = 0
    private val vaultKeys = HashMap<String, ByteArray>()
    /** 已经从云端拉下来、解开的附件字节（按 fileId）。**上锁即清** —— 它是明文，只在内存里。 */
    private val attPlain = HashMap<String, ByteArray>()
    /** 解锁时验过的那个 verifier —— 内存里这把 AUK 和哪份 record 是一对。 */
    private var unlockedVerifier: String? = null
    /** 锁着时拉到的云端那份，只在「本机有没推的改动、云端又换过主密码」时留着。 */
    private var cloudPending: Triple<VRecord, Int, String>? = null
    /** 用户明确选了「以云端为准、用新主密码解锁」。 */
    private var preferCloudKey = false
    /** 这一轮解锁里已经提示过一次「两份只能留一份」。⚠️ 不能复用 preferCloudKey（理由见 iOS VaultCore.swift:223）。 */
    private var cloudPrompted = false
    /** 解锁代次：每次上锁 / 解开都 +1。网络往返前记一下，回来对不上就整份丢掉回包。 */
    private var unlockGen = 0
    /** 本机缓存「推之前落盘」的序号。 */
    private var cacheEncodeSeq = 0
    private var lastEncodeFailure: VaultEncodeFailure? = null
    /** 这个进程里已经确认过盘上那份缓存「读得出来 / 根本没有」。 */
    private var cacheVouched = false
    /** 最近一次 GET /vault/sync 回的云端格式（没拉到过 = null）与服务端开关 `fmtTarget`。迁移时机由它们判（[VaultSyncRules.shouldMigrate]）。 */
    private var cloudFmt: Int? = null
    private var fmtTarget: Int? = null
    /** 这一轮解锁里迁移自检没过：这一次不同步（方案 §二第 4 条），下次解锁再试。存的是那一轮的代次。 */
    private var migrationBlockedGen = -1
    /** PUT 回过 426 `upgrade_required`（这一轮解锁内有效）。 */
    private var putSaidUpgrade = false

    private val http: UmbraHttp get() = AppGraph.http

    // MARK: - 本机缓存（离线可用）
    //
    // 缓存的是**密文** —— 和服务端存的是同一份 blob，没有 AUK 谁也读不懂。trashCount / pending 是两格明文旁注，**不上传**。
    // 目录 filesDir/vault 已在备份规则里排除。

    private const val CACHE_NAME = "umbra-vault-cache.json"
    private val cacheDir: File get() = File(AppGraph.context.filesDir, "vault").apply { mkdirs() }
    private val cacheFile: File get() = File(cacheDir, CACHE_NAME)

    /** `pending` 必须由调用方**明说**，不给默认值（umbra-ios#28 场景 C）。 */
    private fun writeCache(blob: String, rev: Int, pending: Boolean) =
        writeCache(VCache(blob, rev, s.trashCount, pending))

    /**
     * 写本机缓存。**盘上那份读不出来时先挪去备份，再写**；顺序是「新的先写进临时文件 → 旧的挪去备份 → 临时文件挪到位」，
     * 新的写不进就一个字节都不动（iOS VaultCore.swift:345）。
     */
    private fun writeCache(c: VCache) {
        val data = c.encode().toByteArray(Charsets.UTF_8)
        val dir = cacheDir
        val url = cacheFile
        val tmp = File(dir, ".$CACHE_NAME.${java.util.UUID.randomUUID()}.tmp")
        if (!runCatching { tmp.writeBytes(data) }.isSuccess) { tmp.delete(); return }
        if (!cacheVouched && VaultSyncRules.mustBackUpBeforeOverwrite(readCacheResult())) {
            val backup = File(dir, VaultSyncRules.CacheBackup.name(System.currentTimeMillis()))
            if (!url.renameTo(backup)) { tmp.delete(); return }
            if (!tmp.renameTo(url)) {
                // 挪不到位：把备份挪回去，宁可下次再试，也不留一个「没有缓存」的空档。
                backup.renameTo(url); tmp.delete(); return
            }
            pruneCacheBackups()
            cacheVouched = true
            return
        }
        if (tmp.renameTo(url)) cacheVouched = true else tmp.delete()
    }

    private fun pruneCacheBackups() {
        val names = cacheDir.list()?.toList() ?: return
        for (n in VaultSyncRules.CacheBackup.prune(names)) File(cacheDir, n).delete()
    }

    /** 读本机缓存，**分清没有和读不出**。 */
    private fun readCacheResult(): VCacheRead {
        val f = cacheFile
        if (!f.exists()) { cacheVouched = true; return VCacheRead.Missing }
        val c = runCatching { VCache.parse(f.readText()) }.getOrNull()
        if (c == null) { cacheVouched = false; return VCacheRead.Unreadable }
        val r = VCacheRead.Ok(c)
        cacheVouched = !VaultSyncRules.mustBackUpBeforeOverwrite(r)
        return r
    }

    private fun readCache(): VCache? = readCacheResult().cacheOrNull

    /** 「彻底忘掉」：缓存与它的备份一起删 —— 备份同样是这台设备上的密文。 */
    private fun clearCache() {
        cacheFile.delete()
        cacheDir.list()?.filter { VaultSyncRules.CacheBackup.isBackup(it) || VaultSyncRules.Fmt2Backup.isBackup(it) }?.forEach { File(cacheDir, it).delete() }
        cacheVouched = true
    }

    /** 网络拉不到时回落到本机缓存。解锁态不许回落（会把 record / syncRev 换成缓存那份）。 */
    private fun fallBackToCache(netError: String) {
        if (auk != null) return
        val c = readCache()
        val rd = c?.let { VRecord.parse(it.blob) }
        if (c == null || rd == null) {
            set { it.copy(error = netError, offline = true) }   // 连缓存都没有，才是真的用不了
            return
        }
        record = rd
        syncRev = c.rev
        set { it.copy(trashCount = c.trashCount ?: 0, pendingPush = c.pending ?: false, recordExists = true, offline = true, error = "") }
    }

    /** 页面上「重新输入」清掉错误卡。 */
    fun setErrorForUi(msg: String) = set { it.copy(error = msg) }

    // MARK: - 拉取

    /** GET /vault/sync 的回包（已拆好）。 */
    private class Pulled(val exists: Boolean, val rev: Int, val blob: String?)

    private fun parseSync(body: String): JsonObject? = runCatching { VaultJson.parseToJsonElement(body) as? JsonObject }.getOrNull()

    /**
     * ⚠️ umbra-ios#28：**只给锁着的时候用**（取 salt / verifier / 密文，好让 unlock 有东西可解）。
     * 解锁态一律转去 [refreshUnlocked]（只走 pullMerge，有待推才推）；锁着时拉到的那份怎么用由 [VaultSyncRules.pullAdopt] 判。
     */
    suspend fun pullRecord() {
        if (auk != null) { refreshUnlocked(); return }
        // 不因为凭证为空就提前拒绝：照发，让服务端的 401 来说话（共享密钥时代踩过的坑）。
        when (val r = http.raw("GET", "/vault/sync", mapOf("have_rev" to -1))) {
            // 未登录（单机）：保险箱是本地功能，没有云端可拉不是错误 —— 安静地用本机缓存，不出错误卡、不挂离线条。
            // （原来照 iOS 把请求闸的调试描述「未登录：请求 /vault/sync 在本地被拦下…」当成离线原因显示，模拟器实测。）
            is HttpResult.Blocked -> { fallBackToCache(""); set { it.copy(offline = false) } }
            is HttpResult.Offline -> fallBackToCache("连不上服务器：${r.message}")
            is HttpResult.Fail -> when (r.status) {
                // 401 / 403 只剩一种成因：这台手机的登录服务端不认。出口是账号页（解锁页按「登录」二字给「去账号」）。
                401, 403 -> set { it.copy(error = "还没登录${Brand.productName}账号，或登录已失效。去「我 › 账号」登录后再试。") }
                404 -> fallBackToCache("服务器未部署同步接口（请更新并重启服务端）")
                else -> fallBackToCache("服务器返回 ${r.status}")
            }
            is HttpResult.Ok -> {
                if (r.status != 200) { fallBackToCache("服务器返回 ${r.status}"); return }
                val obj = parseSync(r.body) ?: run { fallBackToCache("服务器响应异常"); return }
                val exists = (obj["exists"] as? JsonPrimitive)?.booleanOrNull ?: false
                val rev = (obj["rev"] as? JsonPrimitive)?.intOrNull ?: 0
                noteFmt(obj)
                val blobStr = (obj["blob"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val rd = blobStr?.let { VRecord.parse(it) }
                if (blobStr == null || rd == null) {
                    if (auk != null) { refreshUnlocked(); return }
                    set { it.copy(recordExists = exists) }
                    syncRev = rev
                    if (exists) fallBackToCache("云端数据解析失败（版本不一致？）")
                    // 云端还没有、本机却有一份待推的（在这台手机上建的库还没登录推上去）→ 用本机那份解锁。
                    else if (readCache()?.pending == true) { fallBackToCache(""); set { it.copy(offline = false) } }
                    return
                }
                // ⚠️ 判据要在**网络往返之后**再问一次「解没解锁」。
                when (val adopt = VaultSyncRules.pullAdopt(auk != null, readCacheResult(), rd.verifier, preferCloudKey)) {
                    VaultSyncRules.PullAdopt.MergeInstead -> refreshUnlocked()
                    VaultSyncRules.PullAdopt.AdoptCloud, VaultSyncRules.PullAdopt.AdoptCloudKeepFile -> {
                        cloudPending = null
                        syncRev = rev
                        record = rd
                        set { it.copy(recordExists = exists, error = "", offline = false, pendingPush = false) }
                        // 本机缓存在、却读不出来时一个字节不动：里面可能有待推的改动。
                        if (adopt == VaultSyncRules.PullAdopt.AdoptCloud) writeCache(blobStr, rev, pending = false)
                    }
                    VaultSyncRules.PullAdopt.KeepLocal -> {
                        fallBackToCache("")
                        set { it.copy(offline = false) }
                        cloudPending = if (rd.verifier != record?.verifier) Triple(rd, rev, blobStr) else null
                    }
                }
            }
        }
    }

    /** 记下回包里的 `fmt` / `fmtTarget`（老服务端没有这两个键：不覆盖已知的值）。 */
    private fun noteFmt(obj: JsonObject) {
        (obj["fmt"] as? JsonPrimitive)?.intOrNull?.let { cloudFmt = it }
        (obj["fmtTarget"] as? JsonPrimitive)?.intOrNull?.let { fmtTarget = it }
    }

    /** 解锁态下「看看云端有没有新的」：**只经 pullMerge**；有待推的改动时才推。 */
    private suspend fun refreshUnlocked() {
        if (s.pendingPush) { syncNow(); return }
        val m = pullMerge()
        if (m is VaultMergeResult.Failed && m.why == VaultMergeFailure.KeyMismatch) {
            set { it.copy(needsReunlock = true, syncProblem = REUNLOCK_PROBLEM) }
        }
    }

    // MARK: - 解锁 / 建库 / 校验

    suspend fun unlock(password: String, secretKey: String) {
        set { it.copy(loading = true, error = "") }
        try {
            if (record == null) pullRecord()
            val localRec = record ?: run {
                if (s.error.isEmpty()) set { it.copy(error = "云端还没有数据，请先在电脑端「立即同步」一次") }
                return
            }
            // 没亲手输就用存着的那把 —— **只在本机确认过的前提下**（hasSecretKey）。
            val sk = secretKey.ifEmpty { if (s.hasSecretKey) VaultKeychain.load() ?: "" else "" }
            if (sk.isEmpty()) { set { it.copy(error = "首次在本机解锁需输入 Secret Key（电脑端 Emergency Kit）") }; return }
            var localKey: ByteArray? = null
            var cloudKey: ByteArray? = null
            val pending = cloudPending
            val preferCloud = preferCloudKey || cloudPrompted
            val pick = withContext(Dispatchers.Default) {
                VaultSyncRules.unlockPick(
                    preferCloud,
                    matchesLocal = { localKey = VaultCrypto.tryKey(password, sk, localRec); localKey != null },
                    matchesCloud = pending?.let { c -> { cloudKey = VaultCrypto.tryKey(password, sk, c.first); cloudKey != null } },
                )
            }
            val a: ByteArray
            val rec: VRecord
            when (pick) {
                VaultSyncRules.UnlockPick.PromptCloud -> {
                    // 他输的是**新**主密码：说清楚两份只能留一份，再输一次新密码就以云端为准。只记 cloudPrompted。
                    cloudPrompted = true
                    set {
                        it.copy(error = "云端的主密码已经在别的设备上换过了，而这台手机上还有一批没推上去的改动，是用旧主密码加密的，两份合不到一起。" +
                            "输旧主密码解锁可以先看到它们；确定不要了，用新主密码再解锁一次，就以云端那份为准。")
                    }
                    return
                }
                VaultSyncRules.UnlockPick.Wrong -> { set { it.copy(error = "主密码或 Secret Key 不正确") }; return }
                VaultSyncRules.UnlockPick.Local -> { a = localKey ?: run { set { it.copy(error = "主密码或 Secret Key 不正确") }; return }; rec = localRec }
                VaultSyncRules.UnlockPick.Cloud -> {
                    a = cloudKey ?: run { set { it.copy(error = "主密码或 Secret Key 不正确") }; return }
                    rec = pending?.first ?: run { set { it.copy(error = "主密码或 Secret Key 不正确") }; return }
                }
            }
            val snap = withContext(Dispatchers.Default) { VaultCrypto.aesDecrypt(a, rec.enc)?.let { VSnapshot.parse(it) } }
                ?: run { set { it.copy(error = "解密失败") }; return }
            auk = a
            unlockGen++
            unlockedVerifier = rec.verifier
            if (pick == VaultSyncRules.UnlockPick.Cloud && pending != null) {
                // 以云端为准：record / syncRev / 缓存换成云端那份，待推标记清掉（那批改动已经确认不要了）。
                record = pending.first; syncRev = pending.second
                set { it.copy(pendingPush = false) }
                writeCache(pending.third, pending.second, pending = false)
            }
            cloudPending = null
            preferCloudKey = false
            cloudPrompted = false
            VaultKeychain.save(sk)
            set { it.copy(hasSecretKey = true) }
            putSaidUpgrade = false
            applySnapshot(snap)
            set { it.copy(unlocked = true) }
            pruneFmt2Backups()
            // 到了迁移的时候（没登录 / 云端已是 3 / 开关翻了）：备份 → 迁移 → 自检 → 落盘 → syncRev 归零，然后整份拉一次再推。
            val migrated = migrateIfDue()
            // 解开的是一份带着离线改动的缓存（或刚迁完）：解锁这一刻就去「拉合并 → 推」。
            if (s.pendingPush || migrated) AppGraph.scope.launch { syncNow() }
            // 回收站的到期清理借解锁这一刻做（锁着时没有密钥）。不 await：杂务不该拖慢解锁。
            AppGraph.scope.launch { sweepExpiredTrash() }
            // iOS 这里还会把可填充的记录登记进系统身份库（QuickType）。Android 自动填充没有预登记身份的机制，
            // 每次由 AutofillService 实时匹配 —— 无对等物（docs §五 ⛔ 系统身份库）。
        } finally {
            set { it.copy(loading = false) }
        }
    }

    /**
     * 本机建库（单机化 ③）：和 PC `VaultManager.setup` 同一套 —— 随机 salt + Secret Key，派生 AUK，
     * 一个库「我自己」（四个内置类型、空条目），首份密文**先落本机缓存**（pending）。回 Secret Key 给页面抄；失败回 null。
     * **不自己推**：推不推由调用方定（建库页登着就 syncNow，没登的等登录后 pullRecord 看到 pending 再推）。
     */
    suspend fun createLocal(masterPassword: String): String? {
        if (record != null || readCache() != null || VaultAudit.graphemeCount(masterPassword) < 6) return null
        val salt = VaultCrypto.randomBytes(16)
        val sk = VaultCrypto.generateSecretKey()
        val a = withContext(Dispatchers.Default) { VaultCrypto.deriveAUK(masterPassword, sk, salt) }
        val verifier = VaultCrypto.verifierOf(VaultCrypto.authHash(a, salt))
        val vk = VaultCrypto.randomBytes(32)
        val wrapped = VaultCrypto.aesEncrypt(a, vk) ?: return null
        val vid = VaultCrypto.rid("v")
        val info = VVaultInfo(vid, "我自己", "self", "🧑", 0.0, wrapped)
        val types = VaultDefaults.types(VaultDefaults.typeSeeds.map { VaultCrypto.rid("t") })
        val snap = VSnapshot(1, listOf(info), mapOf(vid to VData(types, emptyList(), emptyMap())))
        val enc = VaultCrypto.aesEncrypt(a, snap.encode().toByteArray(Charsets.UTF_8)) ?: return null
        // 派生期间另一路（首页的 pullRecord）可能已经拿到一份 record：那就不建了。
        if (record != null) return null
        auk = a
        unlockGen++
        unlockedVerifier = verifier
        record = VRecord(1, "pbkdf2", VaultCrypto.b64(salt), verifier, enc)
        syncRev = 0
        cloudPending = null
        VaultKeychain.save(sk)
        set { it.copy(recordExists = true, hasSecretKey = true, error = "") }
        applySnapshot(snap)
        set { it.copy(unlocked = true) }
        if (!migrateIfDue()) encodeForPush()
        set { it.copy(pendingPush = true) }
        return sk
    }

    /** 只校验主密码对不对，**不解密、不改状态**（开启生物识别前验一次）。纯本地、不联网。 */
    suspend fun verifyPassword(password: String): Boolean {
        val rec = record ?: return false
        val sk = VaultKeychain.load()?.takeIf { it.isNotEmpty() } ?: return false
        return withContext(Dispatchers.Default) { VaultCrypto.tryKey(password, sk, rec) != null }
    }

    private fun applySnapshot(snap: VSnapshot) {
        snapshot = snap
        vaultKeys.clear()
        auk?.let { a -> for (v in snap.vaults) VaultCrypto.aesDecrypt(a, v.keyWrapped)?.let { vaultKeys[v.id] = it } }
        val vaults = snap.vaults.sortedBy { it.order }
        val cur = s.curVaultId.takeIf { id -> vaults.any { it.id == id } } ?: vaults.firstOrNull()?.id ?: ""
        set { it.copy(vaults = vaults, curVaultId = cur, upgradeRequired = snap.fromFuture || putSaidUpgrade) }
        loadCurrent()
    }

    private fun loadCurrent() {
        val d = snapshot?.data?.get(s.curVaultId)
        set {
            it.copy(
                types = (d?.types ?: emptyList()).sortedBy { t -> t.order },
                items = (d?.items ?: emptyList()).filter { i -> i.deleted != true }.sortedByDescending { i -> i.updatedAt },
                tree = d?.tree,
                snapTick = it.snapTick + 1,
            )
        }
    }

    fun switchVault(id: String) {
        set { it.copy(curVaultId = id) }
        loadCurrent()
    }

    /** 上锁只清内存里的明文与密钥。**缓存不清** —— 它是密文，留着下次离线还能开。 */
    fun lock() {
        auk = null; unlockedVerifier = null
        unlockGen++   // 在路上的 pullMerge 回来时认得出「已经不是那一轮了」
        snapshot = null; vaultKeys.clear(); attPlain.clear()
        putSaidUpgrade = false
        set { it.copy(unlocked = false, syncProblem = "", needsReunlock = false, items = emptyList(), types = emptyList(), tree = null, upgradeRequired = false, migrateFailed = false, snapTick = it.snapTick + 1) }
    }

    /** 「锁定并重新解锁」（umbra-ios#28）：上锁，并**忘掉手上这份旧 record**、记下「以云端为准」。 */
    fun relockForNewKey() {
        lock()
        record = null
        cloudPending = null
        preferCloudKey = true
    }

    /** 彻底忘掉这台设备上的本地痕迹：密文缓存（含备份）+ Secret Key。 */
    fun forgetLocalData() {
        lock()
        clearCache()
        VaultKeychain.clear()
        record = null
        syncRev = 0
        cloudPending = null
        preferCloudKey = false
        cloudPrompted = false
        set { it.copy(hasSecretKey = false, recordExists = false, offline = false, pendingPush = false, trashCount = 0) }
    }

    // MARK: - 附件

    /** 快照里还内联着的旧 base64（迁移过渡兼容）。 */
    fun imageData(attId: String): ByteArray? =
        snapshot?.data?.get(s.curVaultId)?.attachments?.get(attId)?.let { VaultCrypto.b64Decode(it) }

    fun attName(attId: String): String =
        s.tree?.atts?.firstOrNull { it.id == attId }?.name?.ifEmpty { null }
            ?: s.items.flatMap { it.attachments }.firstOrNull { it.id == attId }?.name ?: "文件"

    /**
     * 画布版附件：从云端 `GET /files/{fileId}` 拉那份密文、用**当前库**的 VaultKey 解开。拉不到 / 解不开 / 没解锁 → null。
     * ⚠️ 统一出口 [UmbraHttp.raw] 只回文本（`body.string()`），二进制会被按 UTF-8 解坏 —— 所以这里直接用它的 OkHttp 客户端，
     * 请求闸 / Bearer 照它的规矩手搭一遍（地基缺「取字节」的接口，已报告）。
     */
    suspend fun attachmentBytes(fileId: String): ByteArray? {
        attPlain[fileId]?.let { return it }
        if (auk == null || fileId.isEmpty()) return null
        val gen = unlockGen
        val data = http.bytes("/files/" + UmbraUrl.segment(fileId)).dataOrNull ?: return null
        if (gen != unlockGen) return null
        val vk = vaultKeys[s.curVaultId] ?: return null
        val plain = withContext(Dispatchers.Default) { VaultCrypto.aesDecryptBytes(vk, data) } ?: return null
        if (gen != unlockGen) return null
        attPlain[fileId] = plain
        return plain
    }

    // MARK: - 记录的增改删

    /** 保存改动到内存快照并推送。已有的抬 revision、改 updatedAt；新的 createdAt = updatedAt = 现在、revision = 1。 */
    suspend fun saveItem(item: VItem) {
        val snap = snapshot ?: return
        if (!guardWritable()) return
        val d = snap.data[s.curVaultId] ?: return
        val now = System.currentTimeMillis().toDouble()
        val i = d.items.indexOfFirst { it.id == item.id }
        val items = d.items.toMutableList()
        if (i >= 0) items[i] = item.copy(updatedAt = now, revision = d.items[i].revision.coerceAtLeast(item.revision) + 1)
        else items += item.copy(createdAt = now, updatedAt = now, revision = 1.0)
        snapshot = snap.copy(data = snap.data + (s.curVaultId to d.copy(items = items)))
        loadCurrent()
        push()
    }

    /** 回收站保留期。跟电脑端、服务端同一个 30 天。 */
    const val TRASH_KEEP_MS = 30.0 * 24 * 3600 * 1000

    /** 一条记录是不是「在回收站里、还能恢复」：deleted 且**内容还在**。 */
    fun isTrashed(it: VItem): Boolean = it.deleted == true && it.hasContent

    /** 还剩几天。向上取整（删完当天显示 30），过期未清的回 0；离谱的值装不进整型就按最大值，不闪退。 */
    fun leftDays(deletedAtMs: Double, nowMs: Double = System.currentTimeMillis().toDouble()): Int {
        val left = deletedAtMs + TRASH_KEEP_MS - nowMs
        if (left <= 0) return 0
        return VNum.int(Math.ceil(left / 86_400_000)) ?: Int.MAX_VALUE
    }

    /** 删除 = **移进回收站**：只置标志、抬 revision，内容与附件一个都不动（三态与 PC trash.ts 一字不差）。 */
    suspend fun deleteItem(id: String) {
        val snap = snapshot ?: return
        if (!guardWritable()) return
        val d = snap.data[s.curVaultId] ?: return
        val i = d.items.indexOfFirst { it.id == id }
        if (i >= 0 && d.items[i].deleted != true) {
            val it = d.items[i]
            val items = d.items.toMutableList()
            items[i] = it.copy(deleted = true, updatedAt = System.currentTimeMillis().toDouble(), revision = it.revision + 1)
            snapshot = snap.copy(data = snap.data + (s.curVaultId to d.copy(items = items)))
        }
        loadCurrent()
        refreshTrashCount()
        push()
    }

    /** 回收站里的一条（**跨所有身份库**）。from 是类型名。 */
    data class TrashRow(
        val vaultId: String, val itemId: String, val title: String, val from: String, val deletedAtMs: Double, val leftDays: Int,
        /** 树形库的一组：[itemId] 是组（grp），[descendants] = 组里除组根外的节点数（「和它下面的 N 个节点一起」）。 */
        val tree: Boolean = false, val descendants: Int = 0,
    ) {
        val id: String get() = "$vaultId:$itemId"
    }

    /** 回收站列表，最近删的在前。只在解锁态有内容。 */
    fun trashRows(): List<TrashRow> {
        val snap = snapshot ?: return emptyList()
        val now = System.currentTimeMillis().toDouble()
        val out = mutableListOf<TrashRow>()
        for ((vid, d) in snap.data) {
            d.tree?.let { t ->
                for (g in VaultTrash3.groups(t, now)) out += TrashRow(vid, g.grp, g.title.ifEmpty { "（无标题）" }, Fmt3.kindName(g.rootKind), g.at, g.leftDays, true, g.descendants)
            }
            val typeName = d.types.associate { it.id to it.name }
            for (it in d.items) if (isTrashed(it)) {
                out += TrashRow(vid, it.id, it.title.ifEmpty { "（无标题）" }, typeName[it.typeId] ?: "记录", it.updatedAt, leftDays(it.updatedAt, now))
            }
        }
        return out.sortedByDescending { it.deletedAtMs }
    }

    /** 从回收站恢复。抬 revision 是关键：不抬的话下一次合并会按「revision 高者胜」把删除态又拉回来。 */
    suspend fun restoreTrash(vaultId: String, itemId: String) {
        val snap = snapshot ?: return
        val d = snap.data[vaultId] ?: return
        if (!guardWritable()) return
        d.tree?.let { t ->
            // 树形：itemId 是回收站的组（grp），整组一起回来（293 §八「恢复时整组一起回来」）。
            val (t2, n) = VaultTrash3.restore(t, itemId, now())
            if (n > 0) commitTree(vaultId, t2)
            return
        }
        val i = d.items.indexOfFirst { it.id == itemId }
        if (i < 0 || !isTrashed(d.items[i])) return
        val it = d.items[i]
        val items = d.items.toMutableList()
        items[i] = it.copy(deleted = false, updatedAt = System.currentTimeMillis().toDouble(), revision = it.revision + 1)
        snapshot = snap.copy(data = snap.data + (vaultId to d.copy(items = items)))
        loadCurrent()
        refreshTrashCount()
        push()
    }

    /** 彻底删除：擦干净内容，但**保留这一行**（墓碑还得跨端传播「这条没了」）；附件字节一并从快照里删。 */
    suspend fun purgeTrash(vaultId: String, itemId: String) {
        val snap = snapshot ?: return
        val d = snap.data[vaultId] ?: return
        if (!guardWritable()) return
        d.tree?.let { t ->
            // 手机上不删云端的附件字节（方案 §三之二：删字节只走 PC，并且要带 vaultFmt）；元数据写成 gone 跨端传过去。
            val r = VaultTrash3.purge(t, itemId, now())
            if (r.purged > 0) commitTree(vaultId, r.tree)
            return
        }
        val i = d.items.indexOfFirst { it.id == itemId }
        if (i < 0 || !isTrashed(d.items[i])) return
        snapshot = snap.copy(data = snap.data + (vaultId to purgeInPlace(d, i)))
        loadCurrent()
        refreshTrashCount()
        push()
    }

    /** 到期清理：超过保留期的自动彻底删除。**解锁时跑一次。** */
    suspend fun sweepExpiredTrash(): Int {
        val snap = snapshot ?: return 0
        val now = System.currentTimeMillis().toDouble()
        var n = 0
        val data = snap.data.toMutableMap()
        for ((vid, orig) in snap.data) {
            var d = orig
            var changed = false
            if (snap.fromFuture) break
            d.tree?.let { t0 ->
                var t = t0
                for (g in VaultTrash3.expired(t, now)) { t = VaultTrash3.purge(t, g, now).tree; n++; changed = true }
                if (changed) data[vid] = d.copy(tree = t)
                return@let
            }
            if (d.isTree) continue
            for (i in d.items.indices) {
                if (!isTrashed(d.items[i]) || now - d.items[i].updatedAt < TRASH_KEEP_MS) continue
                d = purgeInPlace(d, i); changed = true; n++
            }
            if (changed) data[vid] = d
        }
        if (n == 0) { refreshTrashCount(); return 0 }
        snapshot = snap.copy(data = data)
        loadCurrent()
        refreshTrashCount()
        push()
        return n
    }

    private fun purgeInPlace(d: VData, i: Int): VData {
        val it = d.items[i]
        val atts = d.attachments - it.attachments.map { a -> a.id }.toSet()
        val items = d.items.toMutableList()
        items[i] = it.purgedContent().copy(deleted = true, updatedAt = System.currentTimeMillis().toDouble(), revision = it.revision + 1)
        return d.copy(items = items, attachments = atts)
    }

    /** 重算回收站条数并只改缓存里 trashCount 那一格（blob / rev / pending 原样）。 */
    private fun refreshTrashCount() {
        val n = trashRows().size
        if (n == s.trashCount) return
        set { it.copy(trashCount = n) }
        readCache()?.let { writeCache(it.copy(trashCount = n)) }
    }

    // MARK: - 树形（fmt 3）的增改删
    //
    // 每一次改动只动它自己那几行（节点 / 条目），rev + 1、updatedAt = 现在；合并按 rev 逐行比（[VaultMerge3]）。

    private fun now(): Double = System.currentTimeMillis().toDouble()

    /** 只读时（格式比这一版新）不让改：吐司说一次（295 §四），调用方不进编辑态。 */
    fun guardWritable(): Boolean {
        if (!s.upgradeRequired) return true
        AppGraph.router.showToast(READ_ONLY_TOAST)
        return false
    }

    /** 295 §四 的吐司（尝试改动时）。 */
    val READ_ONLY_TOAST: String get() = "这台的${Brand.productName}版本太旧，改不了保险箱 · 先升级"

    /** 换掉当前快照里一个库的树，刷新界面、回收站数，推。 */
    private suspend fun commitTree(vid: String, t: Tree3) {
        val snap = snapshot ?: return
        val d = snap.data[vid] ?: return
        snapshot = snap.copy(data = snap.data + (vid to d.copy(tree = t)))
        loadCurrent()
        refreshTrashCount()
        push()
    }

    /** 当前库的树（没解锁 / 不是树形回 null）。 */
    private fun curTree(): Tree3? = snapshot?.data?.get(s.curVaultId)?.tree

    /** 保存一个节点：已有的 rev = max(旧, 传入) + 1；新的 createdAt = updatedAt = 现在、rev = 1。 */
    suspend fun saveNode(n: Node3): Boolean {
        val t = curTree() ?: return false
        if (!guardWritable()) return false
        val old = t.node(n.id)
        val at = now()
        val next = if (old != null) n.copy(rev = maxOf(old.rev, n.rev) + 1, updatedAt = at) else n.copy(rev = 1.0, createdAt = at, updatedAt = at)
        commitTree(s.curVaultId, t.withNode(next))
        return true
    }

    /**
     * 新建一个节点，挂在 [parent] 下（null = 根），排在兄弟的最后（[VaultOrd.after]）。[data] / [set] 先铺该种类的默认值再盖。
     * 新建的东西在编辑页「保存」时才落库（见 [VaultTreeUi.newId]）。回新节点（只读 / 没解锁回 null）。
     */
    suspend fun createNode(kind: String, parent: String?, title: String, data: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
                           set: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()): Node3? {
        val t = curTree() ?: return null
        if (!guardWritable()) return null
        val siblings = VaultTreeIndex.children(t.nodes)[parent] ?: emptyList()
        val at = now()
        val n = Node3(VaultCrypto.rid("n"), kind, title, parent, VaultOrd.after(siblings),
            VaultNodeDefaults.data(kind) + data, VaultNodeDefaults.set(kind) + set, rev = 1.0, createdAt = at, updatedAt = at)
        commitTree(s.curVaultId, t.withNode(n))
        return n
    }

    /** 多账号里存一条（新增或修改）：条目 rev + 1，节点 rev 也抬（条目合并挂在节点上）。 */
    suspend fun saveEntry(nodeId: String, e: Entry3): Boolean {
        val t = curTree() ?: return false
        if (!guardWritable()) return false
        val n = t.node(nodeId)?.takeIf { it.live && it.kind == "multi" } ?: return false
        val at = now()
        val list = n.entries.toMutableList()
        val i = list.indexOfFirst { it.id == e.id }
        if (i >= 0) list[i] = e.copy(rev = maxOf(list[i].rev, e.rev) + 1, updatedAt = at) else list += e.copy(rev = 1.0, updatedAt = at)
        commitTree(s.curVaultId, t.withNode(n.withEntries(list).copy(rev = n.rev + 1, updatedAt = at)))
        return true
    }

    /** 删一个节点（连同全部后代）进回收站。 */
    suspend fun trashNode(id: String): Boolean {
        val t = curTree() ?: return false
        if (!guardWritable()) return false
        val (t2, k) = VaultTrash3.trashNode(t, id, now())
        if (k == 0) return false
        commitTree(s.curVaultId, t2)
        return true
    }

    /** 删多账号里的一条进回收站。 */
    suspend fun trashEntry(nodeId: String, entryId: String): Boolean {
        val t = curTree() ?: return false
        if (!guardWritable()) return false
        val t2 = VaultTrash3.trashEntry(t, nodeId, entryId, now()) ?: return false
        commitTree(s.curVaultId, t2)
        return true
    }

    suspend fun toggleFav(id: String) {
        val it = s.items.firstOrNull { i -> i.id == id } ?: return
        saveItem(it.copy(favorite = !(it.favorite ?: false)))
    }

    suspend fun moveItem(id: String, typeId: String) {
        val it = s.items.firstOrNull { i -> i.id == id } ?: return
        saveItem(it.copy(typeId = typeId))
    }

    // MARK: - 同步：先拉合并，再推

    suspend fun syncNow() {
        set { it.copy(loading = true) }
        try {
            pullMerge()
            push()
        } finally {
            set { it.copy(loading = false) }
        }
    }

    /**
     * 把云端那份合并进本机。每一条早退都回 Failed(<哪一种>)（umbra-ios#17）。
     * ⚠️ 这里**只判有没有**，不在 await 之前取 snapshot / auk 留到之后用（审查台账 vault-pullmerge-stale-snapshot）。
     */
    private suspend fun pullMerge(conflictRev: Int? = null, conflictBlob: String? = null, putBaseRev: Int? = null): VaultMergeResult {
        if (auk == null || snapshot == null) return VaultMergeResult.Failed(VaultMergeFailure.Unreadable)
        if (migrationBlockedGen == unlockGen) return VaultMergeResult.Failed(VaultMergeFailure.Unreadable)
        val gen = unlockGen
        val baseRev = putBaseRev ?: syncRev
        var rev = conflictRev ?: 0
        var blob = conflictBlob
        if (blob == null) {
            val body = when (val r = http.raw("GET", "/vault/sync", mapOf("have_rev" to baseRev))) {
                is HttpResult.Ok -> r.body
                is HttpResult.Fail -> r.body
                else -> return VaultMergeResult.Failed(VaultMergeFailure.Unreachable)
            }
            val obj = parseSync(body) ?: return VaultMergeResult.Failed(VaultMergeFailure.Unreachable)
            rev = (obj["rev"] as? JsonPrimitive)?.intOrNull ?: 0
            blob = (obj["blob"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            noteFmt(obj)
            // 这一拉知道了「云端已是 3 / 开关翻了」：先把本机迁了（syncRev 跟着归零），再**整份**拉一次 —— 不能拿着旧 syncRev 接着并：
            // 回包可能是「没有新的」，本机迁完的旧数据就会盖掉云端的树（方案〇第 4 条）。
            if (gen == unlockGen && migrateIfDue()) return pullMerge()
        }
        when (VaultSyncRules.mergeGate(rev, baseRev, conflictRev != null, syncRev, gen == unlockGen)) {
            VaultSyncRules.MergeGate.Proceed -> Unit
            VaultSyncRules.MergeGate.NothingNewer -> return VaultMergeResult.Failed(VaultMergeFailure.NothingNewer)
            VaultSyncRules.MergeGate.Superseded -> return VaultMergeResult.Failed(VaultMergeFailure.Superseded)
        }
        val rd = blob?.let { VRecord.parse(it) } ?: return VaultMergeResult.Failed(VaultMergeFailure.Unreadable)
        if (rd.verifier != record?.verifier) return VaultMergeResult.Failed(VaultMergeFailure.KeyMismatch)
        val a = auk ?: return VaultMergeResult.Failed(VaultMergeFailure.Superseded)
        val remote = withContext(Dispatchers.Default) { VaultCrypto.aesDecrypt(a, rd.enc)?.let { VSnapshot.parse(it) } }
            ?: return VaultMergeResult.Failed(VaultMergeFailure.Unreadable)
        // 解密在后台跑过一趟：回来再问一次代次，并在**此刻**取快照合并（含着往返期间所有保存的那份）。
        if (gen != unlockGen) return VaultMergeResult.Failed(VaultMergeFailure.Superseded)
        if (snapshot == null) return VaultMergeResult.Failed(VaultMergeFailure.Superseded)
        // 冲突带回来的那份已经是树形：本机还是 fmt 2 的话先走一遍迁移（备份 + 自检）。自检没过就不并。
        if (remote.data.values.any { it.isTree }) cloudFmt = maxOf(cloudFmt ?: 2, 3)
        migrateIfDue()
        if (migrationBlockedGen == unlockGen) return VaultMergeResult.Failed(VaultMergeFailure.Unreadable)
        val cur = snapshot ?: return VaultMergeResult.Failed(VaultMergeFailure.Superseded)
        val merged = VaultSyncRules.merge(cur, remote)
        record = rd; syncRev = rev
        applySnapshot(merged)
        return VaultMergeResult.Merged
    }

    /** 发一次 PUT /vault/sync。不看状态码、只看回包 JSON（同 iOS：冲突可能以 4xx + JSON 回来）。 */
    private suspend fun putSync(blob: String, baseRev: Int, force: Boolean, fmt: Int?): VaultPutReply {
        val body = buildJsonObject {
            put("blob", blob); put("baseRev", baseRev); put("deviceId", AppGraph.prefs.clientId); put("force", force)
            // 明文的格式版本（方案 §三第 1 条）：服务端只升不降；库里已是 3 而这里不带 / 偏低 → 426。
            if (fmt != null) put("fmt", fmt)
        }.toString().toRequestBody(UmbraHttp.JSON)
        val text = when (val r = http.raw("PUT", "/vault/sync", body = body)) {
            is HttpResult.Ok -> r.body
            is HttpResult.Fail -> {
                if (r.code == UPGRADE_REQUIRED) return VaultPutReply.UpgradeRequired
                r.body
            }
            else -> return VaultPutReply.Unreachable
        }
        val obj = parseSync(text) ?: return VaultPutReply.Unreachable
        if ((obj["code"] as? JsonPrimitive)?.content == UPGRADE_REQUIRED) return VaultPutReply.UpgradeRequired
        if ((obj["ok"] as? JsonPrimitive)?.booleanOrNull == true) return VaultPutReply.Ok((obj["rev"] as? JsonPrimitive)?.intOrNull ?: baseRev)
        if ((obj["conflict"] as? JsonPrimitive)?.booleanOrNull == true) {
            return VaultPutReply.Conflict((obj["rev"] as? JsonPrimitive)?.intOrNull ?: 0, (obj["blob"] as? JsonPrimitive)?.takeIf { it.isString }?.content)
        }
        return VaultPutReply.Rejected
    }

    /**
     * 把**当前**这份快照编成要上传的 blob，顺手落本机缓存（pending:true）。每一趟重试都重做：剔孤儿附件、去内联字节、
     * 重新编码；钥匙和 record 不是一对就一个字节都不编、不推、不落缓存。钥匙在这里当场取。
     */
    private fun encodeForPush(): String? {
        lastEncodeFailure = null
        val a = auk ?: return null
        var snap = snapshot ?: return null
        val rec = record ?: return null
        if (!VaultSyncRules.mayEncode(rec.verifier, unlockedVerifier)) { lastEncodeFailure = VaultEncodeFailure.KeyMismatch; return null }
        val (pruned, n1) = VaultSyncRules.pruneOrphanAttachments(snap)
        if (n1 > 0) { snap = pruned; snapshot = snap }
        val (stripped, n2) = VaultSyncRules.stripInlineAttachments(snap)
        if (n2 > 0) { snap = stripped; snapshot = snap }
        val enc = runCatching { VaultCrypto.aesEncrypt(a, snap.encode().toByteArray(Charsets.UTF_8)) }.getOrNull()
            ?: run { lastEncodeFailure = VaultEncodeFailure.CannotEncode; return null }
        val newRec = VRecord(1, rec.kdf ?: "pbkdf2", rec.salt, rec.verifier, enc)
        val recordStr = newRec.encode()
        // **先落本机缓存再推**：顺序反过来的话，离线时改的东西一关 App 就没了。
        writeCache(recordStr, syncRev, pending = true)
        cacheEncodeSeq++
        // record 跟着本机最新这份走（和缓存同一份），否则「立即上锁再解锁」解开的是旧快照。
        record = newRec
        return recordStr
    }

    private suspend fun push() {
        if (auk == null || snapshot == null || record == null) return
        // 更新的版本写的格式：一个字节都不往回写（会把 fmt 4 降成 3）。迁移自检没过的这一轮：不同步。
        if (snapshot?.fromFuture == true || migrationBlockedGen == unlockGen) return
        val gen = unlockGen
        var mySeq = -1
        var encodeFailure: VaultEncodeFailure? = null
        var sentBase = syncRev
        val outcome = VaultSyncRules.run(
            sameUnlock = { unlockGen == gen },
            encode = {
                val b = encodeForPush()
                if (b != null) mySeq = cacheEncodeSeq
                encodeFailure = lastEncodeFailure
                b
            },
            send = { blob, force -> sentBase = syncRev; putSync(blob, sentBase, force, snapshot?.pushFmt) },
            merge = { rev, blob -> pullMerge(rev, blob, sentBase) },
        )
        when (outcome) {
            is VaultPushOutcome.Pushed -> {
                syncRev = outcome.rev
                set { it.copy(offline = false) }
                // 推的就是最后落盘的那份才写干净；不是的话另一趟推送还拿着更新的那份在路上。
                if (VaultSyncRules.pushedIsLatest(mySeq, cacheEncodeSeq)) {
                    writeCache(outcome.blob, syncRev, pending = false)
                    set { it.copy(pendingPush = false) }
                }
            }
            VaultPushOutcome.Superseded -> set { it.copy(pendingPush = true) }
            VaultPushOutcome.Offline -> set { it.copy(offline = true, pendingPush = true) }
            is VaultPushOutcome.MergeFailed ->
                if (outcome.why == VaultMergeFailure.Unreachable) set { it.copy(offline = true, pendingPush = true) }
                else set { it.copy(pendingPush = true) }
            VaultPushOutcome.EncodeFailed -> if (encodeFailure != null) set { it.copy(pendingPush = true) }
            VaultPushOutcome.Rejected, VaultPushOutcome.GaveUp -> set { it.copy(pendingPush = true) }
            VaultPushOutcome.UpgradeRequired -> { putSaidUpgrade = true; set { it.copy(pendingPush = true, upgradeRequired = true) } }
        }
        when (VaultSyncRules.problemAfter(outcome, s.needsReunlock, encodeFailure)) {
            VaultSyncRules.ProblemUpdate.Clear -> set { it.copy(syncProblem = "", needsReunlock = false) }
            // 要升级那一档不写 syncProblem：顶上的条幅（upgradeRequired）已经说了，再挂一条「没接受」黄条是两句话说一件事。
            VaultSyncRules.ProblemUpdate.UpgradeRequired -> set { it.copy(syncProblem = "") }
            VaultSyncRules.ProblemUpdate.Keep -> Unit
            VaultSyncRules.ProblemUpdate.Reunlock -> set { it.copy(needsReunlock = true, syncProblem = REUNLOCK_PROBLEM) }
            VaultSyncRules.ProblemUpdate.CannotMerge -> set { it.copy(syncProblem = "云端有新的改动，这次没能合并进来，本次没有同步上去（本地改动还在）。") }
            VaultSyncRules.ProblemUpdate.Rejected -> set { it.copy(syncProblem = "服务端没有接受这次同步（本地改动还在）。") }
            VaultSyncRules.ProblemUpdate.GaveUp -> set { it.copy(syncProblem = "连着几次都和云端那份对不上，这次没推上去，下次同步会再推（本地改动还在）。") }
            VaultSyncRules.ProblemUpdate.CannotEncode -> set {
                it.copy(syncProblem = "这次改动没能加密打包，既没存进本机缓存、也没同步上去。改动还在屏上，先别退出 App，点「重试」再试一次。")
            }
        }
    }

    /** 服务端版本闸的错误码（`X-Umbra-Error`，HTTP 426）。 */
    private const val UPGRADE_REQUIRED = "upgrade_required"


    // MARK: - fmt 2 → 3 迁移（方案 §二第 2–4、6 条）

    /**
     * 到了迁移的时候就迁本机的 fmt 2 库。回 true = 迁了（快照已换成树形、缓存已落、syncRev 已归零）。
     *
     * 顺序（方案 §二第 3 条）：① 备份盘上那份缓存（原样拷贝密文、fsync）→ ② 内存里迁移 → ③ 自检（各项计数相等）→
     * ④ 落缓存（pending）→ ⑤ syncRev 归零（强制下一次整份拉取，方案〇第 4 条）。任何一步不过：一个字节都不写，
     * 这一轮不同步，挂「升级没完成」那句，下次解锁再试。
     */
    private fun migrateIfDue(): Boolean {
        val snap = snapshot ?: return false
        if (snap.data.values.all { it.isTree } || snap.fromFuture) return false
        if (migrationBlockedGen == unlockGen) return false
        if (!VaultSyncRules.shouldMigrate(AppGraph.auth.signedIn, cloudFmt, fmtTarget)) return false
        val next = runCatching {
            val data = snap.data.mapValues { (vid, d) ->
                if (d.isTree) return@mapValues d
                val raw = d.items.map { it.toJson() }
                val t = VaultMigrate3.migrate(raw)
                val bad = VaultMigrate3.selfCheck(VaultMigrate3.countsBefore(raw), VaultMigrate3.countsAfter(t))
                check(bad.isEmpty()) { "身份库 $vid 自检没过：$bad" }
                VData(emptyList(), emptyList(), emptyMap(), 3, t, d.extra)
            }
            snap.copy(v = maxOf(snap.v, 3), data = data)
        }
        val migrated = next.getOrNull()
        if (migrated == null || !backupFmt2Cache()) {
            android.util.Log.w("UmbraVault", "fmt 3 迁移没完成：${next.exceptionOrNull()?.message ?: "备份没写成"}")
            migrationBlockedGen = unlockGen
            set { it.copy(migrateFailed = true) }
            return false
        }
        snapshot = migrated
        syncRev = 0
        applySnapshot(migrated)
        if (encodeForPush() == null) {
            // 落不了盘：内存里已经是树了，但盘上还是 fmt 2 的那份 —— 照「没完成」处理，这一轮不推。
            migrationBlockedGen = unlockGen
            set { it.copy(migrateFailed = true) }
            return false
        }
        set { it.copy(pendingPush = true, migrateFailed = false) }
        return true
    }

    /** 条幅上的［再试一次］：当场再走一遍迁移（备份 → 自检 → 落盘），过了就整份拉一次再推。 */
    suspend fun retryMigration() {
        if (auk == null) return
        migrationBlockedGen = -1
        set { it.copy(migrateFailed = false) }
        if (migrateIfDue()) syncNow()
    }

    /**
     * 迁移前把盘上那份缓存**原样**拷一份（本来就是密文，不用再加密），写完 fsync。没有缓存文件（从没落过盘）也算成功。
     * 只留最近一份（[VaultSyncRules.Fmt2Backup]）。
     */
    private fun backupFmt2Cache(): Boolean {
        val src = cacheFile
        if (!src.exists()) return true
        return runCatching {
            val dst = File(cacheDir, VaultSyncRules.Fmt2Backup.name(System.currentTimeMillis()))
            java.io.FileOutputStream(dst).use { out ->
                out.write(src.readBytes())
                out.fd.sync()
            }
            pruneFmt2Backups(keepNewest = dst.name)
            true
        }.getOrDefault(false)
    }

    /** 清掉多余 / 过期（90 天）的 fmt 2 备份。解锁时跑一次。 */
    private fun pruneFmt2Backups(keepNewest: String? = null) {
        val names = cacheDir.list()?.toList() ?: return
        for (n in VaultSyncRules.Fmt2Backup.prune(names, System.currentTimeMillis())) if (n != keepNewest) File(cacheDir, n).delete()
    }

    /** 「云端换过主密码」那一档的说法。两处会说它，只写一份。 */
    const val REUNLOCK_PROBLEM = "云端的主密码 / Secret Key 在别的设备上换过了，这台手机拿着的还是旧的，这次没有推上去。锁定后用新主密码重新解锁。"

    // MARK: - 通用回收站（服务端那一区：灵感 / 任务 / 提醒 / 记账 / 常用语 / 知识库 / 记忆）
    //
    // 界面一页、存储两套：通用区走服务端 /trash 三个接口；保险箱区端到端加密、走上面那套，服务端连它有几条都不知道。

    suspend fun fetchTrash(): TrashList? = http.get<TrashList>("/trash")

    /** 回服务端说**真正找回了几条**（`{"restored": n}`）；请求失败 / 回包解不开回 null（不猜成「找回了」）。 */
    suspend fun restoreGenericTrash(entries: List<JsonObject>): Int? {
        if (entries.isEmpty()) return null
        val body = buildJsonObject { put("entries", kotlinx.serialization.json.JsonArray(entries)) }
        val r = http.call("POST", "/trash/restore", body)
        return (r as? com.geek.umbra.net.ApiResult.Ok)?.value?.get("restored")?.let { (it as? JsonPrimitive)?.intOrNull }
    }

    suspend fun purgeGenericTrash(entries: List<JsonObject>): Boolean {
        if (entries.isEmpty()) return false
        val body = buildJsonObject { put("entries", kotlinx.serialization.json.JsonArray(entries)) }
        return http.call("POST", "/trash/purge", body) is com.geek.umbra.net.ApiResult.Ok
    }

    /** 清空回收站。**只清通用区** —— 保险箱那一区服务端动不了。 */
    suspend fun purgeAllGenericTrash(): Boolean =
        http.call("POST", "/trash/purge", buildJsonObject { put("all", true) }) is com.geek.umbra.net.ApiResult.Ok
}
