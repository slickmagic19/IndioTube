package com.indiotube.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indiotube.app.Container
import com.indiotube.app.data.ChannelAbout
import com.indiotube.app.data.ChannelItem
import com.indiotube.app.data.ChannelPage
import com.indiotube.app.data.PlaylistItem
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelScreen(container: Container, ui: UiRefs, screen: Screen.Channel) {
    val channelId = screen.channelId
    val name = screen.name
    val avatar = screen.avatar
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val active by container.accounts.active.collectAsState()
    val context = LocalContext.current

    var page by remember { mutableStateOf<ChannelPage?>(null) }
    var playlists by remember(channelId) { mutableStateOf<List<PlaylistItem>>(emptyList()) }
    var playlistsToken by remember(channelId) { mutableStateOf<String?>(null) }
    var loadingMorePlaylists by remember(channelId) { mutableStateOf(false) }
    var playlistsLoaded by remember(channelId) { mutableStateOf(false) }
    var playlistsLoading by remember(channelId) { mutableStateOf(false) }
    var playlistsError by remember(channelId) { mutableStateOf<String?>(null) }
    // Not every channel has a Playlists tab; the old hardcoded fallback asked
    // YouTube for a tab that does not exist and showed whatever came back.
    var hasPlaylistsTab by remember(channelId) { mutableStateOf(true) }
    // The selected tab lives on the screen object, not in this composable:
    // the screen is torn down while a playlist is open on top of it, and
    // going back must land on the Playlists tab again.
    val tabIdx = screen.selectedTab.value
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var livePage by remember(channelId) { mutableStateOf<ChannelPage?>(null) }
    var liveLoading by remember(channelId) { mutableStateOf(false) }
    var liveError by remember(channelId) { mutableStateOf<String?>(null) }

    var about by remember(channelId) { mutableStateOf<ChannelAbout?>(null) }
    var aboutLoading by remember(channelId) { mutableStateOf(false) }
    var aboutError by remember(channelId) { mutableStateOf<String?>(null) }

    suspend fun load(force: Boolean) {
        try {
            val p = container.innerTube.channelVideos(channelId, force = force)
            page = p
            error = null
        } catch (e: Exception) {
            if (page == null) error = e.message ?: "Couldn't load this channel"
        }
    }

    // The playlists are loaded when their tab is opened instead of together
    // with the videos: most channels are visited for their videos, and the
    // Playlists tab costs an extra InnerTube round trip.
    suspend fun loadPlaylists(force: Boolean) {
        playlistsLoading = true
        playlistsError = null
        try {
            val pl = container.innerTube.channelPlaylistsPage(channelId, force = force)
            playlists = pl.playlists
            playlistsToken = pl.continuation
            hasPlaylistsTab = pl.hasTab
            playlistsLoaded = true
        } catch (e: Exception) {
            playlistsError = e.message ?: "Couldn't load this channel's playlists"
            playlistsLoaded = true
        }
        loadingMorePlaylists = false
        playlistsLoading = false
    }

    suspend fun loadLive(force: Boolean) {
        liveLoading = true
        liveError = null
        try {
            livePage = container.innerTube.channelLive(channelId, force)
        } catch (e: Exception) {
            liveError = e.message ?: "Couldn't load live videos"
        }
        liveLoading = false
    }

    suspend fun loadAbout(force: Boolean) {
        aboutLoading = true
        aboutError = null
        about = container.innerTube.channelAbout(channelId, page?.aboutToken, force)
        if (about == null) aboutError = "Couldn't load this channel's info"
        aboutLoading = false
    }

    LaunchedEffect(channelId) {
        loading = true
        load(false)
        loading = false
    }

    LaunchedEffect(channelId, tabIdx) {
        if (tabIdx == CHANNEL_TAB_LIVE && livePage == null && !liveLoading) loadLive(false)
        if (tabIdx == CHANNEL_TAB_PLAYLISTS && !playlistsLoaded && !playlistsLoading) loadPlaylists(false)
        if (tabIdx == CHANNEL_TAB_ABOUT && about == null && !aboutLoading) loadAbout(false)
    }

    // Playlists are laid out as a grid of cards, so the list holds rows of
    // cards instead of one item per playlist.
    val columns = playlistGridColumns(LocalConfiguration.current.screenWidthDp.toFloat())
    val playlistRows = remember(playlists, columns) { playlistGridRows(playlists, columns) }

    LaunchedEffect(listState, tabIdx, columns) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                when (tabIdx) {
                    CHANNEL_TAB_VIDEOS -> {
                        val p = page ?: return@collect
                        val token = p.continuation ?: return@collect
                        if (last >= p.videos.size - 3 && !loadingMore && last >= 0) {
                            loadingMore = true
                            try {
                                val more = container.innerTube.channelContinuation(token)
                                page = p.copy(videos = p.videos + more.videos, continuation = more.continuation)
                            } catch (_: Exception) { /* keep what we have */ }
                            loadingMore = false
                        }
                    }

                    2 -> {
                        val token = playlistsToken ?: return@collect
                        // The channel header and the tab row are the first two
                        // list items; the playlist rows follow them. The rows
                        // are rebuilt from the live list so a page that was
                        // just appended is measured, not the one from when
                        // this effect started.
                        val rows = playlistGridRows(playlists, columns)
                        val lastRow = last - 2
                        if (lastRow >= rows.size - 2 && rows.isNotEmpty() && !loadingMorePlaylists) {
                            loadingMorePlaylists = true
                            try {
                                val more = container.innerTube.channelPlaylistsContinuation(token)
                                playlists = (playlists + more.playlists).distinctBy { it.playlistId }
                                playlistsToken = more.continuation
                                    ?.takeUnless { it == token }
                            } catch (_: Exception) { /* keep what we have */ }
                            loadingMorePlaylists = false
                        }
                    }
                }
            }
    }

    val ch = page?.channel
    val dispName = ch?.name?.takeIf { it.isNotBlank() } ?: name ?: "Channel"
    val dispAvatar = ch?.avatar?.takeIf { it.isNotBlank() } ?: avatar
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
                    if (tabIdx == CHANNEL_TAB_LIVE) loadLive(true)
                    if (tabIdx == CHANNEL_TAB_PLAYLISTS) loadPlaylists(true)
                    if (tabIdx == CHANNEL_TAB_ABOUT) loadAbout(true)
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
                        ChannelTabRow(screen)
                    }
                    when (tabIdx) {
                        0 -> {
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
                                    onPlayNext = { ui.playNext(video) },
                                    onAddToQueue = { ui.addToQueue(video) },
                                )
                            }
                            if (loadingMore) {
                                item { LoadingBox(Modifier.height(70.dp)) }
                            }
                        }

                    CHANNEL_TAB_LIVE -> {
                        val videos = livePage?.videos.orEmpty()
                            when {
                                livePage == null && liveError == null -> item(key = "live-loading") {
                                    LoadingBox(Modifier.height(120.dp))
                                }
                                liveError != null && livePage == null -> item(key = "live-error") {
                                    ErrorState(
                                        liveError!!,
                                        onRetry = { scope.launch { loadLive(true) } },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                videos.isEmpty() -> item(key = "live-empty") {
                                    EmptyState(
                                        title = "No live videos",
                                        subtitle = "This channel isn't live right now and has no recent streams.",
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                else -> items(videos, key = { "live-" + it.videoId }) { video ->
                                    VideoCard(
                                        video,
                                        onClick = { ui.openWatch(video, videos) },
                                        onChannelClick = { ui.openChannel(channelId, dispName, dispAvatar) },
                                        onDownload = { ui.download(video) },
                                        onPlayNext = { ui.playNext(video) },
                                        onAddToQueue = { ui.addToQueue(video) },
                                    )
                                }
                            }
                        }

                    CHANNEL_TAB_PLAYLISTS -> {
                        when {
                            !playlistsLoaded && playlistsLoading -> item(key = "pl-loading") {
                                    LoadingBox(Modifier.height(120.dp))
                                }

                                playlistsError != null && playlists.isEmpty() -> item(key = "pl-error") {
                                    ErrorState(
                                        playlistsError!!,
                                        onRetry = { scope.launch { loadPlaylists(true) } },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }

                                !hasPlaylistsTab -> item(key = "pl-no-tab") {
                                    EmptyState(
                                        title = "No playlists",
                                        subtitle = "This channel doesn't have a playlists section.",
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }

                                playlists.isEmpty() -> item(key = "pl-empty") {
                                    EmptyState(
                                        title = "No playlists",
                                        subtitle = "This channel has no public playlists.",
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }

                                else -> {
                                    itemsIndexed(
                                        playlistRows,
                                        key = { _, row -> "pl-row-" + row.first().playlistId },
                                    ) { _, row ->
                                        ChannelPlaylistRow(row, columns) { pl ->
                                            ui.open(Screen.YtPlaylist(pl.playlistId, pl.title))
                                        }
                                    }
                                    if (loadingMorePlaylists) {
                                        item(key = "pl-loading-more") { LoadingBox(Modifier.height(70.dp)) }
                                    }
                                }
                            }
                        }

                        else -> item(key = "about") {
                            ChannelAboutTab(
                                about = about,
                                loading = aboutLoading,
                                error = aboutError,
                                onRetry = { scope.launch { loadAbout(true) } },
                                onOpenLink = { url ->
                                    try {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                    } catch (_: Exception) {
                                        ui.toast("Couldn't open this link")
                                    }
                                },
                            )
                        }
                    }
                    item { Spacer(Modifier.height(10.dp)) }
                }
            }
        }
    }
}

// The tab row of a channel. The selected tab lives on the screen object, so
// it survives a playlist screen opened on top of it: going back returns to
// the Playlists tab instead of the videos.
@Composable
internal fun ChannelTabRow(screen: Screen.Channel, modifier: Modifier = Modifier) {
    val selected = screen.selectedTab.value
    Row(modifier.fillMaxWidth().padding(top = 8.dp)) {
        ChannelTab("Videos", selected == CHANNEL_TAB_VIDEOS, Modifier.weight(1f)) {
            screen.selectedTab.value = CHANNEL_TAB_VIDEOS
        }
        ChannelTab("Live", selected == CHANNEL_TAB_LIVE, Modifier.weight(1f)) {
            screen.selectedTab.value = CHANNEL_TAB_LIVE
        }
        ChannelTab("Playlists", selected == CHANNEL_TAB_PLAYLISTS, Modifier.weight(1f)) {
            screen.selectedTab.value = CHANNEL_TAB_PLAYLISTS
        }
        ChannelTab("About", selected == CHANNEL_TAB_ABOUT, Modifier.weight(1f)) {
            screen.selectedTab.value = CHANNEL_TAB_ABOUT
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

// YouTube shows a channel's playlists as a grid of cards: a 16:9 cover on
// top, the title underneath and the video count in the corner of the cover.
// The old layout put one small cover in a full-width row, which left the tab
// looking broken next to the rest of the app.
internal fun playlistGridColumns(widthDp: Float): Int = when {
    widthDp < 320f -> 1
    widthDp < 600f -> 2
    widthDp < 840f -> 3
    else -> 4
}

internal fun playlistGridRows(playlists: List<PlaylistItem>, columns: Int): List<List<PlaylistItem>> =
    playlists.chunked(columns.coerceAtLeast(1))

// Lets the layout test find a playlist cover.
internal const val PlaylistCoverTag = "playlist-cover"

@Composable
fun ChannelPlaylistRow(
    row: List<PlaylistItem>,
    columns: Int,
    onOpen: (PlaylistItem) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
        row.forEach { pl ->
            ChannelPlaylistCard(pl, Modifier.weight(1f)) { onOpen(pl) }
        }
        // The last row is usually short; the spacers keep its cards the same
        // width as the rows above instead of stretching them.
        repeat((columns - row.size).coerceAtLeast(0)) {
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun ChannelPlaylistCard(pl: PlaylistItem, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
    ) {
        Thumb(
            model = pl.thumbnail,
            corner = 10.dp,
            badge = pl.videoCountText,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .testTag(PlaylistCoverTag),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            pl.title,
            style = MaterialTheme.typography.titleSmall,
            color = YtText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "Playlist",
            style = MaterialTheme.typography.bodySmall,
            color = YtMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// About tab: the full description, channel stats and external links YouTube
// keeps behind the "more about this channel" panel.
@Composable
private fun ChannelAboutTab(
    about: ChannelAbout?,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onOpenLink: (String) -> Unit,
) {
    when {
        about == null && (loading || error == null) -> LoadingBox(Modifier.height(120.dp))
        about == null -> ErrorState(
            error ?: "Couldn't load this channel's info",
            onRetry = onRetry,
            modifier = Modifier.fillMaxWidth(),
        )
        else -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (!about.description.isNullOrBlank()) {
                Text(
                    "Description",
                    style = MaterialTheme.typography.titleSmall,
                    color = YtText,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    about.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = YtText,
                    lineHeight = 20.sp,
                )
                Spacer(Modifier.height(18.dp))
            }
            val stats = listOfNotNull(
                about.subscribersText?.let { "Subscribers" to it },
                about.videoCountText?.let { "Videos" to it },
                about.viewCountText?.let { "Views" to it },
                about.joinedText?.removePrefix("Joined ")?.let { "Joined" to it },
                about.country?.let { "Country" to it },
            )
            stats.forEach { (label, value) -> AboutStatRow(label, value) }
            if (about.links.isNotEmpty()) {
                HorizontalDivider(color = YtDivider, modifier = Modifier.padding(vertical = 10.dp))
                Text(
                    "Links",
                    style = MaterialTheme.typography.titleSmall,
                    color = YtText,
                )
                about.links.forEach { link ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenLink(link.url) }
                            .padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                link.title,
                                style = MaterialTheme.typography.bodyLarge,
                                color = YtText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "Open link",
                                style = MaterialTheme.typography.bodySmall,
                                color = YtMuted,
                            )
                        }
                        Text(
                            "Open",
                            style = MaterialTheme.typography.bodySmall,
                            color = YtBlue,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutStatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = YtMuted,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = YtText,
        )
    }
}
