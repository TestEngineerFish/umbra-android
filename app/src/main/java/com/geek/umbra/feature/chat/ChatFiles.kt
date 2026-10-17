// 聊天里和系统文件打交道的几件事：拍照落盘、复制图片进剪贴板、存图到相册、读选来的图。
//
// 平台替换（docs/Android开发文档.md §四）：iOS UIPasteboard.image → ClipData.newUri + FileProvider；
// PHPhotoLibrary → MediaStore（29+ 无需权限；28 要 WRITE_EXTERNAL_STORAGE，页面侧先要权限）；UIImagePickerController → TakePicture。
package com.geek.umbra.feature.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.geek.umbra.app.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 子类化的 FileProvider：manifest 里 android:name 不和别的模块的 FileProvider 撞（同名会在合并时冲突）。 */
class ChatFileProvider : FileProvider()

object ChatFiles {
    private fun authority(ctx: Context) = ctx.packageName + ".chat.files"

    private fun dir(sub: String): File = File(AppGraph.context.cacheDir, "chat/$sub").apply { mkdirs() }

    /** 拍照要落盘的那个文件的 content:// 地址（TakePicture 要一个写得进去的 Uri）。 */
    fun newCameraUri(): Uri {
        val f = File(dir("camera"), "shot-${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(AppGraph.context, authority(AppGraph.context), f)
    }

    /** 读一个 content:// 的全部字节（相册 / 文件 / 拍照选回来的）。失败 null。 */
    suspend fun read(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { AppGraph.context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
    }

    /** 单图的宽高比（只读头，不解整张）。读不出 null。 */
    fun ratio(data: ByteArray): Float? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth.toFloat() / o.outHeight else null
    }

    /**
     * 复制图片：进剪贴板的是**图本身**（content:// 地址 + 图片类型），不是链接 —— 粘到别处要能直接出图。
     * 剪贴板只有一格：多图时只复制第一张（菜单与吐司都说「第一张」）。
     */
    suspend fun copyImage(bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val f = File(dir("clip"), "image-${System.currentTimeMillis()}.jpg")
            f.writeBytes(bytes)
            val uri = FileProvider.getUriForFile(AppGraph.context, authority(AppGraph.context), f)
            withContext(Dispatchers.Main) {
                val cm = AppGraph.context.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newUri(AppGraph.context.contentResolver, "image", uri))
            }
            true
        }.getOrDefault(false)
    }

    /** 复制文字。 */
    fun copyText(text: String) {
        val cm = AppGraph.context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("text", text))
    }

    /** 存一张图到相册（Pictures/Umbra）。返回成没成 —— 逐张记账，三张里成两张要说「已存 2 张」。 */
    suspend fun saveToAlbum(bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            if (o.outWidth <= 0) return@runCatching false
            val mime = o.outMimeType ?: "image/jpeg"
            val ext = when (mime) { "image/png" -> "png"; "image/gif" -> "gif"; "image/webp" -> "webp"; else -> "jpg" }
            val name = "umbra-${System.currentTimeMillis()}.$ext"
            val resolver = AppGraph.context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Umbra")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                } else {
                    @Suppress("DEPRECATION")
                    val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Umbra").apply { mkdirs() }
                    @Suppress("DEPRECATION")
                    put(MediaStore.Images.Media.DATA, File(d, name).absolutePath)
                }
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return@runCatching false
            if (Build.VERSION.SDK_INT >= 29) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
            true
        }.getOrDefault(false)
    }
}
