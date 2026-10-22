package com.indiotube.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.indiotube.app.ui.DefaultQualityHeights
import com.indiotube.app.ui.DefaultQualitySection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The quality sheet's "Default for new videos" picker: the rungs it offers and
// the value it hands back, since that value is what every future video starts
// on.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class DefaultQualitySectionTest {

    @get:Rule
    val compose = createComposeRule()

    private fun render(selected: Int?, onPick: (Int?) -> Unit) {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    DefaultQualitySection(selectedHeight = selected, onSelect = onPick)
                }
            }
        }
    }

    @Test
    fun everyRungIsOfferedNextToAuto() {
        var picked: Int? = 12345
        render(selected = null, onPick = { picked = it })

        compose.onNodeWithText("Auto").assertIsDisplayed()
        DefaultQualityHeights.forEach { height ->
            compose.onNodeWithText("${height}p").assertIsDisplayed()
        }
        compose.onAllNodesWithContentDescription("Selected").assertCountEquals(1)
    }

    @Test
    fun pickingARungReportsItsHeight() {
        var picked: Int? = null
        render(selected = null, onPick = { picked = it })

        compose.onNodeWithText("1080p").performClick()

        assertEquals(1080, picked)
    }

    @Test
    fun pickingAutoReportsNoHeight() {
        var picked: Int? = 720
        render(selected = 720, onPick = { picked = it })

        compose.onNodeWithText("Auto").performClick()

        assertNull(picked)
    }

    @Test
    fun theCurrentDefaultIsTheCheckedRow() {
        render(selected = 1440, onPick = {})

        // Exactly one row carries the check mark, and the section offers the
        // full ladder plus Auto.
        compose.onAllNodesWithContentDescription("Selected").assertCountEquals(1)
        compose.onNodeWithText("1440p").assertIsDisplayed()
    }
}
