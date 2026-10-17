package com.geek.umbra.feature.vault

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 两步验证码（TOTP）—— 逐条翻译 iOS `Tools/swifttests/VaultTOTPTests.swift`（umbra-ios#87）。
 *
 * #87 的失败形状是崩溃：`digits=10` 过了旧闸、整型连乘溢出。这里拿 RFC 6238 附录 B 三种算法的向量证明算得对，
 * 再拿越界位数 / 周期各喂一次证明回 null、不崩。时间是毫秒（Android 的 `nowMs`），iOS 的秒 × 1000。
 */
class VaultTotpTest {
    /** RFC 6238 附录 B 的种子是 ASCII 串的重复，这里转成 Base32 交给 parse。 */
    private fun b32(ascii: String) = VaultSecretKey.base32Encode(ascii.toByteArray(Charsets.UTF_8))

    private val sha1 = b32("12345678901234567890")
    private val sha256 = b32("12345678901234567890123456789012")
    private val sha512 = b32("1234567890123456789012345678901234567890123456789012345678901234")

    /** 附录 B：8 位、30 秒一轮，SHA1 / SHA256 / SHA512 四个时刻；algorithm 参数大小写不敏感。 */
    @Test fun rfc6238Vectors() {
        val vectors = listOf(
            listOf(59L, "94287082", "46119246", "90693936"),
            listOf(1_111_111_109L, "07081804", "68084774", "25091201"),
            listOf(1_234_567_890L, "89005924", "91819424", "93441116"),
            listOf(20_000_000_000L, "65353130", "77737706", "47863826"),
        )
        for (v in vectors) {
            val t = v[0] as Long
            val ms = t * 1000
            assertEquals("SHA1 @$t", v[1], VaultTotp.code("otpauth://totp/x?secret=$sha1&digits=8", ms))
            assertEquals("SHA256 @$t", v[2], VaultTotp.code("otpauth://totp/x?secret=$sha256&digits=8&algorithm=SHA256", ms))
            assertEquals("SHA512 @$t", v[3], VaultTotp.code("otpauth://totp/x?secret=$sha512&digits=8&algorithm=sha512", ms))
        }
    }

    /** 6 位是 8 位的后 6 位；裸 Base32 也认，小写、空格随便放；前导零补齐。 */
    @Test fun bareBase32AndPadding() {
        assertEquals("裸 Base32 默认 6 位", "287082", VaultTotp.code(sha1, 59_000L))
        val spaced = sha1.mapIndexed { i, c -> if (i % 4 == 3) "$c " else "$c" }.joinToString("").lowercase()
        assertEquals("小写带空格的 Base32", "287082", VaultTotp.code(spaced, 59_000L))
        assertEquals("前导零被吃掉了", 8, VaultTotp.code("otpauth://totp/x?secret=$sha1&digits=8", 1_111_111_109_000L)?.length)
    }

    /** #87 本体：越界位数一律 null 且不崩；认不出数字的（abc / 超出 Int）退回默认 6 位；6/7/8 收。 */
    @Test fun digitsBounds() {
        for (bad in listOf("10", "9", "11", "0", "-3", "5", "4", "99999999999999999999", "abc")) {
            val got = VaultTotp.code("otpauth://totp/x?secret=$sha1&digits=$bad", 59_000L)
            val want: String? = if (bad == "abc" || bad == "99999999999999999999") "287082" else null
            assertEquals("digits=$bad", want, got)
        }
        for (ok in listOf(6, 7, 8)) {
            assertEquals("digits=$ok 应当收", ok, VaultTotp.parse("otpauth://totp/x?secret=$sha1&digits=$ok")?.digits)
        }
    }

    /** 周期：0 / 负数 / 超过一天 → null；60 照收。非法密钥 / 缺 secret → null。 */
    @Test fun periodAndBadSecret() {
        for (bad in listOf("0", "-30", "86401")) {
            assertEquals("period=$bad 应当拒", true, VaultTotp.parse("otpauth://totp/x?secret=$sha1&period=$bad") == null)
        }
        assertEquals("period=60", 60, VaultTotp.parse("otpauth://totp/x?secret=$sha1&period=60")?.period)
        assertEquals("非法密钥", null, VaultTotp.code("!!!!", System.currentTimeMillis()))
        assertEquals("otpauth 缺 secret", null, VaultTotp.code("otpauth://totp/x?digits=6", System.currentTimeMillis()))
    }

    /** 三位一组只对 6 位码。 */
    @Test fun grouping() {
        assertEquals("6 位分组", "123 456", VaultTotp.grouped("123456"))
        assertEquals("8 位不分组", "12345678", VaultTotp.grouped("12345678"))
    }
}
