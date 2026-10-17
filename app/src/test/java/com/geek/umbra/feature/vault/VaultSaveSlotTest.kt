package com.geek.umbra.feature.vault

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 编辑页自动保存那一个槽（C5）+ 两端同源的几句 —— 逐条翻译 iOS `Tools/swifttests/VaultSaveSlotTests.swift`。
 *
 * 「一个看不见的成功，和一次失败在用户那里是同一件事」：四态里真正要它的是「没保存上」那一档，
 * 那句话必须自己说清**库没被动过**。时间是毫秒。
 */
class VaultSaveSlotTest {
    private val now = 1_800_000_000_000L

    /** 四态：正在保存 / 未写入（带「现在就存」）/ 失败（失败色 + 危险色「重试」，说清库没被动过）。 */
    @Test fun fourStates() {
        assertEquals("正在保存那一句", "正在保存…", VaultSaveSlot.line(VaultSaveState.Saving, null, now).text)
        assertEquals("正在保存不该有钮", true, VaultSaveSlot.line(VaultSaveState.Saving, null, now).action == null)

        val dirty = VaultSaveSlot.line(VaultSaveState.Dirty, null, now)
        assertEquals("未写入那一句", "改了还没写进去", dirty.text)
        assertEquals("未写入那一档没有那颗钮", "现在就存", dirty.action?.label)
        assertEquals("「现在就存」不该是危险色", false, dirty.action?.danger)

        val fail = VaultSaveSlot.line(VaultSaveState.Fail, null, now)
        assertEquals("失败那一句没说没保存上", true, fail.text.contains("没保存上"))
        assertEquals("失败那一句没说改动还在", true, fail.text.contains("这台电脑上的改动还在"))
        assertEquals("失败那一句少了它最值钱的半句", true, fail.text.contains("库没被动过"))
        assertEquals("失败那一档不是失败色", true, fail.tone == VaultSlotTone.Fail)
        assertEquals("失败那一档没有重试", "重试", fail.action?.label)
        assertEquals("重试那一颗该是危险色", true, fail.action?.danger)
    }

    /** 「刚刚」会自己老：2 秒刚刚、11 分钟、2 小时；从没存过也念「刚刚」。 */
    @Test fun agoAges() {
        assertEquals("刚存下那一刻", "已保存 · 刚刚", VaultSaveSlot.line(VaultSaveState.Saved, now - 2_000, now).text)
        assertEquals("十一分钟之后还在念「刚刚」", "已保存 · 11 分钟前", VaultSaveSlot.line(VaultSaveState.Saved, now - 660_000, now).text)
        assertEquals("两小时之后那一句", "已保存 · 2 小时前", VaultSaveSlot.line(VaultSaveState.Saved, now - 7_200_000, now).text)
        assertEquals("还没存过那一刻", "已保存 · 刚刚", VaultSaveSlot.line(VaultSaveState.Saved, null, now).text)
    }

    /** 离开之前：未写入 / 失败都要先落一笔；已存好 / 正在存不再落。 */
    @Test fun flushBeforeLeave() {
        assertEquals("有没落的改动却直接走了", true, VaultSaveSlot.needsFlushBeforeLeave(VaultSaveState.Dirty))
        assertEquals("没保存上时退出去，那几个键就没了", true, VaultSaveSlot.needsFlushBeforeLeave(VaultSaveState.Fail))
        assertEquals("已经存好了还再落一笔", false, VaultSaveSlot.needsFlushBeforeLeave(VaultSaveState.Saved))
        assertEquals("正在存的时候又落一笔", false, VaultSaveSlot.needsFlushBeforeLeave(VaultSaveState.Saving))
    }

    /** 两端同源的那几句：后果写在确认弹窗里、不写进钮名；控件菜单三项；标题占位。 */
    @Test fun recordCopy() {
        assertEquals("删除那颗钮的名字不对", "删除记录", VaultRecordCopy.DELETE_LABEL)
        assertEquals("后果又被塞进钮名里了", false, VaultRecordCopy.DELETE_LABEL.contains("回收站"))
        assertEquals("确认弹窗没说去了哪儿", true, VaultRecordCopy.DELETE_BODY.contains("回收站"))
        assertEquals("确认弹窗没说留多久", true, VaultRecordCopy.DELETE_BODY.contains("30 天"))
        assertEquals("控件菜单不是三项", 3, VaultRecordCopy.BLOCK_MENU.size)
        assertEquals("标题占位不对", "未命名记录", VaultRecordCopy.TITLE_PLACEHOLDER)
    }
}
