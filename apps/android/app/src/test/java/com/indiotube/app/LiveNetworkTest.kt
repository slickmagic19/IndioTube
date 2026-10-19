package com.indiotube.app

import com.indiotube.app.data.InnerTube
import com.indiotube.app.data.SearchEntry
import com.indiotube.app.data.SearchFilter
import com.indiotube.app.data.StreamResolver
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Live smoke tests against real YouTube. No mocks, no fixtures that rot: the
// only honest way to verify the InnerTube parsers and the NewPipeExtractor
// stream resolution still work against the real service.
class LiveNetworkTest {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val innerTube = InnerTube(http)

    // YouTube intentionally serves no recommendations to signed-out users
    // (the "Try searching to get started" nudge). Home may legitimately be
    // empty; if the feed is ever populated, everything in it must be
    // well-formed.
    private val videoId = "dQw4w9WgXcQ"

    @Test
    fun homeFeedParses() = runBlocking {
        val home = innerTube.home()
        assertTrue("videos should have titles", home.videos.all { it.title.isNotBlank() })
        assertTrue("videos should have thumbnails", home.videos.all { !it.thumbnail.isNullOrBlank() })
        assertTrue("chips should have titles", home.chips.all { it.title.isNotBlank() })
    }

    @Test
    fun searchParsesVideos() = runBlocking {
        val results = innerTube.search("lofi hip hop")
        assertTrue("search should return entries", results.entries.isNotEmpty())
        assertTrue("expect at least one video", results.entries.any { it is SearchEntry.Video })
    }

    @Test
    fun searchChannelFilterParsesChannels() = runBlocking {
        val results = innerTube.search("lofi girl", SearchFilter.CHANNELS)
        assertTrue("channel search should return entries", results.entries.isNotEmpty())
        assertTrue("expect at least one channel", results.entries.any { it is SearchEntry.Channel })
    }

    @Test
    fun watchPageParses() = runBlocking {
        val watch = innerTube.watch(videoId)
        assertTrue("watch title should parse", watch.title.isNotBlank())
        assertTrue("related videos expected", watch.related.isNotEmpty())
        assertNotNull("comments entry point expected", watch.commentsToken)
    }

    @Test
    fun commentsParse() = runBlocking {
        val watch = innerTube.watch(videoId)
        val token = watch.commentsToken
        assertNotNull("comments token expected", token)
        val page = innerTube.comments(token!!)
        assertTrue("comments expected", page.comments.isNotEmpty())
        assertTrue("comment text expected", page.comments.all { it.text.isNotBlank() })
    }

    @Test
    fun streamResolvesToPlayableUrl() = runBlocking {
        StreamResolver.init(http)
        val resolver = StreamResolver(http)
        val stream = resolver.resolveBlocking(videoId)
        assertTrue("stream url expected", stream.url.startsWith("http"))
        assertTrue("muxed stream should carry video", stream.height > 0)
    }

    @Test
    fun channelVideosCarryChannelInfo() = runBlocking {
        val results = innerTube.search("lofi girl", SearchFilter.CHANNELS)
        val channel = results.entries.filterIsInstance<SearchEntry.Channel>().first().channel
        val page = innerTube.channelVideos(channel.channelId)
        assertTrue("channel name parsed", page.channel.name.isNotBlank())
        assertTrue("videos expected", page.videos.isNotEmpty())
        val named = page.videos.count {
            !it.channelName.isNullOrBlank() && !it.channelAvatar.isNullOrBlank() && !it.channelId.isNullOrBlank()
        }
        assertTrue("most videos should carry name+avatar+id", named >= page.videos.size / 2)
    }

    // Newer lockups put the view count in an accessibility label
    // ("21 million views") with only the bare number ("21M") as visible text.
    @Test
    fun relatedItemsCarryViewCounts() = runBlocking {
        val watch = innerTube.watch(videoId)
        assertTrue("related videos expected", watch.related.isNotEmpty())
        val withViews = watch.related.count { !it.viewsText.isNullOrBlank() }
        assertTrue("most related videos should carry view counts", withViews >= watch.related.size * 6 / 10)
        assertTrue("view text should mention views", watch.related.filter { it.viewsText != null }.all { it.viewsText!!.contains("view", true) })
        assertTrue("related call out their channel", watch.related.count { !it.channelName.isNullOrBlank() } >= watch.related.size * 6 / 10)
    }

    // Watch-next lockups keep the duration badge under contentImage; the cards
    // must show the length pill exactly like the real app does.
    @Test
    fun relatedItemsCarryDurations() = runBlocking {
        val watch = innerTube.watch(videoId)
        assertTrue("related videos expected", watch.related.isNotEmpty())
        val withDuration = watch.related.count { it.durationSec > 0 }
        assertTrue("most related videos should carry durations", withDuration >= watch.related.size / 2)
        assertTrue("durations render as m:ss", watch.related.filter { it.durationSec > 0 }.all { it.durationText.contains(":") })
    }

    // Real channel photos live in the lockup's owner block, not in the
    // metadata rows; a null avatar shows a letter instead of the photo.
    @Test
    fun relatedItemsCarryChannelAvatars() = runBlocking {
        val watch = innerTube.watch(videoId)
        assertTrue("related videos expected", watch.related.isNotEmpty())
        val withAvatar = watch.related.count { !it.channelAvatar.isNullOrBlank() }
        assertTrue("most related videos should carry avatars", withAvatar >= watch.related.size * 7 / 10)
        assertTrue(
            "avatars must be real https images",
            watch.related.mapNotNull { it.channelAvatar }.all { it.startsWith("https://") },
        )
        assertTrue(
            "related items should carry channel ids",
            watch.related.count { !it.channelId.isNullOrBlank() } >= watch.related.size * 7 / 10,
        )
    }

    @Test
    fun searchSuggestionsParse() = runBlocking {
        val suggestions = innerTube.suggestions("lofi")
        assertTrue("suggestions expected", suggestions.isNotEmpty())
        assertTrue("suggestions should be non-blank", suggestions.all { it.isNotBlank() })
        assertTrue("suggestions should be relevant", suggestions.any { it.contains("lofi", true) })
    }
}
