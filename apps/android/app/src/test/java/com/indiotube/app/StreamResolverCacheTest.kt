package com.indiotube.app

import com.indiotube.app.data.ResolvedStream
import com.indiotube.app.data.StreamResolver
import com.indiotube.app.data.VideoQualities
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

// The player reads the resolver's cache: to start a video straight on a
// remembered quality, and to know whether the next queue item still needs
// resolving. That lookup must never touch the extractor, and it must stop
// serving a ladder whose URLs have had time to expire.
class StreamResolverCacheTest {

    private val resolver = StreamResolver(
        OkHttpClient.Builder().connectTimeout(1, TimeUnit.SECONDS).build(),
    )

    private val ladder = VideoQualities(
        options = emptyList(),
        auto = ResolvedStream("https://example.com/720", "mp4", "video/mp4", 720),
        audio = null,
    )

    @Test
    fun anUnknownVideoHasNoCachedLadder() {
        assertNull(resolver.cachedQualities("unknown"))
    }

    @Test
    fun aResolvedLadderIsServedFromTheCache() {
        resolver.cacheQualities("abc123", ladder)
        assertSame(ladder, resolver.cachedQualities("abc123"))
    }

    @Test
    fun aStaleEntryStopsBeingServed() {
        resolver.cacheQualities("abc123", ladder)
        val cachedAt = System.currentTimeMillis()
        assertSame(ladder, resolver.cachedQualities("abc123", cachedAt + 60_000))
        assertNull(
            "stream URLs live about six hours, so an older ladder is gone",
            resolver.cachedQualities("abc123", cachedAt + 6 * 3600_000L),
        )
    }

    @Test
    fun videosDoNotShareTheirLadders() {
        resolver.cacheQualities("abc123", ladder)
        assertNull(resolver.cachedQualities("def456"))
    }
}
