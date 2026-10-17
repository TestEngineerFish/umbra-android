// 账号 / 计费 / 商城 / 存储那一批接口 —— iOS 放在 Networking/AuthService.swift:350-500 与 HTTPService 的回收站两条。
// Android 地基的 AuthService 只管登录 / 会话，这些「登录之后的账号操作」在这个包里自己走 AppGraph.http。
package com.geek.umbra.feature.account

import com.geek.umbra.app.AppGraph
import com.geek.umbra.net.ApiResult
import com.geek.umbra.net.HttpResult
import com.geek.umbra.net.UmbraUrl
import com.geek.umbra.net.int
import com.geek.umbra.net.long
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `POST /auth/verify/resend` 的回包。 */
@Serializable
data class ResendDTO(val sent: Boolean = false, val already_verified: Boolean = false, val cooling: Boolean = false)

object AccountApi {
    private val http get() = AppGraph.http

    // ── 读：失败一律 null（= 没问到 / 旧服务端没有这条），调用方按「没有这项功能」处理，不画那一格 ──

    suspend fun credits(): CreditsDTO? = http.get("/credits")
    suspend fun membership(): MembershipDTO? = http.get("/membership")
    suspend fun shopProducts(): ShopCatalogDTO? = http.get("/shop/products")
    suspend fun shopItems(): ShopItemsDTO? = http.get("/shop/items")

    /** 存储用量（账号页优先用 /membership 顺带回的那份；这条给管理存储页和旧服务端兜底）。 */
    suspend fun storage(): ApiResult<StorageDTO> = http.send("GET", "/storage")

    /** 一类的条目列表。kind 是固定的四个词；limit 服务端封顶 200，这里先夹一道（AuthService.swift:400）。 */
    suspend fun storageItems(kind: String, offset: Int = 0, limit: Int = 50): ApiResult<StorageItemsDTO> =
        http.send("GET", "/storage/items", query = mapOf("kind" to kind, "offset" to maxOf(0, offset), "limit" to minOf(200, maxOf(1, limit))))

    // ── 写 ──

    /** 用积分买一件。`ref` 幂等。 */
    suspend fun shopBuy(key: String, ref: String): ApiResult<ShopBuyReplyDTO> =
        http.send("POST", "/shop/buy", buildJsonObject { put("key", key); put("ref", ref) })

    suspend fun shopUseItem(id: Int): ApiResult<ShopUseReplyDTO> =
        http.send("POST", "/shop/items/$id/use", JsonObject(emptyMap()))

    /** 付钱下单（M1 收单：支付宝，method=page）。503 = 这台服务器没接收单；409 = 这件现在不卖。 */
    suspend fun shopCheckout(key: String, ref: String): ApiResult<ShopCheckoutDTO> =
        http.send("POST", "/shop/checkout", buildJsonObject {
            put("product_key", key); put("channel", "alipay"); put("method", "page"); put("ref", ref)
        })

    /** 一笔订单现在什么状态。信封 / 平铺两种都认（umbra-ios#88，PayRules.parseOrder）。 */
    suspend fun shopOrder(id: Int): ShopOrderDTO? {
        val r = http.raw("GET", "/shop/orders/$id")
        return if (r is HttpResult.Ok) PayRules.parseOrder(r.body) else null
    }

    /** 当场删一个文件（不进回收站）。回腾出的字节数（旧服务端不回 freed 按 0）。 */
    suspend fun deleteStorageFile(id: String): ApiResult<Long> =
        when (val r = http.call("DELETE", "/storage/files/${UmbraUrl.value(id)}")) {
            is ApiResult.Ok -> ApiResult.Ok(r.value.long("freed") ?: 0)
            is ApiResult.Err -> r
        }

    /** 清空回收站（只清知识库那一区）。回（清掉几项，腾出多少字节）。 */
    suspend fun purgeStorageTrash(): ApiResult<Pair<Int, Long>> =
        when (val r = http.call("POST", "/storage/trash/purge", JsonObject(emptyMap()))) {
            is ApiResult.Ok -> ApiResult.Ok((r.value.int("purged") ?: 0) to (r.value.long("freed") ?: 0))
            is ApiResult.Err -> r
        }

    /** 开 / 关自动续费（合规：随时可关）。 */
    suspend fun setAutoRenew(on: Boolean): ApiResult<JsonObject> =
        http.call("POST", "/membership/auto-renew", buildJsonObject { put("on", on) })

    /** 改显示名。**空串是合法的**（表示不要显示名，界面退回显示邮箱）。成功后刷一次 /auth/me。 */
    suspend fun setDisplayName(name: String): ApiResult<JsonObject> {
        val r = http.call("PATCH", "/auth/me", buildJsonObject { put("display_name", name) })
        if (r is ApiResult.Ok) AppGraph.auth.refresh()
        return r
    }

    /**
     * 改登录密码，回**下掉了几台**（不含这一台）。当前密码不对是 **400 不是 401** —— 令牌是好的，错的是表单里那一格。
     * 这一台不下线（与 /auth/reset 刻意不同：他刚打对了当前密码）。
     */
    suspend fun changePassword(current: String, password: String): ApiResult<Int> =
        when (val r = http.call("POST", "/auth/password", buildJsonObject { put("current", current); put("password", password) })) {
            is ApiResult.Ok -> ApiResult.Ok(r.value.int("logged_out") ?: 0)
            is ApiResult.Err -> r
        }

    suspend fun resendVerify(): ApiResult<ResendDTO> = http.send("POST", "/auth/verify/resend", JsonObject(emptyMap()))

    /**
     * 注销：打上留期起点，所有设备立刻下线，数据一个字都不动。成功后落「未登录」，登录屏接管。
     * 同 iOS deactivate()：await 回来时本机若已换成**另一把**令牌（等的时候登出又登进了别的号），不许把那一把清掉。
     */
    suspend fun deactivate(): ApiResult<JsonObject> {
        val sent = AppGraph.session.token
        val r = http.call("POST", "/auth/deactivate", JsonObject(emptyMap()))
        if (r is ApiResult.Ok) {
            val now = AppGraph.session.token
            if (now == sent || now.isEmpty()) AppGraph.auth.logout()
        }
        return r
    }

    /** 回收站 · 找回（知识库文档）。`restored` 是硬字段：解不出来回 null（不猜成「找回了」）；0 = 已经不在了。 */
    suspend fun restoreKbDoc(id: String): Int? {
        val body = buildJsonObject { put("entries", buildJsonArray { add(buildJsonObject { put("kind", "kb_doc"); put("id", id) }) }) }
        return when (val r = http.call("POST", "/trash/restore", body)) {
            is ApiResult.Ok -> r.value.int("restored")
            is ApiResult.Err -> null
        }
    }

    /** 回收站 · 彻底删除一条。 */
    suspend fun purgeKbDoc(id: String): Boolean {
        val body = buildJsonObject { put("entries", buildJsonArray { add(buildJsonObject { put("kind", "kb_doc"); put("id", id) }) }) }
        return http.call("POST", "/trash/purge", body) is ApiResult.Ok
    }
}

/**
 * 账号页写动作失败时那一句吐司（iOS AccountViews.swift:613 accountFailToast）：**说出下一步**，不只说「失败了」。
 * 连不上 → 等网络；会话没了 → 去重新登录；其余带上服务端那句原话，没有就只说没成。
 */
fun accountFailToast(what: String, err: ApiResult.Err?): String {
    err ?: return "$what，稍后再试一次。"
    if (err.offline) return "$what：连不上服务器，网络好了再试一次。"
    if (err.code == "session_invalid" || err.code == "signed_out") return "$what：登录已经失效，重新登录后再试。"
    val d = err.detail?.trim().orEmpty()
    return if (d.isEmpty()) "$what，稍后再试一次。" else "$what：$d"
}
