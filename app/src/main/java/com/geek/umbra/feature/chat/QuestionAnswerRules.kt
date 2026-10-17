// 问答卡一道题「答了没有」「提交时交出去什么」（iOS: Features/Chat/QuestionAnswerRules.swift，台账 `custom-key-leaks-into-answers`）。
//
// 纯判据，用例照抄 iOS Tools/swifttests/QuestionAnswerRulesTests.swift。
// 坑：「其它（自己写）」那一行的内部 key `__custom` 原来原样进了 payload，执行体选择卡取第一项拿到 `__custom`，
// 人填的执行体被无视。现在「答了没有」和「交出去什么」是同一个函数的两面：values 为空 ⇔ 没答。
package com.geek.umbra.feature.chat

object QuestionAnswerRules {
    /** 「自己填」这一项的内部 key。**只活在端上**，交出去之前一律换成填的字。 */
    const val CUSTOM_KEY = "__custom"

    /**
     * 一道题提交时交出去的答案（按选中顺序）。
     * customKey 本身永远不出去；勾了「其它」且填了字 → 填的字（trim）接在后面、与选项去重；
     * 没勾「其它」时填的字不出去（输入框只在勾上时才画出来，看不见的话不交）。
     */
    fun values(picked: List<String>, custom: String?): List<String> {
        val out = picked.filter { it != CUSTOM_KEY }.toMutableList()
        if (picked.contains(CUSTOM_KEY)) {
            val c = (custom ?: "").trim()
            if (c.isNotEmpty() && !out.contains(c)) out += c
        }
        return out
    }

    /** 这道题算不算答了：可选题永远算；必答题看交出去的答案非空。必须和 values 同源。 */
    fun filled(optional: Boolean, picked: List<String>, custom: String?): Boolean =
        optional || values(picked, custom).isNotEmpty()
}
