package com.indiotube.app

import com.indiotube.app.data.QualityOption
import com.indiotube.app.data.QualityUris
import com.indiotube.app.data.ResolvedStream
import com.indiotube.app.data.VideoQualities
import com.indiotube.app.data.downloadSources
import com.indiotube.app.data.playbackWarmStreams
import com.indiotube.app.data.playsAutoStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The quality sheet maps a user pick (or a stale height) onto the ladder the
// extractor actually returned. These tests pin that fallback behaviour.
class QualitySelectionTest {

    private fun stream(height: Int, mime: String = "video/mp4", codec: String? = null) =
        ResolvedStream("https://example.com/$height", "mp4", mime, height, codec)

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

    // A quality that fails on the device must fall back exactly one rung at a
    // time, ending on Auto - never onto a different video.
    @Test
    fun belowStepsDownOneRungAtATime() {
        assertEquals(720, qualities.below(1080)?.height)
        assertEquals(480, qualities.below(720)?.height)
        assertEquals(240, qualities.below(360)?.height)
        assertNull(qualities.below(144))
        assertNull(VideoQualities(emptyList(), null, null).below(1080))
    }

    @Test
    fun autoTargetIsThePlainMuxedStream() {
        assertEquals("indio://stream/abc123", QualityUris.auto("abc123"))
        assertEquals(
            "indio://stream/abc123",
            QualityUris.target("abc123", null, qualities),
        )
    }

    @Test
    fun muxedQualityMapsToASingleFile() {
        assertEquals(
            "indio://stream/abc123?type=muxed&h=720",
            QualityUris.target("abc123", 720, qualities),
        )
    }

    @Test
    fun videoOnlyQualityMapsToAMergedStream() {
        assertEquals(
            "indio://merge/abc123?h=1080",
            QualityUris.target("abc123", 1080, qualities),
        )
    }

    @Test
    fun staleHeightMapsToTheNearestRung() {
        assertEquals(
            "indio://merge/abc123?h=480",
            QualityUris.target("abc123", 600, qualities),
        )
        assertEquals(
            "indio://merge/abc123?h=1080",
            QualityUris.target("abc123", 2160, qualities),
        )
    }

    @Test
    fun warmupUsesTheSameStreamThePlayerWillOpen() {
        assertEquals(
            listOf("https://example.com/720"),
            playbackWarmStreams(qualities, 720).map { it.url },
        )
        assertEquals(
            listOf("https://example.com/1080", "https://example.com/0"),
            playbackWarmStreams(qualities, 1080).map { it.url },
        )
    }

    @Test
    fun autoWarmupUsesTheAutoStream() {
        assertEquals(
            listOf("https://example.com/720"),
            playbackWarmStreams(qualities, null).map { it.url },
        )
    }

    @Test
    fun targetIsNullOnlyWhenTheRungHasNoSource() {
        val broken = VideoQualities(
            options = listOf(
                QualityOption(600, "600p", 30, muxed = null, videoOnly = null),
            ),
            auto = null,
            audio = stream(0, "audio/mp4"),
        )
        assertNull(QualityUris.target("abc123", 600, broken))
    }

    private val downloadLadder = VideoQualities(
        options = listOf(
            QualityOption(
                1440, "1440p", 30, muxed = null,
                videoOnly = stream(1440, codec = "vp9"),
            ),
            QualityOption(
                1080, "1080p", 30, muxed = null,
                videoOnly = stream(1080, codec = "avc1.640028"),
            ),
            QualityOption(
                360, "360p", 30,
                muxed = stream(360, codec = "avc1.42001E"),
                videoOnly = stream(360, codec = "avc1.42001E"),
            ),
        ),
        auto = stream(360, codec = "avc1.42001E"),
        audio = stream(0, "audio/mp4", "mp4a.40.2"),
    )

    @Test
    fun downloadSheetListsHigherAdaptiveHeights() {
        assertEquals(
            listOf(1080, 360),
            downloadLadder.downloadOptions().map { it.height },
        )
    }

    @Test
    fun explicitAdaptiveHeightDownloadsVideoAndAudio() {
        val sources = downloadSources(downloadLadder, 1080)
        assertEquals(1080, sources.video.height)
        assertTrue(sources.video.videoDownloadMuxable)
        assertEquals(0, sources.audio?.height)
    }

    @Test
    fun explicitMuxedHeightStaysASingleFile() {
        val sources = downloadSources(downloadLadder, 360)
        assertEquals(360, sources.video.height)
        assertNull(sources.audio)
    }

    @Test
    fun autoStillDownloadsTheBestMuxedFile() {
        val sources = downloadSources(downloadLadder, null)
        assertEquals(360, sources.video.height)
        assertNull(sources.audio)
    }

    @Test
    fun unmuxableAdaptiveHeightFallsBackToMuxed() {
        val sources = downloadSources(downloadLadder, 1440)
        assertEquals(360, sources.video.height)
        assertNull(sources.audio)
    }

    // The default quality is applied to a video when it starts. When the rung
    // it resolves to is exactly what Auto already plays, the swap would only
    // rebuild the same source, so the player has to leave the video alone.
    @Test
    fun aDefaultThatAutoAlreadyPlaysIsLeftAlone() {
        assertTrue("no default is Auto", qualities.playsAutoStream(null))
        assertTrue("the Auto rung itself changes nothing", qualities.playsAutoStream(720))
        assertFalse("a higher rung is a real switch", qualities.playsAutoStream(1080))
        assertFalse("a lower rung is a real switch", qualities.playsAutoStream(480))
        assertFalse("an absurd height still picks a real rung", qualities.playsAutoStream(4320))
    }

    @Test
    fun aVideoCappedBelowTheDefaultStaysOnAuto() {
        val capped = VideoQualities(
            options = listOf(option(360, muxed = true)),
            auto = stream(360),
            audio = stream(0, "audio/mp4"),
        )
        assertTrue("nothing better than Auto exists here", capped.playsAutoStream(1080))
    }

    @Test
    fun anEmptyLadderIsLeftAlone() {
        assertTrue(VideoQualities(emptyList(), null, null).playsAutoStream(1080))
    }
}
