// 常用语里的占位（umbra-pc#19 最小集）：复制那一刻展开 —— 对应 iOS Features/Me/PhraseVars.swift。
//
//   {date}  {date:格式}   今天，默认 YYYY-MM-DD
//   {time}  {time:格式}   现在，默认 HH:mm:ss
//   {clipboard}           复制前剪贴板里的文字
//
// 格式字段只认 YYYY / YY / MM / DD / HH(24) / mm / ss / SSS / ddd（中文星期「日一二三四五六」），长的先匹配；
// 认不出的花括号原样留着。⚠️ 两端展开结果必须**逐字相同** —— 照着 PC 60a145b 的 phraseVars.ts 逐条对，
// 用例期望值是 node 直接跑 PC 那份取的（iOS Tools/swifttests/phrase_vars_fixtures.mjs）：
//   · 同一条正则（java.util.regex 与 JS 一样按 UTF-16 走），`{date:}` 回默认、`{date:{x}` 也是一个占位、`{clipboard:}` 不认；
//   · 一遍扫完，替换进去的字不再扫第二遍；不认 `$&` / `$1`（JS 函数替换同样不认）；
//   · `{clipboard}` 只在真用到时读一次。
// ⚠️ 日期不许用 DateTimeFormatter 的本地化格式（跟用户历法 / 12 小时制走）：用 java.time 的 ISO 公历取分量、手工补零。
// 纯 Kotlin，读剪贴板在 Android 那一侧（PhraseHandout），这里只收闭包。
package com.geek.umbra.feature.phrases

import java.time.Instant
import java.time.ZoneId

object PhraseVars {
    /** 占位的正则，与 PC `PHRASE_VAR` 逐字相同。 */
    private val varPattern = Regex("""\{(date|time|clipboard)(?::([^}]*))?\}""")

    /** 格式字段。顺序就是优先级：YYYY 排在 YY 前面，「YYYYY」才会是「2026Y」。 */
    private val fieldPattern = Regex("YYYY|SSS|ddd|YY|MM|DD|HH|mm|ss")

    /** 星期几：java.time 的 dayOfWeek 1 = 周一 … 7 = 周日；JS getDay() 0 = 周日。 */
    private val weekdayNames = listOf("日", "一", "二", "三", "四", "五", "六")

    /** 按格式串格式化一个时刻（毫秒本来就是整毫秒，同 JS Date；时区由调用方给，用例才钉得住纽约 / 加德满都）。 */
    fun formatNow(fmt: String, nowMs: Long, tz: ZoneId): String {
        val z = Instant.ofEpochMilli(nowMs).atZone(tz)
        val ms = Math.floorMod(nowMs, 1000L).toInt()
        val year = z.year.toString()
        val fields = mapOf(
            "YYYY" to year,
            // PC 是 `String(year).slice(-2)`：取字符串末两位。
            "YY" to year.takeLast(2),
            "MM" to pad2(z.monthValue), "DD" to pad2(z.dayOfMonth),
            "HH" to pad2(z.hour), "mm" to pad2(z.minute), "ss" to pad2(z.second),
            "SSS" to (if (ms < 10) "00$ms" else if (ms < 100) "0$ms" else "$ms"),
            "ddd" to weekdayNames[z.dayOfWeek.value % 7],
        )
        return fieldPattern.replace(fmt) { m -> fields[m.value] ?: m.value }
    }

    /** 这段常用语里有没有要展开的占位（与 PC hasPhraseVars 同义）。 */
    fun hasVars(text: String): Boolean = varPattern.containsMatchIn(text)

    /** 展开占位。[clipboard] **只在真用到 {clipboard} 时调一次**。 */
    fun expand(text: String, nowMs: Long, tz: ZoneId, clipboard: () -> String): String {
        var clip: String? = null
        // Regex.replace(transform) 不解释 $ 记号、替换进去的字不再参与匹配 —— 与 JS String.replace(re, fn) 一致。
        return varPattern.replace(text) { m ->
            val name = m.groupValues[1]
            val fmt: String? = m.groups[2]?.value // 写没写冒号要和「冒号后面是空的」分开
            when (name) {
                "date" -> formatNow(if (fmt.isNullOrEmpty()) "YYYY-MM-DD" else fmt, nowMs, tz)
                "time" -> formatNow(if (fmt.isNullOrEmpty()) "HH:mm:ss" else fmt, nowMs, tz)
                "clipboard" -> if (fmt != null) m.value else (clip ?: clipboard().also { clip = it })
                else -> m.value
            }
        }
    }

    /** readClip 的结果：展开成什么、是不是因为敏感值留空、是不是读被拒了。 */
    data class Clip(val text: String, val withheld: Boolean, val denied: Boolean)

    /**
     * {clipboard} 该展开成什么 —— 剪贴板里是保险箱的敏感值、或判不准时，一律是空串，而且根本不去读
     * （与 PC ea6b5c5 readClipForPhrase 同一取向）。isSensitive 抛错 = 判不准 → 按敏感处理。
     * hasText 只看类型不读内容；有文字、读回来却是 null = 被拒了（Android 上通常不会发生，形状照 iOS 留着）。
     * iOS: PhraseVars.swift:135
     */
    fun readClip(isSensitive: () -> Boolean, hasText: () -> Boolean, read: () -> String?): Clip {
        val sensitive = try { isSensitive() } catch (_: Throwable) { true }
        if (sensitive) return Clip("", withheld = true, denied = false)
        if (!hasText()) return Clip("", withheld = false, denied = false)
        val text = read() ?: return Clip("", withheld = false, denied = true)
        return Clip(text, withheld = false, denied = false)
    }

    private fun pad2(n: Int) = if (n in 0..9) "0$n" else "$n"
}
