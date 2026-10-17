// 经营记账表单那几件的**长相**（设计侧 G2；算术在 BizKit.kt）—— 照抄 iOS Features/Money/BizFields.swift。
// PC 那一侧的同源文件是 UmbraPC/src/features/money/BizFields.tsx。
//
// ## 三层，按「谁填」分，不按「是什么」分（formLayering）
//
// ① 人填的（方向 / 价税合计 / 税率 / 科目 / 时间 / 备注）—— 和生活账本同一个版式；
// ② 票据那几件（发票类型 / 号码 / 票据状态）—— 同源，收成一张带组头的卡；
// ③ 算出来的 —— **常驻、不折叠**。
//
// ⚠️ **为什么不折叠**（computedNotCollapsed 驳掉了折叠那一版）：
// > 一个算出来的数，要能看见它是用什么算的，而折起来的数是看不见的数。
// 代价用形制付掉，不用折叠付：人填的用输入件的长相，算出来的用读数的长相（无边框、15/600 等宽数字 + 一句来源）。
//
// iOS 里那几处写死的小圆角（7 / 8 / 9 / 11 / 14）照抄成 dp 字面量，出处标在各自那一行。
package com.geek.umbra.feature.money

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraSegment
import com.geek.umbra.designsystem.UmbraSegmentedControl
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraType
import java.util.Locale

/**
 * 「¥」不带的两位小数（iOS BizKit.swift:207 MoneyFmtBiz.yuan）。
 * 不走 MoneyFmt.yuan：那个带千分位和「¥」，记号行里的算式念的是裸数。
 */
internal fun bizYuan(cents: Long): String = String.format(Locale.ROOT, "%.2f", cents.toDouble() / 100)

// MARK: - 记号（valueProvenanceMarks）

/** 记号三档：算出来的 / 读来的 / 改过的。 */
enum class BizMarkKind { Calc, Read, Edited }

/**
 * 每一个**不是人当场填的值**，都带一句「它从哪儿来」。iOS: BizFields.swift:28
 *
 * 三档共一个形制（10.5 / 行高 1.6 / 挂在字段正下方），只有颜色分工：
 * ① 算出来的 —— faint + 等宽，写算式；② 读来的（PDF 预填）—— faint；③ 改过的 —— 警示色，写它本来是多少 + 一条回去的路。
 * ⚠️ 人改过它之后，那句话不消失 —— 它改成「本来是多少」。消失的话，屏上就再也看不出这个值是人填的还是算的。
 */
data class BizMark(val kind: BizMarkKind, val text: String, val revertLabel: String? = null, val onRevert: (() -> Unit)? = null)

@Composable
private fun BizMarkView(m: BizMark) {
    val c = UmbraTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            m.text,
            color = if (m.kind == BizMarkKind.Edited) c.warningText else c.faint,
            style = TextStyle(
                fontSize = 10.5.sp, lineHeight = 16.8.sp,
                fontFamily = if (m.kind == BizMarkKind.Calc) FontFamily.Monospace else FontFamily.Default,
            ),
            modifier = Modifier.weight(1f, fill = false),
        )
        if (m.onRevert != null && m.revertLabel != null) {
            // 热区撑到 44：它是一颗真的钮，只是字很小（设计侧 171 §五）。
            Box(
                Modifier.heightIn(min = UmbraDimens.tapMin).clickable(role = Role.Button, onClick = m.onRevert),
                contentAlignment = Alignment.Center,
            ) {
                Text(m.revertLabel, color = c.orangeText, style = TextStyle(fontSize = 10.5.sp, fontWeight = UmbraType.weight560))
            }
        }
    }
}

/** 一个字段：标签 + 内容 + 下面那句记号。iOS: BizFields.swift:57 */
@Composable
fun BizRow(label: String, mark: BizMark? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, color = UmbraTheme.colors.muted, style = TextStyle(fontSize = 11.sp, fontWeight = UmbraType.weight560))
        content()
        if (mark != null) BizMarkView(mark)
    }
}

/**
 * 表单里那种 32 高的小输入框（iOS 各处 `.frame(height: 32)` + 圆角 8 + bg 底 + border 描边）。
 * 收成一处：票据组、税率、不含税四处同一个长相，各写一份迟早漂。
 */
@Composable
internal fun BizInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    fontSize: Float = 12.5f,
    weight: FontWeight = FontWeight.Normal,
    keyboard: KeyboardType = KeyboardType.Text,
    stroke: Color = UmbraTheme.colors.border,
    fill: Color = UmbraTheme.colors.bg,
) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(8.dp) // iOS BizFields.swift:280 圆角 8
    val style = TextStyle(
        fontSize = fontSize.sp, fontWeight = weight, color = c.text,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
    )
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(c.orange),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().height(32.dp).clip(shape).background(fill)
                    .border(UmbraDimens.borderW, stroke, shape).padding(horizontal = 11.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) Text(placeholder, color = c.faint, style = style.copy(color = c.faint))
                inner()
            }
        },
    )
}

/** 组头 / 文件行上那颗小描边钮（视觉 24 / 22、热区 44，iOS 用正负 padding 一对撑热区）。 */
@Composable
private fun BizSmallButton(
    text: String,
    onClick: () -> Unit,
    color: Color,
    stroke: Color,
    height: Dp = 24.dp,
    enabled: Boolean = true,
) {
    Box(
        Modifier.heightIn(min = UmbraDimens.tapMin).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(7.dp) // iOS BizFields.swift:185 圆角 7
        Box(
            Modifier.height(height).border(UmbraDimens.borderW, stroke, shape).padding(horizontal = 9.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, color = color, style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold))
        }
    }
}

// MARK: - 科目选择器（accountVsCategory）

/**
 * 科目**不是分类**：没有色槽、没有图标、没有网格。iOS: BizFields.swift:85
 *
 * ① 生活分类那张彩色网格的长相本身就是「这是你自己建的」这句话 —— 而科目一格都不是；
 * ② 选科目的依据不是「像什么」，是「税表上算哪一栏」，那需要每一档旁边一句话，而网格里塞不进一句话；
 * ③ 「不许改」这件事要说出来，而不是靠点不开来说 —— 所以底下那句脚注是必需的。
 *    （记一笔顶栏那颗「管理分类」在经营表单里不存在，已在 MoneyAddView 里落。）
 */
@Composable
fun BizAccountPicker(cats: List<MoneyCatDTO>, direction: String, value: String?, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = UmbraTheme.colors
    val rows = cats.filter { it.direction == direction && it.enabled }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("科目", color = c.muted, style = TextStyle(fontSize = 11.sp, fontWeight = UmbraType.weight560))
        val shape = RoundedCornerShape(11.dp) // iOS BizFields.swift:120 圆角 11
        Column(Modifier.fillMaxWidth().clip(shape).border(UmbraDimens.borderW, c.border, shape)) {
            rows.forEachIndexed { i, cat ->
                val on = value == cat.slug
                Row(
                    Modifier.fillMaxWidth().heightIn(min = UmbraDimens.tapMin)
                        .background(if (on) c.orangeSoft else c.card)
                        .clickable(role = Role.RadioButton) { onPick(cat.slug) }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        cat.name, color = if (on) c.orangeText else c.text,
                        style = TextStyle(fontSize = 13.5.sp, fontWeight = if (on) UmbraType.weight560 else FontWeight.Normal),
                    )
                    Spacer(Modifier.width(8.dp).weight(1f))
                    // ⚠️ 每一档旁边那句口径 —— 它由服务端给（deduct_label），端上不许自己按档位键拼：税的口径只许有一个出处。
                    Text(cat.deduct_label ?: "", color = c.faint, style = TextStyle(fontSize = 11.sp), textAlign = TextAlign.End)
                }
                if (i != rows.lastIndex) MoneyRowSeparator(tint = c.border)
            }
        }
        // ③ 那句脚注。它不是补充说明，它是这一条裁定的落点。
        Text(
            "科目是年度 B 表的口径，不能增删改。生活账本那套分类不在这儿。",
            color = c.faint, style = TextStyle(fontSize = 11.sp, lineHeight = 18.sp),
        )
    }
}

// MARK: - 票据那一组（formLayering ②）

/**
 * 发票类型 / 号码 / 票据状态收成一张带组头的卡 —— 它们同源：扫一张 PDF 进来它们一起被填上。iOS: BizFields.swift:151
 *
 * ⚠️ 发票 PDF 是这一组的第一件，不是一张附件（invoicePdfIsNotAnAttachment）：组头右侧一颗「扫发票 PDF」，解析后组内出一条文件行。
 * ⚠️ 支出侧的税率在这一组里，标签叫「票面税率」、记号写「发票第 3 行 · 不参与计算」（directionShapesTheForm ①）。
 *
 * [dup]：这个号已经被哪一笔用过（null = 没撞上或者还不知道）。[scan]：扫出来的那一份（null = 还没扫过）。
 */
@Composable
fun BizReceiptGroup(
    direction: String,
    kind: String,
    onKind: (String) -> Unit,
    no: String,
    onNo: (String) -> Unit,
    state: ReceiptState,
    onState: (ReceiptState) -> Unit,
    rateBp: Int,
    onRateBp: (Int) -> Unit,
    dup: InvoiceDup?,
    onSeeDup: ((String) -> Unit)?,
    scan: InvoiceScan?,
    scanning: Boolean,
    scanFail: String?,
    onScan: (() -> Unit)?,
) {
    val c = UmbraTheme.colors
    var parseOpen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp) // iOS BizFields.swift:345 圆角 14
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.card).border(UmbraDimens.borderW, c.border, shape).padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("票据", color = c.faint, style = TextStyle(fontSize = 11.sp, fontWeight = UmbraType.weight560, letterSpacing = 0.055.em))
            Spacer(Modifier.weight(1f))
            // ⚠️ 解析中不把这颗钮换成一句话 —— 钮留在原地、禁用、文字换掉。位置一变，人会以为自己点错了地方。
            if (onScan != null) {
                BizSmallButton(
                    if (scanning) "解析中…" else "扫发票 PDF", onScan,
                    color = if (scanning) c.faint else c.text, stroke = c.border, enabled = !scanning,
                )
            }
        }

        if (scanFail != null) {
            Text(scanFail, color = c.dangerText, style = TextStyle(fontSize = 11.5.sp))
        }

        if (scan != null) {
            // 解析后的那条文件行：文件名 + 读出几个字段 + 一颗「看解析结果」。
            // 第三件是稿明写的 —— 解析这件事必须能点回去看，不然「读出 4 个字段」就是一句没法验证的话。
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)) // iOS BizFields.swift:265 圆角 9
                    .background(c.rail).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            scan.filename, color = c.text, maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
                            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                        )
                        Text(InvoicePdfKit.readLine(scan), color = c.faint, style = TextStyle(fontSize = 11.sp))
                    }
                    BizSmallButton(if (parseOpen) "收起" else "看解析结果", { parseOpen = !parseOpen }, color = c.text, stroke = c.border)
                }
                if (parseOpen) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        InvoicePdfKit.fieldKeys.forEach { k ->
                            val v = InvoicePdfKit.valueText(scan, k)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(InvoicePdfKit.fieldLabel(k), color = c.faint, style = TextStyle(fontSize = 11.sp), modifier = Modifier.width(60.dp))
                                // ⚠️ 没读出来的那几行照样列出来，写「没读出来」：
                                // 一份只写成功项的清单，读它的人要靠记忆才能发现失败项。
                                Text(
                                    v ?: InvoicePdfKit.MISSING_TEXT, color = if (v == null) c.faint else c.text,
                                    style = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                                )
                            }
                        }
                        // ⚠️ 这一句是整个面板的重点：已经填过的格子没有被这次扫描动过。
                        // 没有它，人会以为上面那几格现在都是 PDF 说的，于是他拿 PDF 去核对 PDF —— 那次核对永远通过。
                        Text(
                            InvoicePdfKit.ONLY_EMPTY_HINT, color = c.muted, style = TextStyle(fontSize = 11.sp),
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }

        BizRow("票据状态") {
            val all = ReceiptState.entries
            UmbraSegmentedControl(
                segments = all.map { UmbraSegment(it.label) },
                selected = all.indexOf(state).coerceAtLeast(0),
                onSelect = { onState(all[it]) },
            )
        }

        BizRow("发票类型") {
            BizInput(kind, onKind, "数电普票 / 数电专票 / 不填")
        }

        BizRow("发票号码") {
            // ⚠️ 重号当场说，不等提交（businessLedger.inlineValidation）。
            // ⚠️⚠️ 只提示，不拦任何一步 —— 输入不拦，保存也不拦（InvoiceNoKit.DUP_BLOCKS_SAVE）：
            // 一张发票分摊成两笔是成立的用法，拦住的话他会把号删掉再存 —— 校验把它想抓的那件事赶到了自己看不见的地方。
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                BizInput(no, onNo, "20 位", mono = true, keyboard = KeyboardType.Number, stroke = if (dup == null) c.border else c.danger)
                val at = InvoiceNoKit.dupDate(dup)
                if (dup != null && at != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // ⚠️ 那句话里要有日期 —— 少了日期人只能一条一条翻。
                        Text(
                            InvoiceNoKit.dupText(at.first, at.second), color = c.dangerText,
                            style = TextStyle(fontSize = 11.5.sp), modifier = Modifier.weight(1f, fill = false),
                        )
                        // 第三段是可点的（failureAndErrors 三段式）。
                        if (onSeeDup != null) {
                            BizSmallButton("去看那笔", { onSeeDup(dup.id) }, color = c.dangerText, stroke = c.danger, height = 22.dp)
                        }
                    }
                }
            }
        }

        if (MoneyBiz.rateLivesInReceiptGroup(direction)) {
            BizRow("票面税率", BizMark(BizMarkKind.Read, "发票第 3 行 · 不参与计算")) {
                BizRateField(rateBp, onRateBp)
            }
        }
    }
}

/**
 * 税率输入：界面上是百分数，库里是万分数（1% = 100）。iOS: BizFields.swift:363
 *
 * ⚠️ 受控的百分数要自己存一份字符串：直接拿 bp/100 回填的话，删到空、或者打到一半的「6.」会被立刻改写，光标也会跳。
 * ⚠️ 值从框外变了（扫票回填、编辑页晚到的灌值、「接着记」清零）时字要跟着换（MoneyBiz.rateTextResync，台账 2026-10-01 Money f）。
 * ⚠️ iOS 那边要一个 echo 防「框外改字又被当成人敲的」写回 bp（会把 rateTouched 置上）。
 *    Compose 的 onValueChange 只在人输入时触发，程序改 text 不会回调 —— 所以这里不需要 echo，「人动过」天然只由人的输入置上。
 */
@Composable
fun BizRateField(bp: Int, onBp: (Int) -> Unit) {
    val c = UmbraTheme.colors
    var text by remember { mutableStateOf(MoneyBiz.rateText(bp)) }
    LaunchedEffect(bp) { MoneyBiz.rateTextResync(text, bp)?.let { text = it } }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BizInput(
            text,
            { v ->
                val cleaned = v.filter { it.isDigit() || it == '.' }
                text = cleaned
                onBp(MoneyBiz.bp(cleaned))
            },
            "0", modifier = Modifier.weight(1f), mono = true, keyboard = KeyboardType.Decimal,
        )
        Text("%", color = c.faint, style = TextStyle(fontSize = 12.5.sp))
    }
}

// MARK: - 「Umbra 算的」那一条（computedNotCollapsed + directionShapesTheForm ②）

/**
 * **收入侧一格**（不含税金额）；**支出侧两格**（扣除比例、固定资产）。iOS: BizFields.swift:410
 *
 * ⚠️⚠️ 支出侧不摆「不含税金额」—— 不是折起来，是不存在：小规模纳税人进项抵扣不了，一笔支出的成本就是价税合计。
 * > 一个没有消费者的数，最危险的形态不是没用，是有人会去用它。
 */
@Composable
fun BizComputedRow(
    direction: String,
    cents: Long?,
    rateBp: Int,
    netCents: Long?,
    netEdited: Boolean,
    onNet: (cents: Long?, edited: Boolean) -> Unit,
    tier: DeductTier,
    onTier: (DeductTier) -> Unit,
    tierAuto: DeductTier,
    isAsset: Boolean,
    accountName: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (MoneyBiz.showsNet(direction)) {
            BizNetRow(cents, rateBp, netCents, netEdited, onNet)
        } else {
            BizTierRow(tier, onTier, tierAuto, accountName)
            // ⚠️ 它是算出来的，由科目决定 —— 服务端对一个相反的值直接 400，所以这里不给控件，只报数。
            BizRow("是否固定资产", BizMark(BizMarkKind.Calc, "跟着科目「${accountName.ifEmpty { "—" }}」")) {
                Box(Modifier.height(32.dp), contentAlignment = Alignment.CenterStart) {
                    Text(if (isAsset) "是" else "否", color = UmbraTheme.colors.text, style = TextStyle(fontSize = 15.sp, fontWeight = UmbraType.weight560))
                }
            }
        }
    }
}

/** 收入侧那一格「不含税金额」。读数长相 ↔ 输入件长相（控件的长相就是它的状态）。 */
@Composable
private fun BizNetRow(cents: Long?, rateBp: Int, netCents: Long?, netEdited: Boolean, onNet: (Long?, Boolean) -> Unit) {
    val c = UmbraTheme.colors
    val auto = MoneyBiz.netOf(cents, rateBp)
    var netText by remember { mutableStateOf("") }
    val shown = if (netEdited) netCents else auto
    val mark: BizMark? = when {
        netEdited && auto != null -> BizMark(
            BizMarkKind.Edited, "本来是 ¥${bizYuan(auto)}", "回到自动",
        ) { netText = ""; onNet(null, false) }
        cents != null -> BizMark(BizMarkKind.Calc, "= ${bizYuan(cents)} ÷ ${MoneyBiz.divisorText(rateBp)}")
        else -> null
    }
    BizRow("不含税金额", mark) {
        if (netEdited) {
            // ⚠️ 改过的那一档从读数长相换成输入件长相（card 底 + 警示描边），比另挂一枚徽章强。
            BizInput(
                netText,
                { v ->
                    val cleaned = v.filter { it.isDigit() || it == '.' }
                    netText = cleaned
                    // ⚠️ 上界判在 Double 上，收在 MoneyBiz.cents(fromDecimalText) 一处（umbra-ios#36）。
                    onNet(MoneyBiz.cents(cleaned), true)
                },
                "0.00", mono = true, fontSize = 15f, weight = UmbraType.weight560,
                keyboard = KeyboardType.Decimal, stroke = c.warning, fill = c.card,
            )
        } else {
            // 读数的长相：无边框、15/600 等宽。点一下才变成输入件。
            Box(
                Modifier.fillMaxWidth().height(32.dp).clickable(role = Role.Button) {
                    netText = auto?.let { bizYuan(it) } ?: ""
                    onNet(auto, true)
                },
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    shown?.let { "¥" + bizYuan(it) } ?: "—", color = c.text,
                    style = TextStyle(fontSize = 15.sp, fontWeight = UmbraType.weight560, fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
}

/** 支出侧「扣除比例」：跟着科目的预设走，人手改过就描警示边、记号写「本来是」。iOS: BizFields.swift:490 */
@Composable
private fun BizTierRow(tier: DeductTier, onTier: (DeductTier) -> Unit, tierAuto: DeductTier, accountName: String) {
    val c = UmbraTheme.colors
    var open by remember { mutableStateOf(false) }
    val mark = if (tier == tierAuto) BizMark(BizMarkKind.Calc, "跟着科目「${accountName.ifEmpty { "—" }}」")
    else BizMark(BizMarkKind.Edited, "本来是「${tierAuto.label}」", "回到自动") { onTier(tierAuto) }
    BizRow("扣除比例", mark) {
        Box {
            val shape = RoundedCornerShape(8.dp) // iOS BizFields.swift:506 圆角 8
            Row(
                Modifier.fillMaxWidth().height(32.dp).clip(shape).background(c.bg)
                    .border(UmbraDimens.borderW, if (tier == tierAuto) c.border else c.warning, shape)
                    .clickable(role = Role.DropdownList) { open = true }
                    .padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(tier.label, color = c.text, style = TextStyle(fontSize = 12.5.sp))
                UmbraIcon(UmbraIconPaths.chevronDown, c.faint, size = 12.dp, strokeWidth = 1.8f)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.card, modifier = Modifier.widthIn(min = 180.dp)) {
                DeductTier.entries.forEach { k ->
                    DropdownMenuItem(text = { Text(k.label, color = c.text) }, onClick = { open = false; onTier(k) })
                }
            }
        }
    }
}
