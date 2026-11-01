package com.indiotube.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.indiotube.app.data.VideoItem
import com.indiotube.app.ui.IndioTubeTheme
import com.indiotube.app.ui.VideoCard
import com.indiotube.app.ui.VideoRow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Members-only videos are kept off the recommendation feeds, so when one does
// show up (search, a channel page, the library) the card has to say why it
// will not play: a lock pill on the thumbnail and a tag in the meta line.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class MembersTagTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aMembersOnlyCardIsTaggedOnTheThumbnailAndTheMetaLine() {
        compose.setContent {
            IndioTubeTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    VideoCard(
                        VideoItem(
                            "m1",
                            "Members only stream",
                            channelName = "Indio Dev",
                            durationSec = 300,
                            membersOnly = true,
                        ),
                        onClick = {},
                    )
                }
            }
        }

        compose.onAllNodesWithText("Members").assertCountEquals(1)
        compose.onAllNodesWithText("Members only").assertCountEquals(1)
    }

    @Test
    fun aMembersOnlyRowIsTagged() {
        compose.setContent {
            IndioTubeTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    VideoRow(
                        VideoItem(
                            "m2",
                            "Members only row",
                            channelName = "Indio Dev",
                            membersOnly = true,
                        ),
                        onClick = {},
                    )
                }
            }
        }

        compose.onNodeWithText("Members only").assertExists()
        compose.onNodeWithText("Members").assertExists()
    }

    @Test
    fun aPublicCardCarriesNoMembersTag() {
        compose.setContent {
            IndioTubeTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    VideoCard(
                        VideoItem("p1", "A normal video", channelName = "Indio Dev"),
                        onClick = {},
                    )
                }
            }
        }

        compose.onAllNodesWithText("Members", substring = true).assertCountEquals(0)
    }
}
