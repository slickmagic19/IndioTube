package com.indiotube.app.player

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.indiotube.app.data.LibraryStore
import com.indiotube.app.data.QualityUris
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
    private var pendingPlay = false
    private var pendingShuffle = false
    private var lastPositionSave = 0L
    private var lastMediaId: String? = null
    private var applyingQuality = false

    // Quality state for the video that is playing: the ladder we last showed
    // the user, and how many times we stepped down after a failed switch.
    private var currentQualities: VideoQualities? = null
    private var qualityFallbackAttempts = 0

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
                    currentQualities = null
                    qualityFallbackAttempts = 0
                }
                if (newVideo && reason != Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                    _queue.value.getOrNull(idx)?.let { store.noteOpen(it) }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val idx = c.currentMediaItemIndex
                val video = _queue.value.getOrNull(idx)
                val qualities = currentQualities
                val active = _qualityHeight.value

                // A quality that fails on this device must never knock the
                // user into another video: step down the same video's ladder
                // (ending on Auto) and keep playing. Only when nothing is
                // playable is an error surfaced, and even then the current
                // item stays put instead of auto-skipping elsewhere.
                if (video != null && qualities != null && active != null &&
                    qualityFallbackAttempts < qualities.options.size + 1
                ) {
                    qualityFallbackAttempts += 1
                    val lower = qualities.below(active)
                    _error.value = if (lower != null) {
                        "That quality wouldn't play — switching to ${lower.label}"
                    } else {
                        "That quality wouldn't play — back to Auto"
                    }
                    scope.launch { applyQuality(c, idx, video, qualities, lower?.height) }
                    return
                }

                val now = System.currentTimeMillis()
                if (now - lastErrorShownAt > 5000) {
                    lastErrorShownAt = now
                    _error.value = friendlyError(error)
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
        currentQualities = null
        qualityFallbackAttempts = 0
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

    // Switch the video that is playing to a specific height (null = Auto).
    // The replacement media item carries the quality in its URI, so the source
    // factory creates either a single muxed source or a video+audio merge on
    // the fly. The position is kept and playback always resumes, exactly like
    // YouTube: it should never strand the user on a paused frame.
    fun setQuality(videoId: String, height: Int?, qualities: VideoQualities) {
        val c = controller ?: return
        val idx = c.currentMediaItemIndex
        if (idx < 0) return
        val video = _queue.value.getOrNull(idx) ?: return
        if (video.videoId != videoId) return
        currentQualities = qualities
        qualityFallbackAttempts = 0
        applyQuality(c, idx, video, qualities, height)
    }

    private fun applyQuality(
        c: MediaController,
        idx: Int,
        video: VideoItem,
        qualities: VideoQualities,
        height: Int?,
        autoPlay: Boolean = true,
    ) {
        // Guard against a queue change between the decision and the action:
        // the item at this index must still be the video we mean to switch.
        if (_queue.value.getOrNull(idx)?.videoId != video.videoId) return
        val current = c.getMediaItemAt(idx)
        if (current.mediaId != video.videoId) return

        val rung = if (height == null) null else qualities.nearest(height) ?: return
        val target = QualityUris.target(video.videoId, rung?.height, qualities) ?: return
        val appliedHeight = rung?.height

        // Already on the right stream: just make sure it is playing.
        if (current.localConfiguration?.uri?.toString() == target) {
            _qualityHeight.value = appliedHeight
            if (autoPlay) {
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
                if (!c.isPlaying) c.play()
            }
            return
        }

        val position = c.currentPosition.coerceAtLeast(0)
        store.notePosition(video, position)

        applyingQuality = true
        try {
            c.replaceMediaItem(idx, current.buildUpon().setUri(target.toUri()).build())
            c.seekTo(idx, position)
            // A replaced item can come back idle (and after a decoder/network
            // error the player is idle too). play() alone would then only flip
            // playWhenReady and leave the video stuck on a paused frame.
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
        } finally {
            applyingQuality = false
        }
        _qualityHeight.value = appliedHeight
        if (autoPlay) c.play()
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
        currentQualities = null
        qualityFallbackAttempts = 0
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
            -> "Couldn't fetch this video. Tap play to retry."
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            -> "No connection to YouTube right now."
            else -> "This video wouldn't play. Tap play to retry."
        }
    }
}
