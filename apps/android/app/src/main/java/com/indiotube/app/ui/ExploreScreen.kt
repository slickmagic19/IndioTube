package com.indiotube.app.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.indiotube.app.Container
import com.indiotube.app.data.SearchEntry
import com.indiotube.app.data.SearchFilter
import com.indiotube.app.data.VideoItem
import kotlinx.coroutines.launch

// Explore, the way YouTube's tab works: Trending first (this week's most
// viewed videos for the region), then one feed per category borrowed from
// search. Everything is fetched lazily and cached in InnerTube, so flipping
// between chips is instant after the first visit.
private data class ExploreCategory(val label: String, val query: String?)

private val EXPLORE_CATEGORIES = listOf(
    ExploreCategory("Trending", null),
    ExploreCategory("Music", "music"),
    ExploreCategory("Gaming", "gaming"),
    ExploreCategory("News", "news"),
    ExploreCategory("Sports", "sports"),
    ExploreCategory("Movies", "movies"),
    ExploreCategory("Podcasts", "podcasts"),
    ExploreCategory("Live", "live"),
    ExploreCategory("Learning", "learning"),
)

private data class ExploreFeed(
    val videos: List<VideoItem> = emptyList(),
    val continuation: String? = null,
    val loading: Boolean = false,
    val error: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(container: Container, ui: UiRefs) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val account by container.accounts.active.collectAsState()
    val hiddenIds by container.library.notInterestedIds.collectAsState()
    val blockedChannels by container.library.blockedChannelIds.collectAsState()
    var selected by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    val feeds = remember { mutableStateMapOf<Int, ExploreFeed>() }

    suspend fun load(index: Int, force: Boolean = false) {
        val category = EXPLORE_CATEGORIES.getOrNull(index) ?: return
        val existing = feeds[index]
        if (!force && (existing?.loading == true || existing?.videos?.isNotEmpty() == true)) return
        feeds[index] = ExploreFeed(loading = true)
        try {
            val result = if (category.query == null) {
                val feed = container.innerTube.trending(force)
                ExploreFeed(videos = feed.videos, continuation = feed.continuation)
            } else {
                val r = container.innerTube.search(category.query, SearchFilter.VIDEOS)
                ExploreFeed(
                    videos = r.entries.filterIsInstance<SearchEntry.Video>().map { it.video },
                    continuation = r.continuation,
                )
            }
            feeds[index] = if (result.videos.isEmpty()) ExploreFeed(error = true) else result
        } catch (_: Exception) {
            feeds[index] = ExploreFeed(error = true)
        }
    }

    suspend fun loadMore() {
        val index = selected
        val category = EXPLORE_CATEGORIES.getOrNull(index) ?: return
        val current = feeds[index] ?: return
        val token = current.continuation ?: return
        if (loadingMore || current.loading) return
        loadingMore = true
        try {
            val (moreVideos, nextToken) = if (category.query == null) {
                val more = container.innerTube.trendingContinuation(token)
                more.videos to more.continuation
            } else {
                val r = container.innerTube.searchContinuation(token)
                r.entries.filterIsInstance<SearchEntry.Video>().map { it.video } to r.continuation
            }
            feeds[index]?.let { cur ->
                feeds[index] = cur.copy(
                    videos = (cur.videos + moreVideos).distinctBy { it.videoId },
                    continuation = nextToken,
                )
            }
        } catch (_: Exception) {
            /* keep what we have */
        } finally {
            loadingMore = false
        }
    }

    LaunchedEffect(selected) {
        load(selected)
        listState.scrollToItem(0)
    }

    LaunchedEffect(listState, selected) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                val feed = feeds[selected] ?: return@collect
                if (last >= 0 && last >= feed.videos.size - 3) loadMore()
            }
    }

    val feed = feeds[selected]
    val category = EXPLORE_CATEGORIES[selected]
    val sectionTitle = if (category.query == null) "Trending now" else category.label

    Column(Modifier.fillMaxSize()) {
        // Same top-bar rhythm as Home and You: title on the left, search and
        // the account avatar on the right.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 10.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Explore",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = YtText,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { ui.open(Screen.Search) }) {
                Icon(Icons.Outlined.Search, "Search", tint = YtText, modifier = Modifier.size(24.dp))
            }
            ChannelAvatar(
                name = account?.name,
                url = null,
                size = 30.dp,
                modifier = Modifier
                    .padding(end = 2.dp)
                    .clickable { ui.openAccounts() },
            )
        }
        LazyRow(
            Modifier.padding(top = 2.dp, bottom = 4.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
        ) {
            itemsIndexed(EXPLORE_CATEGORIES) { i, item ->
                FeedChip(
                    title = item.label,
                    selected = i == selected,
                    onClick = { selected = i },
                )
                Spacer(Modifier.width(8.dp))
            }
        }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    container.innerTube.invalidate()
                    load(selected, force = true)
                    listState.scrollToItem(0)
                    refreshing = false
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                feed == null || (feed.loading && feed.videos.isEmpty()) ->
                    SkeletonFeed(Modifier.fillMaxSize(), showHeader = true, scrollable = true)
                feed.error && feed.videos.isEmpty() -> ErrorState(
                    "Couldn't load Explore right now",
                    onRetry = {
                        scope.launch {
                            load(selected, force = true)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                else -> {
                    val videos = feed.videos.filter { v ->
                        v.videoId !in hiddenIds &&
                            (v.channelId == null || v.channelId !in blockedChannels)
                    }
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        item(key = "section") { SectionHeader(sectionTitle) }
                        if (videos.isEmpty()) {
                            item(key = "empty") {
                                EmptyState(
                                    title = "Nothing here right now",
                                    subtitle = "Pull down to refresh, or try another category.",
                                    modifier = Modifier.fillParentMaxSize(),
                                )
                            }
                        }
                        items(videos, key = { it.videoId }) { video ->
                            VideoCard(
                                video,
                                onClick = { ui.openWatch(video, videos) },
                                onChannelClick = { ui.openChannelOf(video) },
                                onFeedback = { ui.feedback(video, it) },
                                onDownload = { ui.download(video) },
                                onPlayNext = { ui.playNext(video) },
                                onAddToQueue = { ui.addToQueue(video) },
                            )
                        }
                        if (loadingMore) {
                            item(key = "loading-more") { LoadingBox() }
                        }
                        item(key = "bottom-spacer") { Spacer(Modifier.height(8.dp)) }
                    }
                }
            }
        }
    }
}
