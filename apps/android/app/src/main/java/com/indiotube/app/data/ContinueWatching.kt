package com.indiotube.app.data

data class ContinueWatchingItem(
    val video: VideoItem,
    val fraction: Float,
    val remainingMs: Long,
) {
    val remainingText: String
        get() = if (remainingMs <= 0) "Almost done" else "${formatDuration(remainingMs / 1000)} left"
}

fun continueWatching(history: List<HistoryEntry>, limit: Int = 12): List<ContinueWatchingItem> {
    val seen = HashSet<String>()
    val out = ArrayList<ContinueWatchingItem>(limit)
    for (entry in history) {
        if (out.size >= limit) break
        val video = entry.video
        if (video.membersOnly || video.isLive) continue
        if (!seen.add(video.videoId)) continue
        val fraction = entry.watchedFraction() ?: continue
        if (fraction < 0.02f || fraction >= COMPLETED_FRACTION) {
            seen.remove(video.videoId)
            continue
        }
        val duration = if (entry.durationMs > 0) {
            entry.durationMs
        } else {
            video.durationSec * 1000L
        }
        if (duration < 20_000L) {
            seen.remove(video.videoId)
            continue
        }
        out += ContinueWatchingItem(
            video = video,
            fraction = fraction,
            remainingMs = (duration - entry.positionMs).coerceAtLeast(0L),
        )
    }
    return out
}
