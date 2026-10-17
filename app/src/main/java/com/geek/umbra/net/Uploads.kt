// 附件上传与共用文案 —— 对应 iOS HTTPService 的 upload、NetworkError.from、ImageQuality、AttachFailCopy、AttLimit、StorageRules.fmt。
// 聊天、记账、提醒、分享都走这一份。
package com.geek.umbra.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import com.geek.umbra.model.UploadResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** 上传失败分档（iOS NetworkError）。先看 X-Umbra-Error 头，没有再看 body 的 code；无码 413 当单文件太大（多半是反代拦的）。 */
sealed interface UploadError {
    data object Unauthorized : UploadError
    data object SessionInvalid : UploadError
    data object FileTooLarge : UploadError
    data object BodyTooLarge : UploadError
    data class StorageFull(val used: Long, val limit: Long, val incoming: Long) : UploadError
    data object Offline : UploadError
    data object Server : UploadError

    companion object {
        fun of(r: HttpResult): UploadError = when (r) {
            is HttpResult.Offline -> Offline
            is HttpResult.Blocked -> Unauthorized
            is HttpResult.Ok -> Server
            is HttpResult.Fail -> {
                val o = runCatching { UmbraJson.parseToJsonElement(r.body) as JsonObject }.getOrNull()
                when (r.code) {
                    "session_invalid" -> SessionInvalid
                    "bad_token", "unauthorized" -> Unauthorized
                    // 存储满必须在 413 兜底之前识别
                    "storage_full" -> StorageFull(o?.long("used") ?: 0, o?.long("limit") ?: 0, o?.long("incoming") ?: 0)
                    "file_too_large" -> FileTooLarge
                    "body_too_large" -> BodyTooLarge
                    else -> when (r.status) { 401 -> Unauthorized; 413 -> FileTooLarge; else -> Server }
                }
            }
        }
    }
}

object Uploads {
    /** 单张上限按 32 MB 提示（服务端同值）。 */
    const val LIMIT_MB = 32

    /** filename 里把 `" \ CR LF` 换成 `_` —— multipart 头里这几个字符会把头拆坏。 */
    fun safeName(name: String) = name.replace(Regex("[\"\\\\\r\n]"), "_").ifBlank { "file" }

    /**
     * `POST /files/upload`（multipart，字段名 `file`，`application/octet-stream`），带真实进度。
     * 成功回 [UploadResponse]；失败回分档。[onProgress] 在 IO 线程回调 0..1。
     */
    suspend fun upload(
        bytes: ByteArray,
        filename: String,
        onProgress: (Float) -> Unit = {},
        path: String = "/files/upload",
        extraParts: Map<String, String> = emptyMap(),
        timeoutMs: Long = 60_000,
    ): Pair<UploadResponse?, UploadError?> {
        val http = com.geek.umbra.app.AppGraph.http
        val file = ProgressBody(bytes, "application/octet-stream".toMediaType(), onProgress)
        val mb = MultipartBody.Builder().setType(MultipartBody.FORM)
        extraParts.forEach { (k, v) -> mb.addFormDataPart(k, v) }
        mb.addFormDataPart("file", safeName(filename), file)
        val r = http.raw("POST", path, body = mb.build(), timeoutMs = timeoutMs)
        if (r is HttpResult.Ok) {
            val u = runCatching { UmbraJson.decodeFromString(UploadResponse.serializer(), r.body) }.getOrNull()
            return if (u != null && u.file_id.isNotEmpty()) u to null else null to UploadError.Server
        }
        return null to UploadError.of(r)
    }

    private class ProgressBody(val data: ByteArray, val type: MediaType, val cb: (Float) -> Unit) : RequestBody() {
        override fun contentType() = type
        override fun contentLength() = data.size.toLong()
        override fun writeTo(sink: BufferedSink) {
            var sent = 0L
            var lastPct = -1
            val total = data.size.toLong().coerceAtLeast(1)
            val counting = object : ForwardingSink(sink) {
                override fun write(source: Buffer, byteCount: Long) {
                    super.write(source, byteCount)
                    sent += byteCount
                    val pct = (sent * 100 / total).toInt()
                    if (pct != lastPct) { lastPct = pct; cb(pct / 100f) } // 按整数百分比节流
                }
            }.buffer()
            counting.write(data)
            counting.flush()
        }
    }
}

/** 图片画质档（iOS ImageTier）。各档长边像素**以服务端 `/settings.image_tiers` 为准**，端上不写死。 */
enum class ImageTier(val wire: String) {
    Saver("saver"), Standard("standard"), Clear("clear"), Origin("origin");
    companion object { fun of(s: String?) = entries.firstOrNull { it.wire == s } }
}

enum class ImagePurpose(val wire: String) {
    Chat("chat"), Money("money"), Reminder("reminder");
    val settingKey get() = "image.quality.$wire"
}

data class PreparedImage(val data: ByteArray, val before: Int, val after: Int, val transcoded: Boolean, val png: Boolean) {
    /** 转码后文件名后缀要跟着改：服务端 `/files/{id}` 按文件名判 Content-Type。 */
    fun filename(original: String): String {
        if (!transcoded) return original
        val stem = original.substringBeforeLast('.', original).ifBlank { "image" }
        return stem + if (png) ".png" else ".jpg"
    }
}

object ImageQuality {
    const val JPEG_QUALITY = 85

    /**
     * 按长边缩放并转码（iOS ImageQuality.prepare）：GIF 原样；有透明的出 PNG，否则 JPEG 0.85；
     * 不缩放且转码后反而更大、原图本来就是 JPEG/PNG → 原样上传。target <= 0（原尺寸档）不缩放。
     */
    suspend fun prepare(data: ByteArray, target: Int): PreparedImage = withContext(Dispatchers.Default) {
        val untouched = PreparedImage(data, data.size, data.size, transcoded = false, png = false)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, opts)
        val w = opts.outWidth; val h = opts.outHeight
        if (w <= 0 || h <= 0) return@withContext untouched
        val mime = opts.outMimeType ?: ""
        if (mime == "image/gif") return@withContext untouched
        val long = max(w, h)
        val scale = target > 0 && long > target
        val bmp: Bitmap = runCatching {
            if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(data))) { d, info, _ ->
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    if (scale) {
                        val f = target.toFloat() / long
                        d.setTargetSize((info.size.width * f).roundToInt().coerceAtLeast(1), (info.size.height * f).roundToInt().coerceAtLeast(1))
                    }
                }
            } else BitmapFactory.decodeByteArray(data, 0, data.size)
        }.getOrNull() ?: return@withContext untouched
        val png = bmp.hasAlpha()
        val out = ByteArrayOutputStream()
        bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        val bytes = out.toByteArray()
        val webSafe = mime == "image/jpeg" || mime == "image/png"
        if (!scale && bytes.size >= data.size && webSafe) return@withContext untouched
        PreparedImage(bytes, data.size, bytes.size, transcoded = true, png = png)
    }

    fun humanBytes(n: Int): String = when {
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> "${(n / 1024.0).roundToInt()} KB"
        else -> "%.1f MB".format(n / 1024.0 / 1024.0)
    }
}

/** 存储大小（1024 进制）。<1MB 显示 KB；≥100MB 取整；≥1024MB 显示 GB 两位小数去尾零。 */
object StorageFmt {
    fun fmt(bytes: Long): String {
        if (bytes <= 0) return "0"
        val kb = bytes / 1024.0; val mb = kb / 1024
        if (mb < 1) return "${kb.roundToInt()} KB"
        if (mb >= 1024) return trim("%.2f".format(mb / 1024)) + " GB"
        if (mb >= 100) return "${mb.roundToInt()} MB"
        return trim("%.1f".format(mb)) + " MB"
    }
    private fun trim(s: String): String = if (!s.contains('.')) s else s.trimEnd('0').trimEnd('.')
    fun storageFullMessage(used: Long, limit: Long, incoming: Long) =
        "存储已满：已用 ${fmt(used)} / ${fmt(limit)}，这个文件 ${fmt(incoming)} 放不下。清理或升级会员后再试。"
}

/** 一笔账 / 一条提醒最多挂 4 张图（iOS AttLimit）。 */
object AttLimit {
    const val MAX = 4
    fun shown(count: Int) = count >= MAX
    fun showsAddTile(count: Int) = count < MAX
    fun <T> fit(items: List<T>, count: Int): List<T> = items.take(max(0, MAX - count))
    const val MONEY_TEXT = "这笔账已经挂了 4 张图，要加新的得先删掉一张。"
    const val REMINDER_TEXT = "这条提醒已经挂了 4 张图，要加新的得先删掉一张。"
}

/** 附件失败的主语（iOS AttachSubject）。 */
data class AttachSubject(val noun: String, val record: String, val tier: ImageTier?, val bigWhyTail: String, val recordEn: String, val bigWhyTailEn: String) {
    fun with(tier: ImageTier?) = copy(tier = tier)
    companion object {
        val money = AttachSubject("凭证", "这一笔", null, "小票上那行小字照样读得清。", "entry", "and the fine print on a receipt stays readable.")
        val reminder = AttachSubject("附件", "这条提醒", null, "到点看提醒时够看了。", "reminder", "which is plenty to look at when it goes off.")
    }
}

/**
 * 附件失败文案 —— 与 PC 语言包 `attFail` 段**逐字相同**（设计侧 057 §二：记一笔和提醒共用一张文案表，两端也共用）。
 * 不许意译。[clearPx]：「清晰」档长边像素（来自 /settings.image_tiers），拿不到就不提重发建议。
 */
object AttachFailCopy {
    private val english: Boolean
        get() = AppCompatDelegate.getApplicationLocales().toLanguageTags().startsWith("en")

    private fun titleEn(count: Int, cause: String) = "$count ${if (count == 1) "image" else "images"} didn't upload: $cause"

    fun title(s: AttachSubject, count: Int, err: UploadError, mb: String): String {
        if (english) return when (err) {
            UploadError.Unauthorized, UploadError.SessionInvalid -> titleEn(count, "you've been signed out")
            UploadError.FileTooLarge, UploadError.BodyTooLarge -> titleEn(count, "this one is $mb MB, over the ${Uploads.LIMIT_MB} MB limit")
            is UploadError.StorageFull -> titleEn(count, "storage is full")
            else -> titleEn(count, "the connection dropped mid-upload")
        }
        return when (err) {
            UploadError.Unauthorized, UploadError.SessionInvalid -> "$count 张${s.noun}没传上：登录已经失效了"
            UploadError.FileTooLarge, UploadError.BodyTooLarge -> "$count 张${s.noun}没传上：这张 $mb MB，超过单张 ${Uploads.LIMIT_MB} MB 上限"
            is UploadError.StorageFull -> "$count 张${s.noun}没传上：存储已满"
            else -> "$count 张${s.noun}没传上：上传到一半连接断了"
        }
    }

    fun reason(s: AttachSubject, err: UploadError, clearPx: Int?): String {
        if (english) return when (err) {
            UploadError.Unauthorized -> "Retrying the others will fail the same way — sign in again under Me → Connection first."
            UploadError.SessionInvalid -> "Retrying the others will fail the same way — your session has expired, so sign in again."
            UploadError.FileTooLarge, UploadError.BodyTooLarge ->
                if (s.tier == ImageTier.Origin && clearPx != null) "The image quality for this ${s.recordEn} is “Original size” — no scaling, so it goes up at full size. Resending as Clear scales the long edge to $clearPx px, ${s.bigWhyTailEn}"
                else "This image can't be scaled down (it's animated, or its size can't be read), so it went up as-is and hit the limit. Pick another one."
            is UploadError.StorageFull -> "${StorageFmt.fmt(err.used)} / ${StorageFmt.fmt(err.limit)} used — this file is ${StorageFmt.fmt(err.incoming)} and doesn't fit. Free up space or upgrade your membership, then try again. This ${s.recordEn} is saved and unaffected."
            else -> "This ${s.recordEn} is saved and unaffected. The image is still attached — you can resend it right here."
        }
        return when (err) {
            // iOS 写「我的 → 连接」；Android 的导航名是「我 › 连接」—— 两端导航名本来就不同，这三句不在逐字比对范围（iOS swiftcheck 同）
            UploadError.Unauthorized -> "其余几张重试也会是同样的结果 —— 先去「我 › 连接」重新登录。"
            UploadError.SessionInvalid -> "其余几张重试也会是同样的结果 —— 登录已经失效了，重新登录一次。"
            UploadError.FileTooLarge, UploadError.BodyTooLarge ->
                if (s.tier == ImageTier.Origin && clearPx != null) "${s.record}的${s.noun}画质是「原尺寸」—— 不缩放，原图多大就传多大。用「清晰」重发会缩到长边 $clearPx，${s.bigWhyTail}"
                else "这张图缩不动（动图，或者读不出尺寸），所以按原样传，撞上了上限。换一张吧。"
            is UploadError.StorageFull -> "已用 ${StorageFmt.fmt(err.used)} / ${StorageFmt.fmt(err.limit)}，这个文件 ${StorageFmt.fmt(err.incoming)} 放不下。清理或升级会员后再试，${s.record}已经存上了，不受影响。"
            else -> "${s.record}已经存上了，不受影响。图还留着，可以就地重发。"
        }
    }

    fun actionLabel(s: AttachSubject, err: UploadError, canResend: Boolean): String {
        if (english) return when (err) {
            UploadError.Unauthorized, UploadError.SessionInvalid -> "Sign in again"
            UploadError.FileTooLarge, UploadError.BodyTooLarge -> if (s.tier == ImageTier.Origin && canResend) "Resend as Clear" else "Pick another"
            is UploadError.StorageFull -> "Free up space"
            else -> if (canResend) "Resend" else "Pick another"
        }
        return when (err) {
            UploadError.Unauthorized, UploadError.SessionInvalid -> "重新登录"
            UploadError.FileTooLarge, UploadError.BodyTooLarge -> if (s.tier == ImageTier.Origin && canResend) "用清晰重发" else "换一张"
            is UploadError.StorageFull -> "去清理"
            else -> if (canResend) "重新发送" else "换一张"
        }
    }

    /** 多张失败时挑最该说的那条：登录失效优先。 */
    fun <T> worst(items: List<T>, err: (T) -> UploadError): T? =
        items.firstOrNull { err(it) == UploadError.Unauthorized || err(it) == UploadError.SessionInvalid } ?: items.firstOrNull()
}
