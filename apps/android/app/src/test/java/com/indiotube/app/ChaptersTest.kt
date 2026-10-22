package com.indiotube.app

import com.indiotube.app.data.Chapter
import com.indiotube.app.data.InnerTube
import com.indiotube.app.data.chapterAt
import com.indiotube.app.data.chapterTimestamp
import com.indiotube.app.data.stripChapterLines
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Chapters come from the player-bar markers of the watch response, and the
// same list drives the seek-bar ticks, the chapter rail above the title and
// the description cleanup. These tests pin the parsing and display helpers.
class ChaptersTest {

    private val tube = InnerTube(OkHttpClient())
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parsesDescriptionChaptersFromPlayerMarkers() {
        val root = json.parseToJsonElement(
            """
            {
              "playerOverlays": {
                "playerOverlayRenderer": {
                  "decoratedPlayerBarRenderer": {
                    "decoratedPlayerBarRenderer": {
                      "playerBar": {
                        "multiMarkersPlayerBarRenderer": {
                          "markersMap": [
                            {
                              "key": "HEATMAP",
                              "value": {"heatmap": []}
                            },
                            {
                              "key": "DESCRIPTION_CHAPTERS",
                              "value": {
                                "chapters": [
                                  {
                                    "chapterRenderer": {
                                      "title": {"simpleText": "Intro"},
                                      "timeRangeStartMillis": 0
                                    }
                                  },
                                  {
                                    "chapterRenderer": {
                                      "title": {"runs": [{"text": "How does "}, {"text": "compression work?"}]},
                                      "timeRangeStartMillis": 139000
                                    }
                                  }
                                ]
                              }
                            }
                          ]
                        }
                      }
                    }
                  }
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals(
            listOf(Chapter("Intro", 0), Chapter("How does compression work?", 139000)),
            tube.parseChapters(root),
        )
    }

    @Test
    fun fallsBackToFirstChapterMarkerList() {
        val root = json.parseToJsonElement(
            """
            {"multiMarkersPlayerBarRenderer":{"markersMap":[
              {"key":"AUTO_CHAPTERS","value":{"chapters":[
                {"chapterRenderer":{"title":{"simpleText":"Later"},"timeRangeStartMillis":45000}}
              ]}}
            ]}}
            """.trimIndent(),
        )

        assertEquals(listOf(Chapter("Later", 45000)), tube.parseChapters(root))
    }

    @Test
    fun skipsAndSortsBrokenChapterEntries() {
        val root = json.parseToJsonElement(
            """
            {"multiMarkersPlayerBarRenderer":{"markersMap":[
              {"key":"DESCRIPTION_CHAPTERS","value":{"chapters":[
                {"chapterRenderer":{"title":{"simpleText":"Second"},"timeRangeStartMillis":60000}},
                {"chapterRenderer":{"title":{"simpleText":"No start"}}},
                {"chapterRenderer":{"title":{"simpleText":"  "},"timeRangeStartMillis":10000}},
                {"chapterRenderer":{"title":{"simpleText":"First"},"timeRangeStartMillis":0}},
                {"chapterRenderer":{"title":{"simpleText":"Second copy"},"timeRangeStartMillis":60000}}
              ]}}
            ]}}
            """.trimIndent(),
        )

        assertEquals(
            listOf(Chapter("First", 0), Chapter("Second", 60000)),
            tube.parseChapters(root),
        )
    }

    @Test
    fun noMarkersMeansNoChapters() {
        val root = json.parseToJsonElement("""{"contents":{}}""")
        assertEquals(emptyList<Chapter>(), tube.parseChapters(root))
    }

    @Test
    fun chapterAtUsesTheLatestPassedMarker() {
        val chapters = listOf(Chapter("Intro", 0), Chapter("Middle", 139000), Chapter("End", 621000))

        assertNull(chapterAt(chapters, -1))
        assertEquals("Intro", chapterAt(chapters, 0)?.title)
        assertEquals("Intro", chapterAt(chapters, 138999)?.title)
        assertEquals("Middle", chapterAt(chapters, 139000)?.title)
        assertEquals("End", chapterAt(chapters, 10_000_000)?.title)
        assertNull(chapterAt(emptyList(), 5000))
    }

    @Test
    fun lateFirstChapterHasNoActiveChapterBeforeIt() {
        val chapters = listOf(Chapter("Cold open", 15000), Chapter("Topic", 90000))
        assertNull(chapterAt(chapters, 14000))
        assertEquals("Cold open", chapterAt(chapters, 15000)?.title)
    }

    @Test
    fun timestampsRenderYouTubeStyle() {
        assertEquals("0:00", chapterTimestamp(0))
        assertEquals("0:00", chapterTimestamp(-5000))
        assertEquals("0:09", chapterTimestamp(9000))
        assertEquals("2:19", chapterTimestamp(139000))
        assertEquals("1:02:03", chapterTimestamp(3_723_000))
    }

    @Test
    fun stripChapterLinesRemovesOnlyMatchingDescriptionLines() {
        val chapters = listOf(Chapter("Intro", 0), Chapter("How does compression work?", 139000))
        val description = """
            Intro
            0:00 Intro
            2:19 How does compression work?
            2:19 - details
            2:19ish is not a timestamp
            Filmed in 4K.
        """.trimIndent()

        assertEquals(
            listOf("Intro", "2:19ish is not a timestamp", "Filmed in 4K.").joinToString("\n"),
            stripChapterLines(description, chapters),
        )
    }

    @Test
    fun stripChapterLinesKeepsDescriptionWithoutChapters() {
        val description = "0:00 Intro\nEverything else"
        assertEquals(description, stripChapterLines(description, emptyList()))
    }
}
