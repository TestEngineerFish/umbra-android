// 记账 · 一笔流水的详情 —— 照抄 iOS Features/Money/MoneyDetailView.swift（PC 同一件东西是 EntryDetail.tsx）。
//
// 一笔账最常见的动作是「点开看看」：订单号、批次号、凭证图只在这里，它们是账单导入的那些笔唯一能自证来历的东西。
//
// ## 四条刻意的口径（iOS 文件头）
// 1. **是底部弹层，不是推入页**：详情是「瞥一眼确认一下」，推入页会让人以为自己「进去了」。
// 2. ⚠️ **详情里不放删除**（和 PC 有意分叉）：这一行的左滑里已经有删除了，再放一颗是同一个破坏性动作的第二个入口。
// 3. **编辑走关掉再推**：先关弹层再 router.go，否则推入页会被弹层盖在下面。
// 4. **每张图都能点开看**：这一屏是只读的，没有 ×，「预览只留给原始截图」那条规矩挡的手势冲突在这里不存在。
package com.geek.umbra.feature.money

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.SubcomposeAsyncImage
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraAttGonePlaceholder
import com.geek.umbra.designsystem.UmbraButton
import com.geek.umbra.designsystem.UmbraButtonKind
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraFixedColors
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraSectionLabel
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraTopBarIcon
import com.geek.umbra.navigation.Route
import com.geek.umbra.navigation.UmbraSheet
import com.geek.umbra.net.UmbraImages
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 打开一笔的详情弹层。分类名 / 色槽 / 图标在**点开那一刻**取好传进去：
 * 这一屏显示的是点开那一刻的那一笔，订阅 store 反而会在后台刷新把内容换掉（人还在看着）。iOS: MoneyViews.swift:841
 */
fun showMoneyEntryDetail(e: MoneyEntryDTO) {
    val router = AppGraph.router
    val name = MoneyStore.catName(e.cat)
    val slot = MoneyStore.catSlot(e.cat)
    val art = MoneyStore.catArt(e.cat)
    router.present(UmbraSheet("money-detail-${e.id}") { dismiss ->
        MoneyEntryDetail(
            entry = e, catName = name, catSlot = slot, catArt = art,
            onClose = dismiss,
            // 编辑：先关掉弹层再推（iOS 用 sheet 的 onDismiss 钩子做同一件事）。
            onEdit = { dismiss(); router.go(Route.MoneyAdd(e.id)) },
        )
    })
}

/** 详情面板。只读 —— 它不持有 store，改动一律回到「记一笔」那一屏去做。iOS: MoneyDetailView.swift:28 */
@Composable
fun MoneyEntryDetail(
    entry: MoneyEntryDTO,
    catName: String,
    catSlot: Int,
    catArt: String,
    onClose: () -> Unit,
    onEdit: () -> Unit,
) {
    // ⚠️ 只有图：发票 PDF 不在这儿（invoicePdfIsNotAnAttachment）—— 摆进去是一个永远加载不出来的缩略图。
    val atts = InvoicePdfKit.photos(entry.attList)
    /** 点开的那一张的下标（null = 没开）。整组一起喂给看图器，在里面能左右翻。 */
    var viewer by remember { mutableStateOf<Int?>(null) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = UmbraDimens.pagePadX).padding(top = 14.dp, bottom = UmbraDimens.sp3).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DetailHeader(entry, catName, catSlot, catArt)
        // 内容可滚：弹层高度有上限，长内容被裁掉的那截要能滚到。
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            AmountBand(entry)
            DetailFields(entry, catName)
            AttsBlock(atts) { viewer = it }
        }
        Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            UmbraButton("关闭", onClose, Modifier.weight(1f), kind = UmbraButtonKind.Secondary)
            UmbraButton("编辑", onEdit, Modifier.weight(1f))
        }
    }
    viewer?.let { start -> MoneyImageViewer(atts, start) { viewer = null } }
}

/** 头：分类图块用回流水行那一颗，让人一眼认出自己点开的是刚才那一行（弹层升起时列表被盖住了）。iOS: MoneyDetailView.swift:73 */
@Composable
private fun DetailHeader(entry: MoneyEntryDTO, catName: String, catSlot: Int, catArt: String) {
    val c = UmbraTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        MoneyCatBlock(catSlot, catArt, block = UmbraDimens.iconBlockMD, iconSize = 17.dp, corner = UmbraDimens.radiusControl)
        Spacer(Modifier.width(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("这一笔", color = c.text, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
            Text(
                catName + (if (entry.sub.isEmpty()) "" else " · ${entry.sub}"),
                color = c.faint, style = TextStyle(fontSize = 11.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 金额带（这一屏的主角，和「记一笔」同一个形制：30 / 等宽）。iOS: MoneyDetailView.swift:97（圆角 11 写死） */
@Composable
private fun AmountBand(entry: MoneyEntryDTO) {
    val c = UmbraTheme.colors
    val income = entry.income
    val shape = RoundedCornerShape(11.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(c.bg).border(UmbraDimens.borderW, c.border, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MoneyFitText(
            MoneyFmt.signed(entry.cents, income), if (income) c.success else c.text, 30.sp,
            Modifier.weight(1f), weight = FontWeight.SemiBold, mono = true, minScale = 0.6f,
        )
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier.height(24.dp).clip(RoundedCornerShape(50)).background(if (income) c.successSoft else c.chip).padding(horizontal = 9.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (income) "收入" else "支出", color = if (income) c.success else c.muted, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
        }
    }
}

/** 字段区。iOS: MoneyDetailView.swift:122 */
@Composable
private fun DetailFields(entry: MoneyEntryDTO, catName: String) {
    val c = UmbraTheme.colors
    val v = TextStyle(fontSize = 12.5.sp, color = c.text)
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        FieldRow("分类") { Text(catName + (if (entry.sub.isEmpty()) "" else " · ${entry.sub}"), style = v) }
        FieldRow("时间") { Text(whenLabel(entry.at_ms), style = v.copy(fontFamily = MoneyMono)) }
        // 商家/备注可能是空的（账单里那一格就是 `/`）——「（没写）」而不是留一行空白，空白像没渲染出来。
        FieldRow("商家/备注") {
            if (entry.merchant.isEmpty()) Text("（没写）", style = v.copy(color = c.faint))
            else Text(entry.merchant, style = v)
        }
        // 手记不画徽章：它是默认值，画出来等于给每一笔加一个没有信息的标签。
        MoneySrc.badge(entry.src)?.let { b ->
            FieldRow("来源") { Text(b.first, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.muted)) }
        }
        // 订单号：等宽 + 可选中 —— 它存在的全部意义就是拿去和别处比对，看得见却复制不走等于没给。
        if (entry.order_no.isNotEmpty()) {
            FieldRow("订单号") { SelectionContainer { Text(entry.order_no, style = TextStyle(fontSize = 11.5.sp, fontFamily = MoneyMono, color = c.text)) } }
        }
        // ⚠️ 批次号在手机上**只能是一段文字**（如实的降级）：「按批次撤销」在 PC 的导入向导里，手机这边没有那份历史。
        // 字段为空时整行不出，不写「—」。
        if (entry.batch_id.isNotEmpty()) {
            FieldRow("批次号") {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    SelectionContainer { Text(entry.batch_id, style = TextStyle(fontSize = 11.5.sp, fontFamily = MoneyMono, color = c.text)) }
                    Text("账单导入和整批撤销只在电脑端做。这个号是拿去跟那一批对上的。", color = c.faint, style = TextStyle(fontSize = 11.sp, lineHeight = 15.sp))
                }
            }
        }
    }
}

/** 一行字段：左边定宽 62 的字段名 + 右边的值。定宽是为了几行的值能对齐成一列。iOS: MoneyDetailView.swift:171 */
@Composable
private fun FieldRow(label: String, value: @Composable () -> Unit) {
    val c = UmbraTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, color = c.faint, style = TextStyle(fontSize = 11.5.sp), modifier = Modifier.width(62.dp).padding(top = 1.dp))
        Box(Modifier.weight(1f)) { value() }
    }
}

/** 附件区：没有图时一句话（空的图片区看起来像加载失败）；有图横排缩略图。iOS: MoneyDetailView.swift:185 */
@Composable
private fun AttsBlock(atts: List<MoneyAttDTO>, onOpen: (Int) -> Unit) {
    val c = UmbraTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        UmbraSectionLabel("附件")
        if (atts.isEmpty()) {
            Text("这一笔没有凭证。", color = c.faint, style = TextStyle(fontSize = 11.5.sp))
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                atts.forEachIndexed { i, a ->
                    Column(
                        Modifier.width(78.dp).clickable(role = Role.Button) { onOpen(i) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        val shape = RoundedCornerShape(13.dp)
                        MoneyAttImage(a.file_id, Modifier.size(78.dp).clip(shape).border(UmbraDimens.borderW, c.border, shape), 13.dp, ContentScale.Crop)
                        Text(attName(a), color = c.faint, style = TextStyle(fontSize = 10.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** 图下那个名字：原图叫「原始截图」，其余用标签，没标签叫「图片」。iOS: MoneyDetailView.swift:229 */
private fun attName(a: MoneyAttDTO): String = if (a.origin) "原始截图" else a.label.ifEmpty { "图片" }

/** 附件图片的加载源：`local:` 开头的是本机暂存（MoneyLocal.fileFor 给文件）；其余是服务端 /files/{id}（[UmbraImages.loader] 带令牌）。iOS: HTTPService.swift:796 moneyFileURL */
private fun moneyAttModel(fileId: String): Any? =
    if (MoneyLocalRules.isLocalFileId(fileId)) MoneyLocal.fileFor(fileId) else UmbraImages.fileUrl(fileId)

/**
 * 一张附件图：加载中 = chip 底 + 图片图标；失败 = 「附件已删除」（服务端在「管理存储」里删掉了它，
 * 不画成「还在加载」）。iOS: MoneyDetailView.swift:211 + UmbraAttGone.swift
 */
@Composable
private fun MoneyAttImage(fileId: String, modifier: Modifier, radius: Dp, scale: ContentScale) {
    val c = UmbraTheme.colors
    SubcomposeAsyncImage(
        model = moneyAttModel(fileId),
        imageLoader = UmbraImages.loader,
        contentDescription = null,
        modifier = modifier,
        contentScale = scale,
        loading = {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(radius)).background(c.chip), contentAlignment = Alignment.Center) {
                UmbraIcon(UmbraIconPaths.image, c.faint, size = 18.dp, strokeWidth = 1.8f)
            }
        },
        error = { UmbraAttGonePlaceholder(radius) },
    )
}

/**
 * 全屏看图：整组喂进去，左右翻（iOS 复用 UmbraImageViewer；Android 地基还没有看图器，这里做一个简单的全屏弹层）。
 * ⚠️ 起始页按**过滤之后**的下标算（这里 atts 已是过滤后的那一份，直接用）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MoneyImageViewer(atts: List<MoneyAttDTO>, start: Int, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(initialPage = start.coerceIn(0, (atts.size - 1).coerceAtLeast(0))) { atts.size }
        Box(Modifier.fillMaxSize().background(UmbraFixedColors.viewerBg)) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { i ->
                MoneyAttImage(atts[i].file_id, Modifier.fillMaxSize(), 0.dp, ContentScale.Fit)
            }
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UmbraTopBarIcon(UmbraIconPaths.x, "关闭", onClose, tint = UmbraFixedColors.onNavText)
                Spacer(Modifier.weight(1f))
                Text(
                    "${attName(atts[pager.currentPage])}  ${pager.currentPage + 1}/${atts.size}",
                    color = UmbraFixedColors.onNavText, style = TextStyle(fontSize = 13.sp),
                    modifier = Modifier.widthIn(max = 240.dp).padding(end = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 「2026-08-12 21:32 周三」。给到分不给秒；与 PC 的 whenLabel 同一形制（两端显示成不同的样子，人会以为是两笔）。
 * iOS: MoneyDetailView.swift:290
 */
private fun whenLabel(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm EEE", Locale.CHINA).format(Date(ms))
