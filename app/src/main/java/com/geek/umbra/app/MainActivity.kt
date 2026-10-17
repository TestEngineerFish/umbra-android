package com.geek.umbra.app

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.geek.umbra.designsystem.Appearance
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.navigation.AppShell
import com.geek.umbra.navigation.DeepLinks
import com.geek.umbra.net.Initiator
import com.geek.umbra.net.Prefs
import kotlinx.coroutines.launch

/**
 * 唯一的 Activity。继承 AppCompatActivity 是为了 App 内切语言（AppCompatDelegate.setApplicationLocales
 * 在 Android 12 及以下要靠 AppCompat 落地）。
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 三键导航时系统会在导航栏上加一层半透明遮罩：关掉，让底栏的 card 底一直铺进导航区（设计侧 199 答复第 1 条）
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        handle(intent)
        setContent {
            val prefs by AppGraph.prefs.snapshot.collectAsState()
            UmbraTheme(Appearance.of(prefs[Prefs.K.appearance])) { AppShell() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** `umbra://auth/callback` 交给 OIDC；其余 umbra:// 交给深链（门未定时先攥着）。 */
    private fun handle(intent: Intent?) {
        val data = intent?.dataString ?: return
        if (data.startsWith("umbra://auth/callback")) {
            AppGraph.scope.launch { AppGraph.auth.finishOidc(data) }
        } else DeepLinks.receive(data)
    }

    /** 真实输入探针：作息统计的「人引起的请求」靠它（iOS PresenceInput.swift）。 */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) Initiator.markInput()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) Initiator.markInput()
        return super.dispatchKeyEvent(event)
    }
}
