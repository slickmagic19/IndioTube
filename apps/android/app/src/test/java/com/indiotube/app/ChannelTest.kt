package com.indiotube.app

import com.indiotube.app.data.InnerTube
import com.indiotube.app.data.SyncJson
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The About tab reads a lazily loaded engagement panel. These tests pin the
// parser against representative InnerTube JSON without touching the network;
// LiveNetworkTest covers the real wire contract.
class ChannelTest {

    private val innerTube = InnerTube(
        OkHttpClient.Builder().connectTimeout(1, TimeUnit.SECONDS).build(),
    )

    @Test
    fun aboutParsesDescriptionStatsAndLinks() {
        val root = SyncJson.parseToJsonElement(
            """
            {"onResponseReceivedEndpoints":[{"appendContinuationItemsAction":{"continuationItems":[
              {"aboutChannelRenderer":{"metadata":{"aboutChannelViewModel":{
                "description":"Line one\nLine two",
                "country":"Canada",
                "subscriberCountText":"16.9M subscribers",
                "videoCountText":"7,961 videos",
                "viewCountText":"9,924,940,781 views",
                "joinedDateText":{"content":"Joined Nov 24, 2008"},
                "canonicalChannelUrl":"http://www.youtube.com/@LinusTechTips",
                "links":[
                  {"channelExternalLinkViewModel":{
                    "title":{"content":"Store"},
                    "link":{"content":"store.example","commandRuns":[{"onTap":{"innertubeCommand":{
                      "urlEndpoint":{"url":"https://store.example"}
                    }}}]}
                  }},
                  {"channelExternalLinkViewModel":{
                    "title":{"content":"Broken link"},
                    "link":{"content":"no url"}
                  }}
                ]
              }}}}
            ]}}]}
            """.trimIndent(),
        )

        val about = innerTube.parseChannelAbout(root)
        assertEquals("description keeps its lines", "Line one\nLine two", about?.description)
        assertEquals("Canada", about?.country)
        assertEquals("16.9M subscribers", about?.subscribersText)
        assertEquals("7,961 videos", about?.videoCountText)
        assertEquals("9,924,940,781 views", about?.viewCountText)
        assertEquals("Joined Nov 24, 2008", about?.joinedText)
        assertEquals("http URLs are upgraded", "https://www.youtube.com/@LinusTechTips", about?.canonicalUrl)
        assertEquals("links without a URL are dropped", 1, about?.links?.size)
        assertEquals("Store", about?.links?.first()?.title)
        assertEquals("https://store.example", about?.links?.first()?.url)
    }

    @Test
    fun playlistLockupsUseContentImageAndBadgeCount() {
        val root = SyncJson.parseToJsonElement(
            """{"contents":[{"gridRenderer":{"items":[{"lockupViewModel":{"contentId":"PLtest123","contentType":"LOCKUP_CONTENT_TYPE_PLAYLIST","contentImage":{"collectionThumbnailViewModel":{"primaryThumbnail":{"thumbnailViewModel":{"image":{"sources":[{"url":"https://img.example/playlist.jpg"}]},"overlays":[{"thumbnailOverlayBadgeViewModel":{"thumbnailBadges":[{"thumbnailBadgeViewModel":{"text":"1 video"}}]}}]}}}},"metadata":{"lockupMetadataViewModel":{"title":{"content":"Documentaries"},"metadata":{"contentMetadataViewModel":{"metadataRows":[{"metadataParts":[{"text":{"content":"Playlist"}}]}]}}}}}},{"lockupViewModel":{"contentId":"video123","contentType":"LOCKUP_CONTENT_TYPE_VIDEO","metadata":{"lockupMetadataViewModel":{"title":{"content":"Not a playlist"}}}}},{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"more-playlists"}}}}]}}]}""",
        )

        val page = innerTube.parseChannelPlaylistsPage(root)
        assertEquals("video lockups are not playlists", 1, page.playlists.size)
        assertEquals("Documentaries", page.playlists.first().title)
        assertEquals("PLtest123", page.playlists.first().playlistId)
        assertEquals("https://img.example/playlist.jpg", page.playlists.first().thumbnail)
        assertEquals("1 video", page.playlists.first().videoCountText)
        assertEquals("more-playlists", page.continuation)
    }

    @Test
    fun aboutWithoutPanelParsesToNull() {
        assertNull(innerTube.parseChannelAbout(SyncJson.parseToJsonElement("{}")))
    }

    @Test
    fun aboutTokenComesFromTheChannelPanelNotFeaturedChannels() {
        val root = SyncJson.parseToJsonElement(
            """
            {"contents":[
              {"engagementPanelSectionListRenderer":{
                "header":{"engagementPanelTitleHeaderRenderer":{"title":{"simpleText":"Featured Channels"}}},
                "content":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[
                  {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"featured-token"}}}}
                ]}}]}}
              }},
              {"engagementPanelSectionListRenderer":{
                "header":{"engagementPanelTitleHeaderRenderer":{"title":{"simpleText":"Test Channel"}}},
                "content":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[
                  {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"about-token"}}}}
                ]}}]}}
              }}
            ]}
            """.trimIndent(),
        )

        assertEquals("about-token", innerTube.parseAboutToken(root, "Test Channel"))
        assertEquals(
            "the fallback still skips the featured-channels panel",
            "about-token",
            innerTube.parseAboutToken(root, "Some Other Name"),
        )
        assertTrue(innerTube.parseAboutToken(SyncJson.parseToJsonElement("{}"), "Any") == null)
    }
}
