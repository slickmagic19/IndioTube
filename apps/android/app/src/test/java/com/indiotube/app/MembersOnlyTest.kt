package com.indiotube.app

import com.indiotube.app.data.InnerTube
import com.indiotube.app.data.SavedDownload
import com.indiotube.app.data.SyncJson
import com.indiotube.app.data.VideoItem
import com.indiotube.app.data.recommendableVideos
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Members-only videos used to be dropped before reaching the UI, then failed
// playback for the paths that still let them through. These tests pin the
// parse and the persisted-shape defaults that keep them tagged, not vanished.
class MembersOnlyTest {

    private val innerTube = InnerTube(OkHttpClient())

    private fun watchJson(reason: String?, related: String = "[]"): String {
        val playability = if (reason == null) "" else
            "\"playabilityStatus\": {\"status\": \"UNPLAYABLE\", \"reason\": \"$reason\"},"
        return """
            {
              $playability
              "contents": {
                "twoColumnWatchNextResults": {
                  "secondaryResults": {
                    "secondaryResults": {
                      "results": $related
                    }
                  }
                }
              }
            }
        """.trimIndent()
    }

    @Test
    fun unplayableMembersReasonTagsTheWatchPage() {
        val root = Json.parseToJsonElement(
            watchJson("This video is available to this channel's members only."),
        )
        val watch = innerTube.parseWatch(root, "member1")
        assertTrue(watch.membersOnly)
    }

    @Test
    fun aMembersVideoInTheRelatedRailDoesNotFlagThePlayingVideo() {
        val related = """
            [{
              "compactVideoRenderer": {
                "videoId": "related-member",
                "title": {"runs": [{"text": "Members only stream"}]},
                "badges": [
                  {"metadataBadgeRenderer": {
                    "label": "Members only",
                    "style": "BADGE_STYLE_TYPE_MEMBERS_ONLY"
                  }}
                ]
              }
            }]
        """.trimIndent()
        val watch = innerTube.parseWatch(
            Json.parseToJsonElement(watchJson(null, related)),
            "public1",
        )

        assertFalse(watch.membersOnly)
        assertEquals(1, watch.related.size)
        assertEquals("related-member", watch.related.single().videoId)
        assertTrue("the members badge must survive parsing", watch.related.single().membersOnly)
    }

    @Test
    fun lockupMetadataRowMembersBadgeIsRecognised() {
        val related = """
            [{
              "lockupViewModel": {
                "contentType": "LOCKUP_CONTENT_TYPE_VIDEO",
                "contentId": "lockup-member",
                "metadata": {
                  "lockupMetadataViewModel": {
                    "title": {"content": "Members only lockup"},
                    "metadata": {
                      "contentMetadataViewModel": {
                        "metadataRows": [
                          {"metadataParts": [{"text": {"content": "Members only"}}]}
                        ]
                      }
                    }
                  }
                }
              }
            }]
        """.trimIndent()
        val watch = innerTube.parseWatch(
            Json.parseToJsonElement(watchJson(null, related)),
            "public2",
        )

        assertFalse(watch.membersOnly)
        assertEquals("lockup-member", watch.related.single().videoId)
        assertTrue(watch.related.single().membersOnly)
        assertEquals("Members only lockup", watch.related.single().title)
        assertNull("the badge must not become the channel name", watch.related.single().channelName)
    }

    @Test
    fun recommendationFeedsDropMembersOnlyVideos() {
        val videos = listOf(
            VideoItem("public-1"),
            VideoItem("member-1", membersOnly = true),
            VideoItem("public-2"),
        )

        assertEquals(listOf("public-1", "public-2"), videos.recommendableVideos().map { it.videoId })
    }

    // A feed that is only members uploads has to come back empty, so Home can
    // show its empty state instead of a wall of cards that cannot play.
    @Test
    fun aFeedOfOnlyMembersUploadsRecommendsNothing() {
        val videos = listOf(
            VideoItem("member-1", membersOnly = true),
            VideoItem("member-2", membersOnly = true),
        )

        assertTrue(videos.recommendableVideos().isEmpty())
    }

    @Test
    fun oldPersistedVideosAndDownloadsStillDecode() {
        val video = SyncJson.decodeFromString<VideoItem>("""{"videoId":"old1"}""")
        assertFalse(video.membersOnly)

        val saved = SyncJson.decodeFromString<SavedDownload>(
            """{"video":{"videoId":"old2"},"bytes":10,"at":5}""",
        )
        assertNull(saved.height)
        assertFalse(saved.video.membersOnly)
    }
}
