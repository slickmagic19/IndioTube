package com.indiotube.app.data

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

// Per-account watch statistics. YouTube's own "watch time" card is a single
// number; this keeps the ledger locally so the app can show days, streaks,
// top channels and the time playback speed saved.
//
// The ledger only ever grows while the player reports `isPlaying`, so a paused
// or buffering video never inflates it.

@Serializable
data class ChannelWatch(
    val name: String = "",
    val ms: Long = 0,
)

@Serializable
data class WatchStats(
    val totalMs: Long = 0,
    // Time actually saved by watching above 1x.
    val savedMs: Long = 0,
    // "2026-11-01" (local date) -> ms watched that day.
    val byDay: Map<String, Long> = emptyMap(),
    // channelId -> time watched on that channel.
    val byChannel: Map<String, ChannelWatch> = emptyMap(),
    val longestSessionMs: Long = 0,
    val sessions: Int = 0,
) {
    val activeDays: Int get() = byDay.size

    // The first day anything was watched; the page shows it as "tracking since".
    val firstDay: String? get() = byDay.keys.minOrNull()
}

// Bounds keep the file small on a device that has been used for years.
internal const val STATS_DAY_CAP = 400
internal const val STATS_CHANNEL_CAP = 120

// Pure ledger updates, kept separate from the store so the arithmetic can be
// tested without a player or a file.
fun WatchStats.plusWatch(
    ms: Long,
    day: String,
    channelId: String?,
    channelName: String?,
    saved: Long = 0,
): WatchStats {
    if (ms <= 0) return this
    val next = copy(
        totalMs = totalMs + ms,
        savedMs = savedMs + saved.coerceAtLeast(0),
        byDay = byDay + (day to ((byDay[day] ?: 0L) + ms)),
    )
    val key = channelId ?: channelName?.takeIf { it.isNotBlank() }
    val withChannel = if (key == null) {
        next
    } else {
        val previous = next.byChannel[key]
        val name = channelName?.takeIf { it.isNotBlank() } ?: previous?.name.orEmpty()
        next.copy(byChannel = next.byChannel + (key to ChannelWatch(name, (previous?.ms ?: 0L) + ms)))
    }
    return withChannel.trimmed()
}

fun WatchStats.plusSession(sessionMs: Long): WatchStats {
    if (sessionMs <= 0) return this
    return copy(
        sessions = sessions + 1,
        longestSessionMs = maxOf(longestSessionMs, sessionMs),
    )
}

private fun WatchStats.trimmed(): WatchStats {
    val days = if (byDay.size > STATS_DAY_CAP) {
        byDay.entries.sortedByDescending { it.key }.take(STATS_DAY_CAP).associate { it.key to it.value }
    } else {
        byDay
    }
    val channels = if (byChannel.size > STATS_CHANNEL_CAP) {
        byChannel.entries.sortedByDescending { it.value.ms }.take(STATS_CHANNEL_CAP)
            .associate { it.key to it.value }
    } else {
        byChannel
    }
    return if (days === byDay && channels === byChannel) this else copy(byDay = days, byChannel = channels)
}

// ---- day arithmetic and formatting -------------------------------------------

fun dayKey(ts: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(ts).atZone(zone).toLocalDate().toString()

fun localDate(ts: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()

// Oldest first, so a chart can map it straight onto columns.
fun lastDayKeys(days: Int, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<String> {
    if (days <= 0) return emptyList()
    val today = localDate(now, zone)
    return (days - 1 downTo 0).map { today.minusDays(it.toLong()).toString() }
}

fun lastDays(
    stats: WatchStats,
    days: Int,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<Pair<String, Long>> = lastDayKeys(days, now, zone).map { key -> key to (stats.byDay[key] ?: 0L) }

fun todayMs(stats: WatchStats, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    stats.byDay[dayKey(now, zone)] ?: 0L

fun weekMs(stats: WatchStats, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    lastDays(stats, 7, now, zone).sumOf { it.second }

// Consecutive days with watch time, counting back from today. A day that is
// still in progress (or just ended) keeps yesterday's streak alive, the way a
// step streak behaves before midnight.
fun watchStreak(
    stats: WatchStats,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): Int {
    var day = localDate(now, zone)
    if ((stats.byDay[day.toString()] ?: 0L) <= 0) day = day.minusDays(1)
    var streak = 0
    while ((stats.byDay[day.toString()] ?: 0L) > 0) {
        streak += 1
        day = day.minusDays(1)
    }
    return streak
}

fun averageActiveDayMs(stats: WatchStats): Long =
    if (stats.activeDays == 0) 0L else stats.totalMs / stats.activeDays

fun topChannels(stats: WatchStats, limit: Int = 5): List<ChannelWatch> =
    stats.byChannel.values.filter { it.ms > 0 }.sortedByDescending { it.ms }.take(limit)

// "12m", "1h 5m", "3h": compact enough for a stat card.
fun formatWatchTime(ms: Long): String {
    if (ms <= 0) return "0m"
    val minutes = ms / 60_000
    if (minutes <= 0) return "<1m"
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours <= 0 -> "${rest}m"
        rest == 0L -> "${hours}h"
        else -> "${hours}h ${rest}m"
    }
}

// Weekday initial for the 7-day chart ("M", "T", ...), locale-independent.
fun dayInitial(day: String): String =
    runCatching { LocalDate.parse(day).dayOfWeek.name.first().toString() }.getOrDefault("?")

// ---- persistence ---------------------------------------------------------------

// One file per local account, mirroring LibraryStore, so switching accounts
// switches the statistics with it.
class WatchStatsStore(
    private val dir: File,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val writeSeq = AtomicLong(0)
    @Volatile private var lastAttemptedSeq = 0L
    @Volatile private var accountId: String = ""
    @Volatile private var loaded = false
    @Volatile private var lastWriteAt = 0L

    private val _stats = MutableStateFlow(WatchStats())
    val stats: StateFlow<WatchStats> = _stats.asStateFlow()

    private fun fileFor(id: String) = File(dir, "stats_$id.json")

    fun switchAccount(id: String) {
        if (id == accountId) return
        if (accountId.isNotEmpty()) persist()
        accountId = id
        loaded = false
        load()
    }

    fun load() {
        if (loaded) return
        loaded = true
        if (accountId.isEmpty()) {
            _stats.value = WatchStats()
            return
        }
        _stats.value = try {
            val f = fileFor(accountId)
            if (f.exists()) {
                SyncJson.decodeFromString(WatchStats.serializer(), f.readText())
            } else {
                WatchStats()
            }
        } catch (_: Exception) {
            // Corrupted ledger — start a fresh one instead of crashing.
            WatchStats()
        }
    }

    // Fed from the player tick only while it is actually playing.
    fun addWatchTime(ms: Long, video: VideoItem, speed: Float) {
        if (ms <= 0) return
        val now = System.currentTimeMillis()
        _stats.value = _stats.value.plusWatch(
            ms = ms,
            day = dayKey(now),
            channelId = video.channelId,
            channelName = video.channelName,
            saved = timeSavedBySpeed(ms, speed),
        )
        if (now - lastWriteAt >= WRITE_INTERVAL_MS) persist()
    }

    // A continuous stretch of playback ended; keep the longest one.
    fun endSession(sessionMs: Long) {
        if (sessionMs < 1_000) return
        _stats.value = _stats.value.plusSession(sessionMs)
        persist()
    }

    fun clear() {
        _stats.value = WatchStats()
        persist()
    }

    fun flush() {
        if (accountId.isNotEmpty()) persist()
    }

    private fun persist() {
        val id = accountId
        if (id.isEmpty()) return
        lastWriteAt = System.currentTimeMillis()
        val snapshot = _stats.value
        val target = fileFor(id)
        val seq = writeSeq.incrementAndGet()
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                if (seq <= lastAttemptedSeq) return@withLock
                try {
                    val tmp = File(target.parentFile, target.name + ".tmp")
                    tmp.writeText(SyncJson.encodeToString(WatchStats.serializer(), snapshot))
                    if (!tmp.renameTo(target)) {
                        target.writeText(tmp.readText())
                        tmp.delete()
                    }
                } catch (_: Exception) {
                    /* the next tick retries */
                } finally {
                    lastAttemptedSeq = maxOf(lastAttemptedSeq, seq)
                }
            }
        }
    }

    private companion object {
        // The player writes every 500ms; the file only needs to catch up
        // occasionally, and each write rewrites the whole (small) ledger.
        const val WRITE_INTERVAL_MS = 20_000L
    }
}
