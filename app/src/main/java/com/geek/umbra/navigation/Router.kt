package com.geek.umbra.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 确认弹窗。破坏性动作一律走它（左滑删除也不例外）；`destructive` 时确认钮画红。 */
data class UmbraAlert(
    val title: String,
    val message: String? = null,
    val confirmLabel: String = "确定",
    val cancelLabel: String = "取消",
    val destructive: Boolean = false,
    val onCancel: (() -> Unit)? = null,
    val onConfirm: () -> Unit,
)

/** 底部弹层：内容自己画，Router 只管叠放顺序。 */
class UmbraSheet(val key: String, val content: @Composable (dismiss: () -> Unit) -> Unit)

data class UmbraToast(val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null, val id: Long = System.nanoTime())

/**
 * 导航唯一状态源 —— 对应 iOS Navigation/Router.swift。
 *
 * 每个 Tab 一条栈（不含根页，与 iOS `paths` 同义），切 Tab 不丢各自的位置。
 * 放在进程级的 AppGraph 里，旋转屏幕 / 切深色不会丢栈；进程被杀才重置（与 iOS 冷启动一致）。
 */
class Router {
    var tab by mutableStateOf(Tab.Chat)
    val stacks: Map<Tab, SnapshotStateList<Route>> = Tab.entries.associateWith { mutableStateListOf() }
    val sheets = mutableStateListOf<UmbraSheet>()
    var alert by mutableStateOf<UmbraAlert?>(null)
    var toast by mutableStateOf<UmbraToast?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var toastJob: Job? = null

    fun stack(t: Tab = tab): SnapshotStateList<Route> = stacks.getValue(t)

    /** 当前 Tab 栈顶（没有推入页时是根页）。 */
    val top: Route get() = stack().lastOrNull() ?: tab.root

    fun go(route: Route) {
        stack().add(route)
    }

    fun back() {
        val s = stack()
        if (s.isNotEmpty()) s.removeAt(s.lastIndex)
    }

    /** 异步回包后才退页：只在栈顶仍是这一页时退 —— 用户等不及先返回了，就不能再替他多退一层。 */
    fun back(ifShowing: Route) {
        if (stack().lastOrNull() == ifShowing) back()
    }

    /** 点底栏：点别的 Tab 是切换；再点当前 Tab 清栈回根（iOS 同）。 */
    fun selectTab(t: Tab) {
        if (t == tab) stack(t).clear() else tab = t
    }

    fun root(t: Tab) {
        stack(t).clear(); tab = t
    }

    /** 跨 Tab 跳转，见 [JumpRules]。 */
    fun jump(route: Route) {
        val t = route.tab
        when (JumpRules.plan(route, stack(t))) {
            JumpPlan.Reveal -> tab = t
            JumpPlan.Replace -> replaceStack(route)
            JumpPlan.AskFirst -> confirm(
                UmbraAlert(
                    title = "放弃正在写的内容？",
                    message = "前往的页面在另一处正在编辑的内容后面，过去会丢掉没保存的改动。",
                    confirmLabel = "放弃并前往", cancelLabel = "留在这里", destructive = true,
                ) { replaceStack(route) },
            )
        }
    }

    private fun replaceStack(route: Route) {
        val t = route.tab
        val s = stack(t)
        s.clear()
        if (route != t.root) s.add(route)
        tab = t
    }

    /** 守卫「关」：清空所有栈与弹层，落到提醒（本地功能）。弹窗不清 —— 「登录已失效」那一句就在弹窗里。 */
    fun resetForSignedOut() {
        stacks.values.forEach { it.clear() }
        sheets.clear()
        tab = Tab.standaloneHome
    }

    /** 保险箱上锁：退掉各栈里保险箱子树的页。 */
    fun popVaultSubtree() {
        stacks.values.forEach { s ->
            val i = s.indexOfFirst { it.isVaultSubtree && it != Route.VaultHome }
            if (i >= 0) while (s.size > i) s.removeAt(s.lastIndex)
        }
    }

    fun present(sheet: UmbraSheet) { sheets.add(sheet) }
    fun popSheet() { if (sheets.isNotEmpty()) sheets.removeAt(sheets.lastIndex) }
    fun closeSheets() = sheets.clear()

    fun confirm(a: UmbraAlert) { alert = a }

    /** 吐司：不带动作 1.8 秒，带动作 3 秒（iOS 同）。 */
    fun showToast(text: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        val t = UmbraToast(text, actionLabel, action)
        toast = t
        toastJob?.cancel()
        toastJob = scope.launch {
            delay(if (action != null) 3000 else 1800)
            if (toast?.id == t.id) toast = null
        }
    }
}
