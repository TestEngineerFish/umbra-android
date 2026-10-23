// 提醒的服务端同步层：线格式（DTO）+ 中英枚举映射 + 合并规则 + 附件上不上行的判据。
// 对应 iOS Features/Reminder/ReminderSync.swift。纯 Kotlin（JUnit 直接跑），一条界面代码都没有。
//
// 三条必须记住的约定（iOS 文件头）：
//   1. **出网一律英文枚举**。界面显示中文（"每天"），服务端存 daily —— 中文会被白名单 400 挡回来。映射只在这里做。
//   2. **时刻一律 epoch 毫秒**。
//   3. **本地缓存是缓存不是真相**：断网时读它、写它并排队，联网后按 updated_at_ms 合并。提醒不能因为断网就不能用。
package com.geek.umbra.feature.reminder

import com.geek.umbra.net.UmbraJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.TimeZone

// MARK: - 线格式（对齐服务端 /reminders 的 JSON）

/**
 * 服务端的一条提醒，字段名直接用 snake_case。必填的那些不给默认值：解不出来就是解不出来
 * （拒删回包里那一行解坏了要当「没带」，不能被默认值兜成一条空提醒，iOS ReminderSync.swift:186）。
 *
 * 可选格编码时 null 不写键（UmbraJson.explicitNulls = false）—— 这正是三处「不写 = 保留服务端那份」的约定：
 * atts 只在动过附件时带、anchor_day 只在 1–31 时带、week_days 本机有这一格才带（含 []）、place 只有地点提醒才带。
 */
@Serializable
data class ReminderDTO(
    val id: String,
    val text: String,
    val note: String,
    val at_ms: Long,
    val repeat_rule: String,
    val custom_freq: String,
    val custom_n: Long,
    val repeat_end_ms: Long? = null,
    val ahead_minutes: Int,
    val done: Boolean,
    val source: String,
    val tz: String,
    val updated_at_ms: Long,
    val deleted: Boolean,
    val atts: List<ReminderAttDTO>? = null,
    /** 服务端这一行第一次入库的时刻（UTC `yyyy-MM-dd HH:mm:ss`）。只下行。 */
    val created_at: String? = null,
    val anchor_day: Int? = null,
    val week_days: List<Int>? = null,
    /** 地点提醒的地点，整块原样（见 ReminderPlace）。下行总带这个键（不是地点提醒为 null）；上行 null 不写键 = 服务端保留。 */
    val place: JsonObject? = null,
)

@Serializable
data class ReminderAttDTO(val file_id: String, val label: String)

@Serializable
data class ReminderTombDTO(val id: String, val deleted_at_ms: Long)

/** GET /reminders 的响应：items 和 deleted 总是一起回（都按 since 过滤）。 */
@Serializable
data class ReminderListDTO(val items: List<ReminderDTO>, val deleted: List<ReminderTombDTO>, val synced_at_ms: Long)

/** PUT /reminders/{id} 的响应。applied=false = 你推的这版更旧，拿回来的 reminder 回滚本地。 */
@Serializable
data class ReminderPutDTO(val applied: Boolean, val reminder: ReminderDTO)

// MARK: - 中英枚举映射

/** 界面中文 ↔ 服务端英文。认不出的值一律退最保守的默认（none / day / manual），不原样透传。 */
object ReminderWire {
    private val rules = listOf("不重复" to "none", "每天" to "daily", "每周" to "weekly", "每月" to "monthly", "工作日" to "weekday", "自定义" to "custom")
    private val freqs = listOf("小时" to "hour", "天" to "day", "周" to "week", "月" to "month", "年" to "year")
    private val sources = listOf("手动添加" to "manual", "聊天" to "chat", "任务" to "task")

    fun ruleToWire(display: String) = rules.firstOrNull { it.first == display }?.second ?: "none"
    fun ruleToDisplay(wire: String) = rules.firstOrNull { it.second == wire }?.first ?: "不重复"
    fun freqToWire(display: String) = freqs.firstOrNull { it.first == display }?.second ?: "day"
    fun freqToDisplay(wire: String) = freqs.firstOrNull { it.second == wire }?.first ?: "天"
    fun sourceToWire(display: String) = sources.firstOrNull { it.first == display }?.second ?: "manual"
    fun sourceToDisplay(wire: String) = sources.firstOrNull { it.second == wire }?.first ?: "手动添加"

    /**
     * 服务端 created_at（SQLite CURRENT_TIMESTAMP：UTC、不带时区后缀）→ epoch 毫秒。认不出回 null（别兜成「现在」，
     * 那会让每一次同步都把创建时间刷新一遍）。⚠️ 时区钉 UTC。也认 ISO 写法。iOS: ReminderSync.swift:146
     */
    fun serverCreatedAt(raw: String?): Long? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        for (p in listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss.SSS")) {
            runCatching { return LocalDateTime.parse(s, DateTimeFormatter.ofPattern(p)).toInstant(ZoneOffset.UTC).toEpochMilli() }
        }
        runCatching { return OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli() }
        return null
    }
}

// MARK: - DTO ↔ 模型

/** 从服务端的一条造出本地模型。服务端来的一定是干净的（dirty=false、attsTouched=false）。iOS: ReminderSync.swift:340 */
fun Reminder.Companion.fromDto(dto: ReminderDTO): Reminder = Reminder(
    id = dto.id,
    text = dto.text,
    atMs = dto.at_ms,
    repeatRule = ReminderWire.ruleToDisplay(dto.repeat_rule),
    customFreq = ReminderWire.freqToDisplay(dto.custom_freq),
    customN = maxOf(1L, dto.custom_n),
    repeatEndMs = dto.repeat_end_ms,
    aheadMinutes = dto.ahead_minutes,
    note = dto.note,
    done = dto.done,
    source = ReminderWire.sourceToDisplay(dto.source),
    createdAtMs = ReminderWire.serverCreatedAt(dto.created_at) ?: dto.at_ms,
    updatedAtMs = dto.updated_at_ms,
    dirty = false,
    // 兜一次形状：空 id 丢掉、超上限截掉（与 PC sync.attsFromWire 同口径）。
    atts = (dto.atts ?: emptyList()).filter { it.file_id.isNotBlank() }.take(Reminder.maxAtts).map { ReminderAtt(it.file_id, it.label) },
    attsTouched = false,
    tz = dto.tz,
    anchorDay = dto.anchor_day?.takeIf { it in 1..31 },
    weekDays = dto.week_days?.let { Reminder.normWeekDays(it) },
    place = dto.place,
)

/** 转成上行的线格式 —— 中文枚举唯一的出口。iOS: ReminderSync.swift:375 */
fun Reminder.toDto(): ReminderDTO = ReminderDTO(
    id = id, text = text, note = note, at_ms = atMs,
    repeat_rule = ReminderWire.ruleToWire(repeatRule),
    custom_freq = ReminderWire.freqToWire(customFreq),
    custom_n = maxOf(1L, customN),
    repeat_end_ms = repeatEndMs,
    // 压进服务端认的界（umbra#111：> 7 天回 400）。
    ahead_minutes = Reminder.aheadClamped(aheadMinutes),
    done = done,
    source = ReminderWire.sourceToWire(source),
    // 这条提醒自己的时区，不是「现在这台手机在哪」；空串（老行）才补本机时区。
    tz = tz.ifEmpty { TimeZone.getDefault().id },
    updated_at_ms = updatedAtMs,
    deleted = false,
    atts = ReminderAttsWire.upstream(atts, attsTouched),
    anchor_day = anchorDay?.takeIf { it in 1..31 },
    week_days = weekDays?.let { Reminder.normWeekDays(it) },
    place = place,
)

/** 上行 JSON（null 不写键）。 */
fun ReminderDTO.toJson(): JsonObject = UmbraJson.encodeToJsonElement(ReminderDTO.serializer(), this) as JsonObject

// MARK: - 删除推送的回执（umbra#106）

/** DELETE 送到之后服务端说了什么：真删掉的条数（0 = 拒删）+ 拒删时带回的库里那一行。 */
data class ReminderDeleteReceipt(val deleted: Int, val reminder: ReminderDTO?)

object ReminderTombPush {
    /**
     * DELETE 的回执。null = 没送到（留着墓碑下次重推）。⚠️ LWW 拒删照样回 200 `{deleted:0}`；
     * 2xx 但体解不出来按「删成了」算；reminder 那一格单独解、解坏只丢它；只有拒删时才收它。iOS: ReminderSync.swift:186
     */
    fun receipt(status: Int, body: String): ReminderDeleteReceipt? {
        if (status !in 200..299) return null
        val o = runCatching { UmbraJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val n = o?.strictLong("deleted") ?: return ReminderDeleteReceipt(1, null)
        val count = maxOf(0, n.toInt())
        var row: ReminderDTO? = null
        if (count == 0) {
            (o["reminder"] as? JsonObject)?.let { raw -> row = runCatching { UmbraJson.decodeFromJsonElement(ReminderDTO.serializer(), raw) }.getOrNull() }
        }
        return ReminderDeleteReceipt(count, row)
    }
}

// MARK: - 推送被服务端永久拒收（台账 `permanent-4xx-stuck`）

/** 一次推送的结果。原来只有「回包 / null」，null 同时表示「没送到」和「服务端说不收」—— 一条永远 400 的每轮都重推。 */
sealed interface ReminderPushResult<out T> {
    data class Ok<T>(val value: T) : ReminderPushResult<T>
    data object RetryLater : ReminderPushResult<Nothing>
    data class Refused(val why: ReminderPushRejection) : ReminderPushResult<Nothing>
}

object ReminderPushFail {
    /**
     * 这个状态码是不是「服务端明确不收这一条、重推也一样」：只认 4xx，剔掉 401 / 403（归会话守卫）、
     * 408 / 425 / 429（等一下再来）。5xx 一律不算。iOS: ReminderSync.swift:234
     */
    fun isPermanent(status: Int): Boolean = status in 400..499 && status !in setOf(401, 403, 408, 425, 429)

    /** 响应体里服务端那句 detail（只收字符串；去首尾空白；超长截到 200 字）。 */
    fun detail(body: String): String? {
        val o = runCatching { UmbraJson.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val t = o.strictString("detail")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (t.length > 200) t.take(200) + "…" else t
    }

    /** 非 2xx 的回包该落哪一档。PUT 与 DELETE 共用这一处。 */
    fun <T> failure(status: Int, body: String): ReminderPushResult<T> =
        if (!isPermanent(status)) ReminderPushResult.RetryLater else ReminderPushResult.Refused(ReminderPushRejection(status, detail(body)))
}

// MARK: - 换服务端（sam 2026-10-07 第 8 条）

object ReminderServerSwitch {
    /** 存过地址、而且和这一次的不一样才算换。没存过（首装 / 升级前）不算 —— 算了的话升级当天清空全部提醒。 */
    fun switched(storedServer: String?, server: String): Boolean = !storedServer.isNullOrEmpty() && storedServer != server

    /** 这一行算不算「还没推上去的本机改动」：dirty 但 pendingSinceMs != null 的是自动推进出来的，不算。 */
    fun isLocalChange(r: Reminder): Boolean = r.dirty && r.pendingSinceMs == null

    data class Plan(val dropIds: List<String>, val askCount: Int)

    fun plan(items: List<Reminder>, pendingTombs: Map<String, Long>): Plan {
        val drop = items.filter { !isLocalChange(it) }.map { it.id }
        return Plan(drop, items.size - drop.size + pendingTombs.size)
    }
}

// MARK: - 水位线

object ReminderWatermark {
    /** 这一次拉增量该带的 since：水位线只对给它的那台服务端有意义，对不上就从 0 拉全量。 */
    fun since(storedMs: Long, storedServer: String?, server: String): Long =
        if (storedServer == null || storedServer != server) 0 else maxOf(0, storedMs)
}

// MARK: - 附件上不上行（umbra-ios#39）

object ReminderAttsWire {
    /** 上行时 atts 那一格：没动过 → null（不写键，服务端保留库里那份）；动过 → 完整数组（含 [] = 明确清空）。 */
    fun upstream(atts: List<ReminderAtt>, touched: Boolean): List<ReminderAttDTO>? =
        if (!touched) null else atts.take(Reminder.maxAtts).map { ReminderAttDTO(it.fileId, it.label) }

    /** 推送被采纳之后：只有「本地此刻就是刚推上去那一版」才清 dirty / attsTouched。 */
    fun settled(current: Reminder, pushedAtMs: Long): Reminder =
        if (current.updatedAtMs != pushedAtMs) current else current.copy(dirty = false, attsTouched = false)
}

// MARK: - 合并规则

object ReminderMerge {
    /**
     * 逐条 last-write-wins，但本地 dirty 且更新时本地赢；本机删了、删除还在路上的那几条（[pendingTombs]）
     * 服务端那一版不比删除新就不加回来；墓碑：本地 dirty 且比删除时刻新 → 删除作废。iOS: ReminderSync.swift:449
     */
    fun apply(local: List<Reminder>, serverItems: List<ReminderDTO>, serverTombs: List<ReminderTombDTO>,
              pendingTombs: Map<String, Long> = emptyMap()): List<Reminder> {
        val byId = LinkedHashMap<String, Reminder>()
        for (r in local) byId[r.id] = r
        for (dto in serverItems) {
            val mine = byId[dto.id]
            if (mine != null && mine.dirty && mine.updatedAtMs > dto.updated_at_ms) continue
            if (mine == null) { val del = pendingTombs[dto.id]; if (del != null && del > dto.updated_at_ms) continue }
            byId[dto.id] = fromServer(dto, mine)
        }
        for (t in serverTombs) {
            val mine = byId[t.id]
            if (mine != null && mine.dirty && mine.updatedAtMs > t.deleted_at_ms) continue
            byId.remove(t.id)
        }
        return byId.values.sortedBy { it.atMs }
    }

    /**
     * 推送被拒（applied=false）之后：只有「本地此刻就是刚推上去那一版」才拿服务端那份覆盖；
     * 返回 null = 服务端那一行是墓碑，本地要删。iOS: ReminderSync.swift:489
     */
    fun rejected(current: Reminder, pushedAtMs: Long, server: ReminderDTO): Reminder? {
        if (current.updatedAtMs != pushedAtMs) return current
        if (server.deleted) return null
        return fromServer(server, current)
    }

    sealed interface DeleteFollowUp {
        data object Done : DeleteFollowUp
        data class Restore(val reminder: Reminder) : DeleteFollowUp
        data object RefetchAll : DeleteFollowUp
    }

    /** 删除被服务端按 LWW 拒了之后的下一步（umbra#106）。iOS: ReminderSync.swift:517 */
    fun afterDelete(receipt: ReminderDeleteReceipt, local: Reminder?): DeleteFollowUp {
        if (receipt.deleted != 0) return DeleteFollowUp.Done
        val server = receipt.reminder ?: return DeleteFollowUp.RefetchAll
        if (server.deleted) return DeleteFollowUp.Done
        if (local != null && local.dirty && local.updatedAtMs > server.updated_at_ms) return DeleteFollowUp.Done
        return DeleteFollowUp.Restore(fromServer(server, local))
    }

    /** 拿服务端那一份造本地的一行；服务端没给 created_at 时创建时间留本机那一行的（不退成 at_ms）。 */
    fun fromServer(dto: ReminderDTO, previous: Reminder?): Reminder {
        val v = Reminder.fromDto(dto)
        return if (ReminderWire.serverCreatedAt(dto.created_at) == null && previous != null) v.copy(createdAtMs = previous.createdAtMs) else v
    }
}

// MARK: - 编辑页保存（第三轮审查 `stale-draft-overwrites`）

object ReminderDraftMerge {
    /**
     * 以 store 里**此刻最新**那一行为底，只覆盖用户在这一页真改过的字段；附件按「加了 / 摘了」两张清单合。
     * latest = null（新建 / 编辑期间被删）→ 原样取草稿。iOS: ReminderSync.swift:549
     */
    fun merge(base: Reminder, edited: Reminder, latest: Reminder?): Reminder {
        var v = latest ?: return edited
        if (edited.text != base.text) v = v.copy(text = edited.text)
        if (edited.note != base.note) v = v.copy(note = edited.note)
        if (edited.atMs != base.atMs) v = v.copy(atMs = edited.atMs)
        if (edited.repeatRule != base.repeatRule) v = v.copy(repeatRule = edited.repeatRule)
        if (edited.customFreq != base.customFreq) v = v.copy(customFreq = edited.customFreq)
        if (edited.customN != base.customN) v = v.copy(customN = edited.customN)
        if (edited.repeatEndMs != base.repeatEndMs) v = v.copy(repeatEndMs = edited.repeatEndMs)
        if (edited.weekDays != base.weekDays) v = v.copy(weekDays = edited.weekDays)
        if (edited.aheadMinutes != base.aheadMinutes) v = v.copy(aheadMinutes = edited.aheadMinutes)
        if (edited.done != base.done) v = v.copy(done = edited.done)
        val added = edited.atts.filter { a -> base.atts.none { it.fileId == a.fileId } }
        val removed = base.atts.map { it.fileId }.toSet() - edited.atts.map { it.fileId }.toSet()
        if (added.isNotEmpty() || removed.isNotEmpty()) {
            val out = v.atts.filter { it.fileId !in removed }.toMutableList()
            for (a in added) if (out.none { it.fileId == a.fileId }) out.add(a)
            v = v.withAtts(out.take(Reminder.maxAtts))
        }
        return v
    }

    /**
     * 上传那一段回来之后，「这次要存的样子」= 编辑器此刻的样子 + 这次传上的图；正文在上传期间被清空就退回 sent 的。
     * iOS: ReminderSync.swift:582
     */
    fun afterUpload(sent: Reminder, uploadedAtts: List<ReminderAtt>, current: Reminder): Reminder {
        val text = current.text.trim()
        var v = current.copy(text = text.ifEmpty { sent.text }, note = current.note.trim())
        val fresh = uploadedAtts.filter { a -> sent.atts.none { it.fileId == a.fileId } }
        if (fresh.isNotEmpty()) {
            val out = current.atts.toMutableList()
            for (a in fresh) if (out.none { it.fileId == a.fileId }) out.add(a)
            v = v.withAtts(out.take(Reminder.maxAtts))
        }
        return v
    }
}

/** 「服务端不收」那几处共用的一句：有 detail 原话照引，没有只报状态码。iOS: ReminderViews.swift ReminderRejectCopy */
object ReminderRejectCopy {
    fun said(why: ReminderPushRejection?): String {
        if (why == null) return ""
        why.detail?.let { return "服务端说：「$it」" }
        return "（HTTP ${why.status}）"
    }
}
