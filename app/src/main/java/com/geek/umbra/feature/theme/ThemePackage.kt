// 主题包（`.umbraplugin`，`type: "theme"`）的解包、验签、清单校验、文件检查，以及许可证的离线验与授权判定。
// 逐段照抄 iOS `Features/Theme/ThemePackage.swift`（它照 PC `electron/core/themes/pack.ts` / `license.ts` 写）；
// 容器格式正本是 umbra-plugins `sdk/PACKAGE-FORMAT.md`。顺序不能换：解包 → 验签 → 以 files 里那份清单为准校验 →
// 只许 manifest + tokens（T5）→ 体积（T6）→ tokens 门槛（T1～T4，[ThemeRules]）。任何一步不过就整包拒，调用方一个字节都不落盘。
//
// 三处要自己写的（同 iOS 的那三处，Android 上原因各有不同）：
//   ① gunzip：用 `Inflater(nowrap = true)` 解裸 DEFLATE，头尾自己剥、CRC32 与长度自己核 —— 不用 GZIPInputStream，
//      它会把多个 gzip 成员接着解下去，而正本只认单成员（与 iOS 同一个判法）；
//   ② canonical()：签名签的是 JS `JSON.stringify` 的精确字节，**manifest 保持书写键序** —— kotlinx 的 JsonObject 虽然保序，
//      但重复键、数字原文、转义都和 JS 不一样，所以带一个保序的小 JSON 读写器（[OJson]），转义规则照 PACKAGE-FORMAT §2.1；
//   ③ 验签：Android 28～32 系统里没有 Ed25519，用手写的 [Ed25519]。公钥是 SPKI PEM，剥掉 12 字节 DER 前缀就是 32 字节原始公钥。
// 三样都由测试向量钉着（`app/src/test/resources/theme/`：canonical 精确字节、临时钥匙签的包、未签名包）。
//
// ⚠️ 只许用 JVM 标准库与 kotlinx.serialization，不许 import android.*：JUnit 直接跑它。
package com.geek.umbra.feature.theme

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Inflater

// MARK: - 保序 JSON

/** 保序的 JSON 值：对象是有序的键值对（canonical 要照书写顺序输出 manifest）。数字留原文。 */
sealed interface OJson {
    data class Obj(val kv: List<kotlin.Pair<String, OJson>>) : OJson
    data class Arr(val xs: List<OJson>) : OJson
    data class Str(val s: String) : OJson
    data class Num(val raw: String) : OJson
    data class Bool(val b: Boolean) : OJson
    data object Null : OJson

    /** 取对象里的键：重复的键照 JS 取最后一个。 */
    operator fun get(key: String): OJson? = (this as? Obj)?.kv?.lastOrNull { it.first == key }?.second

    val string: String? get() = (this as? Str)?.s

    /** 序列化成 JS `JSON.stringify` 的样子（无空白；对象键序照 JS：数组下标样的键升序在前，其余按插入顺序）。 */
    fun stringify(): String = when (this) {
        Null -> "null"
        is Bool -> if (b) "true" else "false"
        is Num -> jsNumber(raw)
        is Str -> quote(s)
        is Arr -> xs.joinToString(",", "[", "]") { it.stringify() }
        is Obj -> jsOrder(kv).joinToString(",", "{", "}") { "${quote(it.first)}:${it.second.stringify()}" }
    }

    companion object {
        /** 解析 UTF-8 文本。坏了回 null。重复的键照 JS：值取最后一个、位置留第一次出现的地方（在 [jsOrder] 里合并）。 */
        fun parse(data: ByteArray): OJson? {
            val p = OJsonParser(data)
            p.skipWs()
            val v = p.value() ?: return null
            p.skipWs()
            return if (p.i == data.size) v else null
        }

        /** JS 对象的键序：形如数组下标的键（"0"、"42"，< 2³² − 1）按数值升序排在最前，其余按插入顺序（PACKAGE-FORMAT §四）。 */
        fun jsOrder(kv: List<kotlin.Pair<String, OJson>>): List<kotlin.Pair<String, OJson>> {
            val seen = HashMap<String, Int>()
            val uniq = mutableListOf<kotlin.Pair<String, OJson>>()
            for ((k, v) in kv) {
                val i = seen[k]
                if (i != null) uniq[i] = k to v else { seen[k] = uniq.size; uniq += k to v }
            }
            fun index(k: String): Long? {
                if (k.isEmpty() || !k.all { it in '0'..'9' } || (k != "0" && k.startsWith("0"))) return null
                val n = k.toBigIntegerOrNull() ?: return null
                return if (n < java.math.BigInteger.valueOf(4_294_967_295L)) n.toLong() else null
            }
            val idx = uniq.filter { index(it.first) != null }.sortedBy { index(it.first)!! }
            return idx + uniq.filter { index(it.first) == null }
        }

        /**
         * 字符串转义（PACKAGE-FORMAT §2.1 第 5 条）：只转 `"`、`\`、U+0000–U+001F；`\b \f \n \r \t` 短形式，其余 `\u00xx` 小写；
         * 非 ASCII、`/`、U+007F、U+2028 / 2029 都不转。
         */
        fun quote(s: String): String {
            val out = StringBuilder("\"")
            for (ch in s) {
                when (ch) {
                    '"' -> out.append("\\\"")
                    '\\' -> out.append("\\\\")
                    '\b' -> out.append("\\b")
                    '\u000C' -> out.append("\\f")
                    '\n' -> out.append("\\n")
                    '\r' -> out.append("\\r")
                    '\t' -> out.append("\\t")
                    else -> if (ch.code < 0x20) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", ch.code)) else out.append(ch)
                }
            }
            return out.append('"').toString()
        }

        /** 数字照 JS 的写法：整数原样（去掉 `-0` 这类）；规格不许清单里出现浮点，真出现了按最短十进制写（签名多半对不上，那正是该拒的）。 */
        fun jsNumber(raw: String): String {
            raw.toLongOrNull()?.let { return it.toString() }
            val d = raw.toDoubleOrNull() ?: return raw
            if (d == Math.rint(d) && kotlin.math.abs(d) < 1e21) return d.toLong().toString()
            return d.toString()
        }
    }
}

/** 一个很小的递归下降 JSON 读取器（只为保序与留住数字原文）。 */
private class OJsonParser(val b: ByteArray) {
    var i = 0

    private fun at(k: Int = i): Int = b[k].toInt() and 0xFF

    fun skipWs() {
        while (i < b.size && at() in intArrayOf(0x20, 0x09, 0x0A, 0x0D)) i++
    }

    fun value(): OJson? {
        skipWs()
        if (i >= b.size) return null
        return when (at()) {
            0x7B -> obj()
            0x5B -> arr()
            0x22 -> str()?.let { OJson.Str(it) }
            0x74 -> lit("true", OJson.Bool(true))
            0x66 -> lit("false", OJson.Bool(false))
            0x6E -> lit("null", OJson.Null)
            else -> num()
        }
    }

    private fun lit(w: String, v: OJson): OJson? {
        val u = w.toByteArray()
        if (i + u.size > b.size || !b.copyOfRange(i, i + u.size).contentEquals(u)) return null
        i += u.size
        return v
    }

    private fun num(): OJson? {
        val start = i
        while (i < b.size && at().let { (it in 0x30..0x39) || it in intArrayOf(0x2D, 0x2B, 0x2E, 0x65, 0x45) }) i++
        if (i == start) return null
        val s = String(b, start, i - start, Charsets.UTF_8)
        return if (s.toDoubleOrNull() != null) OJson.Num(s) else null
    }

    fun str(): String? {
        if (i >= b.size || at() != 0x22) return null
        i++
        val out = java.io.ByteArrayOutputStream()
        while (i < b.size) {
            val c = at()
            if (c == 0x22) { i++; return String(out.toByteArray(), Charsets.UTF_8) }
            if (c == 0x5C) {
                i++
                if (i >= b.size) return null
                val e = at()
                i++
                when (e) {
                    0x22 -> out.write(0x22)
                    0x5C -> out.write(0x5C)
                    0x2F -> out.write(0x2F)
                    0x62 -> out.write(0x08)
                    0x66 -> out.write(0x0C)
                    0x6E -> out.write(0x0A)
                    0x72 -> out.write(0x0D)
                    0x74 -> out.write(0x09)
                    0x75 -> {
                        var cp = hex4() ?: return null
                        // 代理对
                        if (cp in 0xD800..0xDBFF && i + 1 < b.size && at() == 0x5C && at(i + 1) == 0x75) {
                            i += 2
                            val lo = hex4()?.takeIf { it in 0xDC00..0xDFFF } ?: return null
                            cp = 0x10000 + ((cp - 0xD800) shl 10) + (lo - 0xDC00)
                        }
                        if (cp in 0xD800..0xDFFF) return null       // 落单的代理（Swift 的 Unicode.Scalar 同样不收）
                        out.write(String(Character.toChars(cp)).toByteArray(Charsets.UTF_8))
                    }
                    else -> return null
                }
            } else {
                out.write(c)
                i++
            }
        }
        return null
    }

    private fun hex4(): Int? {
        if (i + 4 > b.size) return null
        val v = String(b, i, 4, Charsets.US_ASCII).toIntOrNull(16) ?: return null
        i += 4
        return v
    }

    private fun arr(): OJson? {
        i++
        val xs = mutableListOf<OJson>()
        skipWs()
        if (i < b.size && at() == 0x5D) { i++; return OJson.Arr(xs) }
        while (true) {
            xs += value() ?: return null
            skipWs()
            if (i >= b.size) return null
            when (at()) {
                0x2C -> i++
                0x5D -> { i++; return OJson.Arr(xs) }
                else -> return null
            }
        }
    }

    private fun obj(): OJson? {
        i++
        val kv = mutableListOf<kotlin.Pair<String, OJson>>()
        skipWs()
        if (i < b.size && at() == 0x7D) { i++; return OJson.Obj(kv) }
        while (true) {
            skipWs()
            val k = str() ?: return null
            skipWs()
            if (i >= b.size || at() != 0x3A) return null
            i++
            val v = value() ?: return null
            kv += k to v
            skipWs()
            if (i >= b.size) return null
            when (at()) {
                0x2C -> i++
                0x7D -> { i++; return OJson.Obj(kv) }
                else -> return null
            }
        }
    }
}

// MARK: - gzip

object ThemeGzip {
    /** 解一个单成员 gzip 流（RFC 1952）。头不对 / 解不开 / CRC 或长度对不上 / 后面还拖着东西 → null。 */
    fun gunzip(data: ByteArray): ByteArray? {
        val b = data
        fun u(k: Int) = b[k].toInt() and 0xFF
        if (b.size < 18 || u(0) != 0x1F || u(1) != 0x8B || u(2) != 8) return null
        val flg = u(3)
        var p = 10
        if (flg and 0x04 != 0) {                    // FEXTRA
            if (p + 2 > b.size) return null
            p += 2 + u(p) + (u(p + 1) shl 8)
        }
        if (flg and 0x08 != 0) { while (p < b.size && u(p) != 0) p++; p++ }   // FNAME
        if (flg and 0x10 != 0) { while (p < b.size && u(p) != 0) p++; p++ }   // FCOMMENT
        if (flg and 0x02 != 0) p += 2               // FHCRC
        if (p >= b.size - 8) return null
        val inf = Inflater(true)
        val out = java.io.ByteArrayOutputStream()
        try {
            inf.setInput(b, p, b.size - 8 - p)
            val buf = ByteArray(8192)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) return null
                out.write(buf, 0, n)
            }
            // 压缩流之后、尾巴之前不许还有东西（iOS 那边整段交给 zlib，多出来的字节同样解不开）
            if (inf.remaining != 0) return null
        } catch (_: java.util.zip.DataFormatException) {
            return null
        } finally {
            inf.end()
        }
        val raw = out.toByteArray()
        val t = b.size - 8
        val crc = u(t).toLong() or (u(t + 1).toLong() shl 8) or (u(t + 2).toLong() shl 16) or (u(t + 3).toLong() shl 24)
        val size = u(t + 4).toLong() or (u(t + 5).toLong() shl 8) or (u(t + 6).toLong() shl 16) or (u(t + 7).toLong() shl 24)
        val c = CRC32().apply { update(raw) }.value
        if (c != crc || (raw.size.toLong() and 0xFFFFFFFFL) != size) return null
        return raw
    }
}

// MARK: - 签名

object ThemeSignature {
    /** Ed25519 的 SPKI DER 前缀（30 2a 30 05 06 03 2b 65 70 03 21 00），后面跟 32 字节原始公钥。 */
    val SPKI_PREFIX = byteArrayOf(0x30, 0x2A, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x70, 0x03, 0x21, 0x00)

    /** SPKI PEM → 32 字节原始公钥。形状不对回 null。 */
    fun publicKey(pem: String): ByteArray? {
        val body = pem.split("\n").filter { !it.startsWith("-----") }.joinToString("")
        val der = runCatching { Base64.getDecoder().decode(body) }.getOrNull() ?: return null
        if (der.size != 44 || !der.copyOfRange(0, 12).contentEquals(SPKI_PREFIX)) return null
        return der.copyOfRange(12, 44)
    }

    /** 纯 Ed25519（RFC 8032，不预哈希）验一段字节。 */
    fun verify(message: ByteArray, signatureB64: String, pem: String): Boolean {
        val key = publicKey(pem) ?: return false
        val sig = runCatching { Base64.getDecoder().decode(signatureB64) }.getOrNull() ?: return false
        return Ed25519.verify(key, message, sig)
    }
}

// MARK: - 包

/** 解开的包：清单副本（参与签名）、文件表（路径 → base64）、签名。**真正生效的清单是 `files["manifest.json"]`**。 */
data class ThemePackageFile(val manifest: OJson, val files: Map<String, String>, val signature: String?)

data class ThemeManifest(
    val id: String,
    val name: String,
    val version: String,
    val themeApi: String,
    val tokens: String,
    val author: String,
    val brief: String,
)

/** 用不了的原因归四类（themeKit.fallbackCopy 一类一句话）：包坏了 / 验签不过 / 配色不过线 / 授权。 */
enum class ThemeFailKind { Broken, Signature, Contrast, License }

data class ThemeFail(val kind: ThemeFailKind, val why: String)

/** 过了全部检查的主题：清单、tokens 原文、本端生效的整套。 */
data class InspectedTheme(val manifest: ThemeManifest, val tokens: JsonElement, val palette: ThemePalette)

/** 成功 / 失败二选一（Swift 的 Result<_, ThemeFail>）。 */
sealed interface ThemeResult<out T> {
    data class Ok<T>(val value: T) : ThemeResult<T>
    data class Err(val fail: ThemeFail) : ThemeResult<Nothing>
}

object ThemePackage {
    /** 反写域名式 id —— 兼做目录名，字符集卡死（同 PC / Studio）。 */
    val ID_PATTERN = Regex("^[a-z0-9]+(\\.[a-z0-9-]+){1,4}$")
    /** 版本号也兼做目录名。 */
    val VERSION_PATTERN = Regex("^[0-9]+(\\.[0-9]+){0,2}(-[0-9a-z.]+)?$")
    private val API_PATTERN = Regex("^\\^?1(?![0-9])")
    /** 插件才有的字段：主题包里出现就拒（正本 manifest.schema.json 的主题分支）。 */
    val PLUGIN_ONLY = listOf("surfaces", "hostApi", "permissions", "kinds", "panels", "ui", "tools")

    private fun broken(why: String) = ThemeFail(ThemeFailKind.Broken, why)

    /** gunzip → 保序解析 → 形状。坏了回 broken。 */
    fun decode(data: ByteArray): ThemeResult<ThemePackageFile> {
        val raw = ThemeGzip.gunzip(data) ?: return ThemeResult.Err(broken("这不是一个 .umbraplugin 包（gzip 解不开）"))
        val top = OJson.parse(raw) ?: return ThemeResult.Err(broken("包里不是合法 JSON"))
        if (top !is OJson.Obj) return ThemeResult.Err(broken("包里不是对象"))
        if (top["format"] != OJson.Num("1")) return ThemeResult.Err(broken("包格式版本认不了，这一版只认 1"))
        val fkv = (top["files"] as? OJson.Obj)?.kv ?: return ThemeResult.Err(broken("包里没有 files"))
        val man = top["manifest"]?.takeIf { it is OJson.Obj } ?: return ThemeResult.Err(broken("包里没有清单副本"))
        val files = LinkedHashMap<String, String>()
        for ((k, v) in fkv) files[k] = v.string ?: return ThemeResult.Err(broken("包里的文件内容不是 base64 字符串"))
        return ThemeResult.Ok(ThemePackageFile(man, files, top["signature"]?.string))
    }

    /** 签名签的那段字节（PACKAGE-FORMAT §二）：`{"format":1,"manifest":<副本原序>,"files":<按 UTF-16 code unit 升序>}` 的 UTF-8。 */
    fun canonical(pkg: ThemePackageFile): ByteArray {
        // Kotlin 的 String.compareTo 就是逐个 UTF-16 code unit 比（同 JS 的默认排序）
        val keys = pkg.files.keys.sorted()
        val files = OJson.Obj(keys.map { it to OJson.Str(pkg.files[it].orEmpty()) })
        return "{\"format\":1,\"manifest\":${pkg.manifest.stringify()},\"files\":${files.stringify()}}".toByteArray(Charsets.UTF_8)
    }

    /** 验签。⚠️ 没签名就拒（App 里没有开发模式那条后门）。 */
    fun verify(pkg: ThemePackageFile, pem: String): ThemeFail? {
        val sig = pkg.signature
        if (sig.isNullOrEmpty()) return ThemeFail(ThemeFailKind.Signature, "这个包没有签名，拒绝安装")
        return if (ThemeSignature.verify(canonical(pkg), sig, pem)) null
        else ThemeFail(ThemeFailKind.Signature, "签名验不过 —— 包被改过，或者不是我们签的")
    }

    private fun JsonElement?.s(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** 主题清单（正本 manifest.schema.json 的 theme 分支，同 PC `checkThemeManifest`）。 */
    fun checkManifest(raw: JsonElement?): ThemeResult<ThemeManifest> {
        fun bad(why: String) = ThemeResult.Err(broken(why))
        val m = raw as? JsonObject ?: return bad("清单不是对象")
        if (m["type"].s() != "theme") return bad("这不是主题包（清单 type 不是 theme）")
        val id = m["id"].s()?.takeIf { ID_PATTERN.containsMatchIn(it) } ?: return bad("清单 id 形状不对")
        val name = m["name"].s()?.takeIf { it.trim(' ').isNotEmpty() } ?: return bad("清单缺 name")
        val version = m["version"].s()?.takeIf { VERSION_PATTERN.containsMatchIn(it) } ?: return bad("清单 version 形状不对")
        val api = m["themeApi"].s()?.takeIf { API_PATTERN.containsMatchIn(it) } ?: return bad("这个主题要的主题键版本本机不认（只认 ^1）")
        val modes = (m["modes"] as? JsonArray)?.map { it.s() }
        // iOS `as? [String]`：数组里混了非字符串整条不算
        if (modes == null || modes.any { it == null } || "light" !in modes || "dark" !in modes) return bad("主题必须浅、深两档都有")
        val tokens = m["tokens"].s()
        if (tokens.isNullOrEmpty() || tokens.contains('\\') || tokens.startsWith("/") || ".." in tokens.split("/")) return bad("清单 tokens 路径不对")
        for (k in PLUGIN_ONLY) if (m[k] != null) return bad("主题包里不许有 $k")
        return ThemeResult.Ok(ThemeManifest(id, name, version, api, tokens, m["author"].s().orEmpty(), m["brief"].s().orEmpty()))
    }

    private fun jsonOf(b64: String): JsonElement? {
        val d = runCatching { Base64.getDecoder().decode(b64) }.getOrNull() ?: return null
        return runCatching { Json.parseToJsonElement(String(d, Charsets.UTF_8)) }.getOrNull()
    }

    /** 一份文件表装的是不是合格的主题：清单 → T5 只许 manifest + tokens → T6 体积 → tokens 门槛。验签由调用方先做。 */
    fun inspectFiles(files: Map<String, String>): ThemeResult<InspectedTheme> {
        val raw = files["manifest.json"] ?: return ThemeResult.Err(broken("包里没有 manifest.json"))
        val man = jsonOf(raw) ?: return ThemeResult.Err(broken("manifest.json 不是合法 JSON"))
        val manifest = when (val r = checkManifest(man)) {
            is ThemeResult.Err -> return r
            is ThemeResult.Ok -> r.value
        }
        for (rel in files.keys) if (rel != "manifest.json" && rel != manifest.tokens) {
            return ThemeResult.Err(broken("主题包里只许有 manifest.json 和 ${manifest.tokens}：$rel 不该在这里"))
        }
        val b64 = files.values.sumOf { it.toByteArray(Charsets.UTF_8).size }
        if (b64 > ThemeRules.MAX_PACKAGE_BASE64_BYTES) {
            val kb = String.format(java.util.Locale.ROOT, "%.1f", b64 / 1024.0)
            return ThemeResult.Err(broken("主题包太大（$kb KB，上限 ${ThemeRules.MAX_PACKAGE_BASE64_BYTES / 1024} KB）"))
        }
        val tok = files[manifest.tokens] ?: return ThemeResult.Err(broken("包里没有 ${manifest.tokens}"))
        val tokens = jsonOf(tok) ?: return ThemeResult.Err(broken("${manifest.tokens} 不是合法 JSON"))
        val chk = ThemeRules.check(tokens)
        val palette = chk.palette
        if (!chk.ok || palette == null) {
            val shape = chk.problems.any { it.rule == "T1" }
            return ThemeResult.Err(ThemeFail(if (shape) ThemeFailKind.Broken else ThemeFailKind.Contrast, "配色没过校验：${ThemeRules.problemsText(chk.problems)}"))
        }
        return ThemeResult.Ok(InspectedTheme(manifest, tokens, palette))
    }

    /** 整包：解包 → 验签 → 文件。 */
    fun inspect(data: ByteArray, pem: String): ThemeResult<InspectedTheme> {
        val pkg = when (val r = decode(data)) {
            is ThemeResult.Err -> return r
            is ThemeResult.Ok -> r.value
        }
        verify(pkg, pem)?.let { return ThemeResult.Err(it) }
        return inspectFiles(pkg.files)
    }
}

// MARK: - 许可证

/** 许可证里的一条授权。`until` null = 永久；`src`：怎么来的（`membership` = 随会员）。 */
data class ThemeGrant(val plugin: String, val from: String? = null, val until: String? = null, val src: String? = null)

/** 验过的许可证：签给谁（`sub`，账号 id 的字符串）、授权表。 */
data class ThemeLicenseDoc(val sub: String?, val grants: List<ThemeGrant>)

object ThemeLicense {
    private fun bad(why: String) = ThemeResult.Err(ThemeFail(ThemeFailKind.License, why))
    private fun JsonElement?.s(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    /**
     * 验一份许可证文本 `{"payload": "<JSON 字符串>", "signature": "<base64>"}`。
     * ⚠️ **签的是 payload 那个字符串本身**，不是它解析出来的对象（重序列化字节会变）。验过回授权表与签给的账号，不过回原因。
     * `sub` 是 Android 比 iOS 多读的一项：许可证按账号签，本机读取时要和当前账号核对（PC #128：整机一份、不核账号，换号后照用上一个人的授权）。
     */
    fun verify(raw: String, pem: String): ThemeResult<ThemeLicenseDoc> {
        val wrap = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return bad("许可证不是合法 JSON")
        val payload = wrap["payload"].s()
        val sig = wrap["signature"].s()
        if (payload == null || sig == null) return bad("许可证缺 payload 或 signature")
        if (!ThemeSignature.verify(payload.toByteArray(Charsets.UTF_8), sig, pem)) return bad("许可证签名验不过 —— 被改过，或者不是我们签的")
        val lic = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
        val grants = lic?.get("grants") as? JsonArray ?: return bad("许可证里没有 grants")
        // iOS `as? [[String: Any]]`：数组里混了非对象整条不算
        if (grants.any { it !is JsonObject }) return bad("许可证里没有 grants")
        val sub = (lic["sub"] as? JsonPrimitive)?.content
        return ThemeResult.Ok(ThemeLicenseDoc(sub, grants.mapNotNull { e ->
            val g = e as JsonObject
            val p = g["plugin"].s() ?: return@mapNotNull null
            ThemeGrant(p, g["from"].s(), g["until"].s(), g["src"].s())
        }))
    }

    /** 服务端回包 → 可以直接存盘的许可证文本。⚠️ 不重新序列化 payload。 */
    fun text(payload: String?, signature: String?): String? {
        if (payload.isNullOrEmpty() || signature.isNullOrEmpty()) return null
        return OJson.Obj(listOf("payload" to OJson.Str(payload), "signature" to OJson.Str(signature))).stringify()
    }

    /**
     * 这份许可证能不能给这个账号用：签给的 `sub` 要等于账号 id。账号不明（没登录）一律不能用 ——
     * 换号 / 登出时本机许可证已经清掉了（`ThemeStore`），这里是读取时的第二道。
     */
    fun belongsTo(doc: ThemeLicenseDoc, accountId: String?): Boolean =
        !accountId.isNullOrEmpty() && doc.sub == accountId

    /** 授权里的日期：`2026-11-01`（按 UTC 零点，同 JS `new Date`）或 ISO 8601 时刻。认不出回 null。 */
    fun date(s: String): Instant? {
        runCatching { return OffsetDateTime.parse(s).toInstant() }
        runCatching { return Instant.parse(s) }
        runCatching { return LocalDate.parse(s).atStartOfDay().toInstant(ZoneOffset.UTC) }
        return null
    }

    enum class State { Active, Expired, NotYet, Unlicensed }

    data class Entitlement(val state: State, val until: String? = null, val src: String? = null)

    /** 纯判据：这个主题在 `now` 这一刻的授权状态。多条 grant 取最宽松的那条（买断盖过过期的试用），同 PC `entitlementFrom`。 */
    fun entitlement(grants: List<ThemeGrant>?, id: String, now: Instant): Entitlement {
        if (grants == null) return Entitlement(State.Unlicensed)
        val mine = grants.filter { it.plugin == id }
        if (mine.isEmpty()) return Entitlement(State.Unlicensed)
        fun started(g: ThemeGrant) = g.from?.let(::date)?.let { it <= now } ?: true
        fun notEnded(g: ThemeGrant) = g.until?.let(::date)?.let { it >= now } ?: (g.until == null)
        fun untilOf(g: ThemeGrant) = g.until?.let(::date) ?: Instant.MIN
        val usable = mine.filter { started(it) && notEnded(it) }
        if (usable.isNotEmpty()) {
            usable.firstOrNull { it.until == null }?.let { return Entitlement(State.Active, null, it.src) }
            val latest = usable.maxBy(::untilOf)
            return Entitlement(State.Active, latest.until, latest.src)
        }
        if (mine.all { !started(it) }) return Entitlement(State.NotYet)
        val last = mine.filter { it.until != null }.maxByOrNull(::untilOf)
        return Entitlement(State.Expired, last?.until, last?.src)
    }
}

// MARK: - Android 商城里的主题怎么卖（sam 10-21：Android 卖付费主题，积分直接买）

/**
 * iOS 那套「iOS 不卖」（`ThemeShopRules` / `IOSSaleRules`，App Store 3.1.1）Android 不移：这里付费主题照常列、用积分买断，
 * 走的就是现有的 `POST /plugins/{id}/buy`（同 PC `acquireTheme`）。只留 iOS #94 那条教训：**价格解不出来按付费算、而且不卖** ——
 * 当 0 的话付费主题会以「免费」身份一路走到购买，不问一句就扣了积分。
 */
object ThemeShopRules {
    /** 目录里 `price` 解不出来时填的值。 */
    const val UNKNOWN_PRICE = Int.MAX_VALUE

    /** 没有有效授权时，点「获取」要不要先问一句「将扣 N 积分」：标价大于 0 的都问（免费的直接领，扣 0）。 */
    fun needsConfirm(price: Int): Boolean = price > 0

    /** 能不能向服务端买 / 领（`POST /plugins/{id}/buy`）：价格读得出、不是负数。解不出 / 负数一律不调 —— 不知道要扣多少，就不扣。 */
    fun mayBuy(price: Int): Boolean = price in 0 until UNKNOWN_PRICE
}

// MARK: - 发布方公钥

object ThemePublisher {
    /**
     * 发布方公钥（Ed25519，SPKI PEM）：验主题包签名与许可证两处都用它，和服务端 ECS 上的私钥配对。
     * 和 iOS `ThemePublisher.pem`、UmbraPC `keys/publisher.pub`、UmbraStudio `keys/publisher.pub` 是同一把。
     * 内嵌而不是读资源：`ThemeRulesTest` 钉着它与夹具副本（复制自 PC）逐字相同。
     */
    const val PEM = "-----BEGIN PUBLIC KEY-----\nMCowBQYDK2VwAyEAR8cW6zks50HV5zBwKeGiHW5ooccqRIGv3Vwo2orIOCQ=\n-----END PUBLIC KEY-----\n"
}
