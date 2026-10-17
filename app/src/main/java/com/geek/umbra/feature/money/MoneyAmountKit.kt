// 记一笔的金额算式 —— 照抄 iOS Features/Money/MoneyAmountKit.swift。
//
// ⚠️ 纯 Kotlin，不 import android.*：由 MoneyAmountKitTest 真跑。
// iOS 那边原来待在一个 import 了 SwiftUI 的文件里，编不进用例 ——
// 于是从来没有人喂过它一个 17 位的数，直到它在用户手上崩了（umbra-ios#36）。
package com.geek.umbra.feature.money

/**
 * 金额算式（金额框支持直接敲 258/3）。
 *
 * 手写递归下降，只认 + - * / 和括号。规则与 PC money.ts 的 amountToCents 完全一致
 * （两端算出不一样的数才是大事故）：归一化 → 字符白名单 → 求值 → 闸门（≥0 且 ≤ ¥9,999,999.99，**在 Double 上判**）。
 * iOS: MoneyAmountKit.swift:15
 */
object MoneyAmount {

    /** 全角数字/符号、中文标点、¥ 前缀、首尾等号 → 标准算式。iOS: MoneyAmountKit.swift:18 */
    fun normalize(input: String): String {
        val s = StringBuilder()
        for (ch in input) {
            when (ch) {
                in '０'..'９' -> s.append('0' + (ch - '０'))
                '×', 'ｘ', 'Ｘ' -> s.append('*')
                '÷' -> s.append('/')
                '＋' -> s.append('+')
                '－', '−', '–', '—' -> s.append('-')
                '．' -> s.append('.')
                '（' -> s.append('(')
                '）' -> s.append(')')
                // 千分位、货币符号、空格直接拿掉
                '，', ',', '¥', '￥', '元', ' ', '　' -> Unit
                else -> s.append(ch)
            }
        }
        var out = s.toString()
        while (out.startsWith("=")) out = out.substring(1)
        while (out.endsWith("=")) out = out.substring(0, out.length - 1)
        return out
    }

    /** 有没有运算符（决定要不要显示「= ¥86.00」的预览行）。首位的 +/- 不算（iOS dropFirst），首位 * / 算。iOS: MoneyAmountKit.swift:42 */
    fun isExpr(input: String): Boolean {
        val s = normalize(input)
        return s.drop(1).any { it in "+-*/" } || (s.firstOrNull() ?: ' ') in "*/"
    }

    /**
     * 算式/数字 → **整数分**。算不出、负数、超过 ¥9,999,999.99 都回 null ——
     * 一笔上千万的「记账」几乎必然是敲错了，静默收下比当场拒绝更害人。
     *
     * ⚠️⚠️ **0 是合法金额**：导进来的 0 元账（医保全额支付、0 元订单）在编辑时保存钮不能永远灰。
     * ⚠️ 闸门必须和 PC moneyKit.amountToCents 同口径。iOS: MoneyAmountKit.swift:62
     */
    fun cents(input: String): Long? {
        val s = normalize(input)
        if (s.isEmpty() || s.none { it in '0'..'9' }) return null
        // 白名单：归一化后只该剩数字和四则符号，出现别的只能是误触或粘贴错了。
        if (!s.all { it in "0123456789+-*/()." }) return null
        val p = Parser(s)
        val v = p.expr() ?: return null
        if (!p.atEnd || !v.isFinite() || v < 0) return null
        // ⚠️⚠️ 闸门必须在 Double 上判，判完才许转整数（iOS: MoneyAmountKit.swift:69）：
        // iOS 的 Int(Double) 超界会 trap；Kotlin 的 toLong() 超界是静默饱和成 Long.MAX_VALUE ——
        // 不崩但同样错，所以这里也先在 Double 上判。
        val c = roundHalfAwayFromZero(v * 100)
        if (c < 0 || c > 999_999_999.0) return null
        // ⚠️ 一个非零的输入不许被舍成 0（0.004 → 0.4 分 → 0）：「0」和「几乎是 0」在账本上是两句话。
        if (c == 0.0 && v > 0) return null
        return c.toLong()
    }

    /**
     * Swift `Double.rounded()`（.toNearestOrAwayFromZero）的精确等价。
     * 不用 Math.round：它是 floor(x+0.5)，在 0.49999999999999994 这种值上会多进一。
     */
    internal fun roundHalfAwayFromZero(x: Double): Double {
        if (!x.isFinite()) return x
        val a = Math.abs(x)
        val f = Math.floor(a)
        val r = if (a - f >= 0.5) f + 1 else f
        return if (x < 0) -r else r
    }

    /** 递归下降四则运算。带下标游标，不用全局状态。iOS: MoneyAmountKit.swift:91 */
    private class Parser(val chars: String) {
        var i = 0
        val atEnd: Boolean get() = i >= chars.length

        /** expr := term (('+'|'-') term)* */
        fun expr(): Double? {
            var v = term() ?: return null
            while (i < chars.length && (chars[i] == '+' || chars[i] == '-')) {
                val op = chars[i]; i += 1
                val r = term() ?: return null
                v = if (op == '+') v + r else v - r
            }
            return v
        }

        /** term := factor (('*'|'/') factor)* */
        fun term(): Double? {
            var v = factor() ?: return null
            while (i < chars.length && (chars[i] == '*' || chars[i] == '/')) {
                val op = chars[i]; i += 1
                val r = factor() ?: return null
                if (op == '/') {
                    if (r == 0.0) return null   // 除零当场算「算不出」，别放 Infinity 过闸
                    v /= r
                } else {
                    v *= r
                }
            }
            return v
        }

        /** factor := number | '(' expr ')'。不支持一元负号 —— 金额必须是正的，提前拒绝还能少一类奇怪算式。 */
        fun factor(): Double? {
            if (i >= chars.length) return null
            if (chars[i] == '(') {
                i += 1
                val v = expr() ?: return null
                if (i >= chars.length || chars[i] != ')') return null
                i += 1
                return v
            }
            val num = StringBuilder()
            var dots = 0
            while (i < chars.length && (chars[i] in '0'..'9' || chars[i] == '.')) {
                if (chars[i] == '.') { dots += 1; if (dots > 1) return null }
                num.append(chars[i]); i += 1
            }
            return if (num.isEmpty()) null else num.toString().toDoubleOrNull()
        }
    }
}
