package app.ytaudio

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.TimeUnit

const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"

data class Link(val videoId: String?, val listId: String?)
data class Resolved(val item: MediaItem, val related: List<String>)

object YouTubeUrl {
    private val urlRx = Regex("""https?://[^\s]+""")
    private val hostRx = Regex("""^https?://(?:www\.|m\.|music\.)?(?:youtube\.com|youtu\.be)/""")
    private val idRx = Regex("""(?:[?&]v=|youtu\.be/|/shorts/|/live/|/embed/)([\w-]{11})""")
    private val listRx = Regex("""[?&]list=([\w-]+)""")

    /** First YouTube link in shared text: a video, a playlist, or a video inside a playlist. */
    fun parse(text: String): Link? {
        for (m in urlRx.findAll(text)) {
            val u = m.value
            if (!hostRx.containsMatchIn(u)) continue
            val vid = idRx.find(u)?.groupValues?.get(1)
            val list = listRx.find(u)?.groupValues?.get(1)
            if (vid != null || list != null) return Link(vid, list)
        }
        return null
    }

    fun idOf(url: String): String? = idRx.find(url)?.groupValues?.get(1)
    fun video(id: String) = "https://www.youtube.com/watch?v=$id"
}

object Resolver {
    private var ready = false

    @Synchronized
    private fun init() {
        if (!ready) { NewPipe.init(Http); ready = true }
    }

    /** Blocking: call off the main thread. */
    fun resolve(url: String): Resolved {
        init()
        val info = StreamInfo.getInfo(ServiceList.YouTube, url)
        val audio = info.audioStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && !it.content.isNullOrEmpty() }
            .maxByOrNull { it.averageBitrate }

        val builder = MediaItem.Builder().setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(info.name)
                .setArtist(info.uploaderName)
                .setArtworkUri(info.thumbnails.maxByOrNull { it.height }?.url?.let(android.net.Uri::parse))
                .build()
        )
        val item = when {
            audio != null -> builder.setUri(audio.content).build()
            !info.hlsUrl.isNullOrEmpty() -> builder.setUri(info.hlsUrl).setMimeType(MimeTypes.APPLICATION_M3U8).build()
            else -> throw ContentNotAvailableException("No audio stream available")
        }
        val related = info.relatedItems.filterIsInstance<StreamInfoItem>().map { it.url }
        return Resolved(item, related)
    }

    fun playlistFirst(url: String): Pair<List<String>, Page?> {
        init()
        val info = PlaylistInfo.getInfo(ServiceList.YouTube, url)
        return info.relatedItems.map { it.url } to info.nextPage
    }

    fun playlistMore(url: String, page: Page): Pair<List<String>, Page?> {
        init()
        val p = PlaylistInfo.getMoreItems(ServiceList.YouTube, url, page)
        return p.items.map { it.url } to p.nextPage
    }
}

/** OkHttp-backed downloader required by NewPipeExtractor. */
object Http : Downloader() {
    val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

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
        client.newCall(b.build()).execute().use { r ->
            if (r.code == 429) throw ReCaptchaException("Rate limited / captcha", request.url())
            return Response(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
        }
    }
}
