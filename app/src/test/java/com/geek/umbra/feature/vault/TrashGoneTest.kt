package com.geek.umbra.feature.vault

import com.geek.umbra.app.Brand
import com.geek.umbra.model.TrashItem
import com.geek.umbra.model.TrashList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 已删对象那一页（设计侧批次 290 §五 A）：什么时候才说「超过 30 天、找不回来」，以及中英字面。 */
class TrashGoneTest {
    private val trash = TrashList(listOf(TrashItem(kind = "reminder", id = "r1"), TrashItem(kind = "idea", id = "42")))

    @Test fun expiredOnlyWhenSure() {
        assertFalse("回收站里还在 → 可能刚删", TrashGoneRules.expired(trash, "reminder", "r1", false))
        assertTrue("拉到了、没有、本机也没待推的删除 → 超过 30 天", TrashGoneRules.expired(trash, "reminder", "r2", false))
        assertFalse("没拉到回收站（离线 / 没登录）不下结论", TrashGoneRules.expired(null, "reminder", "r2", false))
        assertFalse("本机刚删、还没推上去：服务端回收站里当然没有", TrashGoneRules.expired(trash, "reminder", "r2", true))
        assertFalse("种类要对上（灵感 42 不是提醒 42）", TrashGoneRules.expired(trash, "idea", "42", false))
        assertTrue(TrashGoneRules.expired(trash, "reminder", "42", false))
    }

    @Test fun copy() {
        assertEquals("${Brand.nickname}没找到这条提醒", TrashGoneCopy.title("提醒", "reminder", english = false))
        assertEquals("${Brand.nickname}没找到这条灵感", TrashGoneCopy.title("灵感", "idea", english = false))
        assertEquals("This reminder is gone", TrashGoneCopy.title("提醒", "reminder", english = true))
        assertFalse("英文不用昵称", TrashGoneCopy.title("提醒", "reminder", english = true).contains(Brand.nickname))
        assertEquals("它可能刚被删掉了，删掉的会在回收站留 30 天。", TrashGoneCopy.hint(expired = false, english = false))
        assertEquals("它被删掉超过 30 天了，找不回来。", TrashGoneCopy.hint(expired = true, english = false))
        assertEquals("It may have just been deleted. Deleted items stay in Trash for 30 days.", TrashGoneCopy.hint(false, true))
        assertEquals("回到提醒", TrashGoneCopy.back("提醒", "reminder", false))
        assertEquals("Back to reminders", TrashGoneCopy.back("提醒", "reminder", true))
        assertEquals("Open Trash", TrashGoneCopy.openTrash(true))
    }
}
