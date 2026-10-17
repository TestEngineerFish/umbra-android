// 提醒的进程级 Store —— 对应 iOS ReminderViews.swift 的 `ReminderStore` + ReminderCache.swift + HolidayCalendarStore.swift。
//
// **数据跨端同步**（服务端 /reminders），本机那份是离线缓存：断网时照常读写并排队，联网后按 updated_at_ms 逐条合并。
// **到点触发全靠本机**（服务端只存不调度）：每次拿到新数据都把本机闹钟整体重排一遍（ReminderNotifier.rescheduleAll）。
// **未登录完全本地可用**：只有同步看登录（会话守卫「开」= 同步一次，「关」= 只停同步那一截）。
//
// 线程：全部跑在 AppGraph.scope（Main.immediate）上，与 iOS 的 @MainActor 同一个约束 —— 互斥靠两个布尔就够，
// 不用锁。本机文件很小（几十 KB），同步读写与 iOS 的 UserDefaults 同量级。
package com.geek.umbra.feature.reminder

import android.content.Context
import com.geek.umbra.app.AppGraph
import com.geek.umbra.navigation.Tab
import com.geek.umbra.navigation.TabBadges
import com.geek.umbra.navigation.UmbraAlert
import com.geek.umbra.net.BackgroundWork
import com.geek.umbra.net.HttpResult
import com.geek.umbra.net.UmbraHttp
import com.geek.umbra.net.UmbraJson
import com.geek.umbra.net.UmbraUrl
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale

// MARK: - 本机文件

/**
 * `filesDir/reminder/` 下的 JSON 文件。原子写（先写临时文件再改名）：写到一半被杀，盘上要么旧要么新，不会半截。
 * 读坏的文件改名 `.corrupt-<时间>` 留底（只留第一份：主文件下一次保存会被覆盖成「剩下的」，底稿是唯一能捞回来的地方）。
 */
internal object ReminderFiles {
    private val dir: File get() = File(AppGraph.context.filesDir, "reminder").also { it.mkdirs() }

    fun file(name: String) = File(dir, name)

    fun read(name: String): String? = runCatching { file(name).takeIf { it.exists() }?.readText() }.getOrNull()

    fun write(name: String, text: String) {
        runCatching {
            val f = file(name)
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    fun delete(name: String) { runCatching { file(name).delete() } }

    /** 留底一次：已经有这个文件的底稿就不再写（同 iOS LossyDecode.backupOnce）。 */
    fun backupOnce(name: String, text: String) {
        val has = dir.listFiles()?.any { it.name.startsWith("$name.corrupt-") } == true
        if (has) return
        val stamp = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).format(Date())
        runCatching { File(dir, "$name.corrupt-$stamp").writeText(text) }
    }
}

/**
 * 提醒的本机缓存（iOS ReminderCache）：条目 + 水位线（与服务端地址成对）+ 待推墓碑 + 被拒的删除。
 * 墓碑、拒收记录单放一个 meta 文件，条目一个文件。
 */
internal object ReminderCache {
    private const val ITEMS = "reminders.json"
    private const val META = "meta.json"

    private var meta: JsonObject = loadMeta()

    private fun loadMeta(): JsonObject {
        val raw = ReminderFiles.read(META) ?: return JsonObject(emptyMap())
        return runCatching { UmbraJson.parseToJsonElement(raw) as JsonObject }.getOrElse {
            ReminderFiles.backupOnce(META, raw); JsonObject(emptyMap())
        }
    }

    private fun saveMeta(next: JsonObject) { meta = next; ReminderFiles.write(META, next.toString()) }

    private fun putMeta(key: String, v: JsonElement?) =
        saveMeta(JsonObject(meta.toMutableMap().apply { if (v == null) remove(key) else put(key, v) }))

    /** 读缓存：逐条解、丢坏留好；读不全时把原文留底（台账 `reminder-cache-decode-swallowed`）。 */
    fun load(): List<Reminder> {
        val raw = ReminderFiles.read(ITEMS) ?: return emptyList()
        val root = runCatching { UmbraJson.parseToJsonElement(raw) }.getOrNull()
        val got = ReminderCodec.decodeList(root)
        if (got == null) { ReminderFiles.backupOnce(ITEMS, raw); return emptyList() }
        if (got.second > 0) ReminderFiles.backupOnce(ITEMS, raw)
        return got.first.sortedBy { it.atMs }
    }

    fun save(items: List<Reminder>) {
        ReminderFiles.write(ITEMS, buildJsonArray { items.forEach { add(ReminderCodec.encode(it)) } }.toString())
        com.geek.umbra.feature.widget.WidgetBridge.remindersChanged()
    }

    private val storedServer: String? get() = meta.strictString("syncedServer")
    private val syncedAtMs: Long get() = meta.strictLong("syncedAtMs") ?: 0

    fun since(server: String): Long = ReminderWatermark.since(syncedAtMs, storedServer, server)

    fun setSynced(ms: Long, server: String) =
        saveMeta(JsonObject(meta.toMutableMap().apply { put("syncedAtMs", JsonPrimitive(ms)); put("syncedServer", JsonPrimitive(server)) }))

    /** 下一次拉全量（拒删没带那一行 / 放弃改动）。 */
    fun resetWatermark() = putMeta("syncedAtMs", JsonPrimitive(0))

    fun switchedServer(to: String): Boolean = ReminderServerSwitch.switched(storedServer, to)

    /** 认下新服务端：地址换成它、水位线归 0。 */
    fun adoptServer(server: String) = setSynced(0, server)

    fun pendingTombs(): Map<String, Long> =
        (meta["tombs"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.toLongOrNull()?.let { k to it } }?.toMap() ?: emptyMap()

    private fun writeTombs(t: Map<String, Long>) = putMeta("tombs", buildJsonObject { t.forEach { (k, v) -> put(k, v) } })

    fun addTomb(id: String, ms: Long) = writeTombs(pendingTombs() + (id to ms))

    fun removeTomb(id: String) { writeTombs(pendingTombs() - id); setTombRefusal(id, null) }

    fun tombRefusals(): Map<String, ReminderPushRejection> =
        (meta["tombRefusals"] as? JsonObject)?.mapNotNull { (k, v) ->
            val o = v as? JsonObject ?: return@mapNotNull null
            o.strictLong("status")?.let { k to ReminderPushRejection(it.toInt(), o.strictString("detail")) }
        }?.toMap() ?: emptyMap()

    fun setTombRefusal(id: String, r: ReminderPushRejection?) {
        val map = tombRefusals().toMutableMap()
        if (map[id] == r) return
        if (r == null) map.remove(id) else map[id] = r
        putMeta("tombRefusals", if (map.isEmpty()) null else buildJsonObject {
            map.forEach { (k, v) -> put(k, buildJsonObject { put("status", v.status); v.detail?.let { put("detail", it) } }) }
        })
    }
}

/**
 * 节假日表的本机那份（umbra#93）：同步时拉当年 + 次年、原子写盘、按服务端隔离、30 分钟节流。iOS HolidayCalendarStore.swift。
 * 落盘是因为冷启动的推进在任何网络请求之前就要用它。
 */
internal object HolidayCalendarStore {
    private const val FILE = "umbra-cn-holidays.json"
    private var file: HolidayCacheFile? = ReminderFiles.read(FILE)?.let { HolidayRules.decode(it) }
    /** 每清一次加一：在途期间登出的话，回来的结果不许再写回去。 */
    private var generation = 0
    private var lastAttempt: Pair<Long, String>? = null
    /** 两次拉取之间至少隔 30 分钟（失败也算一次尝试）；换了服务端不受限。 */
    private const val MIN_INTERVAL_MS = 30 * 60 * 1000L

    fun calendar(server: String): HolidayCalendar? = HolidayRules.calendar(file, server)

    /** 拉当年 + 次年并落盘。返回日历有没有变（变了调用方重推进 / 重排）。这一步失败不算同步失败。 */
    suspend fun refresh(server: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        lastAttempt?.let { (ms, s) -> if (s == server && nowMs - ms < MIN_INTERVAL_MS) return false }
        lastAttempt = nowMs to server
        val gen = generation
        val results = HolidayRules.yearsToFetch(nowMs, ZoneId.systemDefault()).map { y -> y to fetch(y) }
        if (gen != generation || AppGraph.guard.locked.value) return false
        val next = HolidayRules.merged(file, server, results)
        if (next == file) return false
        val before = calendar(server)
        file = next
        ReminderFiles.write(FILE, HolidayRules.encode(next).toString())
        return calendar(server) != before
    }

    private suspend fun fetch(year: Int): CnHolidayFetch =
        when (val r = AppGraph.http.raw("GET", "/calendar/cn-holidays", mapOf("year" to year))) {
            is HttpResult.Ok -> HolidayRules.classify(r.status, r.body)
            is HttpResult.Fail -> HolidayRules.classify(r.status, r.body)
            else -> CnHolidayFetch.Failed
        }

    /** 登出时清（2026-10-10 契约后 iOS 已不在登出时调；留着给换服务端 / 将来用）。 */
    @Suppress("unused")
    fun clear() { generation += 1; file = null; lastAttempt = null; ReminderFiles.delete(FILE) }
}

// MARK: - Store

/** 换了服务端、本机还有没推上去的提醒，等人答「带过去 / 丢掉」。 */
data class ServerSwitchAsk(val count: Int, val server: String)

object ReminderStore {
    private val _items = MutableStateFlow<List<Reminder>>(emptyList())
    val items: StateFlow<List<Reminder>> = _items

    /** 通知权限。null = 还没查。 */
    private val _notifyAuthorized = MutableStateFlow<Boolean?>(null)
    val notifyAuthorized: StateFlow<Boolean?> = _notifyAuthorized

    /** 精确闹钟授权（Android 12+；14+ 默认拒）。没有时退非精确闹钟，列表横幅说明可能延迟。 */
    private val _exactAlarmAllowed = MutableStateFlow(true)
    val exactAlarmAllowed: StateFlow<Boolean> = _exactAlarmAllowed

    /** 第一次同步还没回来（只在全新安装、缓存为空、首次同步在路上那几百毫秒里看得见）。 */
    private val _firstSyncing = MutableStateFlow(true)
    val firstSyncing: StateFlow<Boolean> = _firstSyncing

    /** 成功从服务端拉到过一次。区分「真的没有」和「一次都没同步成功」。 */
    private val _loadedOnce = MutableStateFlow(false)
    val loadedOnce: StateFlow<Boolean> = _loadedOnce

    private val _refusedTombIds = MutableStateFlow<Map<String, ReminderPushRejection>>(emptyMap())
    val refusedTombIds: StateFlow<Map<String, ReminderPushRejection>> = _refusedTombIds

    private val _serverSwitchAsk = MutableStateFlow<ServerSwitchAsk?>(null)
    val serverSwitchAsk: StateFlow<ServerSwitchAsk?> = _serverSwitchAsk

    private var started = false
    private var syncing = false
    /** 同步进行中又来了新的同步请求：记一笔，跑完补一轮（秘书刚建的「5 分钟后提醒我」等不起下一轮）。 */
    private var resyncRequested = false
    /** 这一次 sync 里已经为「合并后又推进了几条」补排过一轮了（只补一轮）。 */
    private var advanceResyncQueued = false
    private var askJob: Job? = null

    /** 「等了一阵」的门槛：30 分钟（不是试了多久，是等了多久；见 iOS 注释）。 */
    const val SYNC_QUIET_MS = 30 * 60 * 1000L

    private val ctx: Context get() = AppGraph.context
    private val server: String get() = AppGraph.prefs.serverUrl

    /**
     * 冷启动：读缓存 → 推进过点的重复提醒（缺表的工作日先放着，umbra-ios#72）→ 重排闹钟 → 角标 → 起一次同步。
     * 由 ReminderModule.register 在 App 启动时调；广播接收器冷启动拉起进程时也经这里。幂等。
     */
    fun start() {
        if (started) return
        started = true
        _items.value = ReminderCache.load()
        _refusedTombIds.value = ReminderCache.tombRefusals()
        ReminderNotifier.ensureChannel(ctx)
        refreshAuthorization()
        if (advanceDue(holdUncoveredWorkdays = true)) ReminderCache.save(_items.value)
        rescheduleAll()
        refreshBadge()
        // 秘书或别的端改了提醒 → 立刻拉一次（iOS ChatViewModel 收 reminder_updated / reminder_deleted）。
        AppGraph.scope.launch {
            AppGraph.socket.frames.collect { f ->
                val t = (f["type"] as? JsonPrimitive)?.content
                if (t == "reminder_updated" || t == "reminder_deleted") syncNow()
            }
        }
        AppGraph.scope.launch { serverSwitchAsk.collect { if (it != null) presentServerSwitch() } }
        AppGraph.scope.launch { sync() }
    }

    fun item(id: String): Reminder? = _items.value.firstOrNull { it.id == id }

    // MARK: 同步

    /** 「重试」那一类：先把首屏标志举起来（否则本地一条都没有时点下去界面不动）。 */
    fun reload() {
        _firstSyncing.value = true
        refreshAuthorization()
        syncNow()
    }

    /** 下拉刷新：await 到真的拉完。 */
    suspend fun reloadAsync() { refreshAuthorization(); sync() }

    fun syncNow() { AppGraph.scope.launch { sync() } }

    /** 还没传到服务端的：本地 dirty 的 + 待推墓碑（被服务端明确拒收的不数）。返回条数与最早那一条的时刻。 */
    val unsynced: Pair<Int, Long>?
        get() {
            var oldest = Long.MAX_VALUE; var n = 0
            val refused = ReminderCache.tombRefusals()
            for (r in _items.value) if (r.dirty && r.pushRejection == null) { n++; oldest = minOf(oldest, r.pendingSinceMs ?: r.updatedAtMs) }
            for ((id, ms) in ReminderCache.pendingTombs()) if (refused[id] == null) { n++; oldest = minOf(oldest, ms) }
            return if (n > 0) n to oldest else null
        }

    /** 有东西等着、而且已经等过门槛。渲染时求值，不挂定时器（刻意的，见 iOS）。 */
    val syncStuck: Boolean get() = unsynced?.let { System.currentTimeMillis() - it.second >= SYNC_QUIET_MS } ?: false

    /** 拉增量 → 合并 → 补推。同步中再调只记一笔待办、跑完补一轮。未登录直接返回。 */
    suspend fun sync() {
        if (AppGraph.guard.locked.value) return
        if (syncing) { resyncRequested = true; return }
        syncing = true
        advanceResyncQueued = false
        try {
            do {
                resyncRequested = false
                withContext(BackgroundWork) { syncOnce() }
            } while (resyncRequested)
        } finally {
            syncing = false
            _firstSyncing.value = false
        }
    }

    /** 一轮实际的同步。任何一步失败都不动本地数据。顺序见 iOS ReminderViews.swift:433。 */
    private suspend fun syncOnce() {
        // ⓪⁻ 换了服务端：已同步过的立刻清、没推上去的问人；问的期间这一轮到此为止。
        val target = server
        if (ReminderCache.switchedServer(target)) {
            val plan = ReminderServerSwitch.plan(_items.value, ReminderCache.pendingTombs())
            if (plan.dropIds.isNotEmpty()) applyMerged(_items.value.filter { it.id !in plan.dropIds.toSet() })
            ReminderCache.resetWatermark()
            if (plan.askCount > 0) { _serverSwitchAsk.value = ServerSwitchAsk(plan.askCount, target); return }
            ReminderCache.adoptServer(target)
        }
        if (_serverSwitchAsk.value != null) _serverSwitchAsk.value = null
        // ⓪ 节假日表必须在第一次推进之前拉（umbra-ios#72）。
        if (HolidayCalendarStore.refresh(server)) rescheduleAll()
        // ⓪½ 过点的重复提醒先推进，推出来的这一轮跟着推上去。
        if (advanceDue()) applyMerged(_items.value)
        // ① 先补推：本地的改动先上去，免得紧接着拉下来的服务端旧值把它盖了。
        pushPending()
        // ② 拉增量并合并。地址在发请求之前取一次、落水位线时用同一个。
        val srv = server
        val since = ReminderCache.since(srv)
        val page = AppGraph.http.get<ReminderListDTO>("/reminders", mapOf("since" to since)) ?: return
        _loadedOnce.value = true
        val merged = ReminderMerge.apply(_items.value, page.items, page.deleted, ReminderCache.pendingTombs())
        ReminderCache.setSynced(page.synced_at_ms, srv)
        applyMerged(merged)
        // ③ 拉下来的里面可能有别的端写的、已经过点的重复提醒 —— 推进、补一轮同步推上去（只补一轮）。
        if (advanceDue()) {
            applyMerged(_items.value)
            if (!advanceResyncQueued) { advanceResyncQueued = true; resyncRequested = true }
        }
    }

    /** 推进与排通知用的节假日日历：当前这台服务端给的那份。两处必须从同一处取。 */
    internal val holidayCalendar: HolidayCalendar? get() = HolidayCalendarStore.calendar(server)

    /** 把过点的重复提醒推到下一次。只改 items、不落盘不重排。holdUncoveredWorkdays 只有冷启动传 true。 */
    private fun advanceDue(holdUncoveredWorkdays: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        val cal = holidayCalendar
        var moved = false
        val list = _items.value.map { r ->
            val v = ReminderAdvance.applied(r, now, cal) ?: return@map r
            if (holdUncoveredWorkdays && ReminderAdvance.waitsForHolidayTable(r, v, now, cal)) return@map r
            moved = true
            v
        }
        if (moved) _items.value = list.sortedBy { it.atMs }
        return moved
    }

    /** 合并结果落地：存缓存 + 整体重排 + 刷角标。四件事谁都不能漏。 */
    private fun applyMerged(merged: List<Reminder>) {
        _items.value = merged.sortedBy { it.atMs }
        ReminderCache.save(_items.value)
        rescheduleAll()
        refreshBadge()
    }

    private fun update(id: String, f: (Reminder) -> Reminder?) {
        _items.value = _items.value.mapNotNull { if (it.id == id) f(it) else it }
    }

    /** 把本地攒下的改动与删除推上去。推成功才清 dirty / 墓碑。iOS: ReminderViews.swift:553 */
    private suspend fun pushPending() {
        var changedByServer = false
        val refusedTombs = ReminderCache.tombRefusals()
        for ((id, ms) in ReminderCache.pendingTombs()) {
            if (refusedTombs[id] != null) continue
            val receipt = when (val r = deleteRemote(id, ms)) {
                is ReminderPushResult.Ok -> r.value
                ReminderPushResult.RetryLater -> continue
                is ReminderPushResult.Refused -> { ReminderCache.setTombRefusal(id, r.why); continue }
            }
            ReminderCache.removeTomb(id)
            when (val next = ReminderMerge.afterDelete(receipt, item(id))) {
                ReminderMerge.DeleteFollowUp.Done -> Unit
                is ReminderMerge.DeleteFollowUp.Restore -> {
                    _items.value = if (item(id) != null) _items.value.map { if (it.id == id) next.reminder else it } else _items.value + next.reminder
                    changedByServer = true
                }
                ReminderMerge.DeleteFollowUp.RefetchAll -> ReminderCache.resetWatermark()
            }
        }
        for (r in _items.value.filter { it.dirty && it.pushRejection == null }) {
            val resp = when (val p = putRemote(r.toDto())) {
                is ReminderPushResult.Ok -> p.value
                ReminderPushResult.RetryLater -> continue
                is ReminderPushResult.Refused -> {
                    // 只标推上去的那一版：在途期间又改过（戳对不上）那是新的一版，下一轮照推。
                    update(r.id) { if (it.updatedAtMs == r.updatedAtMs) it.copy(pushRejection = p.why) else it }
                    continue
                }
            }
            if (item(r.id) == null) continue
            if (resp.applied) {
                update(r.id) { ReminderAttsWire.settled(it, r.updatedAtMs) }
            } else {
                val cur = item(r.id) ?: continue
                val v = ReminderMerge.rejected(cur, r.updatedAtMs, resp.reminder)
                if (v == null) changedByServer = true
                update(r.id) { v }
            }
        }
        if (changedByServer) applyMerged(_items.value) else ReminderCache.save(_items.value)
        _refusedTombIds.value = ReminderCache.tombRefusals()
    }

    private suspend fun putRemote(dto: ReminderDTO): ReminderPushResult<ReminderPutDTO> {
        val body = dto.toJson().toString().toRequestBody(UmbraHttp.JSON)
        return when (val r = AppGraph.http.raw("PUT", "/reminders/${UmbraUrl.value(dto.id)}", body = body)) {
            is HttpResult.Ok -> runCatching { ReminderPushResult.Ok(UmbraJson.decodeFromString(ReminderPutDTO.serializer(), r.body)) }
                .getOrElse { ReminderPushResult.RetryLater }
            is HttpResult.Fail -> if (r.status >= 400) ReminderPushFail.failure(r.status, r.body) else ReminderPushResult.RetryLater
            else -> ReminderPushResult.RetryLater
        }
    }

    private suspend fun deleteRemote(id: String, ms: Long): ReminderPushResult<ReminderDeleteReceipt> =
        when (val r = AppGraph.http.raw("DELETE", "/reminders/${UmbraUrl.value(id)}", mapOf("at_ms" to ms))) {
            is HttpResult.Ok -> ReminderTombPush.receipt(r.status, r.body)?.let { ReminderPushResult.Ok(it) } ?: ReminderPushResult.RetryLater
            is HttpResult.Fail -> ReminderTombPush.receipt(r.status, r.body)?.let { ReminderPushResult.Ok(it) } ?: ReminderPushFail.failure(r.status, r.body)
            else -> ReminderPushResult.RetryLater
        }

    /**
     * 本地写操作的统一收尾：盖戳、标 dirty、清 pendingSince / 拒收标记、落缓存、重排、角标，最后经 sync() 推
     * （推送受同一把互斥，永远只有一条在路上）。iOS: ReminderViews.swift:626
     */
    private fun commit(r: Reminder) {
        val v = r.copy(updatedAtMs = System.currentTimeMillis(), dirty = true, pendingSinceMs = null, pushRejection = null)
        _items.value = (if (item(v.id) != null) _items.value.map { if (it.id == v.id) v else it } else _items.value + v).sortedBy { it.atMs }
        ReminderCache.save(_items.value)
        rescheduleAll()
        refreshBadge()
        syncNow()
    }

    // MARK: 服务端不收的那几条

    fun retryRejected(id: String) {
        if (item(id)?.pushRejection == null) return
        update(id) { it.copy(pushRejection = null) }
        ReminderCache.save(_items.value)
        syncNow()
    }

    /** 「放弃改动」：删掉本机这一行、水位线归 0 拉一次全量（服务端有这条就按它那一版回来）。 */
    fun discardRejected(id: String) {
        if (item(id) == null) return
        applyMerged(_items.value.filter { it.id != id })
        ReminderCache.resetWatermark()
        syncNow()
    }

    fun retryRefusedTombs() {
        for (id in _refusedTombIds.value.keys) ReminderCache.setTombRefusal(id, null)
        _refusedTombIds.value = ReminderCache.tombRefusals()
        syncNow()
    }

    fun discardRefusedTombs() {
        for (id in _refusedTombIds.value.keys) ReminderCache.removeTomb(id)
        _refusedTombIds.value = ReminderCache.tombRefusals()
        ReminderCache.resetWatermark()
        syncNow()
    }

    // MARK: 换服务端那一问

    /** 「带过去」：认下新服务端，没推上去的照常推给它；旧服务端的拒收标记一并清掉。 */
    fun carryOverToNewServer() {
        val ask = _serverSwitchAsk.value ?: return
        _serverSwitchAsk.value = null
        if (server != ask.server) { syncNow(); return }
        _items.value = _items.value.map { if (it.pushRejection != null) it.copy(pushRejection = null) else it }
        for (id in ReminderCache.tombRefusals().keys) ReminderCache.setTombRefusal(id, null)
        _refusedTombIds.value = emptyMap()
        ReminderCache.save(_items.value)
        ReminderCache.adoptServer(ask.server)
        syncNow()
    }

    /** 「丢掉」：没推上去的那几条从本机删掉（连同待推删除），再从新服务端拉全量。 */
    fun discardForNewServer() {
        val ask = _serverSwitchAsk.value ?: return
        _serverSwitchAsk.value = null
        if (server != ask.server) { syncNow(); return }
        for (id in ReminderCache.pendingTombs().keys) ReminderCache.removeTomb(id)
        _refusedTombIds.value = emptyMap()
        applyMerged(_items.value.filter { !ReminderServerSwitch.isLocalChange(it) })
        ReminderCache.adoptServer(ask.server)
        syncNow()
    }

    /**
     * 「有 N 条提醒还没同步，要带到新服务端吗？」—— iOS 由外壳挂（问题不属于哪一页）。Android 外壳不归本模块改，
     * 由 store 自己等弹窗那一格空出来再问（router.confirm 只有一格，顶掉别人的确认框会让那边的动作落不了地）。
     * 「带过去」在取消位（安全的那边），「丢掉」在确认位标红。
     */
    private fun presentServerSwitch() {
        askJob?.cancel()
        askJob = AppGraph.scope.launch {
            snapshotFlow { AppGraph.router.alert == null }.first { it }
            val ask = _serverSwitchAsk.value ?: return@launch
            if (AppGraph.auth.revokeNotice.value) return@launch
            AppGraph.router.confirm(UmbraAlert(
                title = "有 ${ask.count} 条提醒还没同步，要带到新服务端吗？",
                message = "换了服务端。同步过的提醒已从这台设备上清掉，以新服务端为准；这 ${ask.count} 条是在这台设备上新建、改过或删掉，还没传上去的。带过去会照常传到新服务端，丢掉会从这台设备上删掉。",
                cancelLabel = "带过去", confirmLabel = "丢掉", destructive = true,
                onCancel = { carryOverToNewServer(); AppGraph.router.showToast("这 ${ask.count} 条会传到新服务端") },
                onConfirm = { discardForNewServer(); AppGraph.router.showToast("已丢掉这 ${ask.count} 条") },
            ))
        }
    }

    // MARK: 「再来一条」模板通道

    private var cloneTemplate: Reminder? = null

    /** 路由只认 id，传不了草稿 —— 详情页 / 聊天把模板放这儿再跳新建页，新建页取走即清。 */
    fun stashCloneTemplate(r: Reminder) { cloneTemplate = r }
    fun takeCloneTemplate(): Reminder? = cloneTemplate.also { cloneTemplate = null }

    // MARK: 写操作

    /** 编辑页 / 新建页落库：先规整每周几（at 挪到第一个选中日），再按提醒时区定锚定日。 */
    fun save(r: Reminder) {
        var v = ReminderAdvance.normalizedWeekDays(r)
        v = v.copy(anchorDay = ReminderAdvance.anchorDayOf(v.atMs, r.anchorDay, ReminderAdvance.zone(r.tz)))
        commit(v)
    }

    fun delete(id: String) {
        _items.value = _items.value.filter { it.id != id }
        ReminderCache.save(_items.value)
        // 删除也要留墓碑并推上去：只删本地的话，下次拉取又会把它同步回来。
        ReminderCache.addTomb(id, System.currentTimeMillis())
        ReminderNotifier.cancel(ctx, id)
        rescheduleAll()
        refreshBadge()
        syncNow()
    }

    /** 标成完成 / 未完成。**幂等**：已经是这个状态就什么都不做（通知上「完成」点两下不能翻回去）。 */
    fun setDone(id: String, done: Boolean) {
        val r = item(id) ?: return
        if (r.done == done) return
        commit(r.copy(done = done))
    }

    /** 「再等 N 分钟」：从现在往后推，不是从原时间推（原时间可能已经过去两小时了）。 */
    fun snooze(id: String, minutes: Int = 10) {
        val r = item(id) ?: return
        commit(r.copy(atMs = System.currentTimeMillis() + minutes * 60_000L, done = false))
    }

    fun snooze(id: String, untilMs: Long) {
        val r = item(id) ?: return
        commit(r.copy(atMs = untilMs, done = false))
    }

    // MARK: 本地通知 / 角标

    /** 未完成且已过期 / 今天到点的条数（App 图标角标口径）。 */
    val pendingCount: Int get() = _items.value.count { !it.done && (it.group == "已过期" || it.group == "今天") }

    /**
     * 角标。提醒 Tab 写「未完成 && 已过期」（iOS AppShell 的 tab 角标口径，和 pendingCount 不是一个数，别合并）。
     * App 图标角标在 Android 上由启动器按通知数显示，见 ReminderNotifier。
     */
    fun refreshBadge() {
        val n = _items.value.count { !it.done && it.group == "已过期" }
        if (n > 0) TabBadges.counts[Tab.Rem] = n else TabBadges.counts.remove(Tab.Rem)
    }

    /** 整体重排本机闹钟（含撤掉主人已不在的那些）。[floorMs]：刚响过的闹钟传它的时刻，保证排的是它之后的下一次。 */
    fun rescheduleAll(floorMs: Long = 0) {
        ReminderNotifier.rescheduleAll(ctx, _items.value, holidayCalendar, maxOf(System.currentTimeMillis(), floorMs))
        _exactAlarmAllowed.value = ReminderNotifier.exactAllowed(ctx)
    }

    fun refreshAuthorization() {
        _notifyAuthorized.value = ReminderNotifier.canPost(ctx)
        _exactAlarmAllowed.value = ReminderNotifier.exactAllowed(ctx)
    }

    /** 权限结果回来（运行时请求）。 */
    fun onPermissionResult(granted: Boolean) { _notifyAuthorized.value = granted; if (granted) rescheduleAll() }

    /**
     * 闹钟响过：重排（排它之后的下一次）+ 角标。**不在这里推进 at**：iOS 也只在冷启动 / 同步时推进 ——
     * 刚响的那次在列表上还该是「已过期」，人才能对它点「完成」。
     */
    internal fun afterAlarm(firedAtMs: Long) {
        rescheduleAll(firedAtMs + 1)
        refreshBadge()
    }

    /** 会话守卫「关」：只关和服务端的那一截（提醒是本地功能：不撤通知、不清角标）。 */
    fun sessionLocked() { _serverSwitchAsk.value = null }

    /** 会话守卫「开」：同步一次。 */
    fun sessionUnlocked() { syncNow() }
}
