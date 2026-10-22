package com.geek.umbra.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 网络层纯判据用例。输入输出照抄 iOS Tools/swifttests 里对应的用例；改判据先改这里。 */
class NetRulesTest {
    @Test fun encodeURIComponent() {
        assertEquals("a%2Bb%3D%2F%3F%23", UmbraUrl.value("a+b=/?#"))
        assertEquals("-_.!~*'()", UmbraUrl.value("-_.!~*'()"))
        assertEquals("%E4%B8%AD", UmbraUrl.value("中"))
    }

    @Test fun redirectOnlySameHost() {
        assertTrue(UmbraUrl.followsRedirect("http://a.com/x", "https://a.com/x"))
        assertFalse(UmbraUrl.followsRedirect("https://a.com/x", "http://a.com/x"))
        assertFalse(UmbraUrl.followsRedirect("https://a.com/x", "https://b.com/x"))
        assertFalse(UmbraUrl.followsRedirect("http://a.com:8080/x", "https://a.com/x"))
    }

    /** 按字节读的出口据此决定带不带令牌：只给自己的服务端带。 */
    @Test fun isServerOnlyOwnOrigin() {
        val srv = "https://api.umbra.example"
        assertTrue(UmbraUrl.isServer(srv, "https://api.umbra.example/files/abc"))
        assertTrue(UmbraUrl.isServer("$srv/", "https://api.umbra.example/files/abc"))
        assertTrue(UmbraUrl.isServer(srv, "https://api.umbra.example:443/files/abc"))
        assertTrue("http→https 升级算同一家", UmbraUrl.isServer("http://api.umbra.example", "https://api.umbra.example/x"))
        assertTrue("带路径前缀的服务端", UmbraUrl.isServer("https://h.example/umbra", "https://h.example/umbra/files/1"))
        assertFalse("主机名只是前缀相同", UmbraUrl.isServer(srv, "https://api.umbra.example.evil.com/files/abc"))
        assertFalse("别家 CDN", UmbraUrl.isServer(srv, "https://cdn.example.com/theme.umbraplugin"))
        assertFalse("OSS 签名地址", UmbraUrl.isServer(srv, "https://bucket.oss-cn-hangzhou.aliyuncs.com/a?Signature=x"))
        assertFalse("降级到 http 不算", UmbraUrl.isServer(srv, "http://api.umbra.example/files/abc"))
        assertFalse("换端口不算", UmbraUrl.isServer(srv, "https://api.umbra.example:8443/files/abc"))
        assertFalse("没配服务端", UmbraUrl.isServer("", "https://api.umbra.example/files/abc"))
    }

    @Test fun sameSite() {
        assertTrue(UmbraUrl.sameSite("example.com", "login.example.com"))
        assertFalse(UmbraUrl.sameSite("example.com", "ple.com"))
        assertEquals("example.com", UmbraUrl.host("https://www.Example.com./a"))
    }

    @Test fun socketUrl() {
        assertEquals("wss://h.x/ws/chat?src=android&session=a%2Bb", UmbraUrl.chatSocket("https://h.x/", "a+b"))
        assertEquals("ws://h.x/ws/chat?src=android", UmbraUrl.chatSocket("http://h.x", ""))
    }

    @Test fun revoke() {
        assertTrue(SessionRevokeRules.http(401, "session_invalid", null))
        assertFalse(SessionRevokeRules.http(401, "bad_token", null))
        assertTrue(SessionRevokeRules.http(200, null, " Invalid "))
        assertFalse(SessionRevokeRules.stillCurrent("", ""))
        val latch = SessionRevokeLatch()
        assertFalse(latch.accept("old", "new"))
        assertTrue(latch.accept("t", "t"))
        assertFalse(latch.accept("t", "t"))
    }

    @Test fun renewCooldown() {
        val s = SessionRenewState()
        assertEquals(SessionRenewState.Decision.Revoke, s.decide(0, canRenew = false))
        assertEquals(SessionRenewState.Decision.Renew, s.decide(0, true))
        s.record(RenewOutcome.Renewed, 1000)
        assertEquals(SessionRenewState.Decision.Revoke, s.decide(20_000, true))
        assertEquals(SessionRenewState.Decision.Renew, s.decide(40_000, true))
        s.record(RenewOutcome.Unreachable, 50_000)
        assertEquals(SessionRenewState.Decision.Ignore, s.decide(60_000, true))
    }

    @Test fun requestGate() {
        assertFalse(SessionRequestGate.blocks(true, "/auth/login", ""))
        assertFalse(SessionRequestGate.blocks(true, "/health", ""))
        assertTrue(SessionRequestGate.blocks(true, "/tasks", ""))
        assertTrue(SessionRequestGate.blocks(true, "/auth/../tasks", ""))
        assertFalse(SessionRequestGate.blocks(true, "/umbra/auth/me", "/umbra/"))
        assertTrue(SessionRequestGate.blocks(true, "/other/auth/me", "/umbra"))
        assertFalse(SessionRequestGate.blocks(false, "/tasks", ""))
    }

    @Test fun guardSteps() {
        assertEquals(GuardStep.Unlock, SessionGuardRules.step(locked = false, settled = false, target = SessionLock.Open))
        assertEquals(GuardStep.None, SessionGuardRules.step(locked = false, settled = true, target = SessionLock.Open))
        assertEquals(GuardStep.Lock, SessionGuardRules.step(locked = false, settled = true, target = SessionLock.Locked))
        assertEquals(GuardStep.None, SessionGuardRules.step(locked = true, settled = true, target = SessionLock.Locked))
        assertTrue(SessionGuardRules.showsAuthScreen(Gate.SessionInvalid, standalone = true))
        assertFalse(SessionGuardRules.showsAuthScreen(Gate.Login, standalone = true))
    }

    @Test fun socketVerdict() {
        assertEquals(ChatSocketRules.Verdict.SessionInvalid, ChatSocketRules.verdict(4001, "session_invalid"))
        assertEquals(ChatSocketRules.Verdict.GateRejected, ChatSocketRules.verdict(4001, ""))
        assertEquals(ChatSocketRules.Verdict.OfflineRetry, ChatSocketRules.verdict(1006, null))
        assertEquals(listOf(1L, 2, 4, 8, 16, 30, 30), (0..6).map { ChatSocketRules.backoffSeconds(it) })
    }

    @Test fun ledgerPaths() {
        assertTrue(Ledger.matches("/money/entries"))
        assertTrue(Ledger.matches("/money"))
        assertTrue(Ledger.matches("/assist/money-shot"))
        assertFalse(Ledger.matches("/moneyx"))
    }

    @Test fun accountSwitch() {
        assertEquals(AccountSwitchPlan.Adopt, AccountSwitchRules.plan(null, null, "1", "s", 3))
        assertEquals(AccountSwitchPlan.Same, AccountSwitchRules.plan("1", null, "1", "s", 3))
        assertEquals(AccountSwitchPlan.Ask, AccountSwitchRules.plan("1", "s", "2", "s", 3))
        assertEquals(AccountSwitchPlan.Replace, AccountSwitchRules.plan("1", "s", "1", "t", 0))
        assertNull(ReasonKind.of(""))
        assertEquals(ReasonKind.Unknown, ReasonKind.of("weird"))
    }
}
