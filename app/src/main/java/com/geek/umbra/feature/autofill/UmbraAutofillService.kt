// 密码自动填充服务 —— 对应 iOS 的 AutoFill Credential Provider 扩展（AutoFillExtension/CredentialProviderViewController.swift）。
//
// 平台差异：iOS 扩展是独立进程，要自己拉密文、自己解密；Android 的 AutofillService 跑在**同一个 App 进程**里，
// 直接用进程里那份 VaultStore（解锁着就直接有明文，锁着就读本机密文缓存去解）。
// 流程：
//   onFillRequest → 从 AssistStructure 里找出账号框 / 密码框，取网页域名（webDomain）或请求方包名；
//   · 保险箱解锁着：同站 / 同 App 的账号直接给成建议（最多 5 条）；
//   · 末尾永远有一条「Umbra · 解锁 / 挑一个账号填充」：它是**要认证的 dataset**，点了拉起 [AutofillActivity]
//     （主密码或生物识别解锁 → 列表 → 挑一个），挑好的那一条经 EXTRA_AUTHENTICATION_RESULT 回填。
// 未登录也能填（2026-10-10 起保险箱是本地功能）。iOS 那个「系统身份库 / QuickType」Android 没有对等物。
package com.geek.umbra.feature.autofill

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Context
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import com.geek.umbra.app.Brand
import com.geek.umbra.R
import com.geek.umbra.feature.vault.VaultSession
import com.geek.umbra.feature.vault.VaultStore
import com.geek.umbra.net.UmbraUrl

/** 从一次填充请求里认出来的东西。 */
data class ParsedFields(
    val username: List<AutofillId>,
    val password: List<AutofillId>,
    val webDomain: String,
    val packageName: String,
) {
    val all: List<AutofillId> get() = username + password
    val target: AutofillTarget get() = AutofillTarget(UmbraUrl.host(webDomain), packageName)
}

object AutofillParser {
    private val userHints = listOf("user", "email", "login", "account", "phone", "mobile", "账号", "帐号", "用户", "邮箱", "手机")
    private val passHints = listOf("pass", "pwd", "密码")

    fun parse(structure: AssistStructure): ParsedFields {
        val users = mutableListOf<AutofillId>()
        val passes = mutableListOf<AutofillId>()
        /** 认不出的文本框，按出现顺序。 */
        val plain = mutableListOf<AutofillId>()
        var lastPlainBeforePass: AutofillId? = null
        var domain = ""
        for (i in 0 until structure.windowNodeCount) {
            walk(structure.getWindowNodeAt(i).rootViewNode) { n ->
                if (domain.isEmpty() && !n.webDomain.isNullOrEmpty()) domain = n.webDomain!!
                val id = n.autofillId ?: return@walk
                if (n.autofillType != View.AUTOFILL_TYPE_TEXT) return@walk
                when (classify(n)) {
                    1 -> users += id
                    2 -> { if (passes.isEmpty()) lastPlainBeforePass = plain.lastOrNull(); passes += id }
                    else -> plain += id
                }
            }
        }
        // 只有一个密码框、没认出账号框时：密码框前面那个文本框多半就是账号（很多 App 不给提示）。
        if (users.isEmpty() && passes.isNotEmpty()) lastPlainBeforePass?.let { users += it }
        return ParsedFields(users.distinct(), passes.distinct(), domain, structure.activityComponent?.packageName ?: "")
    }

    private fun walk(n: AssistStructure.ViewNode, f: (AssistStructure.ViewNode) -> Unit) {
        f(n)
        for (i in 0 until n.childCount) walk(n.getChildAt(i), f)
    }

    /** 0 不是 / 1 账号 / 2 密码。先看 autofillHints，再看输入类型，最后看 id / 提示文字 / HTML 属性。 */
    private fun classify(n: AssistStructure.ViewNode): Int {
        val hints = n.autofillHints?.map { it.lowercase() } ?: emptyList()
        if (hints.any { it.contains("password") }) return 2
        if (hints.any { it == View.AUTOFILL_HINT_USERNAME.lowercase() || it == View.AUTOFILL_HINT_EMAIL_ADDRESS.lowercase() || it.contains("username") || it.contains("email") }) return 1
        val t = n.inputType
        val variation = t and InputType.TYPE_MASK_VARIATION
        if (t and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
            (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        ) return 2
        if (t and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_NUMBER && (t and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_NUMBER_VARIATION_PASSWORD) return 2
        val html = n.htmlInfo
        if (html != null && html.tag.equals("input", true)) {
            val type = html.attributes?.firstOrNull { it.first.equals("type", true) }?.second?.lowercase()
            if (type == "password") return 2
            if (type == "email") return 1
        }
        val text = listOfNotNull(n.idEntry, n.hint?.toString(), html?.attributes?.firstOrNull { it.first.equals("name", true) }?.second)
            .joinToString(" ").lowercase()
        if (passHints.any { text.contains(it) }) return 2
        if (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || userHints.any { text.contains(it) }) return 1
        return 0
    }
}

class UmbraAutofillService : AutofillService() {
    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure ?: return callback.onSuccess(null)
        val parsed = AutofillParser.parse(structure)
        // 自己这个 App 的输入框不提供（保险箱页面本身就是密码框，提示自己填自己没有意义）。
        if (parsed.password.isEmpty() && parsed.username.isEmpty() || parsed.packageName == packageName) return callback.onSuccess(null)
        val b = FillResponse.Builder()
        val open = runCatching { VaultStore.s.unlocked && !VaultSession.s.softLocked }.getOrDefault(false)
        if (open) {
            for (r in AutofillRules.directSuggestions(VaultStore.s.items, parsed.target)) {
                datasetFor(this, parsed, r.account.user, r.account.pwd, AutofillRules.rowTitle(r), r.account.user.ifEmpty { "（没有账号）" })?.let { b.addDataset(it) }
            }
        }
        // 末尾那条：要认证的 dataset，点了拉起解锁 / 挑选面板。上锁时只出这一条（设计侧 199 答复第 12 条定稿文字）。
        val ds = Dataset.Builder(
            if (open) presentation(this, "${Brand.productName}密码保险箱", "挑别的账号") else presentation(this, "${Brand.productName}密码保险箱", "解锁后选账号"),
        )
        for (id in parsed.all) ds.setValue(id, null)
        ds.setAuthentication(AutofillActivity.intentSender(this, parsed))
        b.addDataset(ds.build())
        callback.onSuccess(runCatching { b.build() }.getOrNull())
    }

    /** 不做「保存新密码」：保险箱的写入只在 App 里做（与 iOS 扩展一致，扩展不写库）。 */
    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) = callback.onSuccess()

    companion object {
        fun presentation(ctx: Context, title: String, sub: String? = null): RemoteViews =
            RemoteViews(ctx.packageName, R.layout.umbra_autofill_item).apply {
                setTextViewText(R.id.umbra_autofill_text, title)
                if (sub.isNullOrEmpty()) setViewVisibility(R.id.umbra_autofill_sub, android.view.View.GONE)
                else setTextViewText(R.id.umbra_autofill_sub, sub)
            }

        /** 一条能直接填的 dataset：账号框填用户名、密码框填密码。一个框都填不上回 null。 */
        fun datasetFor(ctx: Context, p: ParsedFields, user: String, pwd: String, label: String, sub: String? = null): Dataset? {
            val ds = Dataset.Builder(presentation(ctx, label, sub))
            var any = false
            for (id in p.username) if (user.isNotEmpty()) { ds.setValue(id, AutofillValue.forText(user)); any = true }
            for (id in p.password) if (pwd.isNotEmpty()) { ds.setValue(id, AutofillValue.forText(pwd)); any = true }
            return if (any) runCatching { ds.build() }.getOrNull() else null
        }

        internal fun pendingFlags(): Int = PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
    }
}
