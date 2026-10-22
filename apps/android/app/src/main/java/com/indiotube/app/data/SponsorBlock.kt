package com.indiotube.app.data

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

// A crowd-sourced skip segment from SponsorBlock. The API reports seconds.
@Serializable
data class SponsorSegment(
    val category: String,
    val segment: List<Double>,
    val UUID: String = "",
) {
    val startMs: Long get() = ((segment.getOrNull(0) ?: 0.0) * 1000).toLong()
    val endMs: Long get() = ((segment.getOrNull(1) ?: 0.0) * 1000).toLong()
}

// The segment covering this playback position, if any. Dismissed segments
// stay dismissed for the current video so the button doesn't nag.
fun sponsorSegmentAt(
    segments: List<SponsorSegment>,
    positionMs: Long,
    dismissed: Set<String> = emptySet(),
): SponsorSegment? = segments.firstOrNull {
    it.UUID !in dismissed && positionMs >= it.startMs && positionMs < it.endMs
}

// SponsorBlock's public API only needs the video id - no account, no key.
// We show a "Jump ahead" pill instead of seeking automatically, the way
// YouTube Premium surfaces its own jump-ahead suggestion.
class SponsorBlock(private val http: OkHttpClient) {

    private val cache = ConcurrentHashMap<String, List<SponsorSegment>>()

    suspend fun segments(videoId: String, force: Boolean = false): List<SponsorSegment> {
        if (videoId.isBlank()) return emptyList()
        if (force) cache.remove(videoId)
        cache[videoId]?.let { return it }
        val loaded = try {
            withContext(Dispatchers.IO) { fetch(videoId) }
        } catch (_: Exception) {
            // A transient failure must not poison the cache.
            return emptyList()
        }
        cache[videoId] = loaded
        return loaded
    }

    private fun fetch(videoId: String): List<SponsorSegment> {
        val url = "https://sponsor.ajay.app/api/skipSegments".toHttpUrl().newBuilder()
            .addQueryParameter("videoID", videoId)
            .addQueryParameter("categories", CATEGORIES)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "IndioTube")
            .build()
        http.newCall(request).execute().use { response ->
            // 404 is the normal "nobody submitted segments" answer.
            if (!response.isSuccessful) return emptyList()
            return parseSponsorSegments(response.body?.string().orEmpty())
        }
    }

    internal fun parseSponsorSegments(body: String): List<SponsorSegment> =
        SyncJson.decodeFromString<List<SponsorSegment>>(body)
            .filter { it.segment.size >= 2 && it.endMs > it.startMs }
            .sortedBy { it.startMs }

    private companion object {
        // Same categories the browser extension skips: paid promo, self-promo,
        // interaction asks, intro and outro.
        const val CATEGORIES = """["sponsor","selfpromo","interaction","intro","outro"]"""
    }
}
