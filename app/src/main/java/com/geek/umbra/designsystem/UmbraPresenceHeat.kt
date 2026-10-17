// 使用记录方格 / 作息浓淡条的 5 档热度色（iOS: Features/Presence/PresenceViews.swift `PresenceHeat`）。
// 不进 UmbraTokens（iOS 同）：这五个值只在这张表上用，是填充色不是字色；单独成文件以免和别的模块撞车。
package com.geek.umbra.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 0 档灰（track，和进度条槽同一个灰），1–4 档主题橙由浅到深。深色模式**倒过来**取（同 GitHub 暗色主题）：
 * 暗底上「深橙」反而看不见，档位越高要越亮。
 */
object UmbraPresenceHeat {
    private val light = listOf(Color(0xFFFDDCC4), Color(0xFFF8AC78), Color(0xFFEE7B35), Color(0xFFC2410C))
    private val dark = listOf(Color(0xFF4A2A16), Color(0xFF7C3A12), Color(0xFFB9500F), Color(0xFFF0731F))

    @Composable
    fun color(level: Int): Color {
        val c = UmbraTheme.colors
        val lv = level.coerceIn(0, 4)
        if (lv == 0) return c.track
        return (if (c.isDark) dark else light)[lv - 1]
    }
}
