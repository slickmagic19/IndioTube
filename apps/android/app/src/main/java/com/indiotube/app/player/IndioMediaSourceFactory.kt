package com.indiotube.app.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.indiotube.app.Container

// High resolutions on YouTube are served as separate video and audio files.
// This factory turns an `indio://merge/...` media item into a
// MergingMediaSource pairing the selected video track with the best audio
// track, and leaves every other item to the normal progressive pipeline.
// Downloaded videos are played from the single saved file instead (the saved
// copy is always muxed), so no double audio and no network use.
class IndioMediaSourceFactory(private val container: Container) : MediaSource.Factory {

    private val base = DefaultMediaSourceFactory(IndioTubeDataSource.Factory(container))

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
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
