// ReminderAdvanceTest 的夹具 —— 由 iOS `Tools/swifttests/ReminderAdvanceTests.swift` 的同名数组机械转写
//（期望值是 `reminder_advance_fixtures.mjs` 用 node 直接执行 PC 的 scanner.ts 取得；2026-10-17 重跑生成器，与 iOS 那份逐行相同）。
// 别手改：PC 推进算法一变，重跑生成器、把 iOS 夹具更新后再转写一遍。
package com.geek.umbra.feature.reminder

/** (名字, 提醒的 tz, at, 规则, 单位, n, 结束, now, 存着的锚定日, PC 的结果)。进程时区在上海（tz 空 / 认不出退回上海）。 */
internal data class AdvFix(val name: String, val tz: String, val at: Long, val rule: String, val freq: String, val n: Long, val end: Long?, val now: Long, val anchor: Int?, val want: Advanced?)

/** (名字, 提醒的 tz, 日历名 / null, at, 规则, 单位, n, 结束, now, PC 的结果)。 */
internal data class HolFix(val name: String, val tz: String, val cal: String?, val at: Long, val rule: String, val freq: String, val n: Long, val end: Long?, val now: Long, val want: Advanced?)

/** 每周多选星期几：(名字, 提醒的 tz, at, 选中的星期几, 结束, now, PC 的结果)。 */
internal data class WeekFix(val name: String, val tz: String, val at: Long, val days: List<Int>, val end: Long?, val now: Long, val want: Advanced?)

/** 保存时校正 at（PC snapToWeekDays）：(名字, tz, at, 选中的星期几, PC 的结果)。 */
internal data class SnapFix(val name: String, val tz: String, val at: Long, val days: List<Int>, val want: Long)

private fun D(date: String, off: Boolean) = CnHolidayDay(date, off)

internal val pcFixtures: List<AdvFix> = listOf(
    AdvFix("每天：过点推到明天同一时刻", "Asia/Shanghai", 1790816400000, "daily", "day", 1, null, 1791180000000, null, Advanced(1791248400000L, false)),
    AdvFix("每天：at 恰等于 now 也推", "Asia/Shanghai", 1791180000000, "daily", "day", 1, null, 1791180000000, null, Advanced(1791266400000L, false)),
    AdvFix("每天：还没到点不动", "Asia/Shanghai", 1791248400000, "daily", "day", 1, null, 1791180000000, null, null),
    AdvFix("每周", "Asia/Shanghai", 1788222600000, "weekly", "day", 1, null, 1791180000000, null, Advanced(1791246600000L, false)),
    AdvFix("每月：31 号没存锚定日 → 2/28（不再溢出到 3/3）", "Asia/Shanghai", 1769821200000, "monthly", "day", 1, null, 1771084800000, null, Advanced(1772240400000L, false)),
    AdvFix("每月：31 号没存锚定日、一次跨两个月 → 推进全程锚 31 → 3/31", "Asia/Shanghai", 1769821200000, "monthly", "day", 1, null, 1773072000000, null, Advanced(1774918800000L, false)),
    AdvFix("每月：闰年 1/31 → 2/29", "Asia/Shanghai", 1706662800000, "monthly", "day", 1, null, 1707494400000, null, Advanced(1709168400000L, false)),
    AdvFix("工作日：周五过点 → 下周一", "Asia/Shanghai", 1790902800000, "weekday", "day", 1, null, 1790992800000, null, Advanced(1791162000000L, false)),
    AdvFix("自定义每 3 小时", "Asia/Shanghai", 1791133200000, "custom", "hour", 3, null, 1791180000000, null, Advanced(1791187200000L, false)),
    AdvFix("自定义每 2 天", "Asia/Shanghai", 1790809200000, "custom", "day", 2, null, 1791180000000, null, Advanced(1791327600000L, false)),
    AdvFix("自定义每 2 周", "Asia/Shanghai", 1788217200000, "custom", "week", 2, null, 1791180000000, null, Advanced(1791846000000L, false)),
    AdvFix("自定义每 2 月：8/31 → 10/31", "Asia/Shanghai", 1788130800000, "custom", "month", 2, null, 1791180000000, null, Advanced(1793401200000L, false)),
    AdvFix("自定义每 1 年：2/29 → 次年 2/28", "Asia/Shanghai", 1709161200000, "custom", "year", 1, null, 1709618400000, null, Advanced(1740697200000L, false)),
    AdvFix("自定义 n=0 按 1 算", "Asia/Shanghai", 1791075600000, "custom", "day", 0, null, 1791180000000, null, Advanced(1791248400000L, false)),
    AdvFix("未知单位按天", "Asia/Shanghai", 1791075600000, "custom", "fortnight", 1, null, 1791180000000, null, Advanced(1791248400000L, false)),
    AdvFix("结束日已过 → done，at 不动", "Asia/Shanghai", 1790816400000, "daily", "day", 1, 1791043199999, 1791180000000, null, Advanced(1790816400000L, true)),
    AdvFix("结束日当天那次照算", "Asia/Shanghai", 1790816400000, "daily", "day", 1, 1791302399999, 1791180000000, null, Advanced(1791248400000L, false)),
    AdvFix("结束日当天 0 点（下一次已越过）→ done", "Asia/Shanghai", 1790816400000, "daily", "day", 1, 1791216000000, 1791180000000, null, Advanced(1790816400000L, true)),
    AdvFix("一年前的每天", "Asia/Shanghai", 1759626000000, "daily", "day", 1, null, 1791180000000, null, Advanced(1791248400000L, false)),
    AdvFix("一年前的每小时（原来超 400 步护栏不动）→ 直接算到 now 之后第一个", "Asia/Shanghai", 1759626000000, "custom", "hour", 1, null, 1791180000000, null, Advanced(1791183600000L, false)),
    AdvFix("每小时、起点 15 年前（逐步走会撞 100000 步护栏）→ 直接算", "Asia/Shanghai", 1293843600000, "custom", "hour", 1, null, 1791180000000, null, Advanced(1791183600000L, false)),
    AdvFix("原 400 步护栏那一侧：第 400 步越过 now", "Asia/Shanghai", 1756342800000, "daily", "day", 1, null, 1790834400000, null, Advanced(1790902800000L, false)),
    AdvFix("原来要第 401 步、护栏停下 → 现在推得动", "Asia/Shanghai", 1756256400000, "daily", "day", 1, null, 1790834400000, null, Advanced(1790902800000L, false)),
    AdvFix("快进：1970 起每天（纽约）→ now 之后第一个", "America/New_York", 50400000, "daily", "day", 1, null, 1791271020000, null, Advanced(1791291600000L, false)),
    AdvFix("快进：1970 起工作日（纽约）→ 2026-10-06 周二", "America/New_York", 50400000, "weekday", "day", 1, null, 1791271020000, null, Advanced(1791291600000L, false)),
    AdvFix("快进：1970 起每 3 天", "Asia/Shanghai", 82800000, "custom", "day", 3, null, 1791180000000, null, Advanced(1791414000000L, false)),
    AdvFix("快进：1970 起每 2 周", "Asia/Shanghai", 82800000, "custom", "week", 2, null, 1791180000000, null, Advanced(1791500400000L, false)),
    AdvFix("快进：跳过的区间越过结束时刻 → done，at 不动", "America/New_York", 1577887200000, "daily", "day", 1, 1609477200000, 1767243600000, null, Advanced(1577887200000L, true)),
    AdvFix("快进：落在缺口里的 02:30 跳回原来的 02:30（逐步走会停在 03:30）", "America/New_York", 1772868600000, "daily", "day", 1, null, 1774022400000, null, Advanced(1774074600000L, false)),
    AdvFix("快进：每周落在缺口里的 02:30 跳回 02:30", "America/New_York", 1772350200000, "weekly", "day", 1, null, 1775016000000, null, Advanced(1775370600000L, false)),
    AdvFix("不快进：1970 起每月照逐步走（约 680 步，护栏远够不着）", "Asia/Shanghai", 2595600000, "monthly", "day", 1, null, 1791180000000, null, Advanced(1793408400000L, false)),
    AdvFix("不快进：1970 起每年", "Asia/Shanghai", 5014800000, "custom", "year", 1, null, 1791180000000, null, Advanced(1803776400000L, false)),
    AdvFix("不重复不动", "Asia/Shanghai", 1790816400000, "none", "day", 1, null, 1791180000000, null, null),
    AdvFix("毫秒尾巴保留", "Asia/Shanghai", 1791075600123, "daily", "day", 1, null, 1791180000000, null, Advanced(1791248400123L, false)),
    AdvFix("锚 31：1/31 → 2/28 → 3/31 → 4/30 → 5/31", "Asia/Shanghai", 1769821200000, "monthly", "day", 1, null, 1778774400000, 31, Advanced(1780189200000L, false)),
    AdvFix("锚 31：从 2/28 起回到 3/31", "Asia/Shanghai", 1772240400000, "monthly", "day", 1, null, 1772294400000, 31, Advanced(1774918800000L, false)),
    AdvFix("锚 31：从 4/30 起回到 5/31", "Asia/Shanghai", 1777510800000, "monthly", "day", 1, null, 1777564800000, 31, Advanced(1780189200000L, false)),
    AdvFix("没存锚定日：从 2/28 起只能 → 3/28（老数据）", "Asia/Shanghai", 1772240400000, "monthly", "day", 1, null, 1772294400000, null, Advanced(1774659600000L, false)),
    AdvFix("锚定日对不上 at（改到了 15 号）→ 跟 at 走 → 3/15", "Asia/Shanghai", 1771117200000, "monthly", "day", 1, null, 1771516800000, 31, Advanced(1773536400000L, false)),
    AdvFix("3/30 存着 31（3 月有 31 天，对不上）→ 取 30 → 4/30", "Asia/Shanghai", 1774832400000, "monthly", "day", 1, null, 1774972800000, 31, Advanced(1777510800000L, false)),
    AdvFix("坏锚定日 99 → 取 at 的日", "Asia/Shanghai", 1769821200000, "monthly", "day", 1, null, 1771084800000, 99, Advanced(1772240400000L, false)),
    AdvFix("锚定日 0 → 取 at 的日", "Asia/Shanghai", 1769734800000, "monthly", "day", 1, null, 1771084800000, 0, Advanced(1772240400000L, false)),
    AdvFix("闰年锚 31：1/31 → 2/29", "Asia/Shanghai", 1832893200000, "monthly", "day", 1, null, 1833724800000, 31, Advanced(1835398800000L, false)),
    AdvFix("29 号没存锚定日：平年 2/28，3 月回到 29", "Asia/Shanghai", 1769648400000, "monthly", "day", 1, null, 1772294400000, null, Advanced(1774746000000L, false)),
    AdvFix("自定义每 3 月锚 31：1/31 → 4/30", "Asia/Shanghai", 1769821200000, "custom", "month", 3, null, 1769875200000, 31, Advanced(1777510800000L, false)),
    AdvFix("自定义每 1 年锚 29：2028-02-29 → 2029-02-28", "Asia/Shanghai", 1835398800000, "custom", "year", 1, null, 1835452800000, 29, Advanced(1866934800000L, false)),
    AdvFix("自定义每 3 年锚 29：2029-02-28 → 2032-02-29", "Asia/Shanghai", 1866934800000, "custom", "year", 3, null, 1866988800000, 29, Advanced(1961629200000L, false)),
    AdvFix("每年：平年 2/28 没存锚定日 → 次年 2/28", "Asia/Shanghai", 1866934800000, "custom", "year", 1, null, 1866988800000, null, Advanced(1898470800000L, false)),
    AdvFix("每年锚 29 跨五年 → 2033-02-28（中途 2032 回到 29）", "Asia/Shanghai", 1835398800000, "custom", "year", 1, null, 1988121600000, 29, Advanced(1993165200000L, false)),
    AdvFix("锚 31 + 结束日 2/27 → done，at 不动", "Asia/Shanghai", 1769821200000, "monthly", "day", 1, 1772207999999, 1770652800000, 31, Advanced(1769821200000L, true)),
    AdvFix("每天不看锚定日", "Asia/Shanghai", 1769821200000, "daily", "day", 1, null, 1769824800000, 31, Advanced(1769907600000L, false)),
    AdvFix("春令跳过的 2:30 → 3:30，之后沿 3:30 走", "America/New_York", 1772868600000, "daily", "day", 1, null, 1773072000000, null, Advanced(1773127800000L, false)),
    AdvFix("秋令重复的 1:30 取前一个（EDT）", "America/New_York", 1793424600000, "daily", "day", 1, null, 1793505600000, null, Advanced(1793511000000L, false)),
    AdvFix("每小时跨春令（绝对时长）", "America/New_York", 1772951400000, "custom", "hour", 1, null, 1772953200000, null, Advanced(1772955000000L, false)),
    AdvFix("每小时跨秋令（绝对时长）", "America/New_York", 1793507400000, "custom", "hour", 1, null, 1793515500000, null, Advanced(1793518200000L, false)),
    AdvFix("每周跨春令，墙上时刻不变", "America/New_York", 1772460000000, "weekly", "day", 1, null, 1773115200000, null, Advanced(1773666000000L, false)),
    AdvFix("工作日跨周末与夏令", "America/New_York", 1772805600000, "weekday", "day", 1, null, 1772895600000, null, Advanced(1773061200000L, false)),
    AdvFix("锚 31 跨春令：2/28 02:30 → 3/31 02:30", "America/New_York", 1772263800000, "monthly", "day", 1, null, 1772341200000, 31, Advanced(1774938600000L, false)),
    AdvFix("每月从 0 点 31 号跨夏令", "Europe/London", 1769819400000, "monthly", "day", 1, null, 1774782000000, null, Advanced(1774913400000L, false)),
    AdvFix("换月那一步落进 1 号的春令缺口（一次换回，不再被中途挪）", "Australia/Sydney", 1853857800000, "monthly", "day", 1, null, 1853974800000, null, Advanced(1856446200000L, false)),
    AdvFix("自定义每 2 月锚 31，中间落进缺口（一次换回）", "Australia/Sydney", 1851265800000, "custom", "month", 2, null, 1851386400000, 31, Advanced(1856532600000L, false)),
    AdvFix("③ 每天 9 点（纽约）跨拨快那天仍是纽约 9 点", "America/New_York", 1772892000000, "daily", "day", 1, null, 1772902800000, null, Advanced(1772974800000L, false)),
    AdvFix("③ 每月 31 号晚 9 点（纽约）：日期按纽约算 → 2/28", "America/New_York", 1769911200000, "monthly", "day", 1, null, 1769914800000, 31, Advanced(1772330400000L, false)),
    AdvFix("③ 工作日：纽约周五晚 → 纽约周一（上海已是周六）", "America/New_York", 1773450000000, "weekday", "day", 1, null, 1773453600000, null, Advanced(1773709200000L, false)),
    AdvFix("③ tz 为空 → 进程本地（上海）：1/31 → 2/28", "", 1769821200000, "monthly", "day", 1, null, 1770220800000, null, Advanced(1772240400000L, false)),
    AdvFix("③ tz 认不出 → 进程本地（上海）：1/31 → 2/28", "Not/AZone", 1769821200000, "monthly", "day", 1, null, 1770220800000, null, Advanced(1772240400000L, false)),
    AdvFix("③ 同一条换成纽约：日期按纽约（1/31 晚 9 点）", "America/New_York", 1769911200000, "monthly", "day", 1, null, 1770267600000, null, Advanced(1772330400000L, false)),
    AdvFix("① 关机 30 天回来：每小时推到 now 之后第一个整点", "America/New_York", 1767225600000, "custom", "hour", 1, null, 1769818500000, null, Advanced(1769821200000L, false)),
    AdvFix("① 每 3 小时、正好落在 now 上 → now + 3h", "", 1767225600000, "custom", "hour", 3, null, 1767258000000, null, Advanced(1767268800000L, false)),
    AdvFix("① 每 3 小时越过结束时刻 → done，at 不动", "", 1767225600000, "custom", "hour", 3, 1767261600000, 1767265200000, null, Advanced(1767225600000L, true)),
    AdvFix("① 每天关机 2 年也推得动（原来 400 步 ≈ 13 个月）", "", 1704070800000, "daily", "day", 1, null, 1767240000000, null, Advanced(1767315600000L, false)),
    AdvFix("每 2 天（纽约）跨拨快墙上时刻不变", "America/New_York", 1772892000000, "custom", "day", 2, null, 1772946000000, null, Advanced(1773061200000L, false)),
    AdvFix("每月（纽约）秋令重复的 1:30 取先出现的", "America/New_York", 1790832600000, "monthly", "day", 1, null, 1790913600000, null, Advanced(1793511000000L, false)),
    AdvFix("每年（纽约）落进拨快缺口 → 往后挪一个缺口", "America/New_York", 1741505400000, "custom", "year", 1, null, 1741579200000, null, Advanced(1773041400000L, false)),
    AdvFix("每 5 小时（纽约）跨拨快：绝对时长", "America/New_York", 1772942400000, "custom", "hour", 5, null, 1772946000000, null, Advanced(1772960400000L, false)),
)

internal val holidayCals: Map<String, Map<String, List<CnHolidayDay>>> = mapOf(
    "国庆2026" to mapOf("2026" to listOf(D("2026-10-01", true), D("2026-10-02", true), D("2026-10-03", true), D("2026-10-04", true), D("2026-10-05", true), D("2026-10-06", true), D("2026-10-07", true), D("2026-10-10", false))),
    "国庆2026+2027空表" to mapOf("2026" to listOf(D("2026-10-01", true), D("2026-10-02", true), D("2026-10-03", true), D("2026-10-04", true), D("2026-10-05", true), D("2026-10-06", true), D("2026-10-07", true), D("2026-10-10", false)), "2027" to listOf()),
    "2026整年放假" to mapOf("2026" to listOf(D("2026-01-01", true), D("2026-01-02", true), D("2026-01-03", true), D("2026-01-04", true), D("2026-01-05", true), D("2026-01-06", true), D("2026-01-07", true), D("2026-01-08", true), D("2026-01-09", true), D("2026-01-10", true), D("2026-01-11", true), D("2026-01-12", true), D("2026-01-13", true), D("2026-01-14", true), D("2026-01-15", true), D("2026-01-16", true), D("2026-01-17", true), D("2026-01-18", true), D("2026-01-19", true), D("2026-01-20", true), D("2026-01-21", true), D("2026-01-22", true), D("2026-01-23", true), D("2026-01-24", true), D("2026-01-25", true), D("2026-01-26", true), D("2026-01-27", true), D("2026-01-28", true), D("2026-01-29", true), D("2026-01-30", true), D("2026-01-31", true), D("2026-02-01", true), D("2026-02-02", true), D("2026-02-03", true), D("2026-02-04", true), D("2026-02-05", true), D("2026-02-06", true), D("2026-02-07", true), D("2026-02-08", true), D("2026-02-09", true), D("2026-02-10", true), D("2026-02-11", true), D("2026-02-12", true), D("2026-02-13", true), D("2026-02-14", true), D("2026-02-15", true), D("2026-02-16", true), D("2026-02-17", true), D("2026-02-18", true), D("2026-02-19", true), D("2026-02-20", true), D("2026-02-21", true), D("2026-02-22", true), D("2026-02-23", true), D("2026-02-24", true), D("2026-02-25", true), D("2026-02-26", true), D("2026-02-27", true), D("2026-02-28", true), D("2026-03-01", true), D("2026-03-02", true), D("2026-03-03", true), D("2026-03-04", true), D("2026-03-05", true), D("2026-03-06", true), D("2026-03-07", true), D("2026-03-08", true), D("2026-03-09", true), D("2026-03-10", true), D("2026-03-11", true), D("2026-03-12", true), D("2026-03-13", true), D("2026-03-14", true), D("2026-03-15", true), D("2026-03-16", true), D("2026-03-17", true), D("2026-03-18", true), D("2026-03-19", true), D("2026-03-20", true), D("2026-03-21", true), D("2026-03-22", true), D("2026-03-23", true), D("2026-03-24", true), D("2026-03-25", true), D("2026-03-26", true), D("2026-03-27", true), D("2026-03-28", true), D("2026-03-29", true), D("2026-03-30", true), D("2026-03-31", true), D("2026-04-01", true), D("2026-04-02", true), D("2026-04-03", true), D("2026-04-04", true), D("2026-04-05", true), D("2026-04-06", true), D("2026-04-07", true), D("2026-04-08", true), D("2026-04-09", true), D("2026-04-10", true), D("2026-04-11", true), D("2026-04-12", true), D("2026-04-13", true), D("2026-04-14", true), D("2026-04-15", true), D("2026-04-16", true), D("2026-04-17", true), D("2026-04-18", true), D("2026-04-19", true), D("2026-04-20", true), D("2026-04-21", true), D("2026-04-22", true), D("2026-04-23", true), D("2026-04-24", true), D("2026-04-25", true), D("2026-04-26", true), D("2026-04-27", true), D("2026-04-28", true), D("2026-04-29", true), D("2026-04-30", true), D("2026-05-01", true), D("2026-05-02", true), D("2026-05-03", true), D("2026-05-04", true), D("2026-05-05", true), D("2026-05-06", true), D("2026-05-07", true), D("2026-05-08", true), D("2026-05-09", true), D("2026-05-10", true), D("2026-05-11", true), D("2026-05-12", true), D("2026-05-13", true), D("2026-05-14", true), D("2026-05-15", true), D("2026-05-16", true), D("2026-05-17", true), D("2026-05-18", true), D("2026-05-19", true), D("2026-05-20", true), D("2026-05-21", true), D("2026-05-22", true), D("2026-05-23", true), D("2026-05-24", true), D("2026-05-25", true), D("2026-05-26", true), D("2026-05-27", true), D("2026-05-28", true), D("2026-05-29", true), D("2026-05-30", true), D("2026-05-31", true), D("2026-06-01", true), D("2026-06-02", true), D("2026-06-03", true), D("2026-06-04", true), D("2026-06-05", true), D("2026-06-06", true), D("2026-06-07", true), D("2026-06-08", true), D("2026-06-09", true), D("2026-06-10", true), D("2026-06-11", true), D("2026-06-12", true), D("2026-06-13", true), D("2026-06-14", true), D("2026-06-15", true), D("2026-06-16", true), D("2026-06-17", true), D("2026-06-18", true), D("2026-06-19", true), D("2026-06-20", true), D("2026-06-21", true), D("2026-06-22", true), D("2026-06-23", true), D("2026-06-24", true), D("2026-06-25", true), D("2026-06-26", true), D("2026-06-27", true), D("2026-06-28", true), D("2026-06-29", true), D("2026-06-30", true), D("2026-07-01", true), D("2026-07-02", true), D("2026-07-03", true), D("2026-07-04", true), D("2026-07-05", true), D("2026-07-06", true), D("2026-07-07", true), D("2026-07-08", true), D("2026-07-09", true), D("2026-07-10", true), D("2026-07-11", true), D("2026-07-12", true), D("2026-07-13", true), D("2026-07-14", true), D("2026-07-15", true), D("2026-07-16", true), D("2026-07-17", true), D("2026-07-18", true), D("2026-07-19", true), D("2026-07-20", true), D("2026-07-21", true), D("2026-07-22", true), D("2026-07-23", true), D("2026-07-24", true), D("2026-07-25", true), D("2026-07-26", true), D("2026-07-27", true), D("2026-07-28", true), D("2026-07-29", true), D("2026-07-30", true), D("2026-07-31", true), D("2026-08-01", true), D("2026-08-02", true), D("2026-08-03", true), D("2026-08-04", true), D("2026-08-05", true), D("2026-08-06", true), D("2026-08-07", true), D("2026-08-08", true), D("2026-08-09", true), D("2026-08-10", true), D("2026-08-11", true), D("2026-08-12", true), D("2026-08-13", true), D("2026-08-14", true), D("2026-08-15", true), D("2026-08-16", true), D("2026-08-17", true), D("2026-08-18", true), D("2026-08-19", true), D("2026-08-20", true), D("2026-08-21", true), D("2026-08-22", true), D("2026-08-23", true), D("2026-08-24", true), D("2026-08-25", true), D("2026-08-26", true), D("2026-08-27", true), D("2026-08-28", true), D("2026-08-29", true), D("2026-08-30", true), D("2026-08-31", true), D("2026-09-01", true), D("2026-09-02", true), D("2026-09-03", true), D("2026-09-04", true), D("2026-09-05", true), D("2026-09-06", true), D("2026-09-07", true), D("2026-09-08", true), D("2026-09-09", true), D("2026-09-10", true), D("2026-09-11", true), D("2026-09-12", true), D("2026-09-13", true), D("2026-09-14", true), D("2026-09-15", true), D("2026-09-16", true), D("2026-09-17", true), D("2026-09-18", true), D("2026-09-19", true), D("2026-09-20", true), D("2026-09-21", true), D("2026-09-22", true), D("2026-09-23", true), D("2026-09-24", true), D("2026-09-25", true), D("2026-09-26", true), D("2026-09-27", true), D("2026-09-28", true), D("2026-09-29", true), D("2026-09-30", true), D("2026-10-01", true), D("2026-10-02", true), D("2026-10-03", true), D("2026-10-04", true), D("2026-10-05", true), D("2026-10-06", true), D("2026-10-07", true), D("2026-10-08", true), D("2026-10-09", true), D("2026-10-10", true), D("2026-10-11", true), D("2026-10-12", true), D("2026-10-13", true), D("2026-10-14", true), D("2026-10-15", true), D("2026-10-16", true), D("2026-10-17", true), D("2026-10-18", true), D("2026-10-19", true), D("2026-10-20", true), D("2026-10-21", true), D("2026-10-22", true), D("2026-10-23", true), D("2026-10-24", true), D("2026-10-25", true), D("2026-10-26", true), D("2026-10-27", true), D("2026-10-28", true), D("2026-10-29", true), D("2026-10-30", true), D("2026-10-31", true), D("2026-11-01", true), D("2026-11-02", true), D("2026-11-03", true), D("2026-11-04", true), D("2026-11-05", true), D("2026-11-06", true), D("2026-11-07", true), D("2026-11-08", true), D("2026-11-09", true), D("2026-11-10", true), D("2026-11-11", true), D("2026-11-12", true), D("2026-11-13", true), D("2026-11-14", true), D("2026-11-15", true), D("2026-11-16", true), D("2026-11-17", true), D("2026-11-18", true), D("2026-11-19", true), D("2026-11-20", true), D("2026-11-21", true), D("2026-11-22", true), D("2026-11-23", true), D("2026-11-24", true), D("2026-11-25", true), D("2026-11-26", true), D("2026-11-27", true), D("2026-11-28", true), D("2026-11-29", true), D("2026-11-30", true), D("2026-12-01", true), D("2026-12-02", true), D("2026-12-03", true), D("2026-12-04", true), D("2026-12-05", true), D("2026-12-06", true), D("2026-12-07", true), D("2026-12-08", true), D("2026-12-09", true), D("2026-12-10", true), D("2026-12-11", true), D("2026-12-12", true), D("2026-12-13", true), D("2026-12-14", true), D("2026-12-15", true), D("2026-12-16", true), D("2026-12-17", true), D("2026-12-18", true), D("2026-12-19", true), D("2026-12-20", true), D("2026-12-21", true), D("2026-12-22", true), D("2026-12-23", true), D("2026-12-24", true), D("2026-12-25", true), D("2026-12-26", true), D("2026-12-27", true), D("2026-12-28", true), D("2026-12-29", true), D("2026-12-30", true), D("2026-12-31", true))),
    "2027键混在2026里" to mapOf("2026" to listOf(D("2027-01-01", true))),
    "同一天既放假又上班" to mapOf("2026" to listOf(D("2026-10-10", true), D("2026-10-10", false))),
)

internal val pcHolidayFixtures: List<HolFix> = listOf(
    HolFix("国庆：9/30 → 跳过整个假期到 10/8", "Asia/Shanghai", "国庆2026", 1790730000000, "weekday", "day", 1, null, 1790733600000, Advanced(1791421200000L, false)),
    HolFix("调休：10/9 周五 → 10/10 周六上班", "Asia/Shanghai", "国庆2026", 1791507600000, "weekday", "day", 1, null, 1791511200000, Advanced(1791594000000L, false)),
    HolFix("调休周六之后 → 10/12 周一", "Asia/Shanghai", "国庆2026", 1791594000000, "weekday", "day", 1, null, 1791597600000, Advanced(1791766800000L, false)),
    HolFix("出差跨国庆：9/30 起、now 10/3 12:00 → 10/8", "Asia/Shanghai", "国庆2026", 1790730000000, "weekday", "day", 1, null, 1791000000000, Advanced(1791421200000L, false)),
    HolFix("没日历：9/30 → 10/1（老行为）", "Asia/Shanghai", null, 1790730000000, "weekday", "day", 1, null, 1790733600000, Advanced(1790816400000L, false)),
    HolFix("日历只管工作日：每天照推到 10/1", "Asia/Shanghai", "国庆2026", 1790730000000, "daily", "day", 1, null, 1790733600000, Advanced(1790816400000L, false)),
    HolFix("结束日落在假期里 → done，at 不动", "Asia/Shanghai", "国庆2026", 1790730000000, "weekday", "day", 1, 1791215999999, 1791000000000, Advanced(1790730000000L, true)),
    HolFix("60 天护栏：整年放假 → 停在第 60 天", "Asia/Shanghai", "2026整年放假", 1772326800000, "weekday", "day", 1, null, 1772330400000, Advanced(1777510800000L, false)),
    HolFix("跨年：次年没表 → 退回周六日（12/20 → 2027-01-01 周五）", "Asia/Shanghai", "2026整年放假", 1797728400000, "weekday", "day", 1, null, 1797732000000, Advanced(1798765200000L, false)),
    HolFix("次年空表 = 只认周六日（2027-01-01 周五 → 01-04 周一）", "Asia/Shanghai", "国庆2026+2027空表", 1798765200000, "weekday", "day", 1, null, 1798768800000, Advanced(1799024400000L, false)),
    HolFix("years 里没有的年份，off 键也不认", "Asia/Shanghai", "2027键混在2026里", 1798678800000, "weekday", "day", 1, null, 1798682400000, Advanced(1798765200000L, false)),
    HolFix("判定顺序：off 先于 work（10/9 → 跳过 10/10 → 10/12）", "Asia/Shanghai", "同一天既放假又上班", 1791507600000, "weekday", "day", 1, null, 1791511200000, Advanced(1791766800000L, false)),
    HolFix("日期键按提醒时区取（纽约的 9/30 → 10/8）", "America/New_York", "国庆2026", 1790773200000, "weekday", "day", 1, null, 1790776800000, Advanced(1791464400000L, false)),
    HolFix("日期键按提醒时区：纽约 9/30 21:00 → 纽约 10/8（上海那天已是 10/1）", "America/New_York", "国庆2026", 1790816400000, "weekday", "day", 1, null, 1790820000000, Advanced(1791507600000L, false)),
    HolFix("日期键按提醒时区：纽约 10/9 21:00（上海已是周六 10/10 调休）→ 纽约 10/10 周六", "America/New_York", "国庆2026", 1791594000000, "weekday", "day", 1, null, 1791597600000, Advanced(1791680400000L, false)),
)

internal val pcWeekFixtures: List<WeekFix> = listOf(
    WeekFix("一三五：周一过点 → 周三", "Asia/Shanghai", 1791156600000, listOf(0, 2, 4), null, 1791158400000, Advanced(1791329400000L, false)),
    WeekFix("一三五：周五过点 → 下周一", "Asia/Shanghai", 1791502200000, listOf(0, 2, 4), null, 1791504000000, Advanced(1791761400000L, false)),
    WeekFix("只选一天 = 每 7 天", "Asia/Shanghai", 1791162000000, listOf(0), null, 1791165600000, Advanced(1791766800000L, false)),
    WeekFix("空集合 = 老行为（at 那天的星期几）", "Asia/Shanghai", 1791162000000, listOf(), null, 1791165600000, Advanced(1791766800000L, false)),
    WeekFix("at 不在选中日里（数据不一致）：周四 → 下周一", "Asia/Shanghai", 1791421200000, listOf(0, 2), null, 1791424800000, Advanced(1791766800000L, false)),
    WeekFix("关机三周：快进后逐日走到周四", "Asia/Shanghai", 1789344000000, listOf(1, 3), null, 1791345600000, Advanced(1791417600000L, false)),
    WeekFix("1970 起（二、日）→ now 之后第一个", "Asia/Shanghai", 435600000, listOf(1, 6), null, 1791345600000, Advanced(1791680400000L, false)),
    WeekFix("下一个选中日越过结束 → done，at 不动", "Asia/Shanghai", 1791162000000, listOf(0, 4), 1791475140000, 1791165600000, Advanced(1791162000000L, true)),
    WeekFix("结束日当天那次照算", "Asia/Shanghai", 1791162000000, listOf(0, 4), 1791561599999, 1791165600000, Advanced(1791507600000L, false)),
    WeekFix("纽约周五晚、选了六日 → 纽约周六晚（上海已是周日）", "America/New_York", 1791594000000, listOf(5, 6), null, 1791597600000, Advanced(1791680400000L, false)),
    WeekFix("纽约跨拨快：周六 → 周一，墙上时刻不变", "America/New_York", 1772892000000, listOf(0, 5), null, 1772895600000, Advanced(1773061200000L, false)),
    WeekFix("纽约快进跨拨快：落在缺口里的 02:30 跳回 02:30", "America/New_York", 1772350200000, listOf(0, 6), null, 1775016000000, Advanced(1775370600000L, false)),
    WeekFix("七天全选 = 逐日", "Asia/Shanghai", 1791162000000, listOf(0, 1, 2, 3, 4, 5, 6), null, 1791345600000, Advanced(1791421200000L, false)),
    WeekFix("坏值被规整掉（7、-1）只剩周三", "Asia/Shanghai", 1791162000000, listOf(7, 2, -1), null, 1791165600000, Advanced(1791334800000L, false)),
)

internal val pcSnapFixtures: List<SnapFix> = listOf(
    SnapFix("at 在选中日里不动", "Asia/Shanghai", 1791334800000, listOf(2, 4), 1791334800000),
    SnapFix("周四改成一三五 → 周五", "Asia/Shanghai", 1791422100000, listOf(0, 2, 4), 1791508500000),
    SnapFix("周四只选周一 → 下周一", "Asia/Shanghai", 1791422100000, listOf(0), 1791767700000),
    SnapFix("空集合不动", "Asia/Shanghai", 1791422100000, listOf(), 1791422100000),
    SnapFix("纽约周日晚（上海已是周一）选了一四 → 纽约周一晚", "America/New_York", 1791766800000, listOf(0, 3), 1791853200000),
    SnapFix("纽约拨快那天的 02:30 挪到下一个选中日照旧 02:30", "America/New_York", 1772868600000, listOf(0), 1773037800000),
)
