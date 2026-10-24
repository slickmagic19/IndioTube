package com.indiotube.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.indiotube.app.data.VideoItem
import com.indiotube.app.ui.UpNextRail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The tablet watch page moves recommendations into their own rail. It has to
// keep the same actions as the inline section: open a video, follow its
// channel and toggle autoplay.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-xhdpi")
class UpNextRailTest {

    @get:Rule
    val compose = createComposeRule()

    private val videos = listOf(
        VideoItem("v1", "Building a tablet layout", channelName = "Indio Dev"),
        VideoItem("v2", "Compose grids explained", channelName = "Indio Dev"),
    )

    private fun setRail(
        autoplay: Boolean = true,
        onToggleAutoplay: (Boolean) -> Unit = {},
        onOpen: (VideoItem) -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    UpNextRail(
                        videos = videos,
                        autoplay = autoplay,
                        onToggleAutoplay = onToggleAutoplay,
                        onOpen = onOpen,
                        onChannelClick = {},
                        onPlayNext = {},
                        onAddToQueue = {},
                        modifier = Modifier.width(360.dp),
                    )
                }
            }
        }
    }

    @Test
    fun theRailShowsTheHeaderAndEveryRecommendation() {
        setRail()

        compose.onNodeWithText("Up next").assertIsDisplayed()
        compose.onNodeWithText("Building a tablet layout").assertIsDisplayed()
        compose.onNodeWithText("Compose grids explained").assertIsDisplayed()
    }

    @Test
    fun tappingARecommendationOpensIt() {
        var opened: VideoItem? = null
        setRail(onOpen = { opened = it })

        compose.onNodeWithText("Compose grids explained").performClick()

        assertEquals("v2", opened?.videoId)
    }

    @Test
    fun theAutoplaySwitchReportsTheNewValue() {
        var autoplay = true
        setRail(onToggleAutoplay = { autoplay = it })

        compose.onNodeWithContentDescription("Autoplay").performClick()

        assertFalse(autoplay)
    }

    @Test
    fun anEmptyRailStillExplainsItself() {
        compose.setContent {
            MaterialTheme {
                UpNextRail(
                    videos = emptyList(),
                    autoplay = false,
                    onToggleAutoplay = {},
                    onOpen = {},
                    onChannelClick = {},
                    onPlayNext = {},
                    onAddToQueue = {},
                    modifier = Modifier.width(360.dp),
                )
            }
        }

        compose.onNodeWithText("No recommendations right now").assertIsDisplayed()
    }
}
