// 保险箱加密内核 —— 逐字节照抄 iOS VaultCore.swift:15-117（它又对齐 PC `UmbraPC/electron/core/vault/crypto.ts`）。
//
// ⚠️ 这个文件**只许用 JVM 标准库**（javax.crypto / java.security / java.util.Base64），不许 import android.*：
// JUnit 用例（VaultCryptoTest）拿 PC 原件生成的向量逐字节比对它，引进 android.util.Base64 就跑不了单测，
// 而「和 PC / iOS 互通」这件事只有向量能证明。
//
// 派生链（2SKD）：
//   pwKey = PBKDF2-HMAC-SHA256(主密码 UTF-8 字节, salt, 600000, 32)   —— 不做 NFC/NFKC
//   AUK   = HKDF-SHA256(ikm = pwKey ‖ decodeSecretKey(SK) 16 字节, salt = 同一 salt, info = "umbra-vault-auk-v1", 32)
//   authHash = HKDF-SHA256(AUK, salt, "umbra-vault-auth-v1", 32) → hex
//   verifier = hex(SHA256(authHash 原始 32 字节))
// 字符串密文 "v1:" + b64(iv) + ":" + b64(tag) + ":" + b64(ct)（**tag 在 ct 前**）；附件密文裸字节 iv(12)‖tag(16)‖ct。
package com.geek.umbra.feature.vault

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object VaultCrypto {
    /** 与 PC `PBKDF2_ITER`、iOS `pbkdf2Iter` 同值。改它就再也解不开任何一端的库。 */
    const val PBKDF2_ITER = 600_000

    private val rng = SecureRandom()

    /**
     * PBKDF2-HMAC-SHA256，**手写**而不用 `SecretKeyFactory("PBKDF2WithHmacSHA256")`：
     * 后者吃 `char[]`，转字节的方式随提供者不同（SunJCE / BouncyCastle / Conscrypt 各有各的历史，
     * 老 BC 甚至只取每个 char 的低 8 位）。主密码里一个中文字或 emoji 就能让 Android 派生出另一把钥匙 ——
     * 而表现只是「主密码不正确」。这里直接喂 `password.toByteArray(UTF_8)`，与 PC `Buffer.from(pw,"utf8")`、
     * iOS `Array(password.utf8)` 同一串字节。只输出 32 字节 = SHA-256 一块，所以只算 T1。
     */
    fun pbkdf2(password: String, salt: ByteArray, iterations: Int = PBKDF2_ITER, keyLen: Int = 32): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // 空密码时 SecretKeySpec 不收空数组：HMAC 规范里空钥匙等价于 32 字节全零补齐，这里照补。
        val pw = password.toByteArray(Charsets.UTF_8)
        mac.init(SecretKeySpec(if (pw.isEmpty()) ByteArray(1) { 0 } else pw, "HmacSHA256"))
        val hLen = 32
        val blocks = (keyLen + hLen - 1) / hLen
        val out = ByteArray(blocks * hLen)
        for (b in 1..blocks) {
            mac.update(salt)
            mac.update(byteArrayOf((b ushr 24).toByte(), (b ushr 16).toByte(), (b ushr 8).toByte(), b.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (i in 1 until iterations) {
                u = mac.doFinal(u)
                for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            System.arraycopy(t, 0, out, (b - 1) * hLen, hLen)
        }
        return out.copyOf(keyLen)
    }

    /** HKDF-SHA256（RFC 5869）手写：JVM 标准库没有 HKDF。salt 为空时按规范用 32 字节零。 */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, len: Int = 32): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val infoBytes = info.toByteArray(Charsets.UTF_8)
        val out = ByteArray(len)
        var prev = ByteArray(0)
        var pos = 0
        var i = 1
        while (pos < len) {
            mac.update(prev); mac.update(infoBytes); mac.update(i.toByte())
            prev = mac.doFinal()
            val n = minOf(prev.size, len - pos)
            System.arraycopy(prev, 0, out, pos, n)
            pos += n; i++
        }
        return out
    }

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    /** 本机建库：16 字节随机 → `U1-…`（形制在 [VaultSecretKey.format]，和 PC `generateSecretKey` 同形）。 */
    fun generateSecretKey(): String = VaultSecretKey.format(randomBytes(16))

    /** PC `rid(p)`：前缀 + 9 字节随机的 hex（18 位）。 */
    fun rid(p: String): String = p + randomBytes(9).toHex()

    /**
     * Secret Key → 16 字节。iOS VaultCore.swift:47 / PC `decodeSecretKey` 逐行照抄：
     * 转大写 → **只留 `[A-Z2-7]`** → 以 `U1` 开头就去掉 → Base32 解码 → 取前 16 字节。
     *
     * ⚠️⚠️ 两端都有的怪行为，**必须照抄、不能修**：过滤那一步已经把 `1` 删了（1 不在 Base32 字母表里），
     * 于是 `U1-ABCDE…` 过滤后是 `UABCDE…`，`U1` 前缀**永远不成立**，`U`（值 20）作为最前 5 位被一起解码 ——
     * 得到的 16 字节不是生成时的那 16 个随机字节。两端一致所以互通；这里「修好」就是换了一把钥匙，
     * 所有已有的库在 Android 上都会报「主密码或 Secret Key 不正确」。
     */
    fun decodeSecretKey(sk: String): ByteArray {
        val clean = sk.uppercase().filter { it in 'A'..'Z' || it in '2'..'7' }
        val body = if (clean.startsWith("U1")) clean.substring(2) else clean
        val d = VaultSecretKey.base32Decode(body)
        return d.copyOf(minOf(16, d.size))
    }

    /** iOS `deriveAUK`：kdf 只认 pbkdf2（PC 旧 scrypt 会自动迁移到 pbkdf2 后再同步）。 */
    fun deriveAUK(password: String, secretKey: String, salt: ByteArray): ByteArray {
        val pwKey = pbkdf2(password, salt)
        val sk = decodeSecretKey(secretKey)
        return hkdf(pwKey + sk, salt, "umbra-vault-auk-v1")
    }

    fun authHash(auk: ByteArray, salt: ByteArray): String = hkdf(auk, salt, "umbra-vault-auth-v1").toHex()

    /** verifier = SHA256(authHash 的**原始字节**)，不是 SHA256(hex 字符串)。 */
    fun verifierOf(authHashHex: String): String =
        MessageDigest.getInstance("SHA-256").digest(authHashHex.hexToBytes()).toHex()

    /** 主密码 + SK 对这份 record 能不能解：对得上回 AUK。PBKDF2 60 万次，调用方放后台线程。 */
    fun tryKey(password: String, secretKey: String, rec: VRecord): ByteArray? {
        val salt = b64Decode(rec.salt) ?: ByteArray(0)
        val k = deriveAUK(password, secretKey, salt)
        return if (verifierOf(authHash(k, salt)) == rec.verifier) k else null
    }

    /**
     * 解 `v1:iv:tag:ct`。按 `:` 最多切 4 段（iOS `maxSplits: 3`），第一段必须 `v1`。
     * Java 的 GCM 要的是 `ct ‖ tag` 拼在一起，这里拼回去。密钥错 / 被改过 / 格式不对 → null，不抛。
     */
    fun aesDecrypt(key: ByteArray, blob: String): ByteArray? = runCatching {
        val parts = blob.split(":", limit = 4)
        if (parts.size != 4 || parts[0] != "v1") return null
        val iv = b64Decode(parts[1]) ?: return null
        val tag = b64Decode(parts[2]) ?: return null
        val ct = b64Decode(parts[3]) ?: return null
        if (tag.size != 16) return null
        gcm(Cipher.DECRYPT_MODE, key, iv).doFinal(ct + tag)
    }.getOrNull()

    /**
     * 加密成 `v1:iv:tag:ct`。Java 输出 `ct ‖ tag`，**拆开后 tag 写在 ct 前面**（与 PC / iOS 同排法）。
     * 返回 null 而不是抛：同 iOS 那条注释 —— 这里护着整个保险箱，加密失败应当是「这次没存上，告诉他」。
     */
    fun aesEncrypt(key: ByteArray, plaintext: ByteArray): String? = aesEncryptWithIv(key, plaintext, randomBytes(12))

    /** 固定 iv 的那一份，只给用例（与 node 同 iv 加密逐字节比）和 [aesEncrypt] 用。 */
    internal fun aesEncryptWithIv(key: ByteArray, plaintext: ByteArray, iv: ByteArray): String? = runCatching {
        val out = gcm(Cipher.ENCRYPT_MODE, key, iv).doFinal(plaintext)
        val ct = out.copyOfRange(0, out.size - 16)
        val tag = out.copyOfRange(out.size - 16, out.size)
        "v1:${b64(iv)}:${b64(tag)}:${b64(ct)}"
    }.getOrNull()

    /** 二进制附件：`iv(12) ‖ tag(16) ‖ ct`，用**该库的 VaultKey**（不是 AUK）。太短 / 解不开 → null。 */
    fun aesDecryptBytes(key: ByteArray, blob: ByteArray): ByteArray? = runCatching {
        if (blob.size < 28) return null
        val iv = blob.copyOfRange(0, 12)
        val tag = blob.copyOfRange(12, 28)
        val ct = blob.copyOfRange(28, blob.size)
        gcm(Cipher.DECRYPT_MODE, key, iv).doFinal(ct + tag)
    }.getOrNull()

    private fun gcm(mode: Int, key: ByteArray, iv: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)) }

    /** 标准 Base64、带 `=`、不换行（PC `toString("base64")` / iOS `base64EncodedString()` 同形）。 */
    fun b64(d: ByteArray): String = Base64.getEncoder().encodeToString(d)

    /** 解不开回 null。标准解码器（不收换行，同 iOS `Data(base64Encoded:)`）；首尾空白先去掉。 */
    fun b64Decode(s: String): ByteArray? = runCatching { Base64.getDecoder().decode(s.trim()) }.getOrNull()
}

/** 小写 hex。 */
fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 0xf])
    }
    return sb.toString()
}

/** hex → 字节；照 iOS `String.hexData`：两个字符一组，认不出的那一组跳过，奇数尾巴丢掉。 */
fun String.hexToBytes(): ByteArray {
    val out = java.io.ByteArrayOutputStream(length / 2)
    var i = 0
    while (i + 2 <= length) {
        substring(i, i + 2).toIntOrNull(16)?.let { out.write(it) }
        i += 2
    }
    return out.toByteArray()
}

/**
 * Secret Key 的形制（iOS VaultModels.swift:504 `VaultSecretKey`）：`U1` + 16 字节随机的 Base32（26 字符，无填充），
 * 每 5 字符一组用 `-` 连。Base32 解码同时给两步验证码（TOTP）用。
 */
object VaultSecretKey {
    private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun base32Encode(data: ByteArray): String {
        var bits = 0
        var value = 0
        val out = StringBuilder()
        for (b in data) {
            value = (value shl 8) or (b.toInt() and 0xff); bits += 8
            while (bits >= 5) { out.append(B32[(value ushr (bits - 5)) and 31]); bits -= 5 }
        }
        if (bits > 0) out.append(B32[(value shl (5 - bits)) and 31])
        return out.toString()
    }

    /**
     * Base32 → 字节；不认识的字符（空格、连字符、`=` 填充）一律跳过 —— 用户是从别处抄来的。
     * `value` 只留低 13 位：Swift 的 Int 是 64 位、PC 的是 32 位有符号移位，长串时高位溢出方式不同，
     * 但输出只取 `(value >> (bits-8)) & 0xff`，bits 不超过 12，留低 13 位足够且三端结果一致。
     */
    fun base32Decode(s: String): ByteArray {
        var bits = 0
        var value = 0
        val out = java.io.ByteArrayOutputStream()
        for (ch in s.uppercase()) {
            val idx = B32.indexOf(ch)
            if (idx < 0) continue
            value = ((value shl 5) or idx) and 0x1FFF
            bits += 5
            if (bits >= 8) { out.write((value ushr (bits - 8)) and 0xff); bits -= 8 }
        }
        return out.toByteArray()
    }

    /** 16 字节 → `U1-XXXXX-XXXXX-XXXXX-XXXXX-XXXXX-X`。 */
    fun format(bytes: ByteArray): String {
        val body = base32Encode(bytes.copyOf(minOf(16, bytes.size)))
        return "U1-" + body.chunked(5).joinToString("-")
    }
}
