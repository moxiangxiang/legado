package io.legado.app.help.glide

import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.HttpException
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.util.ContentLengthInputStream
import com.script.rhino.runScriptWithContext
import io.legado.app.data.entities.BaseSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.CookieManager.cookieJarHeader
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.okHttpClientManga
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.ReadManga
import io.legado.app.utils.ImageUtils
import io.legado.app.utils.isWifiConnect
import kotlinx.coroutines.Job
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import splitties.init.appCtx
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream


class OkHttpStreamFetcher(
    private val oldUrl: GlideUrl,
    private val url: GlideUrl,
    private val options: Options,
) :
    DataFetcher<InputStream>, okhttp3.Callback {
    private var stream: InputStream? = null
    private var responseBody: ResponseBody? = null
    private var callback: DataFetcher.DataCallback<in InputStream>? = null
    private var source: BaseSource? = null
    private val manga = options.get(OkHttpModelLoader.mangaOption) == true
    private val coroutineContext = Job()

    @Volatile
    private var call: Call? = null

    companion object {
        private val failUrl = hashSetOf<String>()
    }

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
        if (failUrl.contains(url.toStringUrl())) {
            callback.onLoadFailed(NoStackTraceException("跳过加载失败的图片"))
            return
        }
        val loadOnlyWifi = options.get(OkHttpModelLoader.loadOnlyWifiOption) ?: false
        if (loadOnlyWifi && !appCtx.isWifiConnect) {
            callback.onLoadFailed(NoStackTraceException("只在wifi加载图片"))
            return
        }
        val requestBuilder: Request.Builder = Request.Builder().url(url.toStringUrl())
        val headerMap = HashMap<String, String>()
        options.get(OkHttpModelLoader.sourceOriginOption)?.let { sourceUrl ->
            source = SourceHelp.getSource(sourceUrl)
            runScriptWithContext(coroutineContext) {
                source?.getHeaderMap(true)?.let {
                    headerMap.putAll(it)
                }
            }
            if (source?.enabledCookieJar == true) {
                headerMap[cookieJarHeader] = "1"
            }
        }
        headerMap.putAll(url.headers)
        requestBuilder.addHeaders(headerMap)
        val request: Request = requestBuilder.build()
        this.callback = callback
        call = if (manga) {
            okHttpClientManga.newCall(request)
        } else {
            okHttpClient.newCall(request)
        }
        call?.enqueue(this)
    }

    override fun cleanup() {
        kotlin.runCatching {
            stream?.close()
        }
        responseBody?.close()
        coroutineContext.cancel()
        callback = null
    }

    override fun cancel() {
        call?.cancel()
        coroutineContext.cancel()
    }

    override fun getDataClass(): Class<InputStream> {
        return InputStream::class.java
    }

    override fun getDataSource(): DataSource {
        return DataSource.REMOTE
    }

    override fun onFailure(call: Call, e: IOException) {
        callback?.onLoadFailed(e)
    }

    override fun onResponse(call: Call, response: Response) {
        responseBody = response.body
        if (response.isSuccessful) {
            val decodeResult = runScriptWithContext(coroutineContext) {
                if (ImageUtils.skipDecode(source, !manga)) {
                    responseBody!!.byteStream()
                } else if (manga) {
                    ImageUtils.decode(
                        oldUrl.toString(),
                        responseBody!!.bytes(),
                        isCover = false,
                        source,
                        ReadManga.book
                    )?.inputStream()
                } else {
                    ImageUtils.decode(
                        url.toStringUrl(), responseBody!!.byteStream(),
                        isCover = true, source
                    )
                }
            }
            if (decodeResult == null) {
                callback?.onLoadFailed(NoStackTraceException("封面二次解密失败"))
            } else {
                val contentLength: Long =
                    if (decodeResult is ByteArrayInputStream) decodeResult.available().toLong()
                    else responseBody!!.contentLength()
                stream = ContentLengthInputStream.obtain(decodeResult, contentLength)
                callback?.onDataReady(stream)
            }
        } else {
            if (!manga) {
                failUrl.add(url.toStringUrl())
            }
            callback?.onLoadFailed(HttpException(response.message, response.code))
        }
    }
}