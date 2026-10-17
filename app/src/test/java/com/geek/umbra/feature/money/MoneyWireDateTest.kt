package com.geek.umbra.feature.money

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.chrono.IsoChronology
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale

/**
 * iOS Tools/swifttests/MoneyWireDateTests.swift 逐条照抄（台账 2026-10-01 Money f）。
 *
 * 对抗环境：iOS 把进程地区改成泰国佛历 + 强制 12 小时制。JVM 上能做到的是把默认地区设成
 * Locale("th","TH","TH")（java.util.Calendar.getInstance() 随之变成 BuddhistCalendar、SimpleDateFormat 用泰文数字）。
 * ⚠️ 「强制 12 小时制」在 JVM 上没有对应的进程级开关（HH 永远是 24 小时），那两条环境自检改成注释，不断言。
 */
class MoneyWireDateTest {
    private var saved: Locale = Locale.getDefault()

    @Before fun hostile() {
        saved = Locale.getDefault()
        Locale.setDefault(Locale("th", "TH", "TH"))
    }

    @After fun restore() {
        Locale.setDefault(saved)
    }

    @Test fun wireFormatIgnoresPhoneSettings() {
        // ── 环境自检：这个进程现在是一台佛历的手机 ──
        assertEquals("环境没改成佛历 —— 这一组用例证明不了什么", "buddhist", Calendar.getInstance().calendarType)
        val naive = SimpleDateFormat("yyyy-MM-dd")
        val hostile = naive.parse("2026-09-05")
        val g = GregorianCalendar(java.util.TimeZone.getDefault(), Locale.ROOT).apply { time = hostile }
        assertEquals("裸 DateFormatter 在佛历下应当读出 1483 年（被修之前那一版的坏法）", 1483, g.get(Calendar.YEAR))
        // iOS 这里还有两条：12 小时制下裸 formatter 解不出「2026-09-05 20:30」、写不出「20:30」。
        // JVM 没有「强制 12 小时制」的进程开关（HH 恒为 24 小时），无法造出这个环境，略去。

        val zone = ZoneId.systemDefault()
        val at2030 = LocalDateTime.of(2026, 9, 5, 20, 30).atZone(zone).toInstant().toEpochMilli()

        // ── 本条主体：线上格式不跟手机走 ──
        assertEquals("first_date 被写成了别的历法", "2026-09-05", MoneyWireDate.dateString(at2030))
        assertEquals("time_hhmm 被写成了 12 小时制", "20:30", MoneyWireDate.timeString(at2030))
        assertEquals("ym 用了佛历年份（2569）", "2026-09", MoneyWireDate.ym(at2030))
        val back = MoneyWireDate.parse("2026-09-05", "20:30")
        assertEquals("打开再保存把 first_date 改坏了", "2026-09-05", back?.let(MoneyWireDate::dateString))
        assertEquals("打开再保存把 time_hhmm 改坏了", "20:30", back?.let(MoneyWireDate::timeString))
        assertEquals("end_date 往返", "2026-12-31", MoneyWireDate.parse("2026-12-31")?.let(MoneyWireDate::dateString))
        val jan = LocalDateTime.of(2027, 1, 1, 0, 5).atZone(zone).toInstant().toEpochMilli()
        assertEquals("ym 月份没补零 / 跨年算错", "2027-01", MoneyWireDate.ym(jan))
        assertEquals("零点那一刻写成了 12:05 AM", "00:05", MoneyWireDate.timeString(jan))
        assertEquals("坏字段解出了一个日期", true, MoneyWireDate.parse("2026-13-40", "25:00") == null)
        assertEquals("数字不是 ASCII", true, MoneyWireDate.dateString(at2030).all { it.code < 128 })
        assertEquals("线上口径的历法不是公历", IsoChronology.INSTANCE, MoneyWireDate.chronology)
    }
}
