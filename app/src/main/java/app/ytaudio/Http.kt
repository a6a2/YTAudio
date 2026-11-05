package app.ytaudio

import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"

/** OkHttp-backed downloader required by NewPipeExtractor. */
object Http : Downloader() {
    /** Used by ExoPlayer for the audio stream itself. */
    val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    // Extractor lookups get their own dispatcher so stale ones can be cancelled
    // (Next/Previous taps, new share) without ever touching the audio stream.
    private val extractorClient: OkHttpClient = client.newBuilder().dispatcher(Dispatcher()).build()

    /** Aborts every in-flight extractor request; their blocked threads fail fast with an IOException. */
    fun cancelPending() = extractorClient.dispatcher.cancelAll()

    override fun execute(request: Request): Response {
        val method = request.httpMethod()
        val data = request.dataToSend()
        val body = data?.toRequestBody(null) ?: if (method == "POST") ByteArray(0).toRequestBody(null) else null
        val b = okhttp3.Request.Builder().url(request.url()).method(method, body)
            .header("User-Agent", USER_AGENT)
        for ((k, values) in request.headers()) {
            b.removeHeader(k)
            values.forEach { b.addHeader(k, it) }
        }
        extractorClient.newCall(b.build()).execute().use { r ->
            if (r.code == 429) throw ReCaptchaException("Rate limited / captcha", request.url())
            return Response(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
        }
    }
}
