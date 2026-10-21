// 从系统分享进来的 Intent 里把**文字 / 链接 / 图片 / 文件**分别取出来 —— 对应 iOS ShareExtension/ShareIntake.swift。
//
// Android 的形状（和 iOS NSExtensionItem 不同）：
// · ACTION_SEND + text/plain：EXTRA_TEXT（浏览器分享网页时就是那条链接）；
// · ACTION_SEND + image/*：EXTRA_STREAM 一个 Uri；ACTION_SEND_MULTIPLE：EXTRA_STREAM 一串 Uri（有的 App 只放在 ClipData 里）；
// · 两样可能同时带（图 + 一句说明）。EXTRA_SUBJECT 不读：iOS 也不读 attributedContentTitle，
//   而且很多 App 往里塞的是「分享自 xxx」这类话。
// 图**只读前 maxImages 张的字节**：第 5 张以后只数个数，不读进内存（iOS 同：一次分享 20 张原图读全了会被杀）。
package com.geek.umbra.feature.share

import android.content.ContentResolver
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.geek.umbra.net.ImageQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.roundToInt

/** 一张要带上的图：上传用的字节 + 文件名 + 面板上那一格缩略图。id 只在本次面板里唯一（重试按 id 记哪张已传上）。 */
class ShareImage(val id: Long, val data: ByteArray, val name: String, val thumb: ImageBitmap?)

/** 读出来的全部原料。 */
data class ShareIntakeResult(
    val texts: List<String> = emptyList(),
    val urls: List<String> = emptyList(),
    val images: List<ShareImage> = emptyList(),
    /** 超出上限、没读进来的图的张数。 */
    val dropped: Int = 0,
    /**
     * 分享进来的第一个**不是图**的文件（「用电脑转换」，iOS 58cf4be：只收一个）。字节不在这里读 ——
     * 转换面板按 32 MB 上限先问大小、超了不读（`readConvertInput`）。
     */
    val file: Uri? = null,
)

object ShareIntake {
    private val ids = AtomicLong(1)

    /** 读一遍分享进来的东西（IO 线程）。 */
    suspend fun collect(cr: ContentResolver, intent: Intent): ShareIntakeResult = withContext(Dispatchers.IO) {
        val texts = mutableListOf<String>()
        val urls = mutableListOf<String>()
        val rawTexts = mutableListOf<String>()
        intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.let { rawTexts += it.toString() }
        runCatching { intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT) }.getOrNull()?.forEach { rawTexts += it.toString() }
        // 整段就是一条链接的认成链接，其余是文字（iOS 分开拿 public.url / public.plain-text，见 ShareInspRules.isLink）。
        for (t in rawTexts) if (ShareInspRules.isLink(t)) urls += t.trim() else texts += t

        val all = streams(intent).distinct()
        val imageUris = all.filter { isImage(cr, it, intent.type) }
        val (kept, dropped) = ShareInspRules.pickImages(imageUris)
        val images = kept.mapNotNull { load(cr, it) }
        ShareIntakeResult(texts, urls, images, dropped, file = all.firstOrNull { it !in imageUris })
    }

    /** EXTRA_STREAM（单个 / 一串）+ ClipData 里的 Uri，按分享顺序。 */
    @Suppress("DEPRECATION")
    private fun streams(intent: Intent): List<Uri> {
        val out = mutableListOf<Uri>()
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            val list = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            list?.let { out += it }
        } else {
            val one = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            one?.let { out += it }
        }
        if (out.isEmpty()) intent.clipData?.let { c -> for (i in 0 until c.itemCount) c.getItemAt(i).uri?.let { out += it } }
        return out
    }

    /** 只认图：先看 ContentResolver 给的类型，拿不到再看整个 Intent 的 type。 */
    private fun isImage(cr: ContentResolver, uri: Uri, intentType: String?): Boolean {
        val t = runCatching { cr.getType(uri) }.getOrNull() ?: intentType ?: return false
        return t.startsWith("image/")
    }

    /** 读一张图的字节、起名字、做缩略图。读不出来（没权限 / 已删）就跳过这张。 */
    private fun load(cr: ContentResolver, uri: Uri): ShareImage? {
        val data = runCatching { cr.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return null
        if (data.isEmpty()) return null
        val mime = runCatching { cr.getType(uri) }.getOrNull()
        val ext = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "jpg"
        val display = runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()?.trim().orEmpty()
        val stem = display.substringBeforeLast('.', display).ifBlank { "image" }
        return ShareImage(ids.getAndIncrement(), data, "$stem.$ext", thumbnail(data))
    }

    /**
     * 面板上那一格缩略图：解码时就缩到长边 192（一格 64dp 的 3 倍屏），不把整张原图解进内存。
     * 数值是**显示尺寸**，不是上传档位（上传不缩放，见 [prepareForUpload]）。
     */
    private fun thumbnail(data: ByteArray): ImageBitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(data))) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val long = max(info.size.width, info.size.height)
            if (long > 192) {
                val f = 192f / long
                d.setTargetSize((info.size.width * f).roundToInt().coerceAtLeast(1), (info.size.height * f).roundToInt().coerceAtLeast(1))
            }
        }.asImageBitmap()
    }.getOrNull()

    /**
     * 上传前：JPEG / PNG / GIF 原样发；别的（HEIC、WebP 等）转成 JPEG（有透明的出 PNG）。
     * **不缩放**（target 0 = ImageQuality.prepare 里「不缩」那一支）：缩到多大是服务端 image_tiers 定的，
     * 分享面板里不猜上限；太大由服务端 413 说出来（share.insp.tooLarge）。iOS: ShareIntake.swift:292。
     */
    suspend fun prepareForUpload(img: ShareImage): Pair<ByteArray, String> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(img.data, 0, img.data.size, opts)
        if (opts.outMimeType in setOf("image/jpeg", "image/png", "image/gif")) return img.data to img.name
        val prep = ImageQuality.prepare(img.data, 0)
        return prep.data to prep.filename(img.name)
    }
}
