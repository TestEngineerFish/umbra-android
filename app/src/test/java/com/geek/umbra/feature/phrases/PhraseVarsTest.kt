package com.geek.umbra.feature.phrases

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

/**
 * PhraseVars 的行为用例 —— 照抄 iOS Tools/swifttests/PhraseVarsTests.swift。
 * 第一组夹具由 `phrase_vars_fixtures.mjs` 直接执行 PC（60a145b）的 phraseVars.ts 取得（2026-10-17 重跑，与 iOS 那份逐行相同）：
 * 要证明的是「同一条常用语两端展开逐字相同」。
 */
class PhraseVarsTest {
    @Test fun parity() {
        for (f in phraseVarFixtures) {
            var calls = 0
            val got = PhraseVars.expand(f.text, f.nowMs, ZoneId.of(f.tz)) { calls += 1; f.clip }
            assertEquals("${f.name}（${f.tz}）：与 PC 展开不一样", f.want, got)
            assertEquals("${f.name}：读剪贴板的次数和 PC 不一样", f.calls, calls)
            val has = Regex("""\{(date|time|clipboard)(:[^}]*)?\}""").containsMatchIn(f.text)
            assertEquals("${f.name}：hasVars", has, PhraseVars.hasVars(f.text))
        }
        // 毫秒尾巴：整毫秒截断（iOS 的 .9996 微秒那条在 Android 上不存在 —— 时刻本来就是整毫秒）。
        val sh = ZoneId.of("Asia/Shanghai")
        assertEquals("07.999", PhraseVars.formatNow("ss.SSS", 1_791_248_707_999L, sh))
        assertEquals("07.123", PhraseVars.formatNow("ss.SSS", 1_791_248_707_123L, sh))
    }

    /** 不跟用户的历法 / 区域走：公历四位年、24 小时制。 */
    @Test fun calendar() {
        assertEquals("2026-10-06 17:05:07", PhraseVars.expand("{date} {time}", 1_791_277_507_000L, ZoneId.of("Asia/Shanghai")) { "" })
    }

    @Test fun sensitive() {
        var reads = 0
        val read: () -> String? = { reads += 1; "hunter2" }
        val s = PhraseVars.readClip({ true }, { true }, read)
        assertEquals("", s.text); assertEquals(true, s.withheld); assertEquals(0, reads)
        val n = PhraseVars.readClip({ false }, { true }, read)
        assertEquals("hunter2", n.text); assertEquals(false, n.withheld); assertEquals(1, reads)
        val f = PhraseVars.readClip({ throw IllegalStateException("判不准") }, { true }, read)
        assertEquals("", f.text); assertEquals(true, f.withheld); assertEquals(1, reads)
        var noneReads = 0
        val none = PhraseVars.readClip({ false }, { false }, { noneReads += 1; null })
        assertEquals("", none.text); assertEquals(false, none.denied); assertEquals(0, noneReads)
        val denied = PhraseVars.readClip({ false }, { true }, { null })
        assertEquals("", denied.text); assertEquals(true, denied.denied); assertEquals(false, denied.withheld)
        assertEquals(false, n.denied); assertEquals(false, s.denied)
        var askedHasText = 0
        PhraseVars.readClip({ true }, { askedHasText += 1; true }, read)
        assertEquals("判出敏感：连有没有文字都不问", 0, askedHasText)
        var expandReads = 0
        val out = PhraseVars.expand("密码是 {clipboard}，{date:YYYY}", 1_791_248_707_000L, ZoneId.of("Asia/Shanghai")) {
            PhraseVars.readClip({ true }, { true }, { expandReads += 1; "hunter2" }).text
        }
        assertEquals("密码是 ，2026", out); assertEquals(0, expandReads)
    }
}

/** (名字, 时区, nowMs, 常用语原文, 剪贴板里的字, PC 展开结果, PC 读了几次剪贴板) —— 由 iOS 夹具机械转写，别手改。 */
internal data class PvFix(val name: String, val tz: String, val nowMs: Long, val text: String, val clip: String, val want: String, val calls: Int)

internal val phraseVarFixtures: List<PvFix> = listOf(
    PvFix("PC 单测：默认格式", "Asia/Shanghai", 1791248707000L, "今天 {date}，{time}", "剪贴板里的", "今天 2026-10-06，09:05:07", 0),
    PvFix("PC 单测：中文年月日 + 星期", "Asia/Shanghai", 1791248707000L, "{date:YYYY年MM月DD日 周ddd}", "", "2026年10月06日 周二", 0),
    PvFix("PC 单测：{time:HH:mm:ss}", "Asia/Shanghai", 1791248707000L, "{time:HH:mm:ss}", "", "09:05:07", 0),
    PvFix("PC 单测：{clipboard} 两处只读一次", "Asia/Shanghai", 1791248707000L, "A{clipboard}B{clipboard}", "剪贴板里的", "A剪贴板里的B剪贴板里的", 1),
    PvFix("PC 单测：没有占位就不读剪贴板", "Asia/Shanghai", 1791248707000L, "没有占位", "剪贴板里的", "没有占位", 0),
    PvFix("PC 单测：认不出的花括号原样留着", "Asia/Shanghai", 1791248707000L, "{name} {query} {clipboard:x} {date", "剪贴板里的", "{name} {query} {clipboard:x} {date", 0),
    PvFix("PC 单测：YY/MM/DD", "Asia/Shanghai", 1791248707000L, "{date:YY/MM/DD}", "", "26/10/06", 0),
    PvFix("毫秒三位补零", "Asia/Shanghai", 1791248707007L, "{time:HH:mm:ss.SSS}", "", "09:05:07.007", 0),
    PvFix("毫秒 999", "Asia/Shanghai", 1791302399999L, "{time:SSS}", "", "999", 0),
    PvFix("YYYYY = YYYY + Y", "Asia/Shanghai", 1791248707000L, "{date:YYYYY}", "", "2026Y", 0),
    PvFix("YYY = YY + Y", "Asia/Shanghai", 1791248707000L, "{date:YYY}", "", "26Y", 0),
    PvFix("DDD / dddd / SSSS / mmm", "Asia/Shanghai", 1791248707042L, "{date:DDD dddd SSSS mmm}", "", "06D 二d 042S 05m", 0),
    PvFix("单字母不认（M/D/H/m/s 原样）", "Asia/Shanghai", 1791248707000L, "{date:M/D H:m:s}", "", "M/D H:m:s", 0),
    PvFix("小写 yyyy / dd 不认", "Asia/Shanghai", 1791248707000L, "{date:yyyy-dd}", "", "yyyy-dd", 0),
    PvFix("日期占位里也能写时分秒", "Asia/Shanghai", 1791248707000L, "{date:YYYY-MM-DD HH:mm}", "", "2026-10-06 09:05", 0),
    PvFix("时间占位里也能写年月日", "Asia/Shanghai", 1791248707000L, "{time:YYYY/MM/DD}", "", "2026/10/06", 0),
    PvFix("空格式 = 默认（{date:} / {time:}）", "Asia/Shanghai", 1791248707000L, "{date:}|{time:}", "", "2026-10-06|09:05:07", 0),
    PvFix("二〇〇五年的 YY 补零", "Asia/Shanghai", 1109869323000L, "{date:YY-M-DD}", "", "05-M-04", 0),
    PvFix("周日", "Asia/Shanghai", 1791043200000L, "周{date:ddd}", "", "周日", 0),
    PvFix("周六 23:59:59.999", "Asia/Shanghai", 1791647999999L, "周{date:ddd} {time:HH:mm:ss.SSS}", "", "周六 23:59:59.999", 0),
    PvFix("周一", "Asia/Shanghai", 1791172800000L, "{date:ddd}", "", "一", 0),
    PvFix("跨年夜", "Asia/Shanghai", 1798732799000L, "{date} {time}", "", "2026-12-31 23:59:59", 0),
    PvFix("闰日", "Asia/Shanghai", 1835366400000L, "{date:YYYY-MM-DD ddd}", "", "2028-02-29 二", 0),
    PvFix("大写 {DATE} 不认", "Asia/Shanghai", 1791248707000L, "{DATE} {Time} {CLIPBOARD}", "X", "{DATE} {Time} {CLIPBOARD}", 0),
    PvFix("花括号里带空格不认", "Asia/Shanghai", 1791248707000L, "{ date} {date }", "", "{ date} {date }", 0),
    PvFix("双层花括号：外层留着", "Asia/Shanghai", 1791248707000L, "{{date}}", "", "{2026-10-06}", 0),
    PvFix("{date:{x} 也是一个占位（[^}]* 吃得下左花括号）", "Asia/Shanghai", 1791248707000L, "{date:{x}", "", "{x", 0),
    PvFix("格式在第一个右花括号处截断", "Asia/Shanghai", 1791248707000L, "{date:a}b}", "", "ab}", 0),
    PvFix("{clipboard:} 空格式也不是这套语法", "Asia/Shanghai", 1791248707000L, "{clipboard:}", "X", "{clipboard:}", 0),
    PvFix("{clipboard:x} 不读剪贴板", "Asia/Shanghai", 1791248707000L, "{clipboard:x}", "X", "{clipboard:x}", 0),
    PvFix("剪贴板里的 {date} 不再被展开", "Asia/Shanghai", 1791248707000L, "[{clipboard}]", "{date}{time}", "[{date}{time}]", 1),
    PvFix("剪贴板里的 \$& / \$1 原样（JS 函数替换不认 \$）", "Asia/Shanghai", 1791248707000L, "{clipboard}", "\$&\$1\$\$", "\$&\$1\$\$", 1),
    PvFix("正文里的 \$ 也原样", "Asia/Shanghai", 1791248707000L, "\$&{date}\$1", "", "\$&2026-10-06\$1", 0),
    PvFix("剪贴板是空的", "Asia/Shanghai", 1791248707000L, "<{clipboard}>", "", "<>", 1),
    PvFix("表情与多字节字符前后", "Asia/Shanghai", 1791248707000L, "😀{date:ddd}👨‍👩‍👧{clipboard}é", "🇨🇳", "😀二👨‍👩‍👧🇨🇳é", 1),
    PvFix("空串", "Asia/Shanghai", 1791248707000L, "", "X", "", 0),
    PvFix("换行与制表符", "Asia/Shanghai", 1791248707000L, "\n{time:HH}\t{clipboard}\n", "a\nb", "\n09\ta\nb\n", 1),
    PvFix("一串都是占位", "Asia/Shanghai", 1791248707123L, "{date}{time}{clipboard}{date:SSS}", "C", "2026-10-0609:05:07C123", 1),
    PvFix("纽约：同一时刻在纽约是前一天", "America/New_York", 1791248707000L, "{date} {time} 周{date:ddd}", "", "2026-10-05 21:05:07 周一", 0),
    PvFix("纽约：春令跳过之后的 3:00", "America/New_York", 1772953200000L, "{date} {time}", "", "2026-03-08 03:00:00", 0),
    PvFix("纽约：秋令重复的 1:30（取 EDT）", "America/New_York", 1793511000000L, "{time}", "", "01:30:00", 0),
    PvFix("半点时区", "Asia/Kolkata", 1791248707000L, "{date} {time}", "", "2026-10-06 06:35:07", 0),
    PvFix("+13:45 时区跨日", "Pacific/Chatham", 1791281100500L, "{date:YYYY-MM-DD ddd HH:mm:ss.SSS}", "", "2026-10-06 二 23:50:00.500", 0),
)
