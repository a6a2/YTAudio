package app.ytaudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ErrorFormatTest {
    @Test fun linksNeverAppearInReports() {
        val s = ErrorFormat.sanitize("failed https://rr1.googlevideo.com/videoplayback?ip=1.2.3.4&sig=abc and http://x.y/z done")
        assertFalse(s.contains("googlevideo"))
        assertFalse(s.contains("1.2.3.4"))
        assertEquals("failed <link> and <link> done", s)
    }

    @Test fun reportHasTheUsefulParts() {
        val e = IOException("Unable to resolve host https://secret.example/token", IllegalStateException("inner"))
        val r = ErrorFormat.format("2026-11-05 10:00:00 +0000", "2.3.0", "15", "Pixel 9", "Network error.", e)
        assertTrue(r.startsWith("YT Audio 2.3.0"))
        assertTrue(r.contains("Android 15, Pixel 9"))
        assertTrue(r.contains("Problem: Network error."))
        assertTrue(r.contains("java.io.IOException: Unable to resolve host <link>"))
        assertTrue(r.contains("Caused by:"))
        assertFalse(r.contains("secret.example"))
    }

    @Test fun worksWithoutAnException() {
        val r = ErrorFormat.format("t", "1", "14", "dev", "Couldn't play this video.", null)
        assertTrue(r.endsWith("Problem: Couldn't play this video."))
    }
}
