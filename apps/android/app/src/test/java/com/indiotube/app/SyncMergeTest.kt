package com.indiotube.app

import com.indiotube.app.data.HistoryEntry
import com.indiotube.app.data.LibraryFile
import com.indiotube.app.data.LocalPlaylist
import com.indiotube.app.data.SearchLogEntry
import com.indiotube.app.data.Subscription
import com.indiotube.app.data.SyncJson
import com.indiotube.app.data.SyncMerge
import com.indiotube.app.data.SyncSnapshot
import com.indiotube.app.data.SyncStats
import com.indiotube.app.data.VideoItem
import com.indiotube.app.data.normName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exact twins of apps/desktop/test/syncCore.test.ts: both platforms run
 * these vectors against the same payloads, which is what proves the phone and
 * the PC converge to the same library from a merge.
 */
class SyncMergeTest {

    private fun video(id: String) = VideoItem(videoId = id, title = "T-$id", durationSec = 60)
    private fun hist(id: String, ts: Long, positionMs: Long = 0, durationMs: Long = 60_000) =
        HistoryEntry(video(id), ts, positionMs, durationMs)

    @Test
    fun unionsLikedAndWatchLaterPrependingRemoteOnly() {
        val local = LibraryFile(liked = listOf(video("a")), watchLater = listOf(video("w")))
        val incoming = SyncSnapshot(
            library = LibraryFile(
                liked = listOf(video("a"), video("b")),
                watchLater = listOf(video("x"), video("w")),
            ),
        )
        val m = SyncMerge.merge(local, emptyList(), incoming)
        assertEquals(listOf("b", "a"), m.library.liked.map { it.videoId })
        assertEquals(listOf("x", "w"), m.library.watchLater.map { it.videoId })
        assertEquals(1, m.stats.likedAdded)
        assertEquals(1, m.stats.watchLaterAdded)
    }

    @Test
    fun historyKeepsNewestTsAndTravelsWithProgress() {
        val local = LibraryFile(history = listOf(hist("v1", 100, 9_000)))
        val older = SyncSnapshot(library = LibraryFile(history = listOf(hist("v1", 50, 40_000))))
        val r1 = SyncMerge.merge(local, emptyList(), older)
        assertEquals(9_000L, r1.library.history[0].positionMs)
        assertEquals(0, r1.stats.historyAdded)

        val newer = SyncSnapshot(library = LibraryFile(history = listOf(hist("v1", 200, 30_000))))
        val r2 = SyncMerge.merge(local, emptyList(), newer)
        assertEquals(30_000L, r2.library.history[0].positionMs)
        assertEquals(200L, r2.library.history[0].ts)
    }

    @Test
    fun historyTiesBreakTowardLargerPosition() {
        val local = LibraryFile(history = listOf(hist("v", 100, 5_000)))
        val incoming = SyncSnapshot(library = LibraryFile(history = listOf(hist("v", 100, 40_000))))
        val m = SyncMerge.merge(local, emptyList(), incoming)
        assertEquals(40_000L, m.library.history[0].positionMs)
    }

    @Test
    fun mergingTheSamePayloadTwiceIsANoOp() {
        val local = LibraryFile(liked = listOf(video("a")), history = listOf(hist("h", 1)))
        val subs = listOf(Subscription("UCb", "B"))
        val incoming = SyncSnapshot(
            library = LibraryFile(
                liked = listOf(video("b")),
                history = listOf(hist("h2", 2)),
                playlists = listOf(LocalPlaylist("", "Road", 0, listOf(video("r")))),
            ),
            subscriptions = listOf(Subscription("UC1", "Chan")),
        )
        val first = SyncMerge.merge(local, subs, incoming)
        val second = SyncMerge.merge(first.library, first.subscriptions, incoming)
        assertEquals(first.library.liked, second.library.liked)
        assertEquals(first.library.history, second.library.history)
        assertEquals(first.library.playlists, second.library.playlists)
        assertEquals(first.subscriptions, second.subscriptions)
        assertEquals(0, second.stats.total)
    }

    @Test
    fun neverTouchesUnsyncedLocalFields() {
        val local = LibraryFile(historyPaused = true, searches = listOf(SearchLogEntry("abc", 1)))
        val m = SyncMerge.merge(local, emptyList(), SyncSnapshot())
        assertTrue(m.library.historyPaused)
        assertEquals(1, m.library.searches.size)
    }

    @Test
    fun playlistsMatchByNormalizedName() {
        val local = LibraryFile(playlists = listOf(LocalPlaylist("p1", "  Gym  HITS ", 0, listOf(video("a")))))
        val incoming = SyncSnapshot(
            library = LibraryFile(
                playlists = listOf(LocalPlaylist("ignored", "gym hits", 0, listOf(video("a"), video("b")))),
            ),
        )
        val m = SyncMerge.merge(local, emptyList(), incoming)
        assertEquals(1, m.library.playlists.size)
        assertEquals("p1", m.library.playlists[0].id) // never taken from the wire
        assertEquals(listOf("a", "b"), m.library.playlists[0].videos.map { it.videoId })
        assertEquals(1, m.stats.playlistsMerged)
        assertEquals(1, m.stats.videosAdded)
    }

    @Test
    fun appendsUnknownPlaylistAndCountsDistinctVideos() {
        val local = LibraryFile(playlists = listOf(LocalPlaylist("p1", "Old", 0, emptyList())))
        val incoming = SyncSnapshot(
            library = LibraryFile(
                playlists = listOf(LocalPlaylist("", "Focus", 0, listOf(video("f1"), video("f1")))),
            ),
        )
        val m = SyncMerge.merge(local, emptyList(), incoming)
        assertEquals(2, m.library.playlists.size)
        assertEquals(1, m.stats.playlistsAdded)
        assertEquals(1, m.stats.videosAdded)
    }

    @Test
    fun subscriptionsUnionWithRemotePrependedInOrder() {
        val local = listOf(Subscription("UCb", "B"))
        val incoming = SyncSnapshot(
            subscriptions = listOf(
                Subscription("UCb"), Subscription("UCa", "A"), Subscription("UCc", "C"),
            ),
        )
        val m = SyncMerge.merge(LibraryFile(), local, incoming)
        assertEquals(listOf("UCa", "UCc", "UCb"), m.subscriptions.map { it.channelId })
        assertEquals(2, m.stats.subsAdded)
    }

    @Test
    fun historyCapSurvivesABigIncomingList() {
        val local = LibraryFile(history = (0 until 500).map { hist("l$it", 1000L + it) })
        val incoming = SyncSnapshot(library = LibraryFile(history = listOf(hist("new", 9_999))))
        val m = SyncMerge.merge(local, emptyList(), incoming)
        assertEquals(500, m.library.history.size)
        assertEquals("new", m.library.history[0].video.videoId)
        assertFalse(m.library.history.any { it.video.videoId == "l0" })
    }

    @Test
    fun snapshotTrimsUnsyncedFieldsAndBlanksPlaylistIds() {
        val library = LibraryFile(
            liked = listOf(video("a")),
            searches = listOf(SearchLogEntry("secret", 1)),
            playlists = listOf(LocalPlaylist("p1", "X", 0, listOf(video("v")))),
        )
        val snap = SyncMerge.snapshot("Pixel 8", library, listOf(Subscription("UC1")))
        assertEquals("sync", snap.indio)
        assertEquals("Pixel 8", snap.name)
        assertEquals("", snap.library.playlists[0].id)
        assertTrue(snap.library.searches.isEmpty())
        assertEquals(1, snap.subscriptions.size)
    }

    @Test
    fun snapshotRoundTripsThroughTheWireFormat() {
        // The desktop reads plain JSON; make sure the envelope encodes with
        // exactly the field names the JS merge expects.
        val snap = SyncMerge.snapshot("Phone", LibraryFile(liked = listOf(video("a"))), emptyList())
        val text = SyncJson.encodeToString(SyncSnapshot.serializer(), snap)
        assertTrue(text.contains("\"indio\":\"sync\""))
        assertTrue(text.contains("\"watchLater\""))
        assertTrue(text.contains("\"subscriptions\""))
        assertTrue(text.contains("\"liked\""))
    }

    @Test
    fun summaryReadsLikeTheDesktop() {
        val s = SyncStats().apply {
            historyAdded = 2; likedAdded = 1; playlistsMerged = 1; videosAdded = 3
        }
        assertEquals("+2 history · +1 liked · 1 playlists updated", SyncMerge.summary(s))
        assertEquals("already in sync", SyncMerge.summary(SyncStats()))
    }

    @Test
    fun normNameCollapsesWhitespace() {
        assertEquals("chill beats", normName("  Chill   Beats "))
    }
}
