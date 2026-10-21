package com.indiotube.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.indiotube.app.data.DOWNLOAD_QUALITY_CHOICES
import com.indiotube.app.data.DownloadPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DownloadPrefsTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun reset() {
        context.getSharedPreferences("indiotube_downloads", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun defaultsMatchExistingDownloadBehavior() {
        val settings = DownloadPrefs(context).settings.value
        assertNull(settings.defaultHeight)
        assertFalse(settings.wifiOnly)
    }

    @Test
    fun defaultQualityPersistsAcrossInstances() {
        DownloadPrefs(context).setDefaultHeight(720)
        assertEquals(720, DownloadPrefs(context).settings.value.defaultHeight)
    }

    @Test
    fun defaultQualityCanReturnToAuto() {
        val prefs = DownloadPrefs(context)
        prefs.setDefaultHeight(1080)
        prefs.setDefaultHeight(null)
        assertNull(DownloadPrefs(context).settings.value.defaultHeight)
    }

    @Test
    fun wifiOnlyPersistsAcrossInstances() {
        DownloadPrefs(context).setWifiOnly(true)
        assertTrue(DownloadPrefs(context).settings.value.wifiOnly)
    }

    @Test
    fun qualityChoicesCoverAutoAndEveryDownloadableHeight() {
        assertEquals(
            listOf(null, 1080, 720, 480, 360, 240, 144),
            DOWNLOAD_QUALITY_CHOICES,
        )
    }
}
