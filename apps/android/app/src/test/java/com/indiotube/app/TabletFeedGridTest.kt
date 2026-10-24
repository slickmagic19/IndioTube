package com.indiotube.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.indiotube.app.data.VideoItem
import com.indiotube.app.ui.IndioTubeTheme
import com.indiotube.app.ui.VideoCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Home, Explore, Subscriptions, Search and channel pages all feed their cards
// through the same grid. This renders the real VideoCard in the grid a tablet
// uses and checks the cards genuinely share a row instead of stacking.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-xhdpi")
class TabletFeedGridTest {

    @get:Rule
    val compose = createComposeRule()

    private val videos = listOf(
        VideoItem("v1", "First tablet video", channelName = "Indio Dev", durationSec = 612),
        VideoItem("v2", "Second tablet video", channelName = "Indio Dev", durationSec = 240),
    )

    @Test
    fun twoTabletCardsShareARowAndKeepTheSameWidth() {
        compose.setContent {
            IndioTubeTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(videos) { video ->
                            VideoCard(video, onClick = {}, horizontalInset = 8.dp)
                        }
                    }
                }
            }
        }

        val first = compose.onNodeWithText("First tablet video").getBoundsInRoot()
        val second = compose.onNodeWithText("Second tablet video").getBoundsInRoot()

        assertEquals("the cards share a row", first.top, second.top)
        assertTrue("the second card is to the right", second.left > first.left)
        assertEquals(
            "both cells are the same width",
            first.right - first.left,
            second.right - second.left,
        )
    }
}
