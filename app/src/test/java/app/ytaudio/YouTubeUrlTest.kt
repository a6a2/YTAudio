package app.ytaudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeUrlTest {
    private val id = "dQw4w9WgXcQ"

    @Test fun watchLink() = assertEquals(Link(id, null), YouTubeUrl.parse("https://www.youtube.com/watch?v=$id"))
    @Test fun shortLink() = assertEquals(Link(id, null), YouTubeUrl.parse("https://youtu.be/$id?si=abc"))
    @Test fun shorts() = assertEquals(Link(id, null), YouTubeUrl.parse("https://youtube.com/shorts/$id"))
    @Test fun live() = assertEquals(Link(id, null), YouTubeUrl.parse("https://www.youtube.com/live/$id"))
    @Test fun music() = assertEquals(Link(id, null), YouTubeUrl.parse("https://music.youtube.com/watch?v=$id"))
    @Test fun mobile() = assertEquals(Link(id, null), YouTubeUrl.parse("https://m.youtube.com/watch?v=$id"))
    @Test fun upperCase() = assertEquals(Link(id, null), YouTubeUrl.parse("HTTPS://WWW.YOUTUBE.COM/watch?v=$id"))

    @Test fun playlistOnly() =
        assertEquals(Link(null, "PLabc_123-x"), YouTubeUrl.parse("https://www.youtube.com/playlist?list=PLabc_123-x"))

    @Test fun videoInsidePlaylist() =
        assertEquals(Link(id, "PLabc"), YouTubeUrl.parse("https://www.youtube.com/watch?v=$id&list=PLabc&index=3"))

    @Test fun extraShareText() =
        assertEquals(Link(id, null), YouTubeUrl.parse("Watch this!! https://youtu.be/$id (so good)"))

    @Test fun firstYouTubeLinkWins() =
        assertEquals(Link(id, null), YouTubeUrl.parse("https://example.com/x https://youtu.be/$id https://youtu.be/AAAAAAAAAAA"))

    @Test fun otherSitesRejected() {
        assertNull(YouTubeUrl.parse("https://example.com/watch?v=$id"))
        assertNull(YouTubeUrl.parse("https://notyoutube.com/watch?v=$id"))
        assertNull(YouTubeUrl.parse("https://youtube.com.evil.com/watch?v=$id"))
        assertNull(YouTubeUrl.parse("https://evil.com/?u=youtube.com/watch?v=$id"))
    }

    @Test fun channelIsYouTubeButUnsupported() {
        val text = "https://www.youtube.com/@somechannel"
        assertNull(YouTubeUrl.parse(text))
        assertTrue(YouTubeUrl.hasYouTubeHost(text))
    }

    @Test fun plainTextIsNotYouTube() {
        assertNull(YouTubeUrl.parse("hello world"))
        assertFalse(YouTubeUrl.hasYouTubeHost("hello world"))
    }

    @Test fun idOfAndVideo() {
        assertEquals(id, YouTubeUrl.idOf(YouTubeUrl.video(id)))
        assertEquals("https://www.youtube.com/watch?v=$id", YouTubeUrl.video(id))
    }
}
