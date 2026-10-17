package com.geek.umbra.feature.vault

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保险箱密码强度（umbra#95）—— 逐条翻译 iOS `Tools/swifttests/PasswordStrengthTests.swift`。
 *
 * 夹具 `vault/pwStrengthCases.json` 是 PC `UmbraPC/tests/fixtures/pwStrengthCases.json` 的逐字节副本（PC / iOS / Android 三端共用），
 * 每条比 level / eff / classes / pct；另有 iOS 侧补的拼音 + 生日、码点长度、pct 取整、长串性能几组。
 * ⚠️ PC 改规则、重新生成夹具时，把新夹具拷进 test/resources/vault/，再把 PasswordStrength.kt 改到全过。
 */
class PasswordStrengthTest {
    private fun fixture(): String =
        javaClass.classLoader!!.getResourceAsStream("vault/pwStrengthCases.json")!!.readBytes().toString(Charsets.UTF_8)

    /** 两端共用夹具：条数 ≥ 61（读错文件 / 被清空先红），每条四个字段逐条与 PC 实跑结果相同。 */
    @Test fun sharedFixtureMatchesPc() {
        val cases = Json.parseToJsonElement(fixture()).jsonArray
        assertTrue("夹具条数（≥ 61），实际 ${cases.size}", cases.size >= 61)
        for (e in cases) {
            val c = e.jsonObject
            val pw = c["pw"]!!.jsonPrimitive.content
            val got = PasswordStrength.of(pw)
            val name = "夹具 \"$pw\""
            assertEquals("$name·level", c["level"]!!.jsonPrimitive.int, got.level)
            assertEquals("$name·eff", c["eff"]!!.jsonPrimitive.int, got.eff)
            assertEquals("$name·classes", c["classes"]!!.jsonPrimitive.int, got.classes)
            assertEquals("$name·pct", c["pct"]!!.jsonPrimitive.int, got.pct)
        }
    }

    /** iOS 侧补的：拼音姓名 + 生日 / 年份这类原来被判「强」的，期望值是 PC pwStrength.ts 用 node 实跑出来的。 */
    @Test fun pinyinAndBirthdayExtras() {
        val extra = listOf(
            listOf("zhangwei19900101", 0, 4, 2, 20),   // 拼音姓名 + 生日
            listOf("Chen@19851020", 0, 4, 4, 20),      // 拼音姓 + 符号 + 生日：四类都有、原来判「很强」
            listOf("wangfang1990!", 0, 7, 3, 35),      // 拼音 + 年份
            listOf("Xiaoming2008@", 0, 3, 4, 15),
            listOf("Liu880808!", 1, 8, 4, 40),         // 六位生日不在规则里（PC 同样判中）
            listOf("Woaini1314520", 1, 8, 3, 40),
            listOf("KKqwerty", 0, 3, 3, 15),      // 开尔文符号小写是 ASCII k：两端都当键盘行里的 k
            listOf("İstanbul1234", 1, 9, 3, 45),  // İ 小写是两个码点：两端都当「其它」、不参与匹配
        )
        for (row in extra) {
            val pw = row[0] as String
            assertEquals("补充 \"$pw\"", PasswordStrength(row[1] as Int, row[2] as Int, row[3] as Int, row[4] as Int), PasswordStrength.of(pw))
        }
    }

    /** 安全体检「弱密码」组按 isWeak 判：issue 点名的几类必须被抓进去，真强的不能误伤。 */
    @Test fun isWeakForAudit() {
        for (pw in listOf("Password123!", "Zhang19900101!", "P@ssw0rd2024", "zhangwei19900101", "Chen@19851020",
            "qwerty123456", "abcdefghijklmnop")) {
            assertEquals("体检要把 $pw 抓进「弱密码」", true, PasswordStrength.of(pw).isWeak)
        }
        for (pw in listOf("k9#Lm2\$vQx7!", "Gx7#mQ2!vL9@wR4\$", "correcthorsebatterystaple")) {
            assertEquals("$pw 不该进「弱密码」", false, PasswordStrength.of(pw).isWeak)
        }
    }

    /** 长度按码点数：x́ḱṕḿ = 4 个字形簇、8 个码点，按码点数够 8，不因长度判弱。 */
    @Test fun lengthCountsCodePoints() {
        val combining = "x́ḱṕḿ"
        assertEquals("组合附加符的字形簇数（前提）", 4, VaultAudit.graphemeCount(combining))
        assertEquals("组合附加符按码点切：8 个码点各算 1", 8, PasswordStrength.of(combining).eff)
        assertEquals("8 码点、eff 8、种类 2 → 中（按字形簇数会是弱）", 1, PasswordStrength.of(combining).level)
    }

    /** pct 的取整：eff 是整数，eff/20×100 永远不落在 .5 上；0…200 全量对 eff×5（照 iOS 的四舍五入远离零写法）。 */
    @Test fun pctRounding() {
        for (eff in 0..200) {
            val p = minOf(100, maxOf(12, Math.round(eff.toDouble() / 20 * 100).toInt()))
            assertEquals("pct 取整 eff=$eff", minOf(100, maxOf(12, eff * 5)), p)
        }
    }

    /** 性能：粘贴 1000 码点的乱串 / 重复串 / 全数字 —— 防有人改回「每个位置切子串」的平方级写法。上限同 iOS 60 ms。 */
    @Test fun longInputIsLinear() {
        val pool = "abcdefghijkmnpqrstuvwxyzABCDEFGH23456789!@#$%"
        val longs = listOf("aZ9\$xQ!7".repeat(125), "a".repeat(1000), "1".repeat(1000), "汉🔑".repeat(500),
            String(CharArray(1000) { pool.random() }))
        val t0 = System.nanoTime()
        for (s in longs) PasswordStrength.of(s)
        val ms = (System.nanoTime() - t0) / 1e6 / longs.size
        assertEquals("1000 码点单次耗时 ${"%.2f".format(ms)} ms（上限 60 ms）", true, ms < 60)
    }
}
