package com.geek.umbra.navigation

import com.geek.umbra.app.AppGraph
import com.geek.umbra.net.DeepLinkAction
import com.geek.umbra.net.SessionGuardRules
import com.geek.umbra.net.SessionLock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 深链消费（iOS AppShell.swift:255-266）：门还在问时攥着；门开就跳；门关时要登录的页丢弃、本地页照跳。
 * 登录之后**不补跳**被丢弃的那条 —— 那是几分钟前的意图，突然跳过去更让人困惑。
 */
object DeepLinks {
    fun receive(uri: String) {
        val route = DeepLinkRules.route(uri) ?: return
        AppGraph.scope.launch {
            val lock = AppGraph.guard.lock.first { it != SessionLock.Undecided }
            when (SessionGuardRules.deepLink(lock, route.needsLogin)) {
                DeepLinkAction.Go -> AppGraph.router.jump(route)
                DeepLinkAction.Hold, DeepLinkAction.Drop -> Unit
            }
        }
    }
}
