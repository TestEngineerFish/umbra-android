package com.geek.umbra.feature.chat

import com.geek.umbra.feature.chat.QuestionAnswerRules as R
import org.junit.Assert.assertEquals
import org.junit.Test

/** 照抄 iOS Tools/swifttests/QuestionAnswerRulesTests.swift。 */
class QuestionAnswerRulesTest {
    private val other = R.CUSTOM_KEY

    @Test fun values() {
        assertEquals("只勾其它填了字：交出去的是那句话", listOf("codex"), R.values(listOf(other), "codex"))
        assertEquals("多选：选项和填的字并存，填的字去首尾空白", listOf("微信小程序", "抖音小程序"), R.values(listOf("微信小程序", other), " 抖音小程序 "))
        assertEquals("勾了其它没填字：什么都不交", emptyList<String>(), R.values(listOf(other), ""))
        assertEquals("勾了其它只填空白：什么都不交", emptyList<String>(), R.values(listOf(other), "  \n"))
        assertEquals("勾了其它从没碰过输入框：什么都不交", emptyList<String>(), R.values(listOf(other), null))
        assertEquals("没勾其它：填过的字不出去", listOf("微信小程序"), R.values(listOf("微信小程序"), "抖音"))
        assertEquals("什么都没勾、残留着字：不交", emptyList<String>(), R.values(emptyList(), "抖音"))
        assertEquals("填的字和勾的选项一样：不重复交", listOf("A"), R.values(listOf("A", other), "A"))
        assertEquals("按选中顺序", listOf("B", "A"), R.values(listOf("B", "A"), null))
    }

    @Test fun filled() {
        assertEquals("必答题只勾其它不填字：不算答了", false, R.filled(false, listOf(other), ""))
        assertEquals("只填空白：不算", false, R.filled(false, listOf(other), "   "))
        assertEquals("勾了其它填了字：算", true, R.filled(false, listOf(other), "codex"))
        assertEquals("勾了选项：算", true, R.filled(false, listOf("A"), null))
        assertEquals("没勾其它、只残留着看不见的字：不算", false, R.filled(false, emptyList(), "残留"))
        assertEquals("可选题：永远算", true, R.filled(true, listOf(other), ""))
        assertEquals("什么都没有：不算", false, R.filled(false, emptyList(), null))
    }
}
