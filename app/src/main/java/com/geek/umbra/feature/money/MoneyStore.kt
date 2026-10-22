// 记账的数据源 —— 照抄 iOS Features/Money/MoneyStore.swift。进程级一份：统计 / 流水 / 记一笔 / 分类管理
// 是独立路由，各建各的 store 会各拉各的数据。工具页的记账卡也读这一份（loadedLedger / stats）。
//
// ⚠️ **生活账本是本机账本**（单机化 ②-a）：读写走 MoneyLocal（本机文件 + 后台同步），不登录也能记；
// **经营账本始终在线**（科目与税口径在服务端，要登录）。分流在各方法入口（usesLocal）。
// 经营账本三个请求任何一个挂了整页算 error（分类挂了流水全是 slug 裸奔，统计挂了首页是空的）。
package com.geek.umbra.feature.money

import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraScreenPhase
import com.geek.umbra.net.Ledger
import com.geek.umbra.net.Prefs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.util.UUID

object MoneyStore {
    enum class Phase { Idle, Loading, Error, Ready }

    private val _phase = MutableStateFlow(Phase.Idle)
    val phase: StateFlow<Phase> = _phase
    private val _cats = MutableStateFlow<List<MoneyCatDTO>>(emptyList())
    val cats: StateFlow<List<MoneyCatDTO>> = _cats
    private val _entries = MutableStateFlow<List<MoneyEntryDTO>>(emptyList())
    val entries: StateFlow<List<MoneyEntryDTO>> = _entries
    private val _stats = MutableStateFlow<MoneyStatsDTO?>(null)
    val stats: StateFlow<MoneyStatsDTO?> = _stats
    /** 周期规则。跟主数据一起拉，但**不参与整页成败**。 */
    private val _recurRules = MutableStateFlow<List<MoneyRecurDTO>>(emptyList())
    val recurRules: StateFlow<List<MoneyRecurDTO>> = _recurRules
    /** 色槽池。null = 还没拉到 / 老服务端，换槽面板按兜底常量画。 */
    private val _slotPool = MutableStateFlow<MoneySlotPoolDTO?>(null)
    val slotPool: StateFlow<MoneySlotPoolDTO?> = _slotPool
    /** 每个分类挂着多少东西。空 = 还没拉到 —— 那时**一律当成「有账」**（最坏是把「删除」藏起来）。 */
    private val _catUsage = MutableStateFlow<Map<String, MoneyCatUsageDTO>>(emptyMap())
    val catUsage: StateFlow<Map<String, MoneyCatUsageDTO>> = _catUsage
    /** 「进分类详情后直接把换槽面板打开」的一次性标志（分类管理页那颗「换槽」用）。详情页读完立刻清空。 */
    val slotSwapFor = MutableStateFlow<String?>(null)

    /** 当前看的月。⚠️ 往后不设上限。 */
    private val _ym = MutableStateFlow(MoneyFmt.ymNow())
    val ym: StateFlow<String> = _ym
    /** 用户自己翻到的那个月；null = 没翻过，跟着当月走（跨零点那一条只对它成立）。 */
    private var pinnedYm: String? = null
    val atCurrentMonth: Boolean get() = _ym.value == MoneyFmt.ymNow()
    /** entries / stats **实际是哪个月的**。拿 entries 说事、又要说出月份的地方读这个（导出文件名）。 */
    private val _loadedYm = MutableStateFlow(MoneyFmt.ymNow())
    val loadedYm: StateFlow<String> = _loadedYm
    /** entries / stats **实际是哪个账本的**。工具页卡底要说出「这是哪本账的数」。null = 还没拉成过。 */
    private val _loadedLedger = MutableStateFlow<Ledger?>(null)
    val loadedLedger: StateFlow<Ledger?> = _loadedLedger
    private val reloadGate = MoneyReloadGate()

    /** 当前账本。正本在 Prefs 的 `umbra.money.ledger`（统一出口读它补 ledger=），这里只是镜像。 */
    val ledger: StateFlow<Ledger> = AppGraph.prefs.snapshot.map { Ledger.of(it[Prefs.K.ledger]) }
        .stateIn(AppGraph.scope, SharingStarted.Eagerly, Ledger.of(AppGraph.prefs[Prefs.K.ledger]))
    private val currentLedger: Ledger get() = Ledger.of(AppGraph.prefs[Prefs.K.ledger])

    /** 流水页的筛选。放 store：统计页点某个分类要**带着筛选**跳过去。 */
    val listDir = MutableStateFlow("all") // all / expense / income
    val listCat = MutableStateFlow<String?>(null)
    /** 「本季待补票」那一筛：不是一页，是这张列表的一个筛选态。 */
    val rcptOnly = MutableStateFlow(false)

    /** 待补票那几笔（整季含往季，单独拉）。null = 拿不到：那颗钮整个不出，而不是「0 笔待补票」。 */
    private val _rcptRows = MutableStateFlow<List<MoneyEntryDTO>?>(null)
    val rcptRows: StateFlow<List<MoneyEntryDTO>?> = _rcptRows
    /** 服务端算的总笔数。⚠️ 和 rcptRows.size 不是同一个数（那一筛有 limit）。 */
    private val _rcptTotal = MutableStateFlow(0)
    val rcptTotal: StateFlow<Int> = _rcptTotal
    val rcptPending: Int get() = if (_rcptRows.value == null) 0 else _rcptTotal.value
    val rcptPast: Int
        get() = (_rcptRows.value ?: emptyList()).count {
            ReceiptKit.bucket(it.receipt_state, it.at_ms, it.tz_offset_min, System.currentTimeMillis()) == ReceiptKit.PendingBucket.Past
        }
    val rcptTruncated: Boolean get() = (_rcptRows.value?.size ?: 0) < _rcptTotal.value

    // MARK: 按 id 找一笔（umbra-ios#37）

    /** 不在 entries / rcptRows 里、但被人打开过的那几笔（按它所在的月单独拉回来的）。 */
    private val looked = HashMap<String, MoneyEntryDTO>()
    /** 打开前就知道「它在哪个月」的入口留下的线索。服务端没有 GET /money/entries/{id}。 */
    private val ymHints = HashMap<String, String>()

    /** 按 id 找一笔。**编辑页只走这一处**。 */
    fun entry(id: String): MoneyEntryDTO? =
        _entries.value.firstOrNull { it.id == id } ?: _rcptRows.value?.firstOrNull { it.id == id } ?: looked[id]

    fun hint(id: String, ym: String) { if (ym.isNotEmpty()) ymHints[id] = ym }

    fun keep(e: MoneyEntryDTO) { looked[e.id] = e }

    /** 缓存里找不到时按 id 拉一笔：拿已知的月份拉那一整月再挑出来。不知道月份就回缓存里那份。 */
    suspend fun fetchEntry(id: String): MoneyEntryDTO? {
        if (usesLocal) return MoneyLocal.entry(id) ?: entry(id)
        val m = looked[id]?.ym ?: ymHints[id]
        if (m.isNullOrEmpty()) return entry(id)
        val want = currentLedger
        val r = MoneyApi.fetchEntries(m)
        if (r == null || currentLedger != want) return entry(id)
        val hit = r.items.firstOrNull { it.id == id } ?: return entry(id)
        looked[id] = hit
        return hit
    }

    // MARK: 首屏三态

    private val _loadedOnce = MutableStateFlow(false)
    val loadedOnce: StateFlow<Boolean> = _loadedOnce
    val hasContent: Boolean get() = _stats.value != null

    /** 这一屏该画什么。`.Idle` 也算 loading：中间这一帧画空态会闪一下假的「还没有记录」。 */
    fun screenPhase(offline: Boolean): UmbraScreenPhase = UmbraScreenPhase.resolve(
        hasContent, _phase.value == Phase.Loading || _phase.value == Phase.Idle, _loadedOnce.value, offline,
    )

    private val _catsLoadedOnce = MutableStateFlow(false)
    val catsLoadedOnce: StateFlow<Boolean> = _catsLoadedOnce

    /** 只吃分类的页面用这个：统计挂了但分类好着的时候，分类管理页本身是能用的。 */
    fun catsScreenPhase(offline: Boolean): UmbraScreenPhase = UmbraScreenPhase.resolve(
        _cats.value.isNotEmpty(), _phase.value == Phase.Loading || _phase.value == Phase.Idle, _catsLoadedOnce.value, offline,
    )

    private val _recurLoadedOnce = MutableStateFlow(false)
    val recurLoadedOnce: StateFlow<Boolean> = _recurLoadedOnce
    /** 周期的首次拉取还没有结果。**初值 true**：周期页第一帧就该是骨架。 */
    private val _recurFirstLoading = MutableStateFlow(true)
    val recurFirstLoading: StateFlow<Boolean> = _recurFirstLoading

    fun recurScreenPhase(offline: Boolean): UmbraScreenPhase = UmbraScreenPhase.resolve(
        _recurRules.value.isNotEmpty(), _recurFirstLoading.value, _recurLoadedOnce.value, offline,
    )

    // MARK: 拉取

    /** 首次进页面用：没拉过才拉。离线时例外，进页面就重探一次（不然离线条会一直挂着）。 */
    fun loadIfNeeded() {
        // 界面开关跟着记账数据一起预热：「记一笔」要读两个默认值，而它自己不拉设置。
        MoneySettings.loadIfNeeded()
        if (_phase.value == Phase.Idle || _phase.value == Phase.Error || AppGraph.http.offline.value) launch { reload() }
    }

    /** 在进程作用域里跑一个动作（页面关了也跑完，同 iOS 的 Task）。 */
    fun launch(block: suspend () -> Unit) { AppGraph.scope.launch { block() } }

    /** 这一批分类是**只读科目**吗。⚠️ 用 contains 不用 allSatisfy：空集上必须回 false。 */
    val catsAreReadonly: Boolean get() = MoneyLedgerRules.readonlyBatch(_cats.value.map { it.isReadonly })

    /**
     * 切账本。**全 App 唯一翻那个值的地方**：翻值和「清筛选 / 重拉」必须在一起
     * （分在两处的话第三个调用方只会学到其中一半）。⒜ 筛选要清；⒝ 月份不动。
     */
    suspend fun pickLedger(v: Ledger) {
        if (v == currentLedger) return
        // 同步写：统一出口马上就要拿它补 ledger=，异步写会让下面这一轮还带着旧账本。
        AppGraph.prefs.setNow(Prefs.K.ledger, v.wire)
        listDir.value = "all"
        listCat.value = null
        rcptOnly.value = false
        reload()
    }

    /** 翻到某个月并重拉。null = 回到「跟着当月走」。 */
    suspend fun goMonth(m: String?) { pinnedYm = m; reload() }

    /** 相对当前月挪 n 个月。⚠️ 往后不设上限：周期记账会生成未来的账。 */
    suspend fun shiftMonth(n: Int) = goMonth(MoneyFmt.shiftYm(_ym.value, n))

    /** 生活账本走本机：不登录也能记；经营账本在服务端。 */
    fun isLocal(l: Ledger) = l == Ledger.Personal
    val usesLocal: Boolean get() = isLocal(currentLedger)

    /** 全量重拉。silent = 下拉刷新 / 写操作后的静默刷新，不把页面打回骨架。iOS: MoneyStore.swift:267 */
    suspend fun reload(silent: Boolean = false) {
        val m = pinnedYm ?: MoneyFmt.ymNow()
        _ym.value = m
        // 记下发请求时是哪个账本 + 领一个号：回来时账本换了、或不是最后发出的那一轮，整个作废。
        val want = currentLedger
        val gen = reloadGate.begin()
        if (!silent) _phase.value = Phase.Loading
        val local = isLocal(want)
        // 经营账本未登录：不发请求（会被本地请求闸拦下），页面画「要登录」。
        if (!local && AppGraph.guard.locked.value) { if (!silent) _phase.value = Phase.Error; return }
        val res = coroutineScope {
            val c = async { if (local) MoneyLocal.liveCats(true) else MoneyApi.fetchCats(true) }
            val e = async { if (local) MoneyLocal.list(ym = m) else MoneyApi.fetchEntries(m) }
            val s = async { if (local) MoneyLocal.stats(m, 12) else MoneyApi.fetchStats(m, 12) }
            val r = async { if (local) { MoneyLocal.scanDue(); MoneyLocal.listRules() } else MoneyApi.fetchRecur() }
            val sp = async { if (local) MoneyLocal.slotPool() else MoneyApi.fetchSlotPool() }
            val cu = async { if (local) MoneyLocal.usage() else MoneyApi.fetchCatUsage() }
            // 本季待补票跟着这一轮一起拉（生活账本的行根本没有票据状态，不问）。
            val rp = async {
                if (local) null else MoneyApi.receiptPending(ReceiptKit.pendingThrough(System.currentTimeMillis(), MoneyFmt.tzOffsetMin))
            }
            Loaded(c.await(), e.await(), s.await(), r.await(), sp.await(), cu.await(), rp.await())
        }
        if (!reloadGate.isCurrent(gen) || currentLedger != want) return
        res.slotPool?.let { _slotPool.value = it }
        // 拿不到就置 null（那颗钮不出），不是留着上一轮那份。
        _rcptRows.value = res.rcpt?.items
        _rcptTotal.value = res.rcpt?.totals?.count ?: 0
        res.usage?.let { _catUsage.value = it }
        // 分类和周期在整页判定之前落地，不跟整页成败连坐。
        res.cats?.let { _cats.value = it; _catsLoadedOnce.value = true }
        res.recur?.let { _recurRules.value = it; _recurLoadedOnce.value = true; _recurFirstLoading.value = false }
        val re = res.entries
        val rs = res.stats
        if (re == null || rs == null || res.cats == null) {
            // 静默刷新失败时不动手上的数据。
            if (!silent || _phase.value != Phase.Ready) _phase.value = Phase.Error
            return
        }
        _entries.value = re.items
        _stats.value = rs
        _loadedYm.value = m
        _loadedLedger.value = want
        _phase.value = Phase.Ready
        _loadedOnce.value = true
        // 本机账本：页面进来顺手同步一轮（登着才会真的出网；sync 自己去重），拉到新东西再静默重画。
        if (local && !silent) launch { syncLocalThenRefresh() }
    }

    private data class Loaded(
        val cats: List<MoneyCatDTO>?, val entries: MoneyEntriesDTO?, val stats: MoneyStatsDTO?, val recur: List<MoneyRecurDTO>?,
        val slotPool: MoneySlotPoolDTO?, val usage: Map<String, MoneyCatUsageDTO>?, val rcpt: MoneyEntriesDTO?,
    )

    /** 同步一轮，拉到了新东西就静默重画。会话守卫的「开」也走它。 */
    suspend fun syncLocalThenRefresh() {
        val before = MoneyLocal.entries.value to MoneyLocal.cats.value
        MoneyLocal.sync()
        if (before.first != MoneyLocal.entries.value || before.second != MoneyLocal.cats.value) {
            if (usesLocal) reload(silent = true)
        }
    }

    /** 附件在服务端挂 / 删之后，本机这笔的附件清单从服务端拿一次对齐。 */
    private suspend fun refreshAttsFromServer(entryId: String) {
        val e = MoneyLocal.entry(entryId) ?: return
        val r = MoneyApi.fetchEntries(e.ym) ?: return
        val hit = r.items.firstOrNull { it.id == entryId } ?: return
        MoneyLocal.setAtts(entryId, hit.atts)
    }

    // MARK: 查询（slug → 展示属性）

    fun enabledCats(direction: String): List<MoneyCatDTO> = _cats.value.filter { it.direction == direction && it.enabled }

    /** 历史流水可能指向停用/未知分类，查不到时名字回退成 slug 本身，别让那行流水消失。 */
    fun catName(slug: String): String = _cats.value.firstOrNull { it.slug == slug }?.name ?: slug

    fun catSlot(slug: String): Int = _cats.value.firstOrNull { it.slug == slug }?.slot ?: 0

    /** 图标 path：存储的语义名优先，slug 兜底。 */
    fun catArt(slug: String): String = MoneyCatArt.icon(slug, _cats.value.firstOrNull { it.slug == slug }?.icon)

    /** 「最近用过」：同方向按时间新→旧去重取 3 个；该方向分类总数 ≤ 3 时返回空（和网格完全重复）。 */
    fun recentCats(direction: String): List<String> {
        val cats = enabledCats(direction)
        if (cats.size <= 3) return emptyList()
        val out = ArrayList<String>()
        val valid = cats.map { it.slug }.toSet()
        for (e in _entries.value) {
            if (e.direction != direction || e.cat !in valid || e.cat in out) continue
            out.add(e.cat)
            if (out.size >= 3) break
        }
        for (c in cats) { if (out.size >= 3) break; if (c.slug !in out) out.add(c.slug) }
        return out
    }

    // MARK: 写操作

    /**
     * 记一笔 / 改一笔。id 为 null 时新建（客户端生成 uuid）。成功回这笔账的 id；失败回分档（MoneyPutFail）。
     * ⚠️ biz 只在经营账本那一路传：生活账本传 null，那七个字段在上行 JSON 里整个不出现。
     */
    suspend fun save(
        id: String?, cents: Long, direction: String, cat: String, sub: String, merchant: String, atMs: Long, src: String?,
        ruleId: String, batchId: String, orderNo: String, biz: BizPayload? = null,
    ): Result<String> {
        val dto = MoneyEntryDTO(
            id = id ?: UUID.randomUUID().toString().lowercase(),
            cents = cents, direction = direction, cat = cat, sub = sub, merchant = merchant,
            at_ms = atMs, tz_offset_min = MoneyFmt.tzOffsetMin,
            ym = "", // 服务端按 at_ms + tz_offset_min 自己算（本机账本在 validate 里算）
            src = src ?: "manual", rule_id = ruleId, batch_id = batchId, order_no = orderNo,
            updated_at_ms = System.currentTimeMillis(), deleted = false,
            // 手动记/改一笔不带附件：null 在上行时被整个省掉，服务端不会动这笔已有的附件。
            atts = null,
            invoice_no = biz?.invoiceNo, invoice_kind = biz?.invoiceKind, receipt_state = biz?.receipt?.wire,
            tax_rate_bp = biz?.rateBp,
            // ⚠️ 支出侧不传 net_cents —— 服务端对支出侧带这个值的请求直接 400。
            net_cents = biz?.let { if (MoneyBiz.showsNet(direction)) it.netCents else null },
            deduct_tier = biz?.tier?.wire,
            // is_asset 由科目推，端上不传。
            is_asset = null,
        )
        if (usesLocal) {
            // 本机账本：落本机就算记上了（登着的话 3 秒后推上去）。校验不过按「没记上」报，内容留在表单里。
            if (runCatching { MoneyLocal.save(dto) }.isFailure) return Result.failure(MoneyApi.PutFailure(MoneyPutFail.Unreachable))
            reload(silent = true)
            return Result.success(dto.id)
        }
        val r = MoneyApi.putEntry(dto)
        if (r.isSuccess) { reload(silent = true); return Result.success(dto.id) }
        val why = (r.exceptionOrNull() as? MoneyApi.PutFailure)?.why ?: MoneyPutFail.Unreachable
        // 这一笔已不在当前账本：静默重拉让它从这边消失，不然人再点进去改，撞的是同一个 409。
        if (why is MoneyPutFail.OtherLedger) reload(silent = true)
        return Result.failure(MoneyApi.PutFailure(why))
    }

    /** 把一笔账挪到另一个账本。从本机账本挪出去的，本机还没推上去的先推一次，挪成了把本机那份去掉。 */
    suspend fun moveEntry(id: String, to: Ledger): MoneyResult<MoneyEntryDTO> {
        val fromLocal = usesLocal
        if (fromLocal && MoneyLocal.entry(id)?.dirty == true) MoneyLocal.sync()
        return when (val r = MoneyApi.moveEntry(id, to)) {
            is MoneyResult.Ok -> {
                if (fromLocal) MoneyLocal.forget(id)
                reload(silent = true)
                MoneyResult.Ok(r.value.entry)
            }
            is MoneyResult.Failed -> r
        }
    }

    /** 给一笔账挂上已上传的文件（本机 id 挂本机；服务端 id 走接口）。 */
    suspend fun addAtt(entryId: String, fileId: String, label: String, kind: String = ""): Boolean {
        if (MoneyLocalRules.isLocalFileId(fileId)) {
            if (!MoneyLocal.attachLocal(entryId, fileId, label)) return false
            reload(silent = true)
            return true
        }
        if (!MoneyApi.addAtt(entryId, fileId, label, kind)) return false
        if (usesLocal) refreshAttsFromServer(entryId)
        reload(silent = true)
        return true
    }

    /** 删一笔 = 移进回收站（30 天内可恢复）。本机账本：移除 + 墓碑。 */
    suspend fun delete(id: String): Boolean {
        if (usesLocal) { MoneyLocal.remove(listOf(id)); reload(silent = true); return true }
        if (!MoneyApi.deleteEntries(listOf(id))) return false
        reload(silent = true)
        return true
    }

    suspend fun updateCat(slug: String, name: String? = null, enabled: Boolean? = null, icon: String? = null, slot: Int? = null): Boolean =
        patchCat(slug, name, enabled, icon, slot) != null

    /** 同一件事，但把响应交出去（换槽与重新启用要读 slot_taken_by / slot_was 才说得出那句话）。 */
    suspend fun patchCat(slug: String, name: String? = null, enabled: Boolean? = null, icon: String? = null, slot: Int? = null): MoneyCatDTO? {
        if (usesLocal) {
            val c = MoneyLocal.updateCat(slug, name, enabled, icon, slot) ?: return null
            reload(silent = true)
            return c
        }
        val c = MoneyApi.updateCat(slug, name, enabled, icon, slot) ?: return null
        reload(silent = true)
        return c
    }

    /** 把 slug 并进 into。**不能撤销**（确认在界面上做完了）。成功回 moved，失败回服务端那句拒绝的话。 */
    suspend fun mergeCat(slug: String, into: String): MoneyResult<MoneyCatUsageDTO> {
        if (usesLocal) {
            val r = MoneyLocal.mergeCat(slug, into)
            if (r is MoneyResult.Ok) reload(silent = true)
            return r
        }
        return when (val r = MoneyApi.mergeCat(slug, into)) {
            is MoneyResult.Ok -> { _cats.value = r.value.cats; reload(silent = true); MoneyResult.Ok(r.value.moved) }
            is MoneyResult.Failed -> r
        }
    }

    /** 删掉一个分类。回 null = 删了；否则是那句话。 */
    suspend fun deleteCat(slug: String): String? {
        if (usesLocal) {
            MoneyLocal.deleteCat(slug)?.let { return it }
            reload(silent = true)
            return null
        }
        return when (val r = MoneyApi.deleteCat(slug)) {
            is MoneyResult.Ok -> { _cats.value = r.value; reload(silent = true); null }
            is MoneyResult.Failed -> r.detail.ifEmpty { "没删掉，检查网络后再试" }
        }
    }

    /** 拖拽排序。**乐观**：先按新顺序把本地列表排好再发请求；失败用服务端真值收回去。 */
    suspend fun reorderCats(direction: String, slugs: List<String>): Boolean {
        val before = _cats.value
        _cats.value = MoneyCatOrder.apply(_cats.value, direction, slugs)
        if (usesLocal) {
            MoneyLocal.reorderCats(direction, slugs)
            _cats.value = MoneyLocal.liveCats(true)
            return true
        }
        val fresh = MoneyApi.reorderCats(direction, slugs) ?: run { _cats.value = before; return false }
        _cats.value = fresh
        return true
    }

    /** 新增分类。成功返回新分类的 slug（调用方常要立刻选中它）。 */
    suspend fun createCat(name: String, direction: String): String? {
        if (usesLocal) {
            val c = MoneyLocal.createCat(name, direction) ?: return null
            reload(silent = true)
            return c.slug
        }
        val c = MoneyApi.createCat(name, direction) ?: return null
        reload(silent = true)
        return c.slug
    }

    /** 本机子类清单改一处：读当前 → 变换 → 整份写回。回 null = 不改。 */
    private suspend fun editLocalSubs(cat: String, fn: (List<String>) -> List<String>?): Boolean {
        val cur = MoneyLocal.subs(cat).map { it.label }
        val next = fn(cur) ?: return false
        if (!MoneyLocal.setSubs(cat, next)) return false
        reload(silent = true)
        return true
    }

    suspend fun addSub(cat: String, label: String): Boolean {
        if (usesLocal) {
            val t = label.trim()
            return editLocalSubs(cat) { if (t.isEmpty() || t in it) null else it + t }
        }
        if (!MoneyApi.addSub(cat, label)) return false
        reload(silent = true)
        return true
    }

    suspend fun renameSub(cat: String, old: String, new: String): Boolean {
        if (usesLocal) {
            val t = new.trim()
            return editLocalSubs(cat) { l -> if (old !in l || t.isEmpty() || (t != old && t in l)) null else l.map { if (it == old) t else it } }
        }
        if (!MoneyApi.renameSub(cat, old, new)) return false
        reload(silent = true)
        return true
    }

    suspend fun deleteSub(cat: String, label: String): Boolean {
        if (usesLocal) return editLocalSubs(cat) { l -> l.filter { it != label } }
        if (!MoneyApi.deleteSub(cat, label)) return false
        reload(silent = true)
        return true
    }

    /** 子类 + 「N 笔在用」：本机账本本机数，经营账本问服务端。 */
    suspend fun subsUsed(cat: String): List<MoneySubUsedDTO>? = if (usesLocal) MoneyLocal.subs(cat) else MoneyApi.fetchSubsUsed(cat)

    /** 摘一张附件（原图摘不掉，服务端也会拒）。 */
    suspend fun deleteAtt(entryId: String, fileId: String): Boolean {
        if (MoneyLocalRules.isLocalFileId(fileId)) {
            if (!MoneyLocal.detachLocal(entryId, fileId)) return false
            reload(silent = true)
            return true
        }
        if (!MoneyApi.deleteAtt(entryId, fileId)) return false
        if (usesLocal) refreshAttsFromServer(entryId)
        reload(silent = true)
        return true
    }

    // MARK: 周期记账

    /** 单独刷新规则列表（进周期页时）。整页成败不看它。 */
    suspend fun reloadRecur() {
        try {
            if (usesLocal) {
                MoneyLocal.scanDue()
                _recurRules.value = MoneyLocal.listRules()
                _recurLoadedOnce.value = true
                return
            }
            MoneyApi.fetchRecur()?.let { _recurRules.value = it; _recurLoadedOnce.value = true }
        } finally {
            // 首次那一轮无论成败都要落幕，否则拉失败时周期页会一直停在骨架上转。
            _recurFirstLoading.value = false
        }
    }

    suspend fun retry() = reload()

    /** 周期页那颗「重试」：先把首屏标志举起来，不然点下去界面完全不动。 */
    suspend fun retryRecur() { _recurFirstLoading.value = true; reloadRecur() }

    /** 建/改一条规则。改动只影响以后的。body 是服务端那张表（本机走同一份字段，不另开一套参数名）。 */
    suspend fun saveRecur(id: String?, body: JsonObject): Boolean {
        val rid = id ?: UUID.randomUUID().toString().lowercase()
        if (usesLocal) {
            fun s(k: String, d: String) = (body[k] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: d
            fun i(k: String, d: Int) = (body[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: d
            fun l(k: String, d: Long) = (body[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: d
            val r = MoneyRecurDTO(
                id = rid, name = s("name", ""), cents = l("cents", 0), direction = s("direction", "expense"),
                cat = s("cat", ""), sub = s("sub", ""), merchant = s("merchant", ""), cycle = s("cycle", "month"),
                every_n = i("every_n", 1), week_day = i("week_day", 0), first_date = s("first_date", ""),
                time_hhmm = s("time_hhmm", "09:00"), tz_offset_min = i("tz_offset_min", 0), end_kind = s("end_kind", "never"),
                end_date = s("end_date", ""), dirty = true,
            )
            if (MoneyLocal.saveRule(r) == null) return false
            reloadRecur()
            return true
        }
        if (MoneyApi.putRecur(rid, body) == null) return false
        reloadRecur()
        return true
    }

    suspend fun pauseRecur(id: String, paused: Boolean): Boolean {
        val ok = if (usesLocal) MoneyLocal.pauseRule(id, paused) else MoneyApi.pauseRecur(id, paused)
        if (!ok) return false
        reloadRecur()
        return true
    }

    /** 删规则不删已生成的流水（那些是真花过的钱）。 */
    suspend fun deleteRecur(id: String): Boolean {
        val ok = if (usesLocal) MoneyLocal.deleteRule(id) else MoneyApi.deleteRecur(id)
        if (!ok) return false
        reloadRecur()
        return true
    }

    /** 流水行上的「周期」徽章要跳的规则。可能已删 —— 返回 null 时调用方给一句人话。 */
    fun recurRule(id: String): MoneyRecurDTO? = _recurRules.value.firstOrNull { it.id == id }

    // MARK: 给工具页（记账卡）

    /** 当月支出合计（拉到过才有；null = 没拉到 / 不是当月）。工具页卡底那句要「哪个账本、哪个月」一起说。 */
    val toolExpense: Long? get() = _stats.value?.takeIf { _loadedOnce.value }?.expense
}
