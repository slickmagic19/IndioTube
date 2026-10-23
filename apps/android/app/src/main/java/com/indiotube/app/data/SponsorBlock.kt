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

// The segment to seek past automatically while the playhead sits inside it.
// Auto-skip is strictly opt-in: with it off this always returns null and the
// player keeps showing the manual "Jump ahead" pill instead. Segments already
// auto-skipped stay skipped for the rest of the video, so seeking back into a
// segment does not fight the user.
fun autoSkipTarget(
    segments: List<SponsorSegment>,
    positionMs: Long,
    skipped: Set<String>,
    autoSkip: Boolean,
): SponsorSegment? {
    if (!autoSkip) return null
    return segments.firstOrNull {
        it.UUID !in skipped && positionMs >= it.startMs && positionMs < it.endMs
    }
}

// Human label for a SponsorBlock category id, used by the skip pill and the
// settings list.
fun sponsorLabel(category: String): String = when (category) {
    "sponsor" -> "Sponsor"
    "selfpromo" -> "Self-promotion"
    "interaction" -> "Interaction reminder"
    "intro" -> "Intro"
    "outro" -> "Outro"
    "preview" -> "Preview"
    "music_offtopic" -> "Non-music section"
    "filler" -> "Filler"
    else -> category.replaceFirstChar { it.uppercase() }
}

// SponsorBlock's public API only needs the video id - no account, no key.
// Auto-skip is off by default; when it is off the player only surfaces the
// manual "Jump ahead" pill, the way YouTube Premium does.
class SponsorBlock(private val http: OkHttpClient) {

    private val cache = ConcurrentHashMap<String, List<SponsorSegment>>()

    suspend fun segments(
        videoId: String,
        categories: Set<String> = DEFAULT_SPONSOR_CATEGORIES,
        force: Boolean = false,
    ): List<SponsorSegment> {
        if (videoId.isBlank() || categories.isEmpty()) return emptyList()
        val key = cacheKey(videoId, categories)
        if (force) cache.remove(key)
        cache[key]?.let { return it }
        val loaded = try {
            withContext(Dispatchers.IO) { fetch(videoId, categories) }
        } catch (_: Exception) {
            // A transient failure must not poison the cache.
            return emptyList()
        }
        cache[key] = loaded
        return loaded
    }

    private fun cacheKey(videoId: String, categories: Set<String>): String =
        videoId + "|" + categories.sorted().joinToString(",")

    private fun fetch(videoId: String, categories: Set<String>): List<SponsorSegment> {
        val url = "https://sponsor.ajay.app/api/skipSegments".toHttpUrl().newBuilder()
            .addQueryParameter("videoID", videoId)
            .addQueryParameter(
                "categories",
                categories.sorted().joinToString(",", "[", "]") { "\"$it\"" },
            )
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

}
