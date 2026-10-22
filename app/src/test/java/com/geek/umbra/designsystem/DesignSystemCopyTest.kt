package com.geek.umbra.designsystem

import com.geek.umbra.app.Brand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 设计系统公共件的文案核对（geek-99 2026-10-22）：UmbraChoice 底部「取消」曾照 iOS 写死中文，英文界面从 Cancel 退成「取消」。
 * 公共件全 App 共用，一处写死就是几十个调用点一起错，所以：① 它借用的那几条资源中英都在；② 这几个文件的代码里不许有中文字面量
 * （注释不算）。单元测试在 JVM 上跑、拿不到 Android 资源，所以直接读 res 下的 xml（同 RemoteRulesTest 的做法）。
 */
class DesignSystemCopyTest {
    private fun file(rel: String): File =
        listOf(File(rel), File("app/$rel")).firstOrNull { it.exists() } ?: error("读不到 $rel")

    /** 在 [dir] 下的 [files] 里找 [key] 的值（iOS 生成的 strings_ios.xml，或手写的 strings.xml）。 */
    private fun value(dir: String, key: String, files: List<String> = listOf("strings_ios.xml")): String? = files.firstNotNullOfOrNull { name ->
        val xml = file("src/main/res/$dir/$name").readText()
        Regex("<string name=\"${Regex.escape(key)}\">([^<]*)</string>").find(xml)?.groupValues?.get(1)
    }

    /** 选择列表 / 输入弹层的「取消」：中文「取消」，英文 Cancel。 */
    @Test fun cancelIsLocalized() {
        assertEquals("取消", value("values", "common_cancel"))
        assertEquals("Cancel", value("values-en", "common_cancel"))
    }

    /** 说明弹层（UmbraNotice）的默认钮：手写的公共键，中文「知道了」，英文 Got it。 */
    @Test fun gotItIsLocalized() {
        assertEquals("知道了", value("values", "common_gotIt", listOf("strings.xml")))
        assertEquals("Got it", value("values-en", "common_gotIt", listOf("strings.xml")))
    }

    /** 三个收口的公共件借用的其余文案，中英都得有值。 */
    @Test fun borrowedKeysHaveBothLanguages() {
        val keys = listOf(
            "common_attGone",
            "state_retry", "state_checkServer", "state_failed_title", "state_failed_reason_offline", "state_failed_reason_server",
            "state_offline_title", "state_offline_reason", "state_offline_reason_live",
        )
        for (dir in listOf("values", "values-en")) for (k in keys) {
            assertTrue("$dir 里 $k 有值", !value(dir, k).isNullOrBlank())
        }
    }

    /** UmbraAttGone / UmbraSheets（UmbraChoice、UmbraPrompt、UmbraNotice）/ UmbraStates 的代码里没有中文字面量（注释除外）。 */
    @Test fun noHardcodedChineseInSharedSheets() {
        val cjk = Regex("\"[^\"\\n]*[\\u4e00-\\u9fff][^\"\\n]*\"")
        for (name in listOf("UmbraAttGone.kt", "UmbraSheets.kt", "UmbraStates.kt")) {
            val code = file("src/main/java/com/geek/umbra/designsystem/$name").readLines()
                .map { it.substringBefore("//") }
                .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
            val hits = code.mapIndexedNotNull { i, line -> cjk.find(line)?.let { "${i + 1}: ${it.value}" } }
            assertTrue("$name 里写死了中文：$hits", hits.isEmpty())
        }
    }

    /**
     * 公共件全 App 共用，产品名写死一处就是全 App 一起错（品牌收口 10-22）：designsystem 下所有文件的字面量里
     * 不许有产品名或代号（整词，UmbraTheme 这类标识符不算）。要用产品名走 Brand.productName / R.string.brand_name。
     */
    @Test fun noHardcodedBrandInDesignSystem() {
        // 占有量词（++ / *+）：普通的 (a|b)* 每个字符递归一层，长字面量会把测试线程的栈撑爆（产品名改「长尾雀」那次就炸过）。
        val literal = Regex("\"(?:[^\"\\\\]++|\\\\.)*+\"")
        val brand = Regex("(?<![A-Za-z\\-.])(${Brand.codename}|${Brand.productNameEn})(?![A-Za-z\\-])|${Brand.productName}")
        val hits = file("src/main/java/com/geek/umbra/designsystem").walk().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                val code = if (line.trimStart().startsWith("*")) "" else line.substringBefore("//")
                literal.findAll(code).firstOrNull { brand.containsMatchIn(it.value) }?.let { "${f.name}:${i + 1} ${it.value}" }
            }
        }.toList()
        assertTrue("公共件里写死了产品名：$hits", hits.isEmpty())
    }
}
