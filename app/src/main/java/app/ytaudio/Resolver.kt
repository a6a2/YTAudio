package app.ytaudio

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.Locale

data class Resolved(val item: MediaItem, val related: List<String>)

object Resolver {
    private var ready = false

    @Synchronized
    private fun init() {
        if (ready) return
        // Use the phone's language/region so recommendations and region rules match the user.
        val locale = Locale.getDefault()
        NewPipe.init(Http, Localization.fromLocale(locale), ContentCountry(locale.country.ifEmpty { "US" }))
        ready = true
    }

    /** Blocking: call off the main thread. */
    fun resolve(url: String): Resolved {
        init()
        val info = StreamInfo.getInfo(ServiceList.YouTube, url)
        val playable = info.audioStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && !it.content.isNullOrEmpty() }
        // Multi-language videos expose dubbed / audio-description tracks too: prefer the original one.
        val original = playable.filter { s -> s.audioTrackType?.name.let { it == null || it == "ORIGINAL" } }
        val audio = original.ifEmpty { playable }.maxByOrNull { it.averageBitrate }

        val builder = MediaItem.Builder()
            .setMediaId(info.id)
            .setMediaMetadata(
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
