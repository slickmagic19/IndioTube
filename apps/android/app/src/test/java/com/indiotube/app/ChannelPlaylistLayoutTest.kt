package com.indiotube.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.indiotube.app.data.PlaylistItem
import com.indiotube.app.ui.ChannelPlaylistRow
import com.indiotube.app.ui.PlaylistCoverTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// The channel Playlists tab used to be a full-width row per playlist, which
// is what made the tab look broken. It is now a grid of cards, the way
// YouTube shows it. This renders the real composable and checks the cards
// really end up side by side, that they keep the same width and that the
// cover keeps YouTube's 16:9 shape.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChannelPlaylistLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private val playlists = listOf(
        PlaylistItem("pl1", "Documentaries", thumbnail = null, videoCountText = "8 videos"),
        PlaylistItem("pl2", "Web Development", thumbnail = null, videoCountText = "12 videos"),
    )

    @Test
    fun cardsSitNextToEachOtherInOneRow() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    ChannelPlaylistRow(playlists, columns = 2) {}
                }
            }
        }

        val first = compose.onNodeWithText("Documentaries").getBoundsInRoot()
        val second = compose.onNodeWithText("Web Development").getBoundsInRoot()

        assertEquals("the cards share a row", first.top, second.top)
        assertTrue("the second card is to the right of the first", second.left > first.left)
        assertEquals("the cards are the same width", first.right - first.left, second.right - second.left)
        assertEquals("the cards are the same height", first.bottom - first.top, second.bottom - second.top)
    }

    @Test
    fun everyCardShowsItsTitleAndVideoCount() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    ChannelPlaylistRow(playlists, columns = 2) {}
                }
            }
        }

        compose.onNodeWithText("Documentaries").assertIsDisplayed()
        compose.onNodeWithText("Web Development").assertIsDisplayed()
        compose.onNodeWithText("8 videos").assertIsDisplayed()
        compose.onNodeWithText("12 videos").assertIsDisplayed()
        compose.onAllNodesWithText("Playlist").assertCountEquals(2)
    }

    @Test
    fun theCoverKeepsYoutubesSixteenByNineShapeAboveTheTitle() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    ChannelPlaylistRow(playlists, columns = 2) {}
                }
            }
        }

        val cover = compose.onAllNodesWithTag(PlaylistCoverTag, useUnmergedTree = true)[0].getBoundsInRoot()
        val title = compose.onNodeWithText("Documentaries", useUnmergedTree = true).getBoundsInRoot()
        val ratio = (cover.right - cover.left) / (cover.bottom - cover.top)
        assertEquals("the cover is 16:9", 16f / 9f, ratio, 0.05f)
        assertTrue("the cover sits above the title", cover.bottom <= title.top)
    }
}
