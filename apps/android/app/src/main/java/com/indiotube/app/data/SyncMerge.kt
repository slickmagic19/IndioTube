package com.indiotube.app.data

import kotlinx.serialization.Serializable
import java.util.UUID

// Phone side of the desktop pairing contract. This file is the exact Kotlin
// mirror of the desktop's electron/syncCore.js — same unions, same newest-ts
// history rule, same stats names — and both platforms run the same test
// vectors (SyncMergeTest.kt vs apps/desktop/test/syncCore.test.ts).
//
// Rule #1: sync only ever ADDS. Nothing local is removed or overwritten by
// an incoming payload; merging the same payload twice is a no-op.

@Serializable
data class SyncSnapshot(
    val indio: String = "sync",
    val v: Int = 1,
    val name: String = "",
    val library: LibraryFile = LibraryFile(),
    val subscriptions: List<Subscription> = emptyList(),
)

@Serializable
data class SyncPairing(
    val indio: String = "",
    val v: Int = 1,
    val host: String = "",
    val port: Int = 0,
    val token: String = "",
    val name: String? = null,
)

data class SyncStats(
    var historyAdded: Int = 0,
    var watchLaterAdded: Int = 0,
    var likedAdded: Int = 0,
    var playlistsAdded: Int = 0,
    var playlistsMerged: Int = 0,
    var videosAdded: Int = 0,
    var subsAdded: Int = 0,
) {
    val total: Int
        get() = historyAdded + watchLaterAdded + likedAdded + playlistsAdded +
            playlistsMerged + videosAdded + subsAdded
}

fun normName(s: String): String = s.trim().lowercase().replace(Regex("\\s+"), " ")

data class SyncMergeResult(
    val library: LibraryFile,
    val subscriptions: List<Subscription>,
    val stats: SyncStats,
)

object SyncMerge {

    // Add-only union for a newest-first video list: remote entries the local
    // side does not have are prepended in their own order.
    private fun unionVideos(local: List<VideoItem>, incoming: List<VideoItem>): Pair<List<VideoItem>, Int> {
        val have = local.map { it.videoId }.toHashSet()
        val additions = incoming.filter { it.videoId.isNotEmpty() && have.add(it.videoId) }
        return (additions + local) to additions.size
    }

    fun merge(
        library: LibraryFile,
        subscriptions: List<Subscription>,
        incoming: SyncSnapshot,
    ): SyncMergeResult {
        val stats = SyncStats()

        val (watchLater, watchLaterAdded) = unionVideos(library.watchLater, incoming.library.watchLater)
        stats.watchLaterAdded = watchLaterAdded
        val (liked, likedAdded) = unionVideos(library.liked, incoming.library.liked)
        stats.likedAdded = likedAdded

        // History: keyed by videoId, newest ts wins (position/duration travel
        // with it so resume works across devices). ts-desc, capped.
        val byId = LinkedHashMap<String, HistoryEntry>()
        for (e in library.history) if (e.video.videoId.isNotEmpty()) byId[e.video.videoId] = e
        var historyAdded = 0
        for (e in incoming.library.history) {
            if (e.video.videoId.isEmpty()) continue
            val hit = byId[e.video.videoId]
            if (hit == null) {
                byId[e.video.videoId] = e
                historyAdded += 1
            } else if (e.ts > hit.ts || (e.ts == hit.ts && e.positionMs > hit.positionMs)) {
                byId[e.video.videoId] = e
            }
        }
        stats.historyAdded = historyAdded
        val history = byId.values.sortedByDescending { it.ts }.take(LibraryStore.HISTORY_CAP)

        // Playlists match by normalized name; ids are minted locally, never
        // taken from the wire.
        val playlists = library.playlists.map { it.copy(videos = it.videos.toMutableList()) }.toMutableList()
        val byName = HashMap<String, Int>()
        playlists.forEachIndexed { i, pl -> byName.putIfAbsent(normName(pl.name), i) }
        for (inc in incoming.library.playlists) {
            val name = inc.name.trim()
            if (name.isEmpty()) continue
            val incVideos = inc.videos.filter { it.videoId.isNotEmpty() }
            val idx = byName[normName(name)]
            if (idx == null) {
                val videos = mutableListOf<VideoItem>()
                val seen = HashSet<String>()
                for (v in incVideos) if (seen.add(v.videoId)) { videos.add(v); stats.videosAdded += 1 }
                playlists.add(LocalPlaylist(UUID.randomUUID().toString(), name, inc.createdAt, videos))
                byName[normName(name)] = playlists.size - 1
                stats.playlistsAdded += 1
            } else {
                val pl = playlists[idx]
                val have = pl.videos.map { it.videoId }.toHashSet()
                var added = 0
                val merged = pl.videos.toMutableList()
                for (v in incVideos) if (have.add(v.videoId)) { merged.add(v); added += 1 }
                if (added > 0) {
                    playlists[idx] = pl.copy(videos = merged)
                    stats.playlistsMerged += 1
                    stats.videosAdded += added
                }
            }
        }

        // Subscriptions union by channelId; remote-only rows are prepended
        // in their own order (exact mirror of the desktop's additions + local).
        val subIds = subscriptions.map { it.channelId }.toHashSet()
        val subAdditions = incoming.subscriptions.filter { it.channelId.isNotEmpty() && subIds.add(it.channelId) }
        stats.subsAdded = subAdditions.size
        val subs = subAdditions + subscriptions

        val mergedLibrary = library.copy(
            watchLater = watchLater,
            history = history,
            playlists = playlists,
            liked = liked,
        )
        return SyncMergeResult(mergedLibrary, subs, stats)
    }

    // The payload shipped to the PC: only the synced subset, playlist ids
    // blanked (the desktop mints its own).
    fun snapshot(deviceName: String, library: LibraryFile, subscriptions: List<Subscription>): SyncSnapshot {
        val trimmed = library.copy(
            searches = emptyList(),
            notInterestedIds = emptyList(),
            interestedChannelIds = emptyList(),
            blockedChannelIds = emptyList(),
            playlists = library.playlists.map { it.copy(id = "") },
        )
        return SyncSnapshot(
            indio = "sync",
            v = 1,
            name = deviceName.take(48),
            library = trimmed,
            subscriptions = subscriptions,
        )
    }

    fun summary(s: SyncStats): String {
        val parts = mutableListOf<String>()
        if (s.historyAdded > 0) parts.add("+${s.historyAdded} history")
        if (s.watchLaterAdded > 0) parts.add("+${s.watchLaterAdded} watch later")
        if (s.likedAdded > 0) parts.add("+${s.likedAdded} liked")
        if (s.playlistsAdded > 0) parts.add("+${s.playlistsAdded} playlists")
        if (s.playlistsMerged > 0) parts.add("${s.playlistsMerged} playlists updated")
        if (s.subsAdded > 0) parts.add("+${s.subsAdded} subscriptions")
        return if (parts.isNotEmpty()) parts.joinToString(" · ") else "already in sync"
    }
}
