package com.geek.umbra.app

import android.annotation.SuppressLint
import android.content.Context
import com.geek.umbra.navigation.Router
import com.geek.umbra.net.AuthService
import com.geek.umbra.net.ChatSocket
import com.geek.umbra.net.Ledger
import com.geek.umbra.net.Prefs
import com.geek.umbra.net.SecureStore
import com.geek.umbra.net.SessionStore
import com.geek.umbra.net.UmbraHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 依赖容器 —— 手写，不上 Hilt。
 *
 * iOS 端本身就是若干进程级单例（ChatViewModel、ReminderStore、VaultStore…），这里一一对应成 AppGraph 上的属性。
 * 功能模块的 Store 用 `by lazy` 挂在 [features] 里（见 FeatureGraph），第一次用到才建 —— 冷启动只建网络与导航。
 * Compose 里通过 `AppGraph.x` 直接取；测试时纯判据不碰它。
 */
@SuppressLint("StaticFieldLeak") // 持有的是 applicationContext，不会泄漏 Activity
object AppGraph {
    lateinit var context: Context; private set

    /** 进程级协程作用域：同步、长连接、防抖推送都挂在这里，不随页面销毁。 */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var prefs: Prefs; private set
    lateinit var secure: SecureStore; private set
    lateinit var session: SessionStore; private set
    lateinit var http: UmbraHttp; private set
    lateinit var auth: AuthService; private set
    lateinit var socket: ChatSocket; private set
    lateinit var guard: SessionGuard; private set
    val router = Router()

    fun init(app: Context) {
        if (::context.isInitialized) return
        context = app.applicationContext
        prefs = Prefs(context, scope)
        secure = SecureStore(context)
        session = SessionStore(secure)
        http = UmbraHttp(prefs, session) { Ledger.of(prefs[Prefs.K.ledger]) }
        auth = AuthService(http, session, prefs, scope)
        socket = ChatSocket(http.client, prefs, session, auth, scope)
        guard = SessionGuard(prefs, auth, router, scope)
        guard.register("socket", onLock = { socket.stop() }, onUnlock = { socket.start() })
        FeatureGraph.registerGuards(guard)
        com.geek.umbra.net.ServerSettings.start()
        guard.register("settings", onLock = {}, onUnlock = { scope.launch { com.geek.umbra.net.ServerSettings.load() } })
    }
}
