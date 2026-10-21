package com.indiotube.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.indiotube.app.Container
import com.indiotube.app.data.ChannelItem
import com.indiotube.app.data.DlEvent
import com.indiotube.app.data.DlStatus
import com.indiotube.app.data.VideoItem
import com.indiotube.app.data.watchedFraction
import com.indiotube.app.player.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

sealed interface Screen {
    data class Channel(val channelId: String, val name: String?, val avatar: String?) : Screen
    data class Watch(val video: VideoItem, val context: List<VideoItem>? = null) : Screen
    data class YtPlaylist(val playlistId: String, val title: String?) : Screen
    data class LocalPlaylist(val id: String) : Screen
    data class LibraryList(val kind: LibKind) : Screen
    data object Search : Screen
    data object Downloads : Screen
    data object ManageSubscriptions : Screen
}

enum class LibKind { HISTORY, WATCH_LATER, LIKED }

class UiRefs(
    val container: Container,
    val player: PlaybackController,
    val scope: CoroutineScope,
    val toasts: ToastState = ToastState(),
    private val onOpen: (Screen) -> Unit,
    private val onReplace: (Screen) -> Unit,
    private val onBack: () -> Unit,
    private val onGoTab: (Int) -> Unit,
    private val onOpenAccounts: () -> Unit,
    val search: SearchState,
) {
    val recentSearches = mutableStateListOf<String>()

    // set by WatchScreen while the player is in (or implies) fullscreen, so
    // the bottom navigation can step aside for true immersive playback
    var playerFullscreen by mutableStateOf(false)

    // Bumped when the Home tab or the wordmark is tapped while Home is on
    // screen; HomeScreen watches it, refreshes and scrolls back to the top.
    var homeRefreshTick by mutableIntStateOf(0)
        private set

    fun refreshHome() {
        homeRefreshTick += 1
    }

    fun open(s: Screen) = onOpen(s)
    fun back() = onBack()
    fun goTab(i: Int) = onGoTab(i)
    fun openAccounts() = onOpenAccounts()

    fun toast(msg: String, actionLabel: String? = null, action: (() -> Unit)? = null) =
        toasts.show(msg, actionLabel, action)

    // Feed-card overflow feedback, persisted per account and applied by the
    // recommender on the next build. Negative feedback is undoable, matching
    // YouTube's snackbar.
    fun feedback(video: VideoItem, feedback: FeedFeedback) {
        when (feedback) {
            FeedFeedback.INTERESTED -> {
                container.library.markInterested(video)
                toast("Got it. We'll recommend more videos like this")
            }
            FeedFeedback.NOT_INTERESTED -> {
                container.library.markNotInterested(video)
                toast("Video removed from recommendations", "Undo") {
                    container.library.undoNotInterested(video.videoId)
                }
            }
            FeedFeedback.DONT_RECOMMEND_CHANNEL -> {
                val channelId = video.channelId ?: return
                container.library.blockChannel(video)
                toast("Channel removed from recommendations", "Undo") {
                    container.library.unblockChannel(channelId)
                }
            }
        }
    }

    fun download(video: VideoItem) {
        if (video.membersOnly) {
            toast("Members-only videos can't be downloaded")
            return
        }
        val id = video.videoId
        if (container.downloads.isDownloaded(id)) {
            toast("Already downloaded")
            return
        }
        val existing = container.downloads.tasks.value[id]
        if (existing != null) {
            if (existing.status == DlStatus.PAUSED || existing.status == DlStatus.FAILED) {
                container.downloads.resume(id)
                toast("Download resumed")
            } else {
                toast("Already downloading")
            }
            return
        }
        val height = container.downloadPrefs.settings.value.defaultHeight
        container.downloads.start(video, height)
        if (container.downloads.isActive(id)) {
            toast(if (height == null) "Download started (Auto)" else "Download started (${height}p)")
        }
    }

    fun top(): Screen? = topProvider()

    var topProvider: () -> Screen? = { null }

    fun openWatch(video: VideoItem, context: List<VideoItem>? = null) {
        if (video.membersOnly) {
            toast("Members-only video — join the channel to watch")
            return
        }
        // Resolve the actual queue first: if the tapped video vanished from a
        // refreshed feed, play just that video instead of whatever sits at 0.
        // Members-only entries are dropped so Next/autoplay can't fall into a
        // video that will only fail.
        val requested = (context ?: listOf(video)).filterNot { it.membersOnly }
        val found = requested.indexOfFirst { it.videoId == video.videoId }
        val queue = if (found >= 0) requested else listOf(video)
        val index = if (found >= 0) found else 0
        // Queue before navigating: WatchScreen can then never compose with the
        // previous video still current (the mini-player replay bug).
        player.setQueue(queue, index, play = true)
        // YouTube replaces the watch screen instead of stacking them
        if (top() is Screen.Watch) onReplace(Screen.Watch(video, queue)) else onOpen(Screen.Watch(video, queue))
    }

    fun openChannel(channelId: String?, name: String?, avatar: String?) {
        if (channelId.isNullOrBlank()) {
            toast("Channel unavailable")
            return
        }
        onOpen(Screen.Channel(channelId, name, avatar))
    }

    fun openChannelOf(video: VideoItem) = openChannel(video.channelId, video.channelName, video.channelAvatar)

    fun play(videos: List<VideoItem>, start: Int, shuffle: Boolean = false, openPlayer: Boolean = true) {
        if (videos.isEmpty()) {
            toast("Nothing to play here")
            return
        }
        val idx = start.coerceIn(0, videos.lastIndex)
        if (videos[idx].membersOnly) {
            toast("Members-only video — join the channel to watch")
            return
        }
        val queue = videos.filterNot { it.membersOnly }
        val queueStart = queue.indexOfFirst { it.videoId == videos[idx].videoId }.coerceAtLeast(0)
        if (openPlayer) onOpen(Screen.Watch(videos[idx], queue))
        player.setQueue(queue, queueStart, play = true, shuffleOn = shuffle)
    }

    fun toggleSubscribe(channel: ChannelItem) {
        val was = container.accounts.isSubscribed(channel.channelId)
        container.accounts.toggleSubscription(channel)
        toast(if (was) "Unsubscribed from ${channel.name}" else "Subscribed to ${channel.name}")
    }
}

@Composable
fun IndioTubeApp(container: Container, player: PlaybackController) {
    val stack = remember { mutableStateListOf<Screen>() }
    val scope = rememberCoroutineScope()
    val toasts = remember { ToastState() }
    val searchState = remember { SearchState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var accountSheetOpen by remember { mutableStateOf(false) }

    val ui = remember {
        UiRefs(
            container = container,
            player = player,
            scope = scope,
            toasts = toasts,
            onOpen = { stack.add(it) },
            onReplace = { if (stack.isNotEmpty()) stack[stack.lastIndex] = it else stack.add(it) },
            onBack = {
                if (stack.isNotEmpty()) {
                    val removed = stack.removeAt(stack.lastIndex)
                    if (removed is Screen.Search) searchState.reset()
                }
            },
            onGoTab = { tab = it },
            onOpenAccounts = { accountSheetOpen = true },
            search = searchState,
        ).also { refs -> refs.topProvider = { stack.lastOrNull() } }
    }

    val current by player.current.collectAsState()
    val playerError by player.error.collectAsState()
    LaunchedEffect(playerError) {
        val msg = playerError
        if (msg != null) {
            player.clearError()
            toasts.show(msg)
        }
    }
    LaunchedEffect(Unit) {
        container.downloads.events.collect { event ->
            when (event) {
                is DlEvent.Finished -> toasts.show("Downloaded \u201c${event.video.title.take(40)}\u201d")
                is DlEvent.Failed -> toasts.show(event.message)
                is DlEvent.Paused -> toasts.show("Download paused")
                is DlEvent.Resumed -> toasts.show("Download resumed")
                else -> {}
            }
        }
    }

    BackHandler(enabled = stack.isNotEmpty()) { ui.back() }

    val watchOpen = stack.lastOrNull() is Screen.Watch

    // Watched-progress lookup for every thumbnail in the app. Rebuilt whenever
    // history changes so the red bars appear/update without leaving a screen.
    val history by container.library.history.collectAsState()
    val watchProgress: (VideoItem) -> Float? = remember(history) {
        val fractions = history.associate { it.video.videoId to it.watchedFraction() }
        val lookup: (VideoItem) -> Float? = { video -> fractions[video.videoId] }
        lookup
    }

    CompositionLocalProvider(LocalWatchProgress provides watchProgress) {
    Box(Modifier.fillMaxSize().background(YtBg)) {
        Scaffold(
            containerColor = YtBg,
            bottomBar = {
                Column {
                    if (current != null && !watchOpen) {
                        MiniPlayer(container, ui, player)
                    }
                    // The bottom navigation never disappears: it stays put on
                    // watch, search, channel and every other screen. It only
                    // yields during fullscreen playback.
                    if (!ui.playerFullscreen) {
                        BottomBar(if (stack.isEmpty()) tab else -1) { i ->
                            // Tapping Home returns to the feed and refreshes
                            // it, the way YouTube's home tab behaves.
                            if (i == 0) ui.refreshHome()
                            tab = i
                            stack.clear()
                            searchState.reset()
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (stack.isEmpty()) {
                    when (tab) {
                        0 -> HomeScreen(container, ui)
                        1 -> ExploreScreen(container, ui)
                        2 -> SubscriptionsScreen(container, ui)
                        else -> YouScreen(container, ui)
                    }
                } else {
                    when (val s = stack.last()) {
                        is Screen.Watch -> WatchScreen(container, player, ui, s.video, s.context ?: listOf(s.video))
                        is Screen.Channel -> ChannelScreen(container, ui, s.channelId, s.name, s.avatar)
                        is Screen.YtPlaylist -> YtPlaylistScreen(container, ui, s.playlistId, s.title)
                        is Screen.LocalPlaylist -> LocalPlaylistScreen(container, ui, s.id)
                        is Screen.LibraryList -> LibraryListScreen(container, ui, s.kind)
                        Screen.Search -> SearchScreen(container, ui)
                        Screen.Downloads -> DownloadsScreen(container, ui, player)
                        Screen.ManageSubscriptions -> ManageSubsScreen(container, ui)
                    }
                }
            }
        }

        if (accountSheetOpen) {
            AccountSheet(container, ui) { accountSheetOpen = false }
        }

        ToastHost(
            toasts,
            Modifier.align(Alignment.BottomCenter).padding(
                bottom = when {
                    ui.playerFullscreen -> 24.dp
                    current != null && !watchOpen -> 180.dp
                    else -> 92.dp
                },
            ),
        )
    }
    }
}

@Composable
private fun BottomBar(tab: Int, onTab: (Int) -> Unit) {
    NavigationBar(containerColor = YtBg, tonalElevation = 0.dp) {
        val items = listOf(
            Triple("Home", Icons.Outlined.Home, Icons.Filled.Home),
            Triple("Explore", Icons.Outlined.Explore, Icons.Filled.Explore),
            Triple("Subscriptions", Icons.Outlined.Subscriptions, Icons.Filled.Subscriptions),
            Triple("You", Icons.Outlined.AccountCircle, Icons.Filled.AccountCircle),
        )
        items.forEachIndexed { i, (label, outlined, filled) ->
            NavigationBarItem(
                selected = tab == i,
                onClick = { onTab(i) },
                icon = {
                    Icon(
                        if (tab == i) filled else outlined,
                        contentDescription = label,
                        modifier = Modifier.size(24.dp),
                    )
                },
                label = {
                    Text(label, fontSize = 10.sp, maxLines = 1)
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = YtText,
                    selectedTextColor = YtText,
                    unselectedIconColor = YtMuted,
                    unselectedTextColor = YtMuted,
                    indicatorColor = Color.Transparent,
                ),
            )
        }
    }
}

@Composable
private fun MiniPlayer(container: Container, ui: UiRefs, player: PlaybackController) {
    val current by player.current.collectAsState()
    val playing by player.playing.collectAsState()
    val connected by player.connected.collectAsState()
    val position by player.position.collectAsState()
    val duration by player.duration.collectAsState()
    val video = current ?: return
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val density = LocalDensity.current
    val dismissThreshold = with(density) { 72.dp.toPx() }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(video.videoId) { dragOffset = 0f }
    val dragState = rememberDraggableState { delta ->
        if (delta > 0f || dragOffset > 0f) {
            dragOffset = (dragOffset + delta).coerceAtLeast(0f)
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .offset { IntOffset(0, dragOffset.roundToInt()) }
            .draggable(
                state = dragState,
                orientation = Orientation.Vertical,
                onDragStopped = { velocity ->
                    val dismiss = dragOffset > dismissThreshold || velocity > 1200f
                    animate(
                        initialValue = dragOffset,
                        targetValue = if (dismiss) 600f else 0f,
                        animationSpec = tween(durationMillis = if (dismiss) 160 else 180),
                    ) { value, _ -> dragOffset = value }
                    if (dismiss) player.clear()
                },
            )
            .clip(RoundedCornerShape(12.dp))
            .background(YtSurface)
            .clickable { ui.open(Screen.Watch(video)) },
    ) {
        LinearProgressIndicator(
            progress = { fraction },
            color = YtRed,
            trackColor = Color.Transparent,
            modifier = Modifier.fillMaxWidth().height(3.dp),
        )
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(128.dp)
                    .height(72.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black),
            ) {
                // Poster frame underneath, then the live video surface on top —
                // the mini player keeps showing the video itself, like YouTube.
                Thumb(model = video.thumbnail, corner = 10.dp, modifier = Modifier.fillMaxSize())
                AndroidView(
                    factory = { c ->
                        PlayerView(c).apply {
                            useController = false
                            setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    },
                    update = { view -> if (connected) view.player = player.mediaController },
                    onRelease = { view -> view.player = null },
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable { player.togglePlayPause() },
                    contentAlignment = Alignment.Center,
                ) {
                    // YouTube only paints the state icon while paused; a
                    // playing mini player shows clean video.
                    if (!playing) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "Play",
                            tint = Color.White,
                            modifier = Modifier
                                .size(34.dp)
                                .background(Color(0x66000000), RoundedCornerShape(17.dp))
                                .padding(3.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    video.title,
                    style = MiniTitle,
                    color = YtText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    video.channelName.orEmpty(),
                    style = MiniMeta,
                    color = YtMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { player.togglePlayPause() }) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                    tint = YtText,
                    modifier = Modifier.size(30.dp),
                )
            }
            IconButton(onClick = { player.next() }) {
                Icon(Icons.Filled.SkipNext, "Next", tint = YtText, modifier = Modifier.size(30.dp))
            }
            IconButton(onClick = { player.clear() }) {
                Icon(Icons.Filled.Close, "Close", tint = YtText, modifier = Modifier.size(26.dp))
            }
        }
    }
}

private val MiniTitle = androidx.compose.ui.text.TextStyle(
    fontWeight = FontWeight.Medium,
    fontSize = 15.sp,
    lineHeight = 20.sp,
)
private val MiniMeta = androidx.compose.ui.text.TextStyle(
    fontSize = 12.5.sp,
    lineHeight = 16.sp,
)
