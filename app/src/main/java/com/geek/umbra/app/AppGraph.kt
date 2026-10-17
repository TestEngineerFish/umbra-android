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
    private lateinit var base: Context
    private var localized: Context? = null
    private var localizedTags: String? = null

    /**
     * 应用级 Context，**按当前 App 内语言配置过**。
     * App 内切语言（AppCompatDelegate.setApplicationLocales）只作用到 Activity 的 Context；直接拿 applicationContext
     * 取文案会一直按系统语言 —— 模拟器实测：切成简体中文后「我」页的「商城 / 知识库 / 执勤」仍是英文。
     * 这里按当前语言标签缓存一份 createConfigurationContext，语言一换就重建。文件、系统服务、启动 Activity 照常可用。
     */
    val context: Context
        get() {
            // Android 13+ 的 App 语言存在系统 LocaleManager 里（设置 › 应用 › 语言 也改的是它）；AppCompat 的读法在这里会回空
            val tags = if (android.os.Build.VERSION.SDK_INT >= 33) {
                base.getSystemService(android.app.LocaleManager::class.java)?.applicationLocales?.toLanguageTags().orEmpty()
            } else androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags()
            if (tags != localizedTags || localized == null) {
                localized = if (tags.isEmpty()) base else base.createConfigurationContext(
                    android.content.res.Configuration(base.resources.configuration).apply { setLocales(android.os.LocaleList.forLanguageTags(tags)) },
                )
                localizedTags = tags
            }
            return localized!!
        }

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
        if (::base.isInitialized) return
        base = app.applicationContext
        prefs = Prefs(context, scope)
        secure = SecureStore(context)
        session = SessionStore(secure)
        http = UmbraHttp(prefs, session) { Ledger.of(prefs[Prefs.K.ledger]) }
        auth = AuthService(http, session, prefs, scope)
        socket = ChatSocket(http.client, prefs, session, auth, scope)
        guard = SessionGuard(prefs, auth, router, scope)
        guard.register("socket", onLock = { socket.stop(); http.clearOffline() }, onUnlock = { socket.start() })
        FeatureGraph.registerGuards(guard)
        com.geek.umbra.net.ServerSettings.start()
        guard.register("settings", onLock = {}, onUnlock = { scope.launch { com.geek.umbra.net.ServerSettings.load() } })
    }
}
