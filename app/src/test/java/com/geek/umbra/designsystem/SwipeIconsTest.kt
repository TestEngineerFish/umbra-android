package com.geek.umbra.designsystem

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 设计侧批次 283 §二的两条约定，单测守住（读源码，同 DesignSystemCopyTest 的做法）：
 * ① 左滑钮的图标只能用 umbra-icons.json 登记过的名字 —— Android 曾自己起了 repeatArrows / textLines / pauseCircle，
 *    双向箭头环会读成「改成周期」、pause-circle 是「已挂起」状态，放在钮上意思全反；
 * ② 「附件已删除」的字保持 muted（faint 的 10.5 号字过不了 4.5:1），图标可以 faint。
 */
class SwipeIconsTest {
    private fun file(rel: String): File =
        listOf(File(rel), File("app/$rel")).firstOrNull { it.exists() } ?: error("读不到 $rel")

    /** 左滑钮可用的图标（Android 名 → 登记名）。新加一颗先去登记表里找，找不到就请设计侧定，别自己起名。 */
    private val registered = mapOf(
        "trash" to "trash", "ban" to "ban", "play" to "play", "briefcase" to "briefcase", "wallet" to "wallet",
        "copy" to "copy", "refreshCw" to "refresh-cw", "undo" to "undo", "edit" to "edit", "shapes" to "shapes",
        "alarmClock" to "alarm-clock", "minusCircle" to "circle-minus", // 批次 285：clock 是状态「待执行」不上钮；移除不是删除，不用 trash
        "check" to "check", "x" to "x", "stopCircle" to "stop-circle", "moon" to "moon",
        "bellOff" to "bell-off", "bellRing" to "bell-ring", // 批次 286 §四：执勤关注「静音 / 单独提醒」跟着当前状态换
    )

    @Test fun swipeIconsAreRegistered() {
        val icon = Regex("""Umbra(?:IconPaths|ExtraIcons)\.(\w+)""")
        val hits = file("src/main/java").walk().filter { it.extension == "kt" }.flatMap { f ->
            val lines = f.readLines()
            lines.indices.filter { "UmbraSwipeAction(" in lines[it] && "class UmbraSwipeAction" !in lines[it] }.flatMap { i ->
                // 参数可能折到后几行（周期记账那颗「开始 / 停止」），往下看三行。
                icon.findAll(lines.subList(i, minOf(i + 4, lines.size)).joinToString(" ")).map { "${f.name}:${i + 1} ${it.groupValues[1]}" }
            }
        }.toList()
        assertTrue("一处左滑都没扫到，扫描写坏了", hits.size > 10)
        val bad = hits.filter { it.substringAfterLast(' ') !in registered }
        assertTrue("左滑用了没登记的图标：$bad", bad.isEmpty())
    }

    /** 照登记表原文抄进来的几颗（带相对坐标、弧线、隐式 lineto）都解析得出路径 —— 重试 / 找回所在的页要联网，本机模拟器点不到。 */
    @Test fun copiedIconsParse() {
        for (d in listOf(UmbraExtraIcons.refreshCw, UmbraExtraIcons.undo, UmbraExtraIcons.edit, UmbraExtraIcons.shapes, UmbraExtraIcons.alarmClock,
            UmbraExtraIcons.bellOff, UmbraExtraIcons.bellRing, UmbraExtraIcons.mapPin)) {
            val nodes = androidx.compose.ui.graphics.vector.PathParser().parsePathString(d).toNodes()
            assertTrue("解析不出：$d", nodes.size >= 3)
        }
    }

    @Test fun attGoneTextIsMuted() {
        val src = file("src/main/java/com/geek/umbra/designsystem/UmbraAttGone.kt").readText()
        val text = src.substringAfter("BasicText(").substringBefore("autoSize")
        assertTrue("「附件已删除」的字得是 muted", "color = c.muted" in text)
    }
}
