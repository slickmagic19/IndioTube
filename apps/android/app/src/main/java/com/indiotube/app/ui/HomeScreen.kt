package com.indiotube.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indiotube.app.Container
import com.indiotube.app.data.HomeFeed
import com.indiotube.app.data.SearchEntry
import com.indiotube.app.data.SearchFilter
import com.indiotube.app.data.VideoItem
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(container: Container, ui: UiRefs) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val account by container.accounts.active.collectAsState()

    var feed by remember { mutableStateOf<HomeFeed?>(null) }
    var selectedChip by remember { mutableIntStateOf(0) }
    var localChip by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var localVideos by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var localLoading by remember { mutableStateOf(false) }
    val localFeeds = remember { mutableStateMapOf<String, LocalCategoryFeed>() }

    suspend fun load(force: Boolean = false, params: String? = null, token: String? = null) {
        // Returning to an old feed shouldn't show yesterday's picks — rebuild
        // against fresh channel data when the last build is stale.
        val stale = container.lastHomeRefreshAt > 0 &&
            System.currentTimeMillis() - container.lastHomeRefreshAt > 30 * 60_000L
        val refresh = force || stale
        try {
            val result = if (token != null) {
                container.innerTube.homeContinuation(token)
            } else {
                container.innerTube.home(params, refresh)
            }
            feed = result
            error = null
            if (token == null && result.videos.isEmpty()) {
                // YouTube serves no recommendations to signed-out sessions,
                // so fall back to a feed built from this device's data:
                // subscriptions, watch history and related videos.
                localLoading = true
                localVideos = try {
                    container.localRecommendations(refresh)
                } catch (_: Exception) {
                    emptyList()
                }
                localLoading = false
            } else if (result.videos.isNotEmpty()) {
                localVideos = emptyList()
            }
            if (token == null && (result.videos.isNotEmpty() || localVideos.isNotEmpty())) {
                container.lastHomeRefreshAt = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            if (feed == null) error = e.message ?: "Couldn't load your feed"
        }
    }

    // Local-mode filter tab: pull one page of search results for the category
    // and cache it, so returning to a tab doesn't refetch.
    suspend fun loadLocalCategory(label: String, force: Boolean = false) {
        val existing = localFeeds[label]
        if (!force && existing != null && (existing.loading || existing.videos.isNotEmpty())) return
        localFeeds[label] = LocalCategoryFeed(loading = true)
        try {
            val r = container.innerTube.search(label.lowercase(), SearchFilter.VIDEOS)
            localFeeds[label] = LocalCategoryFeed(
                videos = r.entries.filterIsInstance<SearchEntry.Video>().map { it.video },
                continuation = r.continuation,
            )
        } catch (_: Exception) {
            localFeeds[label] = LocalCategoryFeed(error = true)
        }
    }

    LaunchedEffect(account?.id) {
        loading = true
        load()
        loading = false
    }

    LaunchedEffect(listState, feed, localChip, localVideos) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                if (last < 0 || loadingMore) return@collect
                val f = feed
                val usingLocalNow = f != null && f.videos.isEmpty() && localVideos.isNotEmpty()
                if (usingLocalNow && localChip > 0) {
                    val label = LOCAL_FEED_CHIPS.getOrNull(localChip) ?: return@collect
                    val catFeed = localFeeds[label] ?: return@collect
                    val token = catFeed.continuation ?: return@collect
                    if (catFeed.videos.isEmpty() || last < catFeed.videos.size - 3) return@collect
                    loadingMore = true
                    try {
                        val r = container.innerTube.searchContinuation(token)
                        localFeeds[label]?.let { cur ->
                            localFeeds[label] = cur.copy(
                                videos = (cur.videos + r.entries.filterIsInstance<SearchEntry.Video>()
                                    .map { it.video }).distinctBy { it.videoId },
                                continuation = r.continuation,
                            )
                        }
                    } catch (_: Exception) { /* keep what we have */ }
                    loadingMore = false
                    return@collect
                }
                val token = f?.continuation ?: return@collect
                if (last >= f.videos.size - 3) {
                    loadingMore = true
                    try {
                        val more = container.innerTube.homeContinuation(token)
                        val latest = feed ?: f
                        feed = HomeFeed(latest.chips, latest.videos + more.videos, more.continuation)
                    } catch (_: Exception) { /* keep what we have */ }
                    loadingMore = false
                }
            }
    }

    Column(Modifier.fillMaxSize()) {
        // top bar: wordmark + search + account avatar
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(width = 26.dp, height = 18.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(YtRed),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
            Spacer(Modifier.width(7.dp))
            Text(
                "IndioTube",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.6).sp,
                ),
                color = YtText,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { ui.open(Screen.Search) }) {
                Icon(Icons.Outlined.Search, "Search", tint = YtText, modifier = Modifier.size(25.dp))
            }
            ChannelAvatar(
                name = account?.name,
                url = null,
                size = 30.dp,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .clickable { ui.openAccounts() },
            )
        }

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    localChip = 0
                    localFeeds.clear()
                    load(force = true)
                    refreshing = false
                    // a refreshed feed is a new mix — start it from the top
                    listState.animateScrollToItem(0)
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                loading && feed == null -> LoadingBox(Modifier.fillMaxSize())
                error != null && feed == null -> ErrorState(error!!, onRetry = {
                    scope.launch {
                        loading = true
                        load(force = true)
                        loading = false
                    }
                }, modifier = Modifier.fillMaxSize())
                else -> {
                    val f = feed
                    val videos = f?.videos.orEmpty()
                    val usingLocal = videos.isEmpty() && localVideos.isNotEmpty()
                    val localLabel = LOCAL_FEED_CHIPS.getOrNull(localChip)
                    val categoryMode = usingLocal && localChip > 0
                    val categoryFeed = if (categoryMode) localFeeds[localLabel] else null
                    val shown = when {
                        categoryMode -> categoryFeed?.videos.orEmpty()
                        usingLocal -> localVideos
                        else -> videos
                    }
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        val chips = f?.chips.orEmpty()
                        if (usingLocal) {
                            // Local feed: YouTube-style category tabs, filled
                            // from search since a signed-out home has no chips.
                            item(key = "local-chips") {
                                LazyRow(
                                    Modifier.padding(vertical = 2.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                                ) {
                                    itemsIndexed(LOCAL_FEED_CHIPS) { i, title ->
                                        FeedChip(
                                            title = title,
                                            selected = i == localChip,
                                            onClick = {
                                                localChip = i
                                                if (i > 0) scope.launch { loadLocalCategory(title) }
                                            },
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                }
                            }
                        } else if (chips.isNotEmpty()) {
                            item(key = "chips") {
                                LazyRow(
                                    Modifier.padding(vertical = 2.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                                ) {
                                    itemsIndexed(chips) { i, chip ->
                                        FeedChip(
                                            title = chip.title,
                                            selected = i == selectedChip,
                                            onClick = {
                                                selectedChip = i
                                                scope.launch {
                                                    load(params = chip.params, token = if (chip.params == null) chip.token else null)
                                                }
                                            },
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                }
                            }
                        }
                        if (usingLocal) {
                            item(key = "recommended") {
                                SectionHeader(if (categoryMode) localLabel.orEmpty() else "Recommended for you")
                            }
                        }
                        items(shown, key = { it.videoId }) { video ->
                            VideoCard(
                                video,
                                onClick = { ui.openWatch(video, shown) },
                                onChannelClick = { ui.openChannelOf(video) },
                            )
                        }
                        if (categoryMode && categoryFeed == null) {
                            item(key = "category-loading") { LoadingBox(Modifier.height(160.dp)) }
                        }
                        if (categoryMode && categoryFeed?.error == true && shown.isEmpty()) {
                            item(key = "category-error") {
                                ErrorState(
                                    "Couldn't load ${localLabel.orEmpty()} right now",
                                    onRetry = {
                                        scope.launch { loadLocalCategory(localLabel.orEmpty(), force = true) }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        if (localLoading && shown.isEmpty() && !categoryMode) {
                            item(key = "local-loading") { LoadingBox(Modifier.height(160.dp)) }
                        }
                        // Only show the signed-out prompt when neither YouTube
                        // nor the local fallback feed had anything to offer.
                        if (f != null && shown.isEmpty() && !loadingMore && !localLoading && !categoryMode) {
                            item(key = "empty") {
                                EmptyState(
                                    title = "Try searching to get started",
                                    subtitle = "Start watching videos to help us build a feed of videos you'll love.",
                                    icon = {
                                        Icon(
                                            Icons.Outlined.Search,
                                            null,
                                            tint = YtMuted,
                                            modifier = Modifier.size(42.dp),
                                        )
                                    },
                                    actionLabel = "Search",
                                    onAction = { ui.open(Screen.Search) },
                                )
                            }
                        }
                        if (loadingMore) {
                            item { LoadingBox(Modifier.height(80.dp)) }
                        }
                        item { Spacer(Modifier.height(8.dp)) }
                    }
                }
            }
        }
    }
}

// Filter tabs for the locally computed feed (a signed-out YouTube home has no
// chips of its own). "All" is the ranked recommendation mix; every other tab
// is filled from search, like YouTube's topic chips.
private val LOCAL_FEED_CHIPS = listOf(
    "All", "Music", "Gaming", "News", "Sports", "Movies", "Podcasts", "Live", "Learning",
)

private data class LocalCategoryFeed(
    val videos: List<VideoItem> = emptyList(),
    val continuation: String? = null,
    val loading: Boolean = false,
    val error: Boolean = false,
)

@Composable
fun FeedChip(title: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) YtChipSelected else YtChip)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Color(0xFF0F0F0F) else YtText,
            maxLines = 1,
        )
    }
}

// shared by subscriptions/channel screens: full-width list of feed cards
@Composable
fun VideoFeedList(
    videos: List<VideoItem>,
    ui: UiRefs,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
    footer: (@Composable () -> Unit)? = null,
) {
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(videos, key = { it.videoId + it.title.hashCode() }) { video ->
            VideoCard(
                video,
                onClick = { ui.openWatch(video, videos) },
                onChannelClick = { ui.openChannelOf(video) },
            )
        }
        if (footer != null) {
            item { footer() }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}
