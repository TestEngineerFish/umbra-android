// Ed25519 验签（RFC 8032 §5.1.7，纯 Ed25519、不预哈希）—— 只验不签，只给主题包和许可证用。
//
// 为什么手写：minSdk 28。Android 系统的 `Signature.getInstance("Ed25519")` 要到较新的版本才有（Conscrypt 里），28～32 上直接抛
// NoSuchAlgorithmException；为一处验签引 BouncyCastle（几 MB，还要配 R8 规则）不划算。iOS 用 CryptoKit、PC 用 node:crypto，都是现成的。
// 写法照 RFC 8032 附带的 Python 参考实现（扩展坐标、BigInteger），慢但够用：一次验签两次标量乘，手机上几十毫秒，主题装包 / 启动重验各一两次。
// 正确性由 `ThemeRulesTest` 钉：umbra-plugins 的测试向量（临时钥匙签的包）、JDK 自带 Ed25519 签的随机消息、改一个字节必须验不过。
//
// ⚠️ 只许用 JVM 标准库，不许 import android.*：JUnit 直接跑它。
package com.geek.umbra.feature.theme

import java.math.BigInteger
import java.security.MessageDigest

object Ed25519 {
    /** `BigInteger.TWO` 是 Java 9 才有的，Android 低版本的 libcore 里没有 —— 自己留一个。 */
    private val TWO: BigInteger = BigInteger.valueOf(2)
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    /** 基点的阶 L = 2^252 + 27742317777372353535851937790883648493。 */
    private val L: BigInteger = BigInteger.ONE.shiftLeft(252).add(BigInteger("27742317777372353535851937790883648493"))
    private val D: BigInteger = BigInteger.valueOf(-121665).multiply(inv(BigInteger.valueOf(121666))).mod(P)
    private val D2: BigInteger = D.shiftLeft(1).mod(P)
    private val SQRT_M1: BigInteger = TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)
    private val ZERO_POINT = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
    private val G: Point = run {
        val y = BigInteger.valueOf(4).multiply(inv(BigInteger.valueOf(5))).mod(P)
        val x = recoverX(y, 0)!!
        Point(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    /** 扩展坐标 (X, Y, Z, T)，x = X/Z、y = Y/Z、x·y = T/Z。 */
    private class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    private fun inv(v: BigInteger): BigInteger = v.modPow(P.subtract(TWO), P)

    private fun add(a: Point, b: Point): Point {
        val aa = a.y.subtract(a.x).multiply(b.y.subtract(b.x)).mod(P)
        val bb = a.y.add(a.x).multiply(b.y.add(b.x)).mod(P)
        val c = a.t.multiply(D2).multiply(b.t).mod(P)
        val d = a.z.shiftLeft(1).multiply(b.z).mod(P)
        val e = bb.subtract(aa)
        val f = d.subtract(c)
        val g = d.add(c)
        val h = bb.add(aa)
        return Point(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P))
    }

    private fun mul(s0: BigInteger, p0: Point): Point {
        var q = ZERO_POINT
        var p = p0
        var s = s0
        while (s.signum() > 0) {
            if (s.testBit(0)) q = add(q, p)
            p = add(p, p)
            s = s.shiftRight(1)
        }
        return q
    }

    private fun equal(a: Point, b: Point): Boolean =
        a.x.multiply(b.z).subtract(b.x.multiply(a.z)).mod(P).signum() == 0 &&
            a.y.multiply(b.z).subtract(b.y.multiply(a.z)).mod(P).signum() == 0

    private fun recoverX(y: BigInteger, sign: Int): BigInteger? {
        if (y >= P) return null
        val y2 = y.multiply(y)
        val x2 = y2.subtract(BigInteger.ONE).multiply(inv(D.multiply(y2).add(BigInteger.ONE))).mod(P)
        if (x2.signum() == 0) return if (sign == 1) null else BigInteger.ZERO
        var x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P)
        if (x.multiply(x).subtract(x2).mod(P).signum() != 0) x = x.multiply(SQRT_M1).mod(P)
        if (x.multiply(x).subtract(x2).mod(P).signum() != 0) return null
        if ((if (x.testBit(0)) 1 else 0) != sign) x = P.subtract(x)
        return x
    }

    /** 32 字节小端 → 整数（无符号）。 */
    private fun le(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())

    private fun decompress(s: ByteArray): Point? {
        if (s.size != 32) return null
        val y = le(s).clearBit(255)
        val sign = if ((s[31].toInt() and 0x80) != 0) 1 else 0
        val x = recoverX(y, sign) ?: return null
        return Point(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    /** 用 32 字节原始公钥验 64 字节签名。任何形状不对都回 false，不抛。 */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        val a = decompress(publicKey) ?: return false
        val rs = signature.copyOfRange(0, 32)
        val r = decompress(rs) ?: return false
        val s = le(signature.copyOfRange(32, 64))
        if (s >= L) return false
        val h = MessageDigest.getInstance("SHA-512").run {
            update(rs); update(publicKey); update(message); digest()
        }.let { BigInteger(1, it.reversedArray()).mod(L) }
        return equal(mul(s, G), add(r, mul(h, a)))
    }
}
