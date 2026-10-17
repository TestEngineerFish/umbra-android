package com.geek.umbra.feature.presence

import com.geek.umbra.app.AppForeground
import com.geek.umbra.app.AppGraph
import com.geek.umbra.app.SessionGuard
import com.geek.umbra.net.HttpResult
import com.geek.umbra.net.Initiator
import com.geek.umbra.net.InitiatorRules
import com.geek.umbra.net.UmbraHttp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 使用记录模块的进程级入口：`POST /presence/ping` 上报（执勤 M0 契约 §15.2 第 1 条、需求 §14.1）。
 * 登录才发（未登录 = 单机，服务端没有这个人），所以登记在守卫上。
 */
object PresenceModule {
    fun register(guard: SessionGuard) {
        guard.register("presence-ping", onLock = { PresencePinger.stop() }, onUnlock = { PresencePinger.start() })
    }
}

/**
 * 「这 5 分钟里人在用」的补充信号（离线记账、本机保险箱这类没碰服务端的操作）。
 *
 * iOS（PresenceInput.swift）是**由输入驱动**：每次真实输入顺手问一次「距上次 ping 满 5 分钟没有」，不起定时器。
 * Android 的输入探针在 MainActivity（app/ 不归本模块改），它只写 `Initiator.lastInputMs`、不回调 ——
 * 所以这里在**前台期间**每 [CHECK_MS] 看一眼「这一段里有没有真实输入」，有才按 InitiatorRules.shouldPing 判。
 * 判据仍是「由输入驱动」：没有输入就一定不发；30 秒一次的读一个原子数不算高频定时器，后台时整个停掉。
 */
object PresencePinger {
    /** 前台时多久看一眼。iOS 的输入驱动最多晚 0 秒，这里最多晚这么久 —— 服务端按分钟格记，差半分钟无所谓。 */
    private const val CHECK_MS = 30_000L

    private var job: Job? = null
    private var enabled = false
    /** 这次启动里上次 ping 的时刻；0 = 还没发过。不落盘：重启后先发一次没有坏处（服务端按分钟格记，重复不加倍）。 */
    private var lastPingMs = 0L
    private var pinging = false
    private var listening = false

    fun start() {
        enabled = true
        if (!listening) {
            listening = true
            // 前后台切换：前台开始看、后台停（AppForeground 是 app/ 的地基，这里只挂一个监听）
            AppForeground.listeners += { fg -> if (fg) loop() else job?.cancel() }
        }
        if (AppForeground.isForeground) loop()
    }

    fun stop() {
        enabled = false
        job?.cancel(); job = null
    }

    private fun loop() {
        if (!enabled) return
        job?.cancel()
        job = AppGraph.scope.launch {
            while (isActive) {
                check()
                delay(CHECK_MS)
            }
        }
    }

    /** 这一段里有真实输入、且该 ping 了就发一次。 */
    private fun check() {
        val input = Initiator.lastInputMs.get()
        val now = System.currentTimeMillis()
        if (input <= 0 || now - input > CHECK_MS) return
        // 「登着」看两样：守卫没锁、本机有会话令牌。
        val signedIn = !AppGraph.guard.locked.value && AppGraph.auth.hasToken
        if (pinging || !InitiatorRules.shouldPing(now, lastPingMs, AppForeground.isForeground, signedIn)) return
        pinging = true
        lastPingMs = now
        AppGraph.scope.launch {
            // **显式带** X-Umbra-Initiator: user（契约：ping 本身同样带这个头）—— 不靠 3 秒窗口，发出去那一刻未必还在窗口里。
            // 失败不重试、不出声：丢一次只是少算几分钟。
            val r = AppGraph.http.raw(
                "POST", "/presence/ping", body = "{}".toRequestBody(UmbraHttp.JSON),
                headers = mapOf(InitiatorRules.HEADER to InitiatorRules.USER),
            )
            @Suppress("UNUSED_VARIABLE") val ok = r is HttpResult.Ok
            pinging = false
        }
    }
}
