package com.indiotube.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indiotube.app.Container
import com.indiotube.app.data.ChannelItem
import com.indiotube.app.data.ChannelPage
import com.indiotube.app.data.PlaylistItem
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelScreen(container: Container, ui: UiRefs, channelId: String, name: String?, avatar: String?) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val active by container.accounts.active.collectAsState()

    var page by remember { mutableStateOf<ChannelPage?>(null) }
    var playlists by remember { mutableStateOf<List<PlaylistItem>>(emptyList()) }
    var tabIdx by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun load(force: Boolean) {
        try {
            val p = container.innerTube.channelVideos(channelId, force = force)
            page = p
            error = null
            if (playlists.isEmpty() || force) {
                playlists = container.innerTube.channelPlaylists(channelId, force = force)
            }
        } catch (e: Exception) {
            if (page == null) error = e.message ?: "Couldn't load this channel"
        }
    }

    LaunchedEffect(channelId) {
        loading = true
        load(false)
        loading = false
    }

    LaunchedEffect(listState, tabIdx) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                if (tabIdx != 0) return@collect
                val p = page ?: return@collect
                val token = p.continuation ?: return@collect
                if (last >= p.videos.size - 3 && !loadingMore && last >= 0) {
                    loadingMore = true
                    try {
                        val more = container.innerTube.channelContinuation(token)
                        page = ChannelPage(p.channel, p.videos + more.videos, more.continuation)
                    } catch (_: Exception) { /* keep what we have */ }
                    loadingMore = false
                }
            }
    }

    val ch = page?.channel
    val dispName = ch?.name?.takeIf { it.isNotBlank() } ?: name ?: "Channel"
    val dispAvatar = ch?.avatar ?: avatar
    val subscribed = active?.subscriptions?.any { it.channelId == channelId } == true

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { ui.back() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = YtText)
            }
            Text(
                dispName,
                style = MaterialTheme.typography.titleMedium,
                color = YtText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
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
                loading && page == null -> LoadingBox(Modifier.fillMaxSize())
                error != null && page == null -> ErrorState(error!!, onRetry = {
                    scope.launch {
                        loading = true
                        load(true)
                        loading = false
                    }
                }, modifier = Modifier.fillMaxSize())
                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    item(key = "header") {
                        Column(
                            Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            ChannelAvatar(dispName, dispAvatar, 84.dp)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                dispName,
                                style = MaterialTheme.typography.headlineSmall,
                                color = YtText,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                modifier = Modifier.padding(horizontal = 24.dp),
                            )
                            val sub = listOfNotNull(
                                ch?.subscribersText,
                                ch?.videoCountText,
                            ).joinToString(" • ")
                            if (sub.isNotBlank()) {
                                Spacer(Modifier.height(5.dp))
                                Text(sub, style = MaterialTheme.typography.bodySmall, color = YtMuted)
                            }
                            Spacer(Modifier.height(14.dp))
                            SubscribeButton(subscribed) {
                                ui.toggleSubscribe(
                                    ChannelItem(
                                        channelId = channelId,
                                        name = dispName,
                                        avatar = dispAvatar,
                                        subscribersText = ch?.subscribersText,
                                        videoCountText = ch?.videoCountText,
                                    ),
                                )
                            }
                            if (!ch?.description.isNullOrBlank()) {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    ch?.description.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = YtMuted,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 28.dp),
                                )
                            }
                        }
                    }
                    item(key = "tabs") {
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            ChannelTab("Videos", tabIdx == 0, Modifier.weight(1f)) { tabIdx = 0 }
                            ChannelTab("Playlists", tabIdx == 1, Modifier.weight(1f)) { tabIdx = 1 }
                        }
                    }
                    if (tabIdx == 0) {
                        val videos = page?.videos.orEmpty()
                        if (videos.isEmpty() && !loading) {
                            item {
                                EmptyState(
                                    title = "No videos yet",
                                    subtitle = "This channel hasn't posted anything we can show.",
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        items(videos, key = { it.videoId }) { video ->
                            VideoCard(
                                video,
                                onClick = { ui.openWatch(video, videos) },
                                onChannelClick = { ui.openChannel(channelId, dispName, dispAvatar) },
                                onDownload = { ui.download(video) },
                            )
                        }
                        if (loadingMore) {
                            item { LoadingBox(Modifier.height(70.dp)) }
                        }
                    } else {
                        if (playlists.isEmpty() && !loading) {
                            item {
                                EmptyState(
                                    title = "No playlists",
                                    subtitle = "This channel has no public playlists.",
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        items(playlists, key = { "pl-" + it.playlistId }) { pl ->
                            ChannelPlaylistRow(pl) {
                                ui.open(Screen.YtPlaylist(pl.playlistId, pl.title))
                            }
                        }
                    }
                    item { Spacer(Modifier.height(10.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ChannelTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                ),
                color = if (selected) YtText else YtMuted,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (selected) YtText else Color.Transparent),
        )
    }
}

@Composable
private fun ChannelPlaylistRow(pl: PlaylistItem, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Thumb(
                model = pl.thumbnail,
                corner = 8.dp,
                modifier = Modifier.width(150.dp).height(84.dp),
            )
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                    .background(YtBadge)
                    .padding(vertical = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        pl.videoCountText ?: "Playlist",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                pl.title,
                style = MaterialTheme.typography.titleSmall,
                color = YtText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "View full playlist",
                style = MaterialTheme.typography.bodySmall,
                color = YtMuted,
            )
        }
    }
}
