package com.indiotube.app

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.text.Layout
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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.SubtitleExtractor
import androidx.media3.test.utils.FakeAudioRenderer
import androidx.media3.test.utils.FakeDataSet
import androidx.media3.test.utils.FakeDataSource
import androidx.media3.test.utils.FakeVideoRenderer
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import com.indiotube.app.player.CenteringSubtitleParserFactory
import com.indiotube.app.player.RebuildOnReplaceMediaSource
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
            "00:00:00.000 --> 00:00:09.000 align:start position:0%\n" +
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
        val parserFactory = CenteringSubtitleParserFactory()
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
        // The source file is left-aligned (auto-caption style); the app's
        // parser factory must centre it and let the player own the placement.
        assertTrue(
            "cues must be centred regardless of the file's alignment" +
                " (align=${delivered.map { it.textAlignment }}, pos=${delivered.map { it.position }})",
            delivered.isNotEmpty() && delivered.all {
                it.textAlignment == Layout.Alignment.ALIGN_CENTER &&
                    it.position == Cue.DIMEN_UNSET
            },
        )

        player.release()
        shadowOf(mainLooper).idle()
    }

    // Reproduces enabling captions while a video is already playing: the app
    // replaces the current media item with one that carries the subtitle
    // configuration (PlaybackController.setSubtitles). The track must attach
    // and render immediately, not only after the next quality change.
    @Test
    fun attachingCaptionsWhilePlayingTakesEffectImmediately() {
        val groups = mutableListOf<List<Cue>>()
        val handler = Clock.DEFAULT.createHandler(mainLooper, null)
        val renderers = arrayOf<Renderer>(
            FakeVideoRenderer(handler, object : VideoRendererEventListener {}),
            FakeAudioRenderer(handler, object : AudioRendererEventListener {}),
            TextRenderer(TextOutput { group -> groups += group.cues.toList() }, mainLooper),
        )

        val dataSet = FakeDataSet()
            .newData(CONTENT_URL).appendReadData(wavBytes(seconds = 10)).endData()
            .newData(CAPTION_URL).appendReadData(webvtt).endData()
        val dataSourceFactory = DataSource.Factory {
            RecordingDataSource(FakeDataSource(dataSet), openedUris)
        }
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        val loggingFactory = object : MediaSource.Factory {
            override fun createMediaSource(mediaItem: MediaItem): MediaSource {
                events += "create:subs=${mediaItem.localConfiguration?.subtitleConfigurations?.size ?: -1}"
                return RebuildOnReplaceMediaSource(mediaSourceFactory.createMediaSource(mediaItem))
            }

            override fun getSupportedTypes(): IntArray = mediaSourceFactory.supportedTypes

            override fun setDrmSessionManagerProvider(
                provider: androidx.media3.exoplayer.drm.DrmSessionManagerProvider,
            ): MediaSource.Factory {
                mediaSourceFactory.setDrmSessionManagerProvider(provider)
                return this
            }

            override fun setLoadErrorHandlingPolicy(
                policy: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy,
            ): MediaSource.Factory {
                mediaSourceFactory.setLoadErrorHandlingPolicy(policy)
                return this
            }
        }
        val player = TestExoPlayerBuilder(context)
            .setRenderers(*renderers)
            .setMediaSourceFactory(loggingFactory)
            .build()
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onPlayerError(
                eventTime: AnalyticsListener.EventTime,
                error: androidx.media3.common.PlaybackException,
            ) {
                events += "error:${error.errorCodeName}:${error.message}"
            }
        })

        player.setMediaItem(MediaItem.fromUri(CONTENT_URL))
        player.prepare()
        TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_READY)
        shadowOf(mainLooper).idle()
        TestPlayerRunHelper.playUntilPosition(player, 0, 500)
        shadowOf(mainLooper).idle()
        assertTrue(
            "test setup: no caption track before attaching",
            player.currentTracks.groups.none { it.type == C.TRACK_TYPE_TEXT },
        )

        // Same sequence PlaybackController.setSubtitles performs: replace the
        // current item, keep the position, resume when it was playing.
        val position = player.currentPosition
        val withCaptions = player.getMediaItemAt(0).buildUpon()
            .setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(CAPTION_URL))
                        .setMimeType(MimeTypes.TEXT_VTT)
                        .setLanguage("en")
                        .setLabel("English")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .setRoleFlags(C.ROLE_FLAG_CAPTION)
                        .build(),
                ),
            )
            .build()
        player.replaceMediaItem(0, withCaptions)
        player.seekTo(0, position)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        events += "subsInItemAfterReplace=${player.getMediaItemAt(0).localConfiguration?.subtitleConfigurations?.size ?: -1}"

        // Pump playback in small steps until the caption shows, the way a
        // viewer watches it appear; the loader thread needs looper cycles to
        // hand the parsed cue to the renderer, so a single jump to one
        // position is racy under the fake clock.
        fun playForCue(from: Long): String? {
            var target = from + 250
            while (target <= 9_500L && player.playbackState != Player.STATE_ENDED) {
                try {
                    TestPlayerRunHelper.playUntilPosition(player, 0, target)
                } catch (e: Exception) {
                    throw AssertionError(
                        "playback stalled at $target ms (state=${player.playbackState}," +
                            " pos=${player.currentPosition}, pwr=${player.playWhenReady}," +
                            " opened=$openedUris, events=$events)",
                        e,
                    )
                }
                shadowOf(mainLooper).idle()
                val text = groups.flatten()
                    .mapNotNull { it.text?.toString() }
                    .firstOrNull { it == "Hello captions" }
                if (text != null) return text
                target += 250
            }
            return null
        }

        // The cue spans the whole 10s file, but a loaded caption track that
        // arrives mid-seek can miss it on the first pass under load. A second
        // pass from the start always crosses the cue window.
        var emittedText = playForCue(position)
        if (emittedText == null) {
            player.seekTo(0, 0)
            shadowOf(mainLooper).idle()
            emittedText = playForCue(0)
        }
        val textGroups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val texts = groups.flatten().map { it.text?.toString() }
        assertTrue(
            "captions attached while playing must show without a quality change" +
                " (tracks=${player.currentTracks.groups.map { "${it.type}:${it.isTrackSelected(0)}" }}," +
                " opened=$openedUris, emitted=$texts at ${player.currentPosition}ms, events=$events)",
            textGroups.isNotEmpty() &&
                textGroups.first().isTrackSelected(0) &&
                texts.contains("Hello captions"),
        )

        // Turning captions off mirrors setSubtitles(videoId, null): replace the
        // item with one that carries no subtitle configuration. The text track
        // must disappear and the renderer must clear the cues on screen while
        // the video is still playing â€” the on-device report was that captions
        // lingered until the next quality change.
        val offPosition = player.currentPosition
        val withoutCaptions = player.getMediaItemAt(0).buildUpon()
            .setSubtitleConfigurations(emptyList())
            .build()
        player.replaceMediaItem(0, withoutCaptions)
        player.seekTo(0, offPosition)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()

        var offTarget = offPosition + 250
        while (offTarget <= 9_500L) {
            TestPlayerRunHelper.playUntilPosition(player, 0, offTarget)
            shadowOf(mainLooper).idle()
            if (player.currentTracks.groups.none { it.type == C.TRACK_TYPE_TEXT } &&
                groups.isNotEmpty() && groups.last().isEmpty()
            ) {
                break
            }
            offTarget += 250
        }
        shadowOf(mainLooper).idle()
        assertTrue(
            "turning captions off must remove the text track" +
                " (tracks=${player.currentTracks.groups.map { it.type }}," +
                " groups=${groups.map { it.size }} at ${player.currentPosition}ms, events=$events)",
            player.currentTracks.groups.none { it.type == C.TRACK_TYPE_TEXT },
        )
        assertTrue(
            "turning captions off must clear the rendered cues" +
                " (groups=${groups.map { it.size }} at ${player.currentPosition}ms, events=$events)",
            groups.isNotEmpty() && groups.last().isEmpty(),
        )

        player.release()
        shadowOf(mainLooper).idle()
    }

    // Minimal silent PCM WAV so the content source exposes a real audio track.
    private fun wavBytes(sampleRate: Int = 8_000, seconds: Int = 3): ByteArray {
        val dataSize = sampleRate * seconds * 2
        val buffer = java.nio.ByteBuffer.allocate(44 + dataSize).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        buffer.put("data".toByteArray()).putInt(dataSize)
        buffer.put(ByteArray(dataSize))
        return buffer.array()
    }

    private companion object {
        const val CONTENT_URL = "https://video.test/content.wav"
        const val CAPTION_URL = "https://cc.test/captions.vtt"
    }
}
