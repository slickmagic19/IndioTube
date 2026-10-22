package com.indiotube.app

import com.indiotube.app.data.InnerTube
import com.indiotube.app.data.SearchDuration
import com.indiotube.app.data.SearchEntry
import com.indiotube.app.data.SearchFilter
import com.indiotube.app.data.SearchUploadDate
import com.indiotube.app.data.SponsorBlock
import com.indiotube.app.data.StreamResolver
import com.indiotube.app.data.downloadSources
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
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
        val channels = results.entries.filterIsInstance<SearchEntry.Channel>().map { it.channel }
        assertTrue("expect at least one channel", channels.isNotEmpty())
        // Avatars are protocol-relative in YouTube's payload; the parser must
        // hand Coil a loadable, absolute URL or search shows letter fallbacks.
        val avatars = channels.mapNotNull { it.avatar }
        assertTrue("most channels should carry an avatar", avatars.size >= channels.size - 1)
        assertTrue("avatars must be absolute https URLs", avatars.all { it.startsWith("https://") })
        assertTrue(
            "the channel filter must not leak videos into the results",
            results.entries.all { it is SearchEntry.Channel },
        )
    }

    @Test
    fun searchDurationFilterCapsResults() = runBlocking {
        val results = innerTube.search("lofi", SearchFilter.VIDEOS, duration = SearchDuration.SHORT)
        val videos = results.entries.filterIsInstance<SearchEntry.Video>().map { it.video }
        assertTrue("short search should return videos", videos.isNotEmpty())
        // YouTube omits duration badges on shorts and live radios, so only
        // videos that do carry one can be checked - but any parsed duration
        // over four minutes means the filter was ignored.
        assertTrue(
            "YouTube's short filter must not return known long videos",
            videos.all { it.isLive || it.durationSec < 240 },
        )
    }

    @Test
    fun searchUploadDateFilterIsAccepted() = runBlocking {
        val results = innerTube.search(
            "lofi",
            SearchFilter.VIDEOS,
            uploadDate = SearchUploadDate.THIS_WEEK,
        )
        assertTrue("this-week search should return entries", results.entries.isNotEmpty())
        assertTrue("expect at least one video", results.entries.any { it is SearchEntry.Video })
    }

    // "quickybaby" is age-gated for anonymous clients: the plain search comes
    // back as a promo with zero renderers, which used to show "No results".
    // Recovery must resolve the channel through the completion entity and
    // return it with videos, whatever YouTube answers on a given day.
    @Test
    fun ageGatedChannelNameSearchStillFindsTheChannel() = runBlocking {
        val results = innerTube.search("quickybaby")
        val channel = results.entries.filterIsInstance<SearchEntry.Channel>()
            .map { it.channel }
            .firstOrNull { it.name.contains("QuickyBaby", ignoreCase = true) }
        assertNotNull("the QuickyBaby channel should be in the results", channel)
        assertTrue(
            "videos expected for the recovered channel",
            results.entries.any { it is SearchEntry.Video },
        )
    }

    @Test
    fun watchPageParses() = runBlocking {
        val watch = innerTube.watch(videoId)
        assertTrue("watch title should parse", watch.title.isNotBlank())
        assertTrue("related videos expected", watch.related.isNotEmpty())
        assertNotNull("comments entry point expected", watch.commentsToken)
    }

    // LGA4ZFr-hfo is a Linus Tech Tips upload with creator-defined chapters;
    // the watch response ships them as player-bar markers, not description
    // text, so this exercises the real parsing path behind the chapter rail.
    @Test
    fun watchPageParsesChapters() = runBlocking {
        val watch = innerTube.watch("LGA4ZFr-hfo")
        assertTrue("chapters expected", watch.chapters.size >= 2)
        assertEquals("first chapter starts at 0:00", 0L, watch.chapters.first().startMs)
        assertTrue("chapter titles expected", watch.chapters.all { it.title.isNotBlank() })
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

    // The Explore tab's first chip. YouTube retired the FEtrending browse feed
    // (HTTP 400), so this exercises the search-based "most viewed this week"
    // replacement; it must return a real, non-empty feed.
    @Test
    fun trendingFeedParses() = runBlocking {
        val feed = innerTube.trending()
        assertTrue("trending should return videos", feed.videos.isNotEmpty())
        assertTrue("trending videos need titles and ids", feed.videos.all { it.title.isNotBlank() && it.videoId.isNotBlank() })
        val withViews = feed.videos.count { !it.viewsText.isNullOrBlank() }
        assertTrue("most trending videos should carry view counts", withViews >= feed.videos.size / 2)
    }

    // "View replies" loads a continuation whose items are bare commentViewModel
    // entries; the parser must turn those into CommentItems with text.
    @Test
    fun commentRepliesLoad() = runBlocking {
        val token = innerTube.watch(videoId).commentsToken
        assertNotNull("comments token expected", token)
        val page = innerTube.comments(token!!)
        val parent = page.comments.firstOrNull { it.repliesToken != null }
        assertNotNull("a comment with a replies token expected", parent)
        val replies = innerTube.comments(parent!!.repliesToken!!)
        assertTrue("replies expected", replies.comments.isNotEmpty())
        assertTrue("reply text expected", replies.comments.all { it.text.isNotBlank() })
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

    // The About tab's data hides behind a lazy engagement panel: the channel
    // page only carries its continuation token. Cover the whole chain against
    // real YouTube so a layout change fails loudly.
    @Test
    fun channelAboutPanelParses() = runBlocking {
        val channelId = "UCXuqSBlHAE6Xw-yeJA0Tunw" // Linus Tech Tips
        val page = innerTube.channelVideos(channelId)
        assertNotNull("about continuation expected", page.aboutToken)

        val about = innerTube.channelAbout(channelId, page.aboutToken)
        assertNotNull("about panel expected", about)
        assertTrue("full description expected", !about!!.description.isNullOrBlank())
        assertTrue("subscriber count expected", !about.subscribersText.isNullOrBlank())
        assertTrue("joined date expected", !about.joinedText.isNullOrBlank())
        assertTrue("external links expected", about.links.isNotEmpty())
        assertTrue("links must be openable URLs", about.links.all { it.url.startsWith("https://") })
    }

    // freeCodeCamp used to show an empty Playlists tab: YouTube changed the
    // tab's encoded params, so the old hardcoded value returned the Home tab.
    // The page now discovers the params from the channel itself and paginates.
    @Test
    fun channelPlaylistsLoadForFreeCodeCamp() = runBlocking {
        val channelId = "UC8butISFwT-Wl7EV0hUK0BQ" // freeCodeCamp.org
        val params = innerTube.channelTabParams(channelId, "Playlists")
        assertNotNull("the Playlists tab params should be discovered", params)
        val page = innerTube.channelPlaylistsPage(channelId)
        assertTrue("playlists expected", page.playlists.size >= 20)
        assertTrue(
            "playlists need titles and ids",
            page.playlists.all { it.title.isNotBlank() && it.playlistId.isNotBlank() },
        )
        assertTrue(
            "playlist thumbnails should parse",
            page.playlists.count { !it.thumbnail.isNullOrBlank() } >= page.playlists.size / 2,
        )
        assertTrue(
            "playlist video counts should parse",
            page.playlists.count { !it.videoCountText.isNullOrBlank() } >= page.playlists.size / 2,
        )
        assertNotNull("freeCodeCamp should have more playlist pages", page.continuation)

        val more = innerTube.channelPlaylistsContinuation(page.continuation!!)
        assertTrue("continuation should return playlists", more.playlists.isNotEmpty())
        val firstIds = page.playlists.map { it.playlistId }.toSet()
        assertTrue(
            "pages must not repeat playlists",
            more.playlists.none { it.playlistId in firstIds },
        )
    }

    // Lofi Girl runs permanent live radio streams, so the Live tab must come
    // back with playable live cards rather than an empty page.
    @Test
    fun channelLiveTabParses() = runBlocking {
        val page = innerTube.channelLive("UCSJ4gkVC6NrvII8umztf0Ow")
        assertTrue("live tab should return videos", page.videos.isNotEmpty())
        assertTrue("live cards need titles and ids", page.videos.all { it.title.isNotBlank() && it.videoId.isNotBlank() })
        assertTrue("current streams should carry the LIVE badge", page.videos.any { it.isLive })
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

    // The quality sheet is only honest if it lists the real ladder: muxed
    // heights plus the higher video-only heights that get paired with audio.
    @Test
    fun qualityLadderExtracts() = runBlocking {
        StreamResolver.init(http)
        val resolver = StreamResolver(http)
        val qualities = resolver.qualities(videoId)

        assertTrue("qualities expected", qualities.options.isNotEmpty())
        assertTrue("multiple qualities expected", qualities.options.size >= 2)
        assertTrue(
            "ladder must be sorted highest first",
            qualities.options.map { it.height }.zipWithNext().all { (a, b) -> a > b },
        )
        assertNotNull("auto (muxed) stream expected", qualities.auto)
        assertTrue("auto should carry video", qualities.auto!!.height > 0)
        assertTrue("every option needs a source", qualities.options.all { it.hasStream })
        assertTrue(
            "labels look like YouTube",
            qualities.options.all { it.label.matches(Regex("\\d+p\\d*")) },
        )
        assertNotNull("audio track expected for video-only qualities", qualities.audio)
        assertTrue("audio mime should be audio/*", qualities.audio!!.isAudio)
    }

    // Both halves of a merged quality must be fetchable media URLs, otherwise
    // selecting that quality would break playback.
    @Test
    fun explicitQualityTracksArePlayable() = runBlocking {
        StreamResolver.init(http)
        val resolver = StreamResolver(http)
        val qualities = resolver.qualities(videoId)
        val option = qualities.options.firstOrNull { it.videoOnly != null && it.height <= 1080 }
            ?: qualities.options.first()

        val video = option.videoOnly ?: resolver.resolveTrackBlocking(videoId, "muxed", option.height)
        assertTrue("video mime expected, got ${video.mime}", video.isVideo)
        val (videoCode, videoBytes) = probeStream(video.url)
        assertTrue("video track should respond (got $videoCode)", videoCode == 200 || videoCode == 206)
        assertTrue("video track should carry data", videoBytes > 0)

        if (option.videoOnly != null) {
            val audio = resolver.resolveTrackBlocking(videoId, "audio", 0)
            assertTrue("audio mime expected, got ${audio.mime}", audio.isAudio)
            val (audioCode, audioBytes) = probeStream(audio.url)
            assertTrue("audio track should respond (got $audioCode)", audioCode == 200 || audioCode == 206)
            assertTrue("audio track should carry data", audioBytes > 0)
        }

        // Auto must keep resolving to the single muxed file as before.
        val muxed = resolver.resolveTrackBlocking(videoId, "muxed", qualities.auto!!.height)
        assertTrue("muxed mime expected", muxed.isVideo)

        val sources = downloadSources(qualities, option.height)
        assertTrue("download video source expected", sources.video.isVideo)
        if (option.videoOnly?.videoDownloadMuxable == true && qualities.audio?.audioDownloadMuxable == true) {
            assertNotNull("adaptive rung should download with an audio track", sources.audio)
            assertEquals(option.height, sources.video.height)
        }
    }

    // Switching quality used to be able to serve another video's stream. The
    // resolver cache is per video; pin that two videos never share tracks.
    @Test
    fun qualityLaddersArePerVideo() = runBlocking {
        StreamResolver.init(http)
        val resolver = StreamResolver(http)
        val first = resolver.qualities(videoId)
        val second = resolver.qualities("9bZkp7q19f0")

        assertNotNull(first.auto)
        assertNotNull(second.auto)
        assertTrue("ladders expected for both videos", first.options.isNotEmpty() && second.options.isNotEmpty())
        assertTrue(
            "different videos must resolve different muxed streams",
            first.auto!!.url != second.auto!!.url,
        )

        val firstVideoTrack = first.options.firstNotNullOfOrNull { it.videoOnly }
        val secondVideoTrack = second.options.firstNotNullOfOrNull { it.videoOnly }
        if (firstVideoTrack != null && secondVideoTrack != null) {
            assertTrue(
                "video-only tracks must stay with their own video",
                firstVideoTrack.url != secondVideoTrack.url,
            )
        }

        // The cache is also allowed to exist, as long as it stays per video.
        val cached = resolver.qualities(videoId)
        assertEquals(first.auto!!.url, cached.auto!!.url)
        assertEquals(first.options.map { it.height }, cached.options.map { it.height })
    }

    // Captions come from YouTube's timedtext endpoint. It returns srv3 XML by
    // default, which Media3 cannot parse, so the resolver must expose the
    // WebVTT rendition. Manual captions win over auto-generated ones for the
    // same language, and the URL must actually serve cues.
    @Test
    fun captionsResolveToPlayableVtt() = runBlocking {
        StreamResolver.init(http)
        val resolver = StreamResolver(http)
        val qualities = resolver.qualities(videoId)

        assertTrue("captions expected on $videoId", qualities.subtitles.isNotEmpty())
        assertEquals(
            "one track per language",
            qualities.subtitles.size,
            qualities.subtitles.map { it.languageTag.lowercase() }.distinct().size,
        )
        assertTrue(
            "every caption track must be WebVTT",
            qualities.subtitles.all { it.mime == "text/vtt" && it.url.contains("fmt=vtt") },
        )
        assertTrue(
            "labels and language tags should be present",
            qualities.subtitles.all { it.label.isNotBlank() && it.languageTag.isNotBlank() },
        )

        val manual = qualities.subtitles.firstOrNull { !it.autoGenerated }
        assertNotNull("manual captions expected", manual)
        val (code, body) = fetchText(manual!!.url)
        assertTrue("caption URL should respond (got $code)", code == 200)
        assertTrue("caption body should be WebVTT, got '${body.take(16)}'", body.startsWith("WEBVTT"))
        assertTrue("caption cues expected", body.contains("-->"))

        // A video with only auto-generated captions still exposes them.
        val autoQualities = resolver.qualities("9bZkp7q19f0")
        val auto = autoQualities.subtitles.firstOrNull { it.autoGenerated }
        assertNotNull("auto-generated captions expected", auto)
        val (autoCode, autoBody) = fetchText(auto!!.url)
        assertTrue("auto caption URL should respond (got $autoCode)", autoCode == 200)
        assertTrue("auto caption body should be WebVTT", autoBody.startsWith("WEBVTT"))
    }

    // LGA4ZFr-hfo is a Linus Tech Tips upload with crowd-sourced segments;
    // the API needs no key. If it ever loses them, swap the id - the unit
    // tests pin the parsing, this only checks the live wire contract.
    @Test
    fun sponsorBlockServesSegmentsForAVideoThatHasThem() = runBlocking {
        val segments = SponsorBlock(http).segments("LGA4ZFr-hfo")
        assertTrue("the sample video should have segments", segments.isNotEmpty())
        assertTrue(
            "segments must be ordered by start",
            segments.zipWithNext().all { (a, b) -> a.startMs <= b.startMs },
        )
        assertTrue("segments need a positive span", segments.all { it.endMs > it.startMs })
    }

    private fun probeStream(url: String): Pair<Int, Long> {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-4095")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Mobile Safari/537.36",
            )
            .build()
        http.newCall(request).execute().use { response ->
            val bytes = response.body?.bytes()?.size ?: 0
            return response.code to bytes.toLong()
        }
    }

    private fun fetchText(url: String): Pair<Int, String> {
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Mobile Safari/537.36",
            )
            .build()
        http.newCall(request).execute().use { response ->
            return response.code to (response.body?.string().orEmpty())
        }
    }
}
