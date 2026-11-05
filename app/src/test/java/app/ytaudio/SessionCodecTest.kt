package app.ytaudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCodecTest {
    private val urls = listOf("https://www.youtube.com/watch?v=aaaaaaaaaaa", "https://www.youtube.com/watch?v=bbbbbbbbbbb")

    @Test fun roundTrip() {
        val s = SessionCodec.decode(SessionCodec.encode(urls, 1, true, "My song"), 42_000)!!
        assertEquals(urls, s.urls); assertEquals(1, s.index); assertEquals(42_000L, s.positionMs)
        assertTrue(s.autoplay); assertEquals("My song", s.title)
    }

    @Test fun garbageNeverCrashes() {
        assertNull(SessionCodec.decode("not json", 0))
        assertNull(SessionCodec.decode("{}", 0))
        assertNull(SessionCodec.decode(SessionCodec.encode(emptyList(), 0, false, ""), 0))
    }

    @Test fun indexAndPositionAreClamped() {
        val s = SessionCodec.decode(SessionCodec.encode(urls, 99, false, ""), -5)!!
        assertEquals(1, s.index); assertEquals(0L, s.positionMs); assertFalse(s.autoplay)
    }
}
