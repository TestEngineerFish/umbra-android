// 商城 + 道具历史（iOS Features/Me/ShopView.swift）。
// 三组商品：会员（月 / 年）、积分充值、重置卡与加量包。前两组付钱（M1 收单，支付宝）：`POST /shop/checkout` 拿 `pay_url`
// 交给系统开（Custom Tab，支付宝 App 接管），本页进「等待支付」态，每 3 秒问一次订单，到账就刷新；收单没接上时这两组灰。
// 第三组用积分买：先问一句，买完**放进「我的道具」**（sam：「购买只是拥有，并不应该直接使用」）；点一行 → 确认 → 使用。
// 三栏（设计侧批次 269 §五稿 04，同 PC shopPage.head、iOS 25215d3）：商品 / 主题 / 我的道具 · N。主题不进商品：商品是消耗品，主题是装了一直用的。
// iOS 32de292 起撤了「商品」一栏（App Store 不许卖数字商品）；Android 照卖，三栏都留（sam 10-21）。
package com.geek.umbra.feature.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraSegment
import com.geek.umbra.designsystem.UmbraSegmentedControl
import com.geek.umbra.feature.theme.shopThemesSection
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraScreen
import com.geek.umbra.designsystem.UmbraSettingRow
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.settingSection
import com.geek.umbra.feature.me.MeRow
import com.geek.umbra.feature.me.introItem
import com.geek.umbra.feature.me.meSection
import com.geek.umbra.feature.me.openWebLink
import com.geek.umbra.navigation.Route
import com.geek.umbra.navigation.UmbraAlert
import com.geek.umbra.net.ApiResult
import com.geek.umbra.net.BackgroundWork
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

private val router get() = AppGraph.router

/** 等待支付态要记的东西。 */
data class PendingPay(val orderId: Int, val productName: String, val amountCents: Int, val payUrl: String, val expiresAt: Instant)

/**
 * 商城页的数据。进程级：人跳去支付宝付钱时这一页可能被系统回收重建，轮询与「等待支付」那一组不能跟着丢。
 * 一次只允许一笔在等；再下一单先把上一笔的轮询停掉。离开这一页（onDispose）停轮询（iOS onDisappear 同）。
 */
object ShopStore {
    val shop = MutableStateFlow<ShopCatalogDTO?>(null)
    val membership = MutableStateFlow<MembershipDTO?>(null)
    val credits = MutableStateFlow<CreditsDTO?>(null)
    /** 我的道具；null = 还没问到 / 旧服务端没有背包（那一组不画）。 */
    val items = MutableStateFlow<List<ShopItemDTO>?>(null)
    val loaded = MutableStateFlow(false)
    val pending = MutableStateFlow<PendingPay?>(null)
    /** 商城三栏选着哪一栏：0 商品 / 1 主题 / 2 我的道具。进程级：去支付宝付钱回来不跳回第一栏。 */
    val tab = MutableStateFlow(0)
    private var buying = false
    private var pollJob: Job? = null

    suspend fun reload() {
        shop.value = AccountApi.shopProducts()
        membership.value = AccountApi.membership()
        credits.value = AccountApi.credits()
        items.value = AccountApi.shopItems()?.items
        loaded.value = true
    }

    /** 守卫「关」：停轮询、清掉上一个账号的数据。 */
    fun clear() {
        stopWaiting()
        shop.value = null; membership.value = null; credits.value = null; items.value = null; loaded.value = false
    }

    fun stopWaiting() {
        pollJob?.cancel(); pollJob = null; pending.value = null
    }

    /** 问一次订单；到了终态就收尾（吐司 + 刷新）。回 true = 已收尾。 */
    suspend fun pollOnce(w: PendingPay): Boolean {
        val status = AccountApi.shopOrder(w.orderId)?.status
        val o = PayRules.outcome(status, Instant.now(), w.expiresAt)
        val text = PayRules.doneText(o) ?: return false
        stopWaiting()
        if (o == PayWait.Paid) reload()
        router.showToast(text)
        return true
    }

    /** 每 3 秒问一次，直到终态或被取消。订单轮询不是人点的：后台路径。 */
    private fun startPolling(w: PendingPay) {
        pollJob?.cancel()
        pollJob = AppGraph.scope.launch {
            while (true) {
                delay(PayRules.POLL_MS)
                if (withContext(BackgroundWork) { pollOnce(w) }) return@launch
            }
        }
    }

    private fun ref() = ShopRules.buyRef(System.currentTimeMillis() / 1000, Random.nextLong(0, 0xFFFFFFFFL))

    /** 付钱的那一件：确认 → 下单 → 开支付页 → 进等待态。 */
    fun checkout(p: ShopProductDTO) {
        if (buying) return
        router.confirm(UmbraAlert(
            title = "用支付宝付 ${PayRules.amountText(p.price_cents)} 买「${p.name}」？",
            message = "会跳到支付宝付款，付完回到这里自动更新。",
            confirmLabel = "去支付",
        ) {
            buying = true
            AppGraph.scope.launch {
                try {
                    when (val r = AccountApi.shopCheckout(p.key, ref())) {
                        is ApiResult.Ok -> {
                            val url = PayRules.payUrl(r.value) ?: run { router.showToast("服务端没给出支付地址"); return@launch }
                            val expires = PayRules.parseIso(r.value.expires_at) ?: Instant.now().plusMillis(PayRules.FALLBACK_WAIT_MS)
                            val w = PendingPay(r.value.order_id, p.name, r.value.amount_cents, url, expires)
                            pending.value = w
                            startPolling(w)
                            openWebLink(url)
                        }
                        is ApiResult.Err -> router.showToast(PayRules.checkoutFailText(serverLacks = r.status == 503, detail = r.detail.orEmpty()))
                    }
                } finally { buying = false }
            }
        })
    }

    /** 用积分买：确认（写清后果）→ 买 → 刷新。 */
    fun buy(p: ShopProductDTO) {
        if (buying) return
        val charged = ShopRules.chargedPoints(p)
        router.confirm(UmbraAlert(
            title = "用 $charged 积分买「${p.name}」？",
            message = (if (charged < p.price_points) "会员价（原价 ${p.price_points} 积分）。" else "") + ShopRules.buyTail(p.kind),
            confirmLabel = "买",
        ) {
            buying = true
            AppGraph.scope.launch {
                try {
                    when (val r = AccountApi.shopBuy(p.key, ref())) {
                        is ApiResult.Ok -> {
                            membership.value = r.value.status
                            credits.value = AccountApi.credits()
                            shop.value = AccountApi.shopProducts()
                            items.value = AccountApi.shopItems()?.items
                            router.showToast(ItemRules.boughtToast(stored = r.value.item != null, discount = r.value.discount, charged = r.value.charged))
                        }
                        is ApiResult.Err -> router.showToast(r.detail?.takeIf { it.isNotEmpty() } ?: "没买成，稍后再试")
                    }
                } finally { buying = false }
            }
        })
    }

    /** 用一张道具：确认（写清后果）→ 请求 → 刷新；拒了把服务端那句话原样说出来。 */
    fun use(i: ShopItemDTO) {
        if (buying) return
        val q = membership.value?.quota?.get(i.window_kind)
        val at = PayRules.parseIso(q?.resets_at)?.let {
            val z = it.atZone(ZoneId.systemDefault()); "${z.monthValue}月${z.dayOfMonth}日 %02d:%02d".format(z.hour, z.minute)
        } ?: "—"
        router.confirm(UmbraAlert(title = "使用「${i.name}」？", message = ItemRules.confirmBody(i, q, at), confirmLabel = "使用") {
            buying = true
            AppGraph.scope.launch {
                try {
                    when (val r = AccountApi.shopUseItem(i.id)) {
                        is ApiResult.Ok -> { membership.value = r.value.status; router.showToast("已使用「${i.name}」，额度已更新") }
                        is ApiResult.Err -> router.showToast(r.detail?.takeIf { it.isNotEmpty() } ?: "没用成，稍后再试")
                    }
                } finally { buying = false }
                reload()
            }
        })
    }
}

@Composable
fun ShopPage() {
    val c = UmbraTheme.colors
    val shop by ShopStore.shop.collectAsState()
    val membership by ShopStore.membership.collectAsState()
    val credits by ShopStore.credits.collectAsState()
    val items by ShopStore.items.collectAsState()
    val loaded by ShopStore.loaded.collectAsState()
    val pending by ShopStore.pending.collectAsState()
    LaunchedEffect(Unit) { ShopStore.reload() }
    DisposableEffect(Unit) { onDispose { ShopStore.stopWaiting() } }
    val now = Instant.now()

    val tab by ShopStore.tab.collectAsState()
    val usableCount = items?.let { ItemRules.split(it).first.size }
    val segs = listOf(
        UmbraSegment(stringResource(R.string.shop_tabGoods)),
        UmbraSegment(stringResource(R.string.shop_tabThemes)),
        UmbraSegment(stringResource(R.string.shop_tabMine), count = usableCount),
    )

    UmbraScreen(title = "商城", onBack = { router.back() }) {
        item(key = "tabs") { UmbraSegmentedControl(segs, tab, { ShopStore.tab.value = it }) }
        when (tab) {
            1 -> { shopThemesSection(); return@UmbraScreen }
            2 -> {
                val all = items
                if (all == null) settingSection("items", "我的道具", listOf(UmbraSettingRow(if (loaded) "（这台服务器还没有道具）" else "正在读取…", enabled = false)))
                else itemsSection(all, membership, now, c.faint, c.orangeText)
                return@UmbraScreen
            }
        }
        introItem("会员和积分充值用支付宝付；重置卡和加量包用积分买，买了先放进「我的道具」，需要时再用。")
        pending?.let { w ->
            settingSection("wait", "等待支付", listOf(
                UmbraSettingRow(w.productName, value = PayRules.amountText(w.amountCents), mono = true, valueIcon = UmbraIconPaths.spinnerArc, valueIconSpins = true),
                UmbraSettingRow("订单号", value = w.orderId.toString(), mono = true),
                UmbraSettingRow("我已支付", tint = c.orangeText, chevron = true, action = { AppGraph.scope.launch { ShopStore.pollOnce(w) } }),
                UmbraSettingRow("重新打开支付页", chevron = true, action = { openWebLink(w.payUrl) }),
                UmbraSettingRow("取消", tint = c.muted, action = { ShopStore.stopWaiting() }),
            ), footer = "在支付宝付完回到这里会自动更新；没自动更新就点「我已支付」。")
        }
        // 头一组：手里有什么。买之前先看见余额，不用回账号页查。
        val mine = buildList {
            credits?.let { add(UmbraSettingRow("可用积分", value = it.balance.toString(), mono = true)) }
            membership?.let { m ->
                val mem = m.member
                if (mem != null) add(UmbraSettingRow("会员", value = "${mem.tier_name} · 剩 ${MembershipRules.daysLeft(mem.end_at, now)} 天"))
                else add(UmbraSettingRow("会员", value = "还没有", valueTint = c.muted))
                ShopRules.discountText(m.discount_pct)?.let { add(UmbraSettingRow("会员购物", value = it, mono = true)) }
            }
        }
        if (mine.isNotEmpty()) settingSection("mine", "我的", mine)

        val s = shop
        if (s == null || s.items.isEmpty()) {
            settingSection("goods", "商品", listOf(UmbraSettingRow(if (loaded) "（这台服务器还没有商城）" else "正在读取…", enabled = false)))
        } else {
            listOf("会员", "积分充值", "重置卡与加量包").forEach { group ->
                val list = s.items.filter { ShopRules.group(it.kind) == group }
                if (list.isNotEmpty()) settingSection("g-$group", group, list.map { productRow(it, s.payment_connected) })
            }
        }
    }
}

/**
 * 「我的道具」一组（设计侧 236）：组头「我的道具 · N 张可用」；能用的一张一行，副行有效期（快过期带三角），
 * 白用的原因并进副行、「使用」变灰；「历史 N 张」一行点进二级页（不就地展开）。全空只剩一句。
 */
private fun androidx.compose.foundation.lazy.LazyListScope.itemsSection(
    all: List<ShopItemDTO>, membership: MembershipDTO?, now: Instant,
    faint: androidx.compose.ui.graphics.Color, orangeText: androidx.compose.ui.graphics.Color,
) {
    val (usable, history) = ItemRules.split(all)
    if (usable.isEmpty() && history.isEmpty()) {
        meSection("items", "我的道具", listOf(MeRow(UmbraSettingRow("还没有道具", sub = "在「重置卡与加量包」里可以用积分买。"))))
        return
    }
    val rows = usable.map { i ->
        val why = ItemRules.blocker(i, membership?.quota)
        val (warn, rest) = ItemRules.rowSub(i, now, why)
        // 白用的：「使用」灰掉、原因进副行（iOS 一行放不下「原因 + 灰钮」两段）。
        if (why != null) MeRow(UmbraSettingRow(i.name, sub = rest, value = "使用", valueTint = faint), warn = warn)
        else MeRow(UmbraSettingRow(i.name, sub = rest, value = "使用", valueTint = orangeText, chevron = true, action = { ShopStore.use(i) }), warn = warn)
    }.toMutableList()
    if (history.isNotEmpty()) rows += MeRow(UmbraSettingRow("历史", value = "${history.size} 张", chevron = true, action = { router.go(Route.SetShopHistory) }))
    meSection(
        "items", "我的道具 · ${usable.size} 张可用", rows,
        footer = if (usable.isEmpty()) null else "重置卡和加量包买了先放在这里，等额度快用完时再点「使用」。重置卡有有效期，过期作废。",
    )
}

/** 一件商品那一行：付钱的可下单 / 积分的可买 / 都不行就灰字写原因。 */
private fun productRow(p: ShopProductDTO, paymentConnected: Boolean): UmbraSettingRow {
    val price = ShopRules.priceText(p)
    val sub = p.description.ifEmpty { null }
    if (PayRules.canCheckout(p, paymentConnected)) {
        return UmbraSettingRow(p.name, sub = sub, value = price, mono = true, chevron = true, action = { ShopStore.checkout(p) })
    }
    if (!ShopRules.canBuy(p)) {
        return UmbraSettingRow(p.name, sub = sub, value = "$price · ${ShopRules.reasonLabel(p.reason)}", valueTint = null)
    }
    return UmbraSettingRow(p.name, sub = sub, value = price, mono = true, chevron = true, action = { ShopStore.buy(p) })
}

// MARK: - 道具历史（设计侧 236：「历史」一行 push 进这一页，不就地展开）

@Composable
fun ShopHistoryPage() {
    val items by ShopStore.items.collectAsState()
    LaunchedEffect(Unit) { ShopStore.items.value = AccountApi.shopItems()?.items ?: ShopStore.items.value }
    UmbraScreen(title = "道具历史", onBack = { router.back() }) {
        val all = items
        if (all == null) {
            settingSection("h", null, listOf(UmbraSettingRow("正在读取…", enabled = false)))
            return@UmbraScreen
        }
        val history = ItemRules.split(all).second
        if (history.isEmpty()) {
            settingSection("h", null, listOf(UmbraSettingRow("还没有历史", enabled = false)))
            return@UmbraScreen
        }
        // 用过 / 过期 / 退款收回的道具，新到旧。每行「名字 / 时间 / 状态词」。
        settingSection("h", null, history.map { UmbraSettingRow(it.name, sub = ItemRules.historySub(it), value = ItemRules.stateLabel(it.state)) })
    }
}
