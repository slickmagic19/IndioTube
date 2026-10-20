package com.indiotube.app

import android.content.Context
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

// Pins the two playlist behaviours behind the Watch screen's Autoplay switch.
// The service applies setPauseAtEndOfMediaItems(!autoplay) to its ExoPlayer,
// so these tests exercise exactly that behavior with a real player.
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
