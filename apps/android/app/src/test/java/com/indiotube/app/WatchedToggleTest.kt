package com.indiotube.app

import com.indiotube.app.data.HistoryEntry
import com.indiotube.app.data.LibraryStore
import com.indiotube.app.data.VideoItem
import com.indiotube.app.data.resumeStartPosition
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// "Mark as watched / unwatched" and the library's batch removals. These are
// the store-level rules the card menu, watch page overflow and list selection
// all call into.
class WatchedToggleTest {

    private fun store(): LibraryStore {
        val dir = Files.createTempDirectory("indiotube-watched").toFile()
        return LibraryStore(dir, CoroutineScope(Dispatchers.Unconfined))
    }

    private val video = VideoItem("v1", "Video", durationSec = 100)

    @Test
    fun markingWatchedFillsTheProgressAndSurvivesReopen() {
        val store = store()
        store.noteOpen(video)
        store.notePosition(video, 20_000, 100_000)
        assertFalse(store.isWatched("v1"))

        assertTrue(store.markWatched(video))

        assertTrue(store.isWatched("v1"))
        assertEquals(listOf("v1"), store.completedIds().toList())
        assertEquals(1f, store.watchedFraction("v1")!!, 0.001f)
        // Reopening must not restart a video that was explicitly finished.
        assertEquals(0L, resumeStartPosition(store.resumePosition("v1"), store.watchedFraction("v1")))
    }

    @Test
    fun markingUnwatchedClearsProgressAndKeepsTheRow() {
        val store = store()
        store.noteOpen(video)
        store.markWatched(video)

        store.markUnwatched("v1")

        assertFalse(store.isWatched("v1"))
        assertNull(store.watchedFraction("v1"))
        assertEquals(1, store.history.value.size)
        assertTrue(store.history.value.first().video.videoId == "v1")
    }

    @Test
    fun undoRestoresTheExactPreviousProgress() {
        val store = store()
        store.noteOpen(video)
        store.notePosition(video, 35_000, 120_000)
        val previous = store.history.value.first()

        store.markWatched(video)
        assertEquals(1f, store.watchedFraction("v1")!!, 0.001f)

        store.restoreHistory(previous)
        assertEquals(0.2917f, store.watchedFraction("v1")!!, 0.001f)
        assertEquals(35_000L, store.resumePosition("v1"))
    }

    @Test
    fun pausedHistoryRefusesToMarkWatched() {
        val store = store()
        store.setHistoryPaused(true)
        assertFalse(store.markWatched(video))
        assertTrue(store.history.value.isEmpty())
    }

    @Test
    fun dismissingContinueWatchingClearsProgressButKeepsTheRow() {
        val store = store()
        val watched = VideoItem("cw", "Continue", durationSec = 100)
        store.noteOpen(watched)
        store.notePosition(watched, 40_000, 100_000)

        store.dismissContinueWatching("cw")

        assertNull("no progress means no Continue watching card", store.watchedFraction("cw"))
        assertEquals(listOf("cw"), store.history.value.map { it.video.videoId })
    }

    @Test
    fun historyBatchRemoveDropsOnlyTheGivenIds() {
        val store = store()
        listOf("a", "b", "c").forEach { id ->
            store.noteOpen(VideoItem(id, id, durationSec = 60))
        }
        store.removeFromHistory(listOf("a", "c"))
        assertEquals(listOf("b"), store.history.value.map { it.video.videoId })
    }

    @Test
    fun watchLaterAndLikedBatchRemovals() {
        val store = store()
        val a = VideoItem("a", "A")
        val b = VideoItem("b", "B")
        val c = VideoItem("c", "C")
        listOf(a, b, c).forEach { store.toggleWatchLater(it); store.toggleLike(it) }

        store.removeFromWatchLater(listOf("a", "c"))
        store.removeLiked(listOf("b"))

        // Both lists prepend, so the newest addition sits first.
        assertEquals(listOf("b"), store.watchLater.value.map { it.videoId })
        assertEquals(listOf("c", "a"), store.liked.value.map { it.videoId })
    }

    @Test
    fun searchEntriesCanBeRemovedOneAtATimeAndPersist() {
        val dir = Files.createTempDirectory("indiotube-searches").toFile()
        val store = LibraryStore(dir, CoroutineScope(Dispatchers.Unconfined))
        store.noteSearch("cats")
        store.noteSearch("dogs")
        store.noteSearch("birds")

        store.removeSearch("cats")
        assertEquals(listOf("birds", "dogs"), store.searches.value.map { it.query })

        // RemoveSearch persists alongside the rest of the library file.
        val saved = java.io.File(dir, "library_.json")
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            if (saved.exists() && !saved.readText().contains("cats")) break
            Thread.sleep(20)
        }
        val reloaded = LibraryStore(dir, CoroutineScope(Dispatchers.Unconfined))
        reloaded.load()
        assertEquals(listOf("birds", "dogs"), reloaded.searches.value.map { it.query })
    }

    @Test
    fun playlistBatchRemoveKeepsOtherEntries() {
        val store = store()
        val a = VideoItem("a", "A")
        val b = VideoItem("b", "B")
        val playlist = store.createPlaylist("Mix")
        store.addToPlaylist(playlist.id, a)
        store.addToPlaylist(playlist.id, b)

        store.removeFromPlaylist(playlist.id, listOf("a"))

        val updated = store.playlists.value.first { it.id == playlist.id }
        assertEquals(listOf("b"), updated.videos.map { it.videoId })
    }

    @Test
    fun restoreHistoryDeduplicatesByVideo() {
        val store = store()
        store.noteOpen(video)
        val old = HistoryEntry(video, positionMs = 10_000, durationMs = 100_000)
        store.markWatched(video)
        store.restoreHistory(old)
        assertEquals(1, store.history.value.size)
        assertEquals(10_000L, store.history.value.first().positionMs)
    }
}
