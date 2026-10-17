// 聊天气泡里的 Markdown 链接放不放行（iOS: Features/Chat/ChatLinkRules.swift，umbra-ios#31）。
//
// 纯判据，用例照抄 iOS Tools/swifttests/ChatLinkRulesTests.swift（从一段 Markdown 原文走起：ChatInline.parse → sanitize）。
//
// 为什么要它：链接点了交给系统，任何 scheme 都会被打开（tel: 弹拨号、第三方支付深链……），而气泡正文是**不可信文本**；
// 加上 Markdown 的显示文字和目标可以不一致（`[https://apple.com](https://evil.example)`），等于能造一颗伪装的按钮。
// 两层：源头（ChatInline 解析完调 sanitize，不合格的链接剥成纯文本）+ 兜底（点击时再按 opens 判一次）。
package com.geek.umbra.feature.chat

import com.geek.umbra.net.UmbraUrl
import java.net.URI
import java.text.Normalizer

object ChatLinkRules {
    /** 只放 https，不放 http（umbra-ios#31，sam 2026-10-05 拍板）。改的时候连用例一起改。 */
    val allowedSchemes = setOf("https")

    /**
     * 解析一个链接目标。Java 的 URI 不收非 ASCII 与空格，先把它们百分号编码（iOS 的 Markdown 解析器同样会编码）。
     * 解不开返回 null（= 不是链接，按纯文本画）。
     */
    fun parseUrl(raw: String): URI? {
        val sb = StringBuilder()
        for (b in raw.trim().toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if (c <= 0x20 || c >= 0x7F || c == '"'.code || c == '<'.code || c == '>'.code || c == '\\'.code ||
                c == '^'.code || c == '`'.code || c == '{'.code || c == '|'.code || c == '}'.code
            ) sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xF])
            else sb.append(c.toChar())
        }
        return runCatching { URI(sb.toString()) }.getOrNull()?.takeIf { it.scheme != null }
    }

    /** 这个 URL 点了能不能交给系统打开：https、有 host、不带用户名 / 密码段。 */
    fun opens(url: URI): Boolean {
        val scheme = url.scheme?.lowercase() ?: return false
        if (scheme !in allowedSchemes) return false
        val host = url.host ?: return false
        if (host.isEmpty()) return false
        if (url.rawUserInfo != null) return false
        return true
    }

    fun opens(url: String): Boolean = parseUrl(url)?.let { opens(it) } ?: false

    /** 「网址字符」只认 ASCII：中文和网址常常不留空格地挨着写（「去apple.com登录」）。 */
    private val urlish = "abcdefghijklmnopqrstuvwxyz0123456789.-_:/?#@%=&+~".toSet()

    /**
     * 显示文字里**任何位置**出现的、看起来像网址的片段所声称的 host（归一过的），按出现顺序（iOS: ChatLinkRules.swift:54）。
     * 「像网址」：带 `://`、以 `www.` 开头、或裸域名且顶级域在常见名单里（`README.md` / `app.py` 不当域名）。
     * 归一：先删不可见字符，NFKC 折全角，中文句号 `。` 单独换成 `.`。
     */
    fun displayHosts(text: String): List<String> {
        val norm = Normalizer.normalize(stripInvisible(text), Normalizer.Form.NFKC).replace("。", ".").lowercase()
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        fun flush() {
            val token = cur.toString().trim { it in ".:/?#@-_" }
            cur.clear()
            if (token.isEmpty()) return
            if (token.contains("://") || token.startsWith("www.")) {
                val h = UmbraUrl.host(token)
                if (h.isNotEmpty()) out += h
                return
            }
            var hostPart = token.takeWhile { it !in "/?#:" }
            val at = hostPart.lastIndexOf('@')
            if (at >= 0) hostPart = hostPart.substring(at + 1)
            while (hostPart.endsWith(".")) hostPart = hostPart.dropLast(1)
            val labels = hostPart.split(".")
            if (labels.size < 2 || labels.any { it.isEmpty() }) return
            if (!hostPart.all { it.isLetterOrDigit() || it == '-' || it == '.' }) return
            if (labels.last() !in commonTLDs) return
            out += UmbraUrl.host(hostPart)
        }
        for (c in norm) {
            if (c.code < 0x80 && c in urlish) cur.append(c) else flush()
        }
        flush()
        return out
    }

    /**
     * 删掉显示文字里所有「不占位、不显形」的码位：通用类别 Cf 与 default-ignorable，**按类别删**（iOS: ChatLinkRules.swift:107）。
     * `apple​.com` 屏上是 apple.com，而一个零宽字符就能把它切成两段、哪段都不像域名。只用于判，不改显示。
     */
    fun stripInvisible(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.getType(cp) == Character.FORMAT.toInt() || isDefaultIgnorable(cp)) continue
            sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    /**
     * Unicode Default_Ignorable_Code_Point（DerivedCoreProperties）。JDK 没有这个属性的查询，照 Unicode 15 的表列区间。
     * 除 Cf 之外最要紧的是 U+034F（Mn）、U+115F/1160/3164/FFA0（Lo 的填充符）、U+180B–180F、E0000 段。
     */
    private fun isDefaultIgnorable(cp: Int): Boolean = when (cp) {
        0x00AD, 0x034F, 0x061C, 0x115F, 0x1160, 0x17B4, 0x17B5, 0x3164, 0xFEFF, 0xFFA0 -> true
        in 0x180B..0x180F, in 0x200B..0x200F, in 0x202A..0x202E, in 0x2060..0x206F,
        in 0xFE00..0xFE0F, in 0xFFF0..0xFFF8, in 0x1BCA0..0x1BCA3, in 0x1D173..0x1D17A, in 0xE0000..0xE0FFF -> true
        else -> false
    }

    /** 显示文字声称的第一个 host；一个都不像网址返回 null。只给用例和调试读。 */
    fun displayHost(text: String): String? = displayHosts(text).firstOrNull()

    /** 裸域名那一档认的顶级域：宁可少认，不能把文件名当域名。 */
    val commonTLDs = setOf(
        "com", "net", "org", "cn", "io", "gov", "edu", "co", "app", "dev", "me", "ai",
        "info", "xyz", "top", "uk", "jp", "us", "de", "hk", "tw", "so", "tv", "cc",
    )

    /** 显示 host 与真实 host：相同，或真实 host 是它按标签边界的子域。刻意**单向**（父域方向正是冒充）。 */
    fun hostMatches(display: String, real: String): Boolean {
        if (display.isEmpty() || real.isEmpty()) return false
        return real == display || real.endsWith(".$display")
    }

    /** 一段带链接的文字留不留链接：scheme 过闸，且显示文字里**每一个**像网址的片段都对得上真实 host。 */
    fun keeps(display: String, url: String): Boolean {
        val u = parseUrl(url) ?: return false
        if (!opens(u)) return false
        // 真实 host 取已解析的 URI（编码过的那份），再走同一套归一（小写、去尾点、去 www.）
        val real = UmbraUrl.host("https://" + (u.host ?: return false))
        return displayHosts(display).all { hostMatches(it, real) }
    }

    /** 把一段解析好的行内片段里不合格的链接剥成纯文本（按链接分组：同一个链接的几段合起来才是整段显示文字）。 */
    fun sanitize(runs: List<InlineRun>): List<InlineRun> {
        val byLink = runs.filter { it.link != null }.groupBy { it.linkId }
        val strip = byLink.filter { (_, rs) -> !keeps(rs.joinToString("") { it.text }, rs.first().link!!) }.keys
        return runs.map { if (it.link != null && it.linkId in strip) it.copy(link = null, linkId = -1) else it }
    }
}
