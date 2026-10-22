// 带鉴权的取图 —— 服务端 `/files/{id}` 要 Bearer，Coil 自己的网络层不认我们的规矩。
//
// 从前聊天（ChatImages）、提醒（ReminderImages）各建了一个带拦截器的 ImageLoader，记账两处、任务截图又在请求上逐个塞头，
// 五份做法三种口径：拦截器那两份给**任何主机**都带令牌（服务端回的绝对地址指到别处时会把令牌送出去）、
// 又不跟重定向（`/files` 302 到 OSS 时取不到）；逐个塞头的那三份用 Coil 默认的 OkHttp，过不了请求闸、不记离线。
// 收成这一份（开发文档第五节技术债）：取字节一律走统一出口的 [UmbraHttp.bytes] —— 令牌只带给自己的服务端，
// 跳到别家 https 时去掉登录头，请求闸 / 会话失效 / 离线记账都和别的请求同一处。
package com.geek.umbra.net

import android.annotation.SuppressLint
import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.serviceLoaderEnabled
import com.geek.umbra.app.AppGraph
import kotlinx.coroutines.withContext
import okio.Buffer
import java.io.IOException

@SuppressLint("StaticFieldLeak")
object UmbraImages {
    /** 全 App 共用的取图器（本机文件、`content://` 照 Coil 默认处理；http(s) 走 [BytesFetcher]）。图都是缩略图量级，只留内存缓存。 */
    val loader: ImageLoader by lazy {
        ImageLoader.Builder(AppGraph.context)
            // 不让 Coil 按 ServiceLoader 装它自己的 OkHttp 网络取数：那一条不带令牌、不过请求闸，排在前面就把我们的顶掉了。
            .serviceLoaderEnabled(false)
            .components { add(BytesFetcher.Factory()) }
            .build()
    }

    /** 服务端文件地址（iOS HTTPService.moneyFileURL）。id 按一个路径段编码：带 `/` 的 id 不许多切出一段路径。 */
    fun fileUrl(fileId: String): String = AppGraph.http.server + "/files/" + UmbraUrl.segment(fileId)

    /** 服务端给的相对路径补上服务端前缀；完整地址原样（带不带令牌由 [UmbraHttp.bytes] 按同源判）。 */
    fun full(url: String): String = if (url.startsWith("http")) url else AppGraph.http.server + url

    /** 取一份文件的字节（复制图片 / 存相册 / 求助截图）。失败 null。 */
    suspend fun download(url: String): ByteArray? = AppGraph.http.bytes(full(url), followHttpsAway = true).dataOrNull

    /**
     * Coil 的 http(s) 取数：交给统一出口按字节读。画图不是人点出来的请求，走后台路径（不打「人引起的」头）。
     * 取不到抛 IOException，Coil 走 error 那一档（各处画「附件已删除」）。
     */
    private class BytesFetcher(private val url: String, private val options: Options) : Fetcher {
        override suspend fun fetch(): FetchResult {
            val r = withContext(BackgroundWork) { AppGraph.http.bytes(url, followHttpsAway = true) }
            val ok = r as? BytesResult.Ok ?: throw IOException("取图失败：$r")
            return SourceFetchResult(
                source = ImageSource(Buffer().write(ok.data), options.fileSystem),
                mimeType = ok.headers["Content-Type"]?.substringBefore(';')?.trim(),
                dataSource = DataSource.NETWORK,
            )
        }

        class Factory : Fetcher.Factory<Uri> {
            override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
                val scheme = data.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") return null
                return BytesFetcher(data.toString(), options)
            }
        }
    }
}
