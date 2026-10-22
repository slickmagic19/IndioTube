package com.indiotube.app

import com.indiotube.app.ui.CHANNEL_TAB_PLAYLISTS
import com.indiotube.app.ui.CHANNEL_TAB_VIDEOS
import com.indiotube.app.ui.NavStack
import com.indiotube.app.ui.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Opening a playlist from a channel's Playlists tab and going back must
// land on the Playlists tab again, not on the videos. The channel screen is
// torn down while the playlist is open on top of it, so the selected tab
// lives on the screen object and the back stack keeps that object alive.
class ChannelBackNavigationTest {

    @Test
    fun goingBackFromAPlaylistKeepsThePlaylistsTab() {
        val stack = NavStack()
        val channel = Screen.Channel("UC123", "Some channel", null)
        stack.open(channel)
        channel.selectedTab.value = CHANNEL_TAB_PLAYLISTS
        stack.open(Screen.YtPlaylist("PL123", "Some playlist"))

        assertTrue("the playlist is on top", stack.top is Screen.YtPlaylist)

        stack.pop()

        assertTrue("the channel is on top again", stack.top is Screen.Channel)
        assertEquals(
            "the channel is still on the Playlists tab",
            CHANNEL_TAB_PLAYLISTS,
            channel.selectedTab.value,
        )
    }

    @Test
    fun aFreshChannelStartsOnTheVideosTab() {
        val channel = Screen.Channel("UC123", "Some channel", null)
        assertEquals(CHANNEL_TAB_VIDEOS, channel.selectedTab.value)
    }

    @Test
    fun theBackStackKeepsTheChannelObject() {
        val stack = NavStack()
        val channel = Screen.Channel("UC123", "Some channel", null)
        stack.open(channel)
        stack.open(Screen.YtPlaylist("PL123", "Some playlist"))

        stack.pop()

        assertTrue("the same channel screen comes back", stack.top === channel)
    }
}
