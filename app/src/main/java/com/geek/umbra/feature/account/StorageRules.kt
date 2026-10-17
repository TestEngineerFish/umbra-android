// 存储容量的**纯判据** —— 照抄 iOS Networking/StorageRules.swift（设计侧批次 234 / 235，与 PC 同一份口径）：
// 字节怎么写成人话、四档怎么认、六项怎么合成「聊天与附件」、保险箱那一格在免费档写什么、管理存储页的组头 / 副行 /
// 「其余 N 项」、四套确认弹窗的字面、413 `storage_full` 那句话。字面以稿为准，不做意译：确认弹窗那句
// 「回收站也算在用量里」必须保留（234 §六）—— 没有它，用户删完看见数字没动，会以为没删掉。
//
// net/Uploads.kt 里已有一份 StorageFmt.fmt（格式化走默认 Locale）；这里的 fmt 用 Locale.ROOT，小数点在任何系统语言下都是「.」。
package com.geek.umbra.feature.account

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** `warn` 四档。认不得的按 ok：服务端加了一档这里没跟上时，屏上是一条正常的用量条，不是一片红。 */
enum class StorageWarn { Ok, Warn, Critical, Full }

/** 一套确认弹窗的三句：标题 / 正文 / 确认钮名。 */
data class StorageAlertCopy(val title: String, val body: String, val button: String)

object StorageRules {
    /** 用量条 / 状态行的色档：ok、warn 橙；critical 警告色；full 红。 */
    enum class Tone { Normal, Warning, Danger }

    /** 状态行的三种图标语义：警告三角 / 圆叉 / 信息圆。 */
    enum class LineKind { Warning, Full, Info }

    /** 条幅「不再提示」的键（iOS UserDefaults 同名；Android 落 Prefs.K.storageWarnSilenced）。只收 warn 档那条。 */
    const val SILENCE_KEY = "storage.warnBannerSilenced"

    /** 四组的顺序与组头。kind 就是 `GET /storage/items?kind=` 的值。 */
    val groups: List<Pair<String, String>> = listOf("files" to "聊天与附件", "kb" to "知识库", "vault" to "保险箱", "trash" to "回收站")

    // MARK: 字节 → 人话

    /** 0 写「0」；<1 MB 写 KB；≥1 GB 两位小数去尾零；100 MB～1 GB 整数；<100 MB 一位小数去尾零。全部 1024 进制。 */
    fun fmt(bytes: Long): String {
        if (bytes <= 0) return "0"
        val kb = bytes / 1024.0
        val mb = kb / 1024
        if (mb < 1) return "${kb.roundToLong()} KB"
        if (mb >= 1024) return trimZeros(String.format(Locale.ROOT, "%.2f", mb / 1024)) + " GB"
        if (mb >= 100) return "${mb.roundToLong()} MB"
        return trimZeros(String.format(Locale.ROOT, "%.1f", mb)) + " MB"
    }

    /** 「1.50」→「1.5」、「1.00」→「1」。只在带小数点时剪（30 不能剪成 3）。 */
    fun trimZeros(s: String): String {
        if (!s.contains('.')) return s
        return s.trimEnd('0').trimEnd('.')
    }

    /** 头行「· N%」：照实写，到期超出时会超过 100。 */
    fun pctText(pct: Double): String = "${pct.roundToLong()}%"

    /** 条宽：封顶 100%，有用量时至少画出一点（0.01）。 */
    fun barFraction(pct: Double, used: Long): Double {
        if (used <= 0) return 0.0
        return min(1.0, max(0.01, pct / 100))
    }

    // MARK: 分档

    fun warn(s: String): StorageWarn = when (s) {
        "warn" -> StorageWarn.Warn
        "critical" -> StorageWarn.Critical
        "full" -> StorageWarn.Full
        else -> StorageWarn.Ok
    }

    /** 免费档 = tier 为 null 或空串。 */
    fun isFree(tier: String?): Boolean = tier.isNullOrEmpty()

    /** 会员到期后的样子：档位回落到免费、用量却超过了新上限。这一条不能关。 */
    fun isOverLimit(tier: String?, used: Long, limit: Long): Boolean = isFree(tier) && limit > 0 && used > limit

    fun tone(w: StorageWarn, overLimit: Boolean = false): Tone {
        if (overLimit) return Tone.Danger
        return when (w) {
            StorageWarn.Ok, StorageWarn.Warn -> Tone.Normal
            StorageWarn.Critical -> Tone.Warning
            StorageWarn.Full -> Tone.Danger
        }
    }

    fun tierLabel(tier: String?, overLimit: Boolean = false): String {
        if (!isFree(tier)) return "会员"
        return if (overLimit) "会员已到期" else "免费档"
    }

    // MARK: 四类

    /** 「聊天与附件」= chat + money + reminder + insp + task + other。缺的键按 0。 */
    fun filesBytes(k: StorageByKindDTO): Long =
        listOf(k.chat, k.money, k.reminder, k.insp, k.task, k.other).sumOf { it ?: 0L }

    fun vaultText(tier: String?, vault: Long): String {
        if (!isFree(tier)) return fmt(vault)
        return if (vault > 0) fmt(vault) + " · 仍在云端，开会员后继续同步" else "0 · 附件只在本机"
    }

    /** 账号页那一格是两列，整句放不下：格里写短句，整句另起一行。回 (cell, note)。 */
    fun vaultCell(tier: String?, vault: Long): Pair<String, String?> {
        if (!isFree(tier)) return fmt(vault) to null
        if (vault > 0) return (fmt(vault) + " · 仍在云端") to ("保险箱 " + vaultText(tier, vault))
        return "0 · 只在本机" to null
    }

    fun vaultRow(tier: String?, vault: Long): String {
        if (!isFree(tier)) return fmt(vault) + " · 到保险箱里管理"
        return vaultText(tier, vault)
    }

    // MARK: 状态行与条幅

    /** 用量格里那一行状态。免费档一律信息圆「开会员可到 1 GB」；到期超出没有（条幅已经说了）。 */
    fun statusLine(warn: StorageWarn, tier: String?, pct: Double, limit: Long, overLimit: Boolean = false): Pair<LineKind, String>? {
        if (overLimit) return null
        if (isFree(tier)) return LineKind.Info to "开会员可到 1 GB"
        return when (warn) {
            StorageWarn.Critical -> LineKind.Warning to "已用 ${pctText(pct)}，快满了。满了之后新文件传不上去，已有的照常能看。"
            StorageWarn.Full -> LineKind.Full to "已用满 ${fmt(limit)}。清理或升级后才能继续上传，已有的照常能看。"
            StorageWarn.Ok, StorageWarn.Warn -> null
        }
    }

    fun warnBanner(warn: StorageWarn, pct: Double, used: Long, limit: Long): String? {
        if (warn != StorageWarn.Warn) return null
        return "存储已用 ${pctText(pct)}（${fmt(used)} / ${fmt(limit)}）。满了之后新文件传不上去，已有的照常能看。"
    }

    fun expiredBanner(limit: Long): String =
        "会员已到期，上限回落到 ${fmt(limit)}，超出的部分不会丢，清到 ${fmt(limit)} 以下或续费后可以继续上传"

    fun showsUpgrade(tier: String?): Boolean = isFree(tier)

    // MARK: 管理存储页

    fun groupMeta(bytes: Long, count: Int): String? = if (count > 0) "${fmt(bytes)} · $count 项" else null

    fun restLine(rest: StorageRestDTO?): Pair<String, String>? {
        val r = rest ?: return null
        if (r.count <= 0) return null
        return "其余 ${r.count} 项，到对应模块里管理" to fmt(r.bytes)
    }

    fun emptyText(kind: String): String = if (kind == "trash") "回收站是空的。" else "这一类还没有东西。"

    fun sourceLabel(key: String): String = when (key) {
        "chat" -> "聊天"
        "money" -> "记账"
        "reminder" -> "提醒"
        "insp" -> "灵感"
        "task" -> "任务"
        else -> "其他"
    }

    fun subline(kind: String, item: StorageItemDTO, dateText: String): String = when (kind) {
        "trash" -> {
            val base = "原在知识库 · ${item.source}"
            item.left_days?.let { "$base · 还剩 $it 天" } ?: base
        }
        "kb" -> "知识库 · ${item.source} · $dateText"
        else -> "${sourceLabel(item.source)} · $dateText"
    }

    /** 组内按大小倒序（稳排序：同大小保持服务端顺序）。 */
    fun sorted(items: List<StorageItemDTO>): List<StorageItemDTO> = items.sortedByDescending { it.bytes }

    // MARK: 四套确认弹窗

    fun deleteFileAlert(name: String, bytes: Long) = StorageAlertCopy(
        "删除「$name」？", "删除后马上腾出 ${fmt(bytes)}，不能找回。引用它的聊天或账目会显示「附件已删除」。", "删除",
    )

    fun deleteKbDocAlert(name: String, bytes: Long, keepDays: Int?) = StorageAlertCopy(
        "删除「$name」？", "删除后会移入回收站，保留 ${keepDays ?: 30} 天。回收站也算在用量里，要腾出这 ${fmt(bytes)} 还得清空回收站。", "删除",
    )

    fun purgeAlert(name: String, bytes: Long) = StorageAlertCopy(
        "彻底删除「$name」？", "删除后不能再找回，马上腾出 ${fmt(bytes)}。", "彻底删除",
    )

    fun clearTrashAlert(count: Int, bytes: Long) = StorageAlertCopy(
        "清空回收站？", "清空后这 $count 项立刻删掉，不能再找回，马上腾出 ${fmt(bytes)}。", "清空",
    )

    fun restoredToast(name: String, kbName: String): String = "已找回「$name」，放回知识库「$kbName」"

    // MARK: 413 storage_full

    fun storageFullMessage(used: Long, limit: Long, incoming: Long): String =
        "存储已满：已用 ${fmt(used)} / ${fmt(limit)}，这个文件 ${fmt(incoming)} 放不下。清理或升级会员后再试。"

    /** 从错误体顶层读一个整数字段：数 / 浮点 / 数字串都认；缺了按 0 —— 「已用 0 / 0」比整段话消失好认。 */
    fun long(body: JsonObject, key: String): Long {
        val p = body[key] as? JsonPrimitive ?: return 0
        return p.longOrNull ?: p.doubleOrNull?.toLong() ?: p.contentOrNull?.toLongOrNull() ?: 0
    }
}
