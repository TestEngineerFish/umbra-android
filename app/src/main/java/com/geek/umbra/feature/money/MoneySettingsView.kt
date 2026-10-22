// 记账设置页（018-补充 §一，文案按批次 019 §2.1 定稿）。照 iOS Features/Money/MoneySettingsView.swift。
//
// 入口是记账页页头的齿轮 —— **只影响一个功能的设置，归那个功能的齿轮**（settingsHome.moveOut）。
// 走已有的设置分组渲染器（settingSection / UmbraSettingRow），不另画一套分组卡。
//
// ⚠️ 稿里的四组八行，这一期只做得出三组：「预算」这个功能整体还不存在（服务端没有表也没有路由），
// 所以「预算」那一行、以及依赖它的「超支提醒」都做不出来 —— 摆一颗点了没有目的地的钮，正是设计侧反对的东西。
//
// **值只存服务端**（MoneySettings）：拿不到就不画（骨架 / 重试卡），画一个猜的值出来，人点了会以为改上了。
package com.geek.umbra.feature.money

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraChoice
import com.geek.umbra.designsystem.UmbraChoiceItem
import com.geek.umbra.designsystem.UmbraErrorCard
import com.geek.umbra.designsystem.UmbraScreen
import com.geek.umbra.designsystem.UmbraSettingRow
import com.geek.umbra.designsystem.UmbraSkeleton
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.settingSection
import com.geek.umbra.navigation.Route
import com.geek.umbra.net.ImagePurpose
import com.geek.umbra.net.ImageTier
import com.geek.umbra.net.ServerSettings
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * 键 → 选项（服务端存的值，界面上的字面）。**只有这一处**：行上要拿它把值翻成字面、弹出层要拿它列选项，
 * 两处各写一遍必然漂移。服务端那边 ENUMS 拦的就是这些值，两边对不上时是 400 而不是静默存错。iOS: MoneySettingsView.swift:200
 */
private fun pickOptions(key: String): List<Pair<String, String>> = when (key) {
    "money.defaultDirection" -> listOf("expense" to "支出", "income" to "收入")
    "money.defaultCategory" -> listOf("ask" to "每次都选", "last" to "上次用的")
    else -> emptyList()
}

private fun pickTitle(key: String) = if (key == "money.defaultCategory") "默认分类" else "默认方向"

/** 记账设置。iOS: MoneySettingsView.swift UmbraMoneySettingsView */
@Composable
fun MoneySettingsView() {
    val scope = rememberCoroutineScope()
    // 订阅：设置值（含在途乐观值）、在途键、失败标志、服务端原份（判「拉到过没有」）、档位像素表。
    val values by MoneySettings.values.watch()
    val busy by MoneySettings.busy.watch()
    val failed by MoneySettings.failed.watch()
    val server by ServerSettings.settings.watch()
    val tierPx by ServerSettings.imageTiers.watch()
    // 「11 个分类」「N 条在跑」那两个值要记账数据。
    val cats by MoneyStore.cats.watch()
    val catsLoaded by MoneyStore.catsLoadedOnce.watch()
    val rules by MoneyStore.recurRules.watch()
    val recurLoaded by MoneyStore.recurLoadedOnce.watch()
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        MoneySettings.loadIfNeeded()
        MoneyStore.loadIfNeeded()
    }

    val putFailed = stringResource(R.string.moneySet_putFailed)
    // settingSection 不是 @Composable，组头 / 组尾里走资源的字得先在这里取好。
    val visionTitle = stringResource(R.string.moneySet_visionTitle)
    /** 改一个值：只存服务端；失败说一句。**不说「已经弹回去了」**—— store 是「重新问一次服务端」不是位置回滚。 */
    fun put(key: String, v: JsonPrimitive) = MoneySettings.put(key, v) { AppGraph.router.showToast(putFailed) }

    /** 判据是「拉到过」不是 loading —— 下拉刷新时 loading 也为真，拿它当判据每次刷新整页都会闪一下骨架。 */
    val loadedOnce = server != null
    val readonly = MoneyLedgerRules.readonlyBatch(cats.map { it.isReadonly })

    // MARK: 分类与周期
    // ⚠️ 经营账本里这一行念「科目」而且没有「与子类」—— 名字跟着**里面那张表**走；量词跟着这一行的名字走。
    // 「拿不到」和「你没有」要分开说：分类没拉到时 cats 也是空的，写「0 个分类」就是把连不上说成了你一个都没有。
    val catsValue = if (!catsLoaded) "暂时拿不到" else "${cats.size} 个${if (readonly) "科目" else "分类"}"
    // 数的口径和记账首页那张卡对齐（!paused && next_at_ms > 0）—— 组尾刚说了两处是同一个东西。
    val running = rules.count { !it.paused && it.next_at_ms > 0 }
    val recurValue = if (!recurLoaded) "暂时拿不到" else if (running == 0) "还没设置" else "$running 条在跑"
    val catsRows = listOf(
        UmbraSettingRow(label = if (readonly) "科目" else "分类与子类", value = catsValue, chevron = true, action = { AppGraph.router.go(Route.MoneyCats) }),
        UmbraSettingRow(label = "周期记账", value = recurValue, chevron = true, action = { AppGraph.router.go(Route.MoneyRecur) }),
    )

    // MARK: 记一笔（两个默认值**只作用于「新建」**：编辑既有账目时永远读那条账自己的值）
    fun pickRow(label: String, key: String, fallback: String): UmbraSettingRow {
        // `values.let`：MoneySettings.string 读的是 values.value，这里经由订阅到的 values 读，值一变这一行跟着重画。
        val cur = values.let { MoneySettings.string(key, fallback) }
        val shown = pickOptions(key).firstOrNull { it.first == cur }?.second ?: cur
        return UmbraSettingRow(
            label = label, value = shown, chevron = true, enabled = key !in busy,
            action = {
                // 两个选项的选择器不值得一个新页面：iOS 用系统 confirmationDialog，这里用行式底部弹层。
                UmbraChoice.present(
                    title = pickTitle(key),
                    items = pickOptions(key).map { (wire, text) -> UmbraChoiceItem(text) { put(key, JsonPrimitive(wire)) } },
                    key = "money-set-$key",
                )
            },
        )
    }
    val addRows = listOf(
        pickRow("默认方向", "money.defaultDirection", "expense"),
        pickRow("默认分类", "money.defaultCategory", "ask"),
    )

    // MARK: 识图记账（四句走资源不硬编码：两端一字不差、PC 那边是有英文的）
    val visionOn = values.let { MoneySettings.bool("money.visionEnabled", true) }
    val visionFoot = stringResource(if (visionOn) R.string.moneySet_visionFootOn else R.string.moneySet_visionFootOff) +
        " " + stringResource(R.string.moneySet_shotQualityFoot)
    val visionRow = UmbraSettingRow(
        label = stringResource(R.string.moneySet_visionLabel),
        sub = stringResource(R.string.moneySet_visionSub),
        toggle = visionOn,
        enabled = "money.visionEnabled" !in busy,
        onToggle = { put("money.visionEnabled", JsonPrimitive(!visionOn)) },
    )
    val qualityRow = imageQualityRow(ImagePurpose.Money, stringResource(R.string.imgQ_rowShot), loadedOnce, tierPx, busy.keys, ::put)

    UmbraScreen(
        title = "记账设置",
        onBack = { AppGraph.router.back() },
        refreshing = refreshing,
        // 两个都 await：先起个协程就返回的话，下拉的转圈会立刻停，假装刷新完了，而设置还在飞。
        onRefresh = {
            scope.launch {
                refreshing = true
                MoneySettings.retry()
                MoneyStore.reload(silent = true)
                refreshing = false
            }
        },
    ) {
        if (!loadedOnce) {
            item(key = "skeleton") {
                if (failed) {
                    UmbraErrorCard(
                        title = "设置没拉下来",
                        body = "连不上服务端，或者它这一下没回。开关的值只在服务端有一份，拿不到就不画 —— 画一个猜的值出来，你点了会以为改上了。",
                        actions = listOf(stringResource(R.string.state_retry) to { MoneyStore.launch { MoneySettings.retry() } }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    UmbraSkeleton()
                }
            }
            return@UmbraScreen
        }
        // 稿里这一组叫「分类与预算」。预算不存在，组名跟着内容走。footer 只写人话，token 名进注释不进正文。
        settingSection("cats", "分类与周期", catsRows, footer = "记账页上那张周期卡和这一行通向同一个页面：卡上看下次几时记，这里改规则。")
        settingSection(
            "add", "记一笔", addRows,
            // ⚠️ 描述的是**结果**不是算法（设计侧 172 §四）：描述算法的说明，算法一改就成了假话。
            footer = "默认值只决定打开「记一笔」时预先选中什么，每一笔都还能改。" +
                "「上次用的」按方向找时间最近的那一笔（不分月份），找不到就回「每次都选」——" +
                "不硬塞一个分类，塞了会让人以为系统认得这笔账。",
        )
        // 凭证画质和识图开关同组。⚠️ 识图关掉时画质这一行照样有用（凭证还是会存下来），所以不跟着开关灰掉。
        settingSection("vision", visionTitle, listOf(visionRow, qualityRow), footer = visionFoot)
    }
}

/**
 * 画质那一行（iOS Features/Me/ImageQualityRow.swift UmbraImageQualityRow.row）。和聊天的「发图画质」是同一个件：
 * 控件共用、**默认值不共用**（键是 image.quality.<用途>，默认值由服务端给）。
 * 设置还没拉到时**不画一个猜的档位**；像素数一个都不写死，从服务端那份 image_tiers 取，拿不到就说还没拿到。
 */
@Composable
private fun imageQualityRow(
    purpose: ImagePurpose,
    label: String,
    loadedOnce: Boolean,
    tierPx: Map<String, Int>,
    busyKeys: Set<String>,
    put: (String, JsonPrimitive) -> Unit,
): UmbraSettingRow {
    val c = UmbraTheme.colors
    val unknown = stringResource(R.string.imgQ_unknown)
    val labels = mapOf(
        ImageTier.Saver to stringResource(R.string.imgQ_tierSaver),
        ImageTier.Standard to stringResource(R.string.imgQ_tierStandard),
        ImageTier.Clear to stringResource(R.string.imgQ_tierClear),
        ImageTier.Origin to stringResource(R.string.imgQ_tierOrigin),
    )
    val pending = stringResource(R.string.imgQ_pending)
    // 这一档的那句人话：前三档带长边像素数（从服务端那份表取），「原尺寸」不带数 —— 它本来就不缩放。
    val hints = ImageTier.entries.associateWith { t ->
        val px = tierPx[t.wire]
        when {
            t == ImageTier.Origin -> stringResource(R.string.imgQ_hintOrigin)
            px == null -> pending
            t == ImageTier.Saver -> stringResource(R.string.imgQ_hintSaver, px)
            t == ImageTier.Standard -> stringResource(R.string.imgQ_hintStandard, px)
            else -> stringResource(R.string.imgQ_hintClear, px)
        }
    }
    val tier = MoneySettings.imageTier(purpose)
    if (!loadedOnce || tier == null) return UmbraSettingRow(label = label, sub = unknown, tint = c.faint)
    return UmbraSettingRow(
        label = label, sub = hints[tier], value = labels[tier], chevron = true, enabled = purpose.settingKey !in busyKeys,
        action = {
            // 弹出选择：每一项带那一档的人话，选之前就能比。
            UmbraChoice.present(
                title = label,
                items = ImageTier.entries.map { t ->
                    UmbraChoiceItem(labels.getValue(t), note = hints[t], checked = t == tier) {
                        // 已经是这一档就别发请求：一次没有变化的 PUT 会让行白灰一下，看起来像点错了。
                        if (t != tier) put(purpose.settingKey, JsonPrimitive(t.wire))
                    }
                },
                key = "money-set-quality",
            )
        },
    )
}
