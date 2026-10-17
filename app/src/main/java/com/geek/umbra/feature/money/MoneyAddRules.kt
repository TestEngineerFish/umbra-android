// 记一笔那一屏的纯判据 + 字面（设计侧批次 219）—— 照抄 iOS Features/Money/MoneyAddRules.swift。纯 Kotlin。
package com.geek.umbra.feature.money

import com.geek.umbra.net.Ledger

/**
 * 记一笔「没存上」那张三段式。
 * 第二段分新增 / 编辑两档：编辑已有的一笔时「还没进账本」是假话 —— 它早在账本里了，没存上的只是这次的改动。
 * ⚠️ 不许写「本地已排队 / 会自动重试」：一期没有离线队列。iOS: MoneyAddRules.swift:24
 */
object MoneySaveFail {
    /** 第一段：发生了什么。新增 / 编辑两档相同。 */
    const val TITLE = "没存上：服务端没有响应"

    /** 第二段：账本里现在是什么状态 + 下一步。 */
    fun reason(editing: Boolean): String =
        if (editing) "账本里还是改之前那一版。改过的内容都还在，联网后再点一次就行。"
        else "这一笔还没进账本。填好的内容都还在，联网后再点一次就行。"

    /** 第三段那颗钮。⚠️ 与 PC（念「重试」）不同、是有意的：它离它重试的动作隔着整张表单（219 §四4 豁免）。 */
    const val ACTION_LABEL = "重试保存"
}

/**
 * PUT 一笔账没成的分档（umbra#102 / 服务端 323fdc0）。
 * 这一笔被另一台挪去了另一个账本：服务端回 409 state_conflict + state:"other_ledger"（+ ledger）、不写。
 * 这一档重试永远没用，所以不能落进 MoneySaveFail 那三段（第三段是「重试保存」）。
 * ⚠️ 判据要求 409 + state_conflict + other_ledger 三样都对上。iOS: MoneyAddRules.swift:61
 */
sealed interface MoneyPutFail {
    /** 这一笔已在别的账本（服务端没说 / 认不出时为 null —— 这是一档正常形态，不是解析失败）。 */
    data class OtherLedger(val to: Ledger?) : MoneyPutFail

    /** 其余一切（断网、5xx、解不出）—— 走「没存上」三段，给重试。 */
    data object Unreachable : MoneyPutFail

    companion object {
        const val CONFLICT_CODE = "state_conflict"
        const val OTHER_LEDGER_STATE = "other_ledger"

        /** 响应 → 分档。status 0 表示没拿到响应。code 由调用方先看头、再看体。 */
        fun classify(status: Int, code: String?, state: String?, ledger: String?): MoneyPutFail {
            if (status != 409 || code != CONFLICT_CODE || state != OTHER_LEDGER_STATE) return Unreachable
            // ⚠️ 不能直接 Ledger.of(ledger)：它把认不出的名字兜底成 Personal，
            // 而 iOS 是 UmbraLedger(rawValue:) 认不出回 nil —— 认不出的账本名不拿去冒充「生活账本」。
            return OtherLedger(Ledger.entries.firstOrNull { it.wire == ledger })
        }

        /** 撞上这一档时那一句（吐司）。说清改动没存上；不说「再试一次」：重试永远是同一个 409。 */
        fun movedAwayText(to: Ledger?): String =
            "这笔已被挪到${to?.displayName ?: "另一个账本"}，这次的改动没存上"
    }
}

/**
 * 记一笔这张表此刻代表哪一笔（money-saved-entry-overwrite-in-new-mode）。
 * 新建存上之后图没传上、人留在本页：从这一刻起这张表就是账本里那一笔（savedId），再点保存 PUT 的是它。
 * 所以「是不是已有的一笔」不能只看路由带没带 id。iOS: MoneyAddRules.swift:99
 */
object MoneyAddTarget {
    /** 路由带 id 进来的编辑态，或新建后已经存上过一次。 */
    fun isExisting(routeId: String?, savedId: String?): Boolean = routeId != null || savedId != null

    /** 保存写到哪一笔。null = 新建（store 生成 id）。路由的 id 优先：编辑态从头到尾只改那一笔。 */
    fun saveId(routeId: String?, savedId: String?): String? = routeId ?: savedId

    /** 「再记一笔」出不出：已有的一笔没有「接着记」（那一支会清表单、而保存仍写回那个 id）。 */
    fun allowsAgain(routeId: String?, savedId: String?): Boolean = !isExisting(routeId, savedId)

    /** 保存后挂不挂扫描出的发票 PDF：只有这次保存新建了这一笔时才挂，否则同一张票挂两份。 */
    fun attachesScanOnSave(routeId: String?, savedId: String?): Boolean = !isExisting(routeId, savedId)

    /** 存上之后能不能离开这张表：图和发票 PDF 都落地了才行（原来只看图，PDF 挂失败照样退屏，票就丢了）。 */
    fun settled(photoFails: Int, invoiceAttached: Boolean): Boolean = photoFails == 0 && invoiceAttached
}
