package com.geek.umbra.feature.chat

import com.geek.umbra.feature.chat.ChatReasonRules as R
import com.geek.umbra.net.ReasonKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** 照抄 iOS Tools/swifttests/ReasonKindTests.swift。 */
class ChatReasonRulesTest {
    @Test fun wire() {
        assertEquals(ReasonKind.InsufficientBalance, R.resolve("insufficient_balance", null))
        assertEquals(ReasonKind.RateLimited, R.resolve("rate_limited", null))
        assertEquals(ReasonKind.UpstreamError, R.resolve("upstream_error", null))
        assertEquals(ReasonKind.AuthFailed, R.resolve("auth_failed", null))
        assertEquals(ReasonKind.BadRequest, R.resolve("bad_request", null))
        assertEquals(ReasonKind.Unavailable, R.resolve("unavailable", null))
        assertEquals(ReasonKind.Unknown, R.resolve("unknown", null))
        assertEquals("空串 = 没有原因，不走兼容分支", null, R.resolve("", "Error code: 429"))
        assertEquals(ReasonKind.Unknown, R.resolve("quota_exhausted_v2", null))
        assertEquals("不认识的值不回退读原话", ReasonKind.Unknown, R.resolve("quota_exhausted_v2", "余额不足 429"))
        assertEquals(ReasonKind.RateLimited, R.resolve("rate_limited", "Error code: 429"))
    }

    @Test fun legacy() {
        assertEquals(ReasonKind.InsufficientBalance, R.resolve(null, "Error code: 429, 请求过多"))
        assertEquals(ReasonKind.InsufficientBalance, R.resolve(null, "账户余额不足"))
        assertEquals(null, R.resolve(null, "req-14290 timeout"))
        assertEquals(null, R.resolve(null, "image 1429x800 too large"))
        assertEquals(null, R.resolve(null, "unsupported image format"))
        assertEquals(null, R.resolve(null, ""))
        assertEquals(null, R.resolve(null, null))
    }

    @Test fun actions() {
        val all = ReasonKind.entries
        for (k in all) assertEquals(k.wire, k in listOf(ReasonKind.InsufficientBalance, ReasonKind.NeedMembership, ReasonKind.QuotaExhausted), R.showsTopup(k))
        assertEquals("vision.topup", R.topupButtonKey(ReasonKind.InsufficientBalance))
        assertEquals("vision.openMembership", R.topupButtonKey(ReasonKind.NeedMembership))
        assertEquals("vision.quotaShop", R.topupButtonKey(ReasonKind.QuotaExhausted))
        assertEquals(ReasonKind.NeedMembership, R.resolve("need_membership", null))
        assertEquals(ReasonKind.QuotaExhausted, R.resolve("quota_exhausted", null))
        assertEquals(listOf(ReasonKind.RateLimited, ReasonKind.UpstreamError, ReasonKind.Unknown), all.filter { R.retryable(it) })
        assertEquals(true, R.visionShowsReread(ReasonKind.InsufficientBalance))
        assertEquals(true, R.visionShowsReread(ReasonKind.QuotaExhausted))
        assertEquals(false, R.visionShowsReread(ReasonKind.AuthFailed))
        assertEquals(false, R.visionShowsReread(ReasonKind.BadRequest))
        assertEquals(false, R.visionShowsReread(ReasonKind.Unavailable))
        assertEquals(true, R.visionShowsReread(ReasonKind.RateLimited))
        assertEquals(true, R.visionShowsReread(null))
        assertEquals(null, R.visionCopyKey(ReasonKind.Unknown))
        assertEquals(listOf(ReasonKind.Unknown), all.filter { R.visionCopyKey(it) == null })
    }

    @Test fun topup() {
        val pay = "https://pay.example.com/topup"
        assertEquals(pay, R.topupLink(ReasonKind.InsufficientBalance, pay))
        assertEquals("http://pay.example.com/x", R.topupLink(ReasonKind.InsufficientBalance, "http://pay.example.com/x"))
        assertEquals("HTTPS://pay.example.com", R.topupLink(ReasonKind.InsufficientBalance, "HTTPS://pay.example.com"))
        assertEquals(null, R.topupLink(ReasonKind.InsufficientBalance, null))
        assertEquals(null, R.topupLink(ReasonKind.InsufficientBalance, ""))
        assertEquals(null, R.topupLink(ReasonKind.InsufficientBalance, "javascript:alert(1)"))
        assertEquals(null, R.topupLink(ReasonKind.InsufficientBalance, "ftp://pay.example.com/x"))
        assertEquals(null, R.topupLink(ReasonKind.InsufficientBalance, "https://"))
        assertEquals(null, R.topupLink(ReasonKind.InsufficientBalance, "pay.example.com/topup"))
        assertEquals(null, R.topupLink(R.resolve(null, "Error code: 429"), null))
        for (k in listOf(ReasonKind.RateLimited, ReasonKind.UpstreamError, ReasonKind.AuthFailed, ReasonKind.BadRequest, ReasonKind.Unavailable, ReasonKind.Unknown)) {
            assertEquals(k.wire, null, R.topupLink(k, pay))
        }
        assertEquals(pay, R.topupLink(ReasonKind.NeedMembership, pay))
        assertEquals(null, R.topupLink(ReasonKind.QuotaExhausted, "javascript:x"))
        assertEquals(null, R.topupLink(null, pay))
    }

    @Test fun taskFail() {
        val pay = "https://pay.example.com/topup"
        assertEquals(TaskFailAction.Topup(pay), R.taskFailAction("insufficient_balance", pay))
        assertEquals(TaskFailAction.NoRetry, R.taskFailAction("insufficient_balance", null))
        assertEquals(TaskFailAction.NoRetry, R.taskFailAction("insufficient_balance", "javascript:alert(1)"))
        assertEquals(TaskFailAction.AsBefore, R.taskFailAction("rate_limited", null))
        assertEquals(TaskFailAction.AsBefore, R.taskFailAction("upstream_error", null))
        assertEquals(TaskFailAction.AsBefore, R.taskFailAction("rate_limited", pay))
        for (w in listOf("auth_failed", "bad_request", "unavailable")) assertEquals(w, TaskFailAction.NoRetry, R.taskFailAction(w, null))
        assertEquals(TaskFailAction.AsBefore, R.taskFailAction("unknown", null))
        assertEquals(TaskFailAction.AsBefore, R.taskFailAction(null, null))
        assertEquals(TaskFailAction.AsBefore, R.taskFailAction(null, pay))
        assertEquals(TaskFailAction.AsBefore, R.taskFailAction("", null))
        assertEquals(TaskFailAction.AsBefore, R.taskFailAction("quota_exhausted_v2", pay))
    }
}
