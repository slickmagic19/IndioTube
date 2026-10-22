package com.indiotube.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.indiotube.app.ui.CHANNEL_TAB_PLAYLISTS
import com.indiotube.app.ui.ChannelTabRow
import com.indiotube.app.ui.Screen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The selected tab of a channel has to be stored on the screen object, not
// in the composable: the channel is torn down while a playlist is open on top
// of it, and going back has to return to the Playlists tab.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ChannelTabRowTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun pickingThePlaylistsTabIsRememberedOnTheScreen() {
        val screen = Screen.Channel("UC123", "Some channel", null)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    ChannelTabRow(screen)
                }
            }
        }

        compose.onNodeWithText("Playlists").assertIsDisplayed()
        compose.onNodeWithText("Playlists").performClick()

        assertEquals(CHANNEL_TAB_PLAYLISTS, screen.selectedTab.value)
    }
}
