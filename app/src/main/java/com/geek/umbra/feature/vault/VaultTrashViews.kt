// 回收站（iOS VaultToolViews.swift:321 `UmbraVaultTrashView`）。挂在「我」Tab（路由区 VaultTrash）。
//
// **界面一页，存储两套**：通用区（灵感 / 任务 / 提醒 / 记账 / 常用语 / 知识库 / 记忆）存在服务端，走 /trash 三个接口；
// 保险箱区端到端加密，走 VaultStore，服务端连它有几条都不知道 —— 锁着时读不出标题，只显示本机缓存里那个明文条数。
// 手机上刻意**不做多选**：每张卡自带「找回 / 彻底删除」。
package com.geek.umbra.feature.vault

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraButton
import com.geek.umbra.designsystem.UmbraButtonKind
import com.geek.umbra.designsystem.UmbraCard
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraEmptyState
import com.geek.umbra.designsystem.UmbraIconBlock
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraScreen
import com.geek.umbra.designsystem.UmbraSectionLabel
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraType
import com.geek.umbra.model.TrashItem
import com.geek.umbra.navigation.Route
import com.geek.umbra.navigation.UmbraAlert
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Calendar

/** 发回服务端时的形状。灵感那一路要还原成整数，别把 "12" 发过去。 */
internal fun TrashItem.entry(): JsonObject = JsonObject(mapOf(
    "kind" to JsonPrimitive(kind),
    "id" to if (kind == "idea") JsonPrimitive(id.toLongOrNull() ?: 0L) else JsonPrimitive(id),
))

/**
 * 服务端的 kind 全集在 `trash.py` 的 `KINDS`：idea / task / reminder / money / phrase / kb_doc / memory。
 * **每一种都显式列一格，default 不给真实类型**：原来 default 是「任务」，phrase 被当成任务显示了很久 —— 显示成了别的东西，比不显示更难查。
 */
private fun kindIcon(kind: String) = when (kind) {
    "idea" -> UmbraIconPaths.bulb; "task" -> UmbraIconPaths.task; "reminder" -> UmbraIconPaths.bell
    "money" -> UmbraIconPaths.wallet; "phrase" -> UmbraIconPaths.messageText; "kb_doc" -> UmbraIconPaths.file
    "memory" -> UmbraIconPaths.memory; else -> UmbraIconPaths.helpCircle
}

@Composable
private fun kindName(kind: String) = when (kind) {
    "idea" -> "灵感"; "task" -> "任务"; "reminder" -> "提醒"; "money" -> "记账"; "phrase" -> "常用语"
    "kb_doc" -> stringResource(R.string.kb_trash_kind); "memory" -> "记忆"; else -> "未知类型"
}

/** 删除时刻 → 「今天 14:02删除」「昨天 22:10删除」「8月17日删除」「2025年8月17日删除」。 */
private fun whenText(ms: Double): String {
    if (ms <= 0) return ""
    val d = Calendar.getInstance().apply { timeInMillis = ms.toLong() }
    val now = Calendar.getInstance()
    val yest = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    val hm = "%02d:%02d".format(d.get(Calendar.HOUR_OF_DAY), d.get(Calendar.MINUTE))
    val s = when {
        same(d, now) -> "今天 $hm"
        same(d, yest) -> "昨天 $hm"
        d.get(Calendar.YEAR) == now.get(Calendar.YEAR) -> "${d.get(Calendar.MONTH) + 1}月${d.get(Calendar.DAY_OF_MONTH)}日"
        else -> "${d.get(Calendar.YEAR)}年${d.get(Calendar.MONTH) + 1}月${d.get(Calendar.DAY_OF_MONTH)}日"
    }
    return s + "删除"
}

@Composable
internal fun VaultTrashContent() {
    val st by VaultStore.state.collectAsState()
    val c = UmbraTheme.colors
    val router = AppGraph.router
    var generic by remember { mutableStateOf<List<TrashItem>>(emptyList()) }
    var keepDays by remember { mutableIntStateOf(30) }
    var loading by remember { mutableStateOf(true) }
    var tick by remember { mutableIntStateOf(0) }
    suspend fun reload() {
        loading = true
        VaultStore.fetchTrash()?.let { generic = it.items; keepDays = it.keep_days ?: 30 }
        loading = false
    }
    LaunchedEffect(tick) { reload() }
    @Suppress("UNUSED_VARIABLE") val snapTick = st.snapTick
    val vaultRows = if (st.unlocked) VaultStore.trashRows() else emptyList()
    val vaultCount = if (st.unlocked) vaultRows.size else st.trashCount
    val empty = generic.isEmpty() && vaultCount == 0

    UmbraScreen(title = "回收站", onBack = { router.back() }, refreshing = loading && generic.isNotEmpty(), onRefresh = { tick++ }) {
        if (!empty) item("intro") {
            Text("删除的东西在这里保留 $keepDays 天，到期彻底删除。保险箱的条目单独加密，锁着时只能看到条数。",
                color = c.muted, style = TextStyle(fontSize = 13.sp, lineHeight = 20.sp), modifier = Modifier.padding(horizontal = 4.dp))
        }
        if (empty && !loading) {
            item("empty") {
                UmbraEmptyState(UmbraIconPaths.trash, "回收站是空的", hint = "删掉的任务、灵感、提醒会先放进来，保留 $keepDays 天。保险箱的条目也在这里，锁着时只显示条数。")
            }
            return@UmbraScreen
        }
        // 通用区
        item("gh") {
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                UmbraSectionLabel("通用")
                Spacer(Modifier.width(8.dp))
                Text("${generic.size} 项", color = c.faint, style = TextStyle(fontSize = 12.sp), modifier = Modifier.weight(1f))
                if (generic.isNotEmpty()) Text("清空", color = c.dangerText, style = TextStyle(fontSize = 13.sp),
                    modifier = Modifier.heightIn(min = UmbraDimens.tapMin).clickable { askPurgeAll(generic.size, vaultCount) { tick++ } }.padding(top = 13.dp))
            }
        }
        if (generic.isEmpty()) item("gEmpty") {
            UmbraCard { Text("通用区没有东西", color = c.faint, style = TextStyle(fontSize = 13.sp), modifier = Modifier.fillMaxWidth()) }
        } else for (it in generic) item("g-${it.kind}:${it.id}") {
            TrashCard(kindIcon(it.kind), it.title, "${kindName(it.kind)} · ${whenText(it.deleted_at_ms)}", it.left_days,
                onRestore = {
                    router.confirm(UmbraAlert(
                        title = "找回「${it.title}」？",
                        // 知识库文档的恢复说的是另一件事：回到库里原位（同名就换个名字），不用重新解析。
                        message = if (it.kind == "kb_doc") AppGraph.context.getString(R.string.kb_trash_restoreBody) else "条目会回到原来的位置，统计也会跟着回来。",
                        confirmLabel = "找回",
                    ) {
                        AppGraph.scope.launch {
                            // 按服务端说的**找回了几条**说话：200 + restored: 0 不是「已找回」。
                            val n = VaultStore.restoreGenericTrash(listOf(it.entry()))
                            if (n == null) { router.showToast("找回失败，服务端没有响应"); return@launch }
                            tick++
                            router.showToast(if (n > 0) "已找回「${it.title}」" else "没找回「${it.title}」：服务端说这一条没能恢复（可能已被彻底删除或过期清掉）")
                        }
                    })
                },
                onPurge = {
                    router.confirm(UmbraAlert(title = "彻底删除「${it.title}」？", message = "不再进回收站，也没有恢复的路。", confirmLabel = "彻底删除", destructive = true) {
                        AppGraph.scope.launch { VaultStore.purgeGenericTrash(listOf(it.entry())); tick++; router.showToast("已彻底删除") }
                    })
                })
        }
        // 保险箱区：条数为 0 就整区不画。
        if (vaultCount > 0) {
            item("vh") {
                Row(Modifier.padding(horizontal = 4.dp).padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    UmbraSectionLabel("保险箱")
                    Spacer(Modifier.width(8.dp))
                    Text("$vaultCount 项", color = c.faint, style = TextStyle(fontSize = 12.sp))
                }
            }
            if (st.unlocked) {
                for (r in vaultRows) item("v-${r.id}") {
                    TrashCard(UmbraIconPaths.lock, r.title, "${r.from} · ${whenText(r.deletedAtMs)}", r.leftDays,
                        onRestore = {
                            router.confirm(UmbraAlert(title = "找回「${r.title}」？", message = when {
                                // 树形库整组回来（293 §八）；组根原来的上一层不在了，就落到最上层（tree 现算生效父节点）。
                                r.tree && r.descendants > 0 -> "会和它下面的 ${r.descendants} 个节点一起回到原来的位置；原来的上一层不在了就放到最上层。"
                                r.tree -> "会回到原来的位置；原来的上一层不在了就放到最上层。"
                                else -> "条目会回到保险箱原来的分组。"
                            }, confirmLabel = "找回") {
                                AppGraph.scope.launch { VaultStore.restoreTrash(r.vaultId, r.itemId) }
                            })
                        },
                        onPurge = {
                            router.confirm(UmbraAlert(title = "彻底删除「${r.title}」？", message = "密文与附件一并擦掉，其它设备下次同步后同样消失。没有恢复的路。",
                                confirmLabel = "彻底删除", destructive = true) {
                                AppGraph.scope.launch { VaultStore.purgeTrash(r.vaultId, r.itemId); router.showToast("已彻底删除") }
                            })
                        })
                }
            } else {
                item("vLocked") {
                    UmbraCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            UmbraIconBlock(UmbraIconPaths.lock, block = 34.dp, icon = 17.dp)
                            Spacer(Modifier.width(UmbraDimens.sp3))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("${st.trashCount} 项 · 解锁后可查看", color = c.text, style = TextStyle(fontSize = 14.5.sp, fontWeight = UmbraType.weight560))
                                Text("这些条目是端到端加密的，锁着时读不出标题。", color = c.muted, style = TextStyle(fontSize = 12.sp, lineHeight = 18.sp))
                            }
                            Text("解锁", color = c.orangeText, style = TextStyle(fontSize = 14.sp, fontWeight = UmbraType.weight560),
                                modifier = Modifier.heightIn(min = UmbraDimens.tapMin).clickable { router.go(Route.VaultHome) }.padding(top = 12.dp))
                        }
                    }
                }
            }
        }
        item("foot") { VaultNote("找回会把条目放回原来的位置。彻底删除不进任何地方，也没有恢复的路。") }
    }
}

/** 一张条目卡：图标块 + 标题 + 「来源 · 何时删除 · 还剩 N 天」（≤ 7 天转 warning —— 这一页唯一的紧迫信号）+ 两颗钮。 */
@Composable
private fun TrashCard(icon: String, title: String, meta: String, leftDays: Int, onRestore: () -> Unit, onPurge: () -> Unit) {
    val c = UmbraTheme.colors
    UmbraCard {
        Column(verticalArrangement = Arrangement.spacedBy(UmbraDimens.sp3)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                UmbraIconBlock(icon, block = 34.dp, icon = 17.dp)
                Spacer(Modifier.width(UmbraDimens.sp3))
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, color = c.text, style = UmbraType.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("$meta · 还剩 $leftDays 天", color = if (leftDays <= 7) c.warningText else c.faint, style = TextStyle(fontSize = 12.5.sp), maxLines = 1)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UmbraButton("找回", onRestore, Modifier.weight(1f), kind = UmbraButtonKind.Secondary, height = 44.dp)
                UmbraButton("彻底删除", onPurge, Modifier.weight(1f), kind = UmbraButtonKind.DangerOutline, height = 44.dp)
            }
        }
    }
}

/** 清空：保险箱那一区要解锁后单独清 —— 不是界面上的取舍，是服务端确实碰不到那一区。 */
private fun askPurgeAll(genericCount: Int, vaultCount: Int, onDone: () -> Unit) {
    val body = if (vaultCount > 0) "通用区 $genericCount 项会被彻底删除。保险箱那 $vaultCount 项要解锁后单独清，这里动不了。"
    else "通用区 $genericCount 项会被彻底删除，没有恢复的路。"
    AppGraph.router.confirm(UmbraAlert(title = "清空回收站？", message = body, confirmLabel = "清空", destructive = true) {
        AppGraph.scope.launch { VaultStore.purgeAllGenericTrash(); onDone(); AppGraph.router.showToast("已清空通用区") }
    })
}
