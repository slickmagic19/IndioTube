package com.indiotube.app.player

import android.net.Uri
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleExtractor
import com.indiotube.app.Container

// High resolutions on YouTube are served as separate video and audio files.
// This factory turns an `indio://merge/...` media item into a
// MergingMediaSource pairing the selected video track with the best audio
// track, and leaves every other item to the normal progressive pipeline.
// Downloaded videos are played from the single saved file instead (the saved
// copy is always muxed), so no double audio and no network use.
//
// Captions are attached here for every path. Media3 1.4 and newer parse
// subtitles during extraction: a side-loaded track has to be loaded as
// progressive media through a SubtitleExtractor, which turns the WebVTT into
// `application/x-media3-cues` samples. The older SingleSampleMediaSource
// approach feeds raw `text/vtt` samples to the text renderer, which rejects
// them ("legacy decoding is disabled") and playback stops - that made
// captions silently disappear. This mirrors DefaultMediaSourceFactory.
class IndioMediaSourceFactory(private val container: Container) : MediaSource.Factory {

    private val dataSourceFactory = IndioTubeDataSource.Factory(container)
    private val base = DefaultMediaSourceFactory(dataSourceFactory)
    private val subtitleParserFactory = DefaultSubtitleParserFactory()

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val subtitleConfigs = mediaItem.localConfiguration?.subtitleConfigurations.orEmpty()
        // Subtitles belong to the item as a whole, never to its merged video
        // and audio children (they would fight over the text renderer), so
        // strip them first and re-attach them once at the top level.
        val bare = if (subtitleConfigs.isEmpty()) {
            mediaItem
        } else {
            mediaItem.buildUpon().setSubtitleConfigurations(emptyList()).build()
        }
        val content = contentSource(bare)
        val source = if (subtitleConfigs.isEmpty()) {
            content
        } else {
            val subtitles = subtitleConfigs.mapNotNull { subtitleSource(it) }
            if (subtitles.isEmpty()) content else MergingMediaSource(content, *subtitles.toTypedArray())
        }
        // Media3 decides in place whether a replaced item can keep its media
        // source (ProgressiveMediaSource only compares the URI), so without
        // this wrapper replacing an item to toggle captions never builds the
        // subtitle source and captions stay invisible until a quality change.
        return RebuildOnReplaceMediaSource(source)
    }

    private fun contentSource(mediaItem: MediaItem): MediaSource {
        val uri = mediaItem.localConfiguration?.uri
        val id = if (uri?.scheme == "indio" && uri.host == "merge") uri.lastPathSegment else null

        if (id.isNullOrBlank()) return base.createMediaSource(mediaItem)

        if (container.downloads.isDownloaded(id)) {
            val offline = Uri.Builder()
                .scheme("indio")
                .authority("offline")
                .appendPath(id)
                .build()
            return base.createMediaSource(mediaItem.buildUpon().setUri(offline).build())
        }

        val height = uri?.getQueryParameter("h") ?: return base.createMediaSource(mediaItem)
        val video = base.createMediaSource(
            mediaItem.buildUpon()
                .setUri(
                    Uri.Builder()
                        .scheme("indio")
                        .authority("stream")
                        .appendPath(id)
                        .appendQueryParameter("type", "video")
                        .appendQueryParameter("h", height)
                        .build(),
                )
                .build(),
        )
        val audio = base.createMediaSource(
            mediaItem.buildUpon()
                .setUri(
                    Uri.Builder()
                        .scheme("indio")
                        .authority("stream")
                        .appendPath(id)
                        .appendQueryParameter("type", "audio")
                        .build(),
                )
                .build(),
        )
        return MergingMediaSource(video, audio)
    }

    // One side-loaded caption track, loaded and parsed the same way
    // DefaultMediaSourceFactory does it so the modern cues pipeline handles it.
    private fun subtitleSource(config: MediaItem.SubtitleConfiguration): MediaSource? {
        val mimeType = config.mimeType ?: return null
        val format = Format.Builder()
            .setSampleMimeType(mimeType)
            .setLanguage(config.language)
            .setSelectionFlags(config.selectionFlags)
            .setRoleFlags(config.roleFlags)
            .setLabel(config.label)
            .setId(config.id)
            .build()
        if (!subtitleParserFactory.supportsFormat(format)) return null
        val extractorsFactory = ExtractorsFactory {
            arrayOf<Extractor>(SubtitleExtractor(subtitleParserFactory.create(format), format))
        }
        return ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
            .createMediaSource(MediaItem.fromUri(config.uri))
    }

    override fun getSupportedTypes(): IntArray = base.supportedTypes

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory {
        base.setDrmSessionManagerProvider(provider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory {
        base.setLoadErrorHandlingPolicy(policy)
        return this
    }
}
