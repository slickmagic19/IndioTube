package com.indiotube.app

import com.indiotube.app.data.HomeConnection
import com.indiotube.app.data.homeConnection
import com.indiotube.app.data.shouldRefreshFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Offline mode's two rules: a feed is never refreshed without a validated
// network, and Home shows the cached list (or points at Downloads) instead of
// pretending the account is empty.
class OfflinePolicyTest {

    @Test
    fun onlineAlwaysWins() {
        assertEquals(HomeConnection.ONLINE, homeConnection(online = true, hasContent = true))
        assertEquals(HomeConnection.ONLINE, homeConnection(online = true, hasContent = false))
    }

    @Test
    fun offlineKeepsContentWhenThereIsAny() {
        assertEquals(
            HomeConnection.OFFLINE_WITH_CONTENT,
            homeConnection(online = false, hasContent = true),
        )
    }

    @Test
    fun offlineWithNothingPointsAtDownloads() {
        assertEquals(
            HomeConnection.OFFLINE_EMPTY,
            homeConnection(online = false, hasContent = false),
        )
    }

    @Test
    fun feedRefreshesAreOnlineOnly() {
        assertTrue(shouldRefreshFeed(true))
        assertFalse(shouldRefreshFeed(false))
    }
}
