package com.geek.umbra.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 照抄 iOS Tools/swifttests/ChatLinkRulesTests.swift：从一段 Markdown 原文走起（ChatInline.parse = 解析 + sanitize）。
 */
class ChatLinkRulesTest {
    private fun render(md: String) = ChatInline.parse(md)
    private fun hasLink(md: String) = render(md).any { it.link != null }
    private fun text(md: String) = render(md).joinToString("") { it.text }

    @Test fun schemeCases() {
        assertEquals("tel: 剥掉", false, hasLink("[x](tel:1)"))
        assertEquals("tel: 剥链接不吞字", "x", text("[x](tel:1)"))
        assertEquals(false, hasLink("[x](shortcuts://run-shortcut?name=wipe)"))
        assertEquals(false, hasLink("[x](sms:10086)"))
        assertEquals(false, hasLink("[x](facetime:a@b.com)"))
        assertEquals(false, hasLink("[x](javascript:alert(1))"))
        assertEquals(false, hasLink("[x](alipays://platformapi/startapp)"))
        assertEquals("尖括号自动链接的 tel: 也剥掉", false, hasLink("<tel:123>"))
        assertEquals("http 不放行", false, hasLink("[x](http://a.com)"))
        assertEquals(true, hasLink("[文档](https://a.com/doc)"))
        assertEquals("scheme 大小写不敏感", true, hasLink("[x](HTTPS://a.com)"))
        assertEquals("https 但没有 host 剥掉", false, hasLink("[x](https:nohost)"))
        assertEquals("带 userinfo 的剥掉", false, hasLink("[x](https://apple.com@evil.example)"))
        assertEquals(false, ChatLinkRules.opens("tel:1"))
        assertEquals(true, ChatLinkRules.opens("https://a.com"))
    }

    @Test fun disguiseCases() {
        assertEquals(false, hasLink("[https://a.com](https://b.com)"))
        assertEquals("https://a.com", text("[https://a.com](https://b.com)"))
        assertEquals(false, hasLink("[https://apple.com](https://evil.example)"))
        assertEquals(false, hasLink("[apple.com](https://evil.example)"))
        assertEquals(false, hasLink("[www.apple.com](https://evil.example)"))
        assertEquals(false, hasLink("[apple.com](https://apple.com.evil.example)"))
        assertEquals(false, hasLink("[apple.com](https://com/x)"))
        assertEquals(false, hasLink("[**https://a.com**](https://b.com)"))
        assertEquals(true, hasLink("[https://a.com](https://a.com/path)"))
        assertEquals(true, hasLink("[apple.com](https://www.apple.com)"))
        assertEquals(true, hasLink("[apple.com](https://support.apple.com/kb)"))
        assertEquals(true, hasLink("[点这里](https://b.com)"))
        assertEquals(true, hasLink("[README.md](https://github.com/x/y)"))
        assertEquals(true, hasLink("[app.py](https://github.com/x/y)"))
        assertEquals(true, hasLink("前文 [https://a.com](https://b.com) 中间 [好的](https://c.com)"))
        assertEquals(false, hasLink("[去 apple.com 登录](https://evil.example)"))
        assertEquals("去 apple.com 登录", text("[去 apple.com 登录](https://evil.example)"))
        assertEquals(false, hasLink("[去apple.com登录](https://evil.example)"))
        assertEquals(false, hasLink("[apple.com.](https://evil.example)"))
        assertEquals(false, hasLink("[请访问 apple.com.](https://evil.example)"))
        assertEquals(false, hasLink("[apple。com](https://evil.example)"))
        assertEquals(false, hasLink("[apple．com](https://evil.example)"))
        assertEquals(false, hasLink("[ａｐｐｌｅ.com](https://evil.example)"))
        assertEquals(false, hasLink("[登录 https://apple.com 继续](https://evil.example)"))
        assertEquals(false, hasLink("[apple.com 和 evil.example](https://evil.example)"))
        assertEquals(false, hasLink("[联系 support@apple.com](https://evil.example)"))
        assertEquals(false, hasLink("[apple​.com](https://evil.example)"))
        assertEquals(false, hasLink("[apple⁠.com](https://evil.example)"))
        assertEquals(false, hasLink("[apple­.com](https://evil.example)"))
        assertEquals(false, hasLink("[apple﻿.com](https://evil.example)"))
        assertEquals(false, hasLink("[apple‍.com](https://evil.example)"))
        assertEquals(false, hasLink("[apple.‌com](https://evil.example)"))
        assertEquals(false, hasLink("[apple‬.com](https://evil.example)"))
        assertEquals(false, hasLink("[apple͏.com](https://evil.example)"))
        assertEquals(false, hasLink("[appleㅤ.com](https://evil.example)"))
        assertEquals(false, hasLink("[apple${String(Character.toChars(0xE0020))}.com](https://evil.example)"))
        assertEquals(false, hasLink("[去 apple​.com 登录](https://evil.example)"))
        assertEquals("剥链接不改原文", "apple​.com", text("[apple​.com](https://evil.example)"))
        assertEquals(true, hasLink("[apple​.com](https://apple.com)"))
        assertEquals(true, hasLink("[去 apple.com 登录](https://apple.com/login)"))
        assertEquals(true, hasLink("[apple。com](https://support.apple.com)"))
        assertEquals(true, hasLink("[看 README.md 第三节](https://github.com/x/y)"))
        assertEquals(true, hasLink("[打开 app.py 和 main.swift](https://github.com/x/y)"))
        assertEquals(true, hasLink("[v1.2.3 发布说明](https://github.com/x/y/releases)"))
        assertEquals(true, hasLink("[详情见此。](https://a.com)"))
        assertEquals(true, hasLink("[第 1。2 节](https://a.com)"))
        val links = render("[https://a.com](https://b.com) 和 [好的](https://c.com)").mapNotNull { it.link }.distinct()
        assertEquals("一段里两条链接：留下的正是没伪装那条", "https://c.com", links.joinToString(","))
    }

    @Test fun displayHostCases() {
        assertEquals("apple.com", ChatLinkRules.displayHost("https://Apple.com/x"))
        assertEquals("apple.com", ChatLinkRules.displayHost("www.apple.com"))
        assertEquals("apple.com", ChatLinkRules.displayHost("apple.com/path"))
        assertEquals(null, ChatLinkRules.displayHost("点这里"))
        assertEquals("b.com", ChatLinkRules.displayHosts("a b.com").joinToString(","))
        assertEquals("apple.com,foo.io", ChatLinkRules.displayHosts("去 apple.com. 或 www.foo.io/x").joinToString(","))
        assertEquals(null, ChatLinkRules.displayHost("README.md"))
    }

    /** 行内样式（Android 自己的解析器，iOS 交给系统）：粗体 / 代码 / 下划线不误伤。 */
    @Test fun inlineStyles() {
        val r = ChatInline.parse("**开发步骤**：1. `npm i` 再看 chat_message_id")
        assertEquals("开发步骤：1. npm i 再看 chat_message_id", r.joinToString("") { it.text })
        assertEquals(true, r.first().bold)
        assertEquals(true, r.any { it.code && it.text == "npm i" })
        assertEquals("下划线在词中不当斜体", false, r.any { it.italic })
        assertEquals("转义", "*不是斜体*", ChatInline.parse("\\*不是斜体\\*").joinToString("") { it.text })
        assertEquals(true, ChatInline.parse("~~删~~").first().strike)
    }
}
