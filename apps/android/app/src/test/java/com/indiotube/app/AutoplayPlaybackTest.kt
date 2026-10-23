package com.indiotube.app

import android.content.Context
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import com.indiotube.app.player.atEndOfItem
import com.indiotube.app.player.shouldStartAutoplayCountdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

// Pins the two playlist behaviours behind the Watch screen's Autoplay switch.
// The service applies setPauseAtEndOfMediaItems(!autoplay || countdownArmed)
// to its ExoPlayer; with the watch page open, the countdown forces the pause
// so these tests exercise exactly that end state with a real player.
@RunWith(RobolectricTestRunner::class)
class AutoplayPlaybackTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun playerWithQueue(): ExoPlayer = TestExoPlayerBuilder(context).build().apply {
        setMediaSources(
            listOf(
                SilenceMediaSource.Factory().setDurationUs(1_000_000).createMediaSource(),
                SilenceMediaSource.Factory().setDurationUs(1_000_000).createMediaSource(),
            ),
        )
        prepare()
        play()
    }

    @Test
    fun autoplayOnAdvancesToTheNextVideo() {
        val player = playerWithQueue()
        TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED)
        assertEquals(1, player.currentMediaItemIndex)
        player.release()
    }

    // The countdown relies on the pause flag's end state: index unchanged,
    // playback READY rather than ENDED, and the position at the very end.
    @Test
    fun pausedAtEndIsDetectableAndCanBeAdvancedExplicitly() {
        val player = playerWithQueue()
        player.setPauseAtEndOfMediaItems(true)
        TestPlayerRunHelper.runUntilPlayWhenReady(player, false)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, player.currentMediaItemIndex)
        assertEquals(Player.STATE_READY, player.playbackState)
        assertTrue(
            "pos=${player.currentPosition} dur=${player.duration}",
            atEndOfItem(player.currentPosition, player.duration),
        )
        assertTrue(
            shouldStartAutoplayCountdown(
                autoplayEnabled = true,
                hasNext = player.hasNextMediaItem(),
                playbackReady = player.playbackState == Player.STATE_READY,
                isPlaying = player.isPlaying,
                positionMs = player.currentPosition,
                durationMs = player.duration,
                alreadyPending = false,
            ),
        )

        // Exactly what playUpNextNow() does when the card is accepted.
        player.seekToNextMediaItem()
        player.play()
        assertEquals(1, player.currentMediaItemIndex)
        TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED)
        player.release()
    }

    @Test
    fun autoplayOffPausesAtTheEndOfTheCurrentVideo() {
        val player = playerWithQueue()
        player.setPauseAtEndOfMediaItems(true)
        // With the pause flag set the player must pause at the end of the
        // first item instead of moving on to the second one.
        TestPlayerRunHelper.runUntilPlayWhenReady(player, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, player.currentMediaItemIndex)
        assertFalse(
            "pos=${player.currentPosition} state=${player.playbackState}" +
                " pwr=${player.playWhenReady} playing=${player.isPlaying}",
            player.isPlaying,
        )
        player.release()
    }
}
