// 记一笔 / 编辑账目（money.add，对齐稿 2521–2636 + 批次 004 附件区正式形态）—— 照抄 iOS Features/Money/MoneyAddView.swift。
//
// 附件区（批次 004 定稿）在 MoneyAttachments.kt；经营账本那几件（G2）的长相在 MoneyBizFields.kt。
//
// 保存失败的文案说真话：「检查网络后再点一次保存」，不说「已排队自动重试」——
// 离线队列要等整体同步模型拍板（05 的 E15），没有队列就不许诺队列。
//
// ⚠️ 运算符芯片（＋ − × ÷）画在金额卡里，**不挂键盘附件条**：iOS 那边键盘附件条撞上隐藏的 tab bar
// 会把整条导航栈底部 inset 卡死（MoneyAddView.swift:394）。Android 没有同一个坑，但两端版式要一样，照搬。
package com.geek.umbra.feature.money

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.geek.umbra.designsystem.UmbraPrompt
import com.geek.umbra.designsystem.UmbraWhenTab
import com.geek.umbra.designsystem.UmbraPickers
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraBottomBar
import com.geek.umbra.designsystem.UmbraButton
import com.geek.umbra.designsystem.UmbraButtonKind
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraErrorCard
import com.geek.umbra.designsystem.UmbraFieldLabel
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraScreen
import com.geek.umbra.designsystem.UmbraSegment
import com.geek.umbra.designsystem.UmbraSegmentedControl
import com.geek.umbra.designsystem.UmbraSkeleton
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraTone
import com.geek.umbra.designsystem.UmbraTopBarIcon
import com.geek.umbra.designsystem.UmbraTopBarText
import com.geek.umbra.designsystem.UmbraType
import com.geek.umbra.navigation.Route
import com.geek.umbra.net.Ledger
import com.geek.umbra.net.UploadError
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

/** 「什么时候」那一格（日期 / 时间）的日历图标。iOS MoneyAddView.swift:899 写在调用处的字面 path，照抄。 */
private const val CALENDAR_PATH = "M3.5,5L20.5,5L20.5,20.5L3.5,20.5ZM3.5,10L20.5,10M8,3.5L8,6.5M16,3.5L16,6.5"

/** 新建时先攒在本地的一张图（账没落库，服务端没地方挂）。key 对应失败记录。iOS: MoneyAddView.swift:59 */
internal class PendingAtt(val data: ByteArray, val label: String, val key: String = UUID.randomUUID().toString())

/** 传失败的一张：哪一格、哪一档错、多大（「太大」那句要念 MB）。iOS: MoneyAddView.swift:76 */
internal data class AttFail(val key: String, val err: UploadError, val mb: String)

/**
 * 记一笔这一屏的全部状态 + 动作。iOS 是一个 View 上的一堆 @State；Compose 里收进一个 remember 的对象，
 * 动作（保存、传图、扫票）跑在进程作用域（MoneyStore.launch）—— 同 iOS 的 Task：人退了页，那一趟照样跑完。
 */
internal class MoneyAddForm(val id: String?, val focusAccount: Boolean) {
    var expr by mutableStateOf("")
    var dir by mutableStateOf("expense")
    var cat by mutableStateOf<String?>(null)
    var sub by mutableStateOf("")
    var note by mutableStateOf("")
    var atMs by mutableLongStateOf(System.currentTimeMillis())
    /** 「什么时候」面板开在哪一段（"date" / "time"），null = 关着。 */
    var whenTab by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var failed by mutableStateOf(false)
    /** 记账半路新增分类的名字弹层。 */
    /**
     * 编辑态只在**进页那一刻**灌一次值 —— save 之后 store 会静默重拉，不挡住的话再灌一次会把用户正在改的草稿冲掉。
     * ⚠️ 只在真的灌进了值之后才置（umbra-ios#37）：那一笔晚到时第一次 seed 落空，数据到了还要再灌。
     */
    var seeded = false
    val pending = mutableStateListOf<PendingAtt>()
    /** 附件上传中（与 busy 分开：传图不该把「记下这笔」也锁住）。 */
    var attBusy by mutableStateOf(false)
    /**
     * 传失败的那几张。**失败不拦保存**（稿 attachment.uploadFail 第一条）：金额、分类、时间、备注都是本地的，
     * 图只是凭证 —— 因为一张图丢掉一整笔账是更坏的结果。所以它不参与任何禁用判断。
     */
    val attFails = mutableStateListOf<AttFail>()
    /** 新建这一笔存下来之后的账 id：失败的图要能重发到那一笔上。 */
    var savedEntryId by mutableStateOf<String?>(null)
    /** 按 id 拉那一笔是否已经试过、且没拉到（那时不再画骨架，改说「找不到」）。 */
    var lookupFailed by mutableStateOf(false)
    /** 按 id 拉回来一笔（进了 store 的 looked 缓存，但那份不是 StateFlow）：拨一下让页面重读 editing。 */
    var lookTick by mutableIntStateOf(0)
    /** 要把焦点拉回金额框：拨一下，页面那一侧的 LaunchedEffect 去 requestFocus。 */
    var focusTick by mutableIntStateOf(0)

    // —— 经营账本那几件（G2）——
    var invoiceNo by mutableStateOf("")
    /** 撞上的是哪一笔（null = 没撞上，或者还不知道）。iOS: MoneyAddView.swift:122 */
    var invoiceDup by mutableStateOf<InvoiceDup?>(null)
    var invoiceKind by mutableStateOf("")
    var receipt by mutableStateOf(ReceiptState.Pending)
    var rateBp by mutableIntStateOf(0)
    var scan by mutableStateOf<InvoiceScan?>(null)
    var scanning by mutableStateOf(false)
    var scanFail by mutableStateOf<String?>(null)
    /**
     * 税率那一格**人有没有动过**。⚠️ 不能拿 rateBp == 0 当「还没填」：0 是一个合法的税率（免税发票），
     * 分不开的后果是扫一次发票会把他手填的免税改成 6%，且不报错。
     */
    var rateTouched by mutableStateOf(false)
    var netCents by mutableStateOf<Long?>(null)
    var netEdited by mutableStateOf(false)
    var tier by mutableStateOf(DeductTier.Full)
    /** 上一个科目的预设档位（见 onTierAuto）。 */
    var lastTierAuto = DeductTier.Full

    private val router get() = AppGraph.router

    /**
     * 编辑的原条目（身份字段 src / rule_id / batch_id / order_no 要原样带回去）。
     * ⚠️ 走 MoneyStore.entry，**不许只在 entries 里找**（umbra-ios#37）：entries 只装当前月，
     * 找不到就静默走新建分支 —— 表单空着、主钮写「保存修改」，保存出一笔重复账。
     */
    fun editing(): MoneyEntryDTO? = id?.let { MoneyStore.entry(it) }

    /** 这张表是不是账本里已经有的一笔：编辑态，或新建后已经存上过一次（umbra-ios#35 同形）。 */
    val existing: Boolean get() = MoneyAddTarget.isExisting(id, savedEntryId)

    val cents: Long? get() = MoneyAmount.cents(expr)

    /** 这一屏在经营账本上吗。⚠️ 判据取服务端给的 readonly，不取「当前账本是不是 business」。 */
    val isBiz: Boolean get() = MoneyStore.catsAreReadonly

    /** 选中那个科目自己报的档位（服务端给的 deduct_tier）。⚠️ 这是自动值，不是当前值。 */
    val tierAuto: DeductTier get() = cat?.let { s -> MoneyStore.cats.value.firstOrNull { it.slug == s }?.tier } ?: DeductTier.Full

    val accountName: String get() = cat?.let { MoneyStore.catName(it) } ?: ""

    /** 固定资产由科目推，端上只报不算：科目 slug 是 fixed_asset 那一条。 */
    val isAsset: Boolean get() = cat == "fixed_asset"

    /**
     * 能不能存。⚠️ 重号不进这里（InvoiceNoKit.DUP_BLOCKS_SAVE == false）：那一格只说话，不挡人。
     * 编辑态那一笔还没到手（awaitingEntry）时一律不许保存 —— 拿一张空表单去 PUT 那个 id，等于用空值把原来那笔整条改写。
     */
    fun canSave(awaitingEntry: Boolean): Boolean = cents != null && cat != null && !busy && !awaitingEntry

    /**
     * 换科目时把档位跟到新科目的预设上，除非人手改过（recomputeRule：手改过的值一律留着）。
     * 判据是「当前值等不等于**上一个科目**的预设」—— 记住上一个预设，不然换两次科目之后就分不出「人改的」和「跟过来的」。
     * iOS: MoneyAddView.swift:438
     */
    fun onTierAuto(now: DeductTier) {
        if (now == lastTierAuto) return
        if (tier == lastTierAuto) tier = now
        lastTierAuto = now
    }

    /** 底栏那条常驻校验位要说的话（recomputeRule）。**它不拦保存。** */
    val bizDriftNote: String?
        get() {
            if (!isBiz || !MoneyBiz.showsNet(dir)) return null
            return MoneyBiz.netDriftNote(cents, rateBp, netCents, netEdited)
        }

    private val bizPayload: BizPayload?
        get() {
            if (!isBiz) return null
            return BizPayload(
                invoiceNo = invoiceNo, invoiceKind = invoiceKind, receipt = receipt, rateBp = rateBp,
                netCents = if (netEdited) netCents else MoneyBiz.netOf(cents, rateBp), tier = tier,
            )
        }

    /**
     * 底栏那行小字。**金额齐了但分类还空着**时它是页面上唯一说明「为什么按不动」的地方。iOS: MoneyAddView.swift:264
     */
    val hintText: String
        get() {
            if (id == null && cents != null && cat == null) return "还差一个分类 —— 挑一个就能记下了。"
            // 经营账本上「再记一笔」还会清掉票据那几格（发票号是上一张票的，umbra-ios#35）——这句话说的是那颗钮会做什么。
            if (!existing && isBiz) {
                return "金额可以敲算式，例如 32+18；数字键盘打不出的 ＋－×÷ 在金额卡里。「再记一笔」会留着科目和时间，清掉金额、备注和票据那几格。"
            }
            return if (!existing) "金额可以敲算式，例如 32+18；数字键盘打不出的 ＋－×÷ 在金额卡里。「再记一笔」会留着分类和时间，只清金额和备注。"
            else "改完点「保存修改」。金额可以敲算式，数字键盘打不出的 ＋－×÷ 在金额卡里。"
        }

    // MARK: 灌初值

    /**
     * 进页灌初值：编辑态从原条目来；新建走记账设置里的两个默认值。iOS: MoneyAddView.swift:469
     * ⚠️ 两个默认值只作用于「新建」（018-补充 §一 要点 2）：拿默认值去覆盖一条已经存在的账，是替用户改数据。
     */
    fun seed() {
        if (seeded) return
        val e = editing()
        if (id != null && e == null) return
        seeded = true
        if (e != null) {
            // 留一份在 store 里：它若是从 rcptRows 里找到的，保存后静默重拉时可能掉出那一份。
            MoneyStore.keep(e)
            expr = String.format(Locale.ROOT, "%.2f", e.cents.toDouble() / 100)
            dir = e.direction
            cat = e.cat
            sub = e.sub
            note = e.merchant
            atMs = e.at_ms
            // 经营那几件从这条账自己的值来（G2）。编辑态永远读那条账自己的，不拿默认值覆盖。
            invoiceNo = e.invoice_no ?: ""
            invoiceKind = e.invoice_kind ?: ""
            receipt = e.receipt
            rateBp = e.tax_rate_bp ?: 0
            // 这条账自己带的税率算「人填过」：它本来就是人（或上一次扫描）定下的，再扫一次不该把它顶掉。
            rateTouched = e.tax_rate_bp != null
            // 这笔账可能已经有一份发票 PDF 了 —— 重读一次好把那条文件行摆回来。
            // ⚠️ 解析结果不入库，每次重读：一份被缓存下来的解释，会比它解释的那个文件活得更久。
            InvoicePdfKit.pickInvoice(e.attList)?.let { att ->
                MoneyStore.launch {
                    val got = MoneyApi.invoicePdf(att.file_id) ?: return@launch
                    scan = InvoiceScan(att.file_id, att.label.ifEmpty { att.file_id }, got.first, got.second)
                }
            }
            tier = e.tier
            // ⚠️ netEdited 判的是「库里那个值和现在算出来的对不上」—— 不是「这条账有没有 net_cents」。
            // 后者对每一条收入都为真，于是每一条收入一打开就是「改过的」那一档，而那句「本来是多少」是假的。
            val n = e.net_cents
            val auto = MoneyBiz.netOf(e.cents, e.tax_rate_bp ?: 0)
            if (n != null && n > 0 && auto != null && Math.abs(auto - n) > 1) {
                netCents = n
                netEdited = true
            }
        } else {
            dir = MoneySettings.string("money.defaultDirection", "expense")
            applyLastUsedCat(dir)
            focusTick++
        }
    }

    /**
     * 设置到货得比 seed 晚一步。**只在用户什么都没动过时才补灌** —— 一份姗姗来迟的默认值把正在填的东西冲掉，
     * 是替他改了正在填的东西。iOS: MoneyAddView.swift:428
     */
    fun settingsArrived() {
        if (id != null || expr.isNotEmpty() || cat != null || sub.isNotEmpty() || note.isNotEmpty()) return
        dir = MoneySettings.string("money.defaultDirection", "expense")
        applyLastUsedCat(dir)
    }

    /**
     * 新建时预选哪个分类，按「默认分类」这个设置分档：ask（每次都选）→ 留空；last（上次用的）→ 全部流水里时间最近的那一笔
     * （设计侧 172 §四：它回答的是「你上次怎么记的」，不是「这个月怎么记的」，所以单独拉一条不带 ym 的查询）。
     * 找不到（离线、一笔都没有、那个分类被停用了）就退回留空 —— 降级后那个屏是他自己也能选出来的屏，可以静默。
     * iOS: MoneyAddView.swift:552
     *
     * ⚠️ 与 iOS 的一处差别：iOS 无论哪个账本都发 GET /money/entries；生活账本是本机账本（单机化 ②-a），
     * 未登录时那条请求必然落空、本机还没推上去的账也不在服务端 —— 这里生活账本改读 MoneyLocal.list（同样按 at_ms 倒序取第一条）。
     */
    private fun applyLastUsedCat(direction: String) {
        if (MoneySettings.string("money.defaultCategory", "ask") != "last") return
        MoneyStore.launch {
            // limit 1：服务端是 ORDER BY at_ms DESC，第一条就是时间最近的那一笔。不靠「数组倒着取」。
            val r = if (MoneyStore.usesLocal) MoneyLocal.list(direction = direction, limit = 1)
            else MoneyApi.entries(null, direction, null, limit = 1)
            val slug = r?.items?.firstOrNull()?.cat
            if (slug.isNullOrEmpty()) return@launch
            // 那个分类可能已经被停用 / 删掉了：预选一个选不到的值等于让人打开页面就看见一个改不了的错。
            if (MoneyStore.enabledCats(direction).none { it.slug == slug }) return@launch
            // ⚠️ 姗姗来迟的：回来之前人可能已经动手了（或换了方向）。故意不比 id == null（switchDir 是那条不变量的既有例外）。
            if (cat != null || dir != direction) return@launch
            cat = slug
        }
    }

    /**
     * 切方向要把分类切到那一侧 —— 支出选着「餐饮」切到收入，分类栏里根本没有这一项。iOS: MoneyAddView.swift:733
     * 先清空再去问：「上次用的」是一次异步查询，它回来时那道守卫靠 cat == null 判断「人还没自己选」。
     */
    fun switchDir(d: String) {
        if (d == dir) return
        dir = d
        cat = null
        applyLastUsedCat(d)
        sub = ""
    }

    /** 建好立刻选中 —— 用户在记账半路建分类，十有八九就是要给这一笔用。iOS: MoneyAddView.swift:844 */
    fun commitAddCat(raw: String) {
        val name = raw.trim()
        if (name.isEmpty()) { router.showToast("分类得有个名字"); return }
        if (MoneyStore.cats.value.any { it.name == name }) {
            router.showToast("已经有一个叫「$name」的分类了")
            return
        }
        val d = dir
        MoneyStore.launch {
            val slug = MoneyStore.createCat(name, d)
            if (slug == null) { router.showToast("没存上，检查网络后再试"); return@launch }
            cat = slug
            sub = ""
            router.showToast("已加上分类「$name」")
        }
    }

    // MARK: 发票号查重（B2d②）

    private var invoiceJob: Job? = null

    /**
     * 发票号变了。⚠️ 停手再查，不是按一次键查一次（20 位的号敲完是 20 次请求）。
     * ⚠️ 查之前先把上一次的结论清掉 —— 留着的话，人把号改成另一个之后，屏上那句红字说的还是上一个号的事。
     * iOS: MoneyAddView.swift:446
     */
    fun onInvoiceNo(now: String) {
        if (now == invoiceNo) return
        invoiceNo = now
        recheckInvoice()
    }

    private fun recheckInvoice() {
        invoiceJob?.cancel()
        invoiceDup = null
        val now = invoiceNo
        if (!InvoiceNoKit.shouldCheck(now, isBiz)) return
        invoiceJob = AppGraph.scope.launch {
            delay(InvoiceNoKit.DEBOUNCE_MS)
            // ⚠️ 排掉它自己 —— 改一笔既有的账时，那一笔自己就带着这个号。
            val r = MoneyApi.invoiceNoUsed(now, id ?: savedEntryId ?: "")
            // 外层 null = 还不知道（离线 / 老服务端）：不拦，也不说话。
            if (r != null && invoiceNo == now) invoiceDup = r.getOrNull()
        }
    }

    /** 「去看那笔」：收起这一屏、把那一笔打开。⚠️ 不跳月份；但把它的月份留给 store（umbra-ios#37），编辑页按这个月去拉。 */
    fun seeDup(dupId: String) {
        val d = invoiceDup
        if (d != null && d.id == dupId) MoneyStore.hint(dupId, dupYm(d.atMs, d.tzOffsetMin))
        router.back()
        router.go(Route.MoneyAdd(dupId))
    }

    /** 撞号那一笔落在哪个本地月（服务端的 ym 也是按 at_ms + tz_offset_min 算的，同一口径）。iOS: MoneyAddView.swift:672 */
    private fun dupYm(atMs: Long, tzOffsetMin: Int): String {
        val d = Instant.ofEpochMilli(atMs).atOffset(ZoneOffset.ofTotalSeconds(tzOffsetMin * 60))
        return String.format(Locale.ROOT, "%04d-%02d", d.year, d.monthValue)
    }

    // MARK: 扫发票 PDF（B2d①）

    /**
     * 选一份 PDF → 传上去 → 让服务端读文字层 → 把**空着的那几格**填上。iOS: MoneyAddView.swift:191
     * ⚠️ 三步各有各的失败，说出来的是哪一步失败了：传不上去可以重试，读不出字段重试一百次也一样。
     */
    fun scanInvoice(data: ByteArray?, name: String) {
        MoneyStore.launch {
            scanning = true
            scanFail = null
            try {
                if (data == null) { scanFail = InvoicePdfKit.UPLOAD_FAIL_TEXT; return@launch }
                val up = com.geek.umbra.net.Uploads.upload(data, name).first
                if (up == null) { scanFail = InvoicePdfKit.UPLOAD_FAIL_TEXT; return@launch }
                val got = MoneyApi.invoicePdf(up.file_id)
                if (got == null) { scanFail = InvoicePdfKit.PARSE_FAIL_TEXT; return@launch }
                val next = InvoiceScan(up.file_id, name, got.first, got.second)
                scan = next
                // ⚠️ 判「金额这一格空不空」看的是那串算式空不空，不是 cents == 0：0 元是一个合法的金额。
                val plan = InvoicePdfKit.fillPlan(
                    next, invoiceNo,
                    currentCents = if (expr.trim().isEmpty()) null else (cents ?: 0),
                    currentRateBp = if (rateTouched) rateBp else null,
                )
                plan.no?.let { onInvoiceNo(it) }
                // 回填的写法和编辑态开屏那一处一致。
                plan.cents?.let { expr = String.format(Locale.ROOT, "%.2f", it.toDouble() / 100) }
                plan.rateBp?.let { rateBp = it }
                // 编辑一笔已经落库的账（或新建后已经存上、留在本页的那一笔）：当场挂上，不等保存。
                // ⚠️ 挂接的结果不许扔掉（台账 2026-10-01 Money e）：挂失败说一句，再扫一次同一份就是重试。
                val eid = editing()?.id ?: savedEntryId
                if (eid != null) {
                    val ok = MoneyApi.addAtt(eid, up.file_id, name, kind = "invoice")
                    if (!ok) scanFail = InvoicePdfKit.ATTACH_FAIL_TEXT
                }
            } finally {
                scanning = false
            }
        }
    }

    // MARK: 保存

    /**
     * 保存（again = 「再记一笔」）。iOS: MoneyAddView.swift:1345
     * ⚠️ 编辑态没有「接着记」这回事（umbra-ios#35）—— 新建后已经存上的那一笔也一样：清表单之后人敲的下一笔会 PUT 回上一笔。
     */
    fun save(requested: Boolean) {
        val again = requested && MoneyAddTarget.allowsAgain(id, savedEntryId)
        val c = cents ?: run { router.showToast("先填金额"); return }
        val slug = cat ?: run { router.showToast("选一个分类"); return }
        if (busy) return
        // 编辑态那一笔还没到手：身份字段和原值都不在，PUT 出去就是拿空值改写原账（umbra-ios#37）。
        if (id != null && editing() == null) return
        busy = true
        failed = false
        val e = editing()
        // 按下那一刻的样子：要在协程之前取 —— 里面会写 savedEntryId。
        val priorSaved = savedEntryId
        val wasExisting = existing
        val biz = bizPayload
        val route = Route.MoneyAdd(id, focusAccount)
        MoneyStore.launch {
            val result = MoneyStore.save(
                // ⚠️ id 不能丢（umbra-ios#37）：路由带进来的那个 id 兜底；新建存过一次之后再点保存改的是刚存下的那一笔。
                id = e?.id ?: MoneyAddTarget.saveId(id, priorSaved), cents = c, direction = dir, cat = slug, sub = sub,
                merchant = note.trim(), atMs = atMs, src = e?.src,
                ruleId = e?.rule_id ?: "", batchId = e?.batch_id ?: "", orderNo = e?.order_no ?: "",
                biz = biz,
            )
            val savedId = result.getOrElse { err ->
                val why = (err as? MoneyApi.PutFailure)?.why
                if (why is MoneyPutFail.OtherLedger) {
                    // ⚠️ 这一笔已被另一台挪去别的账本（umbra#102）：不落进「没存上」三段、不给重试 ——
                    // 这一档重试永远是同一个 409。说一句、关页；退的只能是这一页。
                    busy = false
                    failed = false
                    router.showToast(MoneyPutFail.movedAwayText(why.to))
                    router.back(ifShowing = route)
                } else {
                    busy = false
                    failed = true
                }
                return@launch
            }
            // 新建时攒下的图此刻才有地方挂：账落库了，逐张上传 + 挂接。失败的留在格子里，账本身已经记上。
            savedEntryId = savedId
            // 扫出来的那份发票 PDF 同理 —— ⚠️ kind = "invoice" 不能漏（漏了就变成一张普通附件：占名额、摆破图）。
            // 只在这次保存**新建**了这一笔时挂；挂接结果要看（台账 2026-10-01 Money e）。
            var invoiceAttached = true
            val s = scan
            if (s != null && MoneyAddTarget.attachesScanOnSave(id, priorSaved)) {
                invoiceAttached = MoneyApi.addAtt(savedId, s.fileId, s.filename, kind = "invoice")
                if (!invoiceAttached) scanFail = InvoicePdfKit.ATTACH_FAIL_TEXT
            }
            if (pending.isNotEmpty()) upload(pending.toList(), savedId)
            busy = false
            val settled = MoneyAddTarget.settled(attFails.size, invoiceAttached)
            // ⚠️ 有图没传上 / 发票没挂上时不进「接着记」：那些属于刚存下的这一笔，清掉表单它们就丢了或挂到下一笔上。
            if (again && settled) {
                // 连着记几笔外卖时分类和时间十有八九不变 —— 留着；金额和备注必换 —— 清掉。
                expr = ""
                note = ""
                // ⚠️ 「接着记」把扫描也一起清掉 —— 那份 PDF 是上一笔的票。
                scan = null
                scanFail = null
                // ⚠️ 下面这几件原来都没清（umbra-ios#35 同屏那条），每一件都是「上一笔的东西带进下一笔」。
                pending.clear()
                attFails.clear()
                savedEntryId = null
                invoiceJob?.cancel()
                invoiceNo = ""
                invoiceDup = null
                invoiceKind = ""
                // 票据状态 / 税率 / 不含税：回到新建页进来时的初值。税率留着的话下一笔的不含税会按上一张票的税率算。
                receipt = ReceiptState.Pending
                rateBp = 0
                rateTouched = false
                netCents = null
                netEdited = false
                router.showToast("记下了，接着记")
                focusTick++
            } else {
                router.showToast(if (wasExisting) "已保存" else "已记下 ${MoneyFmt.yuan(c)}")
                // ⚠️ 有图没传上就不退出这一屏（这条路上唯一能承载那条 strip 的地方就是这一屏）。
                // ⚠️ 退的只能是这一页：route 带全部参数才比得上相等（focusAccount 漏写的话挪完补科目那一屏存完不退）。
                if (settled) router.back(ifShowing = route)
            }
        }
    }
}

/** 记一笔 / 编辑账目。id 为 null = 新建；focusAccount = 挪进经营后打开就停在科目那一格（C9）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoneyAddView(id: String?, focusAccount: Boolean) {
    val c = UmbraTheme.colors
    val router = AppGraph.router
    val form = remember(id, focusAccount) { MoneyAddForm(id, focusAccount) }

    val cats by MoneyStore.cats.watch()
    val entries by MoneyStore.entries.watch()
    val rcptRows by MoneyStore.rcptRows.watch()
    val ledger by MoneyStore.ledger.watch()
    val locked by AppGraph.guard.locked.watch()
    val settingsValues by MoneySettings.values.watch()
    val editing = remember(entries, rcptRows, form.lookTick, id) { id?.let { MoneyStore.entry(it) } }
    val awaitingEntry = id != null && editing == null
    // 经营账本始终在线：未登录时画「要登录」（iOS 路由层的 needsLogin 守卫）；生活账本是本机账本，不看登录。
    val needLogin = locked && !MoneyStore.isLocal(ledger)
    val isBiz = remember(cats) { MoneyStore.catsAreReadonly }

    val amountFocus = remember { FocusRequester() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    // 进页：补拉设置 + 灌初值。深链（快捷指令）会直接落到这一页，不经过记账首页 —— 那条路上没人预热过设置和分类。
    LaunchedEffect(form) {
        MoneyStore.loadIfNeeded()
        MoneySettings.loadIfNeeded()
        form.seed()
        // 编辑态而那一笔不在任何缓存里：按 id（经由它的月份）拉一次，拉到了再灌值。
        if (id != null && MoneyStore.entry(id) == null) {
            val got = MoneyStore.fetchEntry(id)
            if (got == null) form.lookupFailed = true
            else { MoneyStore.keep(got); form.lookTick++; form.seed() }
        }
    }
    // store 首轮还没拉完就进了这一屏（深链 / 冷启动）：数据晚到时补灌一次。seed 自己有守卫。
    LaunchedEffect(editing?.id) { if (editing != null) form.seed() }
    // 设置晚到时补灌两个默认值（只在人什么都没动过时）。
    var settingsSeen by remember { mutableStateOf(MoneySettings.loadedOnce) }
    LaunchedEffect(settingsValues != null) {
        if (settingsValues != null && !settingsSeen) { settingsSeen = true; form.settingsArrived() }
    }
    LaunchedEffect(form.tierAuto) { form.onTierAuto(form.tierAuto) }
    LaunchedEffect(form.focusTick) {
        if (form.focusTick > 0) runCatching { amountFocus.requestFocus() }
    }

    // 内容区的顺序表：key 同时用来滚到「科目」那一格（C9）。
    val keys = buildList {
        when {
            needLogin -> add("needLogin")
            awaitingEntry -> add("lookup")
            else -> {
                if (form.failed) add("fail")
                add("amount")
                if (isBiz) { add("acct"); add("receipt"); add("computed") }
                else { add("recent"); add("grid"); add("tools"); add("subs") }
                add("timeNote")
                if (editing != null && editing.src == "recur" && editing.rule_id.isNotEmpty()) add("recur")
                add("atts")
            }
        }
    }
    // ⚠️ 等这一屏画完再滚 —— 立刻滚的话它量到的是一个还没布局的高度（iOS 等 0.25s）。
    // 那一笔晚到时第一次滚动落了空（科目格还没画出来），所以 key 带上 editing?.id，到了再滚一次。
    LaunchedEffect(focusAccount, editing?.id, isBiz) {
        if (!focusAccount) return@LaunchedEffect
        delay(250)
        val i = keys.indexOf("acct")
        if (i >= 0) listState.animateScrollToItem(i, -listState.layoutInfo.viewportSize.height / 3)
    }

    val pickers = rememberMoneyAttachPickers(form)

    UmbraScreen(
        title = if (id == null) "记一笔" else "编辑账目",
        leading = { UmbraTopBarText("取消", { router.back() }) },
        listState = listState,
        spacing = 13.dp,
        contentPadding = PaddingValues(start = UmbraDimens.pagePadX, end = UmbraDimens.pagePadX, top = UmbraDimens.sp2, bottom = 24.dp),
        actions = {
            // ⚠️ 经营账本里这颗不出（G-h）：它是一颗「去调设置」的钮，而经营科目一件都调不了 ——
            // 留着它、让它通向一张只读表，等于用一个「可以调」的形状，通向一个什么都调不了的地方。
            // iOS 用 SF Symbol slider.horizontal.3；图标清单里最近的是 filter（三条横线），这里用它。
            if (!isBiz && !needLogin) UmbraTopBarIcon(UmbraIconPaths.filter, "管理分类", { router.go(Route.MoneyCats) }, tint = c.muted)
        },
        bottomBar = if (needLogin) null else { { MoneyAddBottomBar(form, awaitingEntry) } },
    ) {
        keys.forEach { k ->
            item(key = k) {
                when (k) {
                    "needLogin" -> MoneyNeedLogin()
                    "lookup" -> LookupState(form)
                    "fail" -> UmbraErrorCard(
                        // 设计侧 219 §四4 / §四5：三段 —— 发生了什么 / 账本里现在是哪一版 + 下一步 / 钮。
                        // 第二段按新增 / 编辑分两档（编辑已有的一笔时「还没进账本」是假话）。字面都在 MoneySaveFail。
                        title = MoneySaveFail.TITLE,
                        body = MoneySaveFail.reason(form.existing),
                        tone = UmbraTone.Danger,
                        actions = listOf(MoneySaveFail.ACTION_LABEL to { form.save(false) }),
                    )
                    "amount" -> AmountCard(form, ledger, amountFocus)
                    "acct" -> {
                        // ⚠️ 只在它确实空着的时候描一圈：补完之后那圈要自己消失，否则它会从「这里要填」退化成一个装饰。
                        // 科目空着时这一格自己说出它空着（MoneyCopy.CAT_MISSING，同流水行上那一格）。
                        val missing = focusAccount && form.cat.isNullOrEmpty()
                        val warn = c.warning
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            BizAccountPicker(
                                cats, form.dir, form.cat, { form.cat = it },
                                Modifier.drawWithContent {
                                    drawContent()
                                    if (missing) {
                                        val pad = 3.dp.toPx()
                                        drawRoundRect(
                                            warn, topLeft = Offset(-pad, -pad), size = Size(size.width + pad * 2, size.height + pad * 2),
                                            cornerRadius = CornerRadius(12.dp.toPx()), style = Stroke(2.dp.toPx()),
                                        )
                                    }
                                },
                            )
                            if (missing) Text(MoneyCopy.CAT_MISSING, color = c.warningText, style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold))
                        }
                    }
                    "receipt" -> BizReceiptGroup(
                        direction = form.dir,
                        kind = form.invoiceKind, onKind = { form.invoiceKind = it },
                        no = form.invoiceNo, onNo = { form.onInvoiceNo(it) },
                        state = form.receipt, onState = { form.receipt = it },
                        // ⚠️ 税率那一格的回调记一笔「人动过」：扫描回填走 rateBp = r 那条直路，不经过这里。
                        rateBp = form.rateBp, onRateBp = { form.rateTouched = true; form.rateBp = it },
                        dup = form.invoiceDup, onSeeDup = { form.seeDup(it) },
                        scan = form.scan, scanning = form.scanning, scanFail = form.scanFail,
                        onScan = { pickers.pickInvoicePdf() },
                    )
                    "computed" -> BizComputedRow(
                        direction = form.dir, cents = form.cents, rateBp = form.rateBp,
                        netCents = form.netCents, netEdited = form.netEdited,
                        onNet = { v, edited -> form.netCents = v; form.netEdited = edited },
                        tier = form.tier, onTier = { form.tier = it }, tierAuto = form.tierAuto,
                        isAsset = form.isAsset, accountName = form.accountName,
                    )
                    "recent" -> RecentRow(form)
                    "grid" -> CatGrid(form)
                    "tools" -> CatToolsRow(form)
                    "subs" -> SubChips(form, cats)
                    "timeNote" -> TimeNoteCard(form)
                    "recur" -> if (editing != null) RecurLinkRow(editing)
                    "atts" -> MoneyAttachmentsCard(form, editing, isBiz, pickers)
                }
            }
        }
    }

    form.whenTab?.let { WhenPicker(form, it) }
}

/** 编辑态、那一笔还没到手时这一格（umbra-ios#37）：拉的时候画骨架；拉不到就明说，不给一张看起来能填的空表单。 */
@Composable
private fun LookupState(form: MoneyAddForm) {
    if (form.lookupFailed) {
        UmbraErrorCard(
            title = "找不到这笔账",
            body = "它可能已经删掉了，或者在另一个账本里。回流水里再点开一次试试。",
            tone = UmbraTone.Warning,
            actions = listOf("返回" to { AppGraph.router.back() }),
        )
    } else UmbraSkeleton()
}

// 账本胶囊（whereAmI）用 MoneyLedgerSwitch.kt 里那一份公开的 LedgerChip，不在这里另画一份。

/** 金额卡：账本胶囊 + 金额框（支持算式）+ 运算符芯片 + 「= ¥…」预览。iOS: MoneyAddView.swift:581 */
@Composable
private fun AmountCard(form: MoneyAddForm, ledger: Ledger, focus: FocusRequester) {
    val c = UmbraTheme.colors
    val cents = form.cents
    Column(Modifier.moneyCard().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // ⚠️ 账本胶囊挂在这一行上，两个账本都挂（whereAmI）：这一刻正是记错账本的那一刻，
        // 一个只在经营账本出现的标识，防不住「经营支出记进生活」这个方向。右对齐、不新增一行。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (form.dir == "expense") "支出金额" else "收入金额", color = c.faint, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.weight(1f).width(8.dp))
            LedgerChip(ledger)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("¥", color = c.muted, style = TextStyle(fontSize = 22.sp), modifier = Modifier.padding(bottom = 6.dp))
            // 数字键盘（验收点名：金额不该弹出符号和拼音）。算式要用的 ＋－×÷ 是下面卡片内的一排芯片。
            val style = TextStyle(
                fontSize = 38.sp, fontWeight = UmbraType.weight650, fontFamily = FontFamily.Monospace,
                color = if (cents != null) c.text else c.faint, textAlign = TextAlign.End,
            )
            // 用 TextFieldValue 而不是 String 版：芯片、「清空」从外面改 form.expr 时，String 版的 BasicTextField
            // 会把光标留在原位置 —— 输 32、点＋、再输 18 得到「3218+」（模拟器实测）。外部改动一律把光标放到末尾。
            var tfv by remember { mutableStateOf(TextFieldValue(form.expr, TextRange(form.expr.length))) }
            if (tfv.text != form.expr) tfv = TextFieldValue(form.expr, TextRange(form.expr.length))
            BasicTextField(
                value = tfv,
                onValueChange = { tfv = it; form.expr = it.text },
                singleLine = true,
                textStyle = style,
                cursorBrush = SolidColor(c.orange),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f).focusRequester(focus),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterEnd) {
                        if (form.expr.isEmpty()) Text("0.00", style = style.copy(color = c.faint), modifier = Modifier.fillMaxWidth())
                        inner()
                    }
                },
            )
        }
        // 运算符芯片：数字键盘打不出 ＋－×÷，从这里补进算式。点芯片顺手把焦点拉回金额框。
        // 高度 44：ClaudeDesign 批次 003 回稿定的值（也正好是触达底线）。
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("+", "-", "×", "÷").forEach { op ->
                val shape = RoundedCornerShape(50)
                Box(
                    Modifier.size(48.dp, 44.dp).clip(shape).background(c.chip).border(UmbraDimens.borderW, c.border, shape)
                        .clickable(role = Role.Button) { form.expr += op; form.focusTick++ },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(op, color = c.orangeText, style = TextStyle(fontSize = 17.sp, fontWeight = UmbraType.weight560, fontFamily = FontFamily.Monospace))
                }
            }
            Spacer(Modifier.weight(1f))
            if (form.expr.isNotEmpty()) {
                Box(
                    Modifier.height(44.dp).clickable(role = Role.Button) { form.expr = ""; form.focusTick++ }.padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("清空", color = c.muted, style = TextStyle(fontSize = 12.5.sp, fontWeight = UmbraType.weight560)) }
            }
        }
        if (MoneyAmount.isExpr(form.expr)) {
            Text(
                cents?.let { "= ${MoneyFmt.yuan(it)}" } ?: "算式还没写完",
                color = if (cents != null) c.orangeText else c.faint,
                style = TextStyle(fontSize = 12.5.sp, fontFamily = FontFamily.Monospace),
                textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 方向切换 + 最近用过。方向放内容区第一行（稿放在导航栏中间，但顶栏塞不下一个像样的分段控件）。
 * 标签「记在哪边」是词表定稿：记一笔、周期记账、新增分类三处同词。iOS: MoneyAddView.swift:712
 */
@Composable
private fun RecentRow(form: MoneyAddForm) {
    val c = UmbraTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        UmbraFieldLabel("记在哪边")
        val dirs = listOf("expense" to "支出", "income" to "收入")
        UmbraSegmentedControl(
            segments = dirs.map { UmbraSegment(it.second) },
            selected = dirs.indexOfFirst { it.first == form.dir }.coerceAtLeast(0),
            onSelect = { form.switchDir(dirs[it].first) },
        )
        // 同 CatGrid：订阅分类与流水并读它们，「最近用过」才会跟着晚到的数据重画
        val cats by MoneyStore.cats.watch()
        val entries by MoneyStore.entries.watch()
        val recent = remember(cats, entries, form.dir) { MoneyStore.recentCats(form.dir) }
        if (recent.isNotEmpty()) {
            Text("最近用过", color = c.faint, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                recent.forEach { CatPill(form, it) }
            }
        }
    }
}

/** 最近用过那一排的分类胶囊：视觉 32、热区 44（不放大控件本体）。只给图标上色、不铺色底（批次 003 定稿）。 */
@Composable
private fun CatPill(form: MoneyAddForm, slug: String) {
    val c = UmbraTheme.colors
    watchMoneyCats() // 图标 / 色槽 / 名字读 cats 快照：form、slug 不变时这一格会被跳过，得自己订阅分类
    val on = form.cat == slug
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.heightIn(min = UmbraDimens.tapMin).clickable(role = Role.RadioButton) { form.cat = slug; form.sub = "" },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.height(32.dp).clip(shape).background(if (on) c.orangeSoft else c.card)
                .border(UmbraDimens.borderW, if (on) c.orange else c.border, shape).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            UmbraIcon(MoneyStore.catArt(slug), if (on) c.orangeText else moneySlotColor(MoneyStore.catSlot(slug)), size = 14.dp)
            Text(MoneyStore.catName(slug), color = if (on) c.orangeText else c.muted, style = TextStyle(fontSize = 13.sp, fontWeight = UmbraType.weight560))
        }
    }
}

/**
 * 分类网格（三列，格高 64）。记一笔的分类格只给图标上色、不铺色底（批次 003 定稿）；选中态整格归橙。iOS: MoneyAddView.swift:783
 * LazyColumn 里不能再嵌一个同向滚动的 LazyVerticalGrid —— 分类就十几个，按三个一行手排。
 */
@Composable
private fun CatGrid(form: MoneyAddForm) {
    val c = UmbraTheme.colors
    // 订阅分类并**真的读它**：by 委托不读就不订阅。深链 / 快捷方式直达这一页时分类晚到，
    // 只读 MoneyStore 快照的话网格永远是空的（模拟器实测，umbra://money/add 冷启动）。
    val cats by MoneyStore.cats.watch()
    val shape = RoundedCornerShape(14.dp) // iOS MoneyAddView.swift:800 圆角 14
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cats.filter { it.direction == form.dir && it.enabled }.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { cat ->
                    val on = form.cat == cat.slug
                    Column(
                        Modifier.weight(1f).height(64.dp).clip(shape).background(if (on) c.orangeSoft else c.card)
                            .border(UmbraDimens.borderW, if (on) c.orange else c.border, shape)
                            .clickable(role = Role.RadioButton) { form.cat = cat.slug; form.sub = "" },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
                    ) {
                        UmbraIcon(MoneyCatArt.icon(cat.slug, cat.icon), if (on) c.orangeText else moneySlotColor(cat.slot), size = 19.dp)
                        Text(
                            cat.name, color = if (on) c.orangeText else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = TextStyle(fontSize = 12.5.sp, fontWeight = UmbraType.weight560),
                        )
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 分类网格下的两个小入口（稿 mAdd 的 addCat / editSubs）。管理子类只在选了分类时出现。iOS: MoneyAddView.swift:813 */
@Composable
private fun CatToolsRow(form: MoneyAddForm) {
    val c = UmbraTheme.colors
    watchMoneyCats() // 「管理「X」的子类」的名字读 cats 快照：编辑深链直达时科目先于分类表到，订阅了分类到了才换成名字
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        val style = TextStyle(fontSize = 12.5.sp, fontWeight = UmbraType.weight560)
        Box(Modifier.heightIn(min = UmbraDimens.tapMin).clickable(role = Role.Button) {
            // iOS 是带输入框的 alert；Android 走共用的输入弹层（UmbraPrompt）。
            UmbraPrompt.present(
                key = "money-add-cat", title = "新增${if (form.dir == "income") "收入" else "支出"}分类",
                message = "先起个名字，子类可以之后在分类里加。", placeholder = "分类名，例如「宠物」", confirmLabel = "创建分类", cancelLabel = "取消",
            ) { form.commitAddCat(it) }
        }, contentAlignment = Alignment.Center) {
            Text("＋ 新增分类", color = c.muted, style = style)
        }
        form.cat?.let { slug ->
            Box(Modifier.heightIn(min = UmbraDimens.tapMin).clickable(role = Role.Button) { AppGraph.router.go(Route.MoneyCat(slug)) }, contentAlignment = Alignment.Center) {
                Text("管理「${MoneyStore.catName(slug)}」的子类", color = c.muted, style = style)
            }
        }
    }
}

/** 子类芯片：子类从服务端来，跟着分类列表一起下发。再点一次取消 —— 二级可跳过。iOS: MoneyAddView.swift:864 */
@Composable
private fun SubChips(form: MoneyAddForm, cats: List<MoneyCatDTO>) {
    val c = UmbraTheme.colors
    val subs = form.cat?.let { s -> cats.firstOrNull { it.slug == s }?.subList } ?: emptyList()
    if (subs.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        subs.forEach { s ->
            val on = form.sub == s
            val shape = RoundedCornerShape(50)
            // 芯片仍 30，行 44（设计侧 171 §五）：这一排高了 14，裁定里认了这个代价。
            Box(
                Modifier.heightIn(min = UmbraDimens.tapMin).clickable(role = Role.Checkbox) { form.sub = if (on) "" else s },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.height(30.dp).clip(shape).background(if (on) c.orange else c.card)
                        .border(UmbraDimens.borderW, if (on) c.orange else c.border, shape).padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // 选中是强调实底，上面的字读 onAccent、字重 ≥ 600（主题规格 clientConstraints：onAccent 门槛只有 3.0，靠字重补；iOS a95213a）。
                    Text(s, color = if (on) c.onAccent else c.muted, style = TextStyle(fontSize = 12.5.sp, fontWeight = if (on) FontWeight.SemiBold else UmbraType.weight560))
                }
            }
        }
    }
}

/** 时间 + 备注那张卡。备注 = 服务端的 merchant（拍板 D1：商家和备注一个字段）。iOS: MoneyAddView.swift:894 */
@Composable
private fun TimeNoteCard(form: MoneyAddForm) {
    val c = UmbraTheme.colors
    Column(Modifier.moneyCard()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("时间", color = c.text, style = TextStyle(fontSize = 15.sp), modifier = Modifier.weight(1f))
            TimeChip(CALENDAR_PATH, dateLabel(form.atMs)) { form.whenTab = "date" }
            TimeChip(UmbraIconPaths.clock, clockLabel(form.atMs), mono = true) { form.whenTab = "time" }
        }
        MoneyRowSeparator()
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("备注", color = c.text, style = TextStyle(fontSize = 15.sp))
            val style = TextStyle(fontSize = 14.5.sp, color = c.text, textAlign = TextAlign.End)
            BasicTextField(
                value = form.note, onValueChange = { form.note = it }, singleLine = true, textStyle = style,
                cursorBrush = SolidColor(c.orange), modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterEnd) {
                        if (form.note.isEmpty()) Text("可不填", style = style.copy(color = c.faint), modifier = Modifier.fillMaxWidth())
                        inner()
                    }
                },
            )
        }
    }
}

/** 时间那一行的两颗芯片：视觉 36、热区 44（它所在的行本来就是 48）。 */
@Composable
private fun TimeChip(icon: String, label: String, mono: Boolean = false, onClick: () -> Unit) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(UmbraDimens.radiusControl)
    Box(Modifier.heightIn(min = UmbraDimens.tapMin).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Row(
            Modifier.height(36.dp).clip(shape).background(c.chip).border(UmbraDimens.borderW, c.border, shape).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            UmbraIcon(icon, c.muted, size = 15.dp)
            Text(
                label, color = c.text,
                style = TextStyle(fontSize = 14.sp, fontWeight = UmbraType.weight560, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default),
            )
        }
    }
}

/** 「今天」「昨天」或「M月d日」。 */
private fun dateLabel(ms: Long): String {
    val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (d) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> "${d.monthValue}月${d.dayOfMonth}日"
    }
}

private fun clockLabel(ms: Long): String {
    val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    return String.format(Locale.ROOT, "%02d:%02d", t.hour, t.minute)
}

/**
 * 「什么时候」面板 —— 走共用的 UmbraDateTimePanel 的 when 变体（iOS umbraWhenPicker：日期 + 时间是一个瞬间、
 * 一个值，同一个面板两段切 —— 批次 007）。弹层由 Router 托管，弹出后立刻放下旗子。
 */
@Composable
private fun WhenPicker(form: MoneyAddForm, tab: String) {
    LaunchedEffect(tab) {
        UmbraPickers.presentWhen("时间", form.atMs, if (tab == "date") UmbraWhenTab.Date else UmbraWhenTab.Time) { form.atMs = it }
        form.whenTab = null
    }
}

/**
 * 周期生成的账在编辑页给一条回规则的路（流水行上的徽章不可点 —— 14px 命中区嵌在可左滑的行里必然误触）。
 * 规则可能已删（删规则不删流水）—— 那就说人话，别装作能跳。iOS: MoneyAddView.swift:941
 */
@Composable
private fun RecurLinkRow(e: MoneyEntryDTO) {
    val c = UmbraTheme.colors
    val rules by MoneyStore.recurRules.watch()
    val rule = rules.firstOrNull { it.id == e.rule_id }
    Row(
        Modifier.fillMaxWidth().heightIn(min = UmbraDimens.tapMin).clickable(role = Role.Button) {
            if (MoneyStore.recurRule(e.rule_id) != null) AppGraph.router.go(Route.MoneyRecurEdit(e.rule_id))
            else AppGraph.router.showToast("这条账的周期规则已经删了，账还在")
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MoneySrc.badge("recur")?.let { UmbraIcon(it.second, c.orangeText, size = 14.dp) }
        Text(
            rule?.let { "由周期规则「${it.name}」自动记入" } ?: "由周期规则自动记入（规则已删除）",
            color = c.muted, style = TextStyle(fontSize = 12.5.sp), modifier = Modifier.weight(1f),
        )
        if (rule != null) Text("查看规则", color = c.orangeText, style = TextStyle(fontSize = 12.5.sp, fontWeight = UmbraType.weight560))
    }
}

/** 底栏：「再记一笔」+ 主钮 + 经营校验位 + 那行提示。iOS: MoneyAddView.swift:1304 */
@Composable
private fun MoneyAddBottomBar(form: MoneyAddForm, awaitingEntry: Boolean) {
    val c = UmbraTheme.colors
    val canSave = form.canSave(awaitingEntry)
    UmbraBottomBar {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                // ⚠️ 编辑态不出「再记一笔」（umbra-ios#35）：点完清空表单、人照吐司敲下一笔再保存，第二次 PUT 回同一个 id。
                // 新建后存上、图没传上留页的那一笔同理（existing）。
                if (MoneyAddTarget.allowsAgain(form.id, form.savedEntryId)) {
                    UmbraButton("再记一笔", { form.save(true) }, Modifier.width(112.dp), kind = UmbraButtonKind.Secondary, enabled = canSave)
                }
                UmbraButton(if (form.existing) "保存修改" else "记下这笔", { form.save(false) }, Modifier.weight(1f), enabled = canSave)
            }
            // ⚠️ 经营那条常驻校验位（recomputeRule）：人手改过不含税、而它和自动值对不上时说一句 —— 它不拦保存。
            form.bizDriftNote?.let { Text(it, color = c.warningText, style = TextStyle(fontSize = 11.5.sp, lineHeight = 17.8.sp)) }
            // ⚠️ 金额敲完了但还没选分类时换一句（批次 019）：那时主钮是灰的、按下去什么都不会发生。
            Text(form.hintText, color = c.muted, style = TextStyle(fontSize = 11.5.sp, lineHeight = 17.8.sp))
        }
    }
}
