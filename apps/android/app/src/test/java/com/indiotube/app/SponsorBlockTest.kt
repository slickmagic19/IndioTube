package com.indiotube.app

import com.indiotube.app.data.SponsorBlock
import com.indiotube.app.data.SponsorSegment
import com.indiotube.app.data.autoSkipTarget
import com.indiotube.app.data.sponsorLabel
import com.indiotube.app.data.sponsorSegmentAt
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SponsorBlockTest {

    private val sponsorBlock = SponsorBlock(OkHttpClient())

    // Shape of the live /api/skipSegments?videoID=... answer: a flat array of
    // segment objects, times in seconds - not the {segments:[...]} wrapper the
    // hash-prefix endpoint uses.
    private val liveShape = """
        [
          {"category":"sponsor","actionType":"skip","segment":[99.182,130.508],
           "UUID":"12b77abca9496ebc42a693b8d091cd60d5396b948e2b89f55258cf145b2df2fc7",
           "videoDuration":720.981,"locked":0,"votes":11,"description":""},
          {"category":"sponsor","actionType":"skip","segment":[621.801,707.947],
           "UUID":"ba8c53a123d4b17381578923e4bb215b9f5fc5bd6f7de84a8a8e48ed8df8cde57",
           "videoDuration":720.981,"locked":0,"votes":9,"description":""}
        ]
    """.trimIndent()

    @Test
    fun liveResponseShapeParses() {
        val segments = sponsorBlock.parseSponsorSegments(liveShape)
        assertEquals(2, segments.size)
        assertEquals(99182L, segments[0].startMs)
        assertEquals(130508L, segments[0].endMs)
        assertEquals("sponsor", segments[0].category)
        assertEquals(621801L, segments[1].startMs)
    }

    @Test
    fun degenerateSegmentsAreDropped() {
        val segments = sponsorBlock.parseSponsorSegments(
            """
            [
              {"category":"intro","segment":[10.0,20.0],"UUID":"ok"},
              {"category":"intro","segment":[30.0],"UUID":"short"},
              {"category":"intro","segment":[40.0,40.0],"UUID":"empty"},
              {"category":"intro","segment":[60.0,50.0],"UUID":"reversed"}
            ]
            """.trimIndent(),
        )
        assertEquals(listOf("ok"), segments.map { it.UUID })
    }

    @Test
    fun segmentsAreSortedByStart() {
        val segments = sponsorBlock.parseSponsorSegments(
            """
            [
              {"category":"outro","segment":[90.0,100.0],"UUID":"late"},
              {"category":"intro","segment":[5.0,12.0],"UUID":"early"}
            ]
            """.trimIndent(),
        )
        assertEquals(listOf("early", "late"), segments.map { it.UUID })
    }

    @Test
    fun onlyTheSegmentUnderThePlayheadIsActive() {
        val intro = SponsorSegment("intro", listOf(10.0, 20.0), "intro")
        val sponsor = SponsorSegment("sponsor", listOf(60.0, 90.0), "sponsor")
        val segments = listOf(intro, sponsor)

        assertNull(sponsorSegmentAt(segments, 9_999L))
        assertSame(intro, sponsorSegmentAt(segments, 10_000L))
        assertSame(intro, sponsorSegmentAt(segments, 19_999L))
        assertNull(sponsorSegmentAt(segments, 20_000L))
        assertSame(sponsor, sponsorSegmentAt(segments, 75_000L))
        assertNull(sponsorSegmentAt(segments, 90_000L))
    }

    @Test
    fun dismissedSegmentsAreNotSuggestedAgain() {
        val intro = SponsorSegment("intro", listOf(10.0, 20.0), "intro")
        val sponsor = SponsorSegment("sponsor", listOf(10.0, 20.0), "sponsor")
        val segments = listOf(intro, sponsor)

        assertSame(intro, sponsorSegmentAt(segments, 15_000L, setOf("sponsor")))
        assertNull(sponsorSegmentAt(segments, 15_000L, setOf("intro", "sponsor")))
    }

    @Test
    fun emptyBodyParsesToNothing() {
        assertTrue(sponsorBlock.parseSponsorSegments("[]").isEmpty())
    }

    @Test
    fun autoSkipIsOptInAndNeverFiresTwice() {
        val intro = SponsorSegment("intro", listOf(10.0, 20.0), "intro")
        val sponsor = SponsorSegment("sponsor", listOf(60.0, 70.0), "sponsor")
        val segments = listOf(intro, sponsor)

        assertNull("off by default", autoSkipTarget(segments, 15_000L, emptySet(), autoSkip = false))
        assertSame(intro, autoSkipTarget(segments, 15_000L, emptySet(), autoSkip = true))
        assertNull("already skipped", autoSkipTarget(segments, 15_000L, setOf("intro"), autoSkip = true))
        assertNull("outside any segment", autoSkipTarget(segments, 25_000L, emptySet(), autoSkip = true))
        assertSame(sponsor, autoSkipTarget(segments, 65_000L, setOf("intro"), autoSkip = true))
    }

    @Test
    fun categoryLabelsMatchTheSettingsCopy() {
        assertEquals("Sponsor", sponsorLabel("sponsor"))
        assertEquals("Non-music section", sponsorLabel("music_offtopic"))
        assertEquals("Custom", sponsorLabel("custom"))
    }
}
