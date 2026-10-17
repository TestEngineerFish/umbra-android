package com.geek.umbra.feature.money

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** iOS Tools/swifttests/ReceiptKitTests.swift 逐条照抄（G11 / G5）。 */
class ReceiptKitTest {
    private val TZ = 480

    /** 某天本地 00:00（默认东八区）的 epoch 毫秒。 */
    private fun at(y: Int, m: Int, d: Int, tz: Int = 480): Long =
        LocalDate.of(y, m, d).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() - tz.toLong() * 60_000

    /** 季度：四个边界月各一条；按这一笔自己的时区算。 */
    @Test fun quarters() {
        for ((m, q) in listOf(1 to 1, 3 to 1, 4 to 2, 6 to 2, 7 to 3, 9 to 3, 10 to 4, 12 to 4)) {
            val got = ReceiptKit.quarterOf(at(2026, m, 15), TZ)
            assertEquals("$m 月算成了第 ${got.second} 季", q, got.second)
            assertEquals("$m 月的年份算错了", 2026, got.first)
        }
        val boundary = at(2026, 7, 1, tz = 480)
        assertEquals("东八区的 7-1 该是三季度", 3, ReceiptKit.quarterOf(boundary, 480).second)
        assertEquals("同一时刻在西五区还是 6 月 30 日 —— 季度该按这一笔自己的时区算", 2, ReceiptKit.quarterOf(boundary, -300).second)
    }

    /** 季末那一刻与截止日。 */
    @Test fun quarterEndAndDue() {
        assertEquals("三季度末算偏了", at(2026, 10, 1) - 1, ReceiptKit.quarterEndMs(2026, 3, TZ))
        assertEquals("四季度末没跨年", at(2027, 1, 1) - 1, ReceiptKit.quarterEndMs(2026, 4, TZ))
        val due = ReceiptKit.dueDate(at(2026, 8, 10), TZ)
        assertEquals("三季度的截止月该是 9 月", 9, due.first)
        assertEquals("三季度的截止日该是 30 号", 30, due.second)
        val due4 = ReceiptKit.dueDate(at(2026, 11, 10), TZ)
        assertEquals("四季度的截止月该是 12 月", 12, due4.first)
        assertEquals("四季度的截止日该是 31 号", 31, due4.second)
    }

    /** 季末前 5 天转琥珀，而且到期之后不往红走。 */
    @Test fun tone() {
        val e = at(2026, 8, 10)
        assertEquals("离季末还早就该是中性那一档", true, ReceiptKit.pendingTone(e, TZ, at(2026, 9, 1)) == ReceiptKit.Tone.Quiet)
        assertEquals("季末前 5 天该转琥珀", true, ReceiptKit.pendingTone(e, TZ, at(2026, 9, 27)) == ReceiptKit.Tone.Amber)
        assertEquals("过了季末不该再往别的档走", true, ReceiptKit.pendingTone(e, TZ, at(2026, 11, 1)) == ReceiptKit.Tone.Amber)
    }

    /** 月份区间：补零那一条最要命。 */
    @Test fun ranges() {
        assertEquals("一季度起月没补零", "2026-01", ReceiptKit.quarterRange(2026, 1).first)
        assertEquals("一季度末月", "2026-03", ReceiptKit.quarterRange(2026, 1).second)
        assertEquals("三季度起月", "2026-07", ReceiptKit.quarterRange(2026, 3).first)
        assertEquals("三季度末月", "2026-09", ReceiptKit.quarterRange(2026, 3).second)
        assertEquals("四季度末月", "2026-12", ReceiptKit.quarterRange(2026, 4).second)
        for (q in 1..4) {
            val r = ReceiptKit.quarterRange(2026, q)
            assertEquals("起月格式不是 YYYY-MM", 7, r.first.length)
            assertEquals("末月格式不是 YYYY-MM", 7, r.second.length)
            assertEquals("起月不小于末月 —— 字典序就是时间序这个前提破了", true, r.first < r.second)
        }
    }

    /** 往季（C8 / 设计侧 215 §四⒜）。 */
    @Test fun pastQuarters() {
        val nowQ3 = at(2026, 8, 20, tz = TZ)
        assertEquals("上界不是本季末月", "2026-09", ReceiptKit.pendingThrough(nowQ3, TZ))
        assertEquals("一季度的上界", "2026-03", ReceiptKit.pendingThrough(at(2026, 1, 3, tz = TZ), TZ))
        assertEquals("本季那一笔没进本季组", ReceiptKit.PendingBucket.Current, ReceiptKit.bucket("pending", at(2026, 8, 1, tz = TZ), TZ, nowQ3))
        assertEquals("往季那一笔没进往季组", ReceiptKit.PendingBucket.Past, ReceiptKit.bucket("pending", at(2026, 2, 1, tz = TZ), TZ, nowQ3))
        assertEquals("跨年那一笔没进往季组", ReceiptKit.PendingBucket.Past, ReceiptKit.bucket("pending", at(2025, 11, 1, tz = TZ), TZ, nowQ3))
        assertEquals("未来季那一笔进了某一组", ReceiptKit.PendingBucket.Future, ReceiptKit.bucket("pending", at(2026, 11, 1, tz = TZ), TZ, nowQ3))
        assertEquals("有票的也进了这一筛", ReceiptKit.PendingBucket.NotPending, ReceiptKit.bucket("has", at(2026, 8, 1, tz = TZ), TZ, nowQ3))

        assertEquals("往季那一行的季度标", "2026 年第 1 季", ReceiptKit.quarterLabel(2026, 1))
        assertEquals("又写成了「上季」", false, ReceiptKit.quarterLabel(2025, 4).contains("上季"))

        assertEquals("有往季那一句", "7 笔待补票 · 含往季 3 笔", ReceiptKit.pendingLabel(7, 3))
        assertEquals("没有往季却还在念含往季", "4 笔待补票", ReceiptKit.pendingLabel(4, 0))

        assertEquals("截断那半句的字面", " · 只列出了最近的 1000 笔", ReceiptKit.truncatedSuffix(1000))
        assertEquals("截断那半句贴在总数旁边时读不通", "1200 笔 · 只列出了最近的 1000 笔", "1200 笔" + ReceiptKit.truncatedSuffix(1000))

        assertEquals("空态那句", "每一笔都有票", ReceiptKit.PENDING_EMPTY)
        assertEquals("空态那句还带着「本季」", false, ReceiptKit.PENDING_EMPTY.contains("本季"))
    }
}
