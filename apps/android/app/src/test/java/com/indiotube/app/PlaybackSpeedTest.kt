package com.indiotube.app

import com.indiotube.app.data.BOOST_PLAYBACK_SPEED
import com.indiotube.app.data.MAX_PLAYBACK_SPEED
import com.indiotube.app.data.MIN_PLAYBACK_SPEED
import com.indiotube.app.data.PLAYBACK_SPEEDS
import com.indiotube.app.data.clampPlaybackSpeed
import com.indiotube.app.data.speedLabel
import com.indiotube.app.data.timeSavedBySpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The speed ladder is shared by the player menu, the Settings row and the
// press-and-hold boost, so the clamps and labels live in one place and are
// pinned here.
class PlaybackSpeedTest {

    @Test
    fun theLadderCoversHalfAndDoubleSpeedAroundNormal() {
        assertTrue("normal speed is always offered", 1f in PLAYBACK_SPEEDS)
        assertTrue("2x is offered for the boost gesture", BOOST_PLAYBACK_SPEED in PLAYBACK_SPEEDS)
        assertEquals(MIN_PLAYBACK_SPEED, PLAYBACK_SPEEDS.min())
        assertEquals(MAX_PLAYBACK_SPEED, PLAYBACK_SPEEDS.max())
        assertEquals(PLAYBACK_SPEEDS.sorted(), PLAYBACK_SPEEDS)
    }

    @Test
    fun speedsOutsideTheLadderAreClamped() {
        assertEquals(MAX_PLAYBACK_SPEED, clampPlaybackSpeed(3f))
        assertEquals(MIN_PLAYBACK_SPEED, clampPlaybackSpeed(0.1f))
        assertEquals(1.5f, clampPlaybackSpeed(1.5f))
    }

    @Test
    fun aBrokenPreferenceFallsBackToNormalSpeed() {
        assertEquals(1f, clampPlaybackSpeed(0f))
        assertEquals(1f, clampPlaybackSpeed(-2f))
        assertEquals(1f, clampPlaybackSpeed(Float.NaN))
    }

    @Test
    fun labelsDropTheTrailingZero() {
        assertEquals("1\u00d7", speedLabel(1f))
        assertEquals("2\u00d7", speedLabel(2f))
        assertEquals("1.5\u00d7", speedLabel(1.5f))
        assertEquals("0.25\u00d7", speedLabel(0.25f))
    }

    @Test
    fun aBrokenSpeedStillHasAReadableLabel() {
        assertEquals("1\u00d7", speedLabel(0f))
        assertEquals("2\u00d7", speedLabel(9f))
    }

    @Test
    fun onlySpeedAboveNormalSavesTime() {
        assertEquals(0, timeSavedBySpeed(60_000, 1f))
        assertEquals(0, timeSavedBySpeed(60_000, 0.5f))
        assertEquals(0, timeSavedBySpeed(0, 2f))
        assertEquals(60_000, timeSavedBySpeed(60_000, 2f))
        assertEquals(30_000, timeSavedBySpeed(60_000, 1.5f))
    }
}
