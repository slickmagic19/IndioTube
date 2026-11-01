package com.indiotube.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.indiotube.app.ui.tabletPageWidth
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A landscape tablet window is wider than any list of rows wants to be, so the
// pages are capped and centred instead of stretched. That only works if the cap
// really constrains the page, leaves the leftover room evenly on both sides and
// does not cost the page its height. These render the wrapper at tablet size and
// measure it.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-xhdpi")
class TabletPageWidthTest {

    @get:Rule
    val compose = createComposeRule()

    private fun page(cap: Dp?) {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.tabletPageWidth(cap).testTag("page")) {
                        Column(Modifier.fillMaxSize()) {}
                    }
                }
            }
        }
    }

    private fun windowWidth() =
        with(compose.onRoot().getBoundsInRoot()) { right - left }

    private fun windowHeight() =
        with(compose.onRoot().getBoundsInRoot()) { bottom - top }

    @Test
    fun aCappedPageTakesTheCapAndNotTheWindow() {
        page(720.dp)

        val page = compose.onNodeWithTag("page").getBoundsInRoot()
        val width = page.right - page.left

        assertTrue("the page is capped at $width", width <= 721.dp)
        assertTrue("the page fills the cap it was given: $width", width >= 719.dp)
        assertTrue("the window is wider than the page", windowWidth() > width)
    }

    @Test
    fun aCappedPageIsCentredInTheWindow() {
        page(720.dp)

        val page = compose.onNodeWithTag("page").getBoundsInRoot()
        val airLeft = page.left
        val airRight = windowWidth() - page.right

        // Equal air on both sides is what stops the column looking like it was
        // shoved against the rail on a landscape tablet.
        val gap = if (airLeft > airRight) airLeft - airRight else airRight - airLeft
        assertTrue("the page is centred: $airLeft of air left, $airRight right", gap < 2.dp)
        assertTrue("the page has room at the sides", airLeft > 100.dp)
    }

    @Test
    fun anUncappedPageStillFillsTheWindow() {
        page(null)

        val page = compose.onNodeWithTag("page").getBoundsInRoot()

        assertTrue("the page reaches the left edge", page.left < 1.dp)
        assertTrue("the page reaches the right edge", windowWidth() - page.right < 1.dp)
    }

    @Test
    fun aCappedPageKeepsTheFullHeight() {
        page(720.dp)

        val page = compose.onNodeWithTag("page").getBoundsInRoot()

        // Capping the width must not turn the page into a card: it is still the
        // scrolling surface, so it owns the height.
        val height = page.bottom - page.top
        assertTrue("the page starts at the top", page.top < 2.dp)
        assertTrue("the page runs to the bottom", windowHeight() - height < 2.dp)
    }
}
