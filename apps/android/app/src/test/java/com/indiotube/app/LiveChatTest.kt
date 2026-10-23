package com.indiotube.app

import com.indiotube.app.data.InnerTube
import com.indiotube.app.data.isAtLiveEdge
import com.indiotube.app.data.liveChatBadgeLabel
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The watch page's live chat and its replay, parsed from the shapes the
// InnerTube live_chat endpoint actually returns. Polling is network-driven but
// the parse contract is what breaks when YouTube reshuffles renderers, so it
// gets pinned here.
class LiveChatTest {

    private val innerTube = InnerTube(OkHttpClient())

    private fun json(text: String) = Json.parseToJsonElement(text.trimIndent())

    private val livePage = """
        {
          "continuationContents": {
            "liveChatContinuation": {
              "actions": [
                {"addChatItemAction": {"item": {"liveChatTextMessageRenderer": {
                  "id": "abc",
                  "authorName": {"simpleText": "Alice"},
                  "message": {"runs": [{"text": "hello"}, {"text": " world"}]},
                  "authorPhoto": {"thumbnails": [
                    {"url": "https://yt3.ggpht.com/a1", "width": 48},
                    {"url": "https://yt3.ggpht.com/a2", "width": 88}
                  ]},
                  "timestampText": {"simpleText": "10:01"},
                  "authorBadges": [
                    {"liveChatAuthorBadgeRenderer": {"icon": {"iconType": "OWNER"}, "tooltip": "Owner"}}
                  ]
                }}}},
                {"addChatItemAction": {"item": {"liveChatPaidMessageRenderer": {
                  "id": "paid",
                  "authorName": {"simpleText": "Bob"},
                  "message": {"runs": [{"text": "great stream"}]},
                  "purchaseAmountText": {"simpleText": "${'$'}5.00"}
                }}}}
              ],
              "continuations": [
                {"invalidationContinuationData": {"continuation": "next-token"}}
              ]
            }
          }
        }
    """

    private val replayPage = """
        {
          "continuationContents": {
            "liveChatContinuation": {
              "actions": [
                {"replayChatItemAction": {"actions": [
                  {"addChatItemAction": {"item": {"liveChatTextMessageRenderer": {
                    "id": "r1",
                    "authorName": {"simpleText": "Carol"},
                    "message": {"runs": [{"text": "replay message"}]}
                  }}}}
                ]}}
              ],
              "continuations": []
            }
          }
        }
    """

    @Test
    fun livePageParsesMessagesAndNextContinuation() {
        val page = innerTube.parseLiveChat(json(livePage))
        assertEquals(listOf("abc", "paid"), page.messages.map { it.id })
        assertEquals("Alice", page.messages[0].author)
        assertEquals("hello world", page.messages[0].text)
        assertEquals("Owner", page.messages[0].badge)
        assertEquals("next-token", page.continuation)
        assertFalse(page.replay)
    }

    @Test
    fun paidMessagesKeepTheirAmount() {
        val page = innerTube.parseLiveChat(json(livePage))
        assertEquals("$5.00", page.messages[1].amountText)
        assertNull(page.messages[1].badge)
    }

    @Test
    fun replayActionsUnwrapIntoMessages() {
        val page = innerTube.parseLiveChat(json(replayPage), replay = true)
        assertEquals(1, page.messages.size)
        assertEquals("Carol", page.messages[0].author)
        assertEquals("replay message", page.messages[0].text)
        assertNull(page.continuation)
        assertTrue(page.replay)
    }

    @Test
    fun engagementMessagesAreSkippedWithoutBreakingThePage() {
        val page = innerTube.parseLiveChat(
            json(
                """
                {
                  "continuationContents": {
                    "liveChatContinuation": {
                      "actions": [
                        {"addChatItemAction": {"item": {"liveChatViewerEngagementMessageRenderer": {
                          "id": "eng",
                          "message": {"runs": [{"text": "Welcome to chat"}]}
                        }}}},
                        {"addChatItemAction": {"item": {"liveChatTextMessageRenderer": {
                          "id": "ok",
                          "authorName": {"simpleText": "Dan"},
                          "message": {"runs": [{"text": "hi"}]}
                        }}}}
                      ]
                    }
                  }
                }
                """,
            ),
        )
        assertEquals(listOf("ok"), page.messages.map { it.id })
    }

    @Test
    fun missingChatPayloadYieldsAnEmptyPage() {
        val page = innerTube.parseLiveChat(json("""{"responseContext":{}}"""))
        assertTrue(page.messages.isEmpty())
        assertNull(page.continuation)
    }

    @Test
    fun badgeLabelsMapToTheSettingsVocabulary() {
        assertEquals("Owner", liveChatBadgeLabel("OWNER"))
        assertEquals("Moderator", liveChatBadgeLabel("MODERATOR"))
        assertNull(liveChatBadgeLabel("VERIFIED"))
        assertNull(liveChatBadgeLabel(null))
    }

    @Test
    fun liveEdgeThresholdMatchesTheDvrWindow() {
        // Within 15s of the window end counts as live.
        assertTrue(isAtLiveEdge(585_000L, 600_000L))
        assertFalse(isAtLiveEdge(500_000L, 600_000L))
        // A stream without a window yet is treated as live.
        assertTrue(isAtLiveEdge(0L, 0L))
    }
}
