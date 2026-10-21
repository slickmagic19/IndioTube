package com.indiotube.app

import com.indiotube.app.data.ChannelItem
import com.indiotube.app.data.InnerTube
import com.indiotube.app.data.PlaylistItem
import com.indiotube.app.data.SearchEntry
import com.indiotube.app.data.SearchFilter
import com.indiotube.app.data.VideoItem
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// YouTube answers anonymous searches for some channel names ("quickybaby")
// with an age-gate promo ("Confirm your age") and zero renderers, so the
// app used to show "No results". Recovery resolves the channel through the
// completion endpoint's entity metadata and returns it with its videos.
// These tests pin the parsing and the entry shaping without the network.
class SearchRecoveryTest {

    private val innerTube = InnerTube(OkHttpClient())

    // Shape of a real completion answer: each entry is
    // ["text", 0, [relevance...], {"zae":..., "zav": channelId, ...}].
    private val quickyBabyJsonp = """
        window.google.ac.h(["quickybaby",[["quickybaby",0,[512,433,790,47],{"zae":"/g/11c6v63j6k","zaf":"@QuickyBabyTV","zai":"https://yt3.googleusercontent.com/ytc/abc=s88-c-k-c0x00ffffff-no-rj","zao":"QuickyBaby","zav":"UCQWHq9e1hN268yjgYq-0hNw"}],["quicky baby",0,[512,433]],["quickybaby wot",0,[512,433,790]]],{"k":1}])
    """.trimIndent()

    @Test
    fun completionEntityForExactQueryIsParsed() {
        val data = innerTube.parseSuggestionData(quickyBabyJsonp)
        assertEquals(listOf("quickybaby", "quicky baby", "quickybaby wot"), data.suggestions)
        val channel = data.channels["quickybaby"]
        assertNotNull("the exact suggestion should carry a channel entity", channel)
        assertEquals("UCQWHq9e1hN268yjgYq-0hNw", channel!!.channelId)
        assertEquals("QuickyBaby", channel.name)
        assertEquals("@QuickyBabyTV", channel.handle)
        assertTrue("avatars must be absolute https", channel.avatar!!.startsWith("https://"))
    }

    @Test
    fun plainCompletionsCarryNoChannelEntity() {
        val data = innerTube.parseSuggestionData(
            """window.google.ac.h(["lofi",[["lofi hip hop",0,[512]],["lofi girl",0,[512]]],{"k":1}])""",
        )
        assertEquals(listOf("lofi hip hop", "lofi girl"), data.suggestions)
        assertTrue("text-only completions must not invent channels", data.channels.isEmpty())
    }

    @Test
    fun ageGatePromoIsMarkedRestricted() {
        val root = Json.parseToJsonElement(
            """
            {
              "contents": {
                "twoColumnSearchResultsRenderer": {
                  "primaryContents": {
                    "sectionListRenderer": {
                      "contents": [
                        {"itemSectionRenderer": {"contents": [
                          {"backgroundPromoRenderer": {"title": {"runs": [{"text": "Confirm your age"}]}}}
                        ]}}
                      ]
                    }
                  }
                }
              }
            }
            """.trimIndent(),
        )
        val results = innerTube.parseSearch(root)
        assertTrue("no entries expected", results.entries.isEmpty())
        assertTrue("the promo must be recognized as an age gate", results.restricted)
        assertNull(results.continuation)
    }

    @Test
    fun normalResultsAreNotMarkedRestricted() {
        val root = Json.parseToJsonElement(
            """
            {
              "contents": {
                "twoColumnSearchResultsRenderer": {
                  "primaryContents": {
                    "sectionListRenderer": {
                      "contents": [
                        {"itemSectionRenderer": {"contents": [
                          {"videoRenderer": {
                            "videoId": "abc123",
                            "title": {"runs": [{"text": "A real result"}]}
                          }}
                        ]}}
                      ]
                    }
                  }
                }
              }
            }
            """.trimIndent(),
        )
        val results = innerTube.parseSearch(root)
        assertEquals(1, results.entries.size)
        assertTrue(results.entries.first() is SearchEntry.Video)
        assertTrue("real results are never marked restricted", !results.restricted)
    }

    private val channel = ChannelItem(
        channelId = "UCQWHq9e1hN268yjgYq-0hNw",
        name = "QuickyBaby",
        avatar = "https://yt3.googleusercontent.com/ytc/avatar",
    )

    private val videos = listOf(
        VideoItem(videoId = "v1", title = "World of Tanks", channelName = "QuickyBaby"),
        VideoItem(videoId = "v2", title = "Members stream", channelName = "QuickyBaby", membersOnly = true),
    )

    private val playlists = listOf(
        PlaylistItem(playlistId = "pl1", title = "Best of QuickyBaby", channelName = "QuickyBaby"),
    )

    @Test
    fun unfilteredFallbackShowsChannelAndVideos() {
        val entries = innerTube.fallbackSearchEntries(channel, videos, playlists, null)
        val channelEntry = entries.filterIsInstance<SearchEntry.Channel>().single()
        assertEquals("QuickyBaby", channelEntry.channel.name)
        val mixed = entries.filterIsInstance<SearchEntry.Video>().map { it.video }
        assertEquals(listOf("v1", "v2"), mixed.map { it.videoId })
        // Explicit channel results stay explicit: members-only videos remain
        // visible (with their badge) instead of being filtered away.
        assertTrue(mixed.any { it.membersOnly })
    }

    @Test
    fun fallbackHonorsTheSelectedFilter() {
        val channelsOnly = innerTube.fallbackSearchEntries(channel, videos, playlists, SearchFilter.CHANNELS)
        assertTrue(channelsOnly.all { it is SearchEntry.Channel })

        val videosOnly = innerTube.fallbackSearchEntries(channel, videos, playlists, SearchFilter.VIDEOS)
        assertTrue(videosOnly.all { it is SearchEntry.Video })

        val playlistsOnly = innerTube.fallbackSearchEntries(channel, videos, playlists, SearchFilter.PLAYLISTS)
        assertTrue(playlistsOnly.all { it is SearchEntry.Playlist })
    }

    @Test
    fun emptyFilteredFallbackDegradesToTheChannel() {
        val videosEmpty = innerTube.fallbackSearchEntries(channel, emptyList(), playlists, SearchFilter.VIDEOS)
        assertEquals(1, videosEmpty.size)
        assertTrue(videosEmpty.first() is SearchEntry.Channel)

        val playlistsEmpty = innerTube.fallbackSearchEntries(channel, videos, emptyList(), SearchFilter.PLAYLISTS)
        assertEquals(1, playlistsEmpty.size)
        assertTrue(playlistsEmpty.first() is SearchEntry.Channel)
    }
}
