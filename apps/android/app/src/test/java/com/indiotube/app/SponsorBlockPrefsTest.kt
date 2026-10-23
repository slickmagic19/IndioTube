package com.indiotube.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.indiotube.app.data.DEFAULT_SPONSOR_CATEGORIES
import com.indiotube.app.data.SponsorBlockPrefs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Auto-skip must arrive OFF: the settings switch is the only way to opt in,
// and every category starts enabled so switching auto-skip on behaves like
// the browser extension without further setup.
@RunWith(RobolectricTestRunner::class)
class SponsorBlockPrefsTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun autoSkipDefaultsToOff() {
        assertFalse(SponsorBlockPrefs(context).settings.value.autoSkip)
    }

    @Test
    fun allCategoriesStartEnabled() {
        val prefs = SponsorBlockPrefs(context).settings.value
        assertTrue(prefs.categories.containsAll(DEFAULT_SPONSOR_CATEGORIES))
        assertTrue(prefs.categories.contains("sponsor"))
        assertTrue(prefs.categories.contains("intro"))
    }

    @Test
    fun autoSkipChoicePersistsAcrossInstances() {
        SponsorBlockPrefs(context).setAutoSkip(true)
        assertTrue(SponsorBlockPrefs(context).settings.value.autoSkip)
        SponsorBlockPrefs(context).setAutoSkip(false)
        assertFalse(SponsorBlockPrefs(context).settings.value.autoSkip)
    }

    @Test
    fun disablingACategoryPersistsAndCanBeReenabled() {
        val prefs = SponsorBlockPrefs(context)
        prefs.setCategory("intro", false)
        assertFalse(SponsorBlockPrefs(context).settings.value.categories.contains("intro"))
        assertTrue(SponsorBlockPrefs(context).settings.value.categories.contains("sponsor"))

        SponsorBlockPrefs(context).setCategory("intro", true)
        assertTrue(SponsorBlockPrefs(context).settings.value.categories.contains("intro"))
    }
}
