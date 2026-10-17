package com.geek.umbra.feature.chat

import com.geek.umbra.feature.chat.ChatCardResend.Local
import com.geek.umbra.feature.chat.ChatCardResend.Merge
import org.junit.Assert.assertEquals
import org.junit.Test

/** 照抄 iOS Tools/swifttests/ChatCardResendTests.swift。 */
class ChatCardResendTest {
    @Test fun merge() {
        assertEquals("第一次到：追加一张", Merge.Append, ChatCardResend.merge(Local.Absent))
        assertEquals("本地开着：重连补发不多出一张", Merge.Keep, ChatCardResend.merge(Local.Open))
        assertEquals("本地已处理、服务端还在补发：复位成未处理", Merge.Reopen, ChatCardResend.merge(Local.Handled))
        assertEquals("同一条连接上刚处理的：补发不复位", Merge.Keep, ChatCardResend.merge(Local.HandledOnThisConnection))
    }
}
