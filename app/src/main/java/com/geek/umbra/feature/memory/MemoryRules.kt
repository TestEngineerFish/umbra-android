// 「记忆」的纯判据 —— 照抄 iOS Features/Me/MemoryRules.swift（2026-10-14 取代「用户画像」；服务端 user_memory.py，
// PC src/features/settings/memory.ts 同一份口径）。只做三件事：解析回包、分组与显示用的字、写回包落成哪一档。
// 不 import android.*；用例 app/src/test/.../memory/MemoryRulesTest.kt（照抄 Tools/swifttests/MemoryRulesTests.swift）。
package com.geek.umbra.feature.memory

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

/** 一条记忆。`id` 是服务端给的对外 id（偏好 / 档案就是类名，其余 `m_…`）。`source`：最后一次是谁写的。 */
data class MemoryItemDTO(
    val id: String,
    /** preferences / profile（固定两条）· topic / area / person。 */
    val kind: String,
    val title: String,
    val summary: String,
    /** 要点列表 markdown（`- ` 开头一行一条）。 */
    val body: String,
    /** ISO 时间（服务端 UTC）。 */
    val updated_at: String,
    val source: String = "",
)

/** 一次写落成哪一档。 */
sealed interface MemoryWrite {
    /**
     * 成功：`item` 写好的那条；`items` 服务端顺带回的全量；`changed` 改了几条；`full` 满 200 条没建成；
     * `skipped` 没建上几条；`firstChanged` 改动的第一条（吐司「看改了哪条」打开它；删掉的不算）。
     */
    data class Ok(
        val item: MemoryItemDTO?, val items: List<MemoryItemDTO>?, val changed: Int?,
        val full: Boolean = false, val skipped: Int = 0, val firstChanged: String? = null,
    ) : MemoryWrite
    /** 409 memory_changed：编辑期间秘书改过这条，带回现在那份。 */
    data class Conflict(val current: MemoryItemDTO) : MemoryWrite
    /** 其余失败：给人看的那句话。 */
    data class Failed(val say: String) : MemoryWrite
}

object MemoryRules {
    /**
     * 读一次 /memory 之后屏上落成什么（umbra-ios#86 ②）：读到就换上、清掉失败标记；读失败**手里那份照留**，但失败标记一律亮。
     */
    fun afterLoad(fetched: List<MemoryItemDTO>?, current: List<MemoryItemDTO>?): Pair<List<MemoryItemDTO>?, Boolean> =
        if (fetched != null) fetched to false else current to true

    /** 屏上那份是不是别的会话读的。还没读成过（null）不算换了。 */
    fun sessionChanged(loadedFor: Int?, now: Int): Boolean = loadedFor != null && loadedFor != now

    const val KEY_GENERATE = "memory.generate"
    const val KEY_SEARCH_CHATS = "memory.searchChats"
    const val KEY_SENSITIVE = "memory.sensitive"

    /** 组的顺序与组名（与 PC 一致）。 */
    val groups: List<Pair<String, String>> = listOf("you" to "你", "topic" to "话题", "area" to "领域", "person" to "人物")

    /** 一条归哪组；认不得的类当话题。 */
    fun group(kind: String): String = when (kind) {
        "preferences", "profile" -> "you"
        "area", "person" -> kind
        else -> "topic"
    }

    /** 按组分好（组内保持服务端顺序）；空组不要，「你」永远在。 */
    fun grouped(items: List<MemoryItemDTO>): List<Pair<String, List<MemoryItemDTO>>> =
        groups.mapNotNull { (key, label) ->
            val list = items.filter { group(it.kind) == key }
            if (key == "you" || list.isNotEmpty()) label to list else null
        }

    fun groupLabel(kind: String): String = groups.firstOrNull { it.first == group(kind) }?.second ?: "话题"

    /** 详情页「最后更新 今天 09:30 / 5 天前 / 09-07」：去掉「更新」二字，今天的带时分。 */
    fun agoText(iso: String, now: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = updatedText(iso, now, zone).replace(" 更新", "").replace("更新", "")
        val d = parseIso(iso)
        if (t != "今天" || d == null) return t
        val z = d.atZone(zone)
        return String.format(Locale.ROOT, "今天 %02d:%02d", z.hour, z.minute)
    }

    /** 冲突条第二段（设计侧 237：按 source 分四句，后接「现在保存会盖掉那一版。」）。 */
    fun changedText(source: String, updatedAt: String, zone: ZoneId = ZoneId.systemDefault()): String {
        val hm = parseIso(updatedAt)?.atZone(zone)?.let { String.format(Locale.ROOT, "%02d:%02d", it.hour, it.minute) } ?: ""
        val who = when (source) {
            "chat" -> "秘书 $hm 从一段对话里改了这条。"
            "instruct" -> "$hm 按「告诉秘书」改了这条。"
            "import" -> "$hm 导入时改了这条。"
            else -> "$hm 在别处改了这条。"
        }
        return who + "现在保存会盖掉那一版。"
    }

    /** 固定两条：标题、一句话改不了，删除 = 清空。 */
    fun isFixed(kind: String): Boolean = kind == "preferences" || kind == "profile"

    /** 可新建的三类。 */
    val freeKinds: List<Pair<String, String>> = listOf("topic" to "话题", "area" to "领域", "person" to "人物")

    /** 正文 → 要点：去掉开头的 `- ` / `* `（只剩一个「-」的行也算空），空行丢掉。 */
    fun bodyLines(body: String): List<String> = body.split("\n").mapNotNull { raw ->
        var s = raw.trim { it == ' ' || it == '\t' }
        if (s == "-" || s == "*") return@mapNotNull null
        if (s.startsWith("- ") || s.startsWith("* ")) s = s.drop(2).trim { it == ' ' || it == '\t' }
        s.ifEmpty { null }
    }

    fun isEmpty(m: MemoryItemDTO): Boolean = bodyLines(m.body).isEmpty()

    /** 服务端 ISO 时间 → Instant（带不带小数秒都认）。 */
    fun parseIso(s: String): Instant? =
        runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull() ?: runCatching { Instant.parse(s) }.getOrNull()

    /** 更新时间 →「今天更新 / 昨天更新 / N 天前更新 / 09-07 更新 / 2025-12-31 更新」；解不开回空串。按 [zone] 数天。 */
    fun updatedText(iso: String, now: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
        val d = parseIso(iso) ?: return ""
        val day = d.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        val diff = ChronoUnit.DAYS.between(day, today)
        if (diff <= 0) return "今天更新"
        if (diff == 1L) return "昨天更新"
        if (diff < 7) return "$diff 天前更新"
        val md = String.format(Locale.ROOT, "%02d-%02d", day.monthValue, day.dayOfMonth)
        return if (today.year == day.year) "$md 更新" else "${day.year}-$md 更新"
    }

    private fun JsonObject.s(k: String): String = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: ""

    /** 回包里的一项 → DTO（缺 id 回 null，其余缺了给空串）。 */
    fun item(any: JsonElement?): MemoryItemDTO? {
        val o = any as? JsonObject ?: return null
        val id = o.s("id").ifEmpty { return null }
        return MemoryItemDTO(id, o.s("kind"), o.s("title"), o.s("summary"), o.s("body"), o.s("updated_at"), o.s("source"))
    }

    /** `{items:[…]}` → 列表；没有 items 回 null（= 读不懂，不当成「没有记忆」）。 */
    fun items(json: JsonObject?): List<MemoryItemDTO>? {
        val arr = json?.get("items") as? JsonArray ?: return null
        return arr.mapNotNull { item(it) }
    }

    /** 一次写的结果落档。`status` null = 没连上。 */
    fun write(status: Int?, json: JsonObject?): MemoryWrite {
        status ?: return MemoryWrite.Failed("连不上服务器")
        if (status in 200..299) {
            val list = json?.get("changed") as? JsonArray
            val first = list?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { (it["deleted"] as? JsonPrimitive)?.booleanOrNull != true }
                ?.let { (it["id"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
            return MemoryWrite.Ok(
                item = item(json?.get("item")), items = items(json), changed = list?.size,
                full = (json?.get("full") as? JsonPrimitive)?.booleanOrNull ?: false,
                skipped = (json?.get("skipped") as? JsonPrimitive)?.intOrNull ?: 0,
                firstChanged = first,
            )
        }
        if (status == 409 && (json?.get("state") as? JsonPrimitive)?.contentOrNull == "memory_changed") {
            item(json["current"])?.let { return MemoryWrite.Conflict(it) }
        }
        val detail = ((json?.get("detail") as? JsonPrimitive)?.contentOrNull ?: "").trim()
        return MemoryWrite.Failed(detail.ifEmpty { "没保存成，稍后再试" })
    }

    fun importedText(changed: Int?): String =
        if ((changed ?: 0) > 0) "已导入，更新了 ${changed ?: 0} 条记忆" else "秘书觉得没什么需要改的"

    /** 记忆满了、要新建的没建成那句（设计侧 236 字面）。 */
    const val FULL_TEXT = "没记上：记忆满了，200 条是上限。秘书只能改已有的，这句要新加一条 —— 删掉几条不用的再说一次。"

    /** 导入碰上满 200 条那句（设计侧 238；warning 一档，局部失败不是出错）。 */
    fun importFullText(skipped: Int): String =
        "导入了一部分：记忆满了，有 $skipped 条没建上。改已有的那几条都记上了；删掉几条不用的，再导入一次能补上。"

    fun toldText(changed: Int?): String =
        if ((changed ?: 0) > 0) "已更新 ${changed ?: 0} 条记忆" else "秘书觉得没什么需要改的"
}
