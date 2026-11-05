package app.ytaudio

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * Live check that NewPipeExtractor can still read YouTube. Skipped unless YT_SMOKE=1, so normal
 * builds never touch the network. Run weekly by .github/workflows/extractor-check.yml.
 */
class ExtractorSmokeTest {
    @Test
    fun resolvesAudioForAKnownVideo() {
        assumeTrue("set YT_SMOKE=1 to run", System.getenv("YT_SMOKE") == "1")
        NewPipe.init(Http)
        val info = try {
            StreamInfo.getInfo(ServiceList.YouTube, YouTubeUrl.video("dQw4w9WgXcQ"))
        } catch (e: Exception) {
            // Data-centre IPs (like CI runners) are sometimes bot-checked: that says nothing about the extractor.
            val blocked = e is ReCaptchaException || e.javaClass.simpleName.contains("NotBot", true)
            assumeTrue("YouTube blocked this runner: ${e.message}", !blocked)
            throw e
        }
        assertTrue("no progressive audio stream found",
            info.audioStreams.any { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP })
    }
}
