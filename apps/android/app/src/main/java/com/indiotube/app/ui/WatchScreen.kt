package com.indiotube.app.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Typeface
import android.util.TypedValue
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.indiotube.app.Container
import com.indiotube.app.data.ChannelItem
import com.indiotube.app.data.CommentItem
import com.indiotube.app.data.DlStatus
import com.indiotube.app.data.SubtitleTrack
import com.indiotube.app.data.VideoItem
import com.indiotube.app.data.VideoQualities
import com.indiotube.app.data.forLanguage
import com.indiotube.app.data.formatDuration
import com.indiotube.app.player.PlaybackController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchScreen(
    container: Container,
    player: PlaybackController,
    ui: UiRefs,
    video: VideoItem,
    context: List<VideoItem>,
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val activity = ctx as? Activity

    val currentVideo by player.current.collectAsState()
    val playing by player.playing.collectAsState()
    val buffering by player.buffering.collectAsState()
    val position by player.position.collectAsState()
    val duration by player.duration.collectAsState()
    val connected by player.connected.collectAsState()
    val queue by player.queue.collectAsState()
    val active by container.accounts.active.collectAsState()
    val watchLater by container.library.watchLater.collectAsState()
    val playlists by container.library.playlists.collectAsState()
    val tasks by container.downloads.tasks.collectAsState()
    val dlItems by container.downloads.items.collectAsState()

    var watch by remember { mutableStateOf<com.indiotube.app.data.WatchData?>(null) }
    var comments by remember { mutableStateOf<List<CommentItem>>(emptyList()) }
    var commentsToken by remember { mutableStateOf<String?>(null) }
    var loadingComments by remember { mutableStateOf(false) }
    var loadingMoreComments by remember { mutableStateOf(false) }

    var controlsVisible by remember { mutableStateOf(true) }
    var fullscreen by remember { mutableStateOf(false) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    var seekHint by remember { mutableStateOf<Pair<Boolean, Long>?>(null) }
    var liked by remember(video.videoId) { mutableStateOf(container.library.isLiked(video.videoId)) }
    var disliked by remember(video.videoId) { mutableStateOf(false) }
    var descExpanded by remember(video.videoId) { mutableStateOf(false) }
    var saveSheet by remember { mutableStateOf(false) }
    var qualitySheet by remember { mutableStateOf(false) }
    var captionsSheet by remember { mutableStateOf(false) }
    var qualities by remember(video.videoId) { mutableStateOf<VideoQualities?>(null) }
    var qualityError by remember(video.videoId) { mutableStateOf(false) }
    val qualityHeight by player.qualityHeight.collectAsState()
    val captionLanguage by container.captions.language.collectAsState()
    val subtitle by player.subtitle.collectAsState()
    val playingVideo by player.current.collectAsState()
    val captionsAvailable = qualities?.subtitles?.isNotEmpty() == true
    var resumed by remember(video.videoId) { mutableStateOf(false) }

    // make sure the tapped video is the one actually playing
    LaunchedEffect(video.videoId) {
        if (player.current.value?.videoId != video.videoId) {
            val idx = context.indexOfFirst { it.videoId == video.videoId }
            // If the tapped video isn't in the list (stale "up next" payload),
            // play it alone instead of falling back to the list's first item,
            // which would start an unrelated video under this screen.
            if (idx >= 0) {
                player.setQueue(context, idx, play = true)
            } else {
                player.setQueue(listOf(video), 0, play = true)
            }
        }
        container.library.noteOpen(video)
    }

    // watch metadata + first page of comments
    LaunchedEffect(video.videoId) {
        watch = null
        comments = emptyList()
        commentsToken = null
        try {
            val w = container.innerTube.watch(video.videoId)
            watch = w
            // The watch page knows the real channel photo even when the card
            // that opened it did not; backfill saved copies (history, likes,
            // playlists) so avatars never stay blank.
            container.library.enrichVideo(
                VideoItem(
                    videoId = w.videoId,
                    title = w.title,
                    channelId = w.channelId,
                    channelName = w.channelName,
                    channelAvatar = w.channelAvatar,
                    viewsText = w.viewsText,
                    publishedText = w.publishedText,
                ),
            )
            val token = w.commentsToken
            if (token != null) {
                loadingComments = true
                try {
                    val page = container.innerTube.comments(token)
                    comments = page.comments
                    commentsToken = page.continuation
                } catch (_: Exception) { /* comments are optional */ }
                loadingComments = false
            }
        } catch (_: Exception) { /* player still works without metadata */ }
    }

    // Available quality ladder (muxed + video-only heights). Loaded in the
    // background; the player keeps running on Auto until the user picks one.
    LaunchedEffect(video.videoId) {
        val loaded = runCatching {
            withContext(Dispatchers.IO) { container.resolver.qualities(video.videoId) }
        }.getOrNull()
        qualities = loaded
        qualityError = loaded == null
    }

    // Re-apply the remembered caption language once this video's tracks load,
    // so captions stay on across videos like the real app. Turning captions
    // off clears the preference, which also makes this a no-op.
    LaunchedEffect(video.videoId, playingVideo?.videoId, qualities, captionLanguage) {
        if (playingVideo?.videoId != video.videoId) return@LaunchedEffect
        val remembered = captionLanguage ?: return@LaunchedEffect
        val track = qualities?.subtitles?.forLanguage(remembered) ?: return@LaunchedEffect
        if (subtitle?.url != track.url) player.setSubtitles(video.videoId, track)
    }

    // pick up where the user left off
    LaunchedEffect(duration, video.videoId) {
        if (!resumed && duration > 0 && player.current.value?.videoId == video.videoId) {
            resumed = true
            val resume = container.library.resumePosition(video.videoId)
            if (resume > 20_000 && resume < duration - 10_000) player.seekTo(resume)
        }
    }

    LaunchedEffect(controlsVisible, playing, buffering, scrub) {
        if (controlsVisible && playing && !buffering && scrub == null) {
            delay(3500)
            controlsVisible = false
        }
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val effectiveFullscreen = fullscreen || isLandscape

    // the bottom navigation stays everywhere except real fullscreen playback
    LaunchedEffect(effectiveFullscreen) { ui.playerFullscreen = effectiveFullscreen }
    DisposableEffect(Unit) {
        onDispose { ui.playerFullscreen = false }
    }

    DisposableEffect(effectiveFullscreen) {
        val window = activity?.window
        val decor = window?.decorView
        if (window != null && decor != null) {
            val wic = WindowInsetsControllerCompat(window, decor)
            if (effectiveFullscreen) {
                wic.hide(WindowInsetsCompat.Type.systemBars())
                wic.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                wic.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            // Only restore the bars here. Touching the requested orientation
            // in this dispose would unlock rotation the instant fullscreen
            // starts (the effect restarts when the screen turns), which made
            // the player flip landscape → portrait → landscape.
            if (window != null && decor != null) {
                WindowInsetsControllerCompat(window, decor).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Leaving the watch screen always hands rotation back to the system.
    DisposableEffect(Unit) {
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    LaunchedEffect(isLandscape, fullscreen) {
        if (!isLandscape && !fullscreen &&
            activity?.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        ) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    BackHandler(enabled = effectiveFullscreen) {
        if (fullscreen) {
            fullscreen = false
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    fun toggleFullscreen() {
        val target = !fullscreen && !isLandscape
        fullscreen = target
        // A single, deterministic rotation: the plain landscape lock always
        // applies (rotation lock or not) and never re-evaluates the sensor,
        // so there is no second flip back through portrait after the screen
        // has already turned.
        activity?.requestedOrientation = if (target) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        if (target) controlsVisible = true
    }

    val title = watch?.title?.takeIf { it.isNotBlank() } ?: video.title
    val channelName = watch?.channelName ?: video.channelName
    val channelId = watch?.channelId ?: video.channelId
    val channelAvatar = watch?.channelAvatar ?: video.channelAvatar
    val viewsText = watch?.viewsText ?: video.viewsText
    val publishedText = watch?.publishedText ?: video.publishedText
    val likesText = watch?.likesText
    val description = watch?.description
    val subscribersText = watch?.subscribersText

    val merged = video.copy(
        title = title,
        channelName = channelName,
        channelId = channelId,
        channelAvatar = channelAvatar,
        viewsText = viewsText,
        publishedText = publishedText,
    )

    val downloaded = dlItems.containsKey(video.videoId)
    val dlTask = tasks[video.videoId]
    val subscribed = active?.subscriptions?.any { it.channelId == channelId } == true

    val durationF = duration.coerceAtLeast(1L).toFloat()
    val sliderValue = scrub ?: (position.toFloat() / durationF).coerceIn(0f, 1f)
    val posShown = scrub?.times(duration)?.toLong() ?: position
    val qualityLabel = qualityHeight?.let { h -> qualities?.labelFor(h) ?: "${h}p" } ?: "Auto"

    Column(Modifier.fillMaxSize()) {
        // ---- player surface -------------------------------------------------
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (effectiveFullscreen) Modifier.fillMaxSize() else Modifier.aspectRatio(16f / 9f))
                .background(Color.Black)
                // swipe down on the player to minimise, like the real app
                .pointerInput(video.videoId, effectiveFullscreen) {
                    if (!effectiveFullscreen) {
                        var dragged = 0f
                        detectVerticalDragGestures(
                            onVerticalDrag = { change, dragAmount ->
                                dragged = (dragged + dragAmount).coerceAtLeast(0f)
                                change.consume()
                            },
                            onDragEnd = {
                                if (dragged > 180f) ui.back()
                                dragged = 0f
                            },
                            onDragCancel = { dragged = 0f },
                        )
                    }
                },
        ) {
            // Thumbnail sits behind the inline player only; fullscreen shows
            // pure black letterboxing like the real app.
            if (!effectiveFullscreen) {
                AsyncImage(
                    model = video.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            AndroidView(
                factory = { c ->
                    PlayerView(c).apply {
                        useController = false
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        // YouTube-like captions: white text on a 50% black
                        // box, no outline, a touch smaller, and lifted well
                        // clear of the bottom edge.
                        subtitleView?.apply {
                            setStyle(
                                CaptionStyleCompat(
                                    android.graphics.Color.WHITE,
                                    android.graphics.Color.argb(128, 0, 0, 0),
                                    android.graphics.Color.TRANSPARENT,
                                    CaptionStyleCompat.EDGE_TYPE_NONE,
                                    android.graphics.Color.TRANSPARENT,
                                    Typeface.create("sans-serif", Typeface.NORMAL),
                                ),
                            )
                            setApplyEmbeddedStyles(false)
                            setApplyEmbeddedFontSizes(false)
                            setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                            setBottomPaddingFraction(0.15f)
                        }
                    }
                },
                update = { view -> if (connected) view.player = player.mediaController },
                onRelease = { view -> view.player = null },
                modifier = Modifier.fillMaxSize(),
            )

            // double-tap seek: left / right thirds
            Row(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .pointerInput(video.videoId) {
                            detectTapGestures(
                                onDoubleTap = {
                                    player.seekBy(-10_000)
                                    seekHint = true to System.currentTimeMillis()
                                },
                                onTap = { controlsVisible = !controlsVisible },
                            )
                        },
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .pointerInput(Unit) { detectTapGestures(onTap = { controlsVisible = !controlsVisible }) },
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .pointerInput(video.videoId) {
                            detectTapGestures(
                                onDoubleTap = {
                                    player.seekBy(10_000)
                                    seekHint = false to System.currentTimeMillis()
                                },
                                onTap = { controlsVisible = !controlsVisible },
                            )
                        },
                )
            }

            // big play button while paused (only with chrome hidden — the
            // chrome itself shows YouTube-style centre controls)
            if (!playing && !buffering && !controlsVisible) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "Play",
                        tint = Color(0xE6FFFFFF),
                        modifier = Modifier
                            .size(68.dp)
                            .clip(RoundedCornerShape(34.dp))
                            .clickable { player.togglePlayPause() }
                            .padding(6.dp),
                    )
                }
            }
            if (buffering) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(44.dp))
                }
            }

            val hint = seekHint
            if (hint != null) {
                LaunchedEffect(hint.second) {
                    delay(650)
                    seekHint = null
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 30.dp),
                    contentAlignment = if (hint.first) Alignment.CenterStart else Alignment.CenterEnd,
                ) {
                    Text(
                        "10 seconds",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0x99000000))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }

            PlayerChrome(
                visible = controlsVisible,
                title = title,
                fullscreen = effectiveFullscreen,
                playing = playing,
                buffering = buffering,
                queueSize = queue.size,
                sliderValue = sliderValue,
                scrubbing = scrub != null,
                positionText = formatDuration(posShown / 1000),
                durationText = formatDuration(duration / 1000),
                qualityLabel = qualityLabel,
                captionsAvailable = captionsAvailable,
                captionsOn = subtitle != null,
                onQuality = { qualitySheet = true },
                onCaptions = { captionsSheet = true },
                onBack = { ui.back() },
                onTogglePlay = { player.togglePlayPause() },
                onSeekBy = { player.seekBy(it) },
                onNext = { player.next() },
                onToggleFullscreen = { toggleFullscreen() },
                onScrub = { scrub = it },
                onScrubFinished = {
                    scrub?.let { player.seekTo((it * duration).toLong()) }
                    scrub = null
                },
            )
        }

        // ---- page below the player -------------------------------------------
        if (!effectiveFullscreen) {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = YtText,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 22.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        val meta = listOfNotNull(
                            viewsText?.takeIf { it.isNotBlank() },
                            publishedText?.takeIf { it.isNotBlank() },
                        ).joinToString(" • ")
                        if (meta.isNotBlank()) {
                            Text(meta, style = MaterialTheme.typography.bodySmall, color = YtMuted)
                        }
                    }
                }

                item {
                    Row(
                        Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 6.dp),
                    ) {
                        ActionButton(
                            icon = {
                                Icon(
                                    if (liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                                    null,
                                    tint = if (liked) YtBlue else YtText,
                                    modifier = Modifier.size(24.dp),
                                )
                            },
                            label = if (liked) "Liked" else (likesText ?: "Like"),
                            active = liked,
                        ) {
                            liked = container.library.toggleLike(merged)
                            if (liked) disliked = false
                        }
                        ActionButton(
                            icon = {
                                Icon(
                                    Icons.Outlined.ThumbDown,
                                    null,
                                    tint = if (disliked) YtBlue else YtText,
                                    modifier = Modifier.size(24.dp),
                                )
                            },
                            label = "Dislike",
                            active = disliked,
                        ) {
                            disliked = !disliked
                            if (disliked && liked) liked = container.library.toggleLike(merged)
                        }
                        ActionButton(
                            icon = { Icon(Icons.Outlined.Share, null, tint = YtText, modifier = Modifier.size(24.dp)) },
                            label = "Share",
                        ) {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "https://youtu.be/${video.videoId}")
                            }
                            try {
                                ctx.startActivity(Intent.createChooser(send, "Share video"))
                            } catch (_: Exception) {
                                ui.toast("Nothing to share with")
                            }
                        }
                        ActionButton(
                            icon = {
                                when {
                                    downloaded -> Icon(
                                        Icons.Filled.DownloadDone,
                                        null,
                                        tint = YtBlue,
                                        modifier = Modifier.size(24.dp),
                                    )
                                    dlTask != null && dlTask.status == DlStatus.ACTIVE -> {
                                        if (dlTask.indeterminate) {
                                            CircularProgressIndicator(
                                                color = YtBlue,
                                                strokeWidth = 2.5.dp,
                                                modifier = Modifier.size(22.dp),
                                            )
                                        } else {
                                            CircularProgressIndicator(
                                                progress = { dlTask.progress },
                                                color = YtBlue,
                                                strokeWidth = 2.5.dp,
                                                modifier = Modifier.size(22.dp),
                                            )
                                        }
                                    }
                                    else -> Icon(Icons.Outlined.Download, null, tint = YtText, modifier = Modifier.size(24.dp))
                                }
                            },
                            label = when {
                                downloaded -> "Downloaded"
                                dlTask?.status == DlStatus.ACTIVE -> "Downloading"
                                dlTask?.status == DlStatus.PAUSED -> "Resume"
                                dlTask?.status == DlStatus.FAILED -> "Retry"
                                else -> "Download"
                            },
                        ) {
                            when {
                                downloaded -> ui.toast("Saved in Downloads — watch it offline anytime")
                                dlTask?.status == DlStatus.ACTIVE -> container.downloads.pause(video.videoId)
                                dlTask?.status == DlStatus.PAUSED || dlTask?.status == DlStatus.FAILED ->
                                    container.downloads.resume(video.videoId)
                                else -> {
                                    container.downloads.start(merged)
                                    ui.toast("Download started")
                                }
                            }
                        }
                        ActionButton(
                            icon = { Icon(Icons.Outlined.PlaylistAdd, null, tint = YtText, modifier = Modifier.size(24.dp)) },
                            label = "Save",
                        ) {
                            saveSheet = true
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                }

                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { ui.openChannel(channelId, channelName, channelAvatar) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ChannelAvatar(channelName, channelAvatar, 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                channelName ?: "Unknown channel",
                                style = MaterialTheme.typography.titleSmall,
                                color = YtText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!subscribersText.isNullOrBlank()) {
                                Text(subscribersText, style = MaterialTheme.typography.bodySmall, color = YtMuted, maxLines = 1)
                            }
                        }
                        if (!channelId.isNullOrBlank()) {
                            SubscribeButton(subscribed) {
                                ui.toggleSubscribe(
                                    ChannelItem(
                                        channelId = channelId,
                                        name = channelName.orEmpty(),
                                        avatar = channelAvatar,
                                        subscribersText = subscribersText,
                                    ),
                                )
                            }
                        }
                    }
                }

                if (!description.isNullOrBlank()) {
                    item {
                        Column(
                            Modifier
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(YtSurfaceHi)
                                .clickable { descExpanded = !descExpanded }
                                .padding(12.dp),
                        ) {
                            val meta = listOfNotNull(
                                viewsText?.takeIf { it.isNotBlank() },
                                publishedText?.takeIf { it.isNotBlank() },
                            ).joinToString(" • ")
                            if (meta.isNotBlank() && descExpanded) {
                                Text(
                                    meta,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = YtText,
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            Text(
                                description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = YtText,
                                maxLines = if (descExpanded) Int.MAX_VALUE else 2,
                                overflow = TextOverflow.Ellipsis,
                                lineHeight = 19.sp,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                if (descExpanded) "Show less" else "Show more",
                                style = MaterialTheme.typography.labelLarge,
                                color = YtText,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }

                // ---- comments -------------------------------------------------
                item {
                    SectionHeader(
                        listOfNotNull("Comments", watch?.commentsCountText).joinToString(" "),
                    )
                }
                if (loadingComments && comments.isEmpty()) {
                    item { LoadingBox(Modifier.height(80.dp)) }
                }
                items(comments, key = { it.id }) { c ->
                    CommentRow(c)
                }
                if (commentsToken != null) {
                    item {
                        if (loadingMoreComments) {
                            LoadingBox(Modifier.height(60.dp))
                        } else {
                            FillRowButton("Load more comments") {
                                val token = commentsToken ?: return@FillRowButton
                                scope.launch {
                                    loadingMoreComments = true
                                    try {
                                        val page = container.innerTube.comments(token)
                                        comments = comments + page.comments
                                        commentsToken = page.continuation
                                    } catch (_: Exception) {
                                        ui.toast("Couldn't load more comments")
                                    }
                                    loadingMoreComments = false
                                }
                            }
                        }
                    }
                }

                // ---- up next ---------------------------------------------------
                val related = watch?.related.orEmpty()
                if (related.isNotEmpty()) {
                    item { SectionHeader("Up next") }
                    items(related, key = { "rel-" + it.videoId }) { v ->
                        VideoRow(
                            v,
                            onClick = { ui.openWatch(v, related) },
                            onChannelClick = { ui.openChannelOf(v) },
                        )
                    }
                }
                item { Spacer(Modifier.height(10.dp)) }
            }
        }
    }

    if (saveSheet) {
        SaveSheet(
            ui = ui,
            merged = merged,
            watchLater = watchLater.any { it.videoId == video.videoId },
            playlists = playlists.map { it.id to (it.name to it.videos.any { v -> v.videoId == video.videoId }) },
            onSaveLater = { container.library.toggleWatchLater(merged) },
            onAddToPlaylist = { id -> container.library.addToPlaylist(id, merged) },
            onCreatePlaylist = { name -> container.library.createPlaylist(name).id.also { container.library.addToPlaylist(it, merged) } },
            onDismiss = { saveSheet = false },
        )
    }

    if (qualitySheet) {
        QualitySheet(
            qualities = qualities,
            error = qualityError,
            selectedHeight = qualityHeight,
            downloaded = downloaded,
            onSelect = { height ->
                val q = qualities
                if (q != null) {
                    val label = if (height == null) "Auto" else q.labelFor(height) ?: "${height}p"
                    val same = height == qualityHeight
                    player.setQuality(video.videoId, height, q)
                    if (!same) ui.toast("Switching to $label")
                }
                qualitySheet = false
            },
            onDismiss = { qualitySheet = false },
        )
    }

    if (captionsSheet) {
        CaptionsSheet(
            subtitles = qualities?.subtitles.orEmpty(),
            loading = qualities == null && !qualityError,
            error = qualityError,
            selected = subtitle,
            onSelect = { track ->
                if (track == null) {
                    player.setSubtitles(video.videoId, null)
                    container.captions.setLanguage(null)
                    ui.toast("Captions off")
                } else {
                    player.setSubtitles(video.videoId, track)
                    container.captions.setLanguage(track.languageTag)
                    ui.toast("Captions: ${track.label}")
                }
                captionsSheet = false
            },
            onDismiss = { captionsSheet = false },
        )
    }
}

@Composable
private fun PlayerChrome(
    visible: Boolean,
    title: String,
    fullscreen: Boolean,
    playing: Boolean,
    buffering: Boolean,
    queueSize: Int,
    sliderValue: Float,
    scrubbing: Boolean,
    positionText: String,
    durationText: String,
    qualityLabel: String,
    captionsAvailable: Boolean,
    captionsOn: Boolean,
    onQuality: () -> Unit,
    onCaptions: () -> Unit,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onNext: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize()) {
            // top chrome
            Row(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent)))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
                if (fullscreen) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 4.dp, end = 12.dp),
                    )
                }
            }

            // centre controls, YouTube-style: rewind 10 / play-pause / forward 10
            if (!buffering) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onSeekBy(-10_000) }, modifier = Modifier.size(56.dp)) {
                            Icon(
                                Icons.Filled.Replay10, "Rewind 10 seconds",
                                tint = Color(0xE6FFFFFF),
                                modifier = Modifier.size(34.dp),
                            )
                        }
                        Spacer(Modifier.width(22.dp))
                        IconButton(onClick = onTogglePlay, modifier = Modifier.size(64.dp)) {
                            Icon(
                                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                if (playing) "Pause" else "Play",
                                tint = Color(0xE6FFFFFF),
                                modifier = Modifier.size(46.dp),
                            )
                        }
                        Spacer(Modifier.width(22.dp))
                        IconButton(onClick = { onSeekBy(10_000) }, modifier = Modifier.size(56.dp)) {
                            Icon(
                                Icons.Filled.Forward10, "Forward 10 seconds",
                                tint = Color(0xE6FFFFFF),
                                modifier = Modifier.size(34.dp),
                            )
                        }
                    }
                }
            }

            // bottom chrome: seek bar + buttons
            Column(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000))))
                    .padding(horizontal = 12.dp, vertical = 2.dp),
            ) {
                // scrub preview bubble, hovering over the thumb position
                if (scrubbing) {
                    BoxWithConstraints(Modifier.fillMaxWidth().height(26.dp)) {
                        val bubbleWidth = 64.dp
                        val x = ((maxWidth - bubbleWidth) * sliderValue)
                            .coerceIn(0.dp, maxWidth - bubbleWidth)
                        Box(
                            Modifier
                                .offset(x = x)
                                .width(bubbleWidth)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF272727)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(positionText, color = Color.White, fontSize = 11.sp)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(positionText, color = Color.White, fontSize = 11.sp)
                    YtSeekBar(
                        value = sliderValue,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        onScrub = onScrub,
                        onScrubFinished = onScrubFinished,
                    )
                    Text(durationText, color = Color.White, fontSize = 11.sp)
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(1f))
                    if (queueSize > 1) {
                        IconButton(onClick = onNext, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Filled.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(26.dp))
                        }
                    }
                    // CC chip: outlined when off, filled white when on, just
                    // like YouTube's player button.
                    if (captionsAvailable) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .clickable(onClick = onCaptions),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(if (captionsOn) Color.White else Color.Transparent)
                                    .border(1.2.dp, Color.White, RoundedCornerShape(3.dp))
                                    .padding(horizontal = 3.dp, vertical = 1.dp),
                            ) {
                                Text(
                                    "CC",
                                    color = if (captionsOn) Color.Black else Color.White,
                                    fontSize = 9.sp,
                                    lineHeight = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                    Text(qualityLabel, color = Color.White, fontSize = 11.sp)
                    IconButton(onClick = onQuality, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Filled.Settings,
                            "Quality",
                            tint = Color.White,
                            modifier = Modifier.size(23.dp),
                        )
                    }
                    IconButton(onClick = onToggleFullscreen, modifier = Modifier.size(40.dp)) {
                        Icon(
                            if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                            "Fullscreen",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
            }
        }
    }
}

// Slim YouTube-style seek bar: thin rounded track, red progress, and a thumb
// that only appears while dragging. Tap or drag anywhere to seek.
@Composable
private fun YtSeekBar(
    value: Float,
    modifier: Modifier = Modifier,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
) {
    var width by remember { mutableStateOf(1f) }
    var dragging by remember { mutableStateOf(false) }
    val scrubNow by rememberUpdatedState(onScrub)
    val scrubDone by rememberUpdatedState(onScrubFinished)

    fun fraction(x: Float) = (x / width).coerceIn(0f, 1f)

    Box(
        modifier
            .fillMaxWidth()
            .height(34.dp)
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { off ->
                        dragging = true
                        scrubNow(fraction(off.x))
                    },
                    onDragEnd = {
                        dragging = false
                        scrubDone()
                    },
                    onDragCancel = {
                        dragging = false
                        scrubDone()
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        scrubNow(fraction(change.position.x))
                    },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures { off ->
                    scrubNow(fraction(off.x))
                    scrubDone()
                }
            }
            .drawBehind {
                val cy = size.height / 2f
                val h = (if (dragging) 5.dp else 3.dp).toPx()
                val cr = CornerRadius(h / 2f)
                drawRoundRect(
                    color = Color(0x59FFFFFF),
                    topLeft = Offset(0f, cy - h / 2f),
                    size = Size(size.width, h),
                    cornerRadius = cr,
                )
                val activeW = size.width * value.coerceIn(0f, 1f)
                if (activeW > 0f) {
                    drawRoundRect(
                        color = YtRed,
                        topLeft = Offset(0f, cy - h / 2f),
                        size = Size(activeW, h),
                        cornerRadius = cr,
                    )
                }
                if (dragging) {
                    val cx = activeW.coerceIn(9.dp.toPx(), (size.width - 9.dp.toPx()).coerceAtLeast(9.dp.toPx()))
                    drawCircle(Color(0x66FFFFFF), radius = 9.dp.toPx(), center = Offset(cx, cy))
                    drawCircle(YtRed, radius = 6.5f.dp.toPx(), center = Offset(cx, cy))
                }
            },
    )
}

@Composable
private fun CommentRow(c: CommentItem) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        ChannelAvatar(c.author, c.avatar, 32.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.author, fontSize = 12.sp, color = YtMuted, maxLines = 1)
                if (!c.publishedText.isNullOrBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(c.publishedText, fontSize = 11.sp, color = YtMuted, maxLines = 1)
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(c.text, style = MaterialTheme.typography.bodyMedium, color = YtText, lineHeight = 19.sp)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ThumbUp, null, tint = YtMuted, modifier = Modifier.size(15.dp))
                if (!c.likesText.isNullOrBlank() && c.likesText != "0") {
                    Spacer(Modifier.width(5.dp))
                    Text(c.likesText, fontSize = 11.5.sp, color = YtMuted)
                }
                if (!c.replyCountText.isNullOrBlank()) {
                    Spacer(Modifier.width(14.dp))
                    Text(c.replyCountText, fontSize = 11.5.sp, color = YtMuted)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaveSheet(
    ui: UiRefs,
    merged: VideoItem,
    watchLater: Boolean,
    playlists: List<Pair<String, Pair<String, Boolean>>>,
    onSaveLater: () -> Boolean,
    onAddToPlaylist: (String) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = YtSurface,
        contentColor = YtText,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                "Save video to…",
                style = MaterialTheme.typography.titleMedium,
                color = YtText,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
            SheetRow(
                icon = { Icon(Icons.Outlined.Schedule, null, tint = YtText, modifier = Modifier.size(22.dp)) },
                label = "Watch later",
                checked = watchLater,
            ) {
                val saved = onSaveLater()
                ui.toast(if (saved) "Saved to Watch later" else "Removed from Watch later")
            }
            playlists.forEach { (id, rest) ->
                val (name, contains) = rest
                SheetRow(
                    icon = { Icon(Icons.Outlined.PlaylistPlay, null, tint = YtText, modifier = Modifier.size(22.dp)) },
                    label = name,
                    checked = contains,
                ) {
                    if (contains) {
                        ui.toast("Already in $name")
                    } else {
                        onAddToPlaylist(id)
                        ui.toast("Saved to $name")
                    }
                }
            }
            if (creating) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        placeholder = { Text("Playlist name", color = YtMuted) },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Create",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (newName.isBlank()) YtMuted else YtBlue,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(enabled = newName.isNotBlank()) {
                                onCreatePlaylist(newName.trim())
                                ui.toast("Saved to ${newName.trim()}")
                                onDismiss()
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            } else {
                SheetRow(
                    icon = { Icon(Icons.Filled.Add, null, tint = YtText, modifier = Modifier.size(22.dp)) },
                    label = "New playlist",
                    checked = false,
                ) {
                    creating = true
                }
            }
        }
    }
}

@Composable
private fun SheetRow(
    icon: @Composable () -> Unit,
    label: String,
    checked: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(16.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = YtText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (checked) {
            Icon(Icons.Filled.Check, "Saved", tint = YtBlue, modifier = Modifier.size(20.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QualitySheet(
    qualities: VideoQualities?,
    error: Boolean,
    selectedHeight: Int?,
    downloaded: Boolean,
    onSelect: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = YtSurface,
        contentColor = YtText,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 20.dp),
        ) {
            Text(
                "Quality",
                style = MaterialTheme.typography.titleMedium,
                color = YtText,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
            )
            Text(
                "Switching keeps your position — the video resumes right away.",
                style = MaterialTheme.typography.bodySmall,
                color = YtMuted,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 8.dp),
            )
            when {
                downloaded -> {
                    ChoiceRow(
                        label = "Downloaded file",
                        subtitle = "Original quality — delete the download to stream another one",
                        selected = true,
                        enabled = false,
                        onClick = {},
                    )
                }
                qualities == null -> {
                    if (error) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Couldn't load the quality options", color = YtText)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "Playback stays on Auto. Reopen this menu to try again.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = YtMuted,
                                )
                            }
                        }
                    } else {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                color = YtMuted,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("Checking available qualities…", color = YtMuted)
                        }
                    }
                }
                else -> {
                    val autoHeight = qualities.auto?.height
                    ChoiceRow(
                        label = "Auto",
                        subtitle = if (autoHeight != null) {
                            "Starts fastest (${autoHeight}p) • recommended"
                        } else {
                            "Recommended"
                        },
                        selected = selectedHeight == null,
                        onClick = { onSelect(null) },
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp)
                            .height(1.dp)
                            .background(Color(0x1FFFFFFF)),
                    )
                    qualities.options.forEach { option ->
                        val badges = buildList {
                            when {
                                option.height >= 2160 -> add("4K")
                                option.height >= 1440 -> add("2K")
                                option.height >= 720 -> add("HD")
                            }
                            if (option.muxed == null && option.videoOnly != null) add("Video + audio")
                        }
                        ChoiceRow(
                            label = option.label,
                            subtitle = badges.joinToString(" • ").ifBlank { null },
                            selected = selectedHeight == option.height,
                            onClick = { onSelect(option.height) },
                        )
                    }
                    Text(
                        "Higher qualities use more data.",
                        style = MaterialTheme.typography.bodySmall,
                        color = YtMuted,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaptionsSheet(
    subtitles: List<SubtitleTrack>,
    loading: Boolean,
    error: Boolean,
    selected: SubtitleTrack?,
    onSelect: (SubtitleTrack?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = YtSurface,
        contentColor = YtText,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 20.dp),
        ) {
            Text(
                "Captions",
                style = MaterialTheme.typography.titleMedium,
                color = YtText,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
            )
            Text(
                "The language stays on for the next videos too.",
                style = MaterialTheme.typography.bodySmall,
                color = YtMuted,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 8.dp),
            )
            ChoiceRow(
                label = "Off",
                subtitle = null,
                selected = selected == null,
                onClick = { onSelect(null) },
            )
            when {
                error -> Text(
                    "Couldn't load captions for this video.",
                    style = MaterialTheme.typography.bodySmall,
                    color = YtMuted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
                loading -> Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        color = YtMuted,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Checking available captions…", color = YtMuted)
                }
                subtitles.isEmpty() -> Text(
                    "No captions available for this video.",
                    style = MaterialTheme.typography.bodySmall,
                    color = YtMuted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
                else -> {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp)
                            .height(1.dp)
                            .background(Color(0x1FFFFFFF)),
                    )
                    subtitles.forEach { track ->
                        ChoiceRow(
                            label = track.label,
                            subtitle = null,
                            selected = selected != null && selected.languageTag == track.languageTag,
                            onClick = { onSelect(track) },
                        )
                    }
                    if (subtitles.any { it.autoGenerated }) {
                        Text(
                            "Auto-generated captions may be inaccurate.",
                            style = MaterialTheme.typography.bodySmall,
                            color = YtMuted,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    subtitle: String?,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) YtSurfaceHi else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) YtBlue else if (enabled) YtText else YtMuted,
                fontWeight = if (selected) FontWeight.SemiBold else null,
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = YtMuted)
            }
        }
        if (selected) {
            Icon(Icons.Filled.Check, "Selected", tint = YtBlue, modifier = Modifier.size(20.dp))
        }
    }
}
