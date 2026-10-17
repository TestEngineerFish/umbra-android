// 常用语同步「这份回包能不能落地」的纯判据 + 那一圈重推的循环（umbra-ios#40），以及两个时间戳怎么盖（umbra-pc#83）。
// 对应 iOS Features/Me/PhraseSyncRules.swift。纯 Kotlin。
//
// 原来的坑：sync 发出去的是发出那一刻的本地全量，回包回来后无条件整份替换本地 —— 往返途中新建的那条永久消失、
// 删掉的那条复活、编辑回退。现在挂一个**本地版本号**：每改一次本地 +1，发之前记一下、回来再比，变了就丢回包重推。
// 版本号挂在「改本地」这件事上，不挂在「调了谁」上（布尔 resyncPending 依赖每次改动都调 sync 这个没人钉住的巧合）。
package com.geek.umbra.feature.phrases

object PhraseSyncRules {
    /** 最多推几轮。到了上限**不落地**（落地等于拿过期全量冲本地），停在本地、留到下一次 sync。 */
    const val maxRounds = 4

    enum class Verdict { Apply, Resend, Hold }

    /** 回包能不能落地：途中本地没动过 → Apply；动过 → Resend；动过且推满 → Hold。 */
    fun verdict(revisionAtSend: Int, revisionNow: Int, round: Int): Verdict {
        if (revisionNow == revisionAtSend) return Verdict.Apply
        return if (round >= maxRounds) Verdict.Hold else Verdict.Resend
    }

    enum class Outcome { Applied, Failed, Deferred }

    /**
     * 推 → 判 → 落地 / 重推，直到某一轮回包时本地没再动过。循环写在这里，用例跑的就是 App 里那一份。
     * ⚠️ [apply] 在判完 Apply 之后**同步**调用，中间没有挂起点 —— 判和落之间不能再有缝。
     * [push] 每轮现读本地全量（不用发起时的快照）；失败回 null。iOS: PhraseSyncRules.swift:95
     */
    suspend fun <B> run(revision: () -> Int, push: suspend () -> B?, apply: (B) -> Unit): Outcome {
        var round = 0
        while (true) {
            round += 1
            val sent = revision()
            val merged = push() ?: return Outcome.Failed
            when (verdict(sent, revision(), round)) {
                Verdict.Apply -> { apply(merged); return Outcome.Applied }
                Verdict.Resend -> continue
                Verdict.Hold -> return Outcome.Deferred
            }
        }
    }

    /**
     * 改本地之后给这一批盖戳：和改之前那一份逐条比，**内容和位置分开盖**（umbra-pc#83）。
     * - 新加的：两个戳都是 now；
     * - name / content / keyword 变了 → updatedAt = now；
     * - order 变了 → 只盖 orderUpdatedAt = now；
     * - 都没变：原样（「0 当没记过」照 PC 的 `||`：updatedAt 为 0 的老条目补成 now）。
     * ⚠️ 没挪位置的那条也要把 orderUpdatedAt **写实**成旧的生效值，不能留 null（会落回刚盖成 now 的 updatedAt）。
     * iOS: PhraseSyncRules.swift:150
     */
    fun stamp(next: List<Phrase>, prev: List<Phrase>, now: Long): List<Phrase> {
        val before = LinkedHashMap<String, Phrase>()
        for (p in prev) if (!before.containsKey(p.id)) before[p.id] = p
        return next.map { p ->
            val b = before[p.id] ?: return@map p.copy(updatedAt = now, orderUpdatedAt = now)
            val sameContent = b.name == p.name && b.content == p.content && (b.keyword ?: "") == (p.keyword ?: "")
            val oldContentTs = if (b.updatedAt != 0L) b.updatedAt else now
            val oldOrderTs = b.orderUpdatedAt?.takeIf { it != 0L } ?: oldContentTs
            p.copy(
                updatedAt = if (sameContent) oldContentTs else now,
                orderUpdatedAt = if (b.order == p.order) oldOrderTs else now,
            )
        }
    }
}
