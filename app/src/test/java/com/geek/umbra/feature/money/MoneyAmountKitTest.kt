package com.geek.umbra.feature.money

import org.junit.Assert.assertEquals
import org.junit.Test

/** iOS Tools/swifttests/MoneyAmountKitTests.swift 逐条照抄（umbra-ios#36）。 */
class MoneyAmountKitTest {
    @Test fun outOfRange() {
        // ── 越界：iOS 原来是运行时 trap，不是回 nil ──
        assertEquals("金额框敲到第 17 位 —— 回 nil，不是闪退", null, MoneyAmount.cents("99999999999999999"))
        assertEquals("「×」芯片连乘几个大数 —— 回 nil，不是闪退", null, MoneyAmount.cents("9999999*9999999*9999999"))
        assertEquals("300 位的数（仍是有限的 Double）", null, MoneyAmount.cents("1" + "0".repeat(300)))
        assertEquals("刚过上限一元", null, MoneyAmount.cents("10000000"))
    }

    @Test fun upperEdge() {
        assertEquals("上限那一分还收", 999_999_999L, MoneyAmount.cents("9999999.99"))
        // 9999999.995 在 Double 里是 9999999.99499999…，×100 四舍五入回到 999999999；PC 同口径。
        assertEquals("9999999.995 两端都舍回上限（Double 表示误差）", 999_999_999L, MoneyAmount.cents("9999999.995"))
        assertEquals("舍进到 10 亿分 —— 过闸门", null, MoneyAmount.cents("9999999.996"))
    }

    @Test fun whitelistAndZero() {
        assertEquals("科学计数法被白名单挡下（字母只可能是误触）", null, MoneyAmount.cents("1e5"))
        assertEquals("非零的输入不许被舍成 0", null, MoneyAmount.cents("0.004"))
        assertEquals("0 是合法金额", 0L, MoneyAmount.cents("0"))
        assertEquals("0.00 也是 0", 0L, MoneyAmount.cents("0.00"))
    }

    @Test fun normal() {
        assertEquals("258/3 = 86.00", 8600L, MoneyAmount.cents("258/3"))
        assertEquals("32+18", 5000L, MoneyAmount.cents("32+18"))
        assertEquals("全角数字 + ¥ 前缀", 1250L, MoneyAmount.cents("¥１２．５"))
        assertEquals("七位整数", 999_999_900L, MoneyAmount.cents("9999999"))
        assertEquals("除零算「算不出」", null, MoneyAmount.cents("1/0"))
        assertEquals("负数", null, MoneyAmount.cents("5-10"))
        assertEquals("空串", null, MoneyAmount.cents(""))
    }
}
