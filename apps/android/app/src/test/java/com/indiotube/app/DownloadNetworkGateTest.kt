package com.indiotube.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.indiotube.app.data.Downloads
import com.indiotube.app.data.StreamResolver
import com.indiotube.app.data.VideoItem
import com.indiotube.app.data.downloadBlockReason
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DownloadNetworkGateTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun manager(
        online: Boolean = true,
        unmetered: Boolean = true,
        wifiOnly: Boolean = false,
    ) = Downloads(
        context,
        StreamResolver(OkHttpClient()),
        OkHttpClient(),
        online = { online },
        unmetered = { unmetered },
        wifiOnly = { wifiOnly },
    )

    @Test
    fun offlineIsReportedFirst() {
        assertEquals("You're offline", downloadBlockReason(false, true, false))
    }

    @Test
    fun wifiOnlyRejectsMeteredConnections() {
        assertEquals(
            "Connect to Wi-Fi or turn off Wi-Fi only",
            downloadBlockReason(true, true, false),
        )
        assertNull(downloadBlockReason(true, true, true))
    }

    @Test
    fun meteredConnectionsAreAllowedWhenWifiOnlyIsOff() {
        assertNull(downloadBlockReason(true, false, false))
    }

    @Test
    fun wifiOnlyManagerDoesNotStartOnMeteredConnection() {
        val downloads = manager(unmetered = false, wifiOnly = true)
        downloads.start(VideoItem("wifi-gated"), 720)
        assertTrue(downloads.tasks.value.isEmpty())
        assertTrue(downloads.items.value.isEmpty())
    }

    @Test
    fun offlineManagerDoesNotStart() {
        val downloads = manager(online = false)
        downloads.start(VideoItem("offline-gated"), 720)
        assertTrue(downloads.tasks.value.isEmpty())
        assertTrue(downloads.items.value.isEmpty())
    }
}
