package com.indiotube.app

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Clock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleExtractor
import androidx.media3.test.utils.FakeAudioRenderer
import androidx.media3.test.utils.FakeDataSet
import androidx.media3.test.utils.FakeDataSource
import androidx.media3.test.utils.FakeVideoRenderer
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

// End-to-end check of the exact caption composition the app builds at runtime:
// a content source merged with a side-loaded WebVTT track loaded through a
// SubtitleExtractor (the IndioMediaSourceFactory pattern), played by a real
// ExoPlayer with a real TextRenderer (production uses DefaultRenderersFactory,
// which builds one). Covers the whole chain: the caption file is fetched, the
// track is exposed as application/x-media3-cues, DefaultTrackSelector selects
// it, and the renderer parses timings and emits the caption text.
@RunWith(RobolectricTestRunner::class)
class CaptionPlaybackTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val mainLooper: Looper = Looper.getMainLooper()

    private val openedUris = mutableListOf<String>()
    private val events = mutableListOf<String>()

    private val webvtt: ByteArray = (
        "WEBVTT\n\n" +
            "00:00:00.000 --> 00:00:04.000\n" +
            "Hello captions\n"
        ).toByteArray()

    private class RecordingDataSource(
        private val delegate: DataSource,
        private val opened: MutableList<String>,
    ) : DataSource {
        override fun open(dataSpec: DataSpec): Long {
            opened += dataSpec.uri.toString()
            return delegate.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate.read(buffer, offset, length)

        override fun addTransferListener(transferListener: TransferListener) =
            delegate.addTransferListener(transferListener)

        override fun getUri(): Uri? = delegate.uri

        override fun close() = delegate.close()
    }

    private fun subtitleSource(): ProgressiveMediaSource {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.TEXT_VTT)
            .setLanguage("en")
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .setRoleFlags(C.ROLE_FLAG_CAPTION)
            .setLabel("English")
            .build()
        val parserFactory = DefaultSubtitleParserFactory()
        val extractorsFactory = ExtractorsFactory {
            arrayOf<Extractor>(SubtitleExtractor(parserFactory.create(format), format))
        }
        val dataSet = FakeDataSet().newDefaultData().appendReadData(webvtt).endData()
        val fakeDataSource = FakeDataSource(dataSet)
        val dataSourceFactory = DataSource.Factory { RecordingDataSource(fakeDataSource, openedUris) }
        return ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
            .createMediaSource(MediaItem.fromUri("https://cc.test/captions.vtt"))
    }

    @Test
    fun mergedCaptionTrackIsSelectedAndTheRendererEmitsCues() {
        val delivered = mutableListOf<Cue>()
        val handler = Clock.DEFAULT.createHandler(mainLooper, null)
        val renderers = arrayOf<Renderer>(
            FakeVideoRenderer(handler, object : VideoRendererEventListener {}),
            FakeAudioRenderer(handler, object : AudioRendererEventListener {}),
            TextRenderer(TextOutput { group -> delivered += group.cues }, mainLooper),
        )

        val player = TestExoPlayerBuilder(context).setRenderers(*renderers).build()
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onLoadCompleted(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
            ) {
                events += "loadDone:${loadEventInfo.uri}"
            }

            override fun onDownstreamFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                mediaLoadData: MediaLoadData,
            ) {
                events += "format:${mediaLoadData.trackType}:${mediaLoadData.trackFormat?.sampleMimeType}"
            }

            override fun onPlayerError(
                eventTime: AnalyticsListener.EventTime,
                error: androidx.media3.common.PlaybackException,
            ) {
                events += "error:${error.errorCodeName}:${error.message}"
            }
        })

        player.setMediaSource(MergingMediaSource(SilenceMediaSource(10_000_000L), subtitleSource()))
        player.prepare()
        TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_READY)
        shadowOf(mainLooper).idle()

        val textGroups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        assertTrue(
            "subtitle track group must be exposed (opened=$openedUris events=$events)",
            textGroups.isNotEmpty(),
        )
        val group = textGroups.first()
        assertEquals(
            "caption track must be parsed as media3 cues",
            MimeTypes.APPLICATION_MEDIA3_CUES,
            group.getTrackFormat(0).sampleMimeType,
        )
        assertTrue("caption track must be selected", group.isTrackSelected(0))

        // Let the fake clock advance in steps so the loader thread can feed
        // the sample queue before each assert position.
        listOf(500L, 1_000L, 1_500L, 2_000L).forEach { target ->
            TestPlayerRunHelper.playUntilPosition(player, 0, target)
            shadowOf(mainLooper).idle()
        }
        val texts = delivered.map { it.text?.toString() }
        assertTrue(
            "the caption file must be fetched (opened=$openedUris events=$events)",
            openedUris.any { it == "https://cc.test/captions.vtt" },
        )
        assertTrue(
            "TextRenderer must emit the caption text (emitted=$texts at ${player.currentPosition}ms;" +
                " opened=$openedUris events=$events)",
            texts.contains("Hello captions"),
        )

        player.release()
        shadowOf(mainLooper).idle()
    }
}
