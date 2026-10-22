package com.geek.umbra.feature.inspiration

import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraScreenPhase
import com.geek.umbra.net.str
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 灵感页的状态 —— 对应 iOS Features/Inspiration/InspirationsViewModel.swift（全 App 一份）。
 * 数据读本机账本（InspirationLocal），工具页的灵感卡也读这里（list / loadedOnce / firstLoading + 计数）。
 * 写操作一律把结果交回页面（umbra-ios#60）：成没成由页面决定去留和那一句（InspWriteRules）。
 */
object InspirationStore {
    private val _list = MutableStateFlow<List<Inspiration>>(emptyList())
    val list: StateFlow<List<Inspiration>> = _list

    /** 成功拉到过一次。本机账本读出来就算（空列表也是拉到了）。 */
    private val _loadedOnce = MutableStateFlow(false)
    val loadedOnce: StateFlow<Boolean> = _loadedOnce

    /** 首屏那一次拉取还没有结果。初值 true、只落一次（理由同 TasksStore.firstLoading）。 */
    private val _firstLoading = MutableStateFlow(true)
    val firstLoading: StateFlow<Boolean> = _firstLoading

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    /** 拉取序号：每趟发出时领一个，回来时只有比 appliedLoad 新才用（late-response-overwrites-list）。 */
    private var issuedLoad = 0
    private var appliedLoad = 0
    private var started = false

    /** 待办数（工具页卡底「N 条 · M 条待整理」那个 M —— iOS 数的是 status == open）。 */
    fun openCount(l: List<Inspiration> = _list.value) = l.count { it.status == "open" }

    fun screenPhase(offline: Boolean): UmbraScreenPhase =
        UmbraScreenPhase.resolve(_list.value.isNotEmpty(), _firstLoading.value, _loadedOnce.value, offline)

    /** WS inspiration_*（created / updated / deleted …）→ 立即同步并重载（iOS 经 .inspirationChanged 通知）。 */
    fun startListening() {
        if (started) return
        started = true
        AppGraph.scope.launch {
            AppGraph.socket.frames.collect { f ->
                if (f.str("type")?.startsWith("inspiration_") == true) {
                    AppGraph.scope.launch { InspirationLocal.sync(); load() }
                }
            }
        }
    }

    suspend fun retry() {
        _firstLoading.value = true
        load()
    }

    fun load() {
        issuedLoad += 1
        val mine = issuedLoad
        // 本机账本（单机化 ②-c）：读本机；登着的话顺手同步（节流），整理 / 调研结果随之回来。
        val fetched = InspirationLocal.list(null)
        if (InspWriteRules.shouldApplyLoad(mine, appliedLoad)) {
            appliedLoad = mine
            _list.value = fetched
            _loadedOnce.value = true
        }
        _firstLoading.value = false
    }

    suspend fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        InspirationLocal.sync()
        load()
        _refreshing.value = false
    }

    fun create(raw: String, title: String, tags: List<String>, note: String, research: Boolean): Boolean {
        val ok = InspirationLocal.create(raw, title, note, tags, research) != null
        if (ok) load()
        return ok
    }

    /**
     * 让秘书去查一查这条灵感。**乐观地先把本地状态推到 queued**（不先动的话点完按钮什么反应都没有）；
     * 没送达收回到调用前那个值 —— 但请求在飞期间列表刷新过的话不回滚（rollingBackResearch）。
     */
    suspend fun requestResearch(id: Long): Boolean {
        val before = _list.value.firstOrNull { it.id == id }?.research_status
        _list.value = InspWriteRules.settingResearch(_list.value, id, "queued")
        val loadAtMark = appliedLoad
        val ok = InspirationLocal.requestResearch(id)
        if (!ok) {
            _list.value = InspWriteRules.rollingBackResearch(_list.value, id, before, appliedLoad != loadAtMark)
            return false
        }
        load()
        return true
    }

    /** 编辑保存。patch 只含人改过的字段（#69）。存上就先改本地再拉：拉失败时屏上也不是改之前那一版。 */
    fun update(id: Long, patch: InspPatch): InspWriteRules.UpdateResult {
        val r = if (InspirationLocal.update(id, patch) != null) InspWriteRules.UpdateResult.Saved else InspWriteRules.UpdateResult.Gone
        if (r == InspWriteRules.UpdateResult.Saved) {
            _list.value = InspWriteRules.applying(_list.value, id, patch)
            load()
        }
        return r
    }

    /** 改状态。成功先改本地徽标再拉（成功不吐司，徽标当场变就是唯一的反馈）。 */
    fun setStatus(id: Long, status: String): Boolean {
        val patch = InspPatch(status = status)
        val ok = InspirationLocal.update(id, patch) != null
        if (ok) {
            _list.value = InspWriteRules.applying(_list.value, id, patch)
            load()
        }
        return ok
    }

    /** 删除。本机删除找不到回 0（别处已删）—— 交给 afterDelete 说「这条已经不在了」并退页；本机删除没有失败那一档。 */
    fun delete(id: Long): Int? {
        val n = InspirationLocal.delete(id)
        _list.value = _list.value.filter { it.id != id }
        load()
        return n
    }

    /** 全部标签（去重、按出现顺序）。 */
    fun allTags(l: List<Inspiration> = _list.value): List<String> {
        val seen = LinkedHashSet<String>()
        l.forEach { seen.addAll(it.tags) }
        return seen.toList()
    }
}
