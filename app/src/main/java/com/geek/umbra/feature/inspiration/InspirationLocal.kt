// 灵感本机账本（iOS: Features/Inspiration/InspirationLocal.swift，单机化 ②-c，拍板 G），照记账 MoneyLocal / PC manager.ts：
//   · 本机是真相：filesDir/inspiration/{items,tombs,meta}.json，按 uid 认；页面读它，不登录也能记；
//   · 改动：盖 updated_at_ms、标 dirty、落盘、3 秒防抖推；删除 = 移除 + 墓碑；
//   · sync()：换账号判定 → 推墓碑 → 逐条推 dirty → 拉增量 → 合并；整理 / 调研是服务端 AI，结果随增量回来 ——
//     页面要列表时顺手同步（节流 4 秒）；
//   · 同步向会话守卫登记（InspirationModule）：登出只停推、不删条目。
package com.geek.umbra.feature.inspiration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraButton
import com.geek.umbra.designsystem.UmbraButtonKind
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.navigation.UmbraSheet
import com.geek.umbra.net.AccountSwitchChoice
import com.geek.umbra.net.AccountSwitchPlan
import com.geek.umbra.net.AccountSwitchRules
import com.geek.umbra.net.ApiResult
import com.geek.umbra.net.BackgroundWork
import com.geek.umbra.net.UmbraJson
import com.geek.umbra.net.UmbraUrl
import com.geek.umbra.net.isOk
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.util.UUID

object InspirationLocal {
    private val _items = MutableStateFlow<List<Inspiration>>(emptyList())
    /** 本机全部条目（未排序）。页面经 [list] 读。 */
    val items: StateFlow<List<Inspiration>> = _items
    private var tombs: List<InspTomb> = emptyList()

    @Serializable
    private data class Meta(val lastSyncMs: Long = 0, val lastSyncServer: String? = null, val lastSyncUser: String? = null)

    @Serializable
    private data class Dropped(val fromUser: String?, val items: List<Inspiration>)

    private var meta = Meta()
    private var online = false
    private var pushJob: Job? = null
    private var lastListSyncAt = 0L
    /** 正在跑的那一轮；后来者标记 resync 并等它（「帮我查查」前先推要的是真结果）。 */
    private var inflight: Job? = null
    private var resyncRequested = false
    private var loaded = false

    private fun now() = System.currentTimeMillis()

    // MARK: 落盘

    private val dir: File get() = File(AppGraph.context.filesDir, "inspiration")

    /** 读不出来（坏文件）时原文件改名 `.corrupt-<时间>` 留底，再从空开始（工程约定第 11 条）。 */
    private fun <T> read(name: String, ser: KSerializer<T>): T? {
        val f = File(dir, name)
        if (!f.exists()) return null
        val text = runCatching { f.readText() }.getOrNull() ?: return null
        return runCatching { UmbraJson.decodeFromString(ser, text) }.getOrElse {
            runCatching { f.renameTo(File(dir, "$name.corrupt-${now() / 1000}")) }
            null
        }
    }

    /** 先写临时文件再改名：写到一半被杀不会留下半截 JSON。 */
    private fun <T> write(name: String, ser: KSerializer<T>, v: T) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(UmbraJson.encodeToString(ser, v))
            tmp.renameTo(File(dir, name))
        }
    }

    /** 第一次用到才读盘（AppGraph.context 那时一定已就绪）。 */
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        _items.value = read("items.json", ListSerializer(Inspiration.serializer())) ?: emptyList()
        tombs = read("tombs.json", ListSerializer(InspTomb.serializer())) ?: emptyList()
        meta = read("meta.json", Meta.serializer()) ?: Meta()
    }

    private fun saveItems() = write("items.json", ListSerializer(Inspiration.serializer()), _items.value)
    private fun saveTombs() = write("tombs.json", ListSerializer(InspTomb.serializer()), tombs)
    private fun saveMeta() = write("meta.json", Meta.serializer(), meta)

    // MARK: 读

    /** 列表（新 → 旧，可按状态筛）。登着的话顺手同步一轮（节流 4 秒）—— 整理 / 调研的结果就这样回来。 */
    fun list(status: String?): List<Inspiration> {
        ensureLoaded()
        val t = now()
        if (online && t - lastListSyncAt > 4_000) {
            lastListSyncAt = t
            AppGraph.scope.launch { sync() }
        }
        return InspLocalRules.filtered(InspLocalRules.sorted(_items.value), status)
    }

    fun find(id: Long): Inspiration? { ensureLoaded(); return _items.value.firstOrNull { it.id == id } }

    // MARK: 写

    fun create(raw: String, title: String, summary: String, tags: List<String>, research: Boolean): Inspiration? {
        ensureLoaded()
        if (raw.isBlank()) return null
        val row = InspLocalRules.fresh(UUID.randomUUID().toString().lowercase(), raw, title, summary, tags, research, now(), Instant.now().toString())
        _items.value = _items.value + row
        saveItems(); schedulePush()
        return row
    }

    fun update(id: Long, patch: InspPatch): Inspiration? {
        ensureLoaded()
        val i = _items.value.indexOfFirst { it.id == id }
        if (i < 0) return null
        val next = InspLocalRules.applying(_items.value[i], patch, now())
        _items.value = _items.value.toMutableList().also { it[i] = next }
        saveItems(); schedulePush()
        return next
    }

    /** 本机删除：找不到回 0（别处已删、同步已把它拿走）—— 交给 afterDelete 说「这条已经不在了」。 */
    fun delete(id: Long): Int {
        ensureLoaded()
        val row = _items.value.firstOrNull { it.id == id } ?: return 0
        _items.value = _items.value.filter { it.id != id }
        tombs = tombs.filter { it.id != row.key } + InspTomb(row.key, now())
        saveItems(); saveTombs(); schedulePush()
        return 1
    }

    /** 「帮我查查」是服务端 AI：先把这条推上去（要整数 id），再排队；没登录回 false。 */
    suspend fun requestResearch(id: Long): Boolean {
        ensureLoaded()
        val cur = find(id) ?: return false
        if (!online) return false
        if (cur.dirty == true || cur.id <= 0) sync()
        val now = _items.value.firstOrNull { it.key == cur.key }?.takeIf { it.id > 0 } ?: return false
        if (!AppGraph.http.call("POST", "/inspirations/${now.id}/research").isOk) return false
        val i = _items.value.indexOfFirst { it.key == cur.key }
        if (i >= 0 && _items.value[i].dirty != true) {
            _items.value = _items.value.toMutableList().also { it[i] = it[i].copy(research_status = "queued") }
            saveItems()
        }
        return true
    }

    // MARK: 同步

    fun sessionUnlocked() { ensureLoaded(); online = true; AppGraph.scope.launch { sync() } }
    fun sessionLocked() { online = false; pushJob?.cancel(); pushJob = null }

    private fun schedulePush() {
        if (!online) return
        pushJob?.cancel()
        pushJob = AppGraph.scope.launch {
            delay(3_000)
            sync()
        }
    }

    /** 认不出是谁（冷启动 /auth/me 没回来）回 null、这一轮不同步 —— 占位串会被换账号那一截当成「换了账号」。 */
    private fun whoami(): String? = AppGraph.auth.user?.id?.toString()

    /** 还没推上去的条数（dirty + 墓碑）—— 换账号那一问里说「灵感 N 条」用。 */
    val pendingCount: Int get() { ensureLoaded(); return InspLocalRules.pendingCount(_items.value, tombs) }

    /** 已同步过的清掉、按新账号从 0 拉回为准；carry 才留下没推上去的。丢掉前另存一份 dropped-<时刻>.json。墓碑一律丢。 */
    private fun rebaseForAccount(carry: Boolean, server: String, user: String) {
        val dirty = _items.value.filter { it.dirty == true }
        if (!carry && dirty.isNotEmpty()) write("dropped-${now()}.json", Dropped.serializer(), Dropped(meta.lastSyncUser, dirty))
        _items.value = InspLocalRules.rebaseForAccount(carry, _items.value)
        tombs = emptyList()
        saveItems(); saveTombs()
        meta = Meta(0, server, user)
        saveMeta()
    }

    suspend fun sync() {
        ensureLoaded()
        if (!online || AppGraph.guard.locked.value) return
        inflight?.let { resyncRequested = true; it.join(); return }
        val job = AppGraph.scope.launch {
            // 后台同步不是人点出来的：整轮标成后台路径，统一出口不打「人引起的」头。
            do { resyncRequested = false; withContext(BackgroundWork) { syncOnce() } } while (resyncRequested)
        }
        inflight = job
        job.join()
        inflight = null
    }

    private suspend fun syncOnce() {
        val server = AppGraph.prefs.serverUrl
        val user = whoami() ?: return
        // 换了账号（§5.5.2，umbra-ios#89）：必须在推墓碑 / 推 dirty 之前。
        when (AccountSwitchRules.plan(meta.lastSyncUser, meta.lastSyncServer, user, server, pendingCount)) {
            AccountSwitchPlan.Adopt, AccountSwitchPlan.Same -> Unit
            AccountSwitchPlan.Replace -> rebaseForAccount(false, server, user)
            AccountSwitchPlan.Ask -> {
                val from = meta.lastSyncUser ?: return
                when (InspAccountSwitchGate.choice(
                    AccountSwitchRules.identity(from, meta.lastSyncServer ?: server),
                    AccountSwitchRules.identity(user, server),
                )) {
                    AccountSwitchChoice.Carry -> rebaseForAccount(true, server, user)
                    AccountSwitchChoice.Drop -> rebaseForAccount(false, server, user)
                    AccountSwitchChoice.Later, null -> return
                }
            }
        }
        val same = (meta.lastSyncServer == null || meta.lastSyncServer == server) && (meta.lastSyncUser == null || meta.lastSyncUser == user)
        val since = if (same) meta.lastSyncMs else 0L
        // ① 墓碑
        if (tombs.isNotEmpty()) {
            val body = buildJsonObject { put("uids", buildJsonArray { tombs.forEach { add(JsonPrimitive(it.id)) } }) }
            if (AppGraph.http.call("POST", "/inspirations/delete-by-uid", body).isOk) { tombs = emptyList(); saveTombs() }
        }
        // ② dirty —— 推的时候那一条又被改了（updated_at_ms 变了）就不清 dirty，下一轮再推。
        for (r in _items.value.filter { it.dirty == true }) {
            val body = buildJsonObject {
                put("raw", r.raw); put("title", r.title); put("summary", r.summary)
                put("tags", buildJsonArray { r.tags.forEach { add(JsonPrimitive(it)) } })
                put("status", r.status)
                // iOS 写 "ios"；Android 报自己的端标识（服务端认不认见 docs/Android开发文档.md §六）
                put("source_channel", UmbraUrl.CLIENT_SRC)
                put("updated_at_ms", r.updated_at_ms ?: 0)
            }
            val put = (AppGraph.http.send<InspPutDTO>("PUT", "/inspirations/by-uid/${UmbraUrl.segment(r.key)}", body) as? ApiResult.Ok)?.value ?: continue
            val i = _items.value.indexOfFirst { it.key == r.key }
            if (i < 0 || _items.value[i].updated_at_ms != r.updated_at_ms) continue
            val next = if (put.written ?: true) _items.value[i].copy(dirty = null, id = if (put.item.id > 0) put.item.id else _items.value[i].id)
            else put.item.copy(dirty = null)
            _items.value = _items.value.toMutableList().also { it[i] = next }
        }
        // ③ 拉增量 → 合并
        val page = AppGraph.http.get<InspSyncPullDTO>("/inspirations/sync", mapOf("since" to since))
        if (page == null) { saveItems(); return }
        _items.value = InspLocalRules.merge(_items.value, page.items, page.deleted.map { InspTomb(it.uid, it.deleted_at_ms) }, tombs)
        saveItems()
        meta = Meta(page.synced_at_ms, server, user)
        saveMeta()
    }
}

/**
 * 换账号那一问的闸（iOS: App/AccountSwitchGate.swift，umbra-ios#89）。**不阻塞同步等答案**：没答之前这一轮到此为止
 * （不推不拉、本机原样），答了再补一轮。答案只记在内存里、只管这一次运行：「以后再说」下次启动会再问。
 *
 * ⚠️ iOS 这一闸是记账、灵感两边共用的一问（文案里两边各列几条）。记账在另一个移植分支里，这里先只数灵感那一边
 * （money 传 0）；合并时应把它挪到 app/ 或 net/ 做成共用的一份（见交回报告）。
 */
object InspAccountSwitchGate {
    private data class Ask(val from: String, val to: String)
    private var ask: Ask? = null
    private val answers = mutableMapOf<String, AccountSwitchChoice>()

    /** 答过就回答案；没答过就弹一次、回 null（这一轮到此为止）。 */
    fun choice(from: String, to: String): AccountSwitchChoice? {
        answers[AccountSwitchRules.key(from, to)]?.let { return it }
        val next = Ask(from, to)
        if (ask != next) { ask = next; present(next) }
        return null
    }

    private fun answer(c: AccountSwitchChoice) {
        val a = ask ?: return
        answers[AccountSwitchRules.key(a.from, a.to)] = c
        ask = null
        if (c == AccountSwitchChoice.Later) AppGraph.router.showToast(AccountSwitchRules.LATER_NOTE)
        else AppGraph.scope.launch { InspirationLocal.sync(); InspirationStore.load() }
    }

    /** 三个选项，系统弹窗只有两颗钮 —— 用底部弹层。破坏性那颗（丢掉）画红；下拉关掉 = 以后再说。 */
    private fun present(a: Ask) {
        AppGraph.scope.launch {
            AppGraph.router.present(UmbraSheet("account-switch-insp") { dismiss ->
                val c = UmbraTheme.colors
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(AccountSwitchRules.TITLE, color = c.text, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
                    Text(AccountSwitchRules.body(0, InspirationLocal.pendingCount), color = c.muted, style = TextStyle(fontSize = 14.sp, lineHeight = 21.sp))
                    UmbraButton(AccountSwitchRules.CARRY_LABEL, { dismiss(); answer(AccountSwitchChoice.Carry) }, Modifier.fillMaxWidth())
                    UmbraButton(AccountSwitchRules.DROP_LABEL, { dismiss(); answer(AccountSwitchChoice.Drop) }, Modifier.fillMaxWidth(), kind = UmbraButtonKind.DangerOutline)
                    UmbraButton(AccountSwitchRules.LATER_LABEL, { dismiss(); answer(AccountSwitchChoice.Later) }, Modifier.fillMaxWidth(), kind = UmbraButtonKind.Secondary)
                }
            })
        }
    }
}
