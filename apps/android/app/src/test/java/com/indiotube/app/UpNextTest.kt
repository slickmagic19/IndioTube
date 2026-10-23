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
import com.indiotube.app.data.VideoItem
import com.indiotube.app.player.UpNextState
import com.indiotube.app.player.atEndOfItem
import com.indiotube.app.player.shouldStartAutoplayCountdown
import com.indiotube.app.ui.UpNextCard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The rules that decide when the autoplay countdown appears, and the card's
// two actions: play immediately or cancel and stay on the finished video.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class UpNextTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun endOfItemHasAToleranceForTheLastPoll() {
        assertFalse(atEndOfItem(0, 0))
        assertFalse(atEndOfItem(100_000, 0))
        assertFalse(atEndOfItem(98_000, 100_000))
        assertTrue(atEndOfItem(99_300, 100_000))
        assertTrue(atEndOfItem(100_000, 100_000))
        assertTrue(atEndOfItem(150_000, 100_000))
    }

    @Test
    fun countdownNeedsAutoplayANextItemAndAFinishedVideo() {
        fun should(
            autoplay: Boolean = true,
            hasNext: Boolean = true,
            ready: Boolean = true,
            playing: Boolean = false,
            position: Long = 100_000,
            duration: Long = 100_000,
            pending: Boolean = false,
            live: Boolean = false,
        ) = shouldStartAutoplayCountdown(
            autoplayEnabled = autoplay,
            hasNext = hasNext,
            playbackReady = ready,
            isPlaying = playing,
            positionMs = position,
            durationMs = duration,
            alreadyPending = pending,
            isLive = live,
        )

        assertTrue(should())
        assertFalse("autoplay off", should(autoplay = false))
        assertFalse("nothing next", should(hasNext = false))
        assertFalse("not ready", should(ready = false))
        assertFalse("still playing", should(playing = true))
        assertFalse("middle of the video", should(position = 50_000))
        assertFalse("already counting down", should(pending = true))
        assertFalse("a paused live stream is not an ended video", should(live = true))
    }

    @Test
    fun cardShowsTheNextVideoAndCountsDown() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    UpNextCard(
                        state = UpNextState(
                            VideoItem("next", "The next video", channelName = "Some channel"),
                            3,
                        ),
                        onPlayNow = {},
                        onCancel = {},
                    )
                }
            }
        }

        compose.onNodeWithText("Up next in 3s").assertIsDisplayed()
        compose.onNodeWithText("The next video").assertIsDisplayed()
        compose.onNodeWithText("Some channel").assertIsDisplayed()
    }

    @Test
    fun playNowAndCancelAreBothReported() {
        var played = false
        var cancelled = false
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    UpNextCard(
                        state = UpNextState(VideoItem("next", "Next"), 5),
                        onPlayNow = { played = true },
                        onCancel = { cancelled = true },
                    )
                }
            }
        }

        compose.onNodeWithText("Play now").performClick()
        assertTrue(played)
        assertFalse(cancelled)

        compose.onNodeWithContentDescription("Cancel autoplay").performClick()
        assertTrue(cancelled)
    }
}
