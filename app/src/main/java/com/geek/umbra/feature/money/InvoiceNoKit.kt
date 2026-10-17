// 发票号重号那一格的纯判据（B2d② / businessLedger.inlineValidation）—— 照抄 iOS Features/Money/InvoiceNoKit.swift。
//
// 同一张发票被记了两笔，在屏上看不出来 —— 两笔账各自都对，只是指着同一张票，汇算时会被算两遍。
// > 重复不是一个错误的值，它是两个各自正确的值之间的关系。
package com.geek.umbra.feature.money

import java.time.Instant
import java.time.ZoneOffset

/** 查回来的那一笔（服务端只给那句话要的几件）。iOS: InvoiceNoKit.swift:18 */
data class InvoiceDup(
    val id: String,
    val atMs: Long,
    val tzOffsetMin: Int,
    val cents: Long,
    val merchant: String,
)

/** iOS: InvoiceNoKit.swift:26 */
object InvoiceNoKit {
    /** 停手多久去查一次。⚠️ 不是按一次键查一次：20 位的号敲完是 20 次请求，其中 19 次问的是没输完的号。两端同值。 */
    const val DEBOUNCE_MS = 450L

    /**
     * 要不要发这一次查。⚠️ 空号不查（把所有空值都判成重复的校验 = 这一格永远标红）；只在经营账本上查。
     * iOS 用 .whitespaces（Zs 类 + 制表符，不含换行），这里同口径。iOS: InvoiceNoKit.swift:39
     */
    fun shouldCheck(no: String, isBiz: Boolean): Boolean =
        isBiz && no.trim { it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }.isNotEmpty()

    /** 那句话里的日期（month to day）。⚠️ 按那一笔自己的时区算，不按看它的人的。iOS: InvoiceNoKit.swift:48 */
    fun dupDate(dup: InvoiceDup?): Pair<Int, Int>? {
        if (dup == null) return null
        val d = Instant.ofEpochMilli(dup.atMs + dup.tzOffsetMin.toLong() * 60_000).atOffset(ZoneOffset.UTC)
        return d.monthValue to d.dayOfMonth
    }

    /** 那一句话。两端逐字同源；必须带日期 —— 少了日期，人只能一条一条翻。 */
    fun dupText(month: Int, day: Int): String = "这个号 $month 月 $day 日已经用过"

    /**
     * ⚠️⚠️ 重号不拦保存（sam 2026-09-16 推翻了「只拦保存」：一张发票分摊成两笔是合法用法，
     * 拦了人会把号删掉再存，重复反而更查不出来）。这个常量是给这条推翻留的宿主。iOS: InvoiceNoKit.swift:78
     */
    const val DUP_BLOCKS_SAVE = false
}
