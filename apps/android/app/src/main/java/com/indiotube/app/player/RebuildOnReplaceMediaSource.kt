package com.indiotube.app.player

import android.os.Handler
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MediaSourceEventListener
import androidx.media3.exoplayer.upstream.Allocator

// Media3 1.4 only treats a replaced media item as "updatable in place" when
// its URI, image duration and cache key match, and then never rebuilds the
// source. Attaching captions to a playing item (same URI, new subtitle
// configurations) therefore silently produced no subtitle track until the
// next quality change forced a rebuild. Opting out of the in-place update
// makes every replacement recreate the source, so newly enabled captions
// appear immediately.
class RebuildOnReplaceMediaSource(
    private val source: MediaSource,
) : MediaSource {

    override fun getMediaItem(): MediaItem = source.mediaItem

    override fun canUpdateMediaItem(mediaItem: MediaItem): Boolean = false

    override fun updateMediaItem(mediaItem: MediaItem) = source.updateMediaItem(mediaItem)

    override fun getInitialTimeline(): Timeline? = source.initialTimeline

    override fun isSingleWindow(): Boolean = source.isSingleWindow

    override fun prepareSource(
        caller: MediaSource.MediaSourceCaller,
        transferListener: TransferListener?,
        playerId: PlayerId,
    ) = source.prepareSource(caller, transferListener, playerId)

    override fun enable(caller: MediaSource.MediaSourceCaller) = source.enable(caller)

    override fun maybeThrowSourceInfoRefreshError() = source.maybeThrowSourceInfoRefreshError()

    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long,
    ): MediaPeriod = source.createPeriod(id, allocator, startPositionUs)

    override fun releasePeriod(mediaPeriod: MediaPeriod) = source.releasePeriod(mediaPeriod)

    override fun disable(caller: MediaSource.MediaSourceCaller) = source.disable(caller)

    override fun releaseSource(caller: MediaSource.MediaSourceCaller) = source.releaseSource(caller)

    override fun addEventListener(handler: Handler, eventListener: MediaSourceEventListener) =
        source.addEventListener(handler, eventListener)

    override fun removeEventListener(eventListener: MediaSourceEventListener) =
        source.removeEventListener(eventListener)

    override fun addDrmEventListener(handler: Handler, listener: DrmSessionEventListener) =
        source.addDrmEventListener(handler, listener)

    override fun removeDrmEventListener(listener: DrmSessionEventListener) =
        source.removeDrmEventListener(listener)
}
