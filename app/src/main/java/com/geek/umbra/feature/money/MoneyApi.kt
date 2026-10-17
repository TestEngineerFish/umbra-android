// 记账的全部 HTTP 接口 —— 照抄 iOS Networking/HTTPService.swift:503-870 的 Money 那一段。
//
// 取数失败一律回 null 而不是空值：「拉失败了」和「这个月真的没记账」是两回事，混成一个值的话
// 断网会被渲染成「这个月还没有记账」（iOS fetchTasks 的同一条教训）。
// `ledger=` 由统一出口自动补（net/UmbraHttp.url）；**只有同步那几条显式写 ledger=personal** ——
// 本机账本只有生活账本，同步时当前账本选的可能是经营账本（统一出口见到已有 ledger 就不覆盖）。
package com.geek.umbra.feature.money

import com.geek.umbra.app.AppGraph
import com.geek.umbra.net.ApiResult
import com.geek.umbra.net.HttpResult
import com.geek.umbra.net.Ledger
import com.geek.umbra.net.UmbraHttp
import com.geek.umbra.net.UmbraJson
import com.geek.umbra.net.UmbraUrl
import com.geek.umbra.net.str
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.RequestBody.Companion.toRequestBody

/** 写操作「要么成、要么带服务端那句话」的结果（iOS APIResult.ok / .failed(String)）。 */
sealed interface MoneyResult<out T> {
    data class Ok<T>(val value: T) : MoneyResult<T>
    /** detail 是服务端 FastAPI 的 `detail`；解不出就是空串 —— 别把 HTML 错误页当成给用户看的话吐出去。 */
    data class Failed(val detail: String) : MoneyResult<Nothing>
}

object MoneyApi {
    private val http get() = AppGraph.http

    private fun <T> either(r: ApiResult<T>): MoneyResult<T> = when (r) {
        is ApiResult.Ok -> MoneyResult.Ok(r.value)
        is ApiResult.Err -> MoneyResult.Failed(if (r.offline || r.code == "decode" || r.code == "signed_out") "" else (r.detail ?: ""))
    }

    private fun <T> okOrNull(r: ApiResult<T>): T? = (r as? ApiResult.Ok)?.value

    // MARK: 分类

    /** 分类列表。includeDisabled 只给分类管理页用：停用的要能看见、能开回来。 */
    suspend fun fetchCats(includeDisabled: Boolean = false): List<MoneyCatDTO>? =
        http.get("/money/categories", ListSerializer(MoneyCatDTO.serializer()),
            if (includeDisabled) mapOf("include_disabled" to "true") else emptyMap())

    /** 改分类（改名 / 停用启用 / 换图标 / 换色槽）。**零和由服务端做**，端上不发两次 PATCH 去模拟。 */
    suspend fun updateCat(slug: String, name: String? = null, enabled: Boolean? = null, icon: String? = null, slot: Int? = null): MoneyCatDTO? {
        val body = buildJsonObject {
            if (name != null) put("name", name)
            if (enabled != null) put("enabled", enabled)
            if (icon != null) put("icon", icon)
            if (slot != null) put("slot", slot)
        }
        return okOrNull(http.send("PATCH", "/money/categories/${UmbraUrl.segment(slug)}", body, MoneyCatDTO.serializer()))
    }

    /** 色槽池。null = 老服务端没这个接口 / 网络挂了，调用方按兜底常量走。 */
    suspend fun fetchSlotPool(): MoneySlotPoolDTO? = http.get("/money/categories/slots", MoneySlotPoolDTO.serializer())

    /** 每个分类挂着多少东西。null = 老服务端 / 断网：调用方按「拿不到就当它有账」处理。 */
    suspend fun fetchCatUsage(): Map<String, MoneyCatUsageDTO>? =
        http.get("/money/categories/usage", MapSerializer(String.serializer(), MoneyCatUsageDTO.serializer()))

    /** 把一个分类并进另一个。**不能撤销**。失败时带回服务端那句拒绝的话。 */
    suspend fun mergeCat(slug: String, into: String): MoneyResult<MoneyMergeDTO> =
        either(http.send("POST", "/money/categories/${UmbraUrl.segment(slug)}/merge", buildJsonObject { put("into", into) }, MoneyMergeDTO.serializer()))

    /** 删掉一个分类（只在建错了的情况下）；服务端还有一道同样的闸。 */
    suspend fun deleteCat(slug: String): MoneyResult<List<MoneyCatDTO>> =
        either(http.send("DELETE", "/money/categories/${UmbraUrl.segment(slug)}", null, ListSerializer(MoneyCatDTO.serializer())))

    /** 拖拽排序的落点：按给定顺序重排**一个方向**的分类，回全量（含停用的）。 */
    suspend fun reorderCats(direction: String, slugs: List<String>): List<MoneyCatDTO>? {
        val body = buildJsonObject { put("direction", direction); putJsonArray("slugs") { slugs.forEach { add(JsonPrimitive(it)) } } }
        return okOrNull(http.send("POST", "/money/categories/reorder", body, ListSerializer(MoneyCatDTO.serializer())))
    }

    /** 新增分类。slug 服务端生成；重名会 400。 */
    suspend fun createCat(name: String, direction: String): MoneyCatDTO? =
        okOrNull(http.send("POST", "/money/categories", buildJsonObject { put("name", name); put("direction", direction) }, MoneyCatDTO.serializer()))

    suspend fun addSub(cat: String, label: String): Boolean =
        http.call("POST", "/money/categories/${UmbraUrl.segment(cat)}/subs", buildJsonObject { put("label", label) }) is ApiResult.Ok

    suspend fun renameSub(cat: String, old: String, new: String): Boolean =
        http.call("PATCH", "/money/categories/${UmbraUrl.segment(cat)}/subs", buildJsonObject { put("old", old); put("new", new) }) is ApiResult.Ok

    suspend fun deleteSub(cat: String, label: String): Boolean =
        http.call("POST", "/money/categories/${UmbraUrl.segment(cat)}/subs/delete", buildJsonObject { put("label", label) }) is ApiResult.Ok

    /** 某分类的子类 + 「N 笔在用」（全部历史口径）。 */
    suspend fun fetchSubsUsed(cat: String): List<MoneySubUsedDTO>? =
        http.get("/money/categories/${UmbraUrl.segment(cat)}/subs", MoneySubUsedListDTO.serializer(), mapOf("with_used" to "true"))?.items

    // MARK: 流水

    /** 某个月的全部流水（一个月几百条，全量拉回本地过滤，拍板 D4）。 */
    suspend fun fetchEntries(ym: String): MoneyEntriesDTO? =
        http.get("/money/entries", MoneyEntriesDTO.serializer(), mapOf("ym" to ym, "limit" to "1000"))

    /** 任意范围的流水（导出用）。ym 为空 = 全部账目，跨所有月份。调用方要**逐页拉全**。 */
    suspend fun entries(ym: String?, direction: String?, cat: String?, limit: Int, offset: Int = 0): MoneyEntriesDTO? {
        val q = linkedMapOf<String, Any?>("limit" to limit, "offset" to offset)
        if (!ym.isNullOrEmpty()) q["ym"] = ym
        if (!direction.isNullOrEmpty()) q["direction"] = direction
        if (!cat.isNullOrEmpty()) q["cat"] = cat
        return http.get("/money/entries", MoneyEntriesDTO.serializer(), q)
    }

    /** 待补票那一筛的取数：**不传 ym_from（含往季），只传上界 ym_to**。iOS: HTTPService.swift:637 */
    suspend fun receiptPending(ymThrough: String): MoneyEntriesDTO? =
        http.get("/money/entries", MoneyEntriesDTO.serializer(),
            mapOf("ym_to" to ymThrough, "receipt_state" to "pending", "limit" to ReceiptKit.PENDING_LIMIT.toString()))

    /**
     * 这个发票号在这个账本里已经用过没有。外层 null = **还不知道**（离线 / 老服务端）；
     * 内层 null = 查过了没人用。⚠️ 发票号是人敲的，按一个路径段编码（`#` `?` `/` 都会改变路径形状）。
     */
    suspend fun invoiceNoUsed(no: String, excludeId: String): Result<InvoiceDup?>? {
        val trimmed = no.trim()
        if (trimmed.isEmpty()) return Result.success(null)
        val dto = http.get("/money/invoice-no/${UmbraUrl.segment(trimmed)}", InvoiceNoUsedDTO.serializer(),
            if (excludeId.isNotEmpty()) mapOf("exclude" to excludeId) else emptyMap()) ?: return null
        val e = dto.entry
        if (!dto.used || e == null) return Result.success(null)
        return Result.success(InvoiceDup(e.id, e.at_ms, e.tz_offset_min, e.cents, e.merchant))
    }

    /** 把一笔账挪到另一个账本。⚠️ 源账本不在参数里 —— 由统一出口从当前账本补上。 */
    suspend fun moveEntry(id: String, to: Ledger): MoneyResult<MoneyPutDTO> =
        either(http.send("POST", "/money/entries/${UmbraUrl.segment(id)}/move", buildJsonObject { put("to", to.wire) }, MoneyPutDTO.serializer()))

    /**
     * 记一笔 / 改一笔。失败**分档**：409 state_conflict + other_ledger 那一档重试永远没用。
     * 码先看 X-Umbra-Error 头、再看体（统一出口已经替我们按这个顺序取好了）。iOS: HTTPService.swift:693
     */
    suspend fun putEntry(dto: MoneyEntryDTO, ledgerOverride: Ledger? = null): Result<MoneyPutDTO> {
        val body = UmbraJson.encodeToString(MoneyEntryDTO.serializer(), dto).toRequestBody(UmbraHttp.JSON)
        val q: Map<String, Any?> = if (ledgerOverride != null) mapOf("ledger" to ledgerOverride.wire) else emptyMap()
        return when (val r = http.raw("PUT", "/money/entries/${UmbraUrl.segment(dto.id)}", q, body)) {
            is HttpResult.Ok -> runCatching { UmbraJson.decodeFromString(MoneyPutDTO.serializer(), r.body) }
                .fold({ Result.success(it) }, { Result.failure(PutFailure(MoneyPutFail.Unreachable)) })
            is HttpResult.Fail -> {
                val o = runCatching { UmbraJson.parseToJsonElement(r.body) as JsonObject }.getOrNull()
                Result.failure(PutFailure(MoneyPutFail.classify(r.status, r.code, o?.str("state"), o?.str("ledger"))))
            }
            else -> Result.failure(PutFailure(MoneyPutFail.Unreachable))
        }
    }

    /** 把分档包成异常，好走 Result（Kotlin 的 Result 只装 Throwable）。 */
    class PutFailure(val why: MoneyPutFail) : Exception(why.toString())

    /** 删流水 = 移进回收站（保留 30 天）。 */
    suspend fun deleteEntries(ids: List<String>, ledgerOverride: Ledger? = null): Boolean {
        if (ids.isEmpty()) return false
        val body = buildJsonObject { putJsonArray("ids") { ids.forEach { add(JsonPrimitive(it)) } } }
        val q: Map<String, Any?> = if (ledgerOverride != null) mapOf("ledger" to ledgerOverride.wire) else emptyMap()
        return http.call("POST", "/money/entries/delete", body, q) is ApiResult.Ok
    }

    /** 某个月的统计。服务端全部从流水现算。 */
    suspend fun fetchStats(ym: String, trendMonths: Int = 6): MoneyStatsDTO? =
        http.get("/money/stats", MoneyStatsDTO.serializer(), mapOf("ym" to ym, "trend_months" to trendMonths.toString()))

    // MARK: 附件

    /** 摘一张附件。原图（origin）服务端会拒。 */
    suspend fun deleteAtt(entryId: String, fileId: String): Boolean =
        http.call("POST", "/money/entries/${UmbraUrl.segment(entryId)}/atts/delete", buildJsonObject { put("file_id", fileId) }) is ApiResult.Ok

    /** 挂一张附件。kind = "invoice" 是发票 PDF（不占那四张、一笔只留一份，再扫一次是替换）。 */
    suspend fun addAtt(entryId: String, fileId: String, label: String, kind: String = "", ledgerOverride: Ledger? = null): Boolean {
        val body = buildJsonObject { put("file_id", fileId); put("label", label); if (kind.isNotEmpty()) put("kind", kind) }
        val q: Map<String, Any?> = if (ledgerOverride != null) mapOf("ledger" to ledgerOverride.wire) else emptyMap()
        return http.call("POST", "/money/entries/${UmbraUrl.segment(entryId)}/atts", body, q) is ApiResult.Ok
    }

    /** 读一份已上传的发票 PDF 的文字层。失败一律 null —— 不许拿一个空壳当解析成功。 */
    suspend fun invoicePdf(fileId: String): Pair<InvoiceFields, List<String>>? {
        val dto = okOrNull(http.send("POST", "/money/invoice-pdf", buildJsonObject { put("file_id", fileId) }, InvoicePdfDTO.serializer())) ?: return null
        return dto.fields to (dto.missing ?: emptyList())
    }

    /** 附件文件的下载地址（GET /files/{id}）。本机暂存的 `local:` 由 MoneyLocal.fileFor 给本机文件。 */
    fun fileUrl(fileId: String): String = http.server + "/files/" + UmbraUrl.segment(fileId)

    // MARK: 同步（只给生活账本的本机账本用）

    private val personal = mapOf<String, Any?>("ledger" to Ledger.Personal.wire)

    /** 拉生活账本的增量：since=0 全量。null = 没问到。 */
    suspend fun syncPull(since: Long): MoneySyncPullDTO? =
        http.get("/money/entries/sync", MoneySyncPullDTO.serializer(), mapOf("since" to since.toString()) + personal)

    suspend fun recurSyncPull(since: Long): MoneyRecurSyncPullDTO? =
        http.get("/money/recur/sync", MoneyRecurSyncPullDTO.serializer(), mapOf("since" to since.toString()) + personal)

    /** 推一条规则：回 (规则, 写没写上)；null = 没问到。老服务端不回 written → 当写上了。 */
    suspend fun putRecurSync(r: MoneyRecurDTO): Pair<MoneyRecurDTO, Boolean>? {
        val body = UmbraJson.encodeToJsonElement(MoneyRecurDTO.serializer(), r)
        val got = okOrNull(http.send("PUT", "/money/recur/${UmbraUrl.segment(r.id)}", body, MoneyRecurPutDTO.serializer(), personal)) ?: return null
        return got.rule to (got.written ?: true)
    }

    /** 分类 + 子类全量往返（服务端逐条按戳合并，回全量 + 墓碑）。 */
    suspend fun syncCats(cats: List<MoneyCatDTO>): MoneyCatSyncDTO? {
        val body = buildJsonObject { put("cats", UmbraJson.encodeToJsonElement(ListSerializer(MoneyCatDTO.serializer()), cats)) }
        return okOrNull(http.send("POST", "/money/categories/sync", body, MoneyCatSyncDTO.serializer(), personal))
    }

    // MARK: 周期记账

    suspend fun fetchRecur(): List<MoneyRecurDTO>? = http.get("/money/recur", MoneyRecurListDTO.serializer())?.items

    /** 建/改一条规则（经营账本那一路）。服务端校验「一笔都记不到」的规则并 400。 */
    suspend fun putRecur(id: String, body: JsonObject): MoneyRecurDTO? =
        okOrNull(http.send("PUT", "/money/recur/${UmbraUrl.segment(id)}", body, MoneyRecurPutDTO.serializer()))?.rule

    suspend fun pauseRecur(id: String, paused: Boolean): Boolean =
        http.call("POST", "/money/recur/${UmbraUrl.segment(id)}/pause", buildJsonObject { put("paused", paused) }) is ApiResult.Ok

    /** 删规则。404 也算「送到了」：服务端本来就没有这条，本机墓碑没必要再推。 */
    suspend fun deleteRecur(id: String, ledgerOverride: Ledger? = null): Boolean {
        val q: Map<String, Any?> = if (ledgerOverride != null) mapOf("ledger" to ledgerOverride.wire) else emptyMap()
        return when (val r = http.raw("DELETE", "/money/recur/${UmbraUrl.segment(id)}", q)) {
            is HttpResult.Ok -> true
            is HttpResult.Fail -> r.status == 404
            else -> false
        }
    }

    /** 把任意可序列化的值变成 JsonElement（周期规则表单拼 body 用）。 */
    fun json(e: Any?): JsonElement = when (e) {
        null -> kotlinx.serialization.json.JsonNull
        is String -> JsonPrimitive(e)
        is Number -> JsonPrimitive(e)
        is Boolean -> JsonPrimitive(e)
        else -> JsonPrimitive(e.toString())
    }
}
