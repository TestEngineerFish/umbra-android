// 保险箱会话：两级锁、自动锁定、生物识别解锁的状态机 —— 照抄 iOS VaultKit.swift `UmbraVaultSession`，
// 外加 iOS VaultShellGuards.swift 那两道外壳守卫（自动上锁驱动 + 后台遮罩）。
//
// 两级锁定：软锁 —— 界面锁住，AUK 还在内存里（自动锁定走这一档，刷一下生物识别即可）；
//           硬锁 —— VaultStore.lock()，AUK 清掉（「立即上锁」、改用主密码走这一档）。
// 解锁页状态机：idle ──自动 / 点图标──▶ scanning ──通过──▶ unlocked；└─未通过─▶ failed ──点图标──▶ scanning；
//               failed ──「用主密码解锁」──▶ idle + fallbackOnly。
package com.geek.umbra.feature.vault

import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import com.geek.umbra.app.AppForeground
import com.geek.umbra.app.AppGraph
import com.geek.umbra.navigation.isVaultSubtree
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class FaceState { Idle, Scanning, Failed }

data class VaultSessionState(
    val softLocked: Boolean = false,
    val faceState: FaceState = FaceState.Idle,
    val faceFailure: VaultBiometricStore.Failure? = null,
    /** 用户明确选了「用主密码解锁」/ 主动上锁 / 取消过系统弹窗 —— **这期间不再自动发起识别**。 */
    val fallbackOnly: Boolean = false,
    val autoLockMinutes: Int = 10,
    /** 「切到后台立即遮盖」—— Android 上落成保险箱页的 FLAG_SECURE（还防截屏）。 */
    val maskEnabled: Boolean = true,
    val lockNoteSilenced: Boolean = false,
    /** 设置里的「用生物识别解锁」。**只读**——要改走 enableBiometry / disableBiometry。 */
    val faceIDEnabled: Boolean = true,
    val hasBiometricCredential: Boolean = false,
)

object VaultSession {
    private val sp get() = VaultPrefs.sp

    private val _state = MutableStateFlow(
        VaultSessionState(
            autoLockMinutes = sp.getInt(VaultPrefs.LOCK_MIN, 10),
            maskEnabled = sp.getBoolean(VaultPrefs.MASK, true),
            lockNoteSilenced = sp.getBoolean(VaultPrefs.LOCK_NOTE_SILENCED, false),
            faceIDEnabled = sp.getBoolean(VaultPrefs.FACE_ID, true),
            hasBiometricCredential = VaultBiometricStore.hasCredential,
        ),
    )
    val state: StateFlow<VaultSessionState> = _state
    val s: VaultSessionState get() = _state.value
    private inline fun set(f: (VaultSessionState) -> VaultSessionState) = _state.update(f)

    /** 本次进入解锁页是否已经自动发起过。只在离开页面时清，防止重组反复弹窗。 */
    private var autoTriedThisVisit = false

    /** 自动上锁的表（umbra-ios#70）。 */
    private val clock = VaultIdleClock(System.currentTimeMillis())
    /** 5 秒一跳的巡检。**只在保险箱开着时在走**，不随任何页面。 */
    private var ticker: Job? = null

    val biometryAvailable: Boolean get() = VaultBiometricStore.available()
    val biometryName: String get() = VaultBiometricStore.name()

    fun setAutoLockMinutes(m: Int) { sp.edit().putInt(VaultPrefs.LOCK_MIN, m).apply(); set { it.copy(autoLockMinutes = m) }; touch() }
    fun setMaskEnabled(on: Boolean) { sp.edit().putBoolean(VaultPrefs.MASK, on).apply(); set { it.copy(maskEnabled = on) } }
    fun silenceLockNote() { sp.edit().putBoolean(VaultPrefs.LOCK_NOTE_SILENCED, true).apply(); set { it.copy(lockNoteSilenced = true) } }

    // MARK: 自动锁定（表跟着「保险箱开着」走，由 [install] 里的观察者驱动；回前台判一次）

    /** 用户在保险箱里动了一下。保险箱子树的页面外框（[VaultFrame]）每次按下都调。 */
    fun touch() = clock.touch(System.currentTimeMillis())

    /** 保险箱开 / 关。关的时候顺手把软锁放下：硬锁盖过软锁。 */
    fun vaultOpenChanged(open: Boolean) {
        clock.setOpen(open, System.currentTimeMillis())
        if (open) {
            if (ticker != null) return
            ticker = AppGraph.scope.launch {
                while (isActive) { delay(5_000); checkIdle() }
            }
        } else {
            ticker?.cancel(); ticker = null
            set { it.copy(softLocked = false) }
        }
    }

    /** 现在就判一次到没到点（墙钟：后台待了多久就算多久）。 */
    fun checkIdle() {
        if (s.softLocked) return
        if (clock.expired(System.currentTimeMillis(), s.autoLockMinutes)) {
            // 超时上锁时用户多半已经不看手机了，这时候弹识别没意义。
            set { it.copy(softLocked = true, fallbackOnly = true) }
        }
    }

    // MARK: 解锁页状态

    /** 进入解锁页时调。六个条件全满足才给过。 */
    fun shouldAutoScan(unlocked: Boolean): Boolean {
        val st = s
        if (unlocked || !st.faceIDEnabled || !(st.hasBiometricCredential || st.softLocked) || !biometryAvailable ||
            st.fallbackOnly || st.faceState != FaceState.Idle || autoTriedThisVisit
        ) return false
        autoTriedThisVisit = true
        return true
    }

    fun resetVisit() { autoTriedThisVisit = false; set { it.copy(faceState = FaceState.Idle, faceFailure = null) } }
    fun preferPassword() = set { it.copy(faceState = FaceState.Idle, faceFailure = null, fallbackOnly = true) }
    fun markManualLock() = set { it.copy(softLocked = false, fallbackOnly = true, faceState = FaceState.Idle, faceFailure = null) }
    fun markUnlocked() { set { it.copy(faceState = FaceState.Idle, faceFailure = null, fallbackOnly = false) }; touch() }

    private fun fail(f: VaultBiometricStore.Failure) = set {
        it.copy(
            faceState = FaceState.Failed, faceFailure = f,
            fallbackOnly = it.fallbackOnly || f == VaultBiometricStore.Failure.Cancelled,
            hasBiometricCredential = if (f == VaultBiometricStore.Failure.Invalidated) false else it.hasBiometricCredential,
        )
    }

    /** 硬锁：刷生物识别 → 取回主密码 → 交给调用方去解密。 */
    fun scanForPassword(activity: FragmentActivity, onPassword: (String) -> Unit) {
        if (s.faceState == FaceState.Scanning || !s.faceIDEnabled) return
        if (!biometryAvailable) { fail(VaultBiometricStore.Failure.Unavailable); return }
        if (!s.hasBiometricCredential) { fail(VaultBiometricStore.Failure.Invalidated); return }
        val cipher = when (val r = VaultBiometricStore.decryptCipher()) {
            is VaultBiometricStore.DecryptInit.Fail -> { fail(r.why); return }
            is VaultBiometricStore.DecryptInit.Ready -> r.cipher
        }
        set { it.copy(faceState = FaceState.Scanning, faceFailure = null) }
        VaultBiometricPrompt.authenticate(activity, "解锁保险箱", cipher) { res ->
            res.onSuccess { c ->
                val pw = c?.let { VaultBiometricStore.open(it) }
                if (pw == null) { fail(VaultBiometricStore.Failure.Invalidated); return@onSuccess }
                set { it.copy(faceState = FaceState.Idle) }
                onPassword(pw)
            }.onFailure { e -> fail((e as? VaultBiometricPrompt.FailureException)?.failure ?: VaultBiometricStore.Failure.Failed) }
        }
    }

    /** 软锁：AUK 还在内存里，只要证明是本人就行，不用取密码。 */
    fun scanForSoftUnlock(activity: FragmentActivity) {
        if (s.faceState == FaceState.Scanning) return
        if (!biometryAvailable) { fail(VaultBiometricStore.Failure.Unavailable); return }
        set { it.copy(faceState = FaceState.Scanning, faceFailure = null) }
        VaultBiometricPrompt.authenticate(activity, "解锁保险箱", null) { res ->
            res.onSuccess { set { it.copy(faceState = FaceState.Idle, softLocked = false) }; touch() }
                .onFailure { e -> fail((e as? VaultBiometricPrompt.FailureException)?.failure ?: VaultBiometricStore.Failure.Failed) }
        }
    }

    /**
     * 开启。**先验主密码、再验生物识别，两关都过才写入**。completion 收 null = 成功，否则是要显示的那句话。
     */
    fun enableBiometry(activity: FragmentActivity, password: String, completion: (String?) -> Unit) {
        val name = biometryName
        if (!biometryAvailable) { completion("这台设备没有可用的$name"); return }
        AppGraph.scope.launch {
            if (!VaultStore.verifyPassword(password)) { completion("主密码不对"); return@launch }
            val cipher = VaultBiometricStore.encryptCipher()
                ?: run { completion("存不进安全硬件，检查一下这台设备有没有设锁屏密码、录没录$name"); return@launch }
            VaultBiometricPrompt.authenticate(activity, "开启用${name}解锁保险箱", cipher) { res ->
                res.onSuccess { c ->
                    if (c != null && VaultBiometricStore.save(c, password)) {
                        sp.edit().putBoolean(VaultPrefs.FACE_ID, true).apply()
                        set { it.copy(hasBiometricCredential = true, faceIDEnabled = true) }
                        completion(null)
                    } else completion("存不进安全硬件，检查一下这台设备有没有设锁屏密码")
                }.onFailure { e ->
                    completion(((e as? VaultBiometricPrompt.FailureException)?.failure ?: VaultBiometricStore.Failure.Failed).message(name))
                }
            }
        }
    }

    fun disableBiometry() {
        sp.edit().putBoolean(VaultPrefs.FACE_ID, false).apply()
        set { it.copy(faceIDEnabled = false) }
        forgetPassword()
    }

    fun forgetPassword() {
        VaultBiometricStore.clear()
        set { it.copy(hasBiometricCredential = false) }
    }

    // MARK: 外壳守卫（iOS VaultShellGuards.swift）

    private var installed = false

    /**
     * 挂到进程上（VaultModule.register 调一次）。两件事都是「保险箱**开着**」这个状态的事，不是哪一页的事：
     * ① 表跟着 VaultStore.unlocked 起停；回前台**那一刻**按真实流逝时间判一次；
     * ② 上锁那一刻（软锁到点、手动上锁、改用主密码），各 Tab 栈里的保险箱子页一律退回去；
     *    当前 Tab 停在保险箱子树里时，正开着的底部弹层一并收起（「记录 ⋯」那几张的标题就是记录名）。
     */
    fun install() {
        if (installed) return
        installed = true
        AppGraph.scope.launch {
            VaultStore.state.combine(state) { v, se -> v.unlocked to (!v.unlocked || se.softLocked) }
                .distinctUntilChanged()
                .collect { (open, locked) ->
                    vaultOpenChanged(open)
                    if (locked) {
                        val r = AppGraph.router
                        if (r.top.isVaultSubtree) r.closeSheets()
                        r.popVaultSubtree()
                    }
                }
        }
        AppForeground.listeners += { fg -> if (fg) checkIdle() }
    }
}

/**
 * 保险箱子树每一页的外框：
 * - **FLAG_SECURE**（遮罩开着时）：切后台时任务切换器里只看到空白、也截不了屏 —— 对应 iOS 的后台遮罩窗口，更彻底；
 *   用引用计数，页面之间转场时两页同时在组合里也不会提前撤掉；
 * - **touch**：任何一次按下都算「用了保险箱」，免得每个按钮各自记得调；
 * - **剪贴板改道**：页面里所有输入框的长按「复制 / 剪切」都走 [VaultClipboard.copySensitive]（iOS VaultClipField 那一层），
 *   不进普通剪贴板。
 */
@Composable
fun VaultFrame(content: @Composable () -> Unit) {
    val mask by VaultSession.state.collectAsState()
    val activity = LocalContext.current.findFragmentActivity()
    if (mask.maskEnabled && activity != null) {
        DisposableEffect(activity) {
            SecureFlag.acquire(activity)
            onDispose { SecureFlag.release(activity) }
        }
    }
    if (activity != null) {
        DisposableEffect(activity) {
            VaultHostActivity.ref = java.lang.ref.WeakReference(activity)
            onDispose { }
        }
    }
    val sys = LocalClipboard.current
    val clip = remember(sys) { SensitiveClipboard(sys) }
    CompositionLocalProvider(LocalClipboard provides clip) {
        Box(
            Modifier.pointerInput(Unit) {
                awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial); VaultSession.touch() } }
            },
        ) { content() }
    }
}

/**
 * 保险箱页面的宿主 Activity（弱引用）。确认弹窗的回调里要发起 BiometricPrompt，而那一刻手上没有 Composable 的 Context。
 */
object VaultHostActivity {
    var ref: java.lang.ref.WeakReference<FragmentActivity>? = null
    val current: FragmentActivity? get() = ref?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
}

/** FLAG_SECURE 的引用计数。 */
private object SecureFlag {
    private var count = 0
    fun acquire(a: FragmentActivity) {
        if (count++ == 0) a.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    fun release(a: FragmentActivity) {
        if (--count <= 0) { count = 0; a.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/** 写入一律改道敏感入口；读照旧（粘贴 Secret Key 要用）。 */
private class SensitiveClipboard(private val base: Clipboard) : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? = base.getClipEntry()
    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val text = clipEntry?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        if (text.isNullOrEmpty()) base.setClipEntry(clipEntry) else VaultClipboard.copySensitive(text)
    }
    override val nativeClipboard get() = base.nativeClipboard
}
