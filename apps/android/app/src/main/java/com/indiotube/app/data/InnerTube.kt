package com.indiotube.app.data

import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// YouTube metadata via the InnerTube WEB client — the same endpoint family
// yt-dlp and NewPipe talk to. IndioTube never renders Google's own ad or
// Shorts renderers: they are skipped during parsing, which is what makes the
// app ad-free and Shorts-free without touching playback at all.

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
private val UA_DESKTOP =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0"

private val DURATION_RE = Regex("^\\d{1,2}:\\d{2}(:\\d{2})?$")
private val VIEWS_RE = Regex("(views?|watching|likes?)", RegexOption.IGNORE_CASE)
private val AGO_RE = Regex("(ago|Premiered|Streamed|\\b\\d{4}\\b)", RegexOption.IGNORE_CASE)
private val LIKES_ALONG_RE = Regex("along with ([\\d.,]+[KMB]?) other", RegexOption.IGNORE_CASE)
private val COUNT_RE = Regex("^[\\d.,]+[KMB]?$")

// search filter params (same values the web client uses)
object SearchFilter {
    const val VIDEOS = "EgIQAQ%3D%3D"
    const val CHANNELS = "EgIQAg%3D%3D"
    const val PLAYLISTS = "EgIQAw%3D%3D"
}

// Search params for "this week, videos only, sorted by view count" - the
// closest signed-out equivalent of the retired FEtrending feed.
private const val TRENDING_PARAMS = "EgYIAxABGAM%3D"

// channel tab params
private const val CHANNEL_TAB_VIDEOS = "EgZ2aWRlb3PyBgQKAjoA"
private const val CHANNEL_TAB_PLAYLISTS = "EgZwbGF5bGlzdHPyBgQKAkIA"

data class Chip(val title: String, val params: String?, val token: String?)

data class HomeFeed(
    val chips: List<Chip>,
    val videos: List<VideoItem>,
    val continuation: String?,
)

sealed interface SearchEntry {
    data class Video(val video: VideoItem) : SearchEntry
    data class Channel(val channel: ChannelItem) : SearchEntry
    data class Playlist(val playlist: PlaylistItem) : SearchEntry
}

data class SearchResults(val entries: List<SearchEntry>, val continuation: String?)

data class ChannelPage(
    val channel: ChannelItem,
    val videos: List<VideoItem>,
    val continuation: String?,
    val playlists: List<PlaylistItem> = emptyList(),
)

data class WatchData(
    val videoId: String,
    val title: String,
    val channelId: String?,
    val channelName: String?,
    val channelAvatar: String?,
    val subscribersText: String?,
    val viewsText: String?,
    val publishedText: String?,
    val likesText: String?,
    val description: String?,
    val related: List<VideoItem>,
    val commentsToken: String?,
    val commentsCountText: String?,
)

data class CommentsPage(val comments: List<CommentItem>, val continuation: String?)

data class PlaylistPage(
    val title: String,
    val author: String?,
    val videoCountText: String?,
    val videos: List<VideoItem>,
    val continuation: String?,
)

class InnerTube(private val http: OkHttpClient) {

    private data class Cfg(val key: String, val version: String, val visitor: String?)

    @Volatile private var cfg: Cfg? = null
    private val cfgMutex = Mutex()

    private val country: String = Locale.getDefault().country.takeIf { it.length == 2 }?.uppercase() ?: "US"
    private val lang: String = Locale.getDefault().language.takeIf { it.isNotEmpty() }?.lowercase() ?: "en"

    private data class CacheEntry<T>(val at: Long, val data: T)
    private val cache = ConcurrentHashMap<String, CacheEntry<*>>()

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> cached(key: String, ttlMs: Long, force: Boolean = false, load: suspend () -> T): T {
        if (force) cache.remove(key)
        val hit = cache[key] as? CacheEntry<T>
        if (hit != null && System.currentTimeMillis() - hit.at < ttlMs) return hit.data
        val fresh = load()
        cache[key] = CacheEntry(System.currentTimeMillis(), fresh)
        return fresh
    }

    fun invalidate() {
        cache.clear()
    }

    // ---- transport -----------------------------------------------------------

    private suspend fun config(): Cfg {
        cfg?.let { return it }
        return withContext(Dispatchers.IO) {
            cfgMutex.withLock {
                cfg?.let { return@withLock it }
                var key = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
                var version = "2.${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}.00.00"
                var visitor: String? = null
                try {
                    val html = execute("https://www.youtube.com/", null, null)
                    Regex("\"INNERTUBE_API_KEY\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)?.let { key = it }
                    Regex("\"INNERTUBE_CONTEXT_CLIENT_VERSION\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)?.let { version = it }
                    visitor = Regex("\"VISITOR_DATA\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)
                } catch (_: Exception) { /* fall back to constants */ }
                val c = Cfg(key, version, visitor)
                cfg = c
                c
            }
        }
    }

    private fun execute(url: String, body: String?, c: Cfg?): String {
        val rb = Request.Builder().url(url)
            .header("User-Agent", UA_DESKTOP)
            .header("Accept", "*/*")
            .header("Accept-Language", "${lang},en;q=0.8")
            .header("Origin", "https://www.youtube.com")
        if (c != null) {
            rb.header("X-YouTube-Client-Name", "1")
            rb.header("X-YouTube-Client-Version", c.version)
            c.visitor?.let { rb.header("X-Goog-Visitor-Id", it) }
        }
        if (body != null) {
            rb.post(body.toRequestBody(JSON_MEDIA))
        } else {
            rb.get()
        }
        http.newCall(rb.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("YouTube returned HTTP ${r.code}")
            return text
        }
    }

    private fun clientJson(c: Cfg): JsonObject = buildJsonObject {
        put("clientName", "WEB")
        put("clientVersion", c.version)
        put("hl", lang)
        put("gl", country)
        c.visitor?.let { put("visitorData", it) }
    }

    private suspend fun post(endpoint: String, body: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val c = config()
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                val url = "https://www.youtube.com/youtubei/v1/$endpoint?prettyPrint=false&alt=json&key=${c.key}"
                return@withContext SyncJson.parseToJsonElement(execute(url, body.toString(), c))
            } catch (e: Exception) {
                lastError = e
                if (attempt == 0) delay(1200)
            }
        }
        throw lastError ?: IOException("request failed")
    }

    private fun contextOf(c: Cfg): JsonObject = buildJsonObject {
        put("client", clientJson(c))
        putJsonObject("user") {}
    }

    private suspend fun browse(browseId: String, params: String? = null, continuation: String? = null): JsonElement {
        val c = config()
        val body = buildJsonObject {
            put("context", contextOf(c))
            if (continuation != null) {
                put("continuation", continuation)
            } else {
                put("browseId", browseId)
                params?.let { put("params", it) }
            }
        }
        return post("browse", body)
    }

    // ---- home feed -------------------------------------------------------------

    suspend fun home(params: String? = null, force: Boolean = false): HomeFeed =
        cached("home:${params ?: ""}", 10 * 60_000L, force) {
            // Signed-out sessions intentionally receive no recommendations
            // (the "Try searching to get started" nudge), so an empty feed is
            // a valid, expected response from YouTube here.
            parseFeed(browse("FEwhat_to_watch", params))
        }

    suspend fun homeContinuation(token: String): HomeFeed {
        val root = browse("FEwhat_to_watch", continuation = token)
        return parseFeed(root)
    }

    // YouTube retired the signed-out FEtrending feed (it answers HTTP 400 on
    // every client now), so Trending is the popular list of the past week:
    // the search endpoint with an empty query, videos only, sorted by views.
    // Used by the Explore tab; falls back to an empty feed when offline so the
    // UI can show its retry state instead of crashing.
    suspend fun trending(force: Boolean = false): HomeFeed = try {
        cached("trending:$country", 10 * 60_000L, force) {
            val results = search("", TRENDING_PARAMS)
            HomeFeed(
                emptyList(),
                results.entries.filterIsInstance<SearchEntry.Video>().map { it.video },
                results.continuation,
            )
        }
    } catch (_: Exception) {
        HomeFeed(emptyList(), emptyList(), null)
    }

    suspend fun trendingContinuation(token: String): HomeFeed = try {
        val results = searchContinuation(token)
        HomeFeed(
            emptyList(),
            results.entries.filterIsInstance<SearchEntry.Video>().map { it.video },
            results.continuation,
        )
    } catch (_: Exception) {
        HomeFeed(emptyList(), emptyList(), null)
    }

    internal fun parseFeed(root: JsonElement): HomeFeed {
        val chips = ArrayList<Chip>()
        root.path(
            "contents", "twoColumnBrowseResultsRenderer", "tabs", 0,
            "tabRenderer", "content", "richGridRenderer", "header",
            "feedFilterChipBarRenderer", "contents",
        ).let { chipArr ->
            (chipArr as? JsonArray)?.forEach { chipEl ->
                val chip = chipEl.obj("chipCloudChipRenderer") ?: return@forEach
                val title = chip.pathStr("text", "simpleText") ?: chip.path("text").runsText() ?: return@forEach
                val params = chip.pathStr("navigationEndpoint", "browseEndpoint", "params")
                val token = chip.pathStr("navigationEndpoint", "continuationCommand", "token")
                chips.add(Chip(title, params, token))
            }
        }

        val contents = feedContents(root)
        val videos = ArrayList<VideoItem>()
        var continuation: String? = null
        for (entry in contents) {
            entry.obj("richItemRenderer").let { item ->
                if (item != null) {
                    val content = item.field("content") ?: return@let
                    parseAnyVideo(content)?.let { videos.add(it) }
                    return@let
                }
            }
            // continuation marker
            entry.obj("continuationItemRenderer")?.let { cont ->
                continuation = extractContinuation(cont)
            }
        }
        if (continuation == null) continuation = feedContinuation(root)
        return HomeFeed(chips, videos, continuation)
    }

    private fun feedContents(root: JsonElement): List<JsonElement> {
        val direct = root.path(
            "contents", "twoColumnBrowseResultsRenderer", "tabs", 0,
            "tabRenderer", "content", "richGridRenderer", "contents",
        ) as? JsonArray
        if (direct != null) return direct
        val single = root.path(
            "contents", "singleColumnBrowseResultsRenderer", "tabs", 0,
            "tabRenderer", "content", "richGridRenderer", "contents",
        ) as? JsonArray
        if (single != null) return single
        // section-list layouts (experiments): flatten grid renderers
        val out = ArrayList<JsonElement>()
        root.findAll("richGridRenderer", maxDepth = 8, limit = 3).forEach { grid ->
            (grid.arr("contents") as? JsonArray)?.let { out.addAll(it) }
        }
        return out
    }

    private fun feedContinuation(root: JsonElement): String? {
        root.findAll("continuationItemRenderer", maxDepth = 14, limit = 12).forEach { cont ->
            extractContinuation(cont)?.let { return it }
        }
        // newer: continuationCommand directly under richGridRenderer.continuations
        root.findAll("continuationCommand", maxDepth = 14, limit = 12).forEach { cc ->
            cc.pathStr("token")?.let { return it }
        }
        return null
    }

    private fun extractContinuation(cont: JsonElement): String? =
        cont.pathStr("continuationEndpoint", "continuationCommand", "token")
            ?: cont.pathStr("button", "buttonRenderer", "command", "continuationCommand", "token")
            ?: cont.pathStr("continuationEndpoint", "continuationCommand", "token")

    // ---- shorts filtering --------------------------------------------------------

    // Every item that goes through a parser gets checked here: vertical
    // thumbnails (Shorts are portrait) and reel navigation endpoints never
    // reach the UI.
    private fun isShortish(el: JsonElement): Boolean {
        if (el.path("navigationEndpoint", "reelWatchEndpoint") != null) return true
        val last = el.findAll("thumbnails", maxDepth = 6, limit = 6)
            .filterIsInstance<JsonArray>()
            .flatMap { it }
            .lastOrNull() ?: return false
        val w = last.pathStr("width")?.toIntOrNull() ?: 0
        val h = last.pathStr("height")?.toIntOrNull() ?: 0
        return h > w && w > 0
    }

    // ---- search ------------------------------------------------------------------

    suspend fun search(query: String, filter: String? = null): SearchResults {
        val c = config()
        val body = buildJsonObject {
            put("context", contextOf(c))
            put("query", query)
            filter?.let { put("params", it) }
        }
        val root = post("search", body)
        return parseSearch(root)
    }

    suspend fun searchContinuation(token: String): SearchResults {
        val c = config()
        val body = buildJsonObject {
            put("context", contextOf(c))
            put("continuation", token)
        }
        val root = post("search", body)
        return parseSearch(root)
    }

    // Type-ahead suggestions from YouTube's own completion endpoint (the same
    // one the web client uses). No auth, no ads, plain query strings.
    suspend fun suggestions(query: String, force: Boolean = false): List<String> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return cached("suggest:$q", 5 * 60_000L, force) {
            try {
                val url = "https://suggestqueries-clients6.youtube.com/complete/search" +
                    "?client=youtube&ds=yt&hl=$lang&gl=$country" +
                    "&q=${URLEncoder.encode(q, "UTF-8")}"
                val text = withContext(Dispatchers.IO) { execute(url, null, null) }
                // client=youtube answers with a JSONP-wrapped array: goog.ac.h([...])
                val start = text.indexOf('[')
                val end = text.lastIndexOf(']')
                if (start < 0 || end <= start) return@cached emptyList()
                val arr = SyncJson.parseToJsonElement(text.substring(start, end + 1)) as? JsonArray
                    ?: return@cached emptyList()
                val items = arr.getOrNull(1) as? JsonArray ?: return@cached emptyList()
                items.mapNotNull { el ->
                    val raw = when (el) {
                        is JsonArray -> el.firstOrNull()
                        else -> el
                    }
                    (raw as? JsonPrimitive)?.contentOrNull
                }.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(12)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    internal fun parseSearch(root: JsonElement): SearchResults {
        val entries = ArrayList<SearchEntry>()
        var continuation: String? = null
        val sections = root.path(
            "contents", "tabbedSearchResultsRenderer", "tabs", 0,
            "tabRenderer", "content", "sectionListRenderer", "contents",
        ) as? JsonArray ?: root.path(
            "contents", "twoColumnSearchResultsRenderer", "primaryContents",
            "sectionListRenderer", "contents",
        ) as? JsonArray
        val sectionList = sections ?: run {
            val found = ArrayList<JsonElement>()
            root.findAll("itemSectionRenderer", maxDepth = 12, limit = 20).forEach { found.add(it) }
            found
        }
        for (section in sectionList) {
            // direct paths yield {"itemSectionRenderer": ...} wrappers; the
            // bounded-scan fallback yields the renderer bodies themselves
            val itemSection = section.obj("itemSectionRenderer") ?: section as? JsonObject
            val items = itemSection?.arr("contents") ?: continue
            for (el in items) {
                when {
                    el.obj("videoRenderer") != null -> parseVideoRenderer(el.obj("videoRenderer")!!)?.let {
                        entries.add(SearchEntry.Video(it))
                    }
                    el.obj("channelRenderer") != null -> parseChannelRenderer(el.obj("channelRenderer")!!)?.let {
                        entries.add(SearchEntry.Channel(it))
                    }
                    el.obj("playlistRenderer") != null -> parsePlaylistRenderer(el.obj("playlistRenderer")!!)?.let {
                        entries.add(SearchEntry.Playlist(it))
                    }
                    el.obj("lockupViewModel") != null -> parseLockup(el.obj("lockupViewModel")!!)?.let {
                        entries.add(SearchEntry.Video(it))
                    }
                    el.obj("continuationItemRenderer") != null -> extractContinuation(el.obj("continuationItemRenderer")!!)?.let {
                        continuation = it
                    }
                    // shelfRenderer/reelShelfRenderer → skipped (Shorts & shelves)
                }
            }
        }
        if (continuation == null) {
            root.findAll("continuationItemRenderer", maxDepth = 12, limit = 10).forEach { cont ->
                extractContinuation(cont)?.let { continuation = it; return@forEach }
            }
        }
        return SearchResults(entries, continuation)
    }

    // ---- channel -------------------------------------------------------------------

    suspend fun channelVideos(channelId: String, params: String? = CHANNEL_TAB_VIDEOS, force: Boolean = false): ChannelPage =
        cached("channel:$channelId:$params", 10 * 60_000L, force) {
            val root = browse(channelId, params)
            parseChannelPage(root, channelId)
        }

    suspend fun channelContinuation(token: String): ChannelPage {
        val root = browse("", continuation = token)
        return parseChannelPage(root, "")
    }

    suspend fun channelPlaylists(channelId: String, force: Boolean = false): List<PlaylistItem> = try {
        cached("channelPlaylists:$channelId", 30 * 60_000L, force) {
            val root = browse(channelId, CHANNEL_TAB_PLAYLISTS)
            val out = ArrayList<PlaylistItem>()
            root.findAll("gridPlaylistRenderer", maxDepth = 12, limit = 60).forEach { pl ->
                parsePlaylistRenderer(pl)?.let { out.add(it) }
            }
            if (out.isEmpty()) {
                root.findAll("lockupViewModel", maxDepth = 12, limit = 60).forEach { lockup ->
                    if (lockup.pathStr("contentType")?.contains("PLAYLIST") == true) {
                        parsePlaylistLockup(lockup)?.let { out.add(it) }
                    }
                }
            }
            out.distinctBy { it.playlistId }
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun parsePlaylistLockup(lockup: JsonElement): PlaylistItem? {
        val playlistId = lockup.pathStr("contentId") ?: return null
        val meta = lockup.path("metadata", "lockupMetadataViewModel") ?: return null
        val title = meta.pathStr("title", "content")?.trim().orEmpty().ifEmpty { return null }
        val thumb = xthFirstUrl(meta.path("image", "collectionThumbnailViewModel", "primaryThumbnail", "thumbnailViewModel", "image"))
            ?: xthFirstUrl(meta.path("image"))
        var count: String? = null
        val rows = meta.path("metadata", "contentMetadataViewModel", "metadataRows") as? JsonArray
        rows?.forEach { row ->
            (row.arr("metadataParts") ?: return@forEach).forEach { part ->
                part.pathStr("text", "content")?.takeIf { VIEWS_RE.containsMatchIn(it) }?.let { count = count ?: it }
            }
        }
        return PlaylistItem(playlistId, title, null, null, thumb, count)
    }

    private fun parseChannelPage(root: JsonElement, requestedId: String): ChannelPage {
        val channel = parseChannelHeader(root, requestedId)
        val contents = feedContents(root)
        val videos = ArrayList<VideoItem>()
        var continuation: String? = null
        for (entry in contents) {
            entry.obj("richItemRenderer")?.let { item ->
                item.field("content")?.let { content ->
                    parseAnyVideo(content)?.let { videos.add(it) }
                }
                return@let
            }
            // old grid layouts
            entry.obj("gridVideoRenderer")?.let { gv ->
                parseVideoRenderer(gv)?.let { videos.add(it) }
            }
            entry.obj("continuationItemRenderer")?.let { cont ->
                continuation = extractContinuation(cont)
            }
        }
        if (videos.isEmpty()) {
            root.findAll("gridVideoRenderer", maxDepth = 12, limit = 60).forEach { gv ->
                parseVideoRenderer(gv)?.let { videos.add(it) }
            }
        }
        if (continuation == null) continuation = feedContinuation(root)
        // Videos on a channel page don't repeat the owner in each item; stamp
        // the header channel so cards show the real name and avatar.
        val stamped = videos.map { v ->
            if (v.channelName != null && v.channelAvatar != null && v.channelId != null) v
            else v.copy(
                channelId = v.channelId ?: channel.channelId,
                channelName = v.channelName ?: channel.name.takeIf { it != "Channel" },
                channelAvatar = v.channelAvatar ?: channel.avatar,
            )
        }
        return ChannelPage(channel, stamped, continuation)
    }

    private fun parseChannelHeader(root: JsonElement, requestedId: String): ChannelItem {
        var name: String? = null
        var avatar: String? = null
        var subscribers: String? = null
        var videoCount: String? = null
        var description: String? = null

        // newest layout
        val pageHeader = root.path("header", "pageHeaderRenderer")
        if (pageHeader != null) {
            name = pageHeader.pathStr("pageHeaderViewModel", "title", "dynamicTextViewModel", "text", "content")
            avatar = xthFirstUrl(pageHeader.path("pageHeaderViewModel", "image", "decoratedAvatarViewModel", "avatar"))
            val rows = pageHeader.path("pageHeaderViewModel", "metadata", "contentMetadataViewModel", "metadataRows")
            (rows as? JsonArray)?.forEach { row ->
                (row.arr("metadataParts") ?: return@forEach).forEach { part ->
                    val t = part.pathStr("text", "content") ?: return@forEach
                    when {
                        t.startsWith("@") -> {}
                        Regex("subscriber", RegexOption.IGNORE_CASE).containsMatchIn(t) -> subscribers = t
                        Regex("video", RegexOption.IGNORE_CASE).containsMatchIn(t) -> videoCount = t
                    }
                }
            }
            description = pageHeader
                .findAll("descriptionPreviewViewModel", maxDepth = 10, limit = 2)
                .firstOrNull()?.pathStr("description", "content")
        }

        // classic layout
        val c4 = root.path("header", "c4TabbedHeaderRenderer")
        if (c4 != null) {
            if (name == null) name = c4.pathStr("title", "simpleText") ?: c4.path("title").runsText()
            if (avatar == null) avatar = lastThumbUrl(c4.path("avatar"))
            subscribers = subscribers ?: c4.pathStr("subscriberCountText", "simpleText")
                ?: c4.path("subscriberCountText").runsText()
            videoCount = videoCount ?: c4.pathStr("videosCountText", "simpleText")
                ?: c4.path("videosCountText").runsText()
            description = description ?: c4.pathStr("tagline", "channelTaglineRenderer", "content")
        }

        // microformat safety net
        val micro = root.path("microformat", "microformatDataRenderer")
        if (name == null) name = micro.pathStr("title")
        if (description == null) description = micro.pathStr("description")
        if (avatar == null) avatar = lastThumbUrl(micro.path("thumbnail"))
        val channelId = micro.pathStr("urlCanonical")
            ?.substringAfterLast("/")
            ?.takeIf { it.startsWith("UC") }
            ?: requestedId

        return ChannelItem(
            channelId = channelId,
            name = name?.trim().orEmpty().ifEmpty { "Channel" },
            avatar = avatar,
            subscribersText = subscribers,
            videoCountText = videoCount,
            description = description?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun xthFirstUrl(container: JsonElement?): String? {
        val sources = container?.findAll("sources", maxDepth = 6, limit = 4)
            ?.filterIsInstance<JsonArray>()?.flatten() ?: return null
        return sources.mapNotNull { it.pathStr("url") }.lastOrNull()
    }

    private fun lastThumbUrl(container: JsonElement?): String? =
        (container?.path("thumbnails") as? JsonArray)
            ?.mapNotNull { it.pathStr("url") }
            ?.lastOrNull()

    // ---- watch page ---------------------------------------------------------------

    suspend fun watch(videoId: String): WatchData = cached("watch:$videoId", 5 * 60_000L) {
        val c = config()
        val body = buildJsonObject {
            put("context", contextOf(c))
            put("videoId", videoId)
        }
        val root = post("next", body)
        parseWatch(root, videoId)
    }

    internal fun parseWatch(root: JsonElement, videoId: String): WatchData {
        val primary = root.findAll("videoPrimaryInfoRenderer", maxDepth = 10, limit = 2).firstOrNull()
        val secondary = root.findAll("videoSecondaryInfoRenderer", maxDepth = 10, limit = 2).firstOrNull()

        val title = primary.path("title").runsText()
            ?: root.pathStr("contents", "twoColumnWatchNextResults", "results", "results", "contents", 0,
                "videoPrimaryInfoRenderer", "title", "simpleText")
            ?: ""

        val viewCountRenderer = primary.path("viewCount", "videoViewCountRenderer")
        val viewsText = viewCountRenderer.path("viewCount").runsText()
            ?: viewCountRenderer.pathStr("viewCount", "simpleText")
            ?: viewCountRenderer.path("shortViewCount").runsText()
        val publishedText = primary.pathStr("dateText", "simpleText")
            ?: primary.pathStr("relativeDateText", "simpleText")

        val owner = secondary.path("owner", "videoOwnerRenderer")
            ?: root.findAll("videoOwnerRenderer", maxDepth = 10, limit = 2).firstOrNull()
        val channelName = owner.path("title").runsText()
        val channelId = owner.pathStr("title", "runs", 0, "navigationEndpoint", "browseEndpoint", "browseId")
            ?: owner.pathStr("navigationEndpoint", "browseEndpoint", "browseId")
        val channelAvatar = lastThumbUrl(owner.path("thumbnail"))
        val subscribersText = owner.pathStr("subscriberCountText", "simpleText")
            ?: owner.path("subscriberCountText").runsText()

        val description = secondary.pathStr("attributedDescription", "content")
            ?: secondary.path("description").runsText()
            ?: secondary.pathStr("description", "simpleText")

        val likesText = extractLikes(primary ?: root)

        // related videos from the secondary results
        val related = ArrayList<VideoItem>()
        val results = root.path(
            "contents", "twoColumnWatchNextResults", "secondaryResults",
            "secondaryResults", "results",
        ) as? JsonArray
        val resultList = results ?: run {
            val found = ArrayList<JsonElement>()
            root.findAll("itemSectionRenderer", maxDepth = 12, limit = 20).forEach { sec ->
                (sec.arr("contents") as? JsonArray)?.let { found.addAll(it) }
            }
            found
        }
        for (el in resultList) {
            when {
                el.obj("compactVideoRenderer") != null ->
                    parseVideoRenderer(el.obj("compactVideoRenderer")!!)?.let { related.add(it) }
                el.obj("lockupViewModel") != null ->
                    parseLockup(el.obj("lockupViewModel")!!)?.let { related.add(it) }
                el.obj("videoRenderer") != null ->
                    parseVideoRenderer(el.obj("videoRenderer")!!)?.let { related.add(it) }
            }
        }

        // comments entry point
        var commentsToken: String? = null
        var commentsCount: String? = null
        root.findAll("commentsEntryPointHeaderRenderer", maxDepth = 14, limit = 2).forEach { cp ->
            commentsToken = commentsToken ?: extractContinuation(cp)
                ?: cp.pathStr("continuationEndpoint", "continuationCommand", "token")
            commentsCount = commentsCount
                ?: cp.path("commentCount").let { it.runsText() ?: it.str("simpleText") }
                ?: cp.pathStr("commentsCount", "simpleText")
        }
        if (commentsToken == null) {
            root.findAll("commentsSectionRenderer", maxDepth = 14, limit = 2).forEach { cs ->
                commentsToken = commentsToken ?: cs.pathStr("continuations", 0, "continuationEndpoint", "continuationCommand", "token")
            }
        }
        if (commentsToken == null) {
            // current layout: a dedicated engagement panel
            (root.arr("engagementPanels") ?: JsonArray(emptyList())).forEach { p ->
                val panel = p.obj("engagementPanelSectionListRenderer") ?: return@forEach
                val id = panel.pathStr("panelIdentifier") ?: return@forEach
                if (!id.contains("comments")) return@forEach
                panel.findAll("continuationCommand", maxDepth = 12, limit = 2).forEach { cc ->
                    commentsToken = commentsToken ?: cc.pathStr("token")
                }
                commentsCount = commentsCount
                    ?: panel.path("header", "engagementPanelTitleHeaderRenderer", "contextualInfo")
                        .let { it.runsText() ?: it.str("simpleText") }
            }
        }

        return WatchData(
            videoId = videoId,
            title = title.trim(),
            channelId = channelId,
            channelName = channelName,
            channelAvatar = channelAvatar,
            subscribersText = subscribersText,
            viewsText = viewsText,
            publishedText = publishedText,
            likesText = likesText,
            description = description?.trim()?.takeIf { it.isNotEmpty() },
            related = related.distinctBy { it.videoId }.filter { it.videoId != videoId },
            commentsToken = commentsToken,
            commentsCountText = commentsCount,
        )
    }

    private fun extractLikes(scope: JsonElement): String? {
        for (label in scope.findAll("accessibilityData", maxDepth = 12, limit = 60)
            .mapNotNull { it.pathStr("label") }) {
            LIKES_ALONG_RE.find(label)?.groupValues?.getOrNull(1)?.let { return it }
        }
        for (btn in scope.findAll("segmentedLikeDislikeButtonViewModel", maxDepth = 12, limit = 4)) {
            btn.findAll("toggleButtonViewModel", maxDepth = 8, limit = 8).forEach { tb ->
                val text = tb.pathStr("defaultButtonViewModel", "buttonViewModel", "title")
                    ?: tb.pathStr("toggledButtonViewModel", "buttonViewModel", "title")
                if (text != null && text.any { it.isDigit() }) return text
            }
        }
        return null
    }

    // ---- comments -------------------------------------------------------------------

    suspend fun comments(token: String): CommentsPage {
        val c = config()
        val body = buildJsonObject {
            put("context", contextOf(c))
            put("continuation", token)
        }
        val root = post("next", body)
        val entities = collectCommentEntities(root)
        val items = collectContinuationItems(root)
        val comments = ArrayList<CommentItem>()
        var continuation: String? = null
        for (el in items) {
            when {
                el.obj("commentThreadRenderer") != null -> parseCommentThread(el.obj("commentThreadRenderer")!!, entities)?.let {
                    comments.add(it)
                }
                el.obj("commentRenderer") != null -> parseCommentRenderer(el.obj("commentRenderer")!!)?.let {
                    comments.add(it)
                }
                // reply continuations return bare commentViewModel items
                el.obj("commentViewModel") != null -> parseCommentThread(el, entities)?.let {
                    comments.add(it)
                }
                el.obj("continuationItemRenderer") != null -> extractContinuation(el.obj("continuationItemRenderer")!!)?.let {
                    continuation = it
                }
            }
        }
        if (continuation == null && comments.isNotEmpty()) {
            root.findAll("continuationItemRenderer", maxDepth = 14, limit = 6).forEach { cont ->
                extractContinuation(cont)?.let { continuation = it; return@forEach }
            }
        }
        return CommentsPage(comments, continuation)
    }

    // Comment bodies are no longer inline in the renderer tree: the thread
    // renderers only carry keys, and the actual text lives in the
    // frameworkUpdates entity batch, joined by commentId.
    private fun collectCommentEntities(root: JsonElement): Map<String, JsonElement> {
        val out = HashMap<String, JsonElement>()
        (root.path("frameworkUpdates", "entityBatchUpdate", "mutations") as? JsonArray)?.forEach { m ->
            val payload = m.path("payload", "commentEntityPayload") ?: return@forEach
            val id = payload.pathStr("properties", "commentId") ?: return@forEach
            out[id] = payload
        }
        return out
    }

    private fun collectContinuationItems(root: JsonElement): List<JsonElement> {
        val out = ArrayList<JsonElement>()
        // modern: onResponseReceivedEndpoints[].appendContinuationItemsAction / reloadContinuationItemsCommand
        (root.arr("onResponseReceivedEndpoints") ?: JsonArray(emptyList())).forEach { ep ->
            val items = ep.path("appendContinuationItemsAction", "continuationItems")
                ?: ep.path("reloadContinuationItemsCommand", "continuationItems")
            (items as? JsonArray)?.let { out.addAll(it) }
            ep.path("commandExecutorCommand", "commands")?.let { cmds ->
                (cmds as? JsonArray)?.forEach { cmd ->
                    (cmd.path("appendContinuationItemsAction", "continuationItems") as? JsonArray)?.let { out.addAll(it) }
                    (cmd.path("reloadContinuationItemsCommand", "continuationItems") as? JsonArray)?.let { out.addAll(it) }
                }
            }
        }
        if (out.isEmpty()) {
            (root.arr("onResponseReceivedActions") ?: JsonArray(emptyList())).forEach { ep ->
                (ep.path("appendContinuationItemsAction", "continuationItems") as? JsonArray)?.let { out.addAll(it) }
            }
        }
        if (out.isEmpty()) {
            // sometimes the section is inlined (initial render)
            root.findAll("commentThreadRenderer", maxDepth = 14, limit = 40).forEach { out.add(it) }
        }
        return out
    }

    private fun parseCommentThread(th: JsonElement, entities: Map<String, JsonElement>): CommentItem? {
        val vm = th.path("commentViewModel", "commentViewModel") ?: th.path("commentViewModel") ?: return null
        val commentId = vm.pathStr("commentId") ?: vm.pathStr("commentKey") ?: return null
        val entity = entities[commentId]
        val author = entity?.pathStr("author", "displayName") ?: vm.pathStr("author", "displayName").orEmpty()
        val avatar = entity?.pathStr("author", "avatarThumbnailUrl") ?: lastThumbUrl(vm.path("author", "avatar"))
        val text = entity?.pathStr("properties", "content", "content")
            ?: vm.pathStr("content", "content")
            ?: vm.path("content").runsText()
            ?: return null
        val likes = entity?.pathStr("toolbar", "likeCountNotliked")
            ?: entity?.pathStr("toolbar", "likeCountLiked")
            ?: vm.pathStr("likeCount", "likeCountEntity", "likeCount")
            ?: vm.pathStr("likeCount", "simpleText")
            ?: vm.pathStr("voteCount", "simpleText")
        val published = entity?.pathStr("properties", "publishedTime")
            ?: vm.pathStr("publishedTimeText", "simpleText")
            ?: vm.path("publishedTimeText").runsText()
            ?: vm.pathStr("publishedTimeText", "content")
        val replies = (entity?.pathStr("toolbar", "replyCount")
            ?: vm.pathStr("replyCount")
            ?: vm.pathStr("repliesCountText", "simpleText"))
            ?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { if (COUNT_RE.matches(it)) "$it replies" else it }
        if (text.isBlank()) return null
        return CommentItem(commentId, author, avatar, text, likes, published, replies, replyToken(th))
    }

    // The "show replies" continuation lives under the thread's replies node.
    // Older layouts nest it in commentRepliesRenderer.contents; newer ones
    // keep the same continuationItemRenderer shape at another depth.
    private fun replyToken(th: JsonElement): String? {
        val replies = th.path("replies") ?: return null
        replies.pathStr(
            "commentRepliesRenderer", "contents", 0,
            "continuationItemRenderer", "continuationEndpoint", "continuationCommand", "token",
        )?.let { return it }
        replies.findAll("continuationItemRenderer", maxDepth = 8, limit = 4).forEach { cont ->
            extractContinuation(cont)?.let { return it }
        }
        return null
    }

    private fun parseCommentRenderer(c: JsonElement): CommentItem? {
        val text = c.path("contentText").runsText() ?: return null
        val id = c.pathStr("commentId") ?: "c" + text.hashCode()
        val author = c.pathStr("authorText", "simpleText").orEmpty()
        val avatar = lastThumbUrl(c.path("authorThumbnail"))
        val likes = c.pathStr("voteCount", "simpleText")
        val published = c.path("publishedTimeText").runsText()
        val replies = c.pathStr("replyCount")?.let { "$it replies" }
        return CommentItem(id, author, avatar, text, likes, published, replies)
    }

    // ---- playlists --------------------------------------------------------------------

    suspend fun playlist(playlistId: String): PlaylistPage {
        val browseId = if (playlistId.startsWith("VL")) playlistId else "VL$playlistId"
        val root = browse(browseId)
        return parsePlaylist(root)
    }

    suspend fun playlistContinuation(token: String): PlaylistPage {
        val root = browse("", continuation = token)
        return parsePlaylist(root)
    }

    private fun parsePlaylist(root: JsonElement): PlaylistPage {
        var title = ""
        var author: String? = null
        var countText: String? = null
        root.findAll("playlistHeaderRenderer", maxDepth = 12, limit = 2).firstOrNull()?.let { h ->
            title = h.pathStr("title", "simpleText") ?: h.path("title").runsText().orEmpty()
            author = h.path("ownerText").runsText()
            countText = h.pathStr("numVideosText", "simpleText")?.let { "$it videos" }
                ?: h.pathStr("numVideosText", "runs", 0, "text")
        }
        if (title.isBlank()) {
            root.findAll("pageHeaderViewModel", maxDepth = 12, limit = 2).firstOrNull()?.let { h ->
                title = h.pathStr("title", "dynamicTextViewModel", "text", "content").orEmpty()
                val rows = h.path("metadata", "contentMetadataViewModel", "metadataRows") as? JsonArray
                rows?.forEach { row ->
                    (row.arr("metadataParts") ?: return@forEach).forEach { part ->
                        val t = part.pathStr("text", "content") ?: return@forEach
                        when {
                            Regex("videos?$", RegexOption.IGNORE_CASE).containsMatchIn(t) -> countText = t
                            !t.startsWith("@") && author == null -> author = t
                        }
                    }
                }
            }
        }
        if (title.isBlank()) {
            root.findAll("microformatDataRenderer", maxDepth = 10, limit = 1).firstOrNull()?.let { m ->
                title = m.pathStr("title").orEmpty()
            }
        }

        val videos = ArrayList<VideoItem>()
        var continuation: String? = null
        val direct = root.findAll("playlistVideoRenderer", maxDepth = 14, limit = 200)
        direct.forEach { pv ->
            parseVideoRenderer(pv)?.let { videos.add(it) }
        }
        root.findAll("continuationItemRenderer", maxDepth = 14, limit = 4).forEach { cont ->
            extractContinuation(cont)?.let { continuation = continuation ?: it }
        }
        return PlaylistPage(title.ifBlank { "Playlist" }, author, countText, videos, continuation)
    }

    // ---- renderer parsing -----------------------------------------------------------

    private fun parseAnyVideo(content: JsonElement): VideoItem? = when {
        content.obj("videoRenderer") != null -> parseVideoRenderer(content.obj("videoRenderer")!!)
        content.obj("gridVideoRenderer") != null -> parseVideoRenderer(content.obj("gridVideoRenderer")!!)
        content.obj("compactVideoRenderer") != null -> parseVideoRenderer(content.obj("compactVideoRenderer")!!)
        content.obj("playlistVideoRenderer") != null -> parseVideoRenderer(content.obj("playlistVideoRenderer")!!)
        content.obj("lockupViewModel") != null -> parseLockup(content.obj("lockupViewModel")!!)
        // richSectionRenderer (shelves incl. Shorts rails), reelShelfRenderer,
        // promotedSparklesWebRenderer, adSlotRenderer → skipped on purpose
        else -> null
    }

    private fun parseVideoRenderer(v: JsonElement): VideoItem? {
        val videoId = v.pathStr("videoId") ?: return null
        val title = v.path("title").let { it.runsText() ?: it.str("simpleText") }?.trim().orEmpty()
        if (title.isEmpty()) return null
        if (v.field("upcomingEventData") != null) return null
        val badges = v.arr("badges") ?: JsonArray(emptyList())
        if (badges.any { it.pathStr("metadataBadgeRenderer", "label")?.contains("Members", true) == true }) return null
        if (isShortish(v)) return null

        val live = badges.any {
            it.pathStr("metadataBadgeRenderer", "style") == "BADGE_STYLE_TYPE_LIVE_NOW"
        } || v.pathStr("lengthText", "simpleText") == "LIVE"

        val durationText = v.pathStr("lengthText", "simpleText")
            ?: v.path("lengthText").runsText()
        val duration = parseDuration(durationText)

        val views = v.pathStr("viewCountText", "simpleText")
            ?: v.path("viewCountText").runsText()
            ?: v.pathStr("shortViewCountText", "simpleText")
            ?: v.path("shortViewCountText").runsText()
        val published = v.pathStr("publishedTimeText", "simpleText")
            ?: v.path("publishedTimeText").runsText()

        val ownerRuns = (v.path("ownerText", "runs")
            ?: v.path("longBylineText", "runs")
            ?: v.path("shortBylineText", "runs")
            ?: v.path("videoOwnerRenderer", "title", "runs")) as? JsonArray
        val channelName = ownerRuns?.getOrNull(0)?.pathStr("text")
        val channelId = ownerRuns?.getOrNull(0)?.pathStr("navigationEndpoint", "browseEndpoint", "browseId")
            ?: v.pathStr("navigationEndpoint", "browseEndpoint", "browseId")
        val channelAvatar = lastThumbUrl(v.path("channelThumbnailSupportedRenderers", "channelThumbnailWithLinkRenderer", "thumbnail"))
            ?: lastThumbUrl(v.path("channelThumbnail"))

        val thumbnail = lastThumbUrl(v.path("thumbnail"))
            ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

        return VideoItem(
            videoId = videoId,
            title = title,
            channelName = channelName,
            channelId = channelId,
            channelAvatar = channelAvatar,
            thumbnail = thumbnail,
            durationSec = duration,
            viewsText = views,
            publishedText = published,
            isLive = live,
        )
    }

    // New universal item format (lockupViewModel) used by newer layouts.
    private fun parseLockup(lockup: JsonElement): VideoItem? {
        val contentType = lockup.pathStr("contentType") ?: return null
        if (!contentType.contains("VIDEO")) return null
        val videoId = lockup.pathStr("contentId") ?: return null
        val meta = lockup.path("metadata", "lockupMetadataViewModel") ?: return null
        val title = meta.pathStr("title", "content")?.trim().orEmpty()
        if (title.isEmpty()) return null

        // Feed lockups carry the thumbnail under metadata.image; the newer
        // watch-next "Up next" lockups carry it under contentImage instead.
        val thumbContainer = meta.path("image", "collectionThumbnailViewModel", "primaryThumbnail", "thumbnailViewModel")
            ?: lockup.path("contentImage", "thumbnailViewModel")
            ?: lockup.path("contentImage", "collectionThumbnailViewModel", "primaryThumbnail", "thumbnailViewModel")
        val thumbUrl = xthFirstUrl(thumbContainer.path("image"))
        if (thumbContainer != null && isShortish(thumbContainer)) return null

        var durationBadge: String? = null
        var isLive = false
        (thumbContainer.arr("overlays") ?: JsonArray(emptyList())).forEach { ov ->
            ov.findAll("thumbnailBadgeViewModel", maxDepth = 6, limit = 4).forEach { badge ->
                val text = badge.pathStr("text") ?: return@forEach
                if (text.equals("LIVE", true)) isLive = true
                else if (DURATION_RE.matches(text)) durationBadge = text
            }
        }

        var channelName: String? = null
        var channelId: String? = null
        var channelAvatar: String? = null
        var views: String? = null
        var published: String? = null

        // The owner block of a lockup carries the real avatar and channel
        // link outside the metadata rows (metadataParts only has text).
        val avatarVm = meta.path("image", "decoratedAvatarViewModel")
        channelAvatar = xthFirstUrl(avatarVm.path("avatar"))
        channelId = avatarVm.pathStr(
            "rendererContext", "commandContext", "onTap", "innertubeCommand", "browseEndpoint", "browseId",
        )
        channelName = avatarVm.pathStr("a11yLabel")
            ?.removePrefix("Go to channel ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        val rows = meta.path("metadata", "contentMetadataViewModel", "metadataRows") as? JsonArray
        rows?.forEach { row ->
            (row.arr("metadataParts") ?: return@forEach).forEach { part ->
                val t = part.pathStr("text", "content")
                val a11y = part.pathStr("accessibilityLabel")
                when {
                    t != null && VIEWS_RE.containsMatchIn(t) -> views = views ?: t
                    // newer layouts show a bare count ("21M") with the word
                    // "views" only in the accessibility label
                    t != null && AGO_RE.containsMatchIn(t) && !DURATION_RE.matches(t) -> published = published ?: t
                    a11y != null && VIEWS_RE.containsMatchIn(a11y) -> views = views ?: compactCount(a11y)
                    t != null && COUNT_RE.matches(t) -> views = views ?: "$t views"
                    t != null -> channelName = channelName ?: t
                }
                // older lockups ride the avatar and channel link on the part
                if (channelAvatar == null) channelAvatar = xthFirstUrl(part.path("avatar"))
                if (channelId == null) {
                    channelId = part.pathStr(
                        "text", "commandRuns", 0, "onTap", "innertubeCommand", "browseEndpoint", "browseId",
                    )
                }
            }
        }

        return VideoItem(
            videoId = videoId,
            title = title,
            channelName = channelName,
            channelId = channelId,
            channelAvatar = channelAvatar,
            thumbnail = thumbUrl ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            durationSec = parseDuration(durationBadge),
            viewsText = views,
            publishedText = published,
            isLive = isLive,
        )
    }

    // "21 million views" -> "21M views" so meta lines stay YouTube-tight.
    private fun compactCount(label: String): String = label
        .replace(Regex("\\s*thousand\\b", RegexOption.IGNORE_CASE), "K")
        .replace(Regex("\\s*billion\\b", RegexOption.IGNORE_CASE), "B")
        .replace(Regex("\\s*million\\b", RegexOption.IGNORE_CASE), "M")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun parseChannelRenderer(c: JsonElement): ChannelItem? {
        val channelId = c.pathStr("channelId") ?: return null
        val name = c.pathStr("title", "simpleText") ?: c.path("title").runsText() ?: return null
        val avatar = lastThumbUrl(c.path("thumbnail"))
        val subs = c.pathStr("subscriberCountText", "simpleText")
            ?: c.path("subscriberCountText").runsText()
            ?: c.pathStr("videoCountText", "simpleText")
            ?: c.path("videoCountText").runsText()
        val description = c.path("descriptionSnippet").runsText()
        return ChannelItem(channelId, name, avatar, subs, null, description)
    }

    private fun parsePlaylistRenderer(p: JsonElement): PlaylistItem? {
        val playlistId = p.pathStr("playlistId") ?: return null
        val title = p.pathStr("title", "simpleText") ?: p.path("title").runsText() ?: return null
        val count = p.pathStr("videoCountText", "simpleText")
            ?: p.path("videoCountText").runsText()
            ?: p.pathStr("videoCount")?.let { "$it videos" }
        val channel = (p.path("shortBylineText", "runs") as? JsonArray)?.getOrNull(0)?.pathStr("text")
            ?: (p.path("longBylineText", "runs") as? JsonArray)?.getOrNull(0)?.pathStr("text")
        val channelId = (p.path("shortBylineText", "runs") as? JsonArray)?.getOrNull(0)
            ?.pathStr("navigationEndpoint", "browseEndpoint", "browseId")
        val thumb = lastThumbUrl(p.path("thumbnail"))
        return PlaylistItem(playlistId, title, channel, channelId, thumb, count)
    }
}
