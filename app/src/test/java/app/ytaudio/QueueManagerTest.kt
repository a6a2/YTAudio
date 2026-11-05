package app.ytaudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class QueueManagerTest {
    private fun id(n: Int) = "vid" + n.toString().padStart(8, '0')      // 11 chars
    private fun url(n: Int) = YouTubeUrl.video(id(n))
    private fun urls(range: IntRange) = range.map(::url)

    /** Fake playlist: pages of [pageSize] urls taken from [all]. Page tokens are Ints (next start offset). */
    private class Fake(val all: List<String>, val pageSize: Int = 3, val fail: Exception? = null) : PlaylistSource {
        var calls = 0
        override fun first(url: String): Pair<List<String>, Any?> {
            calls++; fail?.let { throw it }
            return page(0)
        }
        override fun more(url: String, page: Any): Pair<List<String>, Any?> {
            calls++; fail?.let { throw it }
            return page(page as Int)
        }
        private fun page(from: Int): Pair<List<String>, Any?> {
            val to = minOf(from + pageSize, all.size)
            return all.subList(from, to) to (if (to < all.size) to else null)
        }
    }

    private fun built(source: PlaylistSource, link: Link) = QueueManager(source).apply { build(link) }

    // ---- single video + autoplay ------------------------------------------------------------

    @Test fun singleVideoPlaysThenAutoplaysUnseenRecommendation() {
        val q = built(Fake(emptyList()), Link(id(1), null))
        assertEquals(1, q.size)
        assertEquals(0, q.step(-1, 1))
        q.setRelated(listOf(url(1), url(2), url(3)))          // 1 is the current video: already seen
        assertEquals(1, q.step(0, 1))
        assertEquals(url(2), q.urlAt(1))
        q.setRelated(listOf(url(1), url(2), url(3)))
        assertEquals(2, q.step(1, 1))                          // next unseen is 3
        assertEquals(url(3), q.urlAt(2))
    }

    @Test fun autoplayCanBeSwitchedOff() {
        val q = built(Fake(emptyList()), Link(id(1), null))
        q.autoplayAllowed = false
        q.setRelated(listOf(url(2)))
        assertNull(q.step(0, 1))
        assertEquals(1, q.size)
    }

    @Test fun autoplayStopsWhenNothingNewIsRecommended() {
        val q = built(Fake(emptyList()), Link(id(1), null))
        q.setRelated(listOf(url(1)))
        assertNull(q.step(0, 1))
    }

    @Test fun stepIsRepeatableForAutoplay() {            // prefetch and the real advance both call step()
        val q = built(Fake(emptyList()), Link(id(1), null))
        q.setRelated(listOf(url(2), url(3)))
        assertEquals(1, q.step(0, 1))
        assertEquals(1, q.step(0, 1))
        assertEquals(2, q.size)
    }

    // ---- playlists --------------------------------------------------------------------------

    @Test fun playlistStartsAtSharedVideoAndPagesOnDemand() {
        val src = Fake(urls(1..10), pageSize = 3)
        val q = built(src, Link(id(5), "PLx"))                // page 1 = 1..3, 5 is found on page 2
        assertEquals(4, q.index + 1)                          // step(+1) from index must give the position of video 5
        assertEquals(4, q.step(q.index, 1))
        assertEquals(url(5), q.urlAt(4))
    }

    @Test fun playlistWithoutVideoStartsAtTheTop() {
        val q = built(Fake(urls(1..4)), Link(null, "PLx"))
        assertEquals(0, q.step(q.index, 1))
    }

    @Test fun walksForwardLoadingMorePagesUntilTheEnd() {
        val q = built(Fake(urls(1..7), pageSize = 3), Link(null, "PLx"))
        var cur = q.index
        val played = ArrayList<String>()
        while (true) { cur = q.step(cur, 1) ?: break; played.add(q.urlAt(cur)) }
        assertEquals(urls(1..7), played)
    }

    @Test fun missingVideoIsInsertedAtTheFront() {
        val q = built(Fake(urls(1..3)), Link(id(99), "PLx"))
        assertEquals(0, q.step(q.index, 1))
        assertEquals(url(99), q.urlAt(0))
    }

    @Test fun brokenPlaylistFallsBackToTheSingleVideo() {
        val q = built(Fake(emptyList(), fail = IllegalStateException("boom")), Link(id(7), "PLx"))
        assertEquals(1, q.size)
        assertEquals(url(7), q.urlAt(0))
        assertTrue(q.isAutoplayChain())
    }

    @Test fun networkErrorsAreNotSwallowed() {
        assertThrows(IOException::class.java) { built(Fake(emptyList(), fail = IOException("offline")), Link(id(7), "PLx")) }
    }

    @Test fun emptyPlaylistWithoutVideoThrows() {
        assertThrows(EmptyQueueException::class.java) { built(Fake(emptyList()), Link(null, "PLx")) }
    }

    @Test fun pagingCannotLoopForever() {
        // A source that keeps returning the same empty page must not hang step().
        val stuck = object : PlaylistSource {
            override fun first(url: String): Pair<List<String>, Any?> = listOf(url(1)) to 0
            override fun more(url: String, page: Any): Pair<List<String>, Any?> = emptyList<String>() to 0
        }
        val q = built(stuck, Link(null, "PLx"))
        assertEquals(0, q.step(q.index, 1))
        assertNull(q.step(0, 1))
    }

    // ---- previous / repeat ------------------------------------------------------------------

    @Test fun previousStopsAtTheStartUnlessRepeatAll() {
        val q = built(Fake(urls(1..3)), Link(null, "PLx"))
        assertEquals(0, q.step(1, -1))
        assertNull(q.step(0, -1))
        q.repeat = Repeat.ALL
        assertEquals(2, q.step(0, -1))
    }

    @Test fun repeatAllWrapsAround() {
        val q = built(Fake(urls(1..3)), Link(null, "PLx"))
        assertNull(q.step(2, 1))
        q.repeat = Repeat.ALL
        assertEquals(0, q.step(2, 1))
    }

    @Test fun repeatAllKeepsASingleVideoLooping() {
        val q = built(Fake(emptyList()), Link(id(1), null))
        q.repeat = Repeat.ALL
        assertEquals(0, q.step(0, 1))
    }

    // ---- rapid taps (regression for "Next twice landed on the same track") ------------------

    @Test fun cursorBasedStepsAccumulate() {
        val q = built(Fake(urls(1..5)), Link(null, "PLx"))
        var cur = q.index
        cur = q.step(cur, 1)!!      // first Next tap moves the cursor immediately...
        cur = q.step(cur, 1)!!      // ...so the second tap goes one further
        assertEquals(1, cur)
    }

    // ---- add to queue -----------------------------------------------------------------------

    @Test fun expandSingleVideo() {
        assertEquals(listOf(url(4)), QueueManager(Fake(emptyList())).expand(Link(id(4), null)))
    }

    @Test fun expandPlaylistFromTheSharedVideo() {
        assertEquals(urls(3..6), QueueManager(Fake(urls(1..6), pageSize = 2)).expand(Link(id(3), "PLx")))
    }

    @Test fun expandRespectsTheLimit() {
        assertEquals(urls(1..4), QueueManager(Fake(urls(1..50), pageSize = 2)).expand(Link(null, "PLx"), limit = 4))
    }

    @Test fun appendAddsAfterTheRest() {
        val q = built(Fake(urls(1..3)), Link(null, "PLx"))
        q.index = 0; q.playingIndex = 0
        q.append(listOf(url(8), url(9)))
        assertEquals(5, q.size)
        assertEquals(url(9), q.urlAt(4))
    }

    @Test fun appendReplacesAnUnplayedAutoplayPick() {
        val q = built(Fake(emptyList()), Link(id(1), null))
        q.index = 0; q.playingIndex = 0
        q.setRelated(listOf(url(2)))
        assertEquals(1, q.step(0, 1))                // prefetch queued recommendation 2
        q.append(listOf(url(7)))                     // the user's pick must come first
        assertEquals(listOf(url(1), url(7)), (0 until q.size).map(q::urlAt))
    }

    // ---- session save / restore -------------------------------------------------------------

    @Test fun snapshotAndRestoreRoundTrip() {
        val q = built(Fake(urls(1..6), pageSize = 10), Link(null, "PLx"))   // all six loaded
        q.playingIndex = 3
        val snap = q.snapshot()!!
        val r = QueueManager(Fake(emptyList()))
        r.restore(snap, autoplayChain = false)
        assertEquals(3, r.step(r.index, 1))
        assertEquals(url(4), r.urlAt(3))
    }

    @Test fun snapshotIsAWindowForHugeQueues() {
        val q = built(Fake(urls(1..1000), pageSize = 1000), Link(null, "PLx"))
        q.playingIndex = 500
        val snap = q.snapshot(maxItems = 300)!!
        assertEquals(300, snap.urls.size)
        assertEquals(url(501), snap.urls[snap.index])         // the playing track is still in the window
    }

    @Test fun emptyQueueHasNoSnapshot() {
        assertNull(QueueManager(Fake(emptyList())).snapshot())
        assertFalse(QueueManager(Fake(emptyList())).isAutoplayChain())
    }
}

class QueueManagerThreadingTest {
    /** Regression guard: a slow playlist download must never make the main thread wait (that would freeze the app). */
    @Test fun queueStaysReadableWhileAPageIsDownloading() {
        val inDownload = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val slow = object : PlaylistSource {
            override fun first(url: String): Pair<List<String>, Any?> =
                listOf(YouTubeUrl.video("vid00000001")) to 1
            override fun more(url: String, page: Any): Pair<List<String>, Any?> {
                inDownload.countDown()
                release.await(10, java.util.concurrent.TimeUnit.SECONDS)      // simulate a slow network
                return listOf(YouTubeUrl.video("vid00000002")) to null
            }
        }
        val q = QueueManager(slow).apply { build(Link(null, "PLx")) }
        val worker = Thread { q.step(0, 1) }                                  // needs page 2 -> blocks in more()
        worker.start()
        assertTrue(inDownload.await(5, java.util.concurrent.TimeUnit.SECONDS))

        val start = System.nanoTime()
        q.size; q.urlOrNull(0); q.snapshot(); q.isAutoplayChain(); q.setRelated(emptyList())   // "main thread" reads
        val ms = (System.nanoTime() - start) / 1_000_000
        release.countDown()
        worker.join(5000)
        assertTrue("main-thread access waited ${ms}ms for the network", ms < 500)
        assertEquals(2, q.size)
    }
}
