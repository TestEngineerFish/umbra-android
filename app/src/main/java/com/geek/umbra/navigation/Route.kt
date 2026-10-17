// 路由表 —— 照抄 iOS Navigation/RouteRules.swift。
//
// 下面几张表（area / needsLogin / isEditor）都用穷举 `when`、**不写 else**：
// 新加一条路由不归类就编不过。iOS 那边是同一个约束（穷举 switch 不写 default），
// 这是「新页面漏了登录拦截」唯一可靠的防线。
package com.geek.umbra.navigation

sealed interface Route {
    data object ChatContacts : Route
    data class ChatThread(val conv: String) : Route
    data object RemList : Route
    data class RemDetail(val id: String) : Route
    /** id == null 表示新建；prefill 是从聊天「添加提醒」带过来的正文。 */
    data class RemEdit(val id: String?, val prefill: String? = null) : Route
    data object ToolHome : Route
    data object ToolWidgets : Route
    data object TaskList : Route
    data class TaskDetail(val id: String) : Route
    data object InspList : Route
    data class InspDetail(val id: String) : Route
    data class InspEdit(val id: String?) : Route
    data object MoneyHome : Route
    data object MoneyList : Route
    data class MoneyAdd(val id: String?, val focusAccount: Boolean = false) : Route
    data object MoneyCats : Route
    data class MoneyCat(val slug: String) : Route
    data object MoneySettings : Route
    data object MoneyRecur : Route
    data class MoneyRecurEdit(val id: String?) : Route
    data object MeHome : Route
    data object MePhrases : Route
    data class MePhraseEdit(val id: String?) : Route
    data object MeDevices : Route
    data class DeviceDetail(val id: String) : Route
    data object MeCaps : Route
    data object MeMemory : Route
    data class MeMemoryItem(val id: String) : Route
    data class MeMemoryEdit(val id: String?, val kind: String) : Route
    data object KbList : Route
    data class KbDocs(val id: String, val name: String) : Route
    data object MeDuty : Route
    data class MeDutyEdit(val id: String?) : Route
    data object MeDutyLog : Route
    data class MeDutyFlow(val id: String) : Route
    data class MeDutyFlowRun(val id: String) : Route
    data object SetAccount : Route
    data object SetShop : Route
    data object SetShopHistory : Route
    data object SetStorage : Route
    data object SetPassword : Route
    data object SetSessions : Route
    data object SetDeactivate : Route
    data object SetConn : Route
    data object SetNotify : Route
    data object SetGeneral : Route
    data object SetAbout : Route
    data object VaultHome : Route
    data object VaultCreate : Route
    data object VaultKey : Route
    data object VaultRecover : Route
    data class VaultRecord(val id: String, val find: String = "") : Route
    data class VaultEdit(val id: String?) : Route
    data object VaultGen : Route
    data object VaultCheck : Route
    data object VaultGroups : Route
    data object VaultProfiles : Route
    data object VaultTrash : Route
    data object VaultImport : Route
    data object VaultSettings : Route
    data object VaultPwd : Route
    data object VaultAutofill : Route
}

enum class RouteArea { Chat, Reminder, Tool, Vault, VaultTrash, Me }

/** 四个 Tab。iOS 的「聊天 / 提醒 / 工具 / 我」。 */
enum class Tab(val label: String, val icon: String, val root: Route) {
    Chat("聊天", com.geek.umbra.designsystem.UmbraIconPaths.chat, Route.ChatContacts),
    Rem("提醒", com.geek.umbra.designsystem.UmbraIconPaths.bell, Route.RemList),
    Tool("工具", com.geek.umbra.designsystem.UmbraIconPaths.wrench, Route.ToolHome),
    Me("我", com.geek.umbra.designsystem.UmbraIconPaths.userCircle, Route.MeHome);

    companion object {
        /** 单机模式默认落提醒 Tab：聊天的根页是联网页，单机打开就是一张「要登录」卡。 */
        val standaloneHome = Rem
    }
}

val Route.area: RouteArea
    get() = when (this) {
        Route.ChatContacts, is Route.ChatThread -> RouteArea.Chat
        Route.RemList, is Route.RemDetail, is Route.RemEdit -> RouteArea.Reminder
        Route.ToolHome, Route.ToolWidgets, Route.TaskList, is Route.TaskDetail,
        Route.InspList, is Route.InspDetail, is Route.InspEdit,
        Route.MoneyHome, Route.MoneyList, is Route.MoneyAdd, Route.MoneyCats, is Route.MoneyCat,
        Route.MoneyRecur, is Route.MoneyRecurEdit, Route.MoneySettings -> RouteArea.Tool
        Route.VaultTrash -> RouteArea.VaultTrash
        Route.VaultHome, Route.VaultCreate, Route.VaultKey, Route.VaultRecover, is Route.VaultRecord,
        is Route.VaultEdit, Route.VaultGen, Route.VaultCheck, Route.VaultGroups, Route.VaultProfiles,
        Route.VaultImport, Route.VaultSettings, Route.VaultPwd, Route.VaultAutofill -> RouteArea.Vault
        Route.MeHome, Route.MePhrases, is Route.MePhraseEdit, Route.MeDevices, is Route.DeviceDetail,
        Route.MeCaps, Route.MeMemory, is Route.MeMemoryItem, is Route.MeMemoryEdit, Route.KbList,
        is Route.KbDocs, Route.MeDuty, is Route.MeDutyEdit, Route.MeDutyLog, is Route.MeDutyFlow,
        is Route.MeDutyFlowRun, Route.SetAccount, Route.SetShop, Route.SetShopHistory, Route.SetStorage,
        Route.SetPassword, Route.SetSessions, Route.SetDeactivate, Route.SetConn, Route.SetNotify,
        Route.SetGeneral, Route.SetAbout -> RouteArea.Me
    }

/**
 * 要登录才能进的页。**本地功能（提醒、记账生活账本、灵感、常用语、保险箱）一律 false** ——
 * 2026-10-10 契约：未登录 = 单机，不是「什么都不能用」。
 */
val Route.needsLogin: Boolean
    get() = when (this) {
        Route.ChatContacts, is Route.ChatThread, Route.TaskList, is Route.TaskDetail,
        Route.KbList, is Route.KbDocs, Route.MeDevices, is Route.DeviceDetail, Route.MeCaps,
        Route.MeMemory, is Route.MeMemoryItem, is Route.MeMemoryEdit, Route.MeDuty, is Route.MeDutyEdit,
        Route.MeDutyLog, is Route.MeDutyFlow, is Route.MeDutyFlowRun, Route.SetPassword, Route.SetSessions,
        Route.SetDeactivate, Route.SetShop, Route.SetShopHistory, Route.SetStorage -> true
        Route.RemList, is Route.RemDetail, is Route.RemEdit, Route.MoneyHome, Route.MoneyList,
        is Route.MoneyAdd, Route.MoneyCats, is Route.MoneyCat, Route.MoneySettings, Route.MoneyRecur,
        is Route.MoneyRecurEdit, Route.InspList, is Route.InspDetail, is Route.InspEdit, Route.ToolHome,
        Route.ToolWidgets, Route.MeHome, Route.MePhrases, is Route.MePhraseEdit, Route.SetAccount,
        Route.SetConn, Route.SetNotify, Route.SetGeneral, Route.SetAbout, Route.VaultHome, Route.VaultCreate,
        Route.VaultKey, Route.VaultRecover, is Route.VaultRecord, is Route.VaultEdit, Route.VaultGen,
        Route.VaultCheck, Route.VaultGroups, Route.VaultProfiles, Route.VaultTrash, Route.VaultImport,
        Route.VaultSettings, Route.VaultPwd, Route.VaultAutofill -> false
    }

val Route.tab: Tab
    get() = when (area) {
        RouteArea.Chat -> Tab.Chat
        RouteArea.Reminder -> Tab.Rem
        RouteArea.Tool, RouteArea.Vault -> Tab.Tool
        RouteArea.Me, RouteArea.VaultTrash -> Tab.Me
    }

/** 保险箱子树：切后台要遮罩、自动上锁时要退出的那些页。 */
val Route.isVaultSubtree: Boolean
    get() = when (area) {
        RouteArea.Vault, RouteArea.VaultTrash -> true
        RouteArea.Chat, RouteArea.Reminder, RouteArea.Tool, RouteArea.Me -> false
    }

/** 编辑页：跨 Tab 跳过去会清掉它的栈，所以要先问「放弃正在写的内容？」。 */
val Route.isEditor: Boolean
    get() = when (this) {
        is Route.RemEdit, is Route.InspEdit, is Route.MoneyAdd, is Route.MoneyRecurEdit, is Route.MePhraseEdit,
        is Route.VaultEdit, Route.VaultRecover, is Route.MeMemoryEdit, is Route.MeDutyEdit, Route.SetAccount,
        Route.SetPassword, Route.SetConn -> true
        Route.ChatContacts, is Route.ChatThread, Route.RemList, is Route.RemDetail, Route.ToolHome,
        Route.ToolWidgets, Route.TaskList, is Route.TaskDetail, Route.InspList, is Route.InspDetail,
        Route.MoneyHome, Route.MoneyList, Route.MoneyCats, is Route.MoneyCat, Route.MoneySettings,
        Route.MoneyRecur, Route.MeHome, Route.MePhrases, Route.MeDevices, is Route.DeviceDetail, Route.MeCaps,
        Route.MeMemory, is Route.MeMemoryItem, Route.KbList, is Route.KbDocs, Route.MeDuty, Route.MeDutyLog,
        is Route.MeDutyFlow, is Route.MeDutyFlowRun, Route.SetSessions, Route.SetDeactivate, Route.SetShop,
        Route.SetShopHistory, Route.SetStorage, Route.SetNotify, Route.SetGeneral, Route.SetAbout,
        Route.VaultHome, Route.VaultCreate, Route.VaultKey, is Route.VaultRecord, Route.VaultGen,
        Route.VaultCheck, Route.VaultGroups, Route.VaultProfiles, Route.VaultTrash, Route.VaultImport,
        Route.VaultSettings, Route.VaultPwd, Route.VaultAutofill -> false
    }

enum class JumpPlan { Reveal, Replace, AskFirst }

/**
 * 跨 Tab 跳转三档（iOS UmbraJumpRules）：目标就在眼前只切 Tab；目标 Tab 栈里有编辑页先问；其余清栈再推。
 * [stack] 不含根页（与 iOS 的 path 同义：根页不在数组里）。
 */
object JumpRules {
    fun plan(route: Route, stack: List<Route>): JumpPlan {
        val showing = if (route == route.tab.root) stack.isEmpty() else stack.lastOrNull() == route
        if (showing) return JumpPlan.Reveal
        return if (stack.any { it.isEditor }) JumpPlan.AskFirst else JumpPlan.Replace
    }
}

/** 深链 `umbra://`。id 只认 `[A-Za-z0-9_-]{1,64}`；认不出的一律不跳（返回 null）。 */
object DeepLinkRules {
    private val idRe = Regex("^[A-Za-z0-9_-]{1,64}$")

    fun route(uri: String): Route? {
        val u = runCatching { java.net.URI(uri) }.getOrNull() ?: return null
        if (u.scheme?.lowercase() != "umbra") return null
        val host = u.host?.lowercase() ?: return null
        val seg = (u.path ?: "").trim('/').split('/').filter { it.isNotEmpty() }
        return when (host) {
            "tasks" -> Route.TaskList
            "task" -> seg.firstOrNull()?.takeIf { idRe.matches(it) }?.let { Route.TaskDetail(it) } ?: Route.TaskList
            "reminders" -> Route.RemList
            "reminder" -> seg.firstOrNull()?.takeIf { idRe.matches(it) }?.let { Route.RemDetail(it) } ?: Route.RemList
            "money" -> if (seg.firstOrNull() == "add") Route.MoneyAdd(null) else Route.MoneyHome
            // Android 独有：桌面快捷方式「记灵感」（iOS 用 App Intent，不走深链）
            "inspiration" -> if (seg.firstOrNull() == "new") Route.InspEdit(null) else Route.InspList
            else -> null
        }
    }
}
