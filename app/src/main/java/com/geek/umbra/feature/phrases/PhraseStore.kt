// 常用语的本机一份 + 同步 —— 对应 iOS Features/Me/PhrasesView.swift 的 PhraseStore 与 PhraseHandout。
//
// 同步约定（与 PC 完全一样，服务端 POST /phrases/sync）：每条两个毫秒戳（内容 updatedAt、位置 orderUpdatedAt），
// 逐条各比各的 LWW；删除留墓碑；一次往返 = 推本地全量 + 墓碑，服务端合并后回全量。
// 本地存 `filesDir/phrases/phrases.json`（明文 —— 页面上也这么写了，密钥类要放保险箱）。
package com.geek.umbra.feature.phrases

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import com.geek.umbra.app.AppGraph
import com.geek.umbra.net.ApiResult
import com.geek.umbra.net.BackgroundWork
import com.geek.umbra.net.UmbraJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.UUID

object PhraseStore {
    private val _items = MutableStateFlow<List<Phrase>>(emptyList())
    val items: StateFlow<List<Phrase>> = _items
    private var tombs: List<PhraseTomb> = emptyList()

    /** 同步状态的一行说明。同步是后台行为，用户唯一能看到的就是这行字，所以要具体。 */
    private val _syncNote = MutableStateFlow("还没同步过")
    val syncNote: StateFlow<String> = _syncNote
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing

    /** 本地版本号：每改一次本地 +1，同步回包落地不加（umbra-ios#40）。不持久化：只在一次往返的两端之间比大小。 */
    private var revision = 0
    private var loaded = false
    private var syncedAtMs: Long = 0

    private val dir: File get() = File(AppGraph.context.filesDir, "phrases").also { it.mkdirs() }
    private const val FILE = "phrases.json"
    private const val META = "meta.json"

    /** 这台设备上成功同步过至少一次。工具页卡底要它：本机为空时「同步过 = 真的没有」，「从没同步过 = 不知道」。 */
    val everSynced: Boolean get() { ensureLoaded(); return syncedAtMs > 0 }

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val f = File(dir, FILE)
        if (f.exists()) {
            val raw = runCatching { f.readText() }.getOrNull()
            val got = raw?.let { runCatching { UmbraJson.parseToJsonElement(it) }.getOrNull() }?.let { PhraseBundle.decodeLossy(it) }
            if (got == null) { raw?.let { backupOnce(it) } }
            else {
                if (got.second) backupOnce(raw)
                _items.value = got.first.items.sortedBy { it.order }
                tombs = got.first.deleted
            }
        }
        syncedAtMs = runCatching { (UmbraJson.parseToJsonElement(File(dir, META).readText()) as JsonObject)["syncedAt"] as JsonPrimitive }
            .getOrNull()?.longOrNull ?: 0
        if (syncedAtMs > 0) _syncNote.value = "上次同步 " + relative(syncedAtMs)
    }

    /** 读不全的原文留一份底（只留第一份）：下一次 persist 会被「剩下的」覆盖，底稿是唯一捞得回来的地方。 */
    private fun backupOnce(raw: String) {
        if (dir.listFiles()?.any { it.name.startsWith("$FILE.corrupt-") } == true) return
        runCatching { File(dir, "$FILE.corrupt-" + SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).format(Date())).writeText(raw) }
    }

    private fun persist() {
        runCatching {
            val tmp = File(dir, "$FILE.tmp")
            tmp.writeText(PhraseBundle(_items.value, tombs).encode().toString())
            val f = File(dir, FILE)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    private val nowMs: Long get() = System.currentTimeMillis()

    /** 改本地的唯一出口：先 +1 版本号再存盘。新增一种改本地的操作时走这里。 */
    private fun commitLocal() { revision += 1; persist() }

    /** 新建 / 编辑 / 排序改完之后的那一份落地：两个戳只经 PhraseSyncRules.stamp 盖。 */
    private fun commitStamped(next: List<Phrase>) {
        _items.value = PhraseSyncRules.stamp(next, _items.value, nowMs)
        commitLocal()
        syncNow()
    }

    /** 新建一条：追加在末尾（order = max+1），别的条目一个都不平移。 */
    fun add(name: String, content: String, keyword: String?) {
        ensureLoaded()
        val p = Phrase(UUID.randomUUID().toString().uppercase(), name, content, keyword, (_items.value.maxOfOrNull { it.order } ?: 0) + 1, nowMs)
        commitStamped(_items.value + p)
    }

    /** 编辑一条。原样保存不盖戳（stamp 里判）。 */
    fun update(p: Phrase) {
        if (_items.value.none { it.id == p.id }) return
        commitStamped(_items.value.map { if (it.id == p.id) p else it })
    }

    fun delete(id: String) {
        _items.value = _items.value.filter { it.id != id }
        // 墓碑留着直到服务端也知道这条没了；不做过期清理（条数少，攒着的代价远小于「删了又活过来」）。
        if (tombs.none { it.id == id }) tombs = tombs + PhraseTomb(id, nowMs)
        commitLocal()
        syncNow()
    }

    /** 拖动排序后重排 order（= 下标）：只盖顺序戳，不许盖内容戳（umbra-pc#83）。 */
    fun reorder(ids: List<String>) {
        val byId = _items.value.associateBy { it.id }
        val list = ids.mapNotNull { byId[it] }.mapIndexed { i, p -> p.copy(order = i) }
        if (list.size != _items.value.size) return
        commitStamped(list)
    }

    fun syncNow() { AppGraph.scope.launch { sync() } }

    /**
     * 一次 sync = 推本地全量、拿回合并结果；往返途中本地动过就丢掉回包再推（判据与循环在 PhraseSyncRules）。
     * 未登录（单机）不同步、也不留「同步失败」那行字。在途时又被调直接返回（途中真动过的话在途那一轮会自己发现、自己再推）。
     */
    suspend fun sync() {
        ensureLoaded()
        if (AppGraph.guard.locked.value) return
        if (_syncing.value) return
        _syncing.value = true
        _syncNote.value = "同步中…"
        try {
            val outcome = withContext(BackgroundWork) {
                PhraseSyncRules.run(
                    revision = { revision },
                    push = { push() },
                    apply = { merged ->
                        _items.value = merged.items.sortedBy { it.order }
                        tombs = merged.deleted
                        persist()
                    },
                )
            }
            when (outcome) {
                PhraseSyncRules.Outcome.Applied -> {
                    syncedAtMs = nowMs
                    runCatching { File(dir, META).writeText(buildJsonObject { put("syncedAt", syncedAtMs) }.toString()) }
                    _syncNote.value = "刚刚同步"
                }
                PhraseSyncRules.Outcome.Failed -> _syncNote.value = "同步失败，本地改动已留着，下次会再试"
                PhraseSyncRules.Outcome.Deferred -> _syncNote.value = "本地还有改动没推完，下次同步会带上"
            }
        } finally { _syncing.value = false }
    }

    /** POST /phrases/sync：推**此刻**的本地全量（每轮现读）。 */
    private suspend fun push(): PhraseBundle? {
        val body = buildJsonObject {
            put("items", buildJsonArray { _items.value.forEach { add(it.wire()) } })
            put("deleted", buildJsonArray { tombs.forEach { add(it.wire()) } })
        }
        return when (val r = AppGraph.http.call("POST", "/phrases/sync", body)) {
            is ApiResult.Ok -> PhraseBundle.decodeLossy(r.value)?.first
            is ApiResult.Err -> null
        }
    }

    /** 「上次同步 3 分钟前」。 */
    private fun relative(ms: Long): String {
        val d = (System.currentTimeMillis() - ms) / 1000
        return when {
            d < 60 -> "刚刚"
            d < 3600 -> "${d / 60} 分钟前"
            d < 86400 -> "${d / 3600} 小时前"
            else -> "${d / 86400} 天前"
        }
    }
}

/**
 * 常用语离开 App 的**唯一出口**：交出那一刻展开 {date} / {time} / {clipboard}（PhraseVars）。iOS: PhrasesView.swift:612
 * {clipboard} 只在常用语里真有它时才读；剪贴板里是敏感值（ClipDescription.EXTRA_IS_SENSITIVE，保险箱复制会打这个标记）
 * 就不读、展开为空。写进剪贴板的是普通（非敏感）内容 —— 常用语是明文。
 */
object PhraseHandout {
    enum class ClipNote { Ok, Withheld, Denied }

    fun copy(ctx: Context, p: Phrase): ClipNote {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        var note = ClipNote.Ok
        val text = PhraseVars.expand(p.content, System.currentTimeMillis(), ZoneId.systemDefault()) {
            val r = PhraseVars.readClip(
                isSensitive = { isSensitive(cm) },
                hasText = { cm.primaryClipDescription?.let { it.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) || it.hasMimeType("text/*") } == true },
                read = { cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString() },
            )
            note = if (r.withheld) ClipNote.Withheld else if (r.denied) ClipNote.Denied else ClipNote.Ok
            r.text
        }
        cm.setPrimaryClip(ClipData.newPlainText(p.name, text))
        return note
    }

    /**
     * 剪贴板里是不是敏感值。Android 13+ 看 EXTRA_IS_SENSITIVE（及其早期非公开键名）；更早的系统没有这个标记 ——
     * 判不出来时按「不敏感」（Android 12 及以下的保险箱复制同样打不上标记，这一格两端无法对齐，见报告）。
     */
    private fun isSensitive(cm: ClipboardManager): Boolean {
        val extras = cm.primaryClipDescription?.extras ?: return false
        val key = if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE"
        return extras.getBoolean(key, false)
    }
}
