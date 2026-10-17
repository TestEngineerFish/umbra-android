// 票据状态在流水行上要用到的那点算术（G11 / receiptStatusInList / timeDerivedStateIsNotStored）
// —— 照抄 iOS Features/Money/ReceiptKit.swift。纯 Kotlin。
//
// 那枚徽章的颜色是**算出来的**，不是存下来的：
// > 一个会自己变色的状态不入库；存下来的颜色，永远是算它那一天的颜色。
// 四条约束：① 变的是同一枚徽章，不是出现或消失；② 颜色是强度，文字是事实，文字不跟着变；
// ③ 只有两档，到期之后不再往红走；④ 渲染时算。
package com.geek.umbra.feature.money

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/** iOS: ReceiptKit.swift:27 */
object ReceiptKit {
    /** 季末前几天开始转琥珀。 */
    const val AMBER_DAYS = 5

    /** 待补票那一筛的 limit（iOS: HTTPService.swift:635 pendingLimit）。⚠️ 会静默截断，调用方要问「截了没有」。 */
    const val PENDING_LIMIT = 1000

    /** 这一笔属于哪个季度（y to q）。**按它自己的本地时间，不按看它的人的时区。** iOS: ReceiptKit.swift:32 */
    fun quarterOf(atMs: Long, tzOffsetMin: Int): Pair<Int, Int> {
        val d = Instant.ofEpochMilli(atMs + tzOffsetMin.toLong() * 60_000).atOffset(ZoneOffset.UTC)
        return d.year to ((d.monthValue - 1) / 3 + 1)
    }

    /** 那个季度最后一天的**本地** 23:59:59.999，回 epoch 毫秒。iOS: ReceiptKit.swift:43 */
    fun quarterEndMs(y: Int, q: Int, tzOffsetMin: Int): Long {
        val nextY = if (q == 4) y + 1 else y
        val nextMonth = if (q == 4) 1 else q * 3 + 1
        val utc = LocalDate.of(nextY, nextMonth, 1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        return utc - tzOffsetMin.toLong() * 60_000 - 1
    }

    /** 「待补票」的强度。⚠️ 两档都出徽章、都写「待补票」；过期之后仍是 Amber，不往红走。 */
    enum class Tone { Quiet, Amber }

    /** 这一笔「待补票」今天该是哪一档。iOS: ReceiptKit.swift:59 */
    fun pendingTone(atMs: Long, tzOffsetMin: Int, nowMs: Long): Tone {
        val (y, q) = quarterOf(atMs, tzOffsetMin)
        val end = quarterEndMs(y, q, tzOffsetMin)
        return if (nowMs >= end - AMBER_DAYS.toLong() * 86_400_000) Tone.Amber else Tone.Quiet
    }

    /** 徽章下面那一行日期：「X 月 X 日前补票」的截止日（month to day）。iOS: ReceiptKit.swift:66 */
    fun dueDate(atMs: Long, tzOffsetMin: Int): Pair<Int, Int> {
        val (y, q) = quarterOf(atMs, tzOffsetMin)
        val end = quarterEndMs(y, q, tzOffsetMin)
        val d = Instant.ofEpochMilli(end + tzOffsetMin.toLong() * 60_000).atOffset(ZoneOffset.UTC)
        return d.monthValue to d.dayOfMonth
    }

    /** 「本季」那一筛看的是哪个季度 —— 按今天，不按用户正在看的那个月。 */
    fun currentQuarter(nowMs: Long, tzOffsetMin: Int): Pair<Int, Int> = quarterOf(nowMs, tzOffsetMin)

    /**
     * 一个季度的月份区间（YYYY-MM 两端，含）。
     * ⚠️ 月份必须补零：服务端 `ym >= ?` 比的是字符串，少一个 0 的话 `2026-1` > `2026-09`，整个季度筛出零条。
     * iOS: ReceiptKit.swift:90
     */
    fun quarterRange(y: Int, q: Int): Pair<String, String> {
        val m0 = (q - 1) * 3 + 1
        return String.format(Locale.ROOT, "%04d-%02d", y, m0) to String.format(Locale.ROOT, "%04d-%02d", y, m0 + 2)
    }

    /**
     * 取数口径：截止到本季末的那一个月份。⚠️ 「含往季」= 不传 ym_from、只传 ym_to；上界要留着
     * （周期规则能生成将来日期的行，两个都不传的话底栏那个数会比屏上多）。iOS: ReceiptKit.swift:115
     */
    fun pendingThrough(nowMs: Long, tzOffsetMin: Int): String {
        val (y, q) = currentQuarter(nowMs, tzOffsetMin)
        return quarterRange(y, q).second
    }

    /** 往季那一行标的是哪一季。⚠️ 写年份不写「上季」—— 拖了三个季度的那一笔，「上季」是假的。 */
    fun quarterLabel(y: Int, q: Int): String = "$y 年第 $q 季"

    /** 这一笔属于哪一组。⚠️ 未来季的不进这一筛：一笔还没发生的账谈不上「待补票」。 */
    enum class PendingBucket { Past, Current, Future, NotPending }

    /** iOS: ReceiptKit.swift:134 */
    fun bucket(receiptState: String?, atMs: Long, tzOffsetMin: Int, nowMs: Long): PendingBucket {
        if (receiptState != "pending") return PendingBucket.NotPending
        val a = quarterOf(atMs, tzOffsetMin)
        val b = currentQuarter(nowMs, tzOffsetMin)
        if (a == b) return PendingBucket.Current
        return if (a.first < b.first || (a.first == b.first && a.second < b.second)) PendingBucket.Past else PendingBucket.Future
    }

    /** 底栏那颗钮上那句话。⚠️ past == 0 时后半句不出 —— 「含往季 0 笔」说了等于没说。 */
    fun pendingLabel(total: Int, past: Int): String =
        if (past > 0) "$total 笔待补票 · 含往季 $past 笔" else "$total 笔待补票"

    /** 那一筛被 limit 截断时贴在底栏总数旁边的半句（设计侧 218 §五）。N = 屏上实际列出的行数。 */
    fun truncatedSuffix(shown: Int): String = " · 只列出了最近的 $shown 笔"

    /** 那一筛的空态标题。⚠️ 原来写「本季每一笔都有票」—— 含往季之后那个限定词是假的。 */
    const val PENDING_EMPTY = "每一笔都有票"
}
