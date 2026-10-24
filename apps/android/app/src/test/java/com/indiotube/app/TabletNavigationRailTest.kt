package com.indiotube.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.indiotube.app.ui.TabletNavRail
import com.indiotube.app.ui.rememberWindowLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Tablets browse from YouTube's left rail instead of the phone's bottom bar.
// This renders the real rail at tablet size and checks the four destinations
// are stacked, reachable and report the right tab index.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-xhdpi")
class TabletNavigationRailTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun everyDestinationIsOnTheRail() {
        compose.setContent {
            MaterialTheme {
                TabletNavRail(tab = 0) {}
            }
        }

        listOf("Home", "Explore", "Subscriptions", "You").forEach { label ->
            compose.onNodeWithText(label).assertExists()
        }
        compose.onNodeWithText("Home").assertIsDisplayed()
    }

    @Test
    fun tappingADestinationReportsItsTab() {
        var selected = -1
        compose.setContent {
            MaterialTheme {
                TabletNavRail(tab = 0) { selected = it }
            }
        }

        compose.onNodeWithText("Subscriptions").performClick()

        assertEquals(2, selected)
    }

    @Test
    fun destinationsStackVerticallyOnTheLeftEdge() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    TabletNavRail(tab = 0) {}
                }
            }
        }

        val home = compose.onNodeWithText("Home").getBoundsInRoot()
        val explore = compose.onNodeWithText("Explore").getBoundsInRoot()

        assertTrue("Home sits above Explore", home.bottom <= explore.top)
        assertTrue("the rail hugs the left edge", home.left < 120.dp)
        assertTrue("the rail stays narrow", home.right < 200.dp)
    }

    @Test
    fun theWindowAtTabletSizeChoosesTheRailLayout() {
        var columns = -1
        var rail = false
        var twoPane = false
        compose.setContent {
            val layout = rememberWindowLayout()
            columns = layout.feedColumns
            rail = layout.useNavigationRail
            twoPane = layout.watchTwoPane
        }

        assertEquals(3, columns)
        assertTrue(rail)
        assertTrue(twoPane)
    }
}
