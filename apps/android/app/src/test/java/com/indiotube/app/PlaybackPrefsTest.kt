package com.indiotube.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.indiotube.app.data.PlaybackPrefs
import org.junit.Assert.assertFalse
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
}
