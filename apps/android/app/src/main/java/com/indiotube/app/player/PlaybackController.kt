package com.indiotube.app.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.indiotube.app.data.LibraryStore
import com.indiotube.app.data.VideoItem
import com.indiotube.app.data.VideoQualities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// UI-facing playback facade. Talks to PlaybackService through a MediaController
// so the notification/lock-screen controls and the in-app UI stay in lockstep.
class PlaybackController(
    private val context: Context,
    private val store: LibraryStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var controller: MediaController? = null

    // Exposed so a PlayerView can render the video surface through the session.
    val mediaController: MediaController? get() = controller

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _queue = MutableStateFlow<List<VideoItem>>(emptyList())
    val queue: StateFlow<List<VideoItem>> = _queue.asStateFlow()

    private val _index = MutableStateFlow(-1)
    val index: StateFlow<Int> = _index.asStateFlow()

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    private val _buffering = MutableStateFlow(false)
    val buffering: StateFlow<Boolean> = _buffering.asStateFlow()

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // The quality chosen for the video that is playing right now (null = Auto).
    // YouTube-style, this is per video: the next item starts on Auto again.
    private val _qualityHeight = MutableStateFlow<Int?>(null)
    val qualityHeight: StateFlow<Int?> = _qualityHeight.asStateFlow()

    val current: StateFlow<VideoItem?> = combine(_queue, _index) { q, i -> q.getOrNull(i) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private var lastErrorShownAt = 0L
    private var consecutiveErrors = 0
    private var pendingPlay = false
    private var pendingShuffle = false
    private var lastPositionSave = 0L
    private var lastMediaId: String? = null
    private var applyingQuality = false

    init {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                try {
                    val c = future.get()
                    controller = c
                    attach(c)
                    _connected.value = true
                } catch (_: Exception) {
                    _error.value = "The playback engine couldn't start. Try reopening the app."
                }
            },
            ContextCompat.getMainExecutor(context),
        )

        scope.launch {
            while (true) {
                delay(500)
                val c = controller ?: continue
                _position.value = c.currentPosition.coerceAtLeast(0)
                val d = c.duration
                _duration.value = if (d > 0) d else 0
                // remember watch progress ~every 5s while playing
                if (c.isPlaying) {
                    val now = System.currentTimeMillis()
                    if (now - lastPositionSave > 5000) {
                        lastPositionSave = now
                        val v = _queue.value.getOrNull(c.currentMediaItemIndex)
                        if (v != null) store.notePosition(v, c.currentPosition.coerceAtLeast(0))
                    }
                }
            }
        }
    }

    private fun attach(c: MediaController) {
        c.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _playing.value = isPlaying
                if (isPlaying) consecutiveErrors = 0
                val v = _queue.value.getOrNull(c.currentMediaItemIndex) ?: return
                store.notePosition(v, c.currentPosition.coerceAtLeast(0))
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _buffering.value = playbackState == Player.STATE_BUFFERING
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val idx = c.currentMediaItemIndex
                _index.value = idx
                // A genuinely different video starts back on Auto; replacing
                // the current item to change quality keeps the same media id
                // and must not be treated as a new watch.
                val id = mediaItem?.mediaId
                val newVideo = id != null && id != lastMediaId
                if (newVideo) {
                    lastMediaId = id
                    if (!applyingQuality) _qualityHeight.value = null
                }
                if (newVideo && reason != Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                    _queue.value.getOrNull(idx)?.let { store.noteOpen(it) }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                consecutiveErrors += 1
                val now = System.currentTimeMillis()
                if (now - lastErrorShownAt > 5000) {
                    lastErrorShownAt = now
                    _error.value = friendlyError(error)
                }
                if (consecutiveErrors <= 5 && c.mediaItemCount > 1 && c.currentMediaItemIndex < c.mediaItemCount - 1) {
                    scope.launch {
                        delay(1200)
                        controller?.seekToNextMediaItem()
                    }
                }
            }
        })

        // queue set before the controller finished connecting (cold start tap)
        if (_queue.value.isNotEmpty() && c.mediaItemCount == 0) {
            val items = _queue.value.map { toMediaItem(it) }
            c.setMediaItems(items, _index.value.coerceAtLeast(0), 0)
            if (pendingShuffle) c.shuffleModeEnabled = true
            c.prepare()
            if (pendingPlay) c.play()
        }
        pendingPlay = false
        pendingShuffle = false
    }

    fun clearError() {
        _error.value = null
    }

    fun setQueue(videos: List<VideoItem>, startIndex: Int, play: Boolean, shuffleOn: Boolean = false) {
        if (videos.isEmpty()) return
        val start = startIndex.coerceIn(0, videos.lastIndex)
        _queue.value = videos
        _index.value = start
        _qualityHeight.value = null
        lastMediaId = null
        val c = controller ?: run {
            pendingPlay = play
            pendingShuffle = shuffleOn
            return
        }
        c.setMediaItems(videos.map { toMediaItem(it) }, start, 0)
        c.shuffleModeEnabled = shuffleOn
        c.prepare()
        if (play) c.play()
    }

    fun playVideo(video: VideoItem) {
        setQueue(listOf(video), 0, true)
        controller?.play()
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    fun previous() {
        controller?.seekToPrevious()
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms)
    }

    fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        c.seekTo((c.currentPosition + deltaMs).coerceAtLeast(0))
    }

    // Switch the video that is playing to a specific height (null = Auto),
    // keeping the position and play/pause state. The replacement media item
    // carries the quality in its URI, so the source factory creates either a
    // single muxed source or a video+audio merge on the fly.
    fun setQuality(videoId: String, height: Int?, qualities: VideoQualities) {
        _qualityHeight.value = height
        val c = controller ?: return
        val idx = c.currentMediaItemIndex
        if (idx < 0) return
        val video = _queue.value.getOrNull(idx) ?: return
        if (video.videoId != videoId) return
        applyQuality(c, idx, video, qualities, height)
    }

    private fun applyQuality(
        c: MediaController,
        idx: Int,
        video: VideoItem,
        qualities: VideoQualities,
        height: Int?,
    ) {
        val current = c.getMediaItemAt(idx)
        val currentUri = current.localConfiguration?.uri
        val currentHeight = currentUri?.getQueryParameter("h")?.toIntOrNull()

        if (height == null && currentUri?.host == "stream" && currentHeight == null) return

        val uri: Uri = if (height == null) {
            Uri.Builder()
                .scheme("indio")
                .authority("stream")
                .appendPath(video.videoId)
                .build()
        } else {
            val option = qualities.nearest(height) ?: return
            if (currentHeight == option.height) return
            when {
                option.muxed != null -> Uri.Builder()
                    .scheme("indio")
                    .authority("stream")
                    .appendPath(video.videoId)
                    .appendQueryParameter("type", "muxed")
                    .appendQueryParameter("h", option.height.toString())
                    .build()
                option.videoOnly != null -> Uri.Builder()
                    .scheme("indio")
                    .authority("merge")
                    .appendPath(video.videoId)
                    .appendQueryParameter("h", option.height.toString())
                    .build()
                else -> return
            }
        }

        val wasPlaying = c.playWhenReady
        val position = c.currentPosition.coerceAtLeast(0)
        applyingQuality = true
        try {
            c.replaceMediaItem(idx, current.buildUpon().setUri(uri).build())
            c.seekTo(idx, position)
            if (wasPlaying) c.play()
        } finally {
            applyingQuality = false
        }
    }

    fun stop() {
        val c = controller ?: return
        val v = _queue.value.getOrNull(c.currentMediaItemIndex)
        if (v != null) store.notePosition(v, c.currentPosition.coerceAtLeast(0))
        c.stop()
    }

    // Dismiss the mini player entirely.
    fun clear() {
        val c = controller ?: return
        val v = _queue.value.getOrNull(c.currentMediaItemIndex)
        if (v != null) store.notePosition(v, c.currentPosition.coerceAtLeast(0))
        c.stop()
        c.clearMediaItems()
        _queue.value = emptyList()
        _index.value = -1
        _playing.value = false
        _position.value = 0
        _duration.value = 0
        _qualityHeight.value = null
        lastMediaId = null
    }

    private fun toMediaItem(v: VideoItem): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(v.title.ifBlank { "Video" })
            .setArtist(v.channelName)
            .apply { v.thumbnail?.let { setArtworkUri(it.toUri()) } }
            .build()
        return MediaItem.Builder()
            .setMediaId(v.videoId)
            .setUri("indio://stream/${v.videoId}")
            .setMediaMetadata(meta)
            .build()
    }

    private fun friendlyError(e: PlaybackException): String {
        return when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            -> "Couldn't fetch this video — skipping."
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            -> "No connection to YouTube right now."
            else -> "This video wouldn't play — skipping."
        }
    }
}
