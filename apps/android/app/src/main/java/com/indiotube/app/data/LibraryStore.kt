package com.indiotube.app.data

import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Per-account library: history, Watch Later and local playlists. Each local
// account gets its own file, so switching accounts swaps the whole library
// the way YouTube does.
class LibraryStore(
    private val dir: File,
    private val scope: CoroutineScope,
) {
    companion object {
        const val HISTORY_CAP = 500
    }

    private val mutex = Mutex()
    @Volatile private var currentAccountId: String = ""
    private val accountFile = { id: String -> File(dir, "library_$id.json") }

    private val _watchLater = MutableStateFlow<List<VideoItem>>(emptyList())
    val watchLater: StateFlow<List<VideoItem>> = _watchLater.asStateFlow()

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    private val _historyPaused = MutableStateFlow(false)
    val historyPaused: StateFlow<Boolean> = _historyPaused.asStateFlow()

    private val _playlists = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val playlists: StateFlow<List<LocalPlaylist>> = _playlists.asStateFlow()

    private val _liked = MutableStateFlow<List<VideoItem>>(emptyList())
    val liked: StateFlow<List<VideoItem>> = _liked.asStateFlow()

    private val _searches = MutableStateFlow<List<SearchLogEntry>>(emptyList())
    val searches: StateFlow<List<SearchLogEntry>> = _searches.asStateFlow()

    private val _notInterestedIds = MutableStateFlow<Set<String>>(emptySet())
    val notInterestedIds: StateFlow<Set<String>> = _notInterestedIds.asStateFlow()

    private val _interestedChannelIds = MutableStateFlow<Set<String>>(emptySet())
    val interestedChannelIds: StateFlow<Set<String>> = _interestedChannelIds.asStateFlow()

    private val _blockedChannelIds = MutableStateFlow<Set<String>>(emptySet())
    val blockedChannelIds: StateFlow<Set<String>> = _blockedChannelIds.asStateFlow()

    fun switchAccount(accountId: String) {
        if (accountId == currentAccountId && loaded) return
        currentAccountId = accountId
        loaded = false
        load()
    }

    @Volatile private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true
        try {
            val f = accountFile(currentAccountId)
            if (f.exists()) {
                val data = SyncJson.decodeFromString(LibraryFile.serializer(), f.readText())
                _watchLater.value = data.watchLater
                _historyPaused.value = data.historyPaused
                _history.value = data.history
                _playlists.value = data.playlists
                _liked.value = data.liked
                _searches.value = data.searches
                _notInterestedIds.value = data.notInterestedIds.toSet()
                _interestedChannelIds.value = data.interestedChannelIds.toSet()
                _blockedChannelIds.value = data.blockedChannelIds.toSet()
            } else {
                _watchLater.value = emptyList()
                _historyPaused.value = false
                _history.value = emptyList()
                _playlists.value = emptyList()
                _liked.value = emptyList()
                _searches.value = emptyList()
                _notInterestedIds.value = emptySet()
                _interestedChannelIds.value = emptySet()
                _blockedChannelIds.value = emptySet()
            }
        } catch (_: Exception) {
            try {
                accountFile(currentAccountId).renameTo(File(dir, "library_corrupt.json"))
            } catch (_: Exception) { }
            _watchLater.value = emptyList()
            _historyPaused.value = false
            _history.value = emptyList()
            _playlists.value = emptyList()
            _liked.value = emptyList()
            _searches.value = emptyList()
            _notInterestedIds.value = emptySet()
            _interestedChannelIds.value = emptySet()
            _blockedChannelIds.value = emptySet()
        }
    }

    private fun persist() {
        val data = LibraryFile(
            watchLater = _watchLater.value,
            historyPaused = _historyPaused.value,
            history = _history.value,
            playlists = _playlists.value,
            liked = _liked.value,
            searches = _searches.value,
            notInterestedIds = _notInterestedIds.value.toList(),
            interestedChannelIds = _interestedChannelIds.value.toList(),
            blockedChannelIds = _blockedChannelIds.value.toList(),
        )
        val target = accountFile(currentAccountId)
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val tmp = File(target.parentFile, target.name + ".tmp")
                    tmp.writeText(SyncJson.encodeToString(LibraryFile.serializer(), data))
                    if (!tmp.renameTo(target)) {
                        target.writeText(tmp.readText())
                        tmp.delete()
                    }
                } catch (_: Exception) { /* next mutation retries */ }
            }
        }
    }

    // ---- history -------------------------------------------------------------

    fun setHistoryPaused(paused: Boolean) {
        if (_historyPaused.value == paused) return
        _historyPaused.value = paused
        persist()
    }

    fun noteOpen(video: VideoItem) {
        // Paused history keeps the list frozen: no new rows, no timestamp or
        // progress churn on existing ones.
        if (_historyPaused.value) return
        val existing = _history.value.firstOrNull { it.video.videoId == video.videoId }
        val entry = HistoryEntry(
            video = video,
            ts = System.currentTimeMillis(),
            positionMs = existing?.positionMs ?: 0,
            durationMs = existing?.durationMs ?: 0,
        )
        val next = listOf(entry) + _history.value.filterNot { it.video.videoId == video.videoId }
        _history.value = next.take(HISTORY_CAP)
        persist()
    }

    // Throttled progress save from the player. The player's duration is kept
    // alongside the position so thumbnails can draw the watched-progress bar;
    // this also un-throttles the very first save once the duration is known.
    fun notePosition(video: VideoItem, positionMs: Long, durationMs: Long = 0) {
        if (_historyPaused.value) return
        val idx = _history.value.indexOfFirst { it.video.videoId == video.videoId }
        if (idx < 0) return
        val entry = _history.value[idx]
        val samePosition = kotlin.math.abs(entry.positionMs - positionMs) < 5000
        val sameDuration = durationMs <= 0 || entry.durationMs == durationMs
        if (samePosition && sameDuration) return
        val updated = entry.copy(
            positionMs = positionMs,
            durationMs = if (durationMs > 0) durationMs else entry.durationMs,
            ts = System.currentTimeMillis(),
        )
        _history.value = listOf(updated) + _history.value.filterNot { it.video.videoId == video.videoId }
        persist()
    }

    fun resumePosition(videoId: String): Long =
        _history.value.firstOrNull { it.video.videoId == videoId }?.positionMs ?: 0

    // How much of a video was watched (0..1), from history; null when it has
    // no measurable progress. Used for the red progress line on cards.
    fun watchedFraction(videoId: String): Float? = _history.value
        .firstOrNull { it.video.videoId == videoId }
        ?.watchedFraction()

    // Videos whose playback reached (near) the end. The local recommender
    // sinks these, like YouTube stops pushing what you already finished.
    fun completedIds(): Set<String> = _history.value
        .filter { (it.watchedFraction() ?: 0f) >= COMPLETED_FRACTION }
        .map { it.video.videoId }
        .toSet()

    fun removeFromHistory(videoId: String) {
        _history.value = _history.value.filterNot { it.video.videoId == videoId }
        persist()
    }

    fun clearHistory() {
        _history.value = emptyList()
        persist()
    }

    // Fills in gaps on stored copies of a video once the watch page has full
    // metadata (real channel photo, name, duration, view count). Entries saved
    // from older builds or bare feed cards keep the placeholder otherwise.
    fun enrichVideo(video: VideoItem): Boolean {
        var changed = false
        fun merge(old: VideoItem): VideoItem {
            val merged = old.copy(
                title = old.title.ifBlank { video.title },
                channelId = old.channelId ?: video.channelId,
                channelName = old.channelName ?: video.channelName,
                channelAvatar = old.channelAvatar ?: video.channelAvatar,
                thumbnail = old.thumbnail ?: video.thumbnail,
                durationSec = if (old.durationSec > 0) old.durationSec else video.durationSec,
                viewsText = old.viewsText ?: video.viewsText,
                publishedText = old.publishedText ?: video.publishedText,
            )
            if (merged != old) changed = true
            return merged
        }

        val history = _history.value.map { e ->
            if (e.video.videoId == video.videoId) e.copy(video = merge(e.video)) else e
        }
        val watchLater = _watchLater.value.map { if (it.videoId == video.videoId) merge(it) else it }
        val liked = _liked.value.map { if (it.videoId == video.videoId) merge(it) else it }
        val playlists = _playlists.value.map { pl ->
            if (pl.videos.none { it.videoId == video.videoId }) pl
            else pl.copy(videos = pl.videos.map { if (it.videoId == video.videoId) merge(it) else it })
        }
        if (changed) {
            _history.value = history
            _watchLater.value = watchLater
            _liked.value = liked
            _playlists.value = playlists
            persist()
        }
        return changed
    }

    // ---- search history ---------------------------------------------------------

    fun noteSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val next = listOf(SearchLogEntry(q, System.currentTimeMillis())) +
            _searches.value.filterNot { it.query.equals(q, true) }
        _searches.value = next.take(200)
        persist()
    }

    fun clearSearches() {
        _searches.value = emptyList()
        persist()
    }

    // ---- watch later -----------------------------------------------------------

    fun isInWatchLater(videoId: String): Boolean = _watchLater.value.any { it.videoId == videoId }

    // Returns true when the video ended up saved (false = removed).
    fun toggleWatchLater(video: VideoItem): Boolean {
        val present = isInWatchLater(video.videoId)
        _watchLater.value = if (present) {
            _watchLater.value.filterNot { it.videoId == video.videoId }
        } else {
            listOf(video) + _watchLater.value
        }
        persist()
        return !present
    }

    // ---- liked ------------------------------------------------------------------

    fun isLiked(videoId: String): Boolean = _liked.value.any { it.videoId == videoId }

    // Returns true when the video ended up liked (false = unliked).
    fun toggleLike(video: VideoItem): Boolean {
        val present = isLiked(video.videoId)
        _liked.value = if (present) {
            _liked.value.filterNot { it.videoId == video.videoId }
        } else {
            listOf(video) + _liked.value
        }
        persist()
        return !present
    }

    // ---- playlists -------------------------------------------------------------

    fun createPlaylist(name: String): LocalPlaylist {
        val clean = name.trim().ifEmpty { "New playlist" }
        val pl = LocalPlaylist(UUID.randomUUID().toString(), clean, System.currentTimeMillis())
        _playlists.value = listOf(pl) + _playlists.value
        persist()
        return pl
    }

    fun renamePlaylist(id: String, name: String) {
        val clean = name.trim().ifEmpty { return }
        _playlists.value = _playlists.value.map { if (it.id == id) it.copy(name = clean) else it }
        persist()
    }

    fun deletePlaylist(id: String) {
        _playlists.value = _playlists.value.filterNot { it.id == id }
        persist()
    }

    fun playlist(id: String): LocalPlaylist? = _playlists.value.firstOrNull { it.id == id }

    fun addToPlaylist(playlistId: String, video: VideoItem) {
        _playlists.value = _playlists.value.map { pl ->
            if (pl.id != playlistId) pl
            else if (pl.videos.any { it.videoId == video.videoId }) pl
            else pl.copy(videos = pl.videos + video)
        }
        persist()
    }

    fun removeFromPlaylist(playlistId: String, videoId: String) {
        _playlists.value = _playlists.value.map { pl ->
            if (pl.id != playlistId) pl else pl.copy(videos = pl.videos.filterNot { it.videoId == videoId })
        }
        persist()
    }

    // ---- recommendation feedback ---------------------------------------------

    fun markNotInterested(video: VideoItem) {
        _notInterestedIds.value = _notInterestedIds.value + video.videoId
        video.channelId?.let { _interestedChannelIds.value = _interestedChannelIds.value - it }
        persist()
    }

    fun undoNotInterested(videoId: String) {
        _notInterestedIds.value = _notInterestedIds.value - videoId
        persist()
    }

    fun markInterested(video: VideoItem) {
        _notInterestedIds.value = _notInterestedIds.value - video.videoId
        video.channelId?.let { _interestedChannelIds.value = _interestedChannelIds.value + it }
        persist()
    }

    fun blockChannel(video: VideoItem) {
        val channelId = video.channelId ?: return
        _blockedChannelIds.value = _blockedChannelIds.value + channelId
        _interestedChannelIds.value = _interestedChannelIds.value - channelId
        persist()
    }

    fun unblockChannel(channelId: String) {
        _blockedChannelIds.value = _blockedChannelIds.value - channelId
        persist()
    }
}
