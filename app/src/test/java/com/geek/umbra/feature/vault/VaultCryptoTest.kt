package com.geek.umbra.feature.vault

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保险箱加密**跨端互通**用例：向量由 PC 端原件 `UmbraPC/electron/core/vault/crypto.ts` 用 node 生成
 * （生成脚本在 test/resources/vault/gen_crypto_vectors.mts，`node gen_crypto_vectors.mts > crypto_vectors.json` 重生成）。
 * Kotlin 派生出来的 AUK / authHash / verifier、解开的 v1 密文、固定 iv 加密出的密文串，必须与 PC 逐字节一致 ——
 * 这是「Android 打得开 iOS / PC 的保险箱」唯一的证据。PBKDF2 60 万次在这里真的跑（每条约一秒）。
 */
class VaultCryptoTest {
    private val v: JsonObject = Json.parseToJsonElement(
        javaClass.classLoader!!.getResourceAsStream("vault/crypto_vectors.json")!!.readBytes().toString(Charsets.UTF_8),
    ).jsonObject

    private fun s(o: JsonObject, k: String) = o[k]!!.jsonPrimitive.content
    private val salt get() = VaultCrypto.b64Decode(s(v, "saltB64"))!!

    /** Secret Key 解码：照抄「U1 前缀永远去不掉」的怪行为，结果与 PC 逐字节相同。 */
    @Test fun decodeSecretKeyMatchesPc() {
        for (c in v["cases"]!!.jsonArray.map { it.jsonObject }) {
            assertEquals("SK ${s(c, "sk")}", s(c, "skDecoded"), VaultCrypto.decodeSecretKey(s(c, "sk")).toHex())
        }
        // 怪行为本身：U1 前缀没被去掉 —— 解出来的不是格式化前那 16 字节（两端一致，所以互通）。
        val raw = ByteArray(16) { it.toByte() }
        val sk = VaultSecretKey.format(raw)
        assertTrue(sk.startsWith("U1-") && sk.length == 3 + 26 + 5)
        assertTrue(!VaultCrypto.decodeSecretKey(sk).contentEquals(raw))
    }

    /** AUK / authHash / verifier 三件，含中文密码与带组合附加符的密码（不做 NFC 规范化）。 */
    @Test fun deriveMatchesPc() {
        for (c in v["cases"]!!.jsonArray.map { it.jsonObject }) {
            val auk = VaultCrypto.deriveAUK(s(c, "pw"), s(c, "sk"), salt)
            assertEquals("AUK ${s(c, "pw")}", s(c, "auk"), auk.toHex())
            val ah = VaultCrypto.authHash(auk, salt)
            assertEquals(s(c, "authHash"), ah)
            assertEquals(s(c, "verifier"), VaultCrypto.verifierOf(ah))
        }
    }

    /** PC 写出来的整份 record：用主密码 + SK 认出它、解开快照、解出 VaultKey、再解附件密文。 */
    @Test fun unlockPcRecord() {
        val c0 = v["cases"]!!.jsonArray[0].jsonObject
        val rec = VRecord.parse(s(v, "record"))!!
        assertEquals("pbkdf2", rec.kdf)
        assertNull("错的密码不许认", VaultCrypto.tryKey("wrong", s(c0, "sk"), rec))
        val auk = VaultCrypto.tryKey(s(c0, "pw"), s(c0, "sk"), rec)
        assertNotNull(auk)
        val plain = VaultCrypto.aesDecrypt(auk!!, rec.enc)!!
        assertEquals(s(v, "snapshotJson"), String(plain, Charsets.UTF_8))
        val snap = VSnapshot.parse(plain)!!
        val item = snap.data.getValue("v1").items.single()
        assertTrue(item.isCanvas)
        assertEquals("alice", item.accounts.single().user)
        assertTrue("节点上不认识的键要留着", item.nodes.single().extra.containsKey("zz"))
        val vk = VaultCrypto.aesDecrypt(auk, snap.vaults.single().keyWrapped)!!
        assertEquals(s(v, "vkHex"), vk.toHex())
        val att = VaultCrypto.aesDecryptBytes(vk, VaultCrypto.b64Decode(s(v, "attBlobB64"))!!)!!
        assertEquals("hello 附件", String(att, Charsets.UTF_8))
        // 被改过一个字节就解不开（不抛，回 null）
        val tampered = rec.enc.dropLast(3) + (if (rec.enc.takeLast(3).first() == 'A') "B" else "A") + rec.enc.takeLast(2)
        assertNull(VaultCrypto.aesDecrypt(auk, tampered))
        assertNull(VaultCrypto.aesDecrypt(auk, "v2:" + rec.enc.removePrefix("v1:")))
    }

    /** 同 key 同 iv 加密：Kotlin 拼出来的 `v1:iv:tag:ct`（tag 在 ct 前）与 node 逐字节相同 —— PC / iOS 因而能解。 */
    @Test fun encryptMatchesPcByteForByte() {
        val c0 = v["cases"]!!.jsonArray[0].jsonObject
        val auk = s(c0, "auk").hexToBytes()
        val f = v["fixedIv"]!!.jsonObject
        val blob = VaultCrypto.aesEncryptWithIv(auk, s(f, "plain").toByteArray(Charsets.UTF_8), s(f, "ivHex").hexToBytes())
        assertEquals(s(f, "blob"), blob)
        // 随机 iv 的往返
        val r = VaultCrypto.aesEncrypt(auk, "往返".toByteArray())!!
        assertEquals("往返", String(VaultCrypto.aesDecrypt(auk, r)!!))
    }

    /** VRecord 键序固定（iOS 字符串拼接）：v → kdf → salt → verifier → enc。 */
    @Test fun recordKeyOrder() {
        val rec = VRecord(1, null, "c2FsdA==", "ab", "v1:a:b:c")
        assertEquals("{\"v\":1,\"kdf\":\"pbkdf2\",\"salt\":\"c2FsdA==\",\"verifier\":\"ab\",\"enc\":\"v1:a:b:c\"}", rec.encode())
        assertEquals(rec.copy(kdf = "pbkdf2"), VRecord.parse(rec.encode()))
    }

    /** PBKDF2 / HKDF 本身对一组公开向量（RFC 6070 风格的 SHA-256 版、RFC 5869 A.1）。 */
    @Test fun primitivesKnownAnswers() {
        // PBKDF2-HMAC-SHA256("password", "salt", 1, 32)
        assertEquals("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            VaultCrypto.pbkdf2("password", "salt".toByteArray(), 1).toHex())
        assertEquals("ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43",
            VaultCrypto.pbkdf2("password", "salt".toByteArray(), 2).toHex())
        // RFC 5869 A.1（info 是字节，这里用 Latin-1 拼出同样的字节）
        val ikm = ByteArray(22) { 0x0b }
        val salt = "000102030405060708090a0b0c".hexToBytes()
        val info = String("f0f1f2f3f4f5f6f7f8f9".hexToBytes(), Charsets.ISO_8859_1)
        // 我们的 hkdf 把 info 按 UTF-8 编码；0xf0.. 在 UTF-8 下是多字节，所以只比 info 为空时的 RFC A.3。
        val a3 = VaultCrypto.hkdf(ikm, ByteArray(0), "", 42)
        assertEquals("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8", a3.toHex())
        assertTrue(info.isNotEmpty() && salt.isNotEmpty())
    }

    /** Base32：编码 / 解码互逆，不认识的字符跳过。 */
    @Test fun base32() {
        val raw = VaultCrypto.randomBytes(16)
        assertArrayEquals(raw, VaultSecretKey.base32Decode(VaultSecretKey.base32Encode(raw)))
        assertArrayEquals("foobar".toByteArray(), VaultSecretKey.base32Decode("MZXW6-YTB OI======"))
    }
}
