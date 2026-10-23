package com.indiotube.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.indiotube.app.data.ContinueWatchingItem
import com.indiotube.app.data.HistoryEntry
import com.indiotube.app.data.VideoItem
import com.indiotube.app.data.continueWatching
import com.indiotube.app.ui.ContinueWatchingRail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Home's "Continue watching" rail: which history rows qualify, the time-left
// badge, and the rail actually rendering and reporting taps.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ContinueWatchingTest {

    private fun entry(
        id: String,
        positionMs: Long,
        durationMs: Long,
        title: String = "Video $id",
        live: Boolean = false,
        membersOnly: Boolean = false,
        ts: Long = 0,
    ) = HistoryEntry(
        video = VideoItem(
            videoId = id,
            title = title,
            durationSec = durationMs / 1000,
            isLive = live,
            membersOnly = membersOnly,
        ),
        ts = ts,
        positionMs = positionMs,
        durationMs = durationMs,
    )

    @Test
    fun onlyStartedButUnfinishedVideosAreOffered() {
        val items = continueWatching(
            listOf(
                entry("done", 100_000, 100_000),
                entry("fresh", 1_000, 100_000),
                entry("middle", 42_000, 100_000),
                entry("short", 5_000, 10_000),
                entry("live", 30_000, 100_000, live = true),
                entry("members", 30_000, 100_000, membersOnly = true),
            ),
        )
        assertEquals(listOf("middle"), items.map { it.video.videoId })
        assertEquals(58_000L, items.single().remainingMs)
        assertEquals("0:58 left", items.single().remainingText)
    }

    @Test
    fun duplicatesKeepTheNewestEntry() {
        val items = continueWatching(
            listOf(
                entry("v", 80_000, 100_000, ts = 2),
                entry("v", 20_000, 100_000, ts = 1),
            ),
        )
        assertEquals(1, items.size)
        assertEquals(0.8f, items.single().fraction, 0.001f)
    }

    @Test
    fun limitCapsTheRail() {
        val history = (1..20).map { entry("v$it", 20_000, 100_000) }
        assertEquals(12, continueWatching(history).size)
        assertEquals(3, continueWatching(history, limit = 3).size)
    }

    @Test
    fun theCardDurationIsTheFallbackWhenPlaybackNeverStoredOne() {
        val video = VideoItem("fallback", "Fallback", durationSec = 200)
        val items = continueWatching(
            listOf(HistoryEntry(video, positionMs = 50_000, durationMs = 0)),
        )
        assertEquals(150_000L, items.single().remainingMs)
        assertEquals("2:30 left", items.single().remainingText)
    }

    @Test
    fun aVideoAtTheEndReadsAlmostDone() {
        val item = ContinueWatchingItem(VideoItem("x"), fraction = 0.9f, remainingMs = 0)
        assertEquals("Almost done", item.remainingText)
    }

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun railRendersTitlesAndReportsTheTappedItem() {
        val items = listOf(
            ContinueWatchingItem(VideoItem("a", "First video"), 0.25f, 60_000),
            ContinueWatchingItem(VideoItem("b", "Second video"), 0.75f, 15_000),
        )
        var tapped: ContinueWatchingItem? = null

        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    ContinueWatchingRail(
                        items = items,
                        onClick = { tapped = it },
                        onRemove = {},
                    )
                }
            }
        }

        compose.onNodeWithText("Continue watching").assertIsDisplayed()
        compose.onNodeWithText("First video").assertIsDisplayed()
        compose.onNodeWithText("Second video").performClick()
        assertEquals("b", tapped?.video?.videoId)
        assertTrue(items.isEmpty().not())
    }

    @Test
    fun removingACardReportsOnlyThatItem() {
        val items = listOf(
            ContinueWatchingItem(VideoItem("a", "First video"), 0.25f, 60_000),
            ContinueWatchingItem(VideoItem("b", "Second video"), 0.75f, 15_000),
        )
        var removed: ContinueWatchingItem? = null
        var tapped: ContinueWatchingItem? = null

        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    ContinueWatchingRail(
                        items = items,
                        onClick = { tapped = it },
                        onRemove = { removed = it },
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Remove First video from Continue watching")
            .performClick()

        assertEquals("a", removed?.video?.videoId)
        assertEquals("removing must not open the video", null, tapped)
    }
}
