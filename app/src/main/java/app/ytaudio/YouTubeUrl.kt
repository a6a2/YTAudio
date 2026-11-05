package app.ytaudio

data class Link(val videoId: String?, val listId: String?)

object YouTubeUrl {
    private val urlRx = Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE)
    private val hostRx = Regex(
        """^https?://(?:www\.|m\.|music\.)?(?:youtube\.com|youtu\.be)/""", RegexOption.IGNORE_CASE
    )
    private val idRx = Regex("""(?:[?&]v=|youtu\.be/|/shorts/|/live/|/embed/)([\w-]{11})""", RegexOption.IGNORE_CASE)
    private val listRx = Regex("""[?&]list=([\w-]+)""", RegexOption.IGNORE_CASE)

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

    /** True if the text contains any youtube.com / youtu.be link, even one we can't play (channel, handle...). */
    fun hasYouTubeHost(text: String): Boolean = urlRx.findAll(text).any { hostRx.containsMatchIn(it.value) }

    fun idOf(url: String): String? = idRx.find(url)?.groupValues?.get(1)
    fun video(id: String) = "https://www.youtube.com/watch?v=$id"
}
