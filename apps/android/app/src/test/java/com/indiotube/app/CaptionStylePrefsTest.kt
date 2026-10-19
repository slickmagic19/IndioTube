package com.indiotube.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.indiotube.app.data.CaptionPrefs
import com.indiotube.app.data.CaptionStyleSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The caption customization sheet writes through CaptionPrefs; these tests pin
// the YouTube-like defaults, persistence across instances, and the clamping of
// out-of-range values.
@RunWith(RobolectricTestRunner::class)
class CaptionStylePrefsTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun defaultsMatchTheYouTubeLook() {
        val style = CaptionPrefs(context).style.value
        assertEquals(15f, style.textSizeSp)
        assertEquals(0xFFFFFFFF.toInt(), style.textColor)
        assertEquals(50, style.backgroundOpacity)
        assertEquals(0x80000000.toInt(), style.backgroundColor)
        assertEquals(CaptionStyleSettings.EDGE_NONE, style.edgeStyle)
        assertEquals("sans-serif", style.fontFamily)
    }

    @Test
    fun styleChangesPersistAcrossInstances() {
        val prefs = CaptionPrefs(context)
        prefs.setTextSize(22f)
        prefs.setTextColor(0xFFFFEB3B.toInt())
        prefs.setBackgroundOpacity(80)
        prefs.setEdgeStyle(CaptionStyleSettings.EDGE_SHADOW)
        prefs.setFontFamily("serif")

        val style = CaptionPrefs(context).style.value
        assertEquals(22f, style.textSizeSp)
        assertEquals(0xFFFFEB3B.toInt(), style.textColor)
        assertEquals(80, style.backgroundOpacity)
        assertEquals(0xCC000000.toInt(), style.backgroundColor)
        assertEquals(CaptionStyleSettings.EDGE_SHADOW, style.edgeStyle)
        assertEquals("serif", style.fontFamily)
    }

    @Test
    fun outOfRangeValuesAreClamped() {
        val prefs = CaptionPrefs(context)
        prefs.setTextSize(90f)
        prefs.setBackgroundOpacity(150)
        prefs.setEdgeStyle(9)

        val style = CaptionPrefs(context).style.value
        assertEquals(24f, style.textSizeSp)
        assertEquals(100, style.backgroundOpacity)
        assertEquals(0xFF000000.toInt(), style.backgroundColor)
        assertEquals(CaptionStyleSettings.EDGE_SHADOW, style.edgeStyle)

        prefs.setTextSize(1f)
        prefs.setBackgroundOpacity(-20)
        assertEquals(10f, CaptionPrefs(context).style.value.textSizeSp)
        assertEquals(0, CaptionPrefs(context).style.value.backgroundColor)
    }
}
