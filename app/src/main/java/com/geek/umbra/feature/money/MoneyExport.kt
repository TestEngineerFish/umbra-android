// 「导出账目」那张面板 —— 照抄 iOS Features/Money/MoneyExportSheet.swift（设计侧 162 §一，D3）。纯判据在 MoneyExportKit.kt。
//
// ## 三处与 PC 分叉（162 §一）
// ① 不是 `⋯` 菜单：手机不做导入，那个菜单只会有一项 —— 一个只有一项的菜单是一层什么都不透露的壳。入口在流水页页头。
// ② 不放进记账设置：导出的范围跟着当前筛选走，而筛选就在流水页那颗钮下面。
// ③ **确认在我们自己的面板里做完，再交给系统分享面板**：系统那一层升起来之后我们再也插不进一句话。
//
// 范围句与主钮上那个笔数都从同一个 `range` 读：开关一变两处一起变（一个不跟着动的数字比不写更糟）。
//
// ⚠️ Android 与 iOS 的差异（报告里写了）：
// · iOS 在系统分享面板**收起时**当场删明文；Android 的 chooser 没有「收起」回调，而且接收方可能在 chooser 关掉之后
//   才去读那个 content:// —— 当场删会让它读空。所以这里改成**每次开始导出前**把 cacheDir 下上一轮留下的清掉，
//   以及面板没走到分享那一步就关掉时清一次。明文最多留到下一次导出（cacheDir 是 App 私有目录，系统空间紧时也会清）。
// · 生成中不许划掉面板（iOS interactiveDismissDisabled）：地基的 UmbraSheet 没有这个开关；划掉时协程随面板取消、
//   并在 onDispose 里清掉写了一半的目录，不留半份。
package com.geek.umbra.feature.money

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraSpinningIcon
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraType
import com.geek.umbra.navigation.UmbraSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 打开导出面板。参数在打开那一刻定下（面板开着时筛选不会变：它盖在流水页上面）。
 * ⚠️ ym 传的是 **loadedYm** 不是 ym（台账 month-switch-stale-response）：翻月那一刻 ym 就变了，而 entries 要等那一轮回来才换。
 * iOS: MoneyViews.swift:819
 */
fun presentMoneyExport(
    filtered: List<MoneyEntryDTO>,
    ym: String,
    dirLabel: String?,
    catLabel: String?,
    rcpt: MoneyExportRcpt?,
) {
    AppGraph.router.present(UmbraSheet("money-export") { dismiss ->
        MoneyExportSheet(filtered, ym, dirLabel, catLabel, catName = { MoneyStore.catName(it) }, onClose = dismiss, rcpt = rcpt)
    })
}

/** 导出面板本体。iOS: MoneyExportSheet.swift:24 */
@Composable
fun MoneyExportSheet(
    /** 当前筛选下的那些（已经在内存里，不用再问服务端）。 */
    filtered: List<MoneyEntryDTO>,
    ym: String,
    dirLabel: String?,
    catLabel: String?,
    /** slug → 显示名。CSV「分类」那一列写显示名（分类被删了才退回 slug，不写空 —— 空格子导回去是「不能空着」）。 */
    catName: (String) -> String,
    onClose: () -> Unit,
    /** 「待补票」那一筛开着时的口径（filtered 那时是整季含往季那一份）。null = 没开那一筛。 */
    rcpt: MoneyExportRcpt? = null,
) {
    val c = UmbraTheme.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportScope by remember { mutableStateOf(MoneyExportScope.Filtered) }
    var withAtts by remember { mutableStateOf(false) }
    /** 「全部账目」那一档的笔数：跨所有月份，内存里只有当前这个月，要问了才知道。null = 还没问到。 */
    var allCount by remember { mutableStateOf<Int?>(null) }
    var counting by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    /** 已经交给系统分享了：面板关掉时就不再清（接收方可能还没读完）。 */
    var shared by remember { mutableStateOf(false) }

    // 面板没走到分享那一步就被关掉（取消、生成失败、划掉）时清一次 —— 不留半份。
    DisposableEffect(Unit) {
        onDispose { if (!shared) sweepTmp(ctx.cacheDir) }
    }

    // 待补票口径只跟「当前筛选」那一档走；「全部账目」本来就不是一个筛选。
    val r = if (exportScope == MoneyExportScope.Filtered) rcpt else null
    val segs = MoneyExportKit.segments(exportScope, ym, MoneyFmt.ymLabel(ym), dirLabel, catLabel, r)
    val range = MoneyExportRange(segs, if (exportScope == MoneyExportScope.All) (allCount ?: 0) else filtered.size, r)
    /** 一笔都没有就不让点：摆一个点了必然什么都不生成的按钮比不摆更糟。 */
    val canExport = !busy && !counting && range.count > 0

    Column(Modifier.fillMaxWidth().background(c.bg).navigationBarsPadding()) {
        Text(
            "导出账目", color = c.text, style = TextStyle(fontSize = 17.sp, fontWeight = UmbraType.weight650),
            modifier = Modifier.padding(horizontal = UmbraDimens.pagePadX).padding(top = UmbraDimens.sp5),
        )
        // 范围那句话。**笔数在这句里**，跟着开关和范围一起重算。
        Text(
            if (counting) "要导的是：全部账目 · 正在数有多少笔…" else range.sentence,
            color = c.muted, style = TextStyle(fontSize = 13.sp, lineHeight = (13 * 1.6).sp),
            modifier = Modifier.padding(horizontal = UmbraDimens.pagePadX).padding(top = UmbraDimens.sp2, bottom = UmbraDimens.sp4),
        )
        MoneyRowSeparator()

        // 「一起导出凭证图」。**默认关**：手机下一步就是发微信 / 存到别处 —— 明文凭证第一次离开 App 就在这儿。
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = !busy, role = Role.Checkbox) { withAtts = !withAtts }
                .padding(horizontal = UmbraDimens.pagePadX),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("一起导出凭证图", color = c.text, style = TextStyle(fontSize = 15.sp), modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(if (withAtts) "会多一个 zip" else "默认不带", color = if (withAtts) c.warningText else c.faint, style = TextStyle(fontSize = 12.5.sp))
            Spacer(Modifier.width(10.dp))
            ExportCheck(withAtts)
        }
        MoneyRowSeparator()

        // 「改成导出全部」/「改回当前筛选」。切到「全部」要先数：一个还没算出来的数字写成 0 就是在说假话。
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = !busy && !counting, role = Role.Button) {
                if (exportScope == MoneyExportScope.All) {
                    exportScope = MoneyExportScope.Filtered
                } else {
                    exportScope = MoneyExportScope.All
                    if (allCount == null) scope.launch {
                        counting = true
                        val n = countAll()
                        counting = false
                        if (n == null) {
                            note = "问不到全部账目有多少笔 —— 网络不通时这一档用不了。可以先导当前筛选的这些。"
                            exportScope = MoneyExportScope.Filtered
                        } else {
                            note = ""
                            allCount = n
                        }
                    }
                }
            }.padding(horizontal = UmbraDimens.pagePadX),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (exportScope == MoneyExportScope.All) "改回当前筛选" else "改成导出全部", color = c.text, style = TextStyle(fontSize = 15.sp), modifier = Modifier.weight(1f))
            if (counting) {
                UmbraSpinningIcon(UmbraIconPaths.spinnerArc, c.muted, size = 16.dp)
            } else if (exportScope == MoneyExportScope.Filtered) {
                Text("跨所有月份", color = c.faint, style = TextStyle(fontSize = 12.5.sp))
            }
        }
        MoneyRowSeparator()

        if (note.isNotEmpty()) {
            Text(
                note, color = c.dangerText, style = TextStyle(fontSize = 12.5.sp, lineHeight = (12.5 * 1.6).sp),
                modifier = Modifier.padding(horizontal = UmbraDimens.pagePadX).padding(top = UmbraDimens.sp3),
            )
        }

        Box(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = canExport, role = Role.Button) {
                scope.launch {
                    busy = true
                    note = ""
                    val res = runExport(ctx, exportScope, filtered, range, withAtts, catName)
                    busy = false
                    when (res) {
                        is ExportResult.Failed -> note = res.note
                        is ExportResult.Ready -> {
                            shared = true
                            share(ctx, res.files)
                            onClose()
                        }
                    }
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (busy) "正在生成…" else range.actionTitle,
                color = if (canExport) c.orangeText else c.faint,
                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
            )
        }
        MoneyRowSeparator()
        Box(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = !busy, role = Role.Button, onClick = onClose),
            contentAlignment = Alignment.Center,
        ) { Text("取消", color = c.muted, style = TextStyle(fontSize = 16.sp)) }
    }
}

/** 面板里那个开关：用勾不用开关件（iOS 理由：系统 Toggle 是这一屏里唯一不走 token 的颜色）。iOS: MoneyExportSheet.swift:400（圆角 6 写死） */
@Composable
private fun ExportCheck(on: Boolean) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier.size(22.dp).clip(shape).background(if (on) c.orange else c.bg).border(1.5.dp, if (on) c.orange else c.border, shape),
        contentAlignment = Alignment.Center,
    ) {
        // 实底上的勾：图标不是正文，走 onWarning 那种「实底上的前景」不合适，这里用 card（亮 / 暗都与 orange 实底对比够）。
        if (on) UmbraIcon(UmbraIconPaths.check, c.card, size = 13.dp, strokeWidth = 2.4f)
    }
}

// MARK: 干活

/** 一次导出的结果：要么一组文件交给分享，要么一句没生成的原因。 */
private sealed interface ExportResult {
    data class Ready(val files: List<File>) : ExportResult
    data class Failed(val note: String) : ExportResult
}

/**
 * 「全部账目」有多少笔。生活账本在本机直接数；经营账本问服务端（只要 totals，limit=1）。null = 问不到。
 * iOS: MoneyExportSheet.swift:206（iOS 生活账本那时还在服务端；Android 生活账本已是本机账本）
 */
private suspend fun countAll(): Int? {
    if (MoneyStore.usesLocal) return withContext(Dispatchers.IO) { MoneyLocal.list(limit = Int.MAX_VALUE).items.size }
    return MoneyApi.entries(ym = null, direction = null, cat = null, limit = 1)?.totals?.count
}

/**
 * 全部账目：生活账本从本机取全；经营账本**逐页拉全**（服务端 limit 默认 500，一次拉不完的账本不罕见）。
 * 拉不全就整个放弃 —— **没有导出半份的道理**，一份缺了几个月的账本比没有更危险。iOS: MoneyExportSheet.swift:281
 */
private suspend fun fetchAll(): List<MoneyEntryDTO>? {
    if (MoneyStore.usesLocal) return withContext(Dispatchers.IO) { MoneyLocal.list(limit = Int.MAX_VALUE).items }
    val out = ArrayList<MoneyEntryDTO>()
    val page = 500
    var offset = 0
    while (true) {
        val r = MoneyApi.entries(ym = null, direction = null, cat = null, limit = page, offset = offset) ?: return null
        out += r.items
        if (r.items.size < page) break
        offset += page
        // 防呆：账本再大也不该到这一步。到了说明分页口径出了问题，宁可停下来报错也不要转到天荒地老。
        if (offset > 200_000) return null
    }
    return out
}

/** 生成 csv（和可选的凭证图 zip）。iOS: MoneyExportSheet.swift:218 run() */
private suspend fun runExport(
    ctx: Context,
    scope: MoneyExportScope,
    filtered: List<MoneyEntryDTO>,
    range: MoneyExportRange,
    withAtts: Boolean,
    catName: (String) -> String,
): ExportResult {
    val entries = if (scope == MoneyExportScope.All) {
        fetchAll() ?: return ExportResult.Failed("拿不全全部账目 —— 这一次没有生成文件（没有导出半份的道理）。")
    } else filtered
    // ⚠️ 范围那几段**直接用 range**，不重新推一遍 —— 重推就是第二份副本（162 §一 那次返工就是这么来的）。
    val r = MoneyExportRange(range.segs, entries.size, range.rcpt)
    // 分类名在主线程取好（catName 读 store），写文件放 IO。
    val rows = entries.map { e ->
        MoneyExportKit.Row(
            at = wallClock(e.at_ms),
            direction = if (e.direction == "income") "收入" else "支出",
            cat = catName(e.cat),
            sub = e.sub,
            amountYuan = MoneyExportKit.yuanPlain(e.cents),
            merchant = e.merchant,
            orderNo = e.order_no,
            atts = e.attList.joinToString(";") { it.label },
            id = e.id,
        )
    }
    val cache = ctx.cacheDir
    return withContext(Dispatchers.IO) {
        // 上一次导出（或旧版散在 cache 根上的那几份）先清掉，再开这一次自己的目录。
        sweepTmp(cache)
        val work = File(cache, MoneyExportKit.TMP_DIR_PREFIX + UUID.randomUUID().toString())
        val csv = runCatching {
            if (!work.mkdirs()) error("mkdir")
            File(work, r.fileBase + ".csv").also { it.writeText(MoneyExportKit.csvText(rows), Charsets.UTF_8) }
        }.getOrNull()
        if (csv == null) {
            sweepTmp(cache)
            return@withContext ExportResult.Failed("写不出文件 —— 这台设备的临时空间可能满了。")
        }
        val out = arrayListOf(csv)
        if (withAtts) writeAttachmentsZip(entries, r.fileBase, work)?.let { out.add(it) }
        ExportResult.Ready(out)
    }
}

/**
 * 凭证图打包成 zip（java.util.zip）。zip 里套一层 `<base>/` 文件夹：解开之后是一个以导出范围命名的目录（同 iOS）。
 * 拿不到的图**跳过不报错**：一张图下不下来不该让整次导出失败，csv 里那一列写着它的名字，收到的人对得上少了哪一张。
 * 一张都没拿到就不出 zip。iOS: MoneyExportSheet.swift:322
 */
private fun writeAttachmentsZip(entries: List<MoneyEntryDTO>, base: String, work: File): File? {
    val dest = File(work, "$base-凭证图.zip")
    var got = 0
    val used = HashSet<String>()
    runCatching {
        ZipOutputStream(FileOutputStream(dest)).use { zip ->
            for (e in entries) {
                for (a in e.attList) {
                    val data = attBytes(a.file_id) ?: continue
                    if (data.size <= 16) continue
                    // 文件名带上流水 id：同一天两笔都叫「凭证」的话，解压之后分不清谁是谁。
                    val stem = MoneyExportKit.safeFileSeg("${e.id}-${a.label.ifEmpty { a.file_id }}")
                    var name = "$base/$stem${suffix(data)}"
                    // zip 里同名条目会直接抛异常；同一笔挂了两张同名的图时补序号。
                    var n = 2
                    while (!used.add(name)) { name = "$base/$stem-$n${suffix(data)}"; n++ }
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(data)
                    zip.closeEntry()
                    got++
                }
            }
        }
    }.onFailure { dest.delete(); return null }
    if (got == 0) { dest.delete(); return null }
    return dest
}

/**
 * 一张附件的字节：本机暂存的读本机文件；服务端的走统一出口的 OkHttp 客户端带令牌下载
 * （UmbraHttp.raw 回的是字符串正文，二进制会被解码坏，所以这里直接用它的 client）。拿不到回 null。
 */
private fun attBytes(fileId: String): ByteArray? {
    if (MoneyLocalRules.isLocalFileId(fileId)) return MoneyLocal.fileFor(fileId)?.takeIf { it.exists() }?.readBytes()
    return runCatching {
        val b = Request.Builder().url(MoneyApi.fileUrl(fileId))
        val token = AppGraph.session.token
        if (token.isNotEmpty()) b.header("Authorization", "Bearer $token")
        AppGraph.http.client.newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body.bytes()
        }
    }.getOrNull()
}

/** 按魔数判后缀：服务端 /files/<id> 根本没有后缀，后缀错了接收方会把一张 JPEG 当未知文件。iOS: MoneyExportSheet.swift:375 */
private fun suffix(data: ByteArray): String {
    fun at(i: Int) = data[i].toInt() and 0xFF
    if (data.size >= 4 && at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47) return ".png"
    if (data.size >= 3 && at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF) return ".jpg"
    if (data.size >= 12 && String(data, 0, 4, Charsets.US_ASCII) == "RIFF" && String(data, 8, 4, Charsets.US_ASCII) == "WEBP") return ".webp"
    return ".jpg"
}

/** 毫秒 → 本地墙上时间 `2026-08-31 23:30`（Locale.ROOT：别让地区把数字换成别的历法）。iOS: MoneyExportSheet.swift:366 */
private fun wallClock(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(ms))

/** 删掉 cacheDir 下所有导出留下的东西（MoneyExportKit.isExportTmp 认的那几样）。失败不报：下一次导出开始时还会再删。 */
private fun sweepTmp(cache: File) {
    cache.listFiles()?.forEach { f -> if (MoneyExportKit.isExportTmp(f.name)) runCatching { f.deleteRecursively() } }
}

/**
 * 交给系统分享面板。⚠️ 按下去才升起 —— 那一层是系统的界面，我们再也插不进一句话。
 * 一个文件 ACTION_SEND（csv 标 text/csv）；csv + zip 两个用 ACTION_SEND_MULTIPLE。
 * ClipData 要一起给：只给 EXTRA_STREAM 的话，读权限过不了 chooser 那一层。
 */
private fun share(ctx: Context, files: List<File>) {
    val auth = ctx.packageName + ".files"
    val uris = files.map { FileProvider.getUriForFile(ctx, auth, it) }
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uris[0])
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
        }
    }
    intent.clipData = ClipData.newUri(ctx.contentResolver, files[0].name, uris[0]).also { cd ->
        uris.drop(1).forEach { cd.addItem(ClipData.Item(it)) }
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val chooser = Intent.createChooser(intent, "导出账目")
    val act = ctx.findActivity()
    if (act == null) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    (act ?: ctx).startActivity(chooser)
}

/** 弹层里的 LocalContext 可能是包了一层的 ContextWrapper：往里剥到 Activity，从 Activity 起 chooser 才不用 NEW_TASK。 */
private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
