package com.indiotube.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Subscriptions
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indiotube.app.Container
import com.indiotube.app.data.VideoItem
import com.indiotube.app.data.publishAgeMinutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen(container: Container, ui: UiRefs) {
    val scope = rememberCoroutineScope()
    val subs by container.accounts.subscriptions.collectAsState()
    val account by container.accounts.active.collectAsState()

    var videos by remember { mutableStateOf<List<VideoItem>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var selectedChannel by remember { mutableStateOf<String?>(null) }

    suspend fun load(force: Boolean) {
        val subsList = container.accounts.subscriptions.value
        if (subsList.isEmpty()) {
            videos = emptyList()
            return
        }
        loading = true
        val sem = Semaphore(5)
        val all = coroutineScope {
            subsList.take(60).map { sub ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        runCatching {
                            container.innerTube
                                .channelVideos(sub.channelId, force = force)
                                .videos
                                .take(8)
                        }.getOrElse { emptyList() }
                    }
                }
            }.awaitAll().flatten()
        }
        videos = all
            .distinctBy { it.videoId }
            .sortedBy { publishAgeMinutes(it.publishedText) }
        loading = false
    }

    LaunchedEffect(account?.id) {
        videos = null
        load(false)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 6.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Subscriptions",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = YtText,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { scope.launch { load(true) } }) {
                Icon(Icons.Filled.Refresh, "Refresh", tint = YtText, modifier = Modifier.size(24.dp))
            }
        }

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    load(true)
                    refreshing = false
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                subs.isEmpty() -> EmptyState(
                    title = "No subscriptions yet",
                    subtitle = "Open a channel and tap Subscribe — their newest videos will show up here.",
                    icon = {
                        Icon(
                            Icons.Outlined.Subscriptions,
                            null,
                            tint = YtMuted,
                            modifier = Modifier.size(48.dp),
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                videos == null && loading -> LoadingBox(Modifier.fillMaxSize())
                else -> {
                    val list = videos.orEmpty()
                        .filter { selectedChannel == null || it.channelId == selectedChannel }
                    Column(Modifier.fillMaxSize()) {
                        LazyRow(
                            Modifier.padding(vertical = 4.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                        ) {
                            item {
                                FeedChip("All", selectedChannel == null) { selectedChannel = null }
                                Spacer(Modifier.width(8.dp))
                            }
                            items(subs.size) { i ->
                                val sub = subs[i]
                                Row(
                                    Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (selectedChannel == sub.channelId) YtChipSelected else YtChip)
                                        .clickable {
                                            selectedChannel = if (selectedChannel == sub.channelId) null else sub.channelId
                                        }
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    ChannelAvatar(sub.name, sub.avatar, 24.dp)
                                    Spacer(Modifier.width(7.dp))
                                    Text(
                                        sub.name,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (selectedChannel == sub.channelId) Color(0xFF0F0F0F) else YtText,
                                        maxLines = 1,
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                            }
                        }
                        if (list.isEmpty() && !loading) {
                            EmptyState(
                                title = "Nothing new here",
                                subtitle = "Pull to refresh, or check back later.",
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            VideoFeedList(list, ui)
                        }
                    }
                }
            }
        }
    }
}
