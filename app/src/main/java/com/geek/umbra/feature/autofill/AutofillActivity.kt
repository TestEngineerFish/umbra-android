// 自动填充的解锁 / 挑选面板 —— 照抄 iOS AutoFillExtension `UmbraAutoFillRoot`（页面那一半）。
// 由 [UmbraAutofillService] 里那条要认证的 dataset 拉起；挑好一条就把 Dataset 经 EXTRA_AUTHENTICATION_RESULT 交回系统填进去。
package com.geek.umbra.feature.autofill

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.app.PendingIntent
import android.os.Bundle
import android.view.WindowManager
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.Appearance
import com.geek.umbra.designsystem.UmbraButton
import com.geek.umbra.designsystem.UmbraButtonKind
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraFilterChips
import com.geek.umbra.designsystem.UmbraRowDivider
import com.geek.umbra.designsystem.UmbraSearchField
import com.geek.umbra.designsystem.UmbraTagPill
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraType
import com.geek.umbra.feature.vault.VaultBiometricPrompt
import com.geek.umbra.feature.vault.VaultBiometricStore
import com.geek.umbra.feature.vault.VaultFrame
import com.geek.umbra.feature.vault.VaultInput
import com.geek.umbra.feature.vault.VaultRecordIcon
import com.geek.umbra.feature.vault.VaultSession
import com.geek.umbra.feature.vault.VaultStore
import com.geek.umbra.net.Prefs
import kotlinx.coroutines.launch

class AutofillActivity : FragmentActivity() {
    private lateinit var parsed: ParsedFields

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 面板里会亮出账号与密码：不许截屏、不进最近任务的缩略图。
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        @Suppress("DEPRECATION")
        parsed = ParsedFields(
            intent.getParcelableArrayListExtra<AutofillId>(EXTRA_USER) ?: arrayListOf(),
            intent.getParcelableArrayListExtra<AutofillId>(EXTRA_PASS) ?: arrayListOf(),
            intent.getStringExtra(EXTRA_DOMAIN).orEmpty(),
            intent.getStringExtra(EXTRA_PKG).orEmpty(),
        )
        setResult(Activity.RESULT_CANCELED)
        setContent {
            val prefs by AppGraph.prefs.snapshot.collectAsState()
            UmbraTheme(Appearance.of(prefs[Prefs.K.appearance])) {
                VaultFrame { AutofillPanel(this, parsed, onPick = ::pick, onCancel = ::finish) }
            }
        }
    }

    private fun pick(user: String, pwd: String) {
        val ds = UmbraAutofillService.datasetFor(this, parsed, user, pwd, user.ifEmpty { "Umbra" })
        if (ds != null) setResult(Activity.RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, ds))
        finish()
    }

    companion object {
        private const val EXTRA_USER = "umbra.autofill.user"
        private const val EXTRA_PASS = "umbra.autofill.pass"
        private const val EXTRA_DOMAIN = "umbra.autofill.domain"
        private const val EXTRA_PKG = "umbra.autofill.pkg"

        fun intentSender(ctx: Context, p: ParsedFields): IntentSender {
            val i = Intent(ctx, AutofillActivity::class.java)
                .putParcelableArrayListExtra(EXTRA_USER, ArrayList(p.username))
                .putParcelableArrayListExtra(EXTRA_PASS, ArrayList(p.password))
                .putExtra(EXTRA_DOMAIN, p.webDomain)
                .putExtra(EXTRA_PKG, p.packageName)
            // 每次请求的字段 id 不同：requestCode 用当下时间，免得系统复用上一次那份 extras。
            return PendingIntent.getActivity(ctx, (System.nanoTime() and 0x7fffffff).toInt(), i, UmbraAutofillService.pendingFlags()).intentSender
        }
    }
}

@Composable
private fun AutofillPanel(activity: FragmentActivity, parsed: ParsedFields, onPick: (String, String) -> Unit, onCancel: () -> Unit) {
    val st by VaultStore.state.collectAsState()
    val se by VaultSession.state.collectAsState()
    val c = UmbraTheme.colors
    val target = parsed.target
    val domain = target.domain
    var password by remember { mutableStateOf("") }
    var secretKey by remember { mutableStateOf("") }
    var query by remember { mutableStateOf(domain) }
    var cat by remember { mutableStateOf("") }
    var faceScanning by remember { mutableStateOf(false) }
    var faceNote by remember { mutableStateOf("") }
    var faceTried by remember { mutableStateOf(false) }
    val unlocked = st.unlocked && !se.softLocked
    /** 主 App 里开过生物识别才显示这条路 —— 没开的话点了只会报「没存过凭证」。 */
    val faceAvailable = VaultBiometricStore.hasCredential && se.faceIDEnabled
    val name = VaultSession.biometryName

    fun unlockWith(pw: String, sk: String) {
        AppGraph.scope.launch {
            // 主 App 里正停在软锁（AUK 还在、界面锁着）：这里不做「只验身份」那一档，先完全上锁再按主密码解 —— 和 iOS 扩展一样每次都是一次真解锁。
            if (VaultSession.s.softLocked) VaultStore.lock()
            VaultStore.unlock(pw, sk)
            if (VaultStore.s.unlocked) VaultSession.markUnlocked()
        }
    }

    fun startFace() {
        if (faceScanning) return
        faceTried = true
        faceNote = ""
        val cipher = when (val r = VaultBiometricStore.decryptCipher()) {
            is VaultBiometricStore.DecryptInit.Fail -> { faceNote = r.why.message(name); return }
            is VaultBiometricStore.DecryptInit.Ready -> r.cipher
        }
        faceScanning = true
        VaultBiometricPrompt.authenticate(activity, "解锁 Umbra 保险箱以填充密码", cipher) { res ->
            faceScanning = false
            res.onSuccess { ci ->
                val pw = ci?.let { VaultBiometricStore.open(it) }
                if (pw == null) faceNote = VaultBiometricStore.Failure.Invalidated.message(name) else unlockWith(pw, "")
            }.onFailure { e -> faceNote = ((e as? VaultBiometricPrompt.FailureException)?.failure ?: VaultBiometricStore.Failure.Failed).message(name) }
        }
    }

    // 拉完密文再刷：解锁需要密文在手，顺序反了会「脸过了却没东西可解」。只自动一次。
    LaunchedEffect(Unit) {
        if (!unlocked) VaultStore.pullRecord()
        if (faceAvailable && !faceTried && !(VaultStore.s.unlocked && !VaultSession.s.softLocked)) startFace()
    }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
        // 顶栏：字标 + 标题 + 关闭
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(c.orange), contentAlignment = Alignment.Center) {
                Text("U", color = androidx.compose.ui.graphics.Color.White, style = TextStyle(fontSize = 13.sp, fontWeight = UmbraType.weight650))
            }
            Spacer(Modifier.width(10.dp))
            Text("Umbra · 密码填充", color = c.text, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
            Box(Modifier.heightIn(min = UmbraDimens.tapMin).clickable(role = Role.Button, onClick = onCancel), contentAlignment = Alignment.Center) {
                Text("关闭", color = c.orangeText, style = TextStyle(fontSize = 15.sp))
            }
        }
        UmbraRowDivider()
        // 只有**真的有一份缓存可看**时才说「这是本机缓存的一份」。
        if (st.offline && st.recordExists) {
            Text("离线 · 这是本机缓存的一份，可能不是最新的", color = c.warningText, style = TextStyle(fontSize = 12.sp),
                modifier = Modifier.fillMaxWidth().background(c.warningSoft).padding(horizontal = 16.dp, vertical = 7.dp))
        }
        if (!unlocked) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (faceAvailable) "填充前要先解锁。可以用${name}，也可以输主密码。" else "填充前要先解锁。主密码不保存、不上传 —— 填充时也一样。",
                    color = c.muted, style = TextStyle(fontSize = 13.sp, lineHeight = 20.sp))
                if (faceAvailable) {
                    UmbraButton(if (faceScanning) "识别中…" else "用${name}解锁", { startFace() }, Modifier.fillMaxWidth(), enabled = !faceScanning, height = 46.dp)
                    if (faceNote.isNotEmpty()) Text(faceNote, color = c.warningText, style = TextStyle(fontSize = 12.5.sp, lineHeight = 19.sp))
                    Text("或者输入主密码", color = c.faint, style = TextStyle(fontSize = 12.sp))
                }
                VaultInput(password, { password = it }, placeholder = "主密码", password = true, mono = true, height = 46.dp, imeDone = { unlockWith(password, secretKey) })
                if (!st.hasSecretKey) {
                    VaultInput(secretKey, { secretKey = it }, placeholder = "Secret Key（第一次在这台设备上用要输一次）", mono = true, fontSize = 13f, height = 46.dp, allCaps = true)
                }
                if (st.error.isNotEmpty()) Text(st.error, color = c.dangerText, style = TextStyle(fontSize = 12.5.sp, lineHeight = 19.sp))
                // 有生物识别那颗橙按钮时，这颗降成次要样式 —— 一屏一个橙实底。
                UmbraButton(if (st.loading) "解锁中…" else "解锁并填充", { unlockWith(password, secretKey) }, Modifier.fillMaxWidth(),
                    kind = if (faceAvailable) UmbraButtonKind.Secondary else UmbraButtonKind.Primary, enabled = !st.loading, height = 46.dp)
            }
            return@Column
        }
        // 列表
        val candidates = AutofillRules.candidates(st.items, target, query, cat)
        val catItems = buildList {
            add(Triple("", "全部", AutofillRules.matched(st.items, target, query, "").size))
            val favs = AutofillRules.matched(st.items, target, query, "fav").size
            if (favs > 0 || cat == "fav") add(Triple("fav", "收藏", favs))
            for (t in st.types) {
                val n = AutofillRules.matched(st.items, target, query, t.id).size
                if (n > 0 || cat == t.id) add(Triple(t.id, t.name, n))
            }
        }
        Column(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            UmbraSearchField(query, { query = it }, placeholder = if (domain.isEmpty()) "搜名称、网址或备注" else "搜 $domain")
            // 分类只有「全部」一项时不画 —— 一个选项的筛选器是纯占地方。
            if (catItems.size > 1) UmbraFilterChips { for ((k, l, n) in catItems) UmbraTagPill(l, cat == k, { cat = k }, count = n) }
            Text(AutofillRules.listCaption(query, domain, cat, st.types.firstOrNull { it.id == cat }?.name), color = c.faint,
                style = TextStyle(fontSize = 11.5.sp, fontWeight = UmbraType.weight560))
            if (candidates.isEmpty()) {
                Text(AutofillRules.emptyText(st.items.isEmpty(), query, domain, cat), color = c.muted, style = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
                    modifier = Modifier.padding(top = 20.dp))
            } else {
                val shape = RoundedCornerShape(12.dp)
                LazyColumn(Modifier.fillMaxWidth().clip(shape).background(c.card).border(1.dp, c.border, shape)) {
                    candidates.forEachIndexed { i, r ->
                        item(r.id) {
                            if (i > 0) UmbraRowDivider()
                            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { onPick(r.account.user, r.account.pwd) }.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                VaultRecordIcon(r.item.icon, r.item.title.take(1))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(AutofillRules.rowTitle(r), color = c.text, style = TextStyle(fontSize = 14.sp, fontWeight = UmbraType.weight560), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(r.account.user.ifEmpty { "（没有账号）" }, color = c.faint, style = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace), maxLines = 1)
                                }
                                Text("填充", color = c.orangeText, style = TextStyle(fontSize = 12.5.sp, fontWeight = UmbraType.weight560), textAlign = TextAlign.End)
                            }
                        }
                    }
                }
            }
        }
    }
}
