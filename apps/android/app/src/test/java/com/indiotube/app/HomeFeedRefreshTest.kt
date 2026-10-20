package com.indiotube.app

import com.indiotube.app.data.VideoItem
import com.indiotube.app.ui.retainPreviousHomeFeed
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Tapping the Home tab forces a refresh with no params. If that refresh has
// nothing to show, the feed already on screen must survive instead of being
// replaced by the "Try searching to get started" empty state.
class HomeFeedRefreshTest {

    private val video = VideoItem("v1")

    @Test
    fun emptyRefreshKeepsPreviousYouTubeFeed() {
        assertTrue(retainPreviousHomeFeed(null, listOf(video), emptyList()))
    }

    @Test
    fun emptyRefreshKeepsPreviousLocalFeed() {
        assertTrue(retainPreviousHomeFeed(null, emptyList(), listOf(video)))
    }

    @Test
    fun categorySwitchMayReplaceTheFeed() {
        assertFalse(retainPreviousHomeFeed("category-token", listOf(video), listOf(video)))
    }

    @Test
    fun firstLoadWithNothingToShowStillShowsThePrompt() {
        assertFalse(retainPreviousHomeFeed(null, emptyList(), emptyList()))
    }
}
