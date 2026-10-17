// 记账本机账本（单机化 ②-a）—— 照抄 iOS Features/Money/MoneyLocal.swift。
//   · 本机是真相：生活账本的流水 / 分类 / 规则先落 filesDir/money/personal/，页面读它；
//   · 改动经一处：盖 updated_at_ms、标 dirty、落盘、3 秒防抖推；删除 = 移除 + 墓碑；
//   · sync()：换账号判定 → ① 分类全量往返 → ①′ 规则 → ② 推墓碑 → ③ 逐条推 dirty → ③′ 本机附件补传
//     → ④ 拉增量 → 合并 → **先存条目再前移水位线**；
//   · 同步是联网那一截，向会话守卫登记（MoneyModule）：登出只停推，本机账一笔不删。
// 只管生活账本；经营账本仍走服务端（MoneyStore 按账本分流）。判据在 MoneyLocalRules（纯函数，有用例）。
package com.geek.umbra.feature.money

import android.util.Log
import com.geek.umbra.app.AppGraph
import com.geek.umbra.net.AccountSwitchChoice
import com.geek.umbra.net.AccountSwitchPlan
import com.geek.umbra.net.AccountSwitchRules
import com.geek.umbra.net.BackgroundWork
import com.geek.umbra.net.Uploads
import com.geek.umbra.net.UmbraJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.UUID

object MoneyLocal {
    private const val TAG = "MoneyLocal"

    private val _entries = MutableStateFlow<List<MoneyEntryDTO>>(emptyList())
    val entries: StateFlow<List<MoneyEntryDTO>> = _entries
    private val _cats = MutableStateFlow<List<MoneyCatDTO>>(emptyList())
    val cats: StateFlow<List<MoneyCatDTO>> = _cats
    private val _rules = MutableStateFlow<List<MoneyRecurDTO>>(emptyList())
    val rules: StateFlow<List<MoneyRecurDTO>> = _rules
    private var tombs: List<MoneyTomb> = emptyList()
    private var ruleTombs: List<MoneyTomb> = emptyList()

    /** 同步状态（形状同提醒）。 */
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing
    private val _lastSyncError = MutableStateFlow("")
    val lastSyncError: StateFlow<String> = _lastSyncError
    private val _lastSyncAt = MutableStateFlow(0L)
    val lastSyncAt: StateFlow<Long> = _lastSyncAt

    @Serializable
    private data class Meta(
        val lastSyncMs: Long = 0,
        val lastSyncServer: String? = null,
        val lastSyncUser: String? = null,
        val lastRuleSyncMs: Long? = null,
    )

    private var meta = Meta()
    private var loaded = false
    private var pushJob: Job? = null
    private var resyncRequested = false
    /** 守卫说「登着」才推；未登录改动留 dirty。 */
    private var online = false

    /**
     * 落盘走一条串行的 IO 线：状态在主线程上改（和 iOS @MainActor 同形，不用锁），
     * 写文件挪出主线程又**保持先后顺序** —— 并发写同一个文件，后到的旧快照会盖掉先到的新快照。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(1)

    private fun now() = System.currentTimeMillis()

    // MARK: 落盘

    private val dir: File get() = File(AppGraph.context.filesDir, "money/personal")
    private fun file(name: String) = File(dir, name)

    /** 本机附件目录 filesDir/money/files/。 */
    val filesDir: File get() = File(AppGraph.context.filesDir, "money/files")

    /** 本机 id → 文件。只认 `local:<uuid>[.ext]`（别让一个拼出来的 id 读到别的路径）。 */
    fun fileFor(id: String): File? = MoneyLocalRules.localFileName(id)?.let { File(filesDir, it) }

    /** 读坏不当空：原文改名 `.corrupt-<秒>` 留底，让人能救回来（同提醒缓存那条）。 */
    private fun <T> read(name: String, ser: KSerializer<T>): T? {
        val f = file(name)
        if (!f.exists()) return null
        val text = runCatching { f.readText() }.getOrNull() ?: return null
        return runCatching { UmbraJson.decodeFromString(ser, text) }.getOrElse {
            val backup = file("$name.corrupt-${now() / 1000}")
            runCatching { f.copyTo(backup, overwrite = true) }
            Log.w(TAG, "$name 读不出来，原文已留底到 ${backup.name}")
            null
        }
    }

    /** 原子写：先写临时文件再改名 —— 写到一半被杀，留下的是旧的完整文件，不是半截 JSON。 */
    private fun <T> write(name: String, ser: KSerializer<T>, v: T) {
        val text = UmbraJson.encodeToString(ser, v)
        AppGraph.scope.launch(io) {
            runCatching {
                dir.mkdirs()
                val tmp = file("$name.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(file(name))) { file(name).delete(); tmp.renameTo(file(name)) }
            }.onFailure { Log.w(TAG, "$name 写不进去：${it.message}") }
        }
    }

    private val entriesSer = ListSerializer(MoneyEntryDTO.serializer())
    private val catsSer = ListSerializer(MoneyCatDTO.serializer())
    private val rulesSer = ListSerializer(MoneyRecurDTO.serializer())
    private val tombsSer = ListSerializer(MoneyTomb.serializer())

    /** 第一次用到时从盘上读。首次没有 cats.json 就落内置分类那一份。 */
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        _entries.value = read("entries.json", entriesSer) ?: emptyList()
        tombs = read("tombs.json", tombsSer) ?: emptyList()
        val c = read("cats.json", catsSer)
        if (c != null) _cats.value = c else { _cats.value = MoneyLocalRules.seedCats(); saveCats() }
        _rules.value = read("rules.json", rulesSer) ?: emptyList()
        ruleTombs = read("ruleTombs.json", tombsSer) ?: emptyList()
        meta = read("meta.json", Meta.serializer()) ?: Meta()
        scanDue()
    }

    private fun saveEntries() = write("entries.json", entriesSer, _entries.value)
    private fun saveTombs() = write("tombs.json", tombsSer, tombs)
    private fun saveCats() = write("cats.json", catsSer, _cats.value)
    private fun saveRules() = write("rules.json", rulesSer, _rules.value)
    private fun saveRuleTombs() = write("ruleTombs.json", tombsSer, ruleTombs)
    private fun saveMeta() = write("meta.json", Meta.serializer(), meta)

    // MARK: 读

    fun list(ym: String? = null, direction: String? = null, cat: String? = null, batchId: String? = null, limit: Int = 1000): MoneyEntriesDTO {
        ensureLoaded()
        return MoneyLocalRules.list(_entries.value, MoneyLocalRules.Filter(ym = ym, direction = direction, cat = cat, batchId = batchId, limit = limit))
    }

    fun stats(ym: String, trendMonths: Int): MoneyStatsDTO { ensureLoaded(); return MoneyLocalRules.stats(_entries.value, ym, trendMonths) }

    fun liveCats(includeDisabled: Boolean): List<MoneyCatDTO> { ensureLoaded(); return MoneyLocalRules.live(_cats.value, includeDisabled) }

    fun slotPool(): MoneySlotPoolDTO {
        ensureLoaded()
        fun free(d: String): List<Int> {
            val t = MoneyLocalRules.takenSlots(_cats.value, d)
            return MoneyLocalRules.colorSlots.filter { it !in t }
        }
        return MoneySlotPoolDTO(MoneyLocalRules.colorSlots, MoneyLocalRules.NEUTRAL_SLOT, mapOf("expense" to free("expense"), "income" to free("income")))
    }

    fun usage(): Map<String, MoneyCatUsageDTO> {
        ensureLoaded()
        return _cats.value.filter { !MoneyLocalRules.isMerged(it) }
            .associate { it.slug to MoneyLocalRules.usage(it.slug, _cats.value, _entries.value) }
    }

    fun subs(cat: String): List<MoneySubUsedDTO> {
        ensureLoaded()
        val c = _cats.value.firstOrNull { it.slug == cat && !MoneyLocalRules.isMerged(it) } ?: return emptyList()
        val used = HashMap<String, Int>()
        for (e in _entries.value) if (e.cat == cat && e.sub.isNotEmpty()) used[e.sub] = (used[e.sub] ?: 0) + 1
        return c.subList.map { MoneySubUsedDTO(it, used[it] ?: 0) }
    }

    fun entry(id: String): MoneyEntryDTO? { ensureLoaded(); return _entries.value.firstOrNull { it.id == id } }

    // MARK: 周期规则（②-b：本机存、本机到点补记）

    /** 到期补记（= 服务端 scan_due）：页面取规则 / 重载、同步前都跑一遍 —— 没有专门的分钟表，这几处够密。回补出几笔。 */
    fun scanDue(): Int {
        val rules = _rules.value
        val r = MoneyLocalRules.runDue(rules, _entries.value.map { it.id }.toSet(), now())
        val advanced = r.rules.zip(rules).any { (a, b) -> a.next_at_ms != b.next_at_ms }
        if (r.entries.isEmpty() && !advanced) return 0
        _rules.value = r.rules
        saveRules()
        if (r.entries.isNotEmpty()) { _entries.value = _entries.value + r.entries; saveEntries(); schedulePush() }
        return r.entries.size
    }

    fun listRules(): List<MoneyRecurDTO> { ensureLoaded(); return MoneyLocalRules.withStats(_rules.value, _entries.value) }

    /** 建 / 改一条规则：改规则只影响以后的，下一次重算到 ≥ 现在。校验不过回 null。 */
    fun saveRule(r: MoneyRecurDTO): MoneyRecurDTO? {
        ensureLoaded()
        val v0 = runCatching { MoneyLocalRules.validateRule(r) }.getOrNull() ?: return null
        val now = now()
        var v = v0.copy(paused = _rules.value.firstOrNull { it.id == v0.id }?.paused ?: false, updated_at_ms = now, dirty = true)
        val (nd, nms) = MoneyLocalRules.nextFrom(v, now)
        v = v.copy(next_date = nd, next_at_ms = nms)
        val list = _rules.value.toMutableList()
        val i = list.indexOfFirst { it.id == v.id }
        if (i >= 0) list[i] = v else list.add(v)
        _rules.value = list
        saveRules(); schedulePush()
        return v
    }

    /** 停 / 重新开始；重新开始**不补停用期间的账**，下一次重算到 ≥ 现在。 */
    fun pauseRule(id: String, paused: Boolean): Boolean {
        ensureLoaded()
        val list = _rules.value.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return false
        val now = now()
        var r = list[i].copy(paused = paused)
        if (!paused) { val (nd, nms) = MoneyLocalRules.nextFrom(r, now); r = r.copy(next_date = nd, next_at_ms = nms) }
        list[i] = r.copy(updated_at_ms = now, dirty = true)
        _rules.value = list
        saveRules(); schedulePush()
        return true
    }

    /** 删规则（不删已生成的流水）。 */
    fun deleteRule(id: String): Boolean {
        ensureLoaded()
        val before = _rules.value.size
        _rules.value = _rules.value.filter { it.id != id }
        if (_rules.value.size == before) return false
        ruleTombs = ruleTombs.filter { it.id != id } + MoneyTomb(id, now())
        saveRules(); saveRuleTombs(); schedulePush()
        return true
    }

    // MARK: 附件本机暂存（②-b）

    /** 未登录时挑的图先落本机，回 `local:<uuid>.<ext>`。写不进回 null。 */
    suspend fun stageFile(data: ByteArray, name: String): String? = withContext(io) {
        val id = MoneyLocalRules.newLocalFileId(UUID.randomUUID().toString(), name)
        val f = fileFor(id) ?: return@withContext null
        runCatching { filesDir.mkdirs(); f.writeBytes(data); id }.getOrNull()
    }

    /** 把一张本机暂存的图挂到一笔账上（最多 4 张，同服务端口径）。满了回 false。 */
    fun attachLocal(entryId: String, fileId: String, label: String): Boolean {
        ensureLoaded()
        val list = _entries.value.toMutableList()
        val i = list.indexOfFirst { it.id == entryId }
        if (i < 0 || !MoneyLocalRules.isLocalFileId(fileId)) return false
        val atts = list[i].attList.toMutableList()
        if (atts.count { it.kind != "invoice" } >= com.geek.umbra.net.AttLimit.MAX) return false
        if (atts.none { it.file_id == fileId }) atts.add(MoneyAttDTO(fileId, label, false, ""))
        list[i] = list[i].copy(atts = atts)
        _entries.value = list
        saveEntries(); schedulePush()
        return true
    }

    fun detachLocal(entryId: String, fileId: String): Boolean {
        ensureLoaded()
        val list = _entries.value.toMutableList()
        val i = list.indexOfFirst { it.id == entryId }
        if (i < 0) return false
        list[i] = list[i].copy(atts = list[i].attList.filter { it.file_id != fileId })
        _entries.value = list
        saveEntries()
        fileFor(fileId)?.let { f -> AppGraph.scope.launch(io) { runCatching { f.delete() } } }
        return true
    }

    /** 同步里那一截：本机暂存的附件逐张传上去、挂上、换成服务端 id。这笔本身还没推上去（dirty）的跳过，下一轮再来。 */
    private suspend fun pushLocalAtts() {
        for (e in _entries.value.filter { it.dirty != true && it.attList.any { a -> MoneyLocalRules.isLocalFileId(a.file_id) } }) {
            for (a in e.attList.filter { MoneyLocalRules.isLocalFileId(it.file_id) }) {
                val f = fileFor(a.file_id)
                val data = f?.let { withContext(io) { runCatching { it.readBytes() }.getOrNull() } }
                if (f == null || data == null) { detachLocal(e.id, a.file_id); continue }
                val (up, _) = Uploads.upload(data, f.name)
                if (up == null) continue
                val attached = MoneyApi.addAtt(e.id, up.file_id, a.label, ledgerOverride = com.geek.umbra.net.Ledger.Personal)
                val list = _entries.value.toMutableList()
                val i = list.indexOfFirst { it.id == e.id }
                if (i < 0) continue
                val atts = list[i].attList.toMutableList()
                val j = atts.indexOfFirst { it.file_id == a.file_id }
                if (attached && j >= 0) atts[j] = MoneyAttDTO(up.file_id, a.label, false, "")
                else atts.removeAll { it.file_id == a.file_id } // 服务端收不下（满了）：本机这张去掉
                list[i] = list[i].copy(atts = atts)
                _entries.value = list
                saveEntries()
                withContext(io) { runCatching { f.delete() } }
            }
        }
    }

    // MARK: 写

    /** 记一笔 / 改一笔：校验 → 盖戳 → 标 dirty → 落盘 → 防抖推。校验不过抛 InvalidEntry。 */
    fun save(dto: MoneyEntryDTO): MoneyEntryDTO {
        ensureLoaded()
        var v = MoneyLocalRules.validate(dto)
        if (v.atts == null) v = v.copy(atts = _entries.value.firstOrNull { it.id == v.id }?.atts)
        v = v.copy(updated_at_ms = now(), dirty = true)
        val list = _entries.value.toMutableList()
        val i = list.indexOfFirst { it.id == v.id }
        if (i >= 0) list[i] = v else list.add(v)
        _entries.value = list
        saveEntries(); schedulePush()
        return v
    }

    /** 服务端把附件挂上 / 删掉之后本机这笔跟着改（不盖戳、不标 dirty）。 */
    fun setAtts(id: String, atts: List<MoneyAttDTO>?) {
        val list = _entries.value.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        list[i] = list[i].copy(atts = atts)
        _entries.value = list
        saveEntries()
    }

    fun remove(ids: List<String>): Int {
        ensureLoaded()
        val set = ids.toSet()
        val before = _entries.value.size
        _entries.value = _entries.value.filter { it.id !in set }
        val n = before - _entries.value.size
        if (n <= 0) return 0
        val now = now()
        tombs = tombs.filter { it.id !in set } + set.map { MoneyTomb(it, now) }
        saveEntries(); saveTombs(); schedulePush()
        return n
    }

    /** 挪到经营账本之后本机这笔去掉（归那边管了，不留墓碑：服务端那一行还活着）。 */
    fun forget(id: String) {
        val before = _entries.value.size
        _entries.value = _entries.value.filter { it.id != id }
        if (_entries.value.size != before) saveEntries()
    }

    fun createCat(name: String, direction: String, icon: String = ""): MoneyCatDTO? {
        ensureLoaded()
        val n = name.trim()
        val cats = _cats.value
        if (n.isEmpty() || direction !in MoneyLocalRules.directions || cats.any { !MoneyLocalRules.isMerged(it) && it.name == n }) return null
        val now = now()
        val seq = (cats.filter { it.direction == direction }.maxOfOrNull { it.seq } ?: -1) + 1
        val row = MoneyCatDTO(
            slug = MoneyLocalRules.newSlug(now, (0 until 100).random()), name = n, direction = direction,
            slot = MoneyLocalRules.freeSlot(cats, direction), seq = seq, enabled = true, locked = false,
            icon = icon, subs = emptyList(), merged_into = "", updated_at_ms = now, subs_updated_at_ms = now, dirty = true,
        )
        _cats.value = cats + row
        commitCats()
        return row
    }

    /** 改分类。换槽时**零和**：占着那个号的同方向分类被踢到中性灰（响应里带 slot_taken_by）。iOS: MoneyLocal.swift:330 */
    fun updateCat(slug: String, name: String?, enabled: Boolean?, icon: String?, slot: Int?): MoneyCatDTO? {
        ensureLoaded()
        val cats = _cats.value.toMutableList()
        val i = cats.indexOfFirst { it.slug == slug && !MoneyLocalRules.isMerged(it) }
        if (i < 0) return null
        val cur = cats[i]
        val now = now()
        var next = cur.copy(updated_at_ms = now, dirty = true)
        if (name != null && name.trim().isNotEmpty()) next = next.copy(name = name.trim())
        if (icon != null) next = next.copy(icon = icon.take(32))
        if (enabled != null) next = next.copy(enabled = enabled)
        if (slot != null) {
            val v = slot.coerceIn(0, 10)
            next = next.copy(slot = if (v == 8) MoneyLocalRules.NEUTRAL_SLOT else v)
            if (next.slot in MoneyLocalRules.colorSlots) {
                val j = cats.indexOfFirst { it.direction == cur.direction && it.slot == next.slot && it.enabled && !MoneyLocalRules.isMerged(it) && it.slug != slug }
                if (j >= 0) {
                    cats[j] = cats[j].copy(slot = MoneyLocalRules.NEUTRAL_SLOT, updated_at_ms = now, dirty = true)
                    next = next.copy(slot_taken_by = cats[j].slug)
                }
            }
        } else if (!cur.enabled && next.enabled && next.slot in MoneyLocalRules.takenSlots(cats, cur.direction, slug)) {
            next = next.copy(slot_was = next.slot, slot = MoneyLocalRules.freeSlot(cats, cur.direction, slug))
        }
        cats[i] = next.copy(slot_was = null, slot_taken_by = null)
        _cats.value = cats
        commitCats()
        return next
    }

    fun mergeCat(slug: String, into: String): MoneyResult<MoneyCatUsageDTO> {
        ensureLoaded()
        return try {
            val r = MoneyLocalRules.mergeCat(_cats.value, _entries.value, slug, into, now())
            _cats.value = r.cats; _entries.value = r.entries
            saveEntries(); commitCats()
            MoneyResult.Ok(r.moved)
        } catch (e: MoneyLocalRules.MergeRefused) {
            MoneyResult.Failed(MoneyLocalRules.refusalText(e.why))
        }
    }

    /** 删分类 = 并进本方向的兜底分类（墓碑，拍板 I）。前置条件同服务端：没账、没子类；兜底不能删。回 null = 删了。 */
    fun deleteCat(slug: String): String? {
        ensureLoaded()
        val c = _cats.value.firstOrNull { it.slug == slug && !MoneyLocalRules.isMerged(it) } ?: return "这个分类不存在"
        if (c.locked) return "「${c.name}」是兜底分类，不能删"
        val u = MoneyLocalRules.usage(slug, _cats.value, _entries.value)
        if (u.entries > 0) return "「${c.name}」名下还有 ${u.entries} 笔账，删不掉 —— 用「合并到…」"
        if (u.subs > 0) return "「${c.name}」还挂着 ${u.subs} 个子类，先删掉它们"
        val r = runCatching { MoneyLocalRules.mergeCat(_cats.value, _entries.value, slug, MoneyLocalRules.fallbackSlug(c.direction), now()) }.getOrNull()
            ?: return "没删掉"
        _cats.value = r.cats
        commitCats()
        return null
    }

    fun reorderCats(direction: String, slugs: List<String>) {
        ensureLoaded()
        val now = now()
        val order = HashMap<String, Int>()
        slugs.forEachIndexed { i, s -> if (s !in order) order[s] = i }
        _cats.value = _cats.value.map { c ->
            val seq = order[c.slug]
            if (c.direction == direction && !MoneyLocalRules.isMerged(c) && seq != null && seq != c.seq) c.copy(seq = seq, updated_at_ms = now, dirty = true) else c
        }
        commitCats()
    }

    fun setSubs(cat: String, labels: List<String>): Boolean {
        ensureLoaded()
        val cats = _cats.value.toMutableList()
        val i = cats.indexOfFirst { it.slug == cat && !MoneyLocalRules.isMerged(it) }
        if (i < 0) return false
        val clean = ArrayList<String>()
        for (l in labels) { val t = l.trim(); if (t.isNotEmpty() && t !in clean) clean.add(t) }
        cats[i] = cats[i].copy(subs = clean, subs_updated_at_ms = now(), dirty = true)
        _cats.value = cats
        commitCats()
        return true
    }

    private fun commitCats() { saveCats(); schedulePush() }

    // MARK: 同步（联网那一截）

    /** 会话守卫的「开」：联网了。同步那一轮由 MoneyModule 接着调 MoneyStore.syncLocalThenRefresh。 */
    fun sessionUnlocked() { online = true }

    /** 会话守卫的「关」：只停推。本机数据一笔不删。 */
    fun sessionLocked() {
        online = false
        pushJob?.cancel(); pushJob = null
    }

    /** 还没推上去的条数 —— 换账号那一问里说「记账 N 条」用。 */
    val pendingCount: Int
        get() { ensureLoaded(); return MoneyLocalRules.pendingCount(_entries.value, tombs, _cats.value, _rules.value, ruleTombs) }

    @Serializable
    private data class Dropped(val fromUser: String?, val entries: List<MoneyEntryDTO>, val rules: List<MoneyRecurDTO>, val cats: List<MoneyCatDTO>)

    /** 换账号：已同步过的本机数据全部清掉、按新账号从 0 拉回为准；carry 才留下没推上去的。墓碑一律丢。 */
    private fun rebaseForAccount(carry: Boolean, server: String, user: String) {
        val dirtyEntries = _entries.value.filter(MoneyLocalRules::isUnpushed)
        val dirtyRules = _rules.value.filter { it.dirty == true }
        val dirtyCats = _cats.value.filter { it.dirty == true }
        if (!carry && !(dirtyEntries.isEmpty() && dirtyRules.isEmpty() && dirtyCats.isEmpty())) {
            // 丢是用户选的，但留个后悔药不费事（money/personal/dropped-<时刻>.json）。
            write("dropped-${now()}.json", Dropped.serializer(), Dropped(meta.lastSyncUser, dirtyEntries, dirtyRules, dirtyCats))
        }
        val r = MoneyLocalRules.rebaseForAccount(carry, _entries.value, _cats.value, _rules.value)
        _entries.value = r.entries; _cats.value = r.cats; _rules.value = r.rules
        tombs = emptyList(); ruleTombs = emptyList()
        saveEntries(); saveTombs(); saveCats(); saveRules(); saveRuleTombs()
        meta = Meta(0, server, user, 0)
        saveMeta()
    }

    private fun schedulePush() {
        if (!online) return
        pushJob?.cancel()
        pushJob = AppGraph.scope.launch {
            delay(3000)
            MoneyStore.syncLocalThenRefresh()
        }
    }

    /**
     * 这次同步归哪个账号：登着的用户 id。还没问出来是谁（冷启动 /auth/me 没回来）回 null，这一轮不同步 ——
     * 一个占位串和上次记下的账号比出来就是「换了账号」，会把本机已同步的账清掉。
     */
    private fun whoami(): String? = AppGraph.auth.user?.id?.toString()

    /** 同步一轮。互斥：在跑时再叫只记一笔，跑完补一轮。 */
    suspend fun sync() {
        ensureLoaded()
        if (!online || AppGraph.guard.locked.value) return
        if (_syncing.value) { resyncRequested = true; return }
        _syncing.value = true
        try {
            do {
                resyncRequested = false
                // 后台同步不是人点出来的：整轮标成后台路径，统一出口不打「人引起的」头。
                withContext(BackgroundWork) { syncOnce() }
            } while (resyncRequested)
        } finally {
            _syncing.value = false
        }
    }

    private suspend fun syncOnce() {
        val server = AppGraph.prefs.serverUrl
        val user = whoami() ?: return
        // 换了账号（umbra-ios#89）：不静默合并两个人的账。**必须在一切推送之前**：下面 ① 推分类、③ 推 dirty 正是「带过去」。
        when (AccountSwitchRules.plan(meta.lastSyncUser, meta.lastSyncServer, user, server, pendingCount)) {
            AccountSwitchPlan.Adopt, AccountSwitchPlan.Same -> Unit
            AccountSwitchPlan.Replace -> rebaseForAccount(false, server, user)
            AccountSwitchPlan.Ask -> {
                val from = meta.lastSyncUser ?: return
                // 键连服务端一起拼：换了服务端、id 恰好相同的那次换号也是一次独立的换号。
                when (MoneyAccountSwitch.choice(
                    AccountSwitchRules.identity(from, meta.lastSyncServer ?: server),
                    AccountSwitchRules.identity(user, server),
                )) {
                    AccountSwitchChoice.Carry -> rebaseForAccount(true, server, user)
                    AccountSwitchChoice.Drop -> rebaseForAccount(false, server, user)
                    AccountSwitchChoice.Later, null -> { _lastSyncError.value = AccountSwitchRules.LATER_NOTE; return }
                }
            }
        }
        // 第一次登录（单机期的数据）/ 换了服务端：水位线归零从头拉；本机 dirty 的照推 —— 它们归到这个账号名下。
        val sameSource = (meta.lastSyncServer == null || meta.lastSyncServer == server) && (meta.lastSyncUser == null || meta.lastSyncUser == user)
        val since = if (sameSource) meta.lastSyncMs else 0L

        // ① 分类全量往返（先于流水：流水的 cat 要指向服务端认得的 slug）
        val catResp = MoneyApi.syncCats(_cats.value) ?: run { _lastSyncError.value = "分类同步没成（服务端没应答）"; return }
        val applied = MoneyLocalRules.applyCatSync(_cats.value, catResp.cats, catResp.merged, _entries.value, now())
        _cats.value = applied.first; _entries.value = applied.second
        saveCats(); saveEntries()

        // ①′ 周期规则：推墓碑 → 推 dirty → 拉增量 → 合并 → 本机补记到现在（补出的流水在下面跟着推）
        for (t in ruleTombs.toList()) {
            if (MoneyApi.deleteRecur(t.id, com.geek.umbra.net.Ledger.Personal)) {
                ruleTombs = ruleTombs.filter { !(it.id == t.id && it.deletedAtMs == t.deletedAtMs) }
                saveRuleTombs()
            }
        }
        for (r in _rules.value.filter { it.dirty == true }) {
            val (srv, written) = MoneyApi.putRecurSync(r) ?: continue
            val list = _rules.value.toMutableList()
            val i = list.indexOfFirst { it.id == r.id }
            if (i < 0 || list[i].updated_at_ms != r.updated_at_ms) continue
            list[i] = if (written) list[i].copy(dirty = null) else srv.copy(dirty = null)
            _rules.value = list
        }
        val ruleSince = if (sameSource) (meta.lastRuleSyncMs ?: 0L) else 0L
        MoneyApi.recurSyncPull(ruleSince)?.let { page ->
            _rules.value = MoneyLocalRules.mergeRules(_rules.value, page.items, page.deleted.map { MoneyTomb(it.id, it.deleted_at_ms) }, ruleTombs)
            saveRules()
            meta = meta.copy(lastRuleSyncMs = page.synced_at_ms)
            saveMeta()
            scanDue()
        }

        // ② 推墓碑（途中又删的照留：按 id@时刻 过滤）
        val batch = tombs
        if (batch.isNotEmpty() && MoneyApi.deleteEntries(batch.map { it.id }, com.geek.umbra.net.Ledger.Personal)) {
            val sent = batch.map { "${it.id}@${it.deletedAtMs}" }.toSet()
            tombs = tombs.filter { "${it.id}@${it.deletedAtMs}" !in sent }
            saveTombs()
        }

        // ③ 逐条推 dirty（单条失败继续往下）
        var failed = 0
        for (r in _entries.value.filter { it.dirty == true }) {
            val res = MoneyApi.putEntry(r, com.geek.umbra.net.Ledger.Personal)
            val put = res.getOrNull()
            if (put != null) {
                _entries.value = MoneyLocalRules.applyPut(_entries.value, r,
                    if (put.written) MoneyLocalRules.PutOutcome.Written else MoneyLocalRules.PutOutcome.Rejected(put.entry))
            } else if ((res.exceptionOrNull() as? MoneyApi.PutFailure)?.why is MoneyPutFail.OtherLedger) {
                _entries.value = MoneyLocalRules.applyPut(_entries.value, r, MoneyLocalRules.PutOutcome.OtherLedger)
            } else failed++
        }

        // ③′ 本机暂存的附件补传（这笔已经推上去了才传）
        pushLocalAtts()

        // ④ 拉增量 → 合并 → 先存条目、再前移水位线
        val page = MoneyApi.syncPull(since) ?: run { saveEntries(); _lastSyncError.value = "拉增量没成（服务端没应答）"; return }
        _entries.value = MoneyLocalRules.mergeEntries(_entries.value, page.items, page.deleted.map { MoneyTomb(it.id, it.deleted_at_ms) }, tombs)
        saveEntries()
        meta = Meta(page.synced_at_ms, server, user, meta.lastRuleSyncMs)
        saveMeta()
        _lastSyncAt.value = now()
        _lastSyncError.value = if (failed > 0) "$failed 笔没推上去，下次再试" else ""
    }
}
