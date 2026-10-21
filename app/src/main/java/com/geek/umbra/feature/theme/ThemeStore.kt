// 主题运行时：选着哪个主题、装着哪些、许可证、试用、回落。照 iOS `Features/Theme/ThemeStore.swift`（它照 PC `src/theme/runtime.ts` +
// `electron/core/themes/store.ts` / `license.ts` / `src/theme/acquire.ts`）。
//
// - 选着的主题 id 存 Prefs `umbra.themeId`（键名同 iOS；和外观 `umbra.appearance` 并列、互不影响 —— 主题和「浅 / 深 / 跟随系统」是两个独立选项）。
//   默认主题 = 这个键不存在。
// - 换了就把整套值交给 [UmbraThemePalette]（UmbraTheme 与小组件先查它），再刷一遍小组件。Compose 换 CompositionLocal 只会重组、
//   不会丢各页的状态 —— 所以 iOS 那条「试用到点时开着编辑页就再等 3 秒」（SwiftUI 要换根视图身份、会丢 @State）这里用不着。
// - 装的主题落 `filesDir/themes/<id>/<version>/package.umbraplugin`（**存原包**，每次启动按内容重验签名、判据、授权 ——
//   同 Studio「按内容验，不看有没有签名文件」）；许可证 `themes/license.json`、它属于哪个账号 `themes/license.owner`、
//   见过的最晚时间 `themes/clock.json`。
// - 启动不闪：正式换上的那套另存一份 `themes/applied.json`，AppGraph 建好时同步读它先套上；原包验签在后台跑，验不过再退回默认。
//   iOS 是启动时同步验（CryptoKit 快）；Android 的 Ed25519 是手写的 BigInteger，放主线程会拖冷启动。
// - 回落：选着的主题包坏了 / 验签不过 / 配色不过线 / 授权过期 → **整套退回默认**并说一句，绝不出现「半套主题」；
//   选择本身不清掉（keepChoice）：续上授权后自动恢复。
// - 试用：临时套上、不写选择、不存 applied.json、不刷小组件，30 秒不点「用这个」自动退回（themeKit.appearancePage.trial）。
//
// **授权按账号**（geek-17 10-21 交代；PC #128：整机一份许可证、不核账号，换号后照用上一个人买的主题）：
//   - 许可证从服务端拉，每次登录后刷新；**登出、换账号时清掉本机许可证**；
//   - 读取时核对账号：许可证里签给的 `sub` 要等于记下的账号，记下的账号要等于现在登着的那个（冷启动还没问到时先信记下的）。
//   没有有效授权的主题（含免费的 —— 免费的也要「买」一次才进许可证）退回默认，选择留着，登回来自动换回去。
//
// 和 iOS 不一样的（已拍板）：内置只有「默认」，海盐 / 石墨从商城装；Android 是新装，**不移 ThemeMigration**（升级后自动装回那一截）；
// **Android 卖付费主题**（sam 10-21）：用积分直接买，走 `POST /plugins/{id}/buy`；iOS 那套「iOS 不卖」（03599d0 / 32de292）不移。
package com.geek.umbra.feature.theme

import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraThemePalette
import com.geek.umbra.feature.widget.WidgetBridge
import com.geek.umbra.net.AccountSwitchRules
import com.geek.umbra.net.ApiResult
import com.geek.umbra.net.HttpResult
import com.geek.umbra.net.LoginState
import com.geek.umbra.net.Prefs
import com.geek.umbra.net.UmbraJson
import com.geek.umbra.net.bool
import com.geek.umbra.net.obj
import com.geek.umbra.net.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

/** 商城主题目录的一条（`GET /plugins/catalog?type=theme`）。宽松解析：缺字段给空，不让一条坏的拖垮整个目录。 */
data class ThemeCatalogEntry(
    val id: String,
    val name: String,
    val author: String,
    val version: String,
    /** 积分。解不出按 [ThemeShopRules.UNKNOWN_PRICE]（iOS #94：当 0 的话付费主题会以「免费」身份一路走到购买）。 */
    val price: Int,
    val brief: String,
    val size: String,
    val packageUrl: String,
    /** 商城色块：浅 / 深各 `bg` / `card` / `accent`（服务端上传时从 tokens 回填）。 */
    val preview: Map<String, Map<String, String>>,
    /** 「随会员提供」。服务端目录今天还不给这个字段（同 iOS 那条交回），缺字段当 false。 */
    val memberGift: Boolean,
) {
    companion object {
        fun parse(o: JsonObject): ThemeCatalogEntry? {
            val id = o.str("id")?.takeIf { it.isNotEmpty() } ?: return null
            val price = (o["price"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: ThemeShopRules.UNKNOWN_PRICE
            val preview = o.obj("preview")?.mapNotNull { (m, v) ->
                (v as? JsonObject)?.let { kv -> m to kv.mapNotNull { (k, c) -> (c as? JsonPrimitive)?.takeIf { it.isString }?.let { k to it.content } }.toMap() }
            }?.toMap().orEmpty()
            return ThemeCatalogEntry(
                id, o.str("name") ?: id, o.str("author").orEmpty(), o.str("version").orEmpty(), price,
                o.str("brief").orEmpty(), o.str("size").orEmpty(), o.str("package_url").orEmpty(), preview, o.bool("member_gift") ?: false,
            )
        }
    }
}

/** 本机装着的一个商城主题（不含内置）。`failure` 有值 = 现在用不了（包坏了 / 验签 / 配色 / 授权）。 */
data class InstalledTheme(
    val id: String,
    val version: String,
    val name: String,
    val author: String,
    val brief: String,
    val palette: ThemePalette?,
    val failure: ThemeFail?,
    val entitlement: ThemeLicense.Entitlement,
)

object ThemeStore {
    /** 试用多久不确认就自动退回（方案 §3.6）。 */
    const val TRIAL_SECONDS = 30
    /** 下载上限与超时（同 PC `MAX_THEME_DOWNLOAD` / `THEME_DOWNLOAD_TIMEOUT_MS`）。 */
    private const val MAX_DOWNLOAD = 2 * 1024 * 1024
    private const val DOWNLOAD_TIMEOUT_MS = 30_000L

    /** 一个能套上的主题：`palette` null = 内置默认（不装调色板）。 */
    data class Resolved(val id: String, val name: String, val palette: ThemePalette?)

    data class Trial(
        val theme: Resolved,
        val untilMs: Long,
        /** 没装的主题按「用这个」时先去拿（获取 + 装），拿到了才正式换上；回原因 = 没拿到。 */
        val onKeep: (suspend () -> String?)? = null,
    )

    /** 最近一次「选着的主题用不了、退回默认」：外观页挂一张错误卡（处理掉或点关闭才收）。 */
    data class Fallback(val id: String, val name: String, val fail: ThemeFail, val until: String? = null, val src: String? = null)

    /** [resolve] 的结果：能套上的整套，或回落要用的那一份（带名字、授权到期日 —— 错误卡要写）。 */
    sealed interface Resolution {
        data class Ok(val theme: Resolved) : Resolution
        data class Fail(val fallback: Fallback) : Resolution
    }

    private val default = Resolved(ThemeOfficial.DEFAULT_ID, "默认", null)

    private val _applied = MutableStateFlow(default)
    val applied: StateFlow<Resolved> = _applied
    private val _trial = MutableStateFlow<Trial?>(null)
    val trial: StateFlow<Trial?> = _trial
    private val _installed = MutableStateFlow<List<InstalledTheme>>(emptyList())
    val installed: StateFlow<List<InstalledTheme>> = _installed
    private val _grants = MutableStateFlow<List<ThemeGrant>?>(null)
    val grants: StateFlow<List<ThemeGrant>?> = _grants
    val fallback = MutableStateFlow<Fallback?>(null)
    /** 商城目录：null = 还没问到；[] = 问到了、空的。`catalogFailed` = 问不到（接口没部署 / 断网）。 */
    private val _catalog = MutableStateFlow<List<ThemeCatalogEntry>?>(null)
    val catalog: StateFlow<List<ThemeCatalogEntry>?> = _catalog
    private val _catalogFailed = MutableStateFlow(false)
    val catalogFailed: StateFlow<Boolean> = _catalogFailed
    /** 正在获取 / 装的 id（钮上写「获取中…」、不让连点）。 */
    private val _busyId = MutableStateFlow<String?>(null)
    val busyId: StateFlow<String?> = _busyId

    private var trialJob: Job? = null
    /** 扫描 / 许可证读写排队：启动时的后台重验和登录后的刷新可能撞在一起。 */
    private val io = Mutex()
    private var started = false

    private val prefs get() = AppGraph.prefs
    private val router get() = AppGraph.router
    private fun str(id: Int, vararg a: Any): String = AppGraph.context.getString(id, *a)

    // MARK: 内置 / 显示名

    /** 内置主题的名字。只有「默认」。 */
    fun builtinName(id: String): String? = if (id == ThemeOfficial.DEFAULT_ID) "默认" else null
    fun isBuiltin(id: String) = builtinName(id) != null

    private fun english(): Boolean = AppGraph.context.resources.configuration.locales[0]?.language == "en"

    /** 界面上显示的主题名 / 简介：英文界面时官方那几套查 [ThemeCopy]（包和后台只有中文），其余照给的。 */
    fun shownName(id: String, served: String): String = ThemeCopy.name(id, served, english())
    fun shownBrief(id: String, served: String): String = ThemeCopy.brief(id, served, english())

    /**
     * 默认主题在缩略图上画的那几色：Android 自己的内置值（同 `UmbraTokens.kt`，不随当前主题变 —— 缩略图要画的是「换过去会是什么样」）。
     * 值同 iOS `ThemeStore.defaultSwatch`（两端内置底色一样，都是 iOS v2）。
     */
    val defaultSwatch: ThemePalette = mapOf(
        ThemeMode.Light to mapOf(
            ThemeKey.Bg to "#F1EFEA", ThemeKey.Card to "#FFFFFF", ThemeKey.Nav to "#15110E", ThemeKey.Chip to "#F2F0EC",
            ThemeKey.Border to "#E6E3DC", ThemeKey.Text to "#1F2320", ThemeKey.Muted to "#656B65", ThemeKey.Faint to "#9A9992",
            ThemeKey.Accent to "#E8590C", ThemeKey.AccentSoft to "#FFF1E6", ThemeKey.OnAccent to "#FFFFFF",
        ),
        ThemeMode.Dark to mapOf(
            ThemeKey.Bg to "#151310", ThemeKey.Card to "#26231F", ThemeKey.Nav to "#15110E", ThemeKey.Chip to "#2A251F",
            ThemeKey.Border to "#332E26", ThemeKey.Text to "#EDEAE3", ThemeKey.Muted to "#9A938A", ThemeKey.Faint to "#6E675E",
            ThemeKey.Accent to "#E8590C", ThemeKey.AccentSoft to "#E8590C29", ThemeKey.OnAccent to "#FFFFFF",
        ),
    )

    // MARK: 文件

    private val root: File get() = File(AppGraph.context.filesDir, "themes")
    private val licenseFile get() = File(root, "license.json")
    private val ownerFile get() = File(root, "license.owner")
    private val clockFile get() = File(root, "clock.json")
    private val appliedFile get() = File(root, "applied.json")

    // MARK: 启动

    /**
     * AppGraph 建好时调一次（Application.onCreate，早于任何 Activity 与小组件）：
     * ① 同步读 applied.json 先套上（不闪默认色）；② 后台按内容重验装着的包、读许可证，选着的用不了就退回默认并说一句；
     * ③ 盯登录态：登录后刷新许可证，登出 / 换号清掉。
     */
    fun start() {
        if (started) return
        started = true
        readAppliedCache()?.let { (r, palette) ->
            _applied.value = r
            UmbraThemePalette.set(palette)
        }
        AppGraph.scope.launch {
            withContext(Dispatchers.Default) { io.withLock { _grants.value = loadLicense(); scanInstalledLocked() } }
            val id = prefs[Prefs.K.themeId] ?: ThemeOfficial.DEFAULT_ID
            when (val r = resolve(id)) {
                is Resolution.Ok -> if (r.theme != _applied.value) apply(r.theme)
                is Resolution.Fail -> {
                    // 选择不清掉（keepChoice）：续上授权 / 重新下载后自动恢复。
                    // 没登录时授权本来就清掉了（登出即清）：静默用默认，不挂卡、不弹吐司 —— 不然每次冷启动都说一遍「用不了」。
                    if (r.fallback.fail.kind == ThemeFailKind.License && !AppGraph.auth.hasToken) revertQuietly()
                    else { fallBack(r.fallback); announceFallback() }
                }
            }
        }
        AppGraph.scope.launch {
            AppGraph.auth.login.collect { onLogin(it) }
        }
    }

    // MARK: 账号（授权按账号）

    private fun identity(userId: Long) = AccountSwitchRules.identity(userId.toString(), AppGraph.http.server)

    /** 现在的账号（身份串 = 服务器 + 账号 id）。冷启动还没问到（Unknown 但有令牌）回 null —— 由 [licenseAccount] 先信记下的那个。 */
    private fun accountIdentity(): String? = (AppGraph.auth.login.value as? LoginState.SignedIn)?.user?.id?.let(::identity)

    private var lastLogin: LoginState? = null

    private suspend fun onLogin(s: LoginState) {
        val prev = lastLogin
        lastLogin = s
        when (s) {
            is LoginState.SignedIn -> {
                val now = identity(s.user.id)
                val owner = readOwner()
                if (owner != null && owner != now) clearLicense()       // 换了账号：上一个人的许可证不留
                // SignedIn 会因为设备列表变了再发一次：同一个账号不重复拉
                if (prev !is LoginState.SignedIn || prev.user.id != s.user.id) AppGraph.scope.launch { refreshLicense() }
            }
            LoginState.Out -> if (readOwner() != null || _grants.value != null) clearLicense()
            LoginState.Unknown, LoginState.Invalid -> Unit             // 断网 / 会话失效不算登出：许可证留着
        }
    }

    /** 登出 / 换号：删掉本机许可证与它的账号记录，按「没有授权」重判装着的；选着的商城主题退回默认（选择留着，登回来换回去）。 */
    private suspend fun clearLicense() {
        withContext(Dispatchers.IO) {
            io.withLock {
                licenseFile.delete(); ownerFile.delete()
                _grants.value = null
                scanInstalledLocked()
            }
        }
        // 用着的商城主题这下没了授权：静默退回默认（人刚登出 / 换号，知道为什么），不挂错误卡；选择留着，登回来 [restoreChoice] 换回去。
        val a = _applied.value
        if (a.palette != null && resolve(a.id) is Resolution.Fail) {
            endTrialSilently()
            revertQuietly()
        }
    }

    /** 退回默认但不出声、不改选择（授权没了的那几种：登出、换号、冷启动没登录）。 */
    private fun revertQuietly() {
        _applied.value = default
        UmbraThemePalette.set(null)
        writeAppliedCache(default)
        WidgetBridge.themeChanged()
    }

    private fun readOwner(): String? = runCatching { ownerFile.readText() }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** 读许可证时要对上的账号：登着就是现在这个；冷启动还没问到（有令牌）先信记下的；没登录 → null（不认任何许可证）。 */
    private fun licenseAccount(owner: String?): String? = when (AppGraph.auth.login.value) {
        is LoginState.SignedIn -> accountIdentity()
        LoginState.Unknown, LoginState.Invalid -> owner.takeIf { AppGraph.auth.hasToken }
        LoginState.Out -> null
    }

    /** 身份串里的账号 id（`server \u0000 user`）。 */
    private fun userOf(identity: String) = identity.substringAfter('\u0000', "")

    private fun loadLicense(): List<ThemeGrant>? {
        val text = runCatching { licenseFile.readText() }.getOrNull() ?: return null
        val owner = readOwner() ?: return null
        val account = licenseAccount(owner) ?: return null
        if (account != owner) return null
        val doc = (ThemeLicense.verify(text, ThemePublisher.PEM) as? ThemeResult.Ok)?.value ?: return null
        return doc.grants.takeIf { ThemeLicense.belongsTo(doc, userOf(owner)) }
    }

    // MARK: 回落

    private fun fallBack(f: Fallback) {
        fallback.value = f
        _applied.value = default
        UmbraThemePalette.set(null)
        writeAppliedCache(default)
        WidgetBridge.themeChanged()
    }

    /** 启动时有回落就说一句（iOS `announceFallback`，外壳 onAppear 调；Android 在后台重验完时调）。 */
    private fun announceFallback() {
        val f = fallback.value ?: return
        router.showToast(str(R.string.theme_fbToast, shownName(f.id, f.name)), str(R.string.theme_fbToastLook)) { openAppearance() }
    }

    fun openAppearance() = router.jump(com.geek.umbra.navigation.Route.SetAppearance)

    fun dismissFallback() { fallback.value = null }

    // MARK: 解析

    /** id → 能套上的整套；用不了回原因（错误卡按 kind 选 fallbackCopy 那句话）。 */
    fun resolve(id: String): Resolution {
        builtinName(id)?.let { return Resolution.Ok(Resolved(id, it, null)) }
        val t = _installed.value.firstOrNull { it.id == id }
            ?: return Resolution.Fail(Fallback(id, _catalog.value?.firstOrNull { it.id == id }?.name ?: id, ThemeFail(ThemeFailKind.Broken, "本机没有这个主题")))
        t.failure?.let { return Resolution.Fail(Fallback(id, t.name, it, t.entitlement.until, t.entitlement.src)) }
        val p = t.palette ?: return Resolution.Fail(Fallback(id, t.name, ThemeFail(ThemeFailKind.Broken, "读不出配色")))
        return Resolution.Ok(Resolved(id, t.name, p))
    }

    // MARK: 应用

    /** 正式换成某个主题（写选择、存 applied.json、刷小组件）。选了别的就把回落那张错误卡收掉。 */
    private fun apply(r: Resolved) {
        endTrialSilently()
        fallback.value = null
        _applied.value = r
        prefs.set(Prefs.K.themeId, r.id.takeIf { it != ThemeOfficial.DEFAULT_ID })
        UmbraThemePalette.set(r.palette?.let(::plain))
        writeAppliedCache(r)
        WidgetBridge.themeChanged()
    }

    /** 按 id 换（外观页长按「用这个」、商城「用这个」）。用不了回原因。 */
    fun use(id: String): String? = when (val r = resolve(id)) {
        is Resolution.Ok -> {
            apply(r.theme)
            router.showToast("已换成「${shownName(r.theme.id, r.theme.name)}」")
            null
        }
        is Resolution.Fail -> r.fallback.fail.why
    }

    /** `ThemePalette` → `{"light": {键: 色}, "dark": {…}}`（[UmbraThemePalette] 用的形状）。 */
    fun plain(p: ThemePalette): Map<String, Map<String, String>> =
        p.entries.associate { (m, kv) -> m.wire to kv.entries.associate { it.key.wire to it.value } }

    private fun writeAppliedCache(r: Resolved) {
        runCatching {
            if (r.palette == null) { appliedFile.delete(); return }
            root.mkdirs()
            val o = buildJsonObject {
                put("id", r.id); put("name", r.name)
                for ((m, kv) in r.palette) putJsonObject(m.wire) { kv.forEach { (k, v) -> put(k.wire, v) } }
            }
            val tmp = File(root, "applied.json.tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(appliedFile)
        }
    }

    /**
     * 读 applied.json：选择还是它、形状对、每一档都过颜色形状 才用。坏了改名 `.corrupt-<时间>` 留底（工程约定第 11 条），从默认开始。
     * 这里**不验签**（那一步在后台跑）：applied.json 是本 App 自己写的，只有过了验签、判据、授权的整套才会写进来。
     */
    private fun readAppliedCache(): kotlin.Pair<Resolved, Map<String, Map<String, String>>>? {
        val f = appliedFile
        if (!f.exists()) return null
        val selected = prefs[Prefs.K.themeId] ?: return null
        val o = runCatching { UmbraJson.parseToJsonElement(f.readText()) as JsonObject }.getOrNull()
        val id = o?.str("id")
        val palette = o?.let {
            ThemeMode.entries.associate { m ->
                m.wire to (it.obj(m.wire)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.takeIf { c -> ThemeRules.parseColor(c) != null }?.let { c -> k to c } }?.toMap().orEmpty())
            }
        }
        if (o == null || id == null || palette == null || palette.values.any { it.isEmpty() }) {
            runCatching { f.renameTo(File(root, "applied.json.corrupt-${System.currentTimeMillis()}")) }
            return null
        }
        if (id != selected) return null
        val typed = palette.entries.associate { (m, kv) -> ThemeMode.of(m)!! to kv.mapNotNull { (k, v) -> ThemeKey.of(k)?.let { it to v } }.toMap() }
        return Resolved(id, o.str("name") ?: id, typed) to palette
    }

    // MARK: 试用

    /** 现在界面上生效的那一套（试用中就是试用的那套）。 */
    val effective: Resolved get() = _trial.value?.theme ?: _applied.value

    /** 临时套用：不写选择、不存 applied.json、不刷小组件，30 秒不确认自动退回。试用中再试别的 = 换对象、秒数重置。 */
    fun startTrial(r: Resolved, onKeep: (suspend () -> String?)? = null) {
        trialJob?.cancel()
        _trial.value = Trial(r, System.currentTimeMillis() + TRIAL_SECONDS * 1000L, onKeep)
        UmbraThemePalette.set(r.palette?.let(::plain))
        trialJob = AppGraph.scope.launch {
            delay(TRIAL_SECONDS * 1000L)
            if (_trial.value == null) return@launch
            endTrialSilently()
            UmbraThemePalette.set(_applied.value.palette?.let(::plain))
            router.showToast(str(R.string.theme_trialEnded, shownName(_applied.value.id, _applied.value.name)))
        }
    }

    /** 结束试用：`keep` = 「用这个」，正式换上（没装的先去拿，拿不到回原因、试用照旧）；否则回到原来那套。 */
    suspend fun endTrial(keep: Boolean): String? {
        val t = _trial.value ?: return null
        if (keep) {
            t.onKeep?.let { k -> k()?.let { return it } }
            // 拿到以后以装好的那份为准（试用时那套是临时下载的，内容一样，但以落盘验过的为准）
            val r = (resolve(t.theme.id) as? Resolution.Ok)?.theme ?: t.theme
            apply(r)
            router.showToast("已换成「${shownName(r.id, r.name)}」")
            return null
        }
        endTrialSilently()
        UmbraThemePalette.set(_applied.value.palette?.let(::plain))
        return null
    }

    private fun endTrialSilently() {
        trialJob?.cancel()
        trialJob = null
        _trial.value = null
    }

    // MARK: 本机装着的

    /** 列本机装着的商城主题：每个 `<id>/<version>/package.umbraplugin` 按内容重验（签名 → 判据）再判授权。 */
    suspend fun scanInstalled() = withContext(Dispatchers.Default) { io.withLock { scanInstalledLocked() } }

    private fun scanInstalledLocked() {
        val out = mutableListOf<InstalledTheme>()
        val now = now()
        val grants = _grants.value
        for (idDir in root.listFiles().orEmpty()) {
            val id = idDir.name
            if (!idDir.isDirectory || !ThemePackage.ID_PATTERN.containsMatchIn(id)) continue
            // 同一个 id 有多个版本取最新写进去的那个（安装时会删掉旧版本，这里只是兜底）
            val vDir = idDir.listFiles().orEmpty().filter { it.isDirectory }.maxByOrNull { it.lastModified() } ?: continue
            val ent = ThemeLicense.entitlement(grants, id, now)
            val data = runCatching { File(vDir, "package.umbraplugin").readBytes() }.getOrNull()
            if (data == null) {
                out += InstalledTheme(id, vDir.name, id, "", "", null, ThemeFail(ThemeFailKind.Broken, "主题文件不在了"), ent)
                continue
            }
            when (val r = ThemePackage.inspect(data, ThemePublisher.PEM)) {
                is ThemeResult.Err -> out += InstalledTheme(id, vDir.name, id, "", "", null, r.fail, ent)
                is ThemeResult.Ok -> {
                    val t = r.value
                    val lic = if (ent.state == ThemeLicense.State.Active) null else ThemeFail(ThemeFailKind.License, licenseWhy(ent))
                    out += InstalledTheme(id, t.manifest.version, t.manifest.name, t.manifest.author, t.manifest.brief,
                        if (lic == null) t.palette else null, lic, ent)
                }
            }
        }
        _installed.value = out.sortedBy { it.name }
    }

    private fun licenseWhy(e: ThemeLicense.Entitlement) = when (e.state) {
        ThemeLicense.State.Expired -> "使用期到了"
        ThemeLicense.State.NotYet -> "使用期还没开始"
        else -> "还没有它的使用授权"
    }

    /** 校验过的包落盘：`<id>/<version>/package.umbraplugin`，同一 id 的旧版本删掉。先写临时文件再改名（原子替换）。 */
    private fun save(data: ByteArray, manifest: ThemeManifest) {
        val idDir = File(root, manifest.id)
        val vDir = File(idDir, manifest.version)
        if (!vDir.mkdirs() && !vDir.isDirectory) error("建不了目录")
        val tmp = File(vDir, "package.umbraplugin.tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(File(vDir, "package.umbraplugin"))) error("改不了名")
        idDir.listFiles().orEmpty().filter { it.name != manifest.version }.forEach { it.deleteRecursively() }
    }

    /** 卸载一个商城主题（内置的卸不掉）。卸的正是现在用的 → 换回默认。 */
    suspend fun uninstall(id: String) {
        if (isBuiltin(id)) return
        val name = shownName(id, _installed.value.firstOrNull { it.id == id }?.name ?: id)
        withContext(Dispatchers.IO) { io.withLock { File(root, id).deleteRecursively(); scanInstalledLocked() } }
        if (_trial.value?.theme?.id == id) endTrial(keep = false)
        if (_applied.value.id == id || prefs[Prefs.K.themeId] == id) {
            apply(default)
            router.showToast("已卸载「$name」，换回了「默认」")
        } else {
            router.showToast("已卸载「$name」")
        }
        if (fallback.value?.id == id) fallback.value = null
    }

    // MARK: 许可证

    /** 「现在」= 系统时间和见过的最晚时间里更晚的那个（挡「把系统时间调回去」续命过期的授权）。写坏了当没见过。 */
    fun now(): Instant {
        val sys = System.currentTimeMillis()
        val seen = runCatching {
            (UmbraJson.parseToJsonElement(clockFile.readText()) as JsonObject)["seen"]?.let { (it as JsonPrimitive).content.toDouble().toLong() }
        }.getOrNull()?.takeIf { it > 0 } ?: 0L
        val now = maxOf(sys, seen)
        if (now > seen) runCatching { root.mkdirs(); clockFile.writeText("{\"seen\":$now}") }
        return Instant.ofEpochMilli(now)
    }

    /**
     * 取一次许可证：先验签、核账号，不过不落盘；落了就按新授权重判装着的，选着的主题能用了就换回去（keepChoice）。
     * 回授权里此刻有效的 id（取不到 / 验不过回 null）。
     */
    suspend fun refreshLicense(): Set<String>? {
        val user = (AppGraph.auth.login.value as? LoginState.SignedIn)?.user ?: return null
        val r = AppGraph.http.raw("GET", "/plugins/license") as? HttpResult.Ok ?: return null
        val body = runCatching { UmbraJson.parseToJsonElement(r.body) as JsonObject }.getOrNull() ?: return null
        val text = ThemeLicense.text(body.str("payload"), body.str("signature")) ?: return null
        val doc = (ThemeLicense.verify(text, ThemePublisher.PEM) as? ThemeResult.Ok)?.value ?: return null
        // 服务端签给的不是现在这个账号（理论上不会；中途换了号就会）→ 不落盘
        if (!ThemeLicense.belongsTo(doc, user.id.toString())) return null
        withContext(Dispatchers.IO) {
            io.withLock {
                root.mkdirs()
                File(root, "license.json.tmp").apply { writeText(text) }.renameTo(licenseFile)
                ownerFile.writeText(identity(user.id))
                _grants.value = doc.grants
                scanInstalledLocked()
            }
        }
        restoreChoice()
        val now = now()
        return doc.grants.filter { ThemeLicense.entitlement(listOf(it), it.plugin, now).state == ThemeLicense.State.Active }.map { it.plugin }.toSet()
    }

    /** 选着的主题这下能用了（授权续上 / 登回来了），而界面还停在因回落而用的「默认」上 → 静默换回去。 */
    private fun restoreChoice() {
        val selected = prefs[Prefs.K.themeId] ?: return
        if (_trial.value != null || _applied.value.id == selected) return
        val r = (resolve(selected) as? Resolution.Ok)?.theme ?: return
        apply(r)
    }

    /** 授权从哪来（商城卡上写「随会员」）。⚠️ 过期的授权也带来源 —— **只拿它定文案，别拿它判「有没有授权」**（iOS #94）。 */
    fun grantSource(id: String): String? = ThemeLicense.entitlement(_grants.value, id, Instant.now()).src

    /** 此刻有没有有效授权（本机许可证）。 */
    fun hasActiveGrant(id: String) = ThemeLicense.entitlement(_grants.value, id, Instant.now()).state == ThemeLicense.State.Active

    // MARK: 商城

    /** 商城目录（`GET /plugins/catalog?type=theme`，服务端说不要凭证；但 Android 的请求闸未登录时只放 /auth/ 与 /health，所以登着才拉得到）。 */
    suspend fun loadCatalog() {
        val r = AppGraph.http.raw("GET", "/plugins/catalog", mapOf("type" to "theme"))
        val list = (r as? HttpResult.Ok)?.let {
            runCatching { ((UmbraJson.parseToJsonElement(it.body) as JsonObject)["plugins"] as? JsonArray)?.mapNotNull { e -> (e as? JsonObject)?.let(ThemeCatalogEntry::parse) } }.getOrNull()
        }
        if (list != null) {
            _catalog.value = list
            _catalogFailed.value = false
        } else {
            _catalogFailed.value = true
            if (_catalog.value == null) _catalog.value = emptyList()
        }
    }

    /** 下载一个包并整包检查（不落盘）。只认 https、有上限、有超时（同 PC `download`）。主题包放在 CDN，不带我们的令牌。 */
    private suspend fun fetch(e: ThemeCatalogEntry): ThemeResult<kotlin.Pair<ByteArray, InspectedTheme>> {
        val url = e.packageUrl.trim()
        if (url.isEmpty()) return ThemeResult.Err(ThemeFail(ThemeFailKind.Broken, "发布方还没放安装包"))
        if (!url.startsWith("https://")) return ThemeResult.Err(ThemeFail(ThemeFailKind.Broken, "主题安装包地址不是 https，不下载"))
        val data = withContext(Dispatchers.IO) {
            runCatching {
                val c = AppGraph.http.client.newBuilder().callTimeout(DOWNLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
                c.newCall(Request.Builder().url(url).header("Accept", "application/octet-stream").build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    if (resp.body.contentLength() > MAX_DOWNLOAD) return@use ByteArray(0)
                    val src = resp.body.byteStream()
                    val buf = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        val n = src.read(chunk)
                        if (n < 0) break
                        buf.write(chunk, 0, n)
                        if (buf.size() > MAX_DOWNLOAD) return@use ByteArray(0)
                    }
                    buf.toByteArray()
                }
            }
        }
        val bytes = data.getOrNull()
        if (data.isFailure) return ThemeResult.Err(ThemeFail(ThemeFailKind.Broken, "安装包下不下来，检查网络后再试"))
        if (bytes == null) return ThemeResult.Err(ThemeFail(ThemeFailKind.Broken, "安装包下不下来"))
        if (bytes.isEmpty()) return ThemeResult.Err(ThemeFail(ThemeFailKind.Broken, "主题安装包太大"))
        return when (val r = withContext(Dispatchers.Default) { ThemePackage.inspect(bytes, ThemePublisher.PEM) }) {
            is ThemeResult.Err -> r
            is ThemeResult.Ok -> if (r.value.manifest.id != e.id) ThemeResult.Err(ThemeFail(ThemeFailKind.Broken, "包里的主题不是这一个"))
            else ThemeResult.Ok(bytes to r.value)
        }
    }

    /**
     * 拿一个主题（照 PC `acquireTheme` 的顺序）：取许可证 → 没授权就买（免费的扣 0；付费的扣积分，调用方已经问过一句）→
     * 再取许可证核一遍 → 没装就下载装包。回 null = 拿到了；否则回原因。
     * Android 卖付费主题（sam 10-21），所以这里和 PC 一样买；只留 iOS #94 那一道：价格读不出来不买（不知道要扣多少）。
     */
    suspend fun acquire(e: ThemeCatalogEntry): String? {
        if (_busyId.value != null) return null
        _busyId.value = e.id
        try {
            val isInstalled = _installed.value.any { it.id == e.id && it.failure?.kind != ThemeFailKind.Broken && it.failure?.kind != ThemeFailKind.Signature }
            if (!isInstalled && e.packageUrl.isEmpty()) return "发布方还没放安装包"
            var granted = refreshLicense()
            if (granted?.contains(e.id) != true) {
                if (!ThemeShopRules.mayBuy(e.price)) return str(R.string.android_theme_priceUnknown)
                when (val r = AppGraph.http.call("POST", "/plugins/${e.id}/buy", JsonObject(emptyMap()))) {
                    is ApiResult.Err -> return r.detail?.takeIf { it.isNotEmpty() && !r.offline } ?: if (r.offline) "连不上服务器，稍后再试" else "没买成，稍后再试"
                    is ApiResult.Ok -> Unit
                }
                granted = refreshLicense()
                if (granted?.contains(e.id) != true) return "许可证里没有这个主题的授权"
            }
            if (!isInstalled) {
                when (val r = fetch(e)) {
                    is ThemeResult.Err -> return r.fail.why
                    is ThemeResult.Ok -> {
                        val ok = withContext(Dispatchers.IO) { io.withLock { runCatching { save(r.value.first, r.value.second.manifest) }.isSuccess } }
                        if (!ok) return "这台手机存不下这个主题"
                    }
                }
            }
            scanInstalled()
            return null
        } finally {
            _busyId.value = null
        }
    }

    /** 试用一个商城主题：装着的直接用；没装的临时下载、验签、过门槛，不落盘（「用这个」再去拿）。 */
    suspend fun trialFromShop(e: ThemeCatalogEntry, onKeep: suspend () -> String?): String? {
        val t = _installed.value.firstOrNull { it.id == e.id }
        if (t?.palette != null) {
            startTrial(Resolved(e.id, t.name, t.palette))
            return null
        }
        return when (val r = fetch(e)) {
            is ThemeResult.Err -> r.fail.why
            is ThemeResult.Ok -> { startTrial(Resolved(e.id, r.value.second.manifest.name, r.value.second.palette), onKeep); null }
        }
    }

    /** 回落那张卡上的「重新下载」：包坏了 / 验签不过时重新拿一份装上并换上。 */
    suspend fun redownload(id: String): String? {
        if (_catalog.value == null) loadCatalog()
        val e = _catalog.value?.firstOrNull { it.id == id } ?: return "商城里找不到这个主题"
        when (val r = fetch(e)) {
            is ThemeResult.Err -> return r.fail.why
            is ThemeResult.Ok -> {
                val ok = withContext(Dispatchers.IO) { io.withLock { runCatching { save(r.value.first, r.value.second.manifest) }.isSuccess } }
                if (!ok) return "这台手机存不下这个主题"
            }
        }
        scanInstalled()
        return use(id)
    }
}
