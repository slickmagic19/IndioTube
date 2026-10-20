package com.indiotube.app

import com.indiotube.app.data.LibraryStore
import com.indiotube.app.data.VideoItem
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Explicit feed feedback: hiding a video, liking a channel's style and
// blocking a channel. The recommender consumes these sets, so the tests pin
// the state transitions, including the Undo paths.
class RecommendationFeedbackTest {

    private fun store(): LibraryStore {
        val dir = Files.createTempDirectory("indiotube-feedback").toFile()
        return LibraryStore(dir, CoroutineScope(Dispatchers.Unconfined))
    }

    private val video = VideoItem("v1", "Video", channelId = "c1", channelName = "Channel")

    @Test
    fun notInterestedHidesTheVideoAndUndoRestoresIt() {
        val store = store()
        store.markNotInterested(video)
        assertEquals(setOf("v1"), store.notInterestedIds.value)

        store.undoNotInterested("v1")
        assertTrue(store.notInterestedIds.value.isEmpty())
    }

    @Test
    fun interestedRemembersTheChannelAndClearsNegativeFeedback() {
        val store = store()
        store.markNotInterested(video)
        store.markInterested(video)

        assertEquals(setOf("c1"), store.interestedChannelIds.value)
        assertTrue(store.notInterestedIds.value.isEmpty())
    }

    @Test
    fun notInterestedAlsoDropsChannelInterest() {
        val store = store()
        store.markInterested(video)
        store.markNotInterested(video)

        assertTrue(store.interestedChannelIds.value.isEmpty())
        assertEquals(setOf("v1"), store.notInterestedIds.value)
    }

    @Test
    fun blockingAChannelHidesItAndUndoRestoresIt() {
        val store = store()
        store.markInterested(video)
        store.blockChannel(video)

        assertEquals(setOf("c1"), store.blockedChannelIds.value)
        assertTrue(store.interestedChannelIds.value.isEmpty())

        store.unblockChannel("c1")
        assertTrue(store.blockedChannelIds.value.isEmpty())
    }

    @Test
    fun videosWithoutAChannelCannotBlock() {
        val store = store()
        store.blockChannel(VideoItem("v2", "No channel"))
        assertTrue(store.blockedChannelIds.value.isEmpty())
    }
}
