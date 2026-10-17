package com.geek.umbra.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 「/」目录过滤（iOS SlashPanel.swift `SlashCatalog.filtered` 的口径）。 */
class SlashCatalogTest {
    @Test fun filter() {
        assertEquals(4, SlashCatalog.total)
        assertEquals("空查询返回全目录", 2, SlashCatalog.filtered("").size)
        assertEquals("按动作名", listOf("记一笔"), SlashCatalog.filtered("一笔").flatMap { it.items }.map { it.label })
        assertEquals("按英文键（大小写不敏感）", listOf("记灵感"), SlashCatalog.filtered("INSP").flatMap { it.items }.map { it.label })
        assertEquals("空组整组不出", listOf("任务"), SlashCatalog.filtered("task").map { it.name })
        assertEquals("没有匹配", 0, SlashCatalog.filtered("翻译").size)
        assertEquals("创建任务", SlashCatalog.taskAction.label)
        assertEquals("创建任务", SlashCatalog.byLabel("创建任务")?.label)
        assertNull(SlashCatalog.byLabel("不存在"))
    }
}
