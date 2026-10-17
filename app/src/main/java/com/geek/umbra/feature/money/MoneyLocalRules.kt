// 记账本机版的纯判据（单机化 ②-a，2026-10-10）—— 照抄 iOS Features/Money/MoneyLocalRules.swift。纯 Kotlin。
// 月份归属、校验、列表合计、统计、合并、内置分类种子、色槽、分类合并 / 同步回包落地、周期规则、本机附件 id、换账号。
//
// ⚠️ 每一条都和服务端 money.py / money_recur.py 以及 PC electron/core/money/rules.ts 同一口径（拍板 H：统计本机算、口径两端同源）。
// 改一边要改另几边；用例逐条和 iOS MoneyLocalRulesTests / PC tests/moneyLocal.test.ts 对得上。
//
// 日期运算一律 java.time 的 UTC LocalDate（规则里的日期是「本地墙上日期」，与设备时区无关）；
// Swift 的 `(Double(idx)/12).rounded(.down)` 是向下取整（负数也对），这里用 Math.floorDiv。
package com.geek.umbra.feature.money

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Locale

/** iOS: MoneyLocalRules.swift:19 */
object MoneyLocalRules {
    /** 彩色槽池（8 号是中性灰 --c8，不在池里）。 */
    val colorSlots: List<Int> = listOf(1, 2, 3, 4, 5, 6, 7, 9, 10)
    const val NEUTRAL_SLOT = 0
    /** 兜底分类：不可停用、不可合并掉。 */
    val lockedSlugs: Set<String> = setOf("other", "other_in")
    val directions: Set<String> = setOf("expense", "income")
    val sources: Set<String> = setOf("manual", "shot", "import", "chat", "recur")

    /** iOS `.whitespaces`（Zs 类 + 制表符，不含换行）口径的去两头空白。 */
    private fun trimWs(s: String): String =
        s.trim { it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }

    // ── 月份（= 服务端 month_of / shift_month）──

    /** 一笔账归哪个月：按它自己的时区偏移算本地时间，不按这台设备的时区（8 月 31 日深夜在 UTC 已经是 9 月）。 */
    fun monthOf(atMs: Long, tzOffsetMin: Int): String {
        val d = java.time.Instant.ofEpochMilli(atMs + tzOffsetMin.toLong() * 60_000).atOffset(ZoneOffset.UTC)
        return String.format(Locale.ROOT, "%04d-%02d", d.year, d.monthValue)
    }

    /** "2026-01" 挪 delta 个月。格式不对原样返回。iOS 的 split 丢空段、compactMap 丢非数字段，这里同口径。 */
    fun shiftMonth(ym: String, delta: Int): String {
        val parts = ym.split("-").filter { it.isNotEmpty() }.mapNotNull { it.toIntOrNull() }
        if (parts.size != 2) return ym
        val idx = parts[0] * 12 + (parts[1] - 1) + delta
        val y = Math.floorDiv(idx, 12)
        val m = idx - y * 12 + 1
        return String.format(Locale.ROOT, "%04d-%02d", y, m)
    }

    // ── 校验（= 服务端 _validate 那组拒绝）──

    /** 一笔流水不合法的原因。 */
    enum class Invalid { Id, Cents, Direction, Src, AtMs, Tz, Cat }

    /** validate 抛的异常（iOS 是 throws Invalid）。 */
    class InvalidEntry(val why: Invalid) : Exception(why.name)

    /** 把一笔规整成入库形状（算 ym、去空白）；非法值抛 InvalidEntry。updated_at_ms / dirty 由 commit 盖。 */
    fun validate(e: MoneyEntryDTO): MoneyEntryDTO {
        val id = trimWs(e.id)
        if (id.isEmpty()) throw InvalidEntry(Invalid.Id)
        if (e.cents < 0) throw InvalidEntry(Invalid.Cents)
        if (e.direction !in directions) throw InvalidEntry(Invalid.Direction)
        val src = e.src.ifEmpty { "manual" }
        if (src !in sources) throw InvalidEntry(Invalid.Src)
        if (e.at_ms <= 0) throw InvalidEntry(Invalid.AtMs)
        if (e.tz_offset_min !in -14 * 60..14 * 60) throw InvalidEntry(Invalid.Tz)
        val cat = trimWs(e.cat)
        if (cat.isEmpty()) throw InvalidEntry(Invalid.Cat)
        return e.copy(
            id = id, src = src, cat = cat,
            sub = trimWs(e.sub), merchant = trimWs(e.merchant),
            ym = monthOf(e.at_ms, e.tz_offset_min), deleted = false,
        )
    }

    // ── 列表与合计（= 服务端 list_entries：合计按整个筛选结果算）──

    /** 列表筛选条件。空串的 direction / cat / batchId 等同不筛。 */
    data class Filter(
        val ym: String? = null,
        val direction: String? = null,
        val cat: String? = null,
        val keyword: String = "",
        val batchId: String? = null,
        val limit: Int = 500,
        val offset: Int = 0,
    )

    /** 筛 → 合计（整个筛选结果）→ 按 at_ms 倒序、id 正序 → 分页。 */
    fun list(all: List<MoneyEntryDTO>, f: Filter = Filter()): MoneyEntriesDTO {
        val kw = trimWs(f.keyword)
        val hit = all.filter { e ->
            (f.ym == null || e.ym == f.ym) &&
                (f.direction.isNullOrEmpty() || e.direction == f.direction) &&
                (f.cat.isNullOrEmpty() || e.cat == f.cat) &&
                (f.batchId.isNullOrEmpty() || e.batch_id == f.batchId) &&
                (kw.isEmpty() || e.merchant.contains(kw) || e.sub.contains(kw))
        }
        var expense = 0L
        var income = 0L
        for (e in hit) if (e.direction == "expense") expense += e.cents else income += e.cents
        val sorted = hit.sortedWith { a, b -> if (a.at_ms != b.at_ms) b.at_ms.compareTo(a.at_ms) else a.id.compareTo(b.id) }
        val lo = minOf(maxOf(0, f.offset), sorted.size)
        val hi = minOf(lo + maxOf(1, f.limit), sorted.size)
        return MoneyEntriesDTO(sorted.subList(lo, hi).toList(), MoneyTotalsDTO(hit.size, expense, income))
    }

    // ── 统计（= 服务端 stats 生活账本那一半；经营的三个税口径数不在端上算）──

    /** 当月收支结余、支出分类降序、趋势（老→新含当月）、上月支出（没有记录 = null，不是 0）。 */
    fun stats(all: List<MoneyEntryDTO>, ym: String, trendMonths: Int = 6): MoneyStatsDTO {
        var expense = 0L
        var income = 0L
        val byCat = LinkedHashMap<String, Pair<Long, Int>>()
        for (e in all) {
            if (e.ym != ym) continue
            if (e.direction == "expense") {
                expense += e.cents
                val c = byCat[e.cat] ?: (0L to 0)
                byCat[e.cat] = (c.first + e.cents) to (c.second + 1)
            } else income += e.cents
        }
        val months = (0 until maxOf(1, trendMonths)).reversed().map { shiftMonth(ym, -it) }
        val trend = HashMap<String, Long>()
        for (e in all) if (e.direction == "expense" && e.ym in months) trend[e.ym] = (trend[e.ym] ?: 0L) + e.cents
        val prev = shiftMonth(ym, -1)
        // 「上月没有记录」和「上月花了 0」是两件事：只有收入没花支出时趋势里查不到它，但它不是「没有记录」。
        val hasPrev = trend[prev] != null || all.any { it.ym == prev }
        return MoneyStatsDTO(
            ym = ym, expense = expense, income = income, balance = income - expense,
            by_cat = byCat.map { MoneyCatStatDTO(it.key, it.value.first, it.value.second) }
                .sortedWith { a, b -> if (a.cents != b.cents) b.cents.compareTo(a.cents) else a.cat.compareTo(b.cat) },
            prev_ym = prev, prev_expense = if (hasPrev) (trend[prev] ?: 0L) else null,
            trend = months.map { MoneyTrendDTO(it, trend[it] ?: 0L) },
            cost = null, profit = null, not_cost = null,
        )
    }

    // ── 合并（= 提醒那套 mergeRemote / applyPutResult；PC rules.ts 同款）──

    /**
     * 拉回来的流水并进本机：本机 dirty 且更新留本机；本机删得更新不加回；远端墓碑删本机（本机改得比那边删得新则留）。
     * 顺序：本机原顺序，新来的追加在后。
     */
    fun mergeEntries(
        local: List<MoneyEntryDTO>, items: List<MoneyEntryDTO>,
        tombs: List<MoneyTomb>, pendingTombs: List<MoneyTomb> = emptyList(),
    ): List<MoneyEntryDTO> {
        val byId = HashMap<String, MoneyEntryDTO>()
        val order = mutableListOf<String>()
        for (r in local) { if (r.id !in byId) order.add(r.id); byId[r.id] = r }
        val myDeletes = HashMap<String, Long>()
        for (t in pendingTombs) myDeletes[t.id] = maxOf(myDeletes[t.id] ?: Long.MIN_VALUE, t.deletedAtMs)
        for (s in items) {
            val mine = byId[s.id]
            if (mine != null && mine.dirty == true && mine.updated_at_ms > s.updated_at_ms) continue   // 本机改得更新：留本机
            val delAt = myDeletes[s.id]
            if (delAt != null && delAt > s.updated_at_ms) continue                                     // 本机删得更新：不加回
            if (s.id !in byId) order.add(s.id)
            byId[s.id] = s.copy(dirty = null)
        }
        for (t in tombs) {
            val mine = byId[t.id]
            if (mine != null && mine.dirty == true && mine.updated_at_ms > t.deletedAtMs) continue     // 本机改得比那边删得新：留
            byId.remove(t.id)
        }
        return order.mapNotNull { byId[it] }
    }

    /** 一笔 PUT 回来的结果。 */
    sealed interface PutOutcome {
        /** 写上了。 */
        data object Written : PutOutcome
        /** 被 LWW 拒：库里那份更新，用它。 */
        data class Rejected(val server: MoneyEntryDTO) : PutOutcome
        /** 409 other_ledger：这一笔已在另一个账本，本机这份去掉。 */
        data object OtherLedger : PutOutcome
    }

    /** 一笔 PUT 回来之后本机这条怎么落：在途又改过不动；写上了清 dirty；被拒用服务端那份；409 other_ledger 去掉。 */
    fun applyPut(items: List<MoneyEntryDTO>, sent: MoneyEntryDTO, outcome: PutOutcome): List<MoneyEntryDTO> {
        val i = items.indexOfFirst { it.id == sent.id }
        if (i < 0 || items[i].updated_at_ms != sent.updated_at_ms) return items
        val next = items.toMutableList()
        when (outcome) {
            PutOutcome.Written -> next[i] = next[i].copy(dirty = null)
            is PutOutcome.Rejected -> next[i] = outcome.server.copy(dirty = null)
            PutOutcome.OtherLedger -> next.removeAt(i)
        }
        return next
    }

    // ── 分类 ──

    /** 与服务端 _SEED_EXPENSE / _SEED_INCOME / _SEED_SUBS 逐字相同。updated_at_ms = 0：同步时服务端那份一定更新，端上照收。 */
    fun seedCats(): List<MoneyCatDTO> {
        val exp = listOf(
            Triple("food", "餐饮", 1), Triple("transport", "交通", 2), Triple("shopping", "购物", 3), Triple("housing", "居住", 9),
            Triple("daily", "日用", 4), Triple("fun", "娱乐", 5), Triple("medical", "医疗", 6), Triple("study", "学习", 7),
            Triple("social", "人情", 10), Triple("other", "其他", 0),
        )
        val inc = listOf(
            Triple("salary", "工资", 1), Triple("bonus", "奖金", 2), Triple("parttime", "兼职", 3), Triple("invest", "理财", 4),
            Triple("reimburse", "报销", 5), Triple("redpack", "红包", 6), Triple("refund", "退款", 7), Triple("other_in", "其他", 0),
        )
        val subs = mapOf(
            "food" to listOf("早餐", "午餐", "晚餐", "外卖", "咖啡奶茶", "请客"),
            "transport" to listOf("打车", "公交地铁", "加油", "停车", "火车高铁", "机票"),
            "shopping" to listOf("服饰", "数码", "家居", "美妆"),
            "housing" to listOf("房租房贷", "水电燃气", "物业", "宽带"),
            "daily" to listOf("生活用品", "母婴", "宠物"),
            "fun" to listOf("订阅会员", "游戏", "观影演出", "旅行"),
            "medical" to listOf("门诊", "药品", "体检", "保险"),
            "study" to listOf("书籍", "课程", "软件工具"),
            "social" to listOf("红包", "礼物", "请客送礼"),
            "salary" to listOf("月薪", "年终"),
            "reimburse" to listOf("差旅", "办公"),
        )
        fun mk(rows: List<Triple<String, String, Int>>, direction: String) = rows.mapIndexed { seq, r ->
            MoneyCatDTO(
                slug = r.first, name = r.second, direction = direction, slot = r.third, seq = seq, enabled = true,
                locked = r.first in lockedSlugs, icon = "", subs = subs[r.first] ?: emptyList(),
                merged_into = "", updated_at_ms = 0, subs_updated_at_ms = 0, dirty = false,
            )
        }
        return mk(exp, "expense") + mk(inc, "income")
    }

    /** merged_into 非空 = 墓碑。 */
    fun isMerged(c: MoneyCatDTO): Boolean = !c.merged_into.isNullOrEmpty()

    /** 列表视角：活着的；includeDisabled 才带停用的。顺序 direction → seq。 */
    fun live(cats: List<MoneyCatDTO>, includeDisabled: Boolean = false): List<MoneyCatDTO> =
        cats.filter { !isMerged(it) && (includeDisabled || it.enabled) }
            .sortedWith { a, b -> if (a.direction != b.direction) a.direction.compareTo(b.direction) else a.seq.compareTo(b.seq) }

    /** 这个方向已被占的彩色槽（停用 / 墓碑 / exclude 那一个不算占）。 */
    fun takenSlots(cats: List<MoneyCatDTO>, direction: String, exclude: String? = null): Set<Int> =
        cats.filter { it.direction == direction && it.enabled && !isMerged(it) && it.slug != exclude && it.slot in colorSlots }
            .map { it.slot }.toSet()

    /** 这个方向池里最小的空号；池空了回中性槽（与服务端 _free_slot 同一规则）。 */
    fun freeSlot(cats: List<MoneyCatDTO>, direction: String, exclude: String? = null): Int {
        val taken = takenSlots(cats, direction, exclude)
        return colorSlots.firstOrNull { it !in taken } ?: NEUTRAL_SLOT
    }

    /** 本方向的兜底分类。 */
    fun fallbackSlug(direction: String): String = if (direction == "income") "other_in" else "other"

    /** 端上生成的 slug：u<ms><两位随机>，与服务端 u<ms> 同族、跨设备不撞。 */
    fun newSlug(nowMs: Long, rand: Int): String =
        "u$nowMs" + String.format(Locale.ROOT, "%02d", Math.abs(rand % 100))

    /** 一个分类挂着多少东西（本机：trashed / rules 记 0，与 iOS 同）。 */
    fun usage(slug: String, cats: List<MoneyCatDTO>, entries: List<MoneyEntryDTO>): MoneyCatUsageDTO =
        MoneyCatUsageDTO(
            entries = entries.count { it.cat == slug }, trashed = 0,
            subs = cats.firstOrNull { it.slug == slug }?.subList?.size ?: 0, rules = 0,
        )

    /** 合并被拒的原因（iOS 的 self_ 在这里叫 Self）。 */
    enum class MergeRefusal { Both, Self, MissingSrc, MissingDst, SrcMerged, DstMerged, Locked, Direction }

    /** mergeCat 抛的异常。 */
    class MergeRefused(val why: MergeRefusal) : Exception(why.name)

    /** 一次本机合并的结果。 */
    data class MergeResult(val cats: List<MoneyCatDTO>, val entries: List<MoneyEntryDTO>, val moved: MoneyCatUsageDTO)

    /** 本机合并：账改挂（标 dirty）、子类同名合并、源落墓碑。拒绝同服务端。iOS: MoneyLocalRules.swift:228 */
    fun mergeCat(cats: List<MoneyCatDTO>, entries: List<MoneyEntryDTO>, slug: String, into: String, nowMs: Long): MergeResult {
        if (slug.isEmpty() || into.isEmpty()) throw MergeRefused(MergeRefusal.Both)
        if (slug == into) throw MergeRefused(MergeRefusal.Self)
        val src = cats.firstOrNull { it.slug == slug } ?: throw MergeRefused(MergeRefusal.MissingSrc)
        val dst = cats.firstOrNull { it.slug == into } ?: throw MergeRefused(MergeRefusal.MissingDst)
        if (isMerged(src)) throw MergeRefused(MergeRefusal.SrcMerged)
        if (isMerged(dst)) throw MergeRefused(MergeRefusal.DstMerged)
        if (src.locked) throw MergeRefused(MergeRefusal.Locked)
        if (src.direction != dst.direction) throw MergeRefused(MergeRefusal.Direction)
        val moved = usage(slug, cats, entries)
        val nextEntries = entries.map { e ->
            if (e.cat != slug) e else e.copy(cat = into, updated_at_ms = nowMs, dirty = true)
        }
        val mergedSubs = dst.subList + src.subList.filter { it !in dst.subList }
        val nextCats = cats.map { c ->
            var m = c
            if (c.slug == into) m = m.copy(subs = mergedSubs, subs_updated_at_ms = nowMs, dirty = true)
            if (c.slug == slug) m = m.copy(enabled = false, merged_into = into, updated_at_ms = nowMs, subs = emptyList(), dirty = true)
            m
        }
        return MergeResult(nextCats, nextEntries, moved)
    }

    /** 合并被拒时给人看的那句。null（不是拒绝、别的错）→「没合并成」。iOS: MoneyLocal.swift:346 */
    fun refusalText(why: MergeRefusal?): String = when (why) {
        MergeRefusal.Both -> "要合并的分类和目标分类都得给"
        MergeRefusal.Self -> "不能把一个分类并进它自己"
        MergeRefusal.MissingSrc -> "要合并的分类不存在"
        MergeRefusal.MissingDst -> "目标分类不存在"
        MergeRefusal.SrcMerged, MergeRefusal.DstMerged -> "那个分类已经被合并掉了"
        MergeRefusal.Locked -> "兜底分类不能合并掉"
        MergeRefusal.Direction -> "收入分类和支出分类不能互相合并"
        null -> "没合并成"
    }

    /**
     * 同步回包落本机（照服务端 sync_categories 的回包）：整行 / 子类各按戳取新的那份；merged 是服务端那边的墓碑 ——
     * 本机还活着的就落墓碑，并把本机指向它的账改挂（那些账标 dirty 推上去）。iOS: MoneyLocalRules.swift:255
     */
    fun applyCatSync(
        local: List<MoneyCatDTO>, server: List<MoneyCatDTO>, merged: List<MoneyCatMergedDTO>,
        entries: List<MoneyEntryDTO>, nowMs: Long,
    ): Pair<List<MoneyCatDTO>, List<MoneyEntryDTO>> {
        val bySlug = HashMap<String, MoneyCatDTO>()
        val order = mutableListOf<String>()
        for (c in local) { if (c.slug !in bySlug) order.add(c.slug); bySlug[c.slug] = c }
        for (s in server) {
            val sUp = s.updated_at_ms ?: 0
            val sSubsUp = s.subs_updated_at_ms ?: 0
            var row = bySlug[s.slug]
            if (row == null) {
                order.add(s.slug)
                bySlug[s.slug] = s.copy(merged_into = "", dirty = false)
                continue
            }
            val mineUp = row.updated_at_ms ?: 0
            val mineSubsUp = row.subs_updated_at_ms ?: 0
            if (sUp > mineUp) {
                row = row.copy(name = s.name, slot = s.slot, seq = s.seq, enabled = s.enabled, icon = s.icon, merged_into = "", updated_at_ms = sUp)
            }
            row = row.copy(locked = s.locked)
            if (sSubsUp > mineSubsUp) row = row.copy(subs = s.subList, subs_updated_at_ms = sSubsUp)
            // 本机的改动推上去了（服务端的戳 ≥ 本机的）→ 不再 dirty；本机更新 → 留 dirty 下一轮再推
            row = row.copy(dirty = row.dirty == true && (mineUp > sUp || mineSubsUp > sSubsUp))
            bySlug[s.slug] = row
        }
        var nextEntries = entries
        for (m in merged) {
            val mine = bySlug[m.slug]
            if (mine != null && !isMerged(mine) && m.updated_at_ms > (mine.updated_at_ms ?: 0)) {
                bySlug[m.slug] = mine.copy(enabled = false, merged_into = m.into, updated_at_ms = m.updated_at_ms, subs = emptyList(), dirty = false)
                nextEntries = nextEntries.map { e ->
                    if (e.cat != m.slug) e else e.copy(cat = m.into, updated_at_ms = nowMs, dirty = true)
                }
            } else if (mine != null && isMerged(mine) && mine.dirty != true) {
                bySlug[m.slug] = mine.copy(merged_into = m.into, updated_at_ms = maxOf(mine.updated_at_ms ?: 0, m.updated_at_ms))
            } else if (mine == null) {
                order.add(m.slug)
                bySlug[m.slug] = MoneyCatDTO(
                    slug = m.slug, name = m.slug, direction = "expense", slot = 0, seq = 0, enabled = false, locked = false,
                    icon = "", subs = emptyList(), merged_into = m.into, updated_at_ms = m.updated_at_ms, subs_updated_at_ms = 0, dirty = false,
                )
            }
        }
        return order.mapNotNull { bySlug[it] } to nextEntries
    }

    /** slug 合法性：非空、≤ 40、只许 ASCII 小写字母 / 数字 / _ / -。 */
    fun isValidSlug(s: String): Boolean =
        s.isNotEmpty() && s.length <= 40 && s.all { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }

    // ── 周期记账（单机化 ②-b）：= 服务端 money_recur.py / PC recur.ts，逐条同口径 ──

    /** 一次最多补记 / 往后找多少期：一条规则不该把整个循环卡死。 */
    const val MAX_CATCHUP = 20000
    val cycles: Set<String> = setOf("day", "week", "month", "year")

    /** 一个公历日期（iOS 用三元组）。 */
    data class YMD(val y: Int, val m: Int, val d: Int)

    /** "YYYY-MM-DD" → YMD；格式不对回 null（规则里的日期是契约，不容错解析）。iOS split 丢空段，这里同口径。 */
    fun parseDate(s: String): YMD? {
        val p = trimWs(s).split("-").filter { it.isNotEmpty() }
        if (p.size != 3) return null
        val y = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        val d = p[2].toIntOrNull() ?: return null
        if (m !in 1..12 || d < 1 || d > daysInMonth(y, m)) return null
        return YMD(y, m, d)
    }

    /** 某年某月有几天（公历，含闰年）。 */
    fun daysInMonth(y: Int, m: Int): Int = YearMonth.of(y, m).lengthOfMonth()

    /** YMD → "YYYY-MM-DD"。 */
    fun fmtDate(d: YMD): String = String.format(Locale.ROOT, "%04d-%02d-%02d", d.y, d.m, d.d)

    private fun toLocal(d: YMD): LocalDate = LocalDate.of(d.y, d.m, d.d)
    private fun ofLocal(l: LocalDate): YMD = YMD(l.year, l.monthValue, l.dayOfMonth)

    /** python date.weekday()：0 = 周一。 */
    private fun weekday(d: YMD): Int = toLocal(d).dayOfWeek.value - 1

    /** 第 k 期（k 从 0 起）的应记日期。月 / 年周期在短月落到月末（31 号 → 2 月 28/29），不跳过。 */
    fun occurrenceAt(cycle: String, everyN: Int, first: YMD, weekDay: Int, k: Int): YMD? {
        val n = maxOf(1, everyN)
        return when (cycle) {
            "day" -> ofLocal(toLocal(first).plusDays(k.toLong() * n))
            "week" -> {
                val shift = ((weekDay - weekday(first)) % 7 + 7) % 7
                ofLocal(toLocal(first).plusDays(shift + k.toLong() * n * 7))
            }
            "month" -> {
                val total = first.y * 12 + (first.m - 1) + k * n
                val y = Math.floorDiv(total, 12)
                val m = total - y * 12 + 1
                YMD(y, m, minOf(first.d, daysInMonth(y, m)))
            }
            "year" -> {
                val y = first.y + k * n
                YMD(y, first.m, minOf(first.d, daysInMonth(y, first.m)))
            }
            else -> null
        }
    }

    /**
     * 某个应记日期在规则时区里的确切时刻（补记时 at_ms 写的就是它）。
     * 时刻解析同 iOS：split 丢空段、丢非数字段，缺小时按 9、缺分钟按 0；越界的时 / 分照 iOS Calendar 的宽松进位直接加。
     */
    fun occurInstantMs(d: YMD, timeHhmm: String, tzOffsetMin: Int): Long {
        val hm = timeHhmm.split(":").filter { it.isNotEmpty() }.mapNotNull { it.toIntOrNull() }
        val h = hm.firstOrNull() ?: 9
        val mi = if (hm.size > 1) hm[1] else 0
        val dayMs = toLocal(d).toEpochDay() * 86_400_000L
        return dayMs + h * 3_600_000L + mi * 60_000L - tzOffsetMin.toLong() * 60_000
    }

    private fun cmp(a: YMD, b: YMD): Int =
        if (a.y != b.y) a.y - b.y else if (a.m != b.m) a.m - b.m else a.d - b.d

    /** 首次~结束（含当天）之间最后一个应记日期；一笔都没有回 null。 */
    fun lastOccurInRange(cycle: String, everyN: Int, first: YMD, weekDay: Int, end: YMD): String? {
        if (cmp(end, first) < 0) return null
        var last: String? = null
        for (k in 0..MAX_CATCHUP) {
            val d = occurrenceAt(cycle, everyN, first, weekDay, k) ?: break
            if (cmp(d, end) > 0) break
            last = fmtDate(d)
        }
        return last
    }

    /** 下一次应记（日期, 时刻）：第一个**时刻** > afterMs 的那期；过了结束日期 / 算不动 → ("", 0)。 */
    fun nextFrom(r: MoneyRecurDTO, afterMs: Long): Pair<String, Long> {
        val first = parseDate(r.first_date) ?: return "" to 0L
        val end = if (r.end_kind == "date") parseDate(r.end_date) else null
        for (k in 0..MAX_CATCHUP) {
            val d = occurrenceAt(r.cycle, r.every_n, first, r.week_day, k) ?: return "" to 0L
            if (end != null && cmp(d, end) > 0) return "" to 0L
            val ms = occurInstantMs(d, r.time_hhmm, r.tz_offset_min)
            if (ms > afterMs) return fmtDate(d) to ms
        }
        return "" to 0L
    }

    /** 周期规则不合法的原因。 */
    enum class RuleInvalid { Name, Cents, Direction, Cat, Cycle, EveryN, WeekDay, FirstDate, Time, Tz, EndKind, EndDate, EndBeforeFirst, NothingInRange }

    /** validateRule 抛的异常。 */
    class InvalidRule(val why: RuleInvalid) : Exception(why.name)

    /** 校验（= 服务端 _validate）。回规整后的规则（next 两列另算）。⚠️ 拦「一笔都记不到」的区间（周五到周六没有周一）。 */
    fun validateRule(r: MoneyRecurDTO): MoneyRecurDTO {
        val name = trimWs(r.name)
        if (name.isEmpty()) throw InvalidRule(RuleInvalid.Name)
        if (r.cents <= 0) throw InvalidRule(RuleInvalid.Cents)
        if (r.direction !in directions) throw InvalidRule(RuleInvalid.Direction)
        val cat = trimWs(r.cat)
        if (cat.isEmpty()) throw InvalidRule(RuleInvalid.Cat)
        if (r.cycle !in cycles) throw InvalidRule(RuleInvalid.Cycle)
        if (r.every_n !in 1..365) throw InvalidRule(RuleInvalid.EveryN)
        if (r.week_day !in 0..6) throw InvalidRule(RuleInvalid.WeekDay)
        val first = parseDate(r.first_date) ?: throw InvalidRule(RuleInvalid.FirstDate)
        val hm = r.time_hhmm.split(":").filter { it.isNotEmpty() }.mapNotNull { it.toIntOrNull() }
        if (hm.size != 2 || hm[0] !in 0..23 || hm[1] !in 0..59) throw InvalidRule(RuleInvalid.Time)
        if (r.tz_offset_min !in -14 * 60..14 * 60) throw InvalidRule(RuleInvalid.Tz)
        if (r.end_kind != "never" && r.end_kind != "date") throw InvalidRule(RuleInvalid.EndKind)
        var endDate = r.end_date
        if (r.end_kind == "date") {
            val end = parseDate(r.end_date) ?: throw InvalidRule(RuleInvalid.EndDate)
            if (cmp(end, first) < 0) throw InvalidRule(RuleInvalid.EndBeforeFirst)
            lastOccurInRange(r.cycle, r.every_n, first, r.week_day, end) ?: throw InvalidRule(RuleInvalid.NothingInRange)
        } else endDate = ""
        return r.copy(name = name, cat = cat, end_date = endDate, sub = trimWs(r.sub), merchant = trimWs(r.merchant))
    }

    /** 到期补记的结果：next 前移后的规则 + 新补出来的流水。 */
    data class DueResult(val rules: List<MoneyRecurDTO>, val entries: List<MoneyEntryDTO>)

    /**
     * 到期补记（= 服务端 scan_due）：没停、没到头、next_at_ms <= now 的规则逐期补到现在；流水 id recur-<规则>-<日期>，
     * 已有同 id 的不重复生成；next 只是派生状态（不盖戳、不标 dirty）。iOS: MoneyLocalRules.swift:424
     */
    fun runDue(rules: List<MoneyRecurDTO>, existingIds: Set<String>, nowMs: Long): DueResult {
        val out = mutableListOf<MoneyEntryDTO>()
        val next = rules.map { r ->
            if (r.paused || r.next_at_ms <= 0 || r.next_at_ms > nowMs) return@map r
            var nd = r.next_date
            var nms = r.next_at_ms
            var steps = 0
            while (nms > 0 && nms <= nowMs) {
                steps += 1; if (steps > MAX_CATCHUP) break
                val id = "recur-${r.id}-$nd"
                if (id !in existingIds && out.none { it.id == id }) {
                    out.add(
                        MoneyEntryDTO(
                            id = id, cents = r.cents, direction = r.direction, cat = r.cat, sub = r.sub, merchant = r.merchant,
                            at_ms = nms, tz_offset_min = r.tz_offset_min, ym = monthOf(nms, r.tz_offset_min),
                            src = "recur", rule_id = r.id, batch_id = "", order_no = "", updated_at_ms = nowMs, deleted = false,
                            atts = null, dirty = true,
                        )
                    )
                }
                val (nd2, nms2) = nextFrom(r, nms)
                if (nd2 == nd && nms2 == nms) { nms = 0; nd = ""; break }
                nd = nd2; nms = nms2
            }
            r.copy(next_date = nd, next_at_ms = nms)
        }
        return DueResult(next, out)
    }

    /** done_count / last_done_ms 从流水现算（服务端同款）；顺序：在跑的在前、按名字。 */
    fun withStats(rules: List<MoneyRecurDTO>, entries: List<MoneyEntryDTO>): List<MoneyRecurDTO> {
        val by = HashMap<String, Pair<Int, Long>>()
        for (e in entries) {
            if (e.src != "recur" || e.rule_id.isEmpty()) continue
            val s = by[e.rule_id] ?: (0 to 0L)
            by[e.rule_id] = (s.first + 1) to maxOf(s.second, e.at_ms)
        }
        return rules.map { r -> r.copy(done_count = by[r.id]?.first ?: 0, last_done_ms = by[r.id]?.second ?: 0L) }
            .sortedWith { a, b -> if (a.paused != b.paused) (if (!a.paused) -1 else 1) else a.name.compareTo(b.name) }
    }

    /** 合并（同流水那套）。 */
    fun mergeRules(
        local: List<MoneyRecurDTO>, items: List<MoneyRecurDTO>,
        tombs: List<MoneyTomb>, pendingTombs: List<MoneyTomb> = emptyList(),
    ): List<MoneyRecurDTO> {
        val byId = HashMap<String, MoneyRecurDTO>()
        val order = mutableListOf<String>()
        for (r in local) { if (r.id !in byId) order.add(r.id); byId[r.id] = r }
        val myDeletes = HashMap<String, Long>()
        for (t in pendingTombs) myDeletes[t.id] = maxOf(myDeletes[t.id] ?: Long.MIN_VALUE, t.deletedAtMs)
        for (s in items) {
            val mine = byId[s.id]
            if (mine != null && mine.dirty == true && mine.updated_at_ms > s.updated_at_ms) continue
            val delAt = myDeletes[s.id]
            if (delAt != null && delAt > s.updated_at_ms) continue
            if (s.id !in byId) order.add(s.id)
            byId[s.id] = s.copy(dirty = null)
        }
        for (t in tombs) {
            val mine = byId[t.id]
            if (mine != null && mine.dirty == true && mine.updated_at_ms > t.deletedAtMs) continue
            byId.remove(t.id)
        }
        return order.mapNotNull { byId[it] }
    }

    // ── 附件本机暂存（单机化 ②-b）：id 形如 local:<uuid>.<ext> ──

    const val LOCAL_FILE_PREFIX = "local:"

    /** 这个附件 id 是不是本机暂存的（还没传上去）。 */
    fun isLocalFileId(id: String): Boolean = id.startsWith(LOCAL_FILE_PREFIX)

    /**
     * 本机 id → 文件名（只认 uuid[.ext]：别让一个拼出来的 id 读到别的路径）。不合法回 null。
     * 与 iOS 的细差（都更严）：uuid 只认 ASCII 十六进制（iOS isHexDigit 还认全角）；
     * 名字以「.」开头时 iOS 的 split 会丢掉那个空段，这里整个拒掉。
     */
    fun localFileName(id: String): String? {
        if (!isLocalFileId(id)) return null
        val name = id.substring(LOCAL_FILE_PREFIX.length)
        val parts = name.split(".", limit = 2).let { if (it.size == 2 && it[1].isEmpty()) listOf(it[0]) else it }
        val uuid = parts.firstOrNull() ?: return null
        if (uuid.length != 36 || !uuid.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == '-' }) return null
        if (parts.size == 2) {
            val ext = parts[1]
            if (ext.isEmpty() || ext.length > 8 || !ext.all { it.isLetter() || isNumberChar(it) }) return null
        }
        return name
    }

    /** 新建一个本机 id：扩展名从原文件名取（小写、字母数字），没有就不带 —— 图片查看器按扩展名认图。 */
    fun newLocalFileId(uuid: String, originalName: String): String {
        val ext = originalName.split(".").lastOrNull { it.isNotEmpty() }?.lowercase() ?: ""
        val clean = ext.filter { it.isLetter() || isNumberChar(it) }
        val withExt = !(clean.isEmpty() || clean.length > 8 || !originalName.contains("."))
        return LOCAL_FILE_PREFIX + uuid.lowercase() + (if (withExt) ".$clean" else "")
    }

    /** Swift Character.isNumber 的近似：Unicode 数字类（Nd / Nl / No）。 */
    private fun isNumberChar(c: Char): Boolean = when (Character.getType(c)) {
        Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt() -> true
        else -> false
    }

    // ── 换账号（umbra-ios#89，§5.5.2，同 PC MoneyManager.rebaseForAccount）──

    /**
     * 这一笔算不算「还没推上去的本机改动」：dirty，或者挂着还没传上去的本机附件（local: 开头）。
     * 后一种 PC 没算 —— 附件补传只在这一笔推上去之后才做，不算进来的话换账号时它被当成「已同步」清掉，图就丢了。
     */
    fun isUnpushed(e: MoneyEntryDTO): Boolean = e.dirty == true || e.attList.any { isLocalFileId(it.file_id) }

    /** 还没推上去的条数（流水 / 分类 / 规则的改动 + 两种墓碑）—— 换账号时问「带过去 / 丢掉」用。 */
    fun pendingCount(
        entries: List<MoneyEntryDTO>, tombs: List<MoneyTomb>, cats: List<MoneyCatDTO>,
        rules: List<MoneyRecurDTO>, ruleTombs: List<MoneyTomb>,
    ): Int = entries.count { isUnpushed(it) } + tombs.size + cats.count { it.dirty == true } +
        rules.count { it.dirty == true } + ruleTombs.size

    /** 换账号之后本机留下的东西。 */
    data class Rebase(val entries: List<MoneyEntryDTO>, val cats: List<MoneyCatDTO>, val rules: List<MoneyRecurDTO>)

    /**
     * 换账号之后本机留下什么。已同步过的全部清掉（按新账号拉回来为准）；carry 才留下没推上去的流水 / 规则（标 dirty），
     * 连同它们挂着的自建分类；分类其余回内置那一份（戳是 0，服务端的版本一律胜出）。墓碑由调用方一律丢。
     */
    fun rebaseForAccount(carry: Boolean, entries: List<MoneyEntryDTO>, cats: List<MoneyCatDTO>, rules: List<MoneyRecurDTO>): Rebase {
        if (!carry) return Rebase(emptyList(), seedCats(), emptyList())
        val keptEntries = entries.filter { isUnpushed(it) }.map { it.copy(dirty = true) }
        val keptRules = rules.filter { it.dirty == true }
        val seeds = seedCats()
        val seedSlugs = seeds.map { it.slug }.toSet()
        val used = (keptEntries.map { it.cat } + keptRules.map { it.cat }).toSet()
        val keptCats = cats
            .filter { it.dirty == true || (it.slug !in seedSlugs && !isMerged(it) && it.slug in used) }
            .map { it.copy(dirty = true) }
        val keptSlugs = keptCats.map { it.slug }.toSet()
        return Rebase(keptEntries, seeds.filter { it.slug !in keptSlugs } + keptCats, keptRules)
    }
}
