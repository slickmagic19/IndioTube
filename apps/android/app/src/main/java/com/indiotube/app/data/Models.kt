package com.indiotube.app.data

import kotlinx.serialization.Serializable

@Serializable
data class VideoItem(
    val videoId: String,
    val title: String = "",
    val channelName: String? = null,
    val channelId: String? = null,
    val channelAvatar: String? = null,
    val thumbnail: String? = null,
    val durationSec: Long = 0,
    val viewsText: String? = null,
    val publishedText: String? = null,
    val isLive: Boolean = false,
) {
    val durationText: String
        get() = when {
            isLive -> "LIVE"
            durationSec > 0 -> formatDuration(durationSec)
            else -> ""
        }

    // graffiti of the meta line: "Channel · 1.2M views · 3 days ago"
    fun metaLine(): String = listOfNotNull(
        channelName?.takeIf { it.isNotBlank() },
        viewsText?.takeIf { it.isNotBlank() },
        publishedText?.takeIf { it.isNotBlank() },
    ).joinToString(" • ")
}

fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return ""
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%d:%02d".format(m, s)
    }
}

fun parseDuration(text: String?): Long {
    if (text.isNullOrBlank()) return 0
    val parts = text.trim().split(":").mapNotNull { it.trim().toLongOrNull() }
    return when (parts.size) {
        3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
        2 -> parts[0] * 60 + parts[1]
        1 -> parts[0]
        else -> 0
    }
}

@Serializable
data class ChannelItem(
    val channelId: String,
    val name: String = "",
    val avatar: String? = null,
    val subscribersText: String? = null,
    val videoCountText: String? = null,
    val description: String? = null,
)

@Serializable
data class PlaylistItem(
    val playlistId: String,
    val title: String = "",
    val channelName: String? = null,
    val channelId: String? = null,
    val thumbnail: String? = null,
    val videoCountText: String? = null,
)

data class CommentItem(
    val id: String,
    val author: String,
    val avatar: String?,
    val text: String,
    val likesText: String?,
    val publishedText: String?,
    val replyCountText: String?,
    // Continuation token that loads this comment's replies, null when none.
    val repliesToken: String? = null,
)

// ---- local accounts (no sign-in: these are on-device profiles) --------------

@Serializable
data class Subscription(
    val channelId: String,
    val name: String = "",
    val avatar: String? = null,
    val at: Long = 0,
)

@Serializable
data class Account(
    val id: String,
    val name: String,
    val createdAt: Long = 0,
    val subscriptions: List<Subscription> = emptyList(),
)

@Serializable
data class AccountsFile(
    val accounts: List<Account> = emptyList(),
    val activeId: String = "",
)

// ---- per-account library -----------------------------------------------------

@Serializable
data class HistoryEntry(
    val video: VideoItem,
    val ts: Long = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

// Progress through a history entry (0..1), fed by the player's position and
// duration. Null when the entry can't be measured (never played, or no
// duration known at all). Drives the red "watched" bar on thumbnails.
fun HistoryEntry.watchedFraction(): Float? {
    if (positionMs <= 0) return null
    val duration = if (durationMs > 0) durationMs else video.durationSec * 1000L
    if (duration <= 0) return null
    return (positionMs.toDouble() / duration).coerceIn(0.0, 1.0).toFloat()
}

// A video counts as finished once playback got this far in — the local
// recommender sinks finished videos and a re-watch starts from the top again.
const val COMPLETED_FRACTION = 0.92f

@Serializable
data class LocalPlaylist(
    val id: String,
    val name: String,
    val createdAt: Long = 0,
    val videos: List<VideoItem> = emptyList(),
)

@Serializable
data class LibraryFile(
    val watchLater: List<VideoItem> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val playlists: List<LocalPlaylist> = emptyList(),
    val liked: List<VideoItem> = emptyList(),
    val searches: List<SearchLogEntry> = emptyList(),
    // Recommendation feedback, persisted per account like the rest of the
    // library so switching accounts also switches the feed's taste.
    val notInterestedIds: List<String> = emptyList(),
    val interestedChannelIds: List<String> = emptyList(),
    val blockedChannelIds: List<String> = emptyList(),
)

// What this account searched for. Feeds the local recommender the way
// YouTube uses search history as a ranking signal.
@Serializable
data class SearchLogEntry(
    val query: String,
    val ts: Long = 0,
)

// "2 days ago" → a rough age in minutes, for merging subscription feeds.
fun publishAgeMinutes(publishedText: String?): Long {
    val t = publishedText?.lowercase() ?: return Long.MAX_VALUE / 2
    val n = Regex("(\\d+)\\s*(second|minute|hour|day|week|month|year)")
        .find(t)?.groupValues ?: return Long.MAX_VALUE / 2
    val value = n[1].toLongOrNull() ?: return Long.MAX_VALUE / 2
    return when {
        n[2].startsWith("second") -> value / 60
        n[2].startsWith("minute") -> value
        n[2].startsWith("hour") -> value * 60
        n[2].startsWith("day") -> value * 60 * 24
        n[2].startsWith("week") -> value * 60 * 24 * 7
        n[2].startsWith("month") -> value * 60 * 24 * 30
        n[2].startsWith("year") -> value * 60 * 24 * 365
        else -> Long.MAX_VALUE / 2
    }
}

// ---- downloads ----------------------------------------------------------------

@Serializable
data class SavedDownload(
    val video: VideoItem,
    val bytes: Long,
    val at: Long,
    val fileName: String = "",
    val thumbFile: String? = null,
)

// Persisted spine of an in-flight download; lets a download killed with the
// process come back as a resumable paused task instead of vanishing.
@Serializable
data class QueueEntry(val video: VideoItem, val totalBytes: Long = 0, val at: Long = 0)
