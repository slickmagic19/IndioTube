package com.indiotube.app

import com.indiotube.app.data.InnerTube
import com.indiotube.app.data.SyncJson
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// A playlist opened from a channel used to answer "This playlist doesn't
// have any playable videos" for every playlist: YouTube stopped sending
// playlistVideoRenderer items and now builds the list from video lockups.
// The fixture in resources/playlist-page.json is a real response of that
// layout (trimmed of tracking noise), so the parser is pinned to what
// YouTube actually sends.
class PlaylistTest {

    private val innerTube = InnerTube(
        OkHttpClient.Builder().connectTimeout(1, TimeUnit.SECONDS).build(),
    )

    private fun resource(name: String): JsonElement {
        val stream = javaClass.getResourceAsStream(name)
            ?: error("missing test resource $name")
        val text = stream.bufferedReader().use { it.readText() }
        return SyncJson.parseToJsonElement(text)
    }

    @Test
    fun theCurrentPlaylistLayoutIsParsed() {
        val page = innerTube.parsePlaylist(resource("/playlist-page.json"))

        assertEquals("the title comes from the page header", "Data Science", page.title)
        assertEquals("the owner comes from the sidebar", "freeCodeCamp.org", page.author)
        assertEquals("the video count comes from the header", "20 videos", page.videoCountText)
        assertEquals("both videos are parsed", 2, page.videos.size)

        val first = page.videos.first()
        assertEquals("ua-CiDNNj30", first.videoId)
        assertEquals("Learn Data Science Tutorial - Full Course for Beginners", first.title)
        assertEquals("the channel is parsed", "freeCodeCamp.org", first.channelName)
        assertEquals("the channel id is parsed", "UC8butISFwT-Wl7EV0hUK0BQ", first.channelId)
        assertTrue(
            "the avatar is parsed",
            first.channelAvatar?.startsWith("https://yt3.ggpht.com/") == true,
        )
        assertTrue(
            "the thumbnail is parsed",
            first.thumbnail?.startsWith("https://i.ytimg.com/vi/ua-CiDNNj30/hqdefault.jpg") == true,
        )
        assertEquals("the duration badge is parsed", 21_129L, first.durationSec)
        assertEquals("the view count is parsed", "4.5M views", first.viewsText)
        assertEquals("the age is parsed", "7 years ago", first.publishedText)

        val second = page.videos[1]
        assertEquals("xxpc-HPKN28", second.videoId)
        assertEquals(29_704L, second.durationSec)
        assertEquals("3.9M views", second.viewsText)
    }

    @Test
    fun theContinuationTokenIsFound() {
        val page = innerTube.parsePlaylist(resource("/playlist-page.json"))
        assertNotNull("the load-more marker is parsed", page.continuation)
        assertTrue(
            "the token looks like a continuation",
            page.continuation!!.length > 40,
        )
    }

    // A playlist can hold other playlists; those lockups are not videos and
    // must not show up as playable rows.
    @Test
    fun playlistsWithoutAVideoAreNotMistakenForVideos() {
        val lockup = """
            {"lockupViewModel": {
              "contentId": "PLonly",
              "contentType": "LOCKUP_CONTENT_TYPE_PLAYLIST",
              "metadata": {"lockupMetadataViewModel": {"title": {"content": "Just a playlist"}}}
            }}
        """.trimIndent()
        val section = """{"itemSectionRenderer": {"contents": [$lockup]}}"""
        val list = """{"sectionListRenderer": {"contents": [$section]}}"""
        val tab = """{"tabRenderer": {"content": $list}}"""
        val columns = """{"twoColumnBrowseResultsRenderer": {"tabs": [$tab]}}"""
        val root = SyncJson.parseToJsonElement("""{"contents": $columns}""")

        val page = innerTube.parsePlaylist(root)
        assertTrue("a playlist lockup is not a video", page.videos.isEmpty())
    }

    // The old layout is still parsed, so a client that answers with
    // playlistVideoRenderer items keeps working.
    @Test
    fun theLegacyPlaylistLayoutIsStillParsed() {
        val root = SyncJson.parseToJsonElement(
            """{"header":{"playlistHeaderRenderer":{
                "title":{"simpleText":"Watch later"},
                "ownerText":{"runs":[{"text":"Me"}]},
                "numVideosText":{"simpleText":"3"}}},
              "contents":[{"playlistVideoRenderer":{
                "videoId":"abc123",
                "title":{"runs":[{"text":"A video"}]},
                "lengthText":{"simpleText":"2:30"},
                "viewCountText":{"simpleText":"9 views"},
                "ownerText":{"runs":[{"text":"Me","navigationEndpoint":{"browseEndpoint":{"browseId":"UCme"}}}]}}}]}""",
        )
        val page = innerTube.parsePlaylist(root)
        assertEquals("Watch later", page.title)
        assertEquals("Me", page.author)
        assertEquals("3 videos", page.videoCountText)
        assertEquals(1, page.videos.size)
        assertEquals("abc123", page.videos.first().videoId)
        assertEquals(150L, page.videos.first().durationSec)
    }

    @Test
    fun anEmptyPlaylistHasNoVideosAndNoContinuation() {
        val page = innerTube.parsePlaylist(SyncJson.parseToJsonElement("{}"))
        assertTrue(page.videos.isEmpty())
        assertNull(page.continuation)
        assertNotNull(page.title)
    }
}
