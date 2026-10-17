// 首次拉历史时，怎么和「历史回来之前已经推到的块」合并（iOS: Features/Chat/ChatHistoryMerge.swift，umbra-ios#32）。
//
// 纯判据，用例照抄 iOS Tools/swifttests/ChatHistoryMergeTests.swift。
//
// 坑：推送分支会往还没加载过历史的会话里直接追加块（设备会话的 device_message、握手补发的卡、task_update…）。
// 原来「store 里有块就不铺历史」二选一 —— 会话里已有一条推送，整批历史被丢、游标照样前移，最近 40 条从此翻不出来。
// 合并规则：结果 = 历史 + 「历史里没有的早到块」。有 id 的按 id；没有 id 但会落库的 device_message / 秘书推送
// 按「角色 + 正文 + 时刻」按位置抵消；其余（卡片、错误卡、还没认领到 id 的我方消息）一律留下。
package com.geek.umbra.feature.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.abs

object ChatHistoryMerge {
    /** 一块早到的块，只留合并要看的三样。echoKey == null = 不进 messages 表的块；at = 广播时刻（epoch 秒）。 */
    data class Early(val serverId: Long? = null, val echoKey: String? = null, val at: Double? = null)

    /** 回显在时刻上的容差（秒）：服务端先落库（截到整秒）再广播。往前留 1 秒，往后留 10 秒（宁宽勿窄）。iOS: ChatHistoryMerge.swift:62 */
    const val ECHO_WINDOW = 10.0
    const val ECHO_LEAD = 1.0

    /** 推送时刻和历史行时刻对不对得上（任一边缺时刻 = 不知道，按对得上算，退回只比正文）。 */
    fun timesMatch(push: Double?, row: Double?): Boolean {
        if (push == null || row == null) return true
        return push >= row - ECHO_LEAD && push - row <= ECHO_WINDOW
    }

    private val epochRe = Regex("""^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(\.\d+)?(Z|[+-]\d{2}:?\d{2})?$""")

    /**
     * 服务端两种时刻写法 → epoch 秒（UTC）：历史行 `2026-10-06 12:34:56`（无时区 = UTC），
     * 广播 `2026-10-06T12:34:56.123456+00:00`。解不出返回 null（调用方退回只比正文）。
     * ⚠️ 手解、不用系统 ISO 解析器：它不认空格分隔、不认无时区，正好是历史那一种（iOS: ChatHistoryMerge.swift:77）。
     */
    fun epoch(s: String?): Double? {
        if (s == null) return null
        val m = epochRe.matchEntire(s) ?: return null
        val g = m.groupValues
        val base = runCatching {
            LocalDateTime.of(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
                .toEpochSecond(ZoneOffset.UTC).toDouble()
        }.getOrNull() ?: return null
        var t = base + ("0" + g[7]).toDoubleOrNull().let { it ?: 0.0 }
        val tz = g[8]
        if (tz.isNotEmpty() && tz != "Z") {
            val sign = if (tz.startsWith("-")) -1 else 1
            val digits = tz.drop(1).filter { it.isDigit() }
            if (digits.length != 4) return null
            val hh = digits.take(2).toDouble(); val mm = digits.takeLast(2).toDouble()
            t -= sign * (hh * 3600 + mm * 60)
        }
        return t
    }

    /**
     * device_message 广播里的消息 id（umbra#104：落库成功时带 `"message_id": <整数>`）。
     * 只认整数且 > 0；字符串、null、布尔、小数一律 null。⚠️ 键名是 message_id，不是 id。
     */
    fun pushId(json: JsonObject): Long? {
        val raw = json["message_id"] as? JsonPrimitive ?: return null
        if (raw.isString) return null
        if (raw.booleanOrNull != null) return null
        val d = raw.doubleOrNull ?: return null
        if (d != Math.rint(d) || abs(d) >= 9e15) return null
        val n = d.toLong()
        return if (n > 0) n else null
    }

    /** 「角色 + 正文」拼成的键，中间用 NUL 隔开（正文里不会有它）。 */
    fun echoKey(role: String, text: String): String = role + "\u0000" + text

    /**
     * 早到块里**要留下**的那些下标（按原顺序）。
     * 正文抵消按位置认：只认「早到正文序列的最长前缀 == 历史正文序列的等长后缀」，且时刻逐条对得上
     * （iOS 台账 `chat-history-echo-dedupe-drops-fresh-push`、`chat-echo-dedupe-tail-collision`）。
     */
    fun keep(early: List<Early>, historyIds: Set<Long>, historyEchoKeys: List<String>, historyEchoTimes: List<Double?> = emptyList()): List<Int> {
        val echoIdx = early.indices.filter { early[it].serverId == null && early[it].echoKey != null }
        val echoSeq = echoIdx.map { early[it].echoKey!! }
        // 行时刻和正文键对不齐时整组当没有：错位比不给更糟。
        val rowTimes: List<Double?> = if (historyEchoTimes.size == historyEchoKeys.size) historyEchoTimes else List(historyEchoKeys.size) { null }
        fun aligned(k: Int): Boolean {
            val base = historyEchoKeys.size - k
            for (j in 0 until k) {
                if (echoSeq[j] != historyEchoKeys[base + j] || !timesMatch(early[echoIdx[j]].at, rowTimes[base + j])) return false
            }
            return true
        }
        var k = minOf(echoSeq.size, historyEchoKeys.size)
        while (k > 0 && !aligned(k)) k--
        val dropped = echoIdx.take(k).toSet()
        val out = mutableListOf<Int>()
        early.forEachIndexed { i, e ->
            val id = e.serverId
            if (id != null) {
                if (!historyIds.contains(id)) out += i
            } else if (!dropped.contains(i)) out += i
        }
        return out
    }
}

/**
 * 拉历史之后翻页游标怎么动（iOS: ChatHistoryMerge.swift:188 `ChatHistoryPaging`）。
 * 坑：读失败原来被 `?? []` 说成「空」，一次网络抖动成了永久的「没有更早的消息了」。现在 null = 没拉到。
 */
object ChatHistoryPaging {
    data class Cursor(val oldestId: Long?, val hasMore: Boolean, val pagedOnce: Boolean, val failed: Boolean)

    /** 一页的条数。和调用方的 limit 是同一个数。 */
    const val PAGE_SIZE = 40

    /** 首屏 / 重拉那一批回来之后。ids 老→新，null = 没拉到；merged=false（主会话重登那一趟）什么都不动。 */
    fun afterFirst(ids: List<Long>?, merged: Boolean, state: Cursor): Cursor {
        if (!merged) return state
        if (ids == null) return state.copy(failed = true)
        var s = state.copy(failed = false)
        val oldest = ids.firstOrNull()
        s = if (oldest != null) {
            val more = ids.size >= PAGE_SIZE
            s.copy(oldestId = oldest, hasMore = more, pagedOnce = if (more) true else s.pagedOnce)
        } else s.copy(hasMore = false)
        return s
    }

    /** 往前翻一页回来之后。null = 没拉到：不判到头，游标不动，标失败。 */
    fun afterOlder(ids: List<Long>?, state: Cursor): Cursor {
        if (ids == null) return state.copy(failed = true)
        val s = state.copy(failed = false)
        val oldest = ids.firstOrNull() ?: return s.copy(hasMore = false)
        return s.copy(oldestId = oldest, hasMore = if (ids.size < PAGE_SIZE) false else s.hasMore)
    }
}
