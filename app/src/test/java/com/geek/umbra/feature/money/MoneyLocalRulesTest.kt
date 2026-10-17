package com.geek.umbra.feature.money

import com.geek.umbra.feature.money.MoneyLocalRules.YMD
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * iOS Tools/swifttests/MoneyLocalRulesTests.swift 逐条照抄（单机化 ②-a，与 PC tests/moneyLocal.test.ts 对得上）。
 * E(...) / atLocal(...) 两个辅助函数照搬。
 */
class MoneyLocalRulesTest {
    private val R = MoneyLocalRules
    private val T0 = 1_756_000_000_000L

    private fun E(
        id: String, cents: Long = 1000, direction: String = "expense", cat: String = "food", sub: String = "", merchant: String = "",
        atMs: Long = T0, tz: Int = 480, ym: String? = null, batch: String = "", updated: Long = 1000, dirty: Boolean? = null,
    ) = MoneyEntryDTO(
        id = id, cents = cents, direction = direction, cat = cat, sub = sub, merchant = merchant, at_ms = atMs, tz_offset_min = tz,
        ym = ym ?: MoneyLocalRules.monthOf(atMs, tz), src = "manual", rule_id = "", batch_id = batch, order_no = "",
        updated_at_ms = updated, deleted = false, atts = null, dirty = dirty,
    )

    /** 某个本地时间（UTC 时分 + 偏移）对应的 epoch 毫秒。 */
    private fun atLocal(y: Int, mo: Int, d: Int, h: Int = 12, tzMin: Int = 480): Long =
        LocalDateTime.of(y, mo, d, h, 0).toInstant(ZoneOffset.UTC).toEpochMilli() - tzMin.toLong() * 60_000

    @Test fun monthCases() {
        val ms = atLocal(2026, 9, 1, 0, tzMin = 480) + 30 * 60_000
        assertEquals("东八区 9 月 1 日 00:30 归 9 月", "2026-09", R.monthOf(ms, 480))
        assertEquals("按 UTC 算会落 8 月（错的那种）", "2026-08", R.monthOf(ms, 0))
        val w = atLocal(2026, 8, 31, 22, tzMin = -300)
        assertEquals("西五区 8 月 31 日 22:00 归 8 月", "2026-08", R.monthOf(w, -300))
        assertEquals("跨年往前", "2025-12", R.shiftMonth("2026-01", -1))
        assertEquals("跨年往后", "2027-01", R.shiftMonth("2026-12", 1))
        assertEquals("推半年", "2025-09", R.shiftMonth("2026-03", -6))
    }

    private fun tryValidate(e: MoneyEntryDTO): MoneyEntryDTO? = try { R.validate(e) } catch (_: MoneyLocalRules.InvalidEntry) { null }

    @Test fun validateCases() {
        val ok = tryValidate(E("a", cents = 1234, cat = " food "))
        assertEquals("分类去空白", "food", ok?.cat)
        assertEquals("ym 由 at_ms + 偏移算出", R.monthOf(T0, 480), ok?.ym)
        assertEquals("负数分拒", true, tryValidate(E("a", cents = -1)) == null)
        assertEquals("方向中文拒", true, tryValidate(E("a", direction = "支出")) == null)
        assertEquals("at_ms 0 拒", true, tryValidate(E("a", atMs = 0)) == null)
        assertEquals("时区超 ±14h 拒", true, tryValidate(E("a", tz = 900)) == null)
        assertEquals("空分类拒", true, tryValidate(E("a", cat = "")) == null)
        assertEquals("空 id 拒", true, tryValidate(E(" ")) == null)
    }

    private val all: List<MoneyEntryDTO> by lazy {
        listOf(
            E("a", cents = 300, cat = "food", merchant = "面馆", atMs = atLocal(2026, 8, 3)),
            E("b", cents = 500, cat = "transport", sub = "打车", atMs = atLocal(2026, 8, 5)),
            E("c", cents = 900, cat = "food", atMs = atLocal(2026, 8, 9)),
            E("d", cents = 8000, direction = "income", cat = "salary", atMs = atLocal(2026, 7, 1)),
            E("e", cents = 100, cat = "food", atMs = atLocal(2026, 5, 1), batch = "B1"),
        )
    }

    @Test fun listCases() {
        val r = R.list(all, MoneyLocalRules.Filter(ym = "2026-08", limit = 2))
        assertEquals("按 at_ms 倒序、分页", listOf("c", "b"), r.items.map { it.id })
        assertEquals("合计按整个筛选结果算，不按这一页", listOf(3L, 1700L, 0L), listOf(r.totals.count.toLong(), r.totals.expense, r.totals.income))
        assertEquals("关键词搜商家 / 子类", listOf("b"), R.list(all, MoneyLocalRules.Filter(keyword = "打车")).items.map { it.id })
        assertEquals("按批次筛", 1, R.list(all, MoneyLocalRules.Filter(batchId = "B1")).totals.count)
        assertEquals("按方向筛", listOf("d"), R.list(all, MoneyLocalRules.Filter(direction = "income")).items.map { it.id })
    }

    @Test fun statsCases() {
        val s = R.stats(all, "2026-08", trendMonths = 3)
        assertEquals("当月收支结余", listOf(1700L, 0L, -1700L), listOf(s.expense, s.income, s.balance))
        assertEquals("分类占比降序", listOf("food:1200:2", "transport:500:1"), s.by_cat.map { "${it.cat}:${it.cents}:${it.count}" })
        assertEquals("趋势老→新含当月", listOf("2026-06", "2026-07", "2026-08"), s.trend.map { it.ym })
        assertEquals("趋势金额", listOf(0L, 0L, 1700L), s.trend.map { it.cents })
        assertEquals("上月", "2026-07", s.prev_ym)
        assertEquals("上月只有收入也算「有记录」：是 0 不是 nil", 0L, s.prev_expense)
        assertEquals("上月没有记录 → nil（别画「比上月少 100%」）", null, R.stats(all, "2026-05", trendMonths = 2).prev_expense)
        assertEquals("生活账本不算税口径那三个数", null, s.cost)
    }

    @Test fun mergeCases() {
        val local = listOf(E("a", cents = 1, updated = 20, dirty = true), E("b", cents = 1, updated = 5), E("c", updated = 50, dirty = true))
        val out = R.mergeEntries(
            local, listOf(E("a", cents = 2, updated = 10), E("b", cents = 2, updated = 9), E("z", updated = 1)),
            listOf(MoneyTomb("c", 40)), listOf(MoneyTomb("z", 2)),
        )
        val by = out.associateBy { it.id }
        assertEquals("本机 dirty 且更新：留本机", 1L, by["a"]?.cents)
        assertEquals("服务端更新：取服务端", 2L, by["b"]?.cents)
        assertEquals("服务端来的不 dirty", null, by["b"]?.dirty)
        assertEquals("本机改得比那边删得新：留", true, by["c"] != null)
        assertEquals("本机删得更新：不加回", null, by["z"])
        val sent = E("a", updated = 10, dirty = true)
        assertEquals("在途又改过：不动", true,
            R.applyPut(listOf(E("a", updated = 11, dirty = true)), sent, MoneyLocalRules.PutOutcome.Written)[0].dirty)
        assertEquals("写上了：清 dirty", null, R.applyPut(listOf(sent), sent, MoneyLocalRules.PutOutcome.Written)[0].dirty)
        assertEquals("被拒：用服务端那份", 7L,
            R.applyPut(listOf(sent), sent, MoneyLocalRules.PutOutcome.Rejected(E("a", cents = 7, updated = 12)))[0].cents)
        assertEquals("409 other_ledger：去掉本机那份", 0, R.applyPut(listOf(sent), sent, MoneyLocalRules.PutOutcome.OtherLedger).size)
    }

    @Test fun catCases() {
        val cats = R.seedCats()
        assertEquals("内置分类 18 个", 18, cats.size)
        assertEquals("兜底锁定", listOf("other", "other_in"), cats.filter { it.locked }.map { it.slug })
        assertEquals("子类种子", listOf("早餐", "午餐", "晚餐", "外卖", "咖啡奶茶", "请客"), cats.firstOrNull { it.slug == "food" }?.subList)
        assertEquals("居住的色槽", 9, cats.firstOrNull { it.slug == "housing" }?.slot)
        assertEquals("支出 10 个", 10, R.live(cats).map { it.direction }.count { it == "expense" })
        assertEquals("支出池满 → 中性", 0, R.freeSlot(cats, "expense"))
        assertEquals("收入池最小空号 9", 9, R.freeSlot(cats, "income"))
        assertEquals("端上 slug 形状", "u170000000000007", R.newSlug(1_700_000_000_000, 7))
        assertEquals("slug 合法性", true, R.isValidSlug("u170000000000007") && R.isValidSlug("other_in") && !R.isValidSlug("Bad Slug!"))
        val entries = listOf(E("a", cat = "study"), E("b", cat = "food"))
        val r = R.mergeCat(cats, entries, "study", "daily", 999)
        assertEquals("账改挂并标 dirty", listOf("daily", "999", "true"),
            r.entries.firstOrNull { it.id == "a" }?.let { listOf(it.cat, it.updated_at_ms.toString(), (it.dirty == true).toString()) })
        assertEquals("别的账不动", null, r.entries.firstOrNull { it.id == "b" }?.dirty)
        assertEquals("源落墓碑", listOf("daily", "false"),
            r.cats.firstOrNull { it.slug == "study" }?.let { listOf(it.merged_into ?: "", it.enabled.toString()) })
        assertEquals("子类同名合并", listOf("生活用品", "母婴", "宠物", "书籍", "课程", "软件工具"), r.cats.firstOrNull { it.slug == "daily" }?.subList)
        assertEquals("搬了几笔", 1, r.moved.entries)
        fun refusal(slug: String, into: String, cs: List<MoneyCatDTO> = cats): MoneyLocalRules.MergeRefusal? =
            try { R.mergeCat(cs, entries, slug, into, 1); null } catch (e: MoneyLocalRules.MergeRefused) { e.why }
        assertEquals("并进自己拒", MoneyLocalRules.MergeRefusal.Self, refusal("study", "study"))
        assertEquals("兜底拒", MoneyLocalRules.MergeRefusal.Locked, refusal("other", "food"))
        assertEquals("跨方向拒", MoneyLocalRules.MergeRefusal.Direction, refusal("study", "salary"))
        assertEquals("已并掉的拒", MoneyLocalRules.MergeRefusal.SrcMerged, refusal("study", "food", r.cats))
    }

    private val rule = MoneyRecurDTO(
        id = "r1", name = "日费", cents = 100, direction = "expense", cat = "fun", sub = "", merchant = "", cycle = "day", every_n = 1,
        week_day = 0, first_date = "2026-09-01", time_hhmm = "09:00", tz_offset_min = 480, end_kind = "never", end_date = "",
        next_date = "", next_at_ms = 0, paused = false, done_count = 0, last_done_ms = 0, updated_at_ms = 1000, dirty = null,
    )

    /** ⑤ 周期规则：日历 = 服务端 occurrences；下一次按时刻；校验拦「一笔都记不到」；补记 id 固定、不重复、next 前移。 */
    @Test fun recurCases() {
        fun d(k: Int, cycle: String, first: YMD, wd: Int = 0, n: Int = 1): String =
            R.fmtDate(R.occurrenceAt(cycle, n, first, wd, k)!!)
        assertEquals("月末顺延不跳过", listOf("2026-01-31", "2026-02-28", "2026-03-31", "2026-04-30"),
            (0..3).map { d(it, "month", YMD(2026, 1, 31)) })
        assertEquals("闰年 2 月", "2028-02-29", d(1, "month", YMD(2028, 1, 31)))
        assertEquals("年周期 2/29 平年落 2/28", listOf("2024-02-29", "2025-02-28", "2028-02-29"),
            listOf(0, 1, 4).map { d(it, "year", YMD(2024, 2, 29)) })
        assertEquals("周：首次日之后第一个周一起", listOf("2026-09-07", "2026-09-14"), (0..1).map { d(it, "week", YMD(2026, 9, 1), 0) })
        assertEquals("周：首次日自己就是周二", listOf("2026-09-01", "2026-09-08"), (0..1).map { d(it, "week", YMD(2026, 9, 1), 1) })
        assertEquals("每 2 天跨年", listOf("2026-12-30", "2027-01-01", "2027-01-03"), (0..2).map { d(it, "day", YMD(2026, 12, 30), 0, 2) })
        assertEquals("应记时刻按规则时区（东八区 9:00 = UTC 1:00）", 1_788_224_400_000L, R.occurInstantMs(YMD(2026, 9, 1), "09:00", 480))
        val sep1_1500 = R.occurInstantMs(YMD(2026, 9, 1), "15:00", 480)
        assertEquals("15:00 建「每天 09:00」：下一笔是明天（比时刻不比日期）", "2026-09-02", R.nextFrom(rule, sep1_1500).first)
        val ended = rule.copy(end_kind = "date", end_date = "2026-09-15")
        assertEquals("过了结束日期 → 0", 0L, R.nextFrom(ended, R.occurInstantMs(YMD(2026, 9, 20), "00:00", 480)).second)
        assertEquals("区间里最后一期", "2026-02-28", R.lastOccurInRange("month", 1, YMD(2026, 1, 31), 0, YMD(2026, 3, 1)))
        fun invalid(f: (MoneyRecurDTO) -> MoneyRecurDTO): MoneyLocalRules.RuleInvalid? =
            try { R.validateRule(f(rule)); null } catch (e: MoneyLocalRules.InvalidRule) { e.why }
        assertEquals("周五到周六没有周一：拦", MoneyLocalRules.RuleInvalid.NothingInRange,
            invalid { it.copy(cycle = "week", week_day = 0, first_date = "2026-09-04", end_kind = "date", end_date = "2026-09-05") })
        assertEquals("结束早于首次：拦", MoneyLocalRules.RuleInvalid.EndBeforeFirst, invalid { it.copy(end_kind = "date", end_date = "2026-08-01") })
        assertEquals("没名字：拦", MoneyLocalRules.RuleInvalid.Name, invalid { it.copy(name = " ") })
        assertEquals("金额 0：拦", MoneyLocalRules.RuleInvalid.Cents, invalid { it.copy(cents = 0) })
        assertEquals("不存在的日期：拦", MoneyLocalRules.RuleInvalid.FirstDate, invalid { it.copy(first_date = "2026-02-30") })
        // 补记：9/1 到期的日规则，现在是 9/3 中午；9/2 那笔已有（别的端记了）→ 只补 9/1、9/3；next 拨到 9/4；停了的不动
        val due = rule.copy(next_date = "2026-09-01", next_at_ms = R.occurInstantMs(YMD(2026, 9, 1), "09:00", 480))
        val paused = due.copy(id = "p", paused = true)
        val now = R.occurInstantMs(YMD(2026, 9, 3), "12:00", 480)
        val r = R.runDue(listOf(due, paused), setOf("recur-r1-2026-09-02"), now)
        assertEquals("逐期补、已有同 id 不重复", listOf("recur-r1-2026-09-01", "recur-r1-2026-09-03"), r.entries.map { it.id })
        assertEquals("补出来的流水", listOf("recur", "r1", "2026-09", "true", "true"),
            r.entries.firstOrNull()?.let { listOf(it.src, it.rule_id, it.ym, (it.dirty == true).toString(), (it.at_ms == due.next_at_ms).toString()) })
        assertEquals("next 前移、不标 dirty", listOf("2026-09-04", "false"), listOf(r.rules[0].next_date, (r.rules[0].dirty == true).toString()))
        assertEquals("停了的不动", "2026-09-01", r.rules[1].next_date)
        val s = R.withStats(listOf(rule), r.entries)
        assertEquals("done_count / last_done_ms 从流水现算", listOf(2, 1),
            listOf(s[0].done_count, if (s[0].last_done_ms == r.entries[1].at_ms) 1 else 0))
        val mine = rule.copy(name = "本机", updated_at_ms = 20, dirty = true)
        val theirs = rule.copy(name = "远端", updated_at_ms = 10)
        val other = rule.copy(id = "b")
        val merged = R.mergeRules(listOf(mine), listOf(theirs, other), listOf(MoneyTomb("b", 99)))
        assertEquals("合并同流水那套", listOf("r1:本机"), merged.map { "${it.id}:${it.name}" })
    }

    /** ⑥ 附件本机 id：只认 local:<uuid>[.ext]，别的（含 ..）不给路径。 */
    @Test fun localFileCases() {
        val id = R.newLocalFileId("2E9F6D1A-5B3C-4F8E-9A7B-1C2D3E4F5A6B", "IMG_0001.JPG")
        assertEquals("小写 uuid + 小写扩展名（图片查看器按扩展名认图）", "local:2e9f6d1a-5b3c-4f8e-9a7b-1c2d3e4f5a6b.jpg", id)
        assertEquals("没扩展名就不带", "local:2e9f6d1a-5b3c-4f8e-9a7b-1c2d3e4f5a6b",
            R.newLocalFileId("2e9f6d1a-5b3c-4f8e-9a7b-1c2d3e4f5a6b", "noext"))
        assertEquals("id → 文件名", "2e9f6d1a-5b3c-4f8e-9a7b-1c2d3e4f5a6b.jpg", R.localFileName(id))
        assertEquals("拼出来的 id 不给路径", null, R.localFileName("local:../../etc/passwd"))
        assertEquals("服务端 id 不是本机的", null, R.localFileName("srv-123"))
        assertEquals("认 local: 前缀", true, R.isLocalFileId(id) && !R.isLocalFileId("abc"))
    }

    @Test fun catSyncCases() {
        val local = R.seedCats().map { if (it.slug == "food") it.copy(name = "吃喝", updated_at_ms = 50, dirty = true) else it }
        val server = R.seedCats().filter { it.slug != "study" }.map {
            when (it.slug) {
                "food" -> it.copy(name = "餐饮改", updated_at_ms = 40)
                "fun" -> it.copy(name = "玩乐", updated_at_ms = 60, subs = listOf("看电影"), subs_updated_at_ms = 60)
                else -> it
            }
        }
        val entries = listOf(E("a", cat = "study"))
        val (cats, es) = R.applyCatSync(local, server, listOf(MoneyCatMergedDTO("study", "other", 70)), entries, 100)
        assertEquals("本机更新：留本机、留 dirty 下一轮推", listOf("吃喝", "true"),
            cats.firstOrNull { it.slug == "food" }?.let { listOf(it.name, (it.dirty == true).toString()) })
        assertEquals("服务端更新：取服务端", listOf("玩乐", "看电影", "false"),
            cats.firstOrNull { it.slug == "fun" }?.let { listOf(it.name, it.subList.joinToString(""), (it.dirty == true).toString()) })
        assertEquals("服务端墓碑落本机", listOf("other", "false"),
            cats.firstOrNull { it.slug == "study" }?.let { listOf(it.merged_into ?: "", it.enabled.toString()) })
        assertEquals("本机指向它的账改挂", "other", es[0].cat)
        assertEquals("改挂的账要推上去", true, es[0].dirty)
    }

    /** umbra-ios#89：换账号时已同步的清掉、分类回内置；带过去只留没推上去的流水 / 规则和它们挂着的自建分类。 */
    @Test fun accountSwitchCases() {
        val rule = rule.copy(cat = "mine2", dirty = true)
        val syncedRule = rule.copy(id = "r0", dirty = null)
        fun cat(slug: String, dirty: Boolean? = null, merged: String = ""): MoneyCatDTO =
            R.seedCats()[0].copy(slug = slug, name = slug, dirty = dirty, merged_into = merged, updated_at_ms = 500)
        val food = R.seedCats()[0].copy(name = "上个账号改过的餐饮", updated_at_ms = 900)
        val cats = R.seedCats().filter { it.slug != "food" } + listOf(food, cat("mine1"), cat("mine2"), cat("mine3", dirty = true), cat("unused"))
        val withPhoto = E("p", cat = "mine1").copy(atts = listOf(MoneyAttDTO(file_id = "local:abc.jpg", label = "", origin = false, kind = "")))
        val entries = listOf(E("a", cat = "food"), E("b", cat = "mine1", dirty = true), E("c", cat = "unused"), withPhoto)
        assertEquals("挂着没传上去的本机图 = 没推上去", true, R.isUnpushed(withPhoto))
        assertEquals("已同步的不算", false, R.isUnpushed(E("a")))
        assertEquals("待推 = 2 笔流水 + 1 条墓碑 + 1 个分类 + 1 条规则", 5,
            R.pendingCount(entries, listOf(MoneyTomb("z", 1)), cats, listOf(rule, syncedRule), emptyList()))

        val drop = R.rebaseForAccount(false, entries, cats, listOf(rule, syncedRule))
        assertEquals("丢掉：流水 / 规则一条不留", listOf(0, 0), listOf(drop.entries.size, drop.rules.size))
        assertEquals("丢掉：分类回内置那一份", R.seedCats().map { it.slug }, drop.cats.map { it.slug })
        assertEquals("上个账号改过的内置分类也回原样（戳 0，新账号的版本胜出）", "餐饮", drop.cats.firstOrNull { it.slug == "food" }?.name)

        val carry = R.rebaseForAccount(true, entries, cats, listOf(rule, syncedRule))
        assertEquals("带过去：只留没推上去的两笔", listOf("b", "p"), carry.entries.map { it.id }.sorted())
        assertEquals("带过去的都标 dirty（挂图那笔也要推）", true, carry.entries.all { it.dirty == true })
        assertEquals("带过去：只留没推上去的规则", listOf("r1"), carry.rules.map { it.id })
        val slugs = carry.cats.map { it.slug }.toSet()
        assertEquals("带过去：留被带过去的账 / 规则挂着的自建分类和改过的分类，别的自建分类不留", listOf(true, true, true, false),
            listOf("mine1" in slugs, "mine2" in slugs, "mine3" in slugs, "unused" in slugs))
        assertEquals("留下的分类标 dirty 推进新账号", true,
            carry.cats.filter { it.slug in listOf("mine1", "mine2", "mine3") }.all { it.dirty == true })
        assertEquals("带过去：内置分类仍回原样", "餐饮", carry.cats.firstOrNull { it.slug == "food" }?.name)
        assertEquals("内置分类一个不少", true, slugs.containsAll(R.seedCats().map { it.slug }))
    }
}
