package com.geek.umbra.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 路由判据用例：照抄 iOS Tools/swifttests/RouteRulesTests.swift 的要点。 */
class RouteRulesTest {
    @Test fun deepLinks() {
        assertEquals(Route.TaskList, DeepLinkRules.route("umbra://tasks"))
        assertEquals(Route.TaskDetail("abc_1-2"), DeepLinkRules.route("umbra://task/abc_1-2"))
        assertEquals(Route.TaskList, DeepLinkRules.route("umbra://task/bad%20id"))
        assertEquals(Route.RemDetail("r1"), DeepLinkRules.route("umbra://reminder/r1"))
        assertEquals(Route.MoneyAdd(null), DeepLinkRules.route("umbra://money/add"))
        assertEquals(Route.MoneyHome, DeepLinkRules.route("umbra://money"))
        assertEquals(Route.InspEdit(null), DeepLinkRules.route("umbra://inspiration/new"))
        assertNull(DeepLinkRules.route("umbra://nope"))
        assertNull(DeepLinkRules.route("https://tasks"))
    }

    @Test fun loginAndTabs() {
        assertTrue(Route.ChatContacts.needsLogin)
        assertFalse(Route.RemList.needsLogin)
        assertFalse(Route.VaultHome.needsLogin)
        assertFalse(Route.MoneyHome.needsLogin)
        assertEquals(Tab.Tool, Route.VaultHome.tab)
        assertEquals(Tab.Me, Route.VaultTrash.tab)
        assertTrue(Route.VaultTrash.isVaultSubtree)
    }

    @Test fun jump() {
        assertEquals(JumpPlan.Reveal, JumpRules.plan(Route.RemList, emptyList()))
        assertEquals(JumpPlan.Replace, JumpRules.plan(Route.RemDetail("a"), listOf(Route.RemDetail("b"))))
        assertEquals(JumpPlan.AskFirst, JumpRules.plan(Route.RemDetail("a"), listOf(Route.RemEdit(null))))
        assertEquals(JumpPlan.Reveal, JumpRules.plan(Route.RemDetail("a"), listOf(Route.RemDetail("a"))))
    }
}
