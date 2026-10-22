package com.indiotube.app.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.indiotube.app.Container
import com.indiotube.app.data.LibraryStore
import com.indiotube.app.data.PlaybackPrefs
import com.indiotube.app.data.QualityOption
import com.indiotube.app.data.QualityUris
import com.indiotube.app.data.ResolvedStream
import com.indiotube.app.data.SubtitleTrack
import com.indiotube.app.data.VideoItem
import com.indiotube.app.data.VideoQualities
import com.indiotube.app.data.playbackStreams
import com.indiotube.app.data.playbackWarmStreams
import com.indiotube.app.data.resumeStartPosition
import java.util.concurrent.TimeUnit
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
import okhttp3.Request

private const val WARM_TTL_MS = 120_000L
private const val WARM_BODY_LIMIT = 16L * 1024

// UI-facing playback facade. Talks to PlaybackService through a MediaController
// so the notification/lock-screen controls and the in-app UI stay in lockstep.
class PlaybackController(
    private val context: Context,
    private val store: LibraryStore,
    private val prefs: PlaybackPrefs,
    // Optional: when present, quality switches warm the CDN connection for
    // the incoming stream before the player starts loading it.
    private val container: Container? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var controller: MediaController? = null

    // Exposed so a PlayerView can render the video surface through the session.
    val mediaController: MediaController? get() = controller

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _queue = MutableStateFlow<List<VideoItem>>(emptyList())
    val queue: StateFlow<List<VideoItem>> = _queue.asStateFlow()

    // IDs the user added via "Play next" / "Add to queue". The player playlist
    // also carries autoplay context from the feed, so the Queue sheet filters
    // to these instead of showing every recommendation as a queued item.
    private val _explicit = MutableStateFlow<Set<String>>(emptySet())
    val explicitIds: StateFlow<Set<String>> = _explicit.asStateFlow()

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

    // The caption track currently attached to the playing video (null = off).
    private val _subtitle = MutableStateFlow<SubtitleTrack?>(null)
    val subtitle: StateFlow<SubtitleTrack?> = _subtitle.asStateFlow()

    // YouTube's Autoplay switch: when off, the player pauses at the end of the
    // current video instead of continuing with the next item in the queue.
    val autoplayNext: StateFlow<Boolean> get() = prefs.autoplayNext

    val current: StateFlow<VideoItem?> = combine(_queue, _index) { q, i -> q.getOrNull(i) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private var lastErrorShownAt = 0L
    private var pendingPlay = false
    private var pendingShuffle = false
    private var pendingStartPosition = 0L
    private var lastPositionSave = 0L
    private var lastMediaId: String? = null
    private var replacingItem = false

    // Quality state for the video that is playing: the ladder we last showed
    // the user, and how many times we stepped down after a failed switch.
    private var currentQualities: VideoQualities? = null
    private var qualityFallbackAttempts = 0

    // If a caption file fails to load it must not take the video down with
    // it: drop captions and retry the stream once before touching quality.
    private var captionRetryAttempted = false

    // What is actually attached to the playing item. Media items that cross
    // the MediaSession boundary arrive without their local configuration
    // (subtitle configs included), so the controller cannot use the item it
    // reads back to decide whether captions are on. That made turning them
    // off a no-op: the item looked like it had no captions already.
    private var attachedSubtitleVideoId: String? = null
    private var attachedSubtitleUrl: String? = null

    // URI of the stream currently selected by applyQuality (null = the item's
    // own indio:// Auto URI), so re-picking the same quality is a no-op even
    // though the controller item hides its local configuration.
    private var currentStreamUrl: String? = null

    // CDN URLs whose connections were warmed recently. The sheet and the final
    // quality pick often ask for the same streams, so this avoids duplicate
    // preflight requests while still refreshing after the pool may have aged.
    private val warmedAt = HashMap<String, Long>()

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
                        if (v != null) {
                            store.notePosition(v, c.currentPosition.coerceAtLeast(0), if (d > 0) d else 0)
                        }
                    }
                }
            }
        }
    }

    private fun attach(c: MediaController) {
        sendAutoplay(c)
        c.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _playing.value = isPlaying
                val v = _queue.value.getOrNull(c.currentMediaItemIndex) ?: return
                val d = c.duration
                store.notePosition(v, c.currentPosition.coerceAtLeast(0), if (d > 0) d else 0)
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
                    if (!replacingItem) _qualityHeight.value = null
                    currentQualities = null
                    qualityFallbackAttempts = 0
                    _subtitle.value = null
                    captionRetryAttempted = false
                    attachedSubtitleVideoId = null
                    attachedSubtitleUrl = null
                    currentStreamUrl = null
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

                // A caption track that fails to load (stale URL, 403, offline)
                // must not stop a playable video, but a video/CDN failure must
                // not silently kill captions either. Only drop them when the
                // failing request is the caption URL itself, then retry the
                // same stream once without them.
                val subtitle = _subtitle.value
                val failedUri = errorFailingUri(error)
                if (video != null && subtitle != null && failedUri != null &&
                    failedUri.toString() == subtitle.url && !captionRetryAttempted
                ) {
                    captionRetryAttempted = true
                    val code = errorHttpStatusCode(error)
                    _error.value = if (code != null) {
                        "Captions unavailable ($code) — playing without them"
                    } else {
                        "Captions unavailable — playing without them"
                    }
                    setSubtitles(video.videoId, null)
                    return
                }

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
            c.setMediaItems(items, _index.value.coerceAtLeast(0), pendingStartPosition)
            if (pendingShuffle) c.shuffleModeEnabled = true
            c.prepare()
            if (pendingPlay) c.play()
        }
        pendingPlay = false
        pendingShuffle = false
        pendingStartPosition = 0
    }

    fun clearError() {
        _error.value = null
    }

    // The URI whose request failed, walking the cause chain of a playback
    // error down to the http data source exception that carries the data spec.
    private fun errorFailingUri(error: Throwable): Uri? {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is HttpDataSource.HttpDataSourceException) return cause.dataSpec.uri
            cause = cause.cause
        }
        return null
    }

    // HTTP status of the failed request when the cause chain has one, so the
    // user sees "Captions unavailable (403)" instead of a generic message.
    private fun errorHttpStatusCode(error: Throwable): Int? {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) return cause.responseCode
            cause = cause.cause
        }
        return null
    }

    fun setQueue(videos: List<VideoItem>, startIndex: Int, play: Boolean, shuffleOn: Boolean = false) {
        if (videos.isEmpty()) return
        val start = startIndex.coerceIn(0, videos.lastIndex)
        _queue.value = videos
        _index.value = start
        _explicit.value = emptySet()
        _qualityHeight.value = null
        currentQualities = null
        qualityFallbackAttempts = 0
        lastMediaId = null
        _subtitle.value = null
        captionRetryAttempted = false
        attachedSubtitleVideoId = null
        attachedSubtitleUrl = null
        currentStreamUrl = null
        val first = videos[start]
        val resume = if (play) {
            resumeStartPosition(
                resumeMs = store.resumePosition(first.videoId),
                watchedFraction = store.watchedFraction(first.videoId),
                durationMs = first.durationSec * 1000L,
            )
        } else {
            0L
        }
        val c = controller ?: run {
            pendingPlay = play
            pendingShuffle = shuffleOn
            pendingStartPosition = resume
            return
        }
        c.setMediaItems(videos.map { toMediaItem(it) }, start, resume)
        c.shuffleModeEnabled = shuffleOn
        c.prepare()
        if (play) c.play()
    }

    fun playVideo(video: VideoItem) {
        setQueue(listOf(video), 0, true)
        controller?.play()
    }

    // ---- queue editing ------------------------------------------------------
    // The player playlist doubles as autoplay context, so _explicit tracks the
    // rows the user actually queued; the Queue sheet shows those plus the
    // current video. Every edit updates _queue and mirrors it onto the
    // controller. Returns false when nothing changed (duplicate, current
    // item, bad index).

    fun addToQueue(video: VideoItem, playNext: Boolean = false): Boolean {
        val q = _queue.value
        val c = controller
        val current = c?.currentMediaItemIndex?.takeIf { it >= 0 } ?: _index.value.coerceAtLeast(0)
        if (q.isEmpty()) {
            // Nothing is playing: adding the first item starts it, otherwise
            // the mini player would appear over a silent queue.
            setQueue(listOf(video), 0, play = true)
            return true
        }
        if (q.getOrNull(current)?.videoId == video.videoId) return false
        if (QueueOps.isQueued(q, current, _explicit.value, video.videoId)) return false
        val existing = q.indexOfFirst { it.videoId == video.videoId }
        if (existing >= 0) {
            // Already in the autoplay context: promote it instead of adding a
            // duplicate media item that would be indistinguishable in the
            // media session.
            val target = if (playNext) (current + 1).coerceAtMost(q.lastIndex) else q.lastIndex
            if (existing != target) {
                val moved = QueueOps.move(q, current, existing, target) ?: return false
                _queue.value = moved.queue
                if (c != null && existing < c.mediaItemCount && target < c.mediaItemCount) {
                    c.moveMediaItem(existing, target)
                }
                _index.value = moved.index
            }
        } else {
            val result = QueueOps.add(q, current, video, playNext) ?: return false
            _queue.value = result.queue
            _index.value = result.index
            if (c != null) {
                val at = result.queue.indexOfFirst { it.videoId == video.videoId }
                when {
                    c.mediaItemCount == q.size -> c.addMediaItem(at, toMediaItem(video))
                    c.mediaItemCount == 0 -> {
                        c.setMediaItems(result.queue.map { toMediaItem(it) }, current, 0)
                        c.prepare()
                    }
                }
            }
        }
        _explicit.value = _explicit.value + video.videoId
        return true
    }

    fun removeFromQueue(index: Int): Boolean {
        val q = _queue.value
        val c = controller
        val current = c?.currentMediaItemIndex?.takeIf { it >= 0 } ?: _index.value
        val removedId = q.getOrNull(index)?.videoId ?: return false
        // Keeping the row for the playing video avoids leaving the player on a
        // removed (and therefore stopped) item; skip to another row instead.
        val result = QueueOps.remove(q, current, index) ?: return false
        _queue.value = result.queue
        _explicit.value = _explicit.value - removedId
        if (c != null && index < c.mediaItemCount) c.removeMediaItem(index)
        _index.value = result.index
        return true
    }

    fun moveInQueue(from: Int, to: Int): Boolean {
        val q = _queue.value
        val c = controller
        val current = c?.currentMediaItemIndex?.takeIf { it >= 0 } ?: _index.value
        val result = QueueOps.move(q, current, from, to) ?: return false
        _queue.value = result.queue
        if (c != null && from < c.mediaItemCount && to < c.mediaItemCount) {
            c.moveMediaItem(from, to)
        }
        _index.value = result.index
        return true
    }

    fun playQueueItem(index: Int) {
        val c = controller ?: return
        if (index !in _queue.value.indices) return
        c.seekTo(index, 0)
        if (!c.isPlaying) c.play()
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

    fun pause() {
        controller?.pause()
    }

    fun setAutoplayNext(enabled: Boolean) {
        prefs.setAutoplayNext(enabled)
        controller?.let { sendAutoplay(it) }
    }

    // The playlist behavior itself lives on the ExoPlayer inside the service:
    // MediaController has no direct setPauseAtEndOfMediaItems(), so the switch
    // travels over a custom session command.
    private fun sendAutoplay(c: MediaController) {
        c.sendCustomCommand(
            PlaybackCommands.SET_AUTOPLAY_NEXT,
            Bundle().apply { putBoolean(PlaybackCommands.KEY_ENABLED, prefs.autoplayNext.value) },
        )
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

        // Already on the right stream: just make sure it is playing. The URI
        // is tracked locally because the controller's media item has no local
        // configuration to read it back from.
        if (currentStreamUrl == target) {
            _qualityHeight.value = appliedHeight
            if (autoPlay) {
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
                if (!c.isPlaying) c.play()
            }
            return
        }

        val position = c.currentPosition.coerceAtLeast(0)
        val duration = c.duration
        store.notePosition(video, position, if (duration > 0) duration else 0)

        // Warm the connection for the incoming stream while the player swaps
        // its source, so DNS + TLS setup for the new CDN host overlaps the
        // teardown and the target quality starts noticeably sooner.
        warmConnections(rung, qualities)

        // A quality swap rebuilds the whole item, so the captions have to ride
        // along explicitly or they would silently disappear on the new stream.
        val subtitles = if (attachedSubtitleVideoId == video.videoId) {
            _subtitle.value?.let { listOf(subtitleConfiguration(it)) }.orEmpty()
        } else {
            emptyList()
        }

        replacingItem = true
        try {
            c.replaceMediaItem(
                idx,
                current.buildUpon()
                    .setUri(target.toUri())
                    .setSubtitleConfigurations(subtitles)
                    .build(),
            )
            c.seekTo(idx, position)
            // A replaced item can come back idle (and after a decoder/network
            // error the player is idle too). play() alone would then only flip
            // playWhenReady and leave the video stuck on a paused frame.
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
        } finally {
            replacingItem = false
        }
        currentStreamUrl = target
        _qualityHeight.value = appliedHeight
        if (autoPlay) c.play()
    }

    // Called when the quality sheet opens. The user usually needs a moment to
    // pick a rung, which is enough time to run the DNS/TCP/TLS handshake for
    // the likely targets. The real ExoPlayer load then reuses those idle
    // connections because both paths use container.playbackHttp.
    fun prewarmQualityOptions(qualities: VideoQualities?) {
        val ladder = qualities ?: return
        val streams = buildList {
            ladder.auto?.let(::add)
            ladder.options.take(4).forEach { addAll(it.playbackStreams(ladder.audio)) }
        }
        warmStreams(streams)
    }

    private fun warmConnections(rung: QualityOption?, qualities: VideoQualities) {
        warmStreams(playbackWarmStreams(qualities, rung?.height))
    }

    // Fire-and-forget small range requests that leave a reusable CDN
    // connection in the playback pool. The response body is consumed (for a
    // normal 206) because closing an unread body would discard the connection
    // instead of returning it to the pool.
    private fun warmStreams(streams: List<ResolvedStream>) {
        val http = container?.playbackHttp ?: return
        val now = System.currentTimeMillis()
        val targets = synchronized(warmedAt) {
            warmedAt.entries.removeAll { now - it.value > WARM_TTL_MS }
            streams.distinctBy { it.url }
                .filter { warmedAt[it.url] == null }
                .also { fresh -> fresh.forEach { warmedAt[it.url] = now } }
        }
        if (targets.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            for (stream in targets) {
                try {
                    val call = http.newCall(
                        Request.Builder()
                            .url(stream.url)
                            .header("Range", "bytes=0-1")
                            .header("Accept-Encoding", "identity")
                            .header("User-Agent", IndioTubeDataSource.USER_AGENT)
                            .build(),
                    )
                    call.timeout().timeout(5, TimeUnit.SECONDS)
                    call.execute().use { response ->
                        val body = response.body
                        if (response.code == 206 && body != null &&
                            body.contentLength() in 0..WARM_BODY_LIMIT
                        ) {
                            body.bytes()
                        }
                    }
                } catch (_: Exception) {
                    synchronized(warmedAt) { warmedAt.remove(stream.url) }
                }
            }
        }
    }

    // Attach (or remove) a caption track on the video that is playing. The
    // whole item is rebuilt from the current one, so the chosen quality URI is
    // preserved, and the position is kept. Replacing an item while playing can
    // leave the player idle, so it is prepared again when needed.
    fun setSubtitles(videoId: String, track: SubtitleTrack?) {
        val c = controller ?: return
        val idx = c.currentMediaItemIndex
        if (idx < 0) return
        val video = _queue.value.getOrNull(idx) ?: return
        if (video.videoId != videoId) return
        val current = c.getMediaItemAt(idx)
        if (current.mediaId != videoId) return

        val previous = _subtitle.value
        // Attaching the same track twice is a no-op; turning captions off must
        // always rebuild the item, because the session copy of it cannot be
        // trusted to carry the subtitle configuration.
        val alreadyAttached = track != null &&
            attachedSubtitleVideoId == videoId &&
            attachedSubtitleUrl == track.url
        if (track == null && previous == null && attachedSubtitleVideoId == null) return
        _subtitle.value = track
        if (track != null) captionRetryAttempted = false
        if (alreadyAttached) return

        val configs = if (track == null) emptyList() else listOf(subtitleConfiguration(track))
        // Keep a URI on the replacement even when the controller copy lost its
        // local configuration; without one the item cannot be played.
        val baseUri = currentStreamUrl?.toUri()
            ?: current.localConfiguration?.uri
            ?: "indio://stream/${video.videoId}".toUri()

        val wasPlaying = c.playWhenReady
        val position = c.currentPosition.coerceAtLeast(0)
        val duration = c.duration
        store.notePosition(video, position, if (duration > 0) duration else 0)
        replacingItem = true
        try {
            c.replaceMediaItem(
                idx,
                current.buildUpon()
                    .setUri(baseUri)
                    .setSubtitleConfigurations(configs)
                    .build(),
            )
            c.seekTo(idx, position)
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
        } finally {
            replacingItem = false
        }
        attachedSubtitleVideoId = if (track == null) null else videoId
        attachedSubtitleUrl = track?.url
        if (wasPlaying) c.play()
    }

    private fun subtitleConfiguration(track: SubtitleTrack): MediaItem.SubtitleConfiguration =
        MediaItem.SubtitleConfiguration.Builder(track.url.toUri())
            .setMimeType(track.mime)
            .setLanguage(track.languageTag)
            .setLabel(track.label)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .setRoleFlags(C.ROLE_FLAG_CAPTION)
            .build()

    fun stop() {
        val c = controller ?: return
        val v = _queue.value.getOrNull(c.currentMediaItemIndex)
        if (v != null) {
            val d = c.duration
            store.notePosition(v, c.currentPosition.coerceAtLeast(0), if (d > 0) d else 0)
        }
        c.stop()
    }

    // Dismiss the mini player entirely.
    fun clear() {
        val c = controller ?: return
        val v = _queue.value.getOrNull(c.currentMediaItemIndex)
        if (v != null) {
            val d = c.duration
            store.notePosition(v, c.currentPosition.coerceAtLeast(0), if (d > 0) d else 0)
        }
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
        _subtitle.value = null
        captionRetryAttempted = false
        attachedSubtitleVideoId = null
        attachedSubtitleUrl = null
        currentStreamUrl = null
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
