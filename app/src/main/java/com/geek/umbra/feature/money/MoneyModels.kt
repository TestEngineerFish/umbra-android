// 记账的线格式 DTO —— 照抄 iOS Features/Money/MoneyModels.swift（+ MoneySync.swift 里界面侧那几个）。
//
// 字段名照服务端 JSON（snake_case，拍板 D2），不写 @SerialName：改字段时少一处能漏。
// **纯 Kotlin，不 import android.\***：纯判据（MoneyLocalRules 等）拿它们当输入，由 JUnit 真跑。
//
// 金额一律**整数分**，类型用 Long（iOS 的 Int 是 64 位）：单笔上限 999_999_999 放得进 Int，
// 但合计 / 趋势是很多笔相加，用 Int 会在一个攒了几年的账本上悄悄溢出。
//
// 可选字段一律给 null 默认值：UmbraJson 是 `explicitNulls = false`，null 在上行时整个省掉 ——
// 等价于 iOS synthesized Codable 的 encodeIfPresent。**生活账本那一路因此一个经营字段都不会传上去。**
package com.geek.umbra.feature.money

import kotlinx.serialization.Serializable

/** 一个分类。slug 是稳定标识**永不变**（流水里存的是它），name 可改。iOS: MoneyModels.swift:10 */
@Serializable
data class MoneyCatDTO(
    val slug: String = "",
    val name: String = "",
    /** expense / income */
    val direction: String = "expense",
    /** 0 = 无色槽（图表里中性灰），1–7 / 9 / 10 彩色 */
    val slot: Int = 0,
    val seq: Int = 0,
    val enabled: Boolean = true,
    /** other / other_in：兜底分类，不可停用 */
    val locked: Boolean = false,
    /** 图标**语义名**（gift / pet / …）。'' 或缺失 = 没挑过，按 slug 兜底。 */
    val icon: String? = null,
    /** 二级分类。可选是给老服务端留的余量 —— 字段缺了整个分类列表不该解不开。 */
    val subs: List<String>? = null,
    /** 只在这次真的换了槽时才出现在 PATCH 响应里：「字段不出现 = 没发生」，别补默认值。 */
    val slot_was: Int? = null,
    val slot_taken_by: String? = null,
    /** 经营账本科目随行带的三个字段。⚠️ 判据取服务端给的 readonly，不取「当前账本是不是 business」。 */
    val readonly: Boolean? = null,
    val deduct_tier: String? = null,
    /** 那句给人看的话。⚠️ 由服务端给，端上不许自己拼 —— 税的口径只许有一个出处。 */
    val deduct_label: String? = null,
    /** 同步字段（单机化 ②-a）。merged_into 非空 = 墓碑。 */
    val merged_into: String? = null,
    val updated_at_ms: Long? = null,
    val subs_updated_at_ms: Long? = null,
    /** 本机改过还没推上去（只在本机缓存里有意义）。 */
    val dirty: Boolean? = null,
) {
    val subList: List<String> get() = subs ?: emptyList()
    /** ⚠️ 字段缺失（老服务端 / 生活账本）一律**不是**只读。 */
    val isReadonly: Boolean get() = readonly ?: false
    /** 服务端没给就是 full（扣得最多那一档）——要跟着 deduct_label 为空时不出任何口径句一起读。 */
    val tier: DeductTier get() = DeductTier.of(deduct_tier) ?: DeductTier.Full
}

/**
 * 一张账单附件。文件本体在服务端（GET /files/{file_id}）或本机暂存（local:…），这里只有引用。
 * `kind == "invoice"` = 发票 PDF（不占四张）。⚠️ 老服务端不回 kind，判断一律写 `kind == "invoice"`。
 */
@Serializable
data class MoneyAttDTO(
    val file_id: String = "",
    val label: String = "",
    /** 截图记账的**原图**：一直留着，不能删。 */
    val origin: Boolean = false,
    val kind: String? = null,
)

/** 一笔流水。iOS: MoneyModels.swift:83 */
@Serializable
data class MoneyEntryDTO(
    /** 客户端生成（离线要能先记后同步） */
    val id: String = "",
    val cents: Long = 0,
    /** expense / income */
    val direction: String = "expense",
    /** 分类 slug */
    val cat: String = "",
    /** 二级，中文字符串不是 slug，可空 */
    val sub: String = "",
    /** 商家/备注（拍板 D1：一个字段） */
    val merchant: String = "",
    val at_ms: Long = 0,
    val tz_offset_min: Int = 0,
    /** 服务端按 at_ms + tz_offset_min 算的本地月 */
    val ym: String = "",
    /** manual / shot / import / chat / recur */
    val src: String = "manual",
    val rule_id: String = "",
    val batch_id: String = "",
    val order_no: String = "",
    val updated_at_ms: Long = 0,
    val deleted: Boolean = false,
    /** 附件。null 在上行时整个省掉：服务端不会动这笔已有的附件。 */
    val atts: List<MoneyAttDTO>? = null,
    /** 经营账本那七列。⚠️ **只在 ledger=business 的行上出现**，生活账本的行上它们不该长出来。 */
    val invoice_no: String? = null,
    val invoice_kind: String? = null,
    val receipt_state: String? = null,
    val tax_rate_bp: Int? = null,
    val net_cents: Long? = null,
    val deduct_tier: String? = null,
    val is_asset: Boolean? = null,
    /** 本机改过、还没成功推给服务端。服务端不认这个键。 */
    val dirty: Boolean? = null,
) {
    val attList: List<MoneyAttDTO> get() = atts ?: emptyList()
    /** ⚠️ 只给表单一个起始值；判「不进成本」走 ReceiptState.notCost(raw, income)。 */
    val receipt: ReceiptState get() = ReceiptState.of(receipt_state) ?: ReceiptState.NoReceipt
    /** 这一行是不是经营账本的：判据取**字段在不在**，不取当前选的账本。 */
    val isBiz: Boolean get() = receipt_state != null
    val tier: DeductTier get() = DeductTier.of(deduct_tier) ?: DeductTier.Full
    val income: Boolean get() = direction == "income"
}

/** GET /money/entries 的合计（服务端按**整个筛选结果**算，不是按页）。 */
@Serializable
data class MoneyTotalsDTO(val count: Int = 0, val expense: Long = 0, val income: Long = 0)

@Serializable
data class MoneyEntriesDTO(val items: List<MoneyEntryDTO> = emptyList(), val totals: MoneyTotalsDTO = MoneyTotalsDTO())

/** GET /money/categories/slots。池按方向分。 */
@Serializable
data class MoneySlotPoolDTO(
    val colorSlots: List<Int> = emptyList(),
    val neutralSlot: Int = 0,
    val free: Map<String, List<Int>> = emptyMap(),
)

/** 一个分类挂着多少东西。三样全空才允许删。 */
@Serializable
data class MoneyCatUsageDTO(val entries: Int = 0, val trashed: Int = 0, val subs: Int = 0, val rules: Int = 0) {
    val isEmpty: Boolean get() = entries == 0 && trashed == 0 && subs == 0 && rules == 0
}

@Serializable
data class MoneyMergeDTO(val cats: List<MoneyCatDTO> = emptyList(), val moved: MoneyCatUsageDTO = MoneyCatUsageDTO())

/** PUT /money/entries/{id} 的响应。written=false 表示库里那份更新，界面要用回传的 entry 对齐。 */
@Serializable
data class MoneyPutDTO(val entry: MoneyEntryDTO = MoneyEntryDTO(), val written: Boolean = true)

@Serializable
data class MoneyCatStatDTO(val cat: String = "", val cents: Long = 0, val count: Int = 0)

@Serializable
data class MoneyTrendDTO(val ym: String = "", val cents: Long = 0)

/**
 * GET /money/stats。`prev_expense` 为 **null** 表示「上月没有记录，无法对比」，跟「上月花了 0」是两回事。
 * cost / profit / not_cost 只在经营账本那一路出现，**不在端上算**。
 */
@Serializable
data class MoneyStatsDTO(
    val ym: String = "",
    val expense: Long = 0,
    val income: Long = 0,
    val balance: Long = 0,
    /** 只含支出，金额降序 */
    val by_cat: List<MoneyCatStatDTO> = emptyList(),
    val prev_ym: String = "",
    val prev_expense: Long? = null,
    /** 老→新，含当月 */
    val trend: List<MoneyTrendDTO> = emptyList(),
    val cost: Long? = null,
    /** 收入 − 成本。⚠️ 不是 balance。 */
    val profit: Long? = null,
    val not_cost: Long? = null,
) {
    /** 画经营那一屏吗：判据是服务端给没给那三个数，不是「当前账本是不是 business」。 */
    val hasBiz: Boolean get() = cost != null
}

/** 一条周期规则。日期是 'YYYY-MM-DD' 文本（「每月 31 号」的锚不能丢）。iOS: MoneyModels.swift:226 */
@Serializable
data class MoneyRecurDTO(
    val id: String = "",
    val name: String = "",
    val cents: Long = 0,
    val direction: String = "expense",
    val cat: String = "",
    val sub: String = "",
    val merchant: String = "",
    /** day / week / month / year */
    val cycle: String = "month",
    val every_n: Int = 1,
    /** cycle='week' 用，0=周一 */
    val week_day: Int = 0,
    val first_date: String = "",
    val time_hhmm: String = "09:00",
    val tz_offset_min: Int = 0,
    /** never / date（结束日期**含当天**） */
    val end_kind: String = "never",
    val end_date: String = "",
    /** '' = 到头了 */
    val next_date: String = "",
    val next_at_ms: Long = 0,
    val paused: Boolean = false,
    val done_count: Int = 0,
    val last_done_ms: Long = 0,
    val updated_at_ms: Long = 0,
    val dirty: Boolean? = null,
)

@Serializable
data class MoneyRecurListDTO(val items: List<MoneyRecurDTO> = emptyList())

@Serializable
data class MoneyRecurPutDTO(val rule: MoneyRecurDTO = MoneyRecurDTO(), val written: Boolean? = null)

@Serializable
data class MoneyTombDTO(val id: String = "", val deleted_at_ms: Long = 0)

/** GET /money/entries/sync?since= 的回包。synced_at_ms 存下来当下次的 since。 */
@Serializable
data class MoneySyncPullDTO(
    val items: List<MoneyEntryDTO> = emptyList(),
    val deleted: List<MoneyTombDTO> = emptyList(),
    val synced_at_ms: Long = 0,
)

@Serializable
data class MoneyCatMergedDTO(val slug: String = "", val into: String = "", val updated_at_ms: Long = 0)

/** POST /money/categories/sync 的回包：存活（含停用）全量 + 墓碑。 */
@Serializable
data class MoneyCatSyncDTO(
    val cats: List<MoneyCatDTO> = emptyList(),
    val merged: List<MoneyCatMergedDTO> = emptyList(),
    val synced_at_ms: Long = 0,
)

@Serializable
data class MoneyRecurSyncPullDTO(
    val items: List<MoneyRecurDTO> = emptyList(),
    val deleted: List<MoneyTombDTO> = emptyList(),
    val synced_at_ms: Long = 0,
)

/** 待推的删除墓碑（本机删了、还没告诉服务端）。iOS: MoneyLocalRules.swift:14 */
@Serializable
data class MoneyTomb(val id: String = "", val deletedAtMs: Long = 0)

/** 子类 + 「N 笔在用」（全部历史口径）。iOS: MoneySync.swift MoneySubUsedDTO */
@Serializable
data class MoneySubUsedDTO(val label: String = "", val used: Int = 0)

@Serializable
data class MoneySubUsedListDTO(val items: List<MoneySubUsedDTO> = emptyList())

/** 一份发票 PDF 读出来的四个字段。读不出的是 null，不是 0。iOS: InvoicePdfKit.swift:20 */
@Serializable
data class InvoiceFields(
    val invoice_no: String? = null,
    val invoice_date: String? = null,
    val total_cents: Long? = null,
    val rate_bp: Int? = null,
)

/** POST /money/invoice-pdf 的回包。missing 是没读出来的字段名 —— 界面照它说话。 */
@Serializable
data class InvoicePdfDTO(val ok: Boolean? = null, val fields: InvoiceFields = InvoiceFields(), val missing: List<String>? = null)

/** GET /money/invoice-no/{no} 的响应。 */
@Serializable
data class InvoiceNoUsedDTO(val used: Boolean = false, val entry: Entry? = null) {
    @Serializable
    data class Entry(val id: String = "", val at_ms: Long = 0, val tz_offset_min: Int = 0, val cents: Long = 0, val merchant: String = "")
}

/** 票据状态三档。⚠️ 字面值 "none" 那一档叫 NoReceipt（iOS 吃过和 Optional.none 重名的亏）。iOS: BizKit.swift:13 */
enum class ReceiptState(val wire: String, val label: String) {
    Has("has", "有票"),
    Pending("pending", "待补票"),
    NoReceipt("none", "无票不可扣");

    companion object {
        fun of(s: String?): ReceiptState? = entries.firstOrNull { it.wire == s }

        /**
         * 这一行要不要把金额转灰、念「不进成本」。⚠️⚠️ **第一问是「这个字段在不在」**：
         * 生活账本的行上 receipt_state 是 null —— 它不是「无票」，是**没有这一问**。iOS: BizKit.swift:52
         */
        fun notCost(raw: String?, income: Boolean): Boolean {
            if (raw == null) return false
            return raw == NoReceipt.wire && !income
        }
    }
}

/** 扣除比例四档。⚠️ 招待费 60%、公私混用 40%（需求文档写反过）。iOS: BizKit.swift:83 */
enum class DeductTier(val wire: String, val label: String) {
    Full("full", "全额扣除"),
    Entertain("entertain", "60% 且不超营收 5‰"),
    Mixed("mixed", "按 40% 扣除"),
    None("none", "不可扣除");

    companion object {
        fun of(s: String?): DeductTier? = entries.firstOrNull { it.wire == s }
    }
}
