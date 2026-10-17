// 保险箱密码强度的纯判据（umbra#95，两端同一套规则）—— 逐条照抄 iOS PasswordStrength.swift（它又照抄 PC `pwStrength.ts`）。
// 两端共用夹具 `UmbraPC/tests/fixtures/pwStrengthCases.json`，JUnit 用例 PasswordStrengthTest 61 条逐条比 level / eff / classes / pct。
// 改规则 / 改表 → PC 先改、重新生成夹具，这里跟着改。
//
// 算法（与 PC 文件头逐条一致）：按 **Unicode 码点**切；classes 只认 ASCII（a-z / A-Z / 0-9 / 其它）；
// 从左到右每个位置按 a→h 试：a 常见词（leet 归一、前缀最长）→1；b 日期 YYYYMMDD → 2；c 手机号 → 2；d 年份 → 1；
// e 键盘行（≥4）→1；f ASCII 顺序（≥3）→1；g 单码点重复（≥3）→1；h 2~4 码点片段连续 ≥3 次 → 片段长度；都不中吃 1 个 → 1。
// level：len<8 或 eff<8 弱；(eff≥12 且 classes≥3) 或 (eff≥16 且 classes≥2) 或 eff≥20 强；其余中。pct = clamp(round(eff×5), 12, 100)。
package com.geek.umbra.feature.vault

data class PasswordStrength(
    /** 0 弱 / 1 中 / 2 强。 */
    val level: Int,
    /** 有效长度。 */
    val eff: Int,
    /** 字符种类数 0–4。 */
    val classes: Int,
    /** 强度条百分比 12–100。 */
    val pct: Int,
) {
    /** 安全体检「弱密码」那一组的判据 —— 体检和强度条问的是同一句话。 */
    val isWeak: Boolean get() = level == 0

    companion object {
        /** 常见密码 / 词 / 拼音。⚠️ 两字母的几项靠末尾过滤剔掉 —— 照抄 PC 原样，两端的表才能逐行对。 */
        val words: List<String> = listOf(
            "password", "passw", "pass", "qwerty", "iloveyou", "letmein", "welcome", "admin", "administrator", "root",
            "login", "master", "monkey", "dragon", "shadow", "sunshine", "princess", "football", "baseball", "soccer",
            "superman", "batman", "trustno", "whatever", "freedom", "computer", "starwars", "pokemon", "mustang",
            "michael", "jordan", "harley", "ranger", "charlie", "summer", "winter", "spring", "autumn", "flower",
            "hunter", "cheese", "banana", "orange", "apple", "google", "secret", "hello", "love", "lover", "baby",
            "angel", "killer", "pepper", "ginger", "cookie", "tigger", "maggie", "buster", "hockey",
            "access", "default", "guest", "test", "user", "temp", "changeme", "abc", "god", "sex", "money",
            "iphone", "android", "windows", "mac", "linux", "umbra",
            "woaini", "aini", "nihao", "zhongguo", "china", "beijing", "shanghai", "shenzhen", "guangzhou",
            "laopo", "laogong", "baobao", "baobei", "tiantian", "xiaoming", "kuaile", "xingfu", "aiqing",
            "wangzhe", "qq", "weixin", "taobao", "mima",
            "zhang", "wang", "chen", "yang", "huang", "zhao", "zhou", "liu", "sun", "guo", "lin", "luo", "gao",
            "xie", "han", "tang", "feng", "deng", "cao", "peng", "zeng", "xiao", "tian", "dong", "pan", "yuan",
            "cai", "jiang", "wei", "jin", "ren", "shen", "song", "zheng", "liang", "xu", "zhu", "lu", "ye",
        ).filter { it.length >= 3 }

        /** 键盘行（小写）。正序、倒序的连续子串（≥ 4）都算。 */
        val keyboardRows = listOf("1234567890", "!@#$%^&*()", "qwertyuiop", "asdfghjkl", "zxcvbnm", "1qaz2wsx3edc")

        /** 只用于常见词匹配的一对一替换。 */
        val leet: Map<Char, Char> = mapOf('@' to 'a', '4' to 'a', '3' to 'e', '0' to 'o', '$' to 's', '5' to 's', '1' to 'i', '!' to 'i', '7' to 't')

        /** 「小写后不是单个 ASCII」的码点一律记成它 —— 三张表全是 ASCII，这类码点跟谁都比不上。 */
        private const val NON_ASCII = -1

        private fun ascii(s: String): IntArray = s.codePoints().toArray()

        private val wordsByFirst: Map<Int, List<IntArray>> = words.map { ascii(it) }.groupBy { it[0] }
        private val rowCodes: List<IntArray> = keyboardRows.flatMap { r -> listOf(ascii(r), ascii(r).reversedArray()) }
        private val leetCodes: Map<Int, Int> = leet.entries.associate { it.key.code to it.value.code }

        /**
         * 单个码点「小写之后」的值。ASCII 走快路；其它码点照 PC 的 `toLowerCase()` 真的小写一次 ——
         * ⚠️ 开尔文符号 U+212A 小写就是 ASCII 的 `k`，两端都会把它当键盘行里的 k。
         */
        private fun lowerCode(v: Int): Int {
            if (v < 0x80) return if (v in 65..90) v + 32 else v
            val l = String(Character.toChars(v)).lowercase()
            val cps = l.codePoints().toArray()
            return if (cps.size == 1 && cps[0] < 0x80) cps[0] else NON_ASCII
        }

        private fun isDigit(v: Int) = v in 48..57
        private fun isAsciiAlnum(v: Int) = isDigit(v) || v in 65..90 || v in 97..122

        fun of(pw: String): PasswordStrength {
            val cs = pw.codePoints().toArray()
            val len = cs.size
            var classes = 0
            if (cs.any { it in 97..122 }) classes++
            if (cs.any { it in 65..90 }) classes++
            if (cs.any { isDigit(it) }) classes++
            if (cs.any { !isAsciiAlnum(it) }) classes++

            val lower = IntArray(len) { lowerCode(cs[it]) }
            val norm = IntArray(len) { leetCodes[lower[it]] ?: lower[it] }
            // 从每个位置起连续多少个 ASCII 数字（倒着扫一遍）。
            val digitRun = IntArray(len + 1)
            for (k in len - 1 downTo 0) if (isDigit(cs[k])) digitRun[k] = digitRun[k + 1] + 1

            var eff = 0
            var i = 0
            while (i < len) {
                fun dg(k: Int) = cs[i + k] - 48
                var take = 0
                var weight = 1
                // a. 常见词（最长）
                for (w in wordsByFirst[norm[i]] ?: emptyList()) {
                    if (w.size <= take || i + w.size > len) continue
                    var k = 1
                    while (k < w.size && norm[i + k] == w[k]) k++
                    if (k == w.size) take = w.size
                }
                if (take == 0) {
                    val run = digitRun[i]
                    val kb = keyboardRun(lower, i)
                    if (run >= 8 && validDate(dg(0) * 1000 + dg(1) * 100 + dg(2) * 10 + dg(3), dg(4) * 10 + dg(5), dg(6) * 10 + dg(7))) {
                        take = 8; weight = 2                                                       // b. 日期
                    } else if (run >= 11 && dg(0) == 1 && dg(1) in 3..9) {
                        take = 11; weight = 2                                                      // c. 手机号
                    } else if (run >= 4 && ((dg(0) == 1 && dg(1) == 9) || (dg(0) == 2 && dg(1) == 0))) {
                        take = 4                                                                   // d. 年份
                    } else if (kb >= 4) {
                        take = kb                                                                  // e. 键盘行
                    } else {
                        val s = sequence(cs, i)
                        if (s >= 3) {
                            take = s                                                               // f. 顺序
                        } else {
                            var r = 1                                                              // g. 重复
                            while (i + r < len && cs[i + r] == cs[i]) r++
                            if (r >= 3) {
                                take = r
                            } else {
                                var b = 2                                                          // h. 重复片段
                                while (b <= 4 && take == 0) {
                                    var copies = 1
                                    while (i + (copies + 1) * b <= len && sameChunk(cs, i, i + copies * b, b)) copies++
                                    if (copies >= 3) { take = b * copies; weight = b }
                                    b++
                                }
                            }
                        }
                    }
                }
                if (take == 0) { take = 1; weight = 1 }
                eff += weight
                i += take
            }

            val level = when {
                len < 8 || eff < 8 -> 0
                (eff >= 12 && classes >= 3) || (eff >= 16 && classes >= 2) || eff >= 20 -> 2
                else -> 1
            }
            // PC 是 `Math.round(eff / 20 * 100)`；eff 是整数，值就是 eff×5，碰不到 .5。照抄浮点写法让两端逐行能对上。
            val pct = minOf(100, maxOf(12, Math.round(eff.toDouble() / 20 * 100).toInt()))
            return PasswordStrength(level, eff, classes, pct)
        }

        private fun sameChunk(cs: IntArray, a: Int, b: Int, n: Int): Boolean {
            for (k in 0 until n) if (cs[a + k] != cs[b + k]) return false
            return true
        }

        private fun validDate(y: Int, m: Int, d: Int) = y in 1900..2099 && m in 1..12 && d in 1..31

        /** 小写串从 i 起落在某一键盘行（正序或倒序）上的最长连续长度。取该行里**第一个**等于它的位置（同 PC indexOf）。 */
        private fun keyboardRun(lower: IntArray, i: Int): Int {
            var best = 0
            for (r in rowCodes) {
                val at = r.indexOf(lower[i])
                if (at < 0) continue
                var n = 0
                while (i + n < lower.size && at + n < r.size && lower[i + n] == r[at + n]) n++
                best = maxOf(best, n)
            }
            return best
        }

        /** 原串从 i 起码点逐个 +1 或 −1（都是 ASCII 字母数字）的最长长度。 */
        private fun sequence(cs: IntArray, i: Int): Int {
            if (!isAsciiAlnum(cs[i])) return 0
            var best = 1
            for (dir in intArrayOf(1, -1)) {
                var n = 1
                while (i + n < cs.size && isAsciiAlnum(cs[i + n]) && cs[i + n] - cs[i + n - 1] == dir) n++
                best = maxOf(best, n)
            }
            return best
        }

        /** 0 弱 / 1 中 / 2 强 —— 文案与 PC 强度条逐字相同。 */
        fun label(level: Int): String = listOf("弱", "中", "强")[level.coerceIn(0, 2)]
    }
}

/**
 * 两步验证码（TOTP，RFC 6238）—— 照抄 iOS VaultTOTP.swift。画布版 acct 节点存的是两步验证**密钥**（`data.seed`），
 * 手机上当场算码。裸 Base32（空白 / 连字符随便放）或 `otpauth://…?secret=&digits=&period=&algorithm=`（参数名不分大小写）。
 * 位数只收 6~8（umbra-ios#87：10 位时整型连乘溢出，打开那条记录就崩）；周期 1~86400。
 */
object VaultTotp {
    val digitsRange = 6..8

    data class Params(val secret: ByteArray, val digits: Int, val period: Int, val algorithm: String)

    fun parse(seed: String): Params? {
        var secret = seed.trim()
        var digits = 6
        var period = 30
        var algo = "SHA1"
        if (secret.lowercase().startsWith("otpauth://")) {
            val q = secret.substringAfter('?', "")
            if (q == secret) return null
            var found: String? = null
            for (pair in q.substringBefore('#').split('&')) {
                if (pair.isEmpty()) continue
                val name = percentDecode(pair.substringBefore('='))
                val value = if (pair.contains('=')) percentDecode(pair.substringAfter('=')) else null
                when (name.lowercase()) {
                    "secret" -> found = value
                    "digits" -> digits = value?.toIntOrNull() ?: 6
                    "period" -> period = value?.toIntOrNull() ?: 30
                    "algorithm" -> algo = (value ?: "SHA1").uppercase()
                }
            }
            secret = found ?: return null
        }
        val bytes = VaultSecretKey.base32Decode(secret.filter { !it.isWhitespace() && it != '-' })
        if (bytes.isEmpty() || digits !in digitsRange || period !in 1..86_400) return null
        return Params(bytes, digits, period, algo)
    }

    /** URLComponents 的 queryItems 口径：%XX 解码，`+` 不当空格。 */
    private fun percentDecode(s: String): String {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        val b = s.toByteArray(Charsets.UTF_8)
        while (i < b.size) {
            if (b[i] == '%'.code.toByte() && i + 2 < b.size) {
                val v = String(byteArrayOf(b[i + 1], b[i + 2])).toIntOrNull(16)
                if (v != null) { out.write(v); i += 3; continue }
            }
            out.write(b[i].toInt()); i++
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** 此刻的验证码；密钥解不出就是 null。`nowMs` 是墙钟毫秒。 */
    fun code(seed: String, nowMs: Long = System.currentTimeMillis()): String? {
        val p = parse(seed) ?: return null
        val counter = maxOf(0L, nowMs / 1000) / p.period
        val msg = ByteArray(8) { (counter ushr (8 * (7 - it))).toByte() }
        val alg = when (p.algorithm) { "SHA256" -> "HmacSHA256"; "SHA512" -> "HmacSHA512"; else -> "HmacSHA1" }
        val mac = javax.crypto.Mac.getInstance(alg).apply { init(javax.crypto.spec.SecretKeySpec(p.secret, alg)) }.doFinal(msg)
        // 动态截断（RFC 4226 §5.3）。
        val off = mac[mac.size - 1].toInt() and 0x0f
        val bin = ((mac[off].toLong() and 0x7f) shl 24) or ((mac[off + 1].toLong() and 0xff) shl 16) or
            ((mac[off + 2].toLong() and 0xff) shl 8) or (mac[off + 3].toLong() and 0xff)
        var mod = 1L
        repeat(p.digits) { mod *= 10 }
        return (bin % mod).toString().padStart(p.digits, '0')
    }

    /** 这一轮还剩几秒（倒计时条用）。解不出密钥按 30 秒一轮算。 */
    fun secondsLeft(seed: String, nowMs: Long = System.currentTimeMillis()): Int {
        val period = parse(seed)?.period ?: 30
        val t = maxOf(0L, nowMs / 1000)
        return (period - (t % period)).toInt()
    }

    fun period(seed: String): Int = parse(seed)?.period ?: 30

    /** 「123 456」—— 三位一组好念。 */
    fun grouped(code: String): String = if (code.length != 6) code else code.take(3) + " " + code.takeLast(3)
}

/**
 * 密码生成器（iOS VaultToolViews.swift:13 `UmbraPasswordGen`）。SecureRandom 不是 Random —— 「看起来随机」和
 * 「密码学随机」是两回事。**不保证每类至少一个**（PC 保证，两端不同，照 iOS）。
 */
object PasswordGen {
    data class Options(
        val length: Int = 20,
        val upper: Boolean = true,
        val digit: Boolean = true,
        val symbol: Boolean = true,
        /** 避免易混字符 l 1 I O 0。 */
        val noAmbiguous: Boolean = true,
    )

    private val rng = java.security.SecureRandom()

    fun make(o: Options = Options()): String {
        var pool = "abcdefghijkmnpqrstuvwxyz"
        if (o.upper) pool += "ABCDEFGHJKLMNPQRSTUVWXYZ"
        if (o.digit) pool += if (o.noAmbiguous) "23456789" else "0123456789"
        if (o.symbol) pool += "!@#$%^&*-_=+?"
        if (!o.noAmbiguous) pool += "ilo01O"
        val bytes = ByteArray(maxOf(1, o.length)).also { rng.nextBytes(it) }
        // 取模会让靠前的字符略微偏多；字符集 70 出头、模 256 的偏差在这个用途下可以忽略（同 iOS 注释）。
        return String(CharArray(bytes.size) { pool[(bytes[it].toInt() and 0xff) % pool.length] })
    }
}
