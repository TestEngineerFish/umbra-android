package com.geek.umbra.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 品牌名收口（sam 10-22）：对外叫 [Brand.productName]，代号 [Brand.codename] 不上界面，产品名只在 Brand.kt 一处。
 * 单测在 JVM 上拿不到 Android 资源，直接读 res 与源码（同 DesignSystemCopyTest 的做法）。
 */
class BrandTest {
    private fun file(rel: String): File =
        listOf(File(rel), File("app/$rel")).firstOrNull { it.exists() } ?: error("读不到 $rel")

    private fun value(dir: String, file: String, key: String): String? =
        Regex("<string name=\"${Regex.escape(key)}\"[^>]*>([^<]*)</string>").find(file("src/main/res/$dir/$file").readText())?.groupValues?.get(1)

    /** 代号整词（UmbraTheme、X-Umbra-Session 这类标识符里的不算）。 */
    // 后面只排除字母：「Umbra-账目-」这种代号后跟连字符的文件名也是对外显示，第一版排除了连字符，漏掉了导出文件名。
    private val codename = Regex("(?<![A-Za-z\\-.])${Brand.codename}(?![A-Za-z])")

    /** brand.xml 是 tools/gen_brand.py 按 Brand.kt 生成的：改了 Brand.kt 忘了重跑，这里会红。 */
    @Test fun brandXmlMatchesBrandKt() {
        assertEquals(Brand.productName, value("values", "brand.xml", "app_name"))
        assertEquals(Brand.productName, value("values", "brand.xml", "brand_name"))
        // 英文表读 displayNameEn：英文名定下来之前英文界面也显示中文名（sam 10-22）。
        assertEquals(Brand.displayNameEn, value("values-en", "brand.xml", "app_name"))
        assertEquals(Brand.displayNameEn, value("values-en", "brand.xml", "brand_name"))
        assertTrue(value("values", "brand.xml", "share_insp_title")!!.contains(Brand.productName))
        assertTrue(value("values-en", "brand.xml", "share_insp_title")!!.contains(Brand.displayNameEn))
    }

    /** 所有字符串资源的**值**里没有代号（name 属性不算，注释不算）—— strings_ios.xml 也一样：gen_strings.py 生成时换掉了。 */
    @Test fun noCodenameInStringResources() {
        val hits = file("src/main/res").walk().filter { it.isFile && it.extension == "xml" && it.parentFile.name.startsWith("values") }
            .flatMap { f ->
                Regex("<string name=\"(\\w+)\"[^>]*>([^<]*)</string>").findAll(f.readText().replace(Regex("<!--[\\s\\S]*?-->"), ""))
                    .filter { codename.containsMatchIn(it.groupValues[2]) }.map { "${f.parentFile.name}/${f.name}: ${it.groupValues[1]}" }
            }.toList()
        assertTrue("资源里还有写死的代号：$hits", hits.isEmpty())
    }

    /**
     * 生成的资源里不许残留 iOS 的占位符 {brand}：iOS 收口后文案表里写的是占位符，生成时必须换成产品名。
     * 第一版 PR 就栽在这儿 —— 当时重跑 gen_strings 读到了 iOS 改到一半的工作区，16 句原样印着 {brand}，而上面那条只查代号。
     */
    @Test fun noBrandTokenInResources() {
        val hits = file("src/main/res").walk().filter { it.isFile && it.extension == "xml" && it.parentFile.name.startsWith("values") }
            .filter { Brand.token in it.readText() }.map { "${it.parentFile.name}/${it.name}" }.toList()
        assertTrue("资源里还印着占位符 ${Brand.token}：$hits", hits.isEmpty())
    }

    /**
     * 英文名定下来之前，界面上不露出 productNameEn（sam 10-22）：任何字符串资源（含生成的）里都不许有它。
     * displayNameEn 改指向 productNameEn 那天，这条自动放行。productNameEn 只给写不了中文的地方用（安装包文件名）。
     */
    @Test fun productNameEnNotOnScreen() {
        if (Brand.displayNameEn == Brand.productNameEn) return
        val hits = file("src/main/res").walk().filter { it.isFile && it.extension == "xml" && it.parentFile.name.startsWith("values") }
            .filter { Brand.productNameEn in it.readText() }.map { "${it.parentFile.name}/${it.name}" }.toList()
        assertTrue("英文名还没定，界面上不该出现 ${Brand.productNameEn}：$hits", hits.isEmpty())
    }

    /**
     * 空格规则（全端统一，geek-99 10-22）：产品名挨着汉字或中文标点时两边不留空格，挨着英文或数字时留一个。
     * 查资源的值和 Kotlin 字面量（${'$'}{Brand.productName} 那种写法）；「 · 」「 › 」这类分隔符不在中文标点里，带的空格照留。
     */
    @Test fun noSpaceBetweenChineseAndProductName() {
        val cjk = "[\\u4e00-\\u9fff\\u3000-\\u303f\\uff00-\\uffef]"
        fun bad(name: String) = Regex("$cjk +${Regex.escape(name)}|${Regex.escape(name)} +$cjk")
        val inRes = bad(Brand.productName)
        val resHits = file("src/main/res").walk().filter { it.isFile && it.extension == "xml" && it.parentFile.name.startsWith("values") }
            .flatMap { f ->
                Regex("<string name=\"(\\w+)\"[^>]*>([^<]*)</string>").findAll(f.readText())
                    .filter { inRes.containsMatchIn(it.groupValues[2]) }.map { "${f.parentFile.name}/${f.name}: ${it.groupValues[1]}" }
            }.toList()
        val inKt = bad("${'$'}{Brand.productName}")
        val ktHits = file("src/main/java").walk().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line -> if (inKt.containsMatchIn(line)) "${f.name}:${i + 1}" else null }
        }.toList()
        assertTrue("产品名和中文之间有空格：${resHits + ktHits}", resHits.isEmpty() && ktHits.isEmpty())
    }

    /** 产品名不在别处写死：手写的资源与 Kotlin 一律引用（brand_name / Brand.productName），生成文件除外。 */
    @Test fun productNameOnlyInBrand() {
        val generated = setOf("brand.xml", "strings_ios.xml", "Brand.kt")
        val hits = (file("src/main/res").walk() + file("src/main/java").walk())
            .filter { it.isFile && it.extension in setOf("xml", "kt") && it.name !in generated }
            .filter { f -> f.readText().let { Brand.productName in it || Brand.productNameEn in it } }
            .map { it.name }.toList()
        assertTrue("这些文件里写死了产品名，改成引用：$hits", hits.isEmpty())
    }

    /**
     * Kotlin 字面量里的代号只剩看不见的那几处：剪贴板 label（系统不展示）、相册目录（改名会把旧图和新图拆成两个相册，
     * 用户可见、暂缓到名字定死再统一改，见开发文档第五节「品牌名」）。新加一处带代号的界面文案会在这里红。
     */
    @Test fun noCodenameInKotlinLiterals() {
        val allowed = listOf("newPlainText(", "RELATIVE_PATH", "getExternalStoragePublicDirectory(", "const val codename")
        val literal = Regex("\"(?:[^\"\\\\]|\\\\.)*\"")
        val hits = file("src/main/java").walk().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                val code = if (line.trimStart().startsWith("*")) "" else line.substringBefore("//")
                if (allowed.any { it in code }) null
                else literal.findAll(code).firstOrNull { codename.containsMatchIn(it.value) }?.let { "${f.name}:${i + 1} ${it.value}" }
            }
        }.toList()
        assertTrue("界面文案里写死了代号，改用 Brand.productName：$hits", hits.isEmpty())
    }
}
