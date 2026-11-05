package app.ytaudio

import java.io.IOException
import java.util.concurrent.CancellationException

enum class Repeat { OFF, ALL, ONE }

/** Thrown when a link yields nothing playable (e.g. an empty playlist with no video id). */
class EmptyQueueException : Exception("Empty playlist")

/** Where playlist pages come from: NewPipe in the app, a fake in tests. The page token is opaque. */
interface PlaylistSource {
    fun first(url: String): Pair<List<String>, Any?>
    fun more(url: String, page: Any): Pair<List<String>, Any?>
}

data class QueueSnapshot(val urls: List<String>, val index: Int)

/**
 * The play queue and its navigation rules, with no Android code so it can be unit-tested.
 * Methods that may hit the network ([build], [step], [expand]) are blocking: call them off the main thread.
 */
class QueueManager(
    private val source: PlaylistSource,
    private val warn: (String, Throwable) -> Unit = { _, _ -> },
) {
    // Two locks on purpose. `lock` guards the state and is only ever held briefly, so the main thread can read
    // the queue at any time. `net` serialises the operations that may download playlist pages; it is never taken
    // on the main thread, and `lock` is never held while waiting for the network.
    private val lock = Any()
    private val net = Any()
    private inline fun <T> sync(block: () -> T): T = synchronized(lock, block)

    private val items = ArrayList<String>()
    private val seen = HashSet<String>()          // video ids already queued (for autoplay de-duplication)
    private val autoAdded = HashSet<String>()     // urls queued by autoplay rather than by the user
    private var related: List<String> = emptyList()
    private var playlistUrl: String? = null
    private var nextPage: Any? = null
    private var autoplay = false                  // single-video share: keep appending recommendations

    /** Queue cursor: moves immediately on Next/Previous, before the stream is resolved. */
    @Volatile var index = -1
    /** The entry actually loaded in the player. */
    @Volatile var playingIndex = -1
    @Volatile var repeat = Repeat.OFF
    /** User setting: may a single video continue with recommendations? */
    @Volatile var autoplayAllowed = true

    val size: Int get() = sync { items.size }

    fun urlAt(i: Int): String = sync { items[i] }
    fun urlOrNull(i: Int): String? = sync { items.getOrNull(i) }
    fun setRelated(urls: List<String>) = sync { related = urls }
    fun isAutoplayChain() = sync { autoplay }

    /** Playlist -> queue of its items (starting at the shared video); single video -> autoplay chain. Blocking. */
    fun build(link: Link) {
        synchronized(net) {
            val vid = link.videoId
            val list = link.listId
            sync {
                items.clear(); seen.clear(); autoAdded.clear()
                index = -1; playingIndex = -1; autoplay = false; related = emptyList(); playlistUrl = null; nextPage = null
            }
            if (list != null) {
                val url = playlistUrlFor(list, vid)
                try {
                    val (first, np) = source.first(url)
                    val any = sync { items.addAll(first); nextPage = np; playlistUrl = url; items.isNotEmpty() }
                    if (any) {
                        if (vid != null) {
                            var pos = sync { items.indexOfFirst { YouTubeUrl.idOf(it) == vid } }
                            var pages = 0
                            while (pos < 0 && sync { nextPage } != null && pages++ < 5) {
                                loadMore()
                                pos = sync { items.indexOfFirst { YouTubeUrl.idOf(it) == vid } }
                            }
                            sync {
                                if (pos < 0) { items.add(0, YouTubeUrl.video(vid)); pos = 0 }
                                index = pos - 1
                            }
                        }
                        return
                    }
                } catch (e: Exception) {
                    if (e is CancellationException || e is IOException || vid == null) throw e
                    warn("playlist failed, falling back to the single video", e)
                }
                sync { items.clear(); playlistUrl = null; nextPage = null; index = -1 }
            }
            if (vid == null) throw EmptyQueueException()
            sync { items.add(YouTubeUrl.video(vid)); seen.add(vid); autoplay = true }
        }
    }

    /** Index to play after moving [dir] from [cur], or null if there is none. May load more playlist pages. */
    fun step(cur: Int, dir: Int): Int? {
        if (dir < 0) {
            return sync {
                when {
                    cur - 1 >= 0 -> cur - 1
                    repeat == Repeat.ALL && items.isNotEmpty() -> items.lastIndex
                    else -> null
                }
            }
        }
        synchronized(net) {
            var pages = 0
            while (sync { cur + 1 >= items.size && nextPage != null } && pages++ < MAX_PAGES) loadMore()
            return sync {
                when {
                    cur + 1 < items.size -> cur + 1
                    repeat == Repeat.ALL && items.isNotEmpty() -> 0
                    else -> autoplayPick()
                }
            }
        }
    }

    /** Queues the next unseen recommendation (caller holds `lock`). */
    private fun autoplayPick(): Int? {
        if (!(autoplay && autoplayAllowed)) return null
        val next = related.firstOrNull { u -> YouTubeUrl.idOf(u)?.let { it !in seen } == true } ?: return null
        items.add(next); seen.add(YouTubeUrl.idOf(next)!!); autoAdded.add(next)
        return items.lastIndex
    }

    /** The urls a shared link stands for, for "add to queue": a video, or a playlist from the shared video onward. */
    fun expand(link: Link, limit: Int = 200): List<String> {
        val vid = link.videoId
        val list = link.listId ?: return listOf(YouTubeUrl.video(vid ?: throw EmptyQueueException()))
        val url = playlistUrlFor(list, vid)
        val out = ArrayList<String>()
        try {
            val (first, np) = source.first(url)
            out.addAll(first)
            var page = np
            var pages = 0
            while (page != null && out.size < limit && pages++ < 10) {
                val (more, next) = source.more(url, page)
                out.addAll(more); page = next
            }
        } catch (e: Exception) {
            if (e is CancellationException || e is IOException || vid == null) throw e
            warn("playlist failed, queueing the single video", e)
            out.clear()
        }
        if (vid != null) {
            val pos = out.indexOfFirst { YouTubeUrl.idOf(it) == vid }
            when {
                pos > 0 -> out.subList(0, pos).clear()
                pos < 0 -> { out.clear(); out.add(YouTubeUrl.video(vid)) }
            }
        }
        if (out.isEmpty()) throw EmptyQueueException()
        return out.take(limit)
    }

    /** Adds [urls] after the current track. Recommendations queued automatically but not yet played make room for them. */
    fun append(urls: List<String>) = sync {
        val from = maxOf(index, playingIndex) + 1
        for (i in items.lastIndex downTo from.coerceAtLeast(0)) {
            if (items[i] in autoAdded) { autoAdded.remove(items[i]); items.removeAt(i) }
        }
        items.addAll(urls)
        Unit
    }

    /** A window of the queue around the playing entry, small enough to store. */
    fun snapshot(maxItems: Int = 300): QueueSnapshot? = sync {
        if (items.isEmpty()) return@sync null
        val p = playingIndex.coerceIn(0, items.lastIndex)
        val from = (p - 50).coerceAtLeast(0)
        val to = (from + maxItems).coerceAtMost(items.size)
        QueueSnapshot(items.subList(from, to).toList(), p - from)
    }

    /** Rebuilds the queue from a saved snapshot; the next [step] forward yields the saved entry. */
    fun restore(s: QueueSnapshot, autoplayChain: Boolean) = sync {
        items.clear(); seen.clear(); autoAdded.clear()
        related = emptyList(); playlistUrl = null; nextPage = null
        items.addAll(s.urls)
        s.urls.forEach { YouTubeUrl.idOf(it)?.let(seen::add) }
        autoplay = autoplayChain
        index = s.index - 1
        playingIndex = -1
    }

    private fun playlistUrlFor(list: String, vid: String?) =
        if (list.startsWith("RD") && vid != null) "https://www.youtube.com/watch?v=$vid&list=$list"
        else "https://www.youtube.com/playlist?list=$list"

    /** Loads the next playlist page. Caller holds `net`; the download itself runs with no lock held. */
    private fun loadMore(): Boolean {
        val (page, url) = sync { nextPage to playlistUrl }
        if (page == null || url == null) return false
        val (more, np) = source.more(url, page)
        sync { nextPage = np; items.addAll(more) }
        return more.isNotEmpty()
    }

    private companion object { const val MAX_PAGES = 20 }
}
