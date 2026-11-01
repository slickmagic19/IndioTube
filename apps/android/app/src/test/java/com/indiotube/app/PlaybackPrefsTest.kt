package com.indiotube.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.indiotube.app.data.PlaybackPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The Watch screen's Autoplay switch; YouTube defaults to on and remembers
// the choice across launches.
@RunWith(RobolectricTestRunner::class)
class PlaybackPrefsTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun autoplayDefaultsToOn() {
        assertTrue(PlaybackPrefs(context).autoplayNext.value)
    }

    @Test
    fun autoplayOffPersistsAcrossInstances() {
        PlaybackPrefs(context).setAutoplayNext(false)
        val reloaded = PlaybackPrefs(context)
        assertFalse(reloaded.autoplayNext.value)
        assertFalse(reloaded.autoplayNext.value)
    }

    @Test
    fun autoplayCanBeTurnedBackOn() {
        val prefs = PlaybackPrefs(context)
        prefs.setAutoplayNext(false)
        prefs.setAutoplayNext(true)
        assertTrue(PlaybackPrefs(context).autoplayNext.value)
    }

    // The default quality is what a new video starts on instead of plain Auto.
    @Test
    fun theDefaultQualityIsAuto() {
        assertNull(PlaybackPrefs(context).defaultQualityHeight.value)
    }

    @Test
    fun aDefaultQualityPersistsAcrossInstances() {
        PlaybackPrefs(context).setDefaultQualityHeight(1080)
        assertEquals(1080, PlaybackPrefs(context).defaultQualityHeight.value)
    }

    @Test
    fun aDefaultQualityCanBeSetBackToAuto() {
        val prefs = PlaybackPrefs(context)
        prefs.setDefaultQualityHeight(2160)
        prefs.setDefaultQualityHeight(null)
        assertNull(prefs.defaultQualityHeight.value)
        assertNull(PlaybackPrefs(context).defaultQualityHeight.value)
    }

    @Test
    fun aNonsenseHeightIsTreatedAsAuto() {
        val prefs = PlaybackPrefs(context)
        prefs.setDefaultQualityHeight(1080)
        prefs.setDefaultQualityHeight(0)
        assertNull(prefs.defaultQualityHeight.value)
    }

    // The chosen speed sticks across videos and launches; 1x is what a fresh
    // install starts on.
    @Test
    fun thePlaybackSpeedStartsAtNormal() {
        assertEquals(1f, PlaybackPrefs(context).speed.value)
    }

    @Test
    fun aPlaybackSpeedPersistsAcrossInstances() {
        PlaybackPrefs(context).setSpeed(1.5f)
        assertEquals(1.5f, PlaybackPrefs(context).speed.value)
    }

    @Test
    fun aPlaybackSpeedCanBeSetBackToNormal() {
        val prefs = PlaybackPrefs(context)
        prefs.setSpeed(2f)
        prefs.setSpeed(1f)
        assertEquals(1f, prefs.speed.value)
        assertEquals(1f, PlaybackPrefs(context).speed.value)
    }

    @Test
    fun aNonsenseSpeedIsClampedToTheLadder() {
        val prefs = PlaybackPrefs(context)
        prefs.setSpeed(0f)
        assertEquals("zero would freeze playback", 1f, prefs.speed.value)
        prefs.setSpeed(8f)
        assertEquals(2f, prefs.speed.value)
    }
}
