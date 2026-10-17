// 提醒的模型本体：存储字段、本机缓存编解码、附件改动标记 —— 对应 iOS Features/Reminder/ReminderModel.swift。
//
// ⚠️ 纯 Kotlin，不许 import android.*：ReminderAdvance / ReminderSync 的 JUnit 用例要直接用它。
// 跟显示 / 本机日历有关的派生（分组、时间文案）在 ReminderDisplay.kt。
//
// 时刻一律 epoch 毫秒 Long（iOS 是 Date，边界上才转毫秒）：服务端、推进算法、PC 全是整数毫秒，
// 这里直接用毫秒省掉一层来回转换，也省掉「Double 舍入差 1ms → LWW 判成两个版本」那一类坑。
package com.geek.umbra.feature.reminder

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.TimeZone

/** 一张附件的引用（文件本体在服务端 /files/{fileId}）。附件跟着整条提醒 LWW 同步。iOS: ReminderModel.swift:13 */
data class ReminderAtt(val fileId: String, val label: String)

/**
 * 服务端明确不收某一条推送时记下的那一笔（台账 `permanent-4xx-stuck`）。持久化进本机缓存：
 * App 被杀再开，也不该对一条服务端已经说过「不收」的改动重新自动推。iOS: ReminderSync.swift:204
 */
data class ReminderPushRejection(val status: Int, val detail: String?)

/**
 * 一条提醒。字段与 iOS `UmbraReminder` 一一对应（注释里的坑也照搬）：
 *
 * - [atts]：⚠️ 改附件**只许经 [withAtts]**（iOS 用 didSet：值真变了才置 attsTouched，umbra-ios#39）。
 *   data class 的 copy(atts=…) 绕得开它 —— 所以全工程只在 [withAtts] 与构造里写 atts。
 * - [attsTouched]：这一份本地附件是用户在这台设备上改出来、还没被服务端采纳的。只有它为 true 时上行才带 atts 键。
 * - [pendingSinceMs]：本机这一行从什么时候起等着上传，只有重复提醒被自动推进时才写（横幅按它算等了多久）。
 * - [tz]：这条提醒**建的时候**在哪个时区（IANA 名）。空串 = 老缓存 / 服务端老行，上行时再补本机时区。
 * - [anchorDay]：按月 / 按年重复的锚定日（1–31），nil = 没存。
 * - [weekDays]：每周选中的星期几（0=周一…6=周日）。nil = 本机没这一格（上行不写键）；[] = 没选（上行写 []）。
 * - [pushRejection]：服务端明确不收这一条。
 */
data class Reminder(
    val id: String,
    val text: String,
    val atMs: Long,
    val repeatRule: String = "不重复",
    val customFreq: String = "天",
    val customN: Long = 1,
    val repeatEndMs: Long? = null,
    val aheadMinutes: Int = 0,
    val note: String = "",
    val done: Boolean = false,
    val source: String = "手动添加",
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    val dirty: Boolean = true,
    val atts: List<ReminderAtt> = emptyList(),
    /** 不传时按「带没带图」定：新建一条带图的提醒必须推 atts（iOS ReminderModel.swift:199）。 */
    val attsTouched: Boolean = atts.isNotEmpty(),
    val pendingSinceMs: Long? = null,
    val tz: String = TimeZone.getDefault().id,
    val anchorDay: Int? = null,
    val weekDays: List<Int>? = null,
    val pushRejection: ReminderPushRejection? = null,
) {
    /** 换附件：值真的变了才置 attsTouched（iOS `atts` 的 didSet）。值没变（上传全失败）不算动过。 */
    fun withAtts(next: List<ReminderAtt>): Reminder =
        if (next == atts) this else copy(atts = next, attsTouched = true)

    companion object {
        val repeatOptions = listOf("不重复", "每天", "每周", "每月", "工作日", "自定义")

        /** 星期几短名，下标 0=周一。与 PC `reminderKit.WEEK_DAY_NAMES` 逐字同源。 */
        val weekDayNamesZh = listOf("一", "二", "三", "四", "五", "六", "日")
        val weekDayNamesEn = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

        /** 选中集合规整：只留 0–6，升序去重；nil → []。PC `normWeekDays` 的对照。 */
        fun normWeekDays(v: List<Int>?): List<Int> = (v ?: emptyList()).filter { it in 0..6 }.toSortedSet().toList()

        val customFreqOptions = listOf("小时", "天", "周", "月", "年")

        /**
         * 「自定义 · 每 N」的上限（umbra-ios#68）：输入框不让天文数字进来；老数据 / 别端写进来的超限值在推进 /
         * 排通知时当作「规则不推进」（不改写数据）。三端同一个数。
         */
        const val customNMax = 10_000

        /**
         * 输入框里打的字 → customN。只认 ASCII 数字（全角「１２」不算）；空 / 0 落回 1；超过上限压到上限 ——
         * 包括长到解不出来的一串（多敲一位，「每 30 天」变「每 1 天」比封顶更让人摸不着头脑）。iOS: ReminderModel.swift:135
         */
        fun customN(fromInput: String): Int {
            val digits = fromInput.filter { it in '0'..'9' }
            if (digits.isEmpty()) return 1
            val v = digits.toLongOrNull() ?: return customNMax
            return minOf(customNMax.toLong(), maxOf(1L, v)).toInt()
        }

        /** 提前量上限：7 天。三端同一个数（服务端 umbra#111 超了 PUT 回 400）。 */
        const val aheadMaxMinutes = 10_080

        /** 提前量压进 0…上限：负数当 0，超限当上限。排通知、上行、显示名都用它。 */
        fun aheadClamped(minutes: Int): Int = if (minutes <= 0) 0 else minOf(minutes, aheadMaxMinutes)

        /** 要不要排「提前 X」那条通知：压完之后还 > 0 才排。 */
        fun aheadSchedulable(minutes: Int): Boolean = aheadClamped(minutes) > 0

        val aheadOptions: List<Pair<String, Int>> = listOf("无" to 0, "5 分钟" to 5, "15 分钟" to 15, "1 小时" to 60, "1 天" to 1440)

        /**
         * 提前量的中文显示名。五档用档位名，不在档里按分钟数照实说（秘书建的 30 分钟不能显示成「无」，
         * 台账 `ahead-label-nonstandard`）。iOS: ReminderModel.swift:170
         */
        fun aheadText(minutes: Int): String {
            aheadOptions.firstOrNull { it.second == minutes }?.let { return it.first }
            if (minutes <= 0) return "无"
            if (minutes % 1440 == 0) return "${minutes / 1440} 天"
            if (minutes % 60 == 0) return "${minutes / 60} 小时"
            return "$minutes 分钟"
        }

        /** 英文那一份（设计侧 245 §三）：整天 → days、整小时 → hours、其余 min；N = 1 用单数。 */
        fun aheadTextEn(minutes: Int): String {
            if (minutes > 0 && minutes % 1440 == 0) { val d = minutes / 1440; return "$d ${if (d == 1) "day" else "days"}" }
            if (minutes > 0 && minutes % 60 == 0) { val h = minutes / 60; return "$h ${if (h == 1) "hour" else "hours"}" }
            return "$minutes min"
        }

        /** 一条提醒最多几张附件，取自共用的 AttLimit（同步截断与「满没满」用同一个数）。 */
        const val maxAtts = 4

        /** 字数上限做在客户端、服务端刻意不限（PC reminderKit.TEXT_MAX / NOTE_MAX）。 */
        const val textMax = 200
        const val noteMax = 1000
    }
}

// MARK: - 本机缓存编解码

/**
 * 本机缓存的编解码（iOS 是 Codable + 手写 init(from:)）。手写而不是 @Serializable：
 * 缺键要给默认值、某一格写坏了**只丢那一格**（anchorDay / weekDays / pushRejection），
 * 整条只在必填的四格（id / text / at / repeatRule）解不出时丢 —— 自动生成的解码器做不到「丢一格」。
 */
object ReminderCodec {
    fun encode(r: Reminder): JsonObject = buildJsonObject {
        put("id", r.id)
        put("text", r.text)
        put("at", r.atMs)
        put("repeatRule", r.repeatRule)
        put("customFreq", r.customFreq)
        put("customN", r.customN)
        r.repeatEndMs?.let { put("repeatEnd", it) }
        put("aheadMinutes", r.aheadMinutes)
        put("note", r.note)
        put("done", r.done)
        put("source", r.source)
        put("createdAt", r.createdAtMs)
        put("updatedAtMs", r.updatedAtMs)
        put("dirty", r.dirty)
        put("atts", buildJsonArray { r.atts.forEach { a -> add(buildJsonObject { put("fileId", a.fileId); put("label", a.label) }) } })
        put("attsTouched", r.attsTouched)
        r.pendingSinceMs?.let { put("pendingSinceMs", it) }
        put("tz", r.tz)
        r.anchorDay?.let { put("anchorDay", it) }
        r.weekDays?.let { w -> put("weekDays", buildJsonArray { w.forEach { add(JsonPrimitive(it)) } }) }
        r.pushRejection?.let { p ->
            put("pushRejection", buildJsonObject { put("status", p.status); p.detail?.let { put("detail", it) } })
        }
    }

    /** 解一条。必填四格缺了 / 类型不对 → null（调用方丢掉这一条并留底）。iOS: ReminderModel.swift:226 */
    fun decode(e: JsonElement): Reminder? {
        val o = e as? JsonObject ?: return null
        val id = o.strictString("id") ?: return null
        val text = o.strictString("text") ?: return null
        val at = o.strictLong("at") ?: return null
        val rule = o.strictString("repeatRule") ?: return null
        var customFreq = o.strictString("customFreq") ?: "天"
        var customN = o.strictLong("customN") ?: 1L
        // v1 的「每年」规则映射成 自定义·每 1 年（iOS 同）。
        val repeatRule = if (rule == "每年") { customFreq = "年"; customN = 1; "自定义" } else rule
        val createdAt = o.strictLong("createdAt") ?: at
        val atts = (o["atts"] as? JsonArray)?.mapNotNull { a ->
            val ao = a as? JsonObject ?: return@mapNotNull null
            val fid = ao.strictString("fileId") ?: return@mapNotNull null
            ReminderAtt(fid, ao.strictString("label") ?: "")
        } ?: emptyList()
        return Reminder(
            id = id, text = text, atMs = at, repeatRule = repeatRule, customFreq = customFreq, customN = customN,
            repeatEndMs = o.strictLong("repeatEnd"),
            aheadMinutes = o.strictLong("aheadMinutes")?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt() ?: 0,
            note = o.strictString("note") ?: "",
            done = o.strictBool("done") ?: false,
            source = o.strictString("source") ?: "手动添加",
            createdAtMs = createdAt,
            // 存量数据没有这两个键：从没推给过服务端，标 dirty 等着补推；戳用 createdAt 而不是「现在」——
            // 每次读缓存都盖新戳的话，它会永远比服务端新，服务端的修改就再也进不来了。
            updatedAtMs = o.strictLong("updatedAtMs") ?: createdAt,
            dirty = o.strictBool("dirty") ?: true,
            atts = atts,
            // 老缓存没有这一格 → false（不推 atts，服务端保留库里那份）。不能按 dirty 推断成 true（#39 的原型）。
            attsTouched = o.strictBool("attsTouched") ?: false,
            pendingSinceMs = o.strictLong("pendingSinceMs"),
            tz = o.strictString("tz") ?: "",
            anchorDay = o.strictLong("anchorDay")?.toInt(),
            weekDays = (o["weekDays"] as? JsonArray)?.let { arr ->
                val ints = arr.map { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.intOrNull }
                if (ints.any { it == null }) null else Reminder.normWeekDays(ints.filterNotNull())
            },
            pushRejection = (o["pushRejection"] as? JsonObject)?.let { p ->
                p.strictLong("status")?.let { ReminderPushRejection(it.toInt(), p.strictString("detail")) }
            },
        )
    }

    /**
     * 逐条解、丢坏留好（台账 `reminder-cache-decode-swallowed`）。整份不是数组 → null（调用方要留底，不能当「空」）。
     * 返回 (好的那些, 丢掉了几条)。iOS: LossyDecode.array
     */
    fun decodeList(root: JsonElement?): Pair<List<Reminder>, Int>? {
        val arr = root as? JsonArray ?: return null
        val good = arr.mapNotNull { decode(it) }
        return good to (arr.size - good.size)
    }
}

/** 只认真正的字符串（数字不当字符串）。 */
internal fun JsonObject.strictString(k: String): String? =
    (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.contentOrNull

/** 只认真正的整数（"31" 这种字符串不认 —— 写坏了的格子只丢它自己）。 */
internal fun JsonObject.strictLong(k: String): Long? =
    (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.longOrNull

internal fun JsonObject.strictBool(k: String): Boolean? =
    (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.booleanOrNull
