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
    private val codename = Regex("(?<![A-Za-z\\-.])${Brand.codename}(?![A-Za-z\\-])")

    /** brand.xml 是 tools/gen_brand.py 按 Brand.kt 生成的：改了 Brand.kt 忘了重跑，这里会红。 */
    @Test fun brandXmlMatchesBrandKt() {
        assertEquals(Brand.productName, value("values", "brand.xml", "app_name"))
        assertEquals(Brand.productName, value("values", "brand.xml", "brand_name"))
        assertEquals(Brand.productNameEn, value("values-en", "brand.xml", "app_name"))
        assertEquals(Brand.productNameEn, value("values-en", "brand.xml", "brand_name"))
        assertTrue(value("values", "brand.xml", "share_insp_title")!!.contains(Brand.productName))
        assertTrue(value("values-en", "brand.xml", "share_insp_title")!!.contains(Brand.productNameEn))
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
