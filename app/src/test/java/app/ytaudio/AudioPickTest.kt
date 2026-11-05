package app.ytaudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioPickTest {
    private data class S(val kbps: Int, val original: Boolean = true)
    private fun pick(list: List<S>, saver: Boolean) = AudioPick.choose(list, { it.kbps }, { it.original }, saver)

    @Test fun bestQualityTakesTheHighestBitrate() =
        assertEquals(S(160), pick(listOf(S(48), S(128), S(160)), saver = false))

    @Test fun dataSaverTakesTheLowestKnownBitrate() =
        assertEquals(S(48), pick(listOf(S(160), S(48), S(128)), saver = true))

    @Test fun dataSaverIgnoresUnknownBitrates() =
        assertEquals(S(70), pick(listOf(S(-1), S(70), S(128)), saver = true))

    @Test fun prefersTheOriginalTrackOverBetterDubs() =
        assertEquals(S(128), pick(listOf(S(128, true), S(256, false)), saver = false))

    @Test fun fallsBackToDubsWhenNothingIsMarkedOriginal() =
        assertEquals(S(256, false), pick(listOf(S(128, false), S(256, false)), saver = false))

    @Test fun nothingPlayableGivesNull() = assertNull(pick(emptyList(), saver = false))
}
