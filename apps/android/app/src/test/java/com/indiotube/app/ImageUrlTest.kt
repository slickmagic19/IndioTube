package com.indiotube.app

import com.indiotube.app.data.VideoItem
import com.indiotube.app.data.normalizeImageUrl
import com.indiotube.app.data.recommendableVideos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// YouTube returns channel avatars as protocol-relative URLs in many renderers
// ("//yt3.ggpht.com/..."), which Coil will not load. These tests pin the
// normalization that both the parser and the UI rely on.
class ImageUrlTest {

    @Test
    fun protocolRelativeAvatarBecomesHttps() {
        assertEquals(
            "https://yt3.ggpht.com/abc=s176-c-k-c0x00ffffff-no-rj",
            normalizeImageUrl("//yt3.ggpht.com/abc=s176-c-k-c0x00ffffff-no-rj"),
        )
    }

    @Test
    fun existingHttpsIsUntouched() {
        assertEquals("https://i.ytimg.com/vi/x/hq.jpg", normalizeImageUrl("https://i.ytimg.com/vi/x/hq.jpg"))
    }

    @Test
    fun insecureHttpIsUpgraded() {
        assertEquals("https://i.ytimg.com/vi/x/hq.jpg", normalizeImageUrl("http://i.ytimg.com/vi/x/hq.jpg"))
    }

    @Test
    fun blankAndNullBecomeNull() {
        assertNull(normalizeImageUrl(null))
        assertNull(normalizeImageUrl(""))
        assertNull(normalizeImageUrl("   "))
    }

    @Test
    fun recommendableVideosDropMembersOnly() {
        fun v(id: String, members: Boolean = false) = VideoItem(
            videoId = id,
            title = "Video $id",
            membersOnly = members,
        )
        val filtered = listOf(v("a"), v("b", members = true), v("c")).recommendableVideos()
        assertTrue(filtered.map { it.videoId } == listOf("a", "c"))
    }
}
