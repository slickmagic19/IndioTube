package com.indiotube.app.ui

import com.indiotube.app.Container
import com.indiotube.app.data.SearchEntry
import com.indiotube.app.data.VideoItem
import com.indiotube.app.data.publishAgeMinutes
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

// YouTube serves no recommendations to signed-out sessions, so the home feed
// is computed locally, mirroring the signals YouTube's own recommender uses:
//  - watch history (recency-weighted channel affinity)
//  - subscriptions and liked videos
//  - search history (both the searched-for topics and the videos those
//    searches surfaced)
//  - the "related" videos of the last few watches
//  - freshness and a strong demotion of already-watched videos
// Every refresh draws a new weighted sample from that candidate pool, so the
// mix genuinely changes between pulls while staying relevant — the way the
// real home feed behaves — instead of returning the same deterministic list.
// Diversity is capped per channel so one uploader can't take over the page.

internal data class FeedSignals(
    val affinity: Map<String, Double>,
    val subIds: Set<String>,
    val likedChannelIds: Set<String>,
    val watchedIds: Set<String>,
    val seedIds: Set<String>,
    val searchTerms: Set<String>,
    // Videos already watched to (near) the end. These sink much deeper than
    // merely-watched ones: YouTube stops pushing what you've finished.
    val completedIds: Set<String> = emptySet(),
    // Explicit "not interested" / "don't recommend channel" feedback removes
    // candidates outright, while "interested" gives the channel a lasting
    // boost even when it would otherwise be a weak candidate.
    val interestedChannelIds: Set<String> = emptySet(),
    val hiddenIds: Set<String> = emptySet(),
    val blockedChannelIds: Set<String> = emptySet(),
)

private val STOP_WORDS = setOf(
    "the", "and", "for", "with", "from", "that", "this", "you", "your", "how",
    "what", "why", "when", "who", "are", "was", "were", "will", "can", "not",
    "official", "video", "music", "song", "full", "new", "best", "top",
)

internal fun tokenizeQuery(query: String): List<String> = query
    .lowercase()
    .split(Regex("[^a-z0-9]+"))
    .filter { it.length >= 3 && it !in STOP_WORDS }

// Score every candidate, then draw `limit` videos with probability
// proportional to their score. `rotation` reseeds the draw, so consecutive
// refreshes surface a different mix while the ranking signals still dominate.
internal fun rankAndSample(
    pool: List<VideoItem>,
    signals: FeedSignals,
    rotation: Int,
    limit: Int = 80,
    maxPerChannel: Int = 3,
): List<VideoItem> {
    if (pool.isEmpty()) return emptyList()
    val candidates = pool.filterNot { v ->
        v.videoId in signals.hiddenIds ||
            (v.channelId != null && v.channelId in signals.blockedChannelIds)
    }
    if (candidates.isEmpty()) return emptyList()
    val rng = Random(rotation * 7919 + 104729)

    fun score(v: VideoItem): Double {
        val age = publishAgeMinutes(v.publishedText).toDouble()
        var s = 60_000.0 / (age + 480.0)
        val aff = signals.affinity[v.channelId] ?: 0.0
        if (aff > 0.0) s *= 1.0 + 1.6 * (aff / (aff + 2.5)).coerceAtMost(1.0)
        if (v.channelId != null && v.channelId in signals.subIds) s *= 2.2
        if (v.channelId != null && v.channelId in signals.likedChannelIds) s *= 1.15
        if (v.channelId != null && v.channelId in signals.interestedChannelIds) s *= 2.0
        if (v.videoId in signals.seedIds) s *= 2.3
        if (v.videoId in signals.watchedIds) s *= 0.12
        if (v.videoId in signals.completedIds) s *= 0.22
        if (signals.searchTerms.isNotEmpty()) {
            val title = v.title.lowercase()
            val matches = signals.searchTerms.count { title.contains(it) }
            if (matches > 0) s *= 1.0 + (0.45 * matches).coerceAtMost(1.2)
            val channel = v.channelName?.lowercase()
            if (channel != null && signals.searchTerms.any { channel.contains(it) }) s *= 1.25
        }
        // per-refresh perturbation: enough to reshuffle the page and swap
        // candidates in and out, small enough that the signals above still
        // decide what the feed is about
        s *= 0.72 + rng.nextDouble() * 0.56
        return s.coerceAtLeast(1e-6)
    }

    val scored = candidates.distinctBy { it.videoId }.map { it to score(it) }.toMutableList()
    val out = ArrayList<VideoItem>(limit)
    val overflow = ArrayList<VideoItem>()
    val perChannel = HashMap<String, Int>()

    while (scored.isNotEmpty()) {
        if (out.size >= limit) break
        var total = 0.0
        for ((_, w) in scored) total += w
        if (total <= 0.0) break
        var target = rng.nextDouble() * total
        var pick = scored.lastIndex
        for (i in scored.indices) {
            target -= scored[i].second
            if (target <= 0.0) {
                pick = i
                break
            }
        }
        val (video, _) = scored.removeAt(pick)
        val key = video.channelId ?: video.videoId
        val n = perChannel[key] ?: 0
        if (n >= maxPerChannel) {
            overflow += video
            continue
        }
        perChannel[key] = n + 1
        out += video
    }
    // Diversity first, then fill any remaining slots with the best of what
    // the per-channel cap pushed out, so a small candidate pool still fills
    // the page.
    for (v in overflow) {
        if (out.size >= limit) break
        out += v
    }
    return out
}

suspend fun Container.localRecommendations(force: Boolean = false): List<VideoItem> {
    homeRotation += 1
    val rotation = homeRotation
    val now = System.currentTimeMillis()

    val history = library.history.value.sortedByDescending { it.ts }
    val subs = accounts.subscriptions.value
    val liked = library.liked.value
    val searches = library.searches.value.sortedByDescending { it.ts }
    val likedChannels = liked.mapNotNull { it.channelId }.toSet()

    // Recency-weighted affinity per channel: what this account actually watches.
    val affinity = mutableMapOf<String, Double>()
    history.forEach { e ->
        val cid = e.video.channelId ?: return@forEach
        val daysOld = ((now - e.ts) / 86_400_000.0).coerceAtLeast(0.0)
        affinity[cid] = (affinity[cid] ?: 0.0) + 1.0 / (1.0 + daysOld * 0.7)
    }
    // Likes say a little more than a plain view.
    liked.forEach { v ->
        val cid = v.channelId ?: return@forEach
        affinity[cid] = (affinity[cid] ?: 0.0) + 0.8
    }

    val subIds = subs.map { it.channelId }.toSet()
    val topWatched = affinity.entries
        .sortedByDescending { it.value }
        .map { it.key }
        .filter { it !in subIds }
        .take(10)

    // Search history: topics the user asked for, and the videos those
    // searches surfaced (a local analogue of YouTube's search-based recs).
    val recentQueries = searches.map { it.query }.distinct().take(4)
    val searchTerms = searches.take(8).flatMap { tokenizeQuery(it.query) }.toSet()

    val related = coroutineScope {
        history.take(5).map { e ->
            async(Dispatchers.IO) {
                runCatching { innerTube.watch(e.video.videoId).related }.getOrElse { emptyList() }
            }
        }.awaitAll()
    }.flatten()
    val seedIds = related.map { it.videoId }.toSet()

    val searchVideos = coroutineScope {
        recentQueries.map { q ->
            async(Dispatchers.IO) {
                runCatching {
                    innerTube.search(q).entries
                        .filterIsInstance<SearchEntry.Video>()
                        .map { it.video }
                        .take(12)
                }.getOrElse { emptyList() }
            }
        }.awaitAll()
    }.flatten()

    val channelIds = (subs.map { it.channelId } + topWatched + likedChannels +
        library.interestedChannelIds.value).distinct()
    val fromChannels = latestFromChannels(channelIds, perChannel = 20, force = force)

    val pool = LinkedHashMap<String, VideoItem>()
    fromChannels.forEach { pool[it.videoId] = it }
    searchVideos.forEach { pool.putIfAbsent(it.videoId, it) }
    related.forEach { pool.putIfAbsent(it.videoId, it) }

    // Cards should always show a real channel name and avatar. Channel pages
    // sometimes omit the owner in each item, so fill gaps from what we know
    // about the channels this account subscribes to, watched or liked.
    val knownChannels = HashMap<String, Pair<String?, String?>>()
    subs.forEach { knownChannels[it.channelId] = it.name to it.avatar }
    (liked + history.map { it.video } + searchVideos).forEach { v ->
        val id = v.channelId ?: return@forEach
        val cur = knownChannels[id]
        knownChannels[id] = (cur?.first ?: v.channelName) to (cur?.second ?: v.channelAvatar)
    }
    pool.replaceAll { _, v ->
        val known = v.channelId?.let { knownChannels[it] }
        if (known != null && (v.channelName == null || v.channelAvatar == null)) {
            v.copy(
                channelName = v.channelName ?: known.first,
                channelAvatar = v.channelAvatar ?: known.second,
            )
        } else {
            v
        }
    }

    val signals = FeedSignals(
        affinity = affinity,
        subIds = subIds,
        likedChannelIds = likedChannels,
        watchedIds = history.map { it.video.videoId }.toSet(),
        seedIds = seedIds,
        searchTerms = searchTerms,
        completedIds = library.completedIds(),
        interestedChannelIds = library.interestedChannelIds.value,
        hiddenIds = library.notInterestedIds.value,
        blockedChannelIds = library.blockedChannelIds.value,
    )
    return rankAndSample(pool.values.toList(), signals, rotation)
}

private suspend fun Container.latestFromChannels(
    channelIds: List<String>,
    perChannel: Int,
    force: Boolean,
): List<VideoItem> {
    if (channelIds.isEmpty()) return emptyList()
    val sem = Semaphore(5)
    return coroutineScope {
        channelIds.take(60).map { id ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    runCatching {
                        innerTube.channelVideos(id, force = force).videos.take(perChannel)
                    }.getOrElse { emptyList() }
                }
            }
        }.awaitAll().flatten()
    }.distinctBy { it.videoId }
}
