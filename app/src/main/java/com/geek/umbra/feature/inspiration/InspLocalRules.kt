// 灵感本机版的纯判据（iOS: Features/Inspiration/InspLocalRules.swift，单机化 ②-c，拍板 G）：合并、排序、
// 临时 id、本机改补丁、换账号。与 PC electron/core/inspiration/manager.ts 的 mergeInsp / sortInsp 同口径。
// 纯 Kotlin，配 JUnit 用例（InspLocalRulesTest.kt）。
package com.geek.umbra.feature.inspiration

import com.geek.umbra.net.UmbraUrl

object InspLocalRules {
    val statuses = setOf("open", "done", "archived")

    /**
     * 离线新建的条目服务端还没给整数 id：给一个**稳定的负数**当身份，同步回来换成真 id。
     * FNV-1a 32 位取负（与 iOS / PC 逐位相同：Int 乘法自然回绕即 UInt32 的 &*）。
     */
    fun tempId(uid: String): Long {
        var h = 0x811C9DC5.toInt()
        for (b in uid.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toInt() and 0xFF)
            h *= 16_777_619
        }
        return -((h and 0x7fff_ffff) or 1).toLong()
    }

    /**
     * 合并（同提醒 / 记账）：本机 dirty 且更新 → 留本机正文，但整数 id / 整理 / 调研结果照收；
     * 本机删得更新 → 不加回；远端墓碑删本机（本机 dirty 且更新的除外）。
     */
    fun merge(local: List<Inspiration>, items: List<Inspiration>, tombs: List<InspTomb>, pendingTombs: List<InspTomb> = emptyList()): List<Inspiration> {
        val byKey = LinkedHashMap<String, Inspiration>()
        val order = mutableListOf<String>()
        for (r in local) { if (!byKey.containsKey(r.key)) order += r.key; byKey[r.key] = r }
        val myDeletes = HashMap<String, Long>()
        for (t in pendingTombs) myDeletes[t.id] = maxOf(myDeletes[t.id] ?: Long.MIN_VALUE, t.deletedAtMs)
        for (s in items) {
            val sUp = s.updated_at_ms ?: 0
            val mine = byKey[s.key]
            if (mine != null && mine.dirty == true && (mine.updated_at_ms ?: 0) > sUp) {
                byKey[s.key] = mine.copy(
                    id = if (s.id > 0) s.id else mine.id,
                    organize_status = s.organize_status, research = s.research,
                    research_status = s.research_status, research_at = s.research_at,
                )
                continue
            }
            val delAt = myDeletes[s.key]
            if (delAt != null && delAt > sUp) continue
            if (!byKey.containsKey(s.key)) order += s.key
            byKey[s.key] = s.copy(dirty = null)
        }
        for (t in tombs) {
            val mine = byKey[t.id]
            if (mine != null && mine.dirty == true && (mine.updated_at_ms ?: 0) > t.deletedAtMs) continue
            byKey.remove(t.id)
        }
        return order.mapNotNull { byKey[it] }
    }

    /** 新 → 旧：服务端按整数 id 倒序；离线新建的（id ≤ 0）排最前，按改动时间倒序。 */
    fun sorted(list: List<Inspiration>): List<Inspiration> = list.sortedWith { a, b ->
        val ai = if (a.id > 0) a.id else Long.MAX_VALUE
        val bi = if (b.id > 0) b.id else Long.MAX_VALUE
        if (ai != bi) bi.compareTo(ai) else (b.updated_at_ms ?: 0).compareTo(a.updated_at_ms ?: 0)
    }

    fun filtered(list: List<Inspiration>, status: String?): List<Inspiration> {
        if (status == null || status !in statuses) return list
        return list.filter { it.status == status }
    }

    /** 本机改一条：只改给了的；空 raw 不覆盖；盖戳、标 dirty。 */
    fun applying(i: Inspiration, patch: InspPatch, nowMs: Long): Inspiration {
        var m = i
        patch.raw?.trim()?.takeIf { it.isNotEmpty() }?.let { m = m.copy(raw = it) }
        patch.title?.let { m = m.copy(title = it.trim()) }
        patch.summary?.let { m = m.copy(summary = it.trim()) }
        patch.tags?.let { t -> m = m.copy(tags = t.map { it.trim() }.filter { it.isNotEmpty() }) }
        patch.status?.takeIf { it in statuses }?.let { m = m.copy(status = it) }
        return m.copy(updated_at_ms = nowMs, dirty = true)
    }

    /** 离线新建一条。没填标题 → organize_status = pending（推上去后服务端补整理）。 */
    fun fresh(uid: String, raw: String, title: String, summary: String, tags: List<String>, research: Boolean, nowMs: Long, iso: String): Inspiration {
        val t = title.trim()
        return Inspiration(
            id = tempId(uid), raw = raw.trim(), title = t, summary = summary.trim(),
            tags = tags.map { it.trim() }.filter { it.isNotEmpty() }, status = "open",
            // iOS 写 "ios"；Android 报自己的端标识（服务端认不认见 docs/Android开发文档.md §六，改 UmbraUrl.CLIENT_SRC 一处）
            source_channel = UmbraUrl.CLIENT_SRC, created_at = iso, updated_at = iso,
            organize_status = if (t.isEmpty()) "pending" else "done",
            research = "", research_status = if (research) "queued" else "idle", research_at = "",
            uid = uid, updated_at_ms = nowMs, atts = null, dirty = true,
        )
    }

    /** 还没推上去的条数（dirty + 墓碑）—— 换账号时问「带过去 / 丢掉」用。 */
    fun pendingCount(items: List<Inspiration>, tombs: List<InspTomb>): Int = items.count { it.dirty == true } + tombs.size

    /**
     * 换账号之后本机留下什么（umbra-ios#89，§5.5.2）：已同步过的清掉；carry 才留下没推上去的，
     * 整数 id 换回按 uid 算的临时 id —— 留着旧 id 的话「帮我查查」会拿它去问新账号的服务端。墓碑由调用方一律丢。
     */
    fun rebaseForAccount(carry: Boolean, items: List<Inspiration>): List<Inspiration> {
        if (!carry) return emptyList()
        return items.filter { it.dirty == true }.map { it.copy(id = tempId(it.key)) }
    }
}
