// 保险箱界面层的几条纯判据：自动上锁（VaultAutoLockRules.swift）、自动保存那一个槽（VaultSaveSlot.swift）、
// 自动锁定条幅（VaultLockNote.swift）、记录图标取值（VaultIconRef.swift）、敏感剪贴板（SensitiveClip.swift）、
// 编辑页逐字同源的几句（VaultRecordCopy.swift）、安全体检（VaultKit.swift `UmbraVaultAudit`）。
// 纯 Kotlin，配 JUnit 用例（输入输出照抄 iOS Tools/swifttests 对应文件）。
package com.geek.umbra.feature.vault

// MARK: - 自动上锁

/**
 * 「N 分钟没用就自动上锁」的判据（umbra-ios#70）。计时跟着**「保险箱开着」这个状态**走，不跟任何一个页面走；
 * 时间一律墙钟：进后台时进程可能被冻结、计时协程不走，但墙钟在走 —— 回前台拿「现在 − 最后一次动作」比。
 */
object VaultAutoLockRules {
    /** 墙钟往回拨多少以内不算数（NTP 校时）。超过按上锁处理 —— 判不准时站在保守那一侧。 */
    const val CLOCK_SKEW_TOLERANCE_MS = 60_000L

    /** `minutes <= 0` =「不自动锁定」→ 永不到点（原来 0 分钟等于一解开就锁，正好说反）。 */
    fun expired(lastActivityMs: Long, nowMs: Long, minutes: Int): Boolean {
        if (minutes <= 0) return false
        val idle = nowMs - lastActivityMs
        if (idle < -CLOCK_SKEW_TOLERANCE_MS) return true
        return idle >= minutes * 60_000L
    }

    /**
     * 上锁那一刻一条导航栈要退成什么样：找到第一张保险箱子页 —— 是首页就留到首页为止，
     * 不是首页（「我 › 回收站」）就整段退掉。栈里没有保险箱子页原样不动。
     */
    fun <R> retreat(path: List<R>, isVault: (R) -> Boolean, isHome: (R) -> Boolean): List<R> {
        val i = path.indexOfFirst(isVault)
        if (i < 0) return path
        return if (isHome(path[i])) path.subList(0, i + 1) else path.subList(0, i)
    }
}

/** 自动上锁的那块表。**只认「保险箱开着」这个状态和用户的动作，不认页面。** 值语义的类，谁来问由会话管。 */
class VaultIdleClock(nowMs: Long) {
    var open = false; private set
    var lastActivityMs: Long = nowMs; private set

    /** 开 / 关。**只有「关 → 开」那一刻清零**（那是一次真的解锁）。 */
    fun setOpen(isOpen: Boolean, nowMs: Long) {
        if (isOpen && !open) lastActivityMs = nowMs
        open = isOpen
    }

    /** 用户在保险箱里动了一下。 */
    fun touch(nowMs: Long) { lastActivityMs = nowMs }

    fun expired(nowMs: Long, minutes: Int): Boolean = open && VaultAutoLockRules.expired(lastActivityMs, nowMs, minutes)
}

// MARK: - 自动保存那一个槽（设计侧 214 §三⒠）

enum class VaultSaveState { Saved, Saving, Dirty, Fail }
enum class VaultSlotTone { Muted, Ok, Fail }
data class VaultSlotAction(val label: String, val danger: Boolean)
data class VaultSlot(val text: String, val tone: VaultSlotTone, val action: VaultSlotAction?)

object VaultSaveSlot {
    /** 停手多久落一笔（与 PC `AUTOSAVE_IDLE_MS` 同值）。 */
    const val IDLE_MS = 900L

    /** 加密库那句失败的后半段 —— 把「看起来像整个库出事」的信号缩回它真正的范围。与 PC 逐字同源。 */
    const val FAIL_TEXT = "没保存上 · 这台电脑上的改动还在，库没被动过"

    fun line(state: VaultSaveState, savedAtMs: Long?, nowMs: Long): VaultSlot = when (state) {
        VaultSaveState.Saving -> VaultSlot("正在保存…", VaultSlotTone.Muted, null)
        VaultSaveState.Dirty -> VaultSlot("改了还没写进去", VaultSlotTone.Muted, VaultSlotAction("现在就存", false))
        VaultSaveState.Fail -> VaultSlot(FAIL_TEXT, VaultSlotTone.Fail, VaultSlotAction("重试", true))
        VaultSaveState.Saved -> VaultSlot("已保存 · ${ago(savedAtMs, nowMs)}", VaultSlotTone.Ok, null)
    }

    /** 「刚刚」要自己会老。 */
    fun ago(atMs: Long?, nowMs: Long): String {
        if (atMs == null) return "刚刚"
        val s = ((nowMs - atMs) / 1000).toInt()
        if (s < 60) return "刚刚"
        if (s < 3600) return "${s / 60} 分钟前"
        return "${s / 3600} 小时前"
    }

    /** 离开之前要不要先落一笔。`Fail` 也要再试一次 —— 在「没保存上」旁边退出去的人，要的是它存上。 */
    fun needsFlushBeforeLeave(state: VaultSaveState): Boolean = state == VaultSaveState.Dirty || state == VaultSaveState.Fail
}

// MARK: - 自动锁定那条条幅（设计侧 214 §一、215 §三⒝）

object VaultLockNote {
    /** 与 PC **逐字同源**的那半句。 */
    const val SHARED = "分钟后保险箱会自己上锁 —— 再进来要重新输主密码。"

    /** 末句指的入口（两端只差这一句）。 */
    const val WHERE = "这个时间在「保险箱设置」里可以改。"

    fun text(minutes: Int): String = "没用保险箱的话，$minutes $SHARED$WHERE"

    const val IDLE_PHRASE = "没用保险箱"

    /** 锁屏那一句（自动锁上之后），与 PC `lockedLine` 逐字。 */
    fun lockedLine(minutes: Int): String = "已自动锁定 · $minutes 分钟没用保险箱"

    /** 锁屏标题。自动锁上与手动锁上同一个标题。 */
    const val LOCKED_TITLE = "保险箱已上锁"

    const val SETTINGS_FOOTER = "切到后台时内容立刻遮盖；超过设定时长${IDLE_PHRASE}就要重新验证。"
    const val PICKER_TITLE = "多久${IDLE_PHRASE}就自动锁定"

    /** `minutes == 0` 时整条不出（「不自动锁定」是一个设置值，不是一条消息）；「×」与「不再提示」是两件事。 */
    fun shows(minutes: Int, closed: Boolean, silenced: Boolean): Boolean {
        if (minutes <= 0) return false
        return !closed && !silenced
    }
}

// MARK: - 记录图标取值

/**
 * `VItem.icon` → 画什么（iOS VaultIconRef.swift）：`data:image/…;base64,…` → 图；`builtin:名` / 光秃秃的 `名` → 内置图标；
 * `custom:` / 网址 / 查不到的名字 → 首字。宁可首字也不画一个意思不对的图标。
 */
sealed interface VaultIconRef {
    data class Image(val base64: String) : VaultIconRef
    data class Builtin(val d: String) : VaultIconRef
    data object Monogram : VaultIconRef

    companion object {
        /** iOS 图标表里有、但 PC `VAULT_ICONS` 没有的两个老名字。 */
        private val legacy = mapOf(
            "user" to com.geek.umbra.designsystem.UmbraIconPaths.user,
            "star" to com.geek.umbra.designsystem.UmbraIconPaths.star,
        )

        fun resolve(raw: String?): VaultIconRef {
            val v = (raw ?: "").trim()
            if (v.isEmpty()) return Monogram
            if (v.startsWith("data:image/")) {
                val comma = v.indexOf(',')
                if (comma < 0 || !v.substring(0, comma).endsWith(";base64")) return Monogram
                val body = v.substring(comma + 1)
                return if (body.isEmpty()) Monogram else Image(body)
            }
            if (v.startsWith("custom:") || v.startsWith("http://") || v.startsWith("https://")) return Monogram
            val name = if (v.startsWith("builtin:")) v.removePrefix("builtin:") else v
            val d = VaultIconPaths.named(name) ?: legacy[name] ?: return Monogram
            return Builtin(d)
        }
    }
}

// MARK: - 敏感剪贴板

/**
 * 「剪贴板里现在是不是保险箱放进去的那份敏感值」的判据（iOS SensitiveClip.swift）。
 * Android 没有 changeCount，换成**剪贴板那份内容的时间戳**（`ClipDescription.getTimestamp()`，API 26+）：
 * 放敏感值那一刻记下时间戳；之后时间戳没变 = 没人再写过 = 里面还是那份。**不读剪贴板内容来比**。
 * ⚠️ 判不准时一律按「是敏感值」（同 PC / iOS 取向：宁可 {clipboard} 空着，也不把密码带出去）。
 */
object SensitiveClip {
    const val PREFS_KEY = "umbra.clipboard.sensitiveChangeCount"

    /** 记录读不出来 —— 判不准，调用方按敏感处理。 */
    class Unjudgeable : Exception("malformedRecord")

    /**
     * - `record` 为 null：从没复制过敏感值 → 不是；
     * - 记录不是数字 → 抛（判不准）；
     * - 相同 → 是；不同 → 不是。
     */
    fun holds(record: Any?, current: Long): Boolean {
        if (record == null) return false
        val n = when (record) {
            is Long -> record
            is Int -> record.toLong()
            else -> throw Unjudgeable()
        }
        return n == current
    }

    /** Android 上「别家标了敏感」的那一档：剪贴板描述里带 `EXTRA_IS_SENSITIVE` 的（API 33+ 约定）。 */
    fun sensitive(ownHeld: Boolean, foreignMarked: Boolean): Boolean = ownHeld || foreignMarked

    /** 输入框长按菜单：拷贝 / 剪切只在改道时给；「共享」一律不给。 */
    fun menuActionAllowed(action: String, rerouted: Boolean): Boolean {
        if (action == "copy:" || action == "cut:") return rerouted
        if (action.lowercase().contains("share")) return false
        return true
    }
}

// MARK: - 编辑页两端逐字同源的几句

object VaultRecordCopy {
    const val DELETE_BODY = "删除后会移入回收站，保留 30 天。所有已同步的设备上都会一起进回收站。"
    const val DELETE_LABEL = "删除记录"
    const val TITLE_PLACEHOLDER = "未命名记录"
    val BLOCK_MENU = listOf("上移", "下移", "删除这个控件")
}

// MARK: - 安全体检（全部在本机算，不上传、不联网）

/** 体检结果。颜色由界面按 [Group.tone] 取（这里不碰 Compose）。 */
class VaultAudit(items: List<VItem>, nowMs: Long = System.currentTimeMillis()) {
    enum class Tone { Danger, Warning, Muted }
    data class Finding(val id: String, val item: VItem, val why: String)
    data class Group(val id: String, val name: String, val icon: String, val tone: Tone, val findings: List<Finding>)

    val groups: List<Group>
    val total: Int
    val score: Int

    init {
        // 先把每条记录的密码抽出来：账号（统一走 accounts）的密码，旧记录还看密文控件的 value。
        val pwOf = LinkedHashMap<String, String>()
        val has2FA = HashSet<String>()
        for (it in items) {
            for (acc in it.accounts) {
                if (acc.pwd.isNotEmpty() && pwOf[it.id] == null) pwOf[it.id] = acc.pwd
                if (acc.hasOtp) has2FA += it.id
            }
            if (it.isCanvas) continue
            for (b in it.blocks) if (b.type == "secret") {
                val p = b.data["value"].vString
                if (p.isNotEmpty() && pwOf[it.id] == null) pwOf[it.id] = p
            }
        }

        // 重复：同一个密码被两条以上记录用了。
        val byPassword = LinkedHashMap<String, MutableList<VItem>>()
        for (it in items) { val p = pwOf[it.id]; if (!p.isNullOrEmpty()) byPassword.getOrPut(p) { mutableListOf() } += it }
        val dup = mutableListOf<Finding>()
        for (group in byPassword.values) if (group.size > 1) for (it in group) {
            val others = group.filter { o -> o.id != it.id }.map { o -> o.title }
            val names = others.take(2).joinToString("、")
            val why = if (others.size > 2) "和另外 ${others.size} 条用了同一个密码" else "与「$names」重复"
            dup += Finding(it.id + ".dup", it, why)
        }

        // 弱：强度 0。够 8 位还判弱，说出原因（位数按用户眼里的「几位」—— 这里取 UTF-16 长度近似字形簇）。
        val weak = items.mapNotNull { it ->
            val p = pwOf[it.id]
            if (p.isNullOrEmpty() || !PasswordStrength.of(p).isWeak) return@mapNotNull null
            val count = graphemeCount(p)
            val why = if (p.codePointCount(0, p.length) >= 8)
                "$count 位，但多是常见词 / 日期 / 键盘序列 / 重复，强度 ${PasswordStrength.label(0)}"
            else "$count 位，强度 ${PasswordStrength.label(0)}"
            Finding(it.id + ".weak", it, why)
        }

        // 没开两步验证：只看有账号控件的记录。
        val no2fa = items.mapNotNull { it ->
            if (it.accounts.isEmpty() || it.id in has2FA) null else Finding(it.id + ".2fa", it, "支持两步验证但没开")
        }

        // 长期未更新：90 天以上没动过，且它是有密码的记录。离谱的值（远古 / 远未来）装不进整型时：远古算「很久」、远未来算「刚动过」。
        val stale = items.mapNotNull { it ->
            if (pwOf[it.id] == null) return@mapNotNull null
            val rawDays = (nowMs - it.updatedAt) / 86_400_000
            val days = VNum.int(rawDays) ?: (if (rawDays > 0) Int.MAX_VALUE else 0)
            if (days < 90) null else Finding(it.id + ".old", it, "$days 天没动过")
        }

        val out = mutableListOf<Group>()
        val P = com.geek.umbra.designsystem.UmbraIconPaths
        if (dup.isNotEmpty()) out += Group("dup", "重复使用", P.xCircle, Tone.Danger, dup)
        if (weak.isNotEmpty()) out += Group("weak", "弱密码", P.alertTriangle, Tone.Danger, weak)
        if (no2fa.isNotEmpty()) out += Group("2fa", "没开两步验证", P.shield, Tone.Warning, no2fa)
        if (stale.isNotEmpty()) out += Group("old", "长期未更新", P.clock, Tone.Muted, stale)
        groups = out

        // 分数：按「有问题的记录占比」扣分，重复和弱各扣得更狠。公式没有权威依据，所以界面上不写「安全等级 A」。
        val flagged = dup.map { it.item.id }.toSet() + weak.map { it.item.id }
        val soft = no2fa.map { it.item.id }.toSet() + stale.map { it.item.id }
        total = dup.size + weak.size + no2fa.size + stale.size
        score = if (items.isEmpty()) 100 else {
            val hard = flagged.size.toDouble() / items.size
            val mild = (soft - flagged).size.toDouble() / items.size
            (100 - hard * 60 - mild * 25).toInt().coerceIn(0, 100)
        }
    }

    companion object {
        /** 字形簇数（iOS `String.count`）：用 BreakIterator 数，emoji / 组合符号按一个算。 */
        fun graphemeCount(s: String): Int {
            val it = java.text.BreakIterator.getCharacterInstance()
            it.setText(s)
            var n = 0
            while (it.next() != java.text.BreakIterator.DONE) n++
            return n
        }
    }
}
