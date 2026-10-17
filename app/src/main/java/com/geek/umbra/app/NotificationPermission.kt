package com.geek.umbra.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.geek.umbra.net.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 通知权限的唯一入口 —— 对应 iOS App/PushRegistry.swift 的 requestIfNeeded / enableTapped（iOS 4bad681）。
 *
 * iOS 那次改动的起因同样适用于 Android：权限要等到新建提醒时才问；而「去开启」直接跳系统设置 —— 新装的用户
 * 从没被问过，Android 13+ 上系统设置里虽然有开关，但用户期待的是一个授权框。所以：
 * - 冷启动没问过就问一次（未登录也问：提醒是单机功能）；
 * - 「去开启 / 去系统设置」：没问过 → 当场弹授权框；问过了（拒过 / 在设置里关了）→ 跳本 App 的通知设置页。
 * Android 12 及以下没有运行时权限（默认开着），只可能是用户在设置里关了，一律跳设置。
 */
object NotificationPermission {
    enum class Status { Granted, NotAsked, Denied }

    /** MainActivity.onCreate 里注册（registerForActivityResult 必须在 STARTED 之前）。 */
    internal var launcher: ActivityResultLauncher<String>? = null

    private val _tick = MutableStateFlow(0)
    /** 权限结果回来 / 回到前台时 +1，页面订阅它重读 [status]。 */
    val tick: StateFlow<Int> = _tick

    fun status(): Status {
        val ctx = AppGraph.context
        if (NotificationManagerCompat.from(ctx).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        ) return Status.Granted
        if (Build.VERSION.SDK_INT >= 33 && AppGraph.prefs[Prefs.K.notifyAsked] != true) return Status.NotAsked
        return Status.Denied
    }

    /** 没问过就问一次；问过了什么都不做（系统也不会再弹）。 */
    fun requestIfNeeded() {
        if (status() != Status.NotAsked) return
        val l = launcher ?: return
        AppGraph.prefs.set(Prefs.K.notifyAsked, true)
        runCatching { l.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }

    /** 「去开启」「去系统设置」按下。 */
    fun enableTapped() {
        when (status()) {
            Status.Granted -> openSettings()
            Status.NotAsked -> requestIfNeeded()
            Status.Denied -> openSettings()
        }
    }

    internal fun onResult(granted: Boolean) {
        _tick.value++
        runCatching { com.geek.umbra.feature.reminder.ReminderStore.onPermissionResult(granted && com.geek.umbra.feature.reminder.ReminderNotifier.canPost(AppGraph.context)) }
    }

    /** 回到前台：用户可能刚从系统设置回来。 */
    internal fun refresh() { _tick.value++ }

    fun openSettings() {
        val ctx = AppGraph.context
        val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }.onFailure {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
