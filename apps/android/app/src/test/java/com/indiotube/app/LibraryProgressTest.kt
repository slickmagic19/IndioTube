package com.indiotube.app

import com.indiotube.app.data.HistoryEntry
import com.indiotube.app.data.LibraryStore
import com.indiotube.app.data.VideoItem
import com.indiotube.app.data.resumeStartPosition
import com.indiotube.app.data.watchedFraction
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Watch progress feeds both UI features: the red progress bar on thumbnails
// (watchedFraction) and the recommender's demotion of finished videos
// (completedIds). These tests pin the math and the storage defaults.
class LibraryProgressTest {

    private fun store(): LibraryStore {
        val dir = Files.createTempDirectory("indiotube-library").toFile()
        return LibraryStore(dir, CoroutineScope(Dispatchers.Unconfined))
    }

    @Test
    fun progressFractionComesFromPlayerDuration() {
        val store = store()
        val video = VideoItem("v1", "Video", durationSec = 100)
        store.noteOpen(video)
        store.notePosition(video, 50_000, 100_000)
        assertEquals(0.5f, store.watchedFraction("v1")!!, 0.001f)
    }

    @Test
    fun progressFallsBackToCardDuration() {
        val store = store()
        val video = VideoItem("v2", "Video", durationSec = 200)
        store.noteOpen(video)
        store.notePosition(video, 30_000, 0)
        assertEquals(0.15f, store.watchedFraction("v2")!!, 0.001f)
    }

    @Test
    fun finishedVideosAreMarkedCompleted() {
        val store = store()
        val video = VideoItem("v3", "Video", durationSec = 100)
        store.noteOpen(video)
        store.notePosition(video, 95_000, 100_000)
        assertEquals(setOf("v3"), store.completedIds())
    }

    @Test
    fun partialWatchesAreNotCompleted() {
        val store = store()
        val video = VideoItem("v4", "Video", durationSec = 100)
        store.noteOpen(video)
        store.notePosition(video, 40_000, 100_000)
        assertTrue(store.completedIds().isEmpty())
        assertEquals(0.4f, store.watchedFraction("v4")!!, 0.001f)
    }

    @Test
    fun neverWatchedVideosHaveNoFraction() {
        assertNull(store().watchedFraction("nope"))
    }

    @Test
    fun openingHistoryKeepsSavedPosition() {
        val store = store()
        val video = VideoItem("v5", "Video", durationSec = 100)
        store.noteOpen(video)
        store.notePosition(video, 40_000, 100_000)
        store.noteOpen(video)
        assertEquals(40_000, store.resumePosition("v5"))
        assertEquals(0.4f, store.watchedFraction("v5")!!, 0.001f)
    }

    @Test
    fun resumeStartSkipsShortAndFinishedVideos() {
        assertEquals(40_000, resumeStartPosition(40_000, 0.4f, 100_000))
        assertEquals(0, resumeStartPosition(15_000, 0.15f, 100_000))
        assertEquals(0, resumeStartPosition(95_000, 0.95f, 100_000))
        assertEquals(0, resumeStartPosition(95_000, 0.4f, 100_000))
        assertEquals(0, resumeStartPosition(95_000, null, 100_000))
        assertEquals(40_000, resumeStartPosition(40_000, null, 0))
    }

    @Test
    fun pausingHistoryStopsNewEntriesAndProgress() {
        val store = store()
        val watched = VideoItem("p1", "Watched", durationSec = 100)
        store.noteOpen(watched)
        store.notePosition(watched, 20_000, 100_000)

        store.setHistoryPaused(true)
        val whilePaused = VideoItem("p2", "Paused")
        store.noteOpen(whilePaused)
        store.notePosition(watched, 60_000, 100_000)

        assertEquals(listOf("p1"), store.history.value.map { it.video.videoId })
        assertEquals("existing progress is frozen", 20_000, store.resumePosition("p1"))
        assertNull("paused videos aren't added", store.watchedFraction("p2"))

        store.setHistoryPaused(false)
        store.noteOpen(whilePaused)
        assertEquals(listOf("p2", "p1"), store.history.value.map { it.video.videoId })
    }

    @Test
    fun historyPausePersistsWithTheAccount() {
        val dir = Files.createTempDirectory("indiotube-library").toFile()
        val store = LibraryStore(dir, CoroutineScope(Dispatchers.Unconfined))
        store.setHistoryPaused(true)

        // Persistence hops onto Dispatchers.IO, so wait for the account file to
        // land rather than assuming the write completes inline.
        val saved = java.io.File(dir, "library_.json")
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            if (saved.exists() && saved.readText().contains("\"historyPaused\":true")) break
            Thread.sleep(20)
        }

        val reloaded = LibraryStore(dir, CoroutineScope(Dispatchers.Unconfined))
        reloaded.load()
        assertTrue("pause should be saved", reloaded.historyPaused.value)

        reloaded.noteOpen(VideoItem("p3", "Not saved"))
        assertTrue("a reloaded pause keeps blocking history", reloaded.history.value.isEmpty())
    }

    @Test
    fun entryFractionHandlesDurationFallbacks() {
        val withDuration = HistoryEntry(VideoItem("a"), positionMs = 25_000, durationMs = 100_000)
        assertEquals(0.25f, withDuration.watchedFraction()!!, 0.001f)

        val clamped = HistoryEntry(VideoItem("b"), positionMs = 300_000, durationMs = 100_000)
        assertEquals(1f, clamped.watchedFraction()!!, 0.001f)

        val noDuration = HistoryEntry(VideoItem("c", durationSec = 0), positionMs = 10_000)
        assertNull(noDuration.watchedFraction())

        val neverPlayed = HistoryEntry(VideoItem("d", durationSec = 60), positionMs = 0)
        assertNull(neverPlayed.watchedFraction())
    }
}
