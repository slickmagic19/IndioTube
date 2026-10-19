package com.indiotube.app

import com.indiotube.app.data.QualityOption
import com.indiotube.app.data.ResolvedStream
import com.indiotube.app.data.VideoQualities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The quality sheet maps a user pick (or a stale height) onto the ladder the
// extractor actually returned. These tests pin that fallback behaviour.
class QualitySelectionTest {

    private fun stream(height: Int, mime: String = "video/mp4") =
        ResolvedStream("https://example.com/$height", "mp4", mime, height)

    private fun option(height: Int, muxed: Boolean = false, video: Boolean = true) = QualityOption(
        height = height,
        label = "${height}p",
        fps = 30,
        muxed = if (muxed) stream(height) else null,
        videoOnly = if (video) stream(height) else null,
    )

    private val qualities = VideoQualities(
        options = listOf(
            option(1080),
            option(720, muxed = true),
            option(480),
            option(360),
            option(240),
            option(144),
        ),
        auto = stream(720),
        audio = stream(0, "audio/mp4"),
    )

    @Test
    fun exactHeightWins() {
        assertEquals(720, qualities.nearest(720)?.height)
        assertEquals(1080, qualities.nearest(1080)?.height)
    }

    @Test
    fun missingHeightFallsBackDownwards() {
        assertEquals(480, qualities.nearest(600)?.height)
        assertEquals(720, qualities.nearest(900)?.height)
    }

    @Test
    fun belowLowestFallsBackToLowest() {
        assertEquals(144, qualities.nearest(60)?.height)
    }

    @Test
    fun aboveHighestFallsBackToHighest() {
        assertEquals(1080, qualities.nearest(2160)?.height)
    }

    @Test
    fun muxedSelectionOnlyUsesMuxedFiles() {
        assertEquals(720, qualities.muxedAt(720)?.height)
        assertTrue("asking for 1080 still resolves the best muxed", qualities.muxedAt(1080)!!.height == 720)
        assertTrue("asking for 240 still resolves a muxed file", qualities.muxedAt(240)!!.height == 720)
    }

    @Test
    fun videoSelectionUsesVideoOnlyFiles() {
        assertEquals(1080, qualities.videoAt(1080)?.height)
        assertEquals(480, qualities.videoAt(600)?.height)
        assertEquals(144, qualities.videoAt(60)?.height)
    }

    @Test
    fun labelsComeFromTheLadder() {
        assertEquals("1080p", qualities.labelFor(1080))
        assertNull(qualities.labelFor(null))
        assertNull(qualities.labelFor(999))
    }

    @Test
    fun autoIsReportedForTheSheet() {
        assertEquals(720, qualities.bestHeight)
    }

    @Test
    fun emptyLadderIsSafe() {
        val empty = VideoQualities(emptyList(), null, null)
        assertNull(empty.nearest(720))
        assertEquals(0, empty.bestHeight)
    }
}
