package com.indiotube.app.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Typeface
import android.util.TypedValue
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
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
import com.indiotube.app.data.CaptionPrefs
import com.indiotube.app.data.CaptionStyleSettings
import com.indiotube.app.data.ChannelItem
import com.indiotube.app.data.Chapter
import com.indiotube.app.data.CommentItem
import com.indiotube.app.data.DlStatus
import com.indiotube.app.data.LiveChatMessage
import com.indiotube.app.data.SponsorSegment
import com.indiotube.app.data.SubtitleTrack
import com.indiotube.app.data.VideoItem
import com.indiotube.app.data.VideoQualities
import com.indiotube.app.data.chapterAt
import com.indiotube.app.data.chapterTimestamp
import com.indiotube.app.data.findCommentTimestamps
import com.indiotube.app.data.forLanguage
import com.indiotube.app.data.formatDuration
import com.indiotube.app.data.autoSkipTarget
import com.indiotube.app.data.isAtLiveEdge
import com.indiotube.app.data.recommendableVideos
import com.indiotube.app.data.sponsorLabel
import com.indiotube.app.data.sponsorSegmentAt
import com.indiotube.app.data.stripChapterLines
import com.indiotube.app.player.PlaybackController
import com.indiotube.app.player.QueueOps
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
    videoArg: VideoItem,
    context: List<VideoItem>,
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val activity = ctx as? Activity

    // The queue can move on its own (autoplay) or through Next while this
    // screen stays on the stack, so the playing item — not the one that opened
    // the screen — is the source of truth for everything below it: title,
    // description, comments, qualities and captions all follow it. Read
    // queue+index directly so a tap that just called setQueue is visible in
    // this frame instead of waiting for the combined `current` flow.
    val playingVideoFlow by player.current.collectAsState()
    val queue by player.queue.collectAsState()
    val queueIndex by player.index.collectAsState()
    val explicitIds by player.explicitIds.collectAsState()
    val playingVideo = queue.getOrNull(queueIndex) ?: playingVideoFlow
    val video = playingVideo ?: videoArg
    val playing by player.playing.collectAsState()
    val upNext by player.upNext.collectAsState()

    val buffering by player.buffering.collectAsState()
    val position by player.position.collectAsState()
    val duration by player.duration.collectAsState()
    val connected by player.connected.collectAsState()
    val active by container.accounts.active.collectAsState()
    val watchLater by container.library.watchLater.collectAsState()
    val playlists by container.library.playlists.collectAsState()
    val tasks by container.downloads.tasks.collectAsState()
    val dlItems by container.downloads.items.collectAsState()
    val history by container.library.history.collectAsState()
    val videoWatched = remember(history, video.videoId) {
        container.library.isWatched(video.videoId)
    }

    // These are keyed to the playing video so an autoplay/Next transition
    // swaps them in the same recomposition as the title, instead of leaving
    // the previous video's description or comments on screen.
    var watch by remember(video.videoId) { mutableStateOf<com.indiotube.app.data.WatchData?>(null) }
    var comments by remember(video.videoId) { mutableStateOf<List<CommentItem>>(emptyList()) }
    var commentsToken by remember(video.videoId) { mutableStateOf<String?>(null) }
    var loadingComments by remember(video.videoId) { mutableStateOf(false) }
    var loadingMoreComments by remember(video.videoId) { mutableStateOf(false) }

    // Live chat (or its replay). The continuation token is kept as state so
    // reopening the panel resumes where the conversation left off instead of
    // replaying the first page.
    var chatMessages by remember(video.videoId) {
        mutableStateOf<List<LiveChatMessage>>(emptyList())
    }
    var chatToken by remember(video.videoId) { mutableStateOf<String?>(null) }
    var chatExpanded by remember(video.videoId) { mutableStateOf(true) }
    var chatConnecting by remember(video.videoId) { mutableStateOf(false) }

    // Loaded reply threads, keyed by the parent comment id.

    var replyStates by remember(video.videoId) {
        mutableStateOf<Map<String, CommentRepliesState>>(emptyMap())
    }

    // Kept so turning captions off can clear the rendered cues immediately,
    // even before the player finishes rebuilding the item without them.
    var playerView by remember { mutableStateOf<PlayerView?>(null) }

    var controlsVisible by remember { mutableStateOf(true) }
    var fullscreen by remember { mutableStateOf(false) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    var seekHint by remember { mutableStateOf<Pair<Boolean, Long>?>(null) }
    var liked by remember(video.videoId) { mutableStateOf(container.library.isLiked(video.videoId)) }
    var disliked by remember(video.videoId) { mutableStateOf(false) }
    var descExpanded by remember(video.videoId) { mutableStateOf(false) }
    var saveSheet by remember { mutableStateOf(false) }
    var queueSheet by remember { mutableStateOf(false) }
    var chaptersSheet by remember(video.videoId) { mutableStateOf(false) }
    var qualitySheet by remember { mutableStateOf(false) }
    var downloadSheet by remember(video.videoId) { mutableStateOf(false) }
    var captionsSheet by remember { mutableStateOf(false) }
    var captionStyleSheet by remember { mutableStateOf(false) }
    var qualities by remember(video.videoId) { mutableStateOf<VideoQualities?>(null) }
    var qualityError by remember(video.videoId) { mutableStateOf(false) }
    var sponsorSegments by remember(video.videoId) { mutableStateOf<List<SponsorSegment>>(emptyList()) }
    var dismissedSponsors by remember(video.videoId) { mutableStateOf<Set<String>>(emptySet()) }
    var autoSkippedSegments by remember(video.videoId) { mutableStateOf<Set<String>>(emptySet()) }
    var skipNotice by remember(video.videoId) { mutableStateOf<String?>(null) }
    val sponsorSettings by container.sponsorBlockPrefs.settings.collectAsState()
    val qualityHeight by player.qualityHeight.collectAsState()
    val defaultQuality by player.defaultQualityHeight.collectAsState()
    val captionLanguage by container.captions.language.collectAsState()
    val captionStyle by container.captions.style.collectAsState()
    val downloadSettings by container.downloadPrefs.settings.collectAsState()
    val subtitle by player.subtitle.collectAsState()
    val autoplayNext by player.autoplayNext.collectAsState()
    val captionsAvailable = qualities?.subtitles?.isNotEmpty() == true
    val pageState = rememberLazyListState()

    // The feed badge is usually enough, but the watch page is authoritative:
    // a members-only video that arrived untagged still gets blocked here.
    val membersOnly = video.membersOnly || watch?.membersOnly == true
    LaunchedEffect(membersOnly) {
        if (membersOnly) {
            player.pause()
            downloadSheet = false
        }
    }

    // A new video is a new page: start it at the top instead of leaving the
    // previous video's scroll position (often mid-description or comments).
    LaunchedEffect(video.videoId) { pageState.scrollToItem(0) }

    // Make sure the video this page was opened for is actually playing. Keyed
    // on videoArg, not the derived `video`: keying on `video` made the guard
    // compare the playing item with itself and never switch (the mini-player
    // replay bug).
    LaunchedEffect(videoArg.videoId) {
        val activeId = player.queue.value.getOrNull(player.index.value)?.videoId
        if (activeId != videoArg.videoId) {
            val idx = context.indexOfFirst { it.videoId == videoArg.videoId }
            // If the tapped video isn't in the list (stale "up next" payload),
            // play it alone instead of falling back to the list's first item,
            // which would start an unrelated video under this screen.
            if (idx >= 0) {
                player.setQueue(context, idx, play = true)
            } else {
                player.setQueue(listOf(videoArg), 0, play = true)
            }
        }
        container.library.noteOpen(videoArg)
    }

    // watch metadata + first page of comments
    LaunchedEffect(video.videoId) {
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
                    membersOnly = w.membersOnly,
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

    // Reply threads are loaded per parent comment, the first time the user
    // expands them, then cached in this map. Replies arrive from the same
    // continuation endpoint as the top-level comments.
    fun toggleReplies(comment: CommentItem) {
        val token = comment.repliesToken ?: return
        val current = replyStates[comment.id]
        if (current?.expanded == true) {
            replyStates = replyStates + (comment.id to current.copy(expanded = false))
            return
        }
        if (current != null && (current.loading || current.replies.isNotEmpty())) {
            replyStates = replyStates + (comment.id to current.copy(expanded = true))
            return
        }
        replyStates = replyStates + (comment.id to CommentRepliesState(expanded = true, loading = true))
        scope.launch {
            try {
                val page = container.innerTube.comments(token)
                replyStates = replyStates + (
                    comment.id to CommentRepliesState(
                        expanded = true,
                        replies = page.comments,
                        continuation = page.continuation,
                    )
                    )
            } catch (_: Exception) {
                replyStates = replyStates + (comment.id to CommentRepliesState(expanded = true, error = true))
            }
        }
    }

    fun loadMoreReplies(comment: CommentItem) {
        val current = replyStates[comment.id] ?: return
        val token = current.continuation ?: return
        if (current.loading) return
        replyStates = replyStates + (comment.id to current.copy(loading = true))
        scope.launch {
            try {
                val page = container.innerTube.comments(token)
                val latest = replyStates[comment.id] ?: current
                replyStates = replyStates + (
                    comment.id to latest.copy(
                        loading = false,
                        error = false,
                        replies = (latest.replies + page.comments).distinctBy { it.id },
                        continuation = page.continuation,
                    )
                    )
            } catch (_: Exception) {
                replyStates = replyStates + (comment.id to current.copy(loading = false, error = true))
            }
        }
    }

    // Available quality ladder (muxed + video-only heights). Loaded in the
    // background; the player keeps running on Auto until the user picks one.
    LaunchedEffect(video.videoId, membersOnly) {
        if (membersOnly) {
            qualities = null
            qualityError = false
            return@LaunchedEffect
        }
        val loaded = runCatching {
            withContext(Dispatchers.IO) { container.resolver.qualities(video.videoId) }
        }.getOrNull()
        qualities = loaded
        qualityError = loaded == null
    }

    // SponsorBlock segments for the playing video, filtered to the categories
    // chosen in Settings. Auto-skip is off by default; with it off these only
    // drive the manual "Jump ahead" pill.
    LaunchedEffect(video.videoId, membersOnly, sponsorSettings.categories) {
        if (membersOnly) {
            sponsorSegments = emptyList()
            return@LaunchedEffect
        }
        sponsorSegments = container.sponsorBlock.segments(
            video.videoId,
            sponsorSettings.categories,
        )
    }

    // A skip notice self-dismisses; keeping the timer out of the skip effect
    // means the next skip restarts it instead of being swallowed.
    LaunchedEffect(skipNotice) {
        if (skipNotice != null) {
            delay(2200)
            skipNotice = null
        }
    }

    // Live chat polling. Stops as soon as the stream has no next continuation
    // and pauses while the panel is collapsed, so a background stream does not
    // keep polling YouTube. Replay chats tick slower: they are not live.
    LaunchedEffect(video.videoId, watch?.liveChat?.token, chatExpanded) {
        val info = watch?.liveChat ?: return@LaunchedEffect
        if (!chatExpanded) return@LaunchedEffect
        var token = chatToken ?: info.token
        chatConnecting = chatMessages.isEmpty()
        while (true) {
            val page = container.innerTube.liveChat(token, info.replay)
            if (page.messages.isNotEmpty()) {
                chatMessages = (chatMessages + page.messages)
                    .distinctBy { it.id }
                    .takeLast(200)
                chatConnecting = false
            }
            val next = page.continuation ?: break
            token = next
            chatToken = next
            delay(if (info.replay) 7_000 else 4_000)
        }
        chatConnecting = false
    }

    // Opening the sheet is a free head start: warm the CDN connections for the
    // likely high-quality picks while the user is still looking at the list.
    LaunchedEffect(qualitySheet, qualities) {
        if (qualitySheet) player.prewarmQualityOptions(qualities)
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

    // pick up where the user left off — but a video watched to the end starts
    // over, the way YouTube replays finished videos from the beginning.
    LaunchedEffect(controlsVisible, playing, buffering, scrub) {
        if (controlsVisible && playing && !buffering && scrub == null) {
            delay(3500)
            controlsVisible = false
        }
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Tablets keep the page visible in landscape: rotating no longer means
    // fullscreen, it reveals the two-pane watch page. Phones keep YouTube's
    // rotate-to-fullscreen gesture.
    val layout = rememberWindowLayout()
    val effectiveFullscreen = fullscreen || layout.autoFullscreenInLandscape

    // the bottom navigation stays everywhere except real fullscreen playback
    LaunchedEffect(effectiveFullscreen) { ui.playerFullscreen = effectiveFullscreen }
    DisposableEffect(Unit) {
        onDispose { ui.playerFullscreen = false }
    }

    // This page is the only place that can show the Up next card, so the
    // countdown is armed here. Leaving the page advances immediately (or stops
    // the countdown) instead of leaving a paused video behind the mini player.
    DisposableEffect(video.videoId) {
        player.setAutoplayCountdownArmed(true)
        onDispose { player.setAutoplayCountdownArmed(false) }
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
        // A tablet has room for a real fullscreen watch page, so the landscape
        // layout is not one. Entering from portrait still rotates so the video
        // gets the whole width; leaving hands rotation back to the device.
        if (layout.isTablet) {
            if (fullscreen) {
                fullscreen = false
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            } else {
                fullscreen = true
                if (!isLandscape) {
                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
                controlsVisible = true
            }
            return
        }
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
    val chapters = watch?.chapters.orEmpty()
    val subscribersText = watch?.subscribersText

    // Chapter timestamps are repeated at the top of most descriptions; once
    // they render as a chapter list they'd be duplicated as plain text.
    val descriptionText = remember(description, chapters) {
        stripChapterLines(description.orEmpty(), chapters)
    }

    // Collapsed previews flatten the description's blank lines; otherwise a
    // description that starts with a title followed by an empty line renders
    // the overflow ellipsis alone on the second line, which looks broken.
    val descriptionPreview = remember(descriptionText, chapters) {
        descriptionText.replace(Regex("\\s+"), " ").trim()
            .ifBlank { chapters.take(2).joinToString(" · ") { it.title } }
    }

    val merged = video.copy(

        title = title,
        channelName = channelName,
        channelId = channelId,
        channelAvatar = channelAvatar,
        viewsText = viewsText,
        publishedText = publishedText,
        membersOnly = membersOnly,
    )

    val downloaded = dlItems.containsKey(video.videoId)
    val dlTask = tasks[video.videoId]
    val subscribed = active?.subscriptions?.any { it.channelId == channelId } == true

    val durationF = duration.coerceAtLeast(1L).toFloat()
    val sliderValue = scrub ?: (position.toFloat() / durationF).coerceIn(0f, 1f)
    val posShown = scrub?.times(duration)?.toLong() ?: position

    // DVR behaviour for live streams: the seek bar covers the rewind window,
    // the left clock counts backwards from the live edge, and a GO LIVE pill
    // appears once the viewer has paused/rewound. A stream at the edge (or one
    // whose window is still empty) simply reads LIVE.
    val isLiveStream = video.isLive || watch?.isLive == true
    val atLiveEdge = !isLiveStream || isAtLiveEdge(posShown, duration)
    val behindByMs = (duration - posShown).coerceAtLeast(0L)
    val positionLabel = if (isLiveStream && !atLiveEdge) {
        "-" + formatDuration(behindByMs / 1000)
    } else {
        formatDuration(posShown / 1000)
    }
    val qualityLabel = qualityHeight?.let { h -> qualities?.labelFor(h) ?: "${h}p" } ?: "Auto"
    // The crowd-sourced segment the playhead is inside, unless already jumped.
    // With auto-skip on, a segment under the playhead is skipped instead of
    // being offered as a pill. The set keeps each segment to one skip so
    // scrolling back does not loop.
    val autoSkip = autoSkipTarget(
        sponsorSegments,
        position,
        autoSkippedSegments,
        sponsorSettings.autoSkip && playing && scrub == null,
    )
    LaunchedEffect(autoSkip?.UUID) {
        val target = autoSkip ?: return@LaunchedEffect
        autoSkippedSegments = autoSkippedSegments + target.UUID
        dismissedSponsors = dismissedSponsors + target.UUID
        player.seekTo(target.endMs)
        skipNotice = "Skipped ${sponsorLabel(target.category)}"
    }
    val jumpSegment = sponsorSegmentAt(sponsorSegments, position, dismissedSponsors)
    val chapterTitle = chapterAt(chapters, posShown)?.title
    val chapterMarks = remember(chapters, duration) {
        if (duration <= 0) {
            emptyList()
        } else {
            chapters.map { it.startMs }
                .filter { it in 1 until duration }
                .map { (it.toFloat() / duration).coerceIn(0f, 1f) }
        }
    }

    // YouTube's tablet watch page: the video and its page share the left
    // column while Up next becomes an independent rail on the right. On a
    // phone, or a portrait tablet, the same content is one scrolling column.
    val twoPane = layout.watchTwoPane

    fun LazyListScope.watchDetails() {
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isLiveStream) {
                            LiveBadge()
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(meta, style = MaterialTheme.typography.bodySmall, color = YtMuted)
                    }
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
                            membersOnly -> Icon(
                                Icons.Filled.Lock,
                                null,
                                tint = YtMuted,
                                modifier = Modifier.size(24.dp),
                            )
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
                        membersOnly -> "Members"
                        downloaded -> "Downloaded"
                        dlTask?.status == DlStatus.ACTIVE -> {
                            val task = dlTask
                            if (!task.indeterminate) {
                                "${(task.progress * 100).toInt()}%"
                            } else {
                                "Downloading"
                            }
                        }
                        dlTask?.status == DlStatus.PAUSED -> "Resume"
                        dlTask?.status == DlStatus.FAILED -> "Retry"
                        else -> "Download"
                    },
                ) {
                    when {
                        membersOnly -> ui.toast("Members-only video — can't download")
                        downloaded -> ui.toast("Saved in Downloads — watch it offline anytime")
                        dlTask?.status == DlStatus.ACTIVE -> container.downloads.pause(video.videoId)
                        dlTask?.status == DlStatus.PAUSED || dlTask?.status == DlStatus.FAILED ->
                            container.downloads.resume(video.videoId)
                        else -> downloadSheet = true
                    }
                }
                ActionButton(
                    icon = { Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, null, tint = YtText, modifier = Modifier.size(24.dp)) },
                    label = "Save",
                ) {
                    saveSheet = true
                }
                // Overflow menu, like the app's "..." next to Save: the
                // remainder actions live here instead of stretching the
                // row any further.
                Box {
                    var moreOpen by remember { mutableStateOf(false) }
                    ActionButton(
                        icon = { Icon(Icons.Filled.MoreVert, null, tint = YtText, modifier = Modifier.size(24.dp)) },
                        label = "More",
                    ) {
                        moreOpen = true
                    }
                    DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                        DropdownMenuItem(
                            text = {
                                Text(if (videoWatched) "Mark as unwatched" else "Mark as watched")
                            },
                            leadingIcon = {
                                Icon(
                                    if (videoWatched) Icons.Outlined.Refresh else Icons.Filled.CheckCircle,
                                    null,
                                    tint = YtText,
                                )
                            },
                            onClick = {
                                moreOpen = false
                                ui.toggleWatched(merged)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Play next") },
                            leadingIcon = {
                                Icon(Icons.AutoMirrored.Outlined.PlaylistPlay, null, tint = YtText)
                            },
                            onClick = {
                                moreOpen = false
                                ui.playNext(merged)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Add to queue") },
                            leadingIcon = {
                                Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = YtText)
                            },
                            onClick = {
                                moreOpen = false
                                ui.addToQueue(merged)
                            },
                        )
                    }
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

        if (!description.isNullOrBlank() || chapters.isNotEmpty()) {
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
                    if (descExpanded && chapters.isNotEmpty()) {
                        // Chapter rows seek straight to the marker. The
                        // inner clickable consumes the tap so the card
                        // doesn't collapse under the user's finger.
                        chapters.forEach { chapter ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { player.seekTo(chapter.startMs) }
                                    .padding(horizontal = 2.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    chapterTimestamp(chapter.startMs),
                                    color = YtBlue,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    chapter.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = YtText,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        if (descriptionText.isNotBlank()) Spacer(Modifier.height(8.dp))
                    }
                    if (descriptionText.isNotBlank()) {
                        Text(
                            if (descExpanded) descriptionText else descriptionPreview,
                            style = MaterialTheme.typography.bodyMedium,
                            color = YtText,
                            maxLines = if (descExpanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 19.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                    }

                    Text(
                        if (descExpanded) "Show less" else "Show more",
                        style = MaterialTheme.typography.labelLarge,
                        color = YtText,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        // ---- live chat -------------------------------------------------
        val chatInfo = watch?.liveChat
        if (chatInfo != null) {
            item {
                LiveChatSection(
                    messages = chatMessages,
                    replay = chatInfo.replay,
                    viewersText = if (isLiveStream) viewsText else null,
                    expanded = chatExpanded,
                    loading = chatConnecting,
                    onToggle = { chatExpanded = !chatExpanded },
                )
            }
        }

        // ---- comments -------------------------------------------------
        item {
            SectionHeader(
                listOfNotNull("Comments", watch?.commentsCountText).joinToString(" "),
            )
        }
        if (loadingComments && comments.isEmpty()) {
            items(3) { CommentSkeleton() }
        } else if (comments.isEmpty()) {
            item {
                Text(
                    when {
                        watch == null -> "Loading comments…"
                        watch?.commentsToken == null -> "Comments are turned off."
                        else -> "No comments yet. Be the first to react?"
                    },
                    color = YtMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
        items(comments, key = { it.id }) { c ->
            val replies = replyStates[c.id]
            CommentRow(
                c = c,
                replies = replies,
                onToggleReplies = if (c.repliesToken != null) {
                    { toggleReplies(c) }
                } else {
                    null
                },
                onLoadMoreReplies = if (replies?.continuation != null) {
                    { loadMoreReplies(c) }
                } else {
                    null
                },
                maxSeconds = if (duration > 0) duration / 1000 else 0,
                onSeek = { seconds ->
                    controlsVisible = true
                    player.seekTo(seconds * 1000)
                },
            )
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
        item { Spacer(Modifier.height(10.dp)) }
    }

    fun LazyListScope.upNextSection() {
        // ---- up next ---------------------------------------------------
        val related = watch?.related.orEmpty().recommendableVideos()
        val hasNext = queue.size > 1
        if (hasNext || related.isNotEmpty()) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Up next",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = YtText,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "Autoplay",
                        style = MaterialTheme.typography.labelMedium,
                        color = YtMuted,
                    )
                    Spacer(Modifier.width(4.dp))
                    AutoplaySwitch(
                        checked = autoplayNext,
                        onCheckedChange = { player.setAutoplayNext(it) },
                    )
                }
                HorizontalDivider(color = YtDivider)
            }
            items(related, key = { "rel-" + it.videoId }) { v ->
                VideoRow(
                    v,
                    onClick = { ui.openWatch(v, related) },
                    onChannelClick = { ui.openChannelOf(v) },
                    trailing = {
                        VideoActionsMenu(
                            onPlayNext = { ui.playNext(v) },
                            onAddToQueue = { ui.addToQueue(v) },
                        )
                    },
                )
            }
        }
        item { Spacer(Modifier.height(10.dp)) }
    }

    val playerSurface: @Composable () -> Unit = {
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
                        applyCaptionStyle(this, captionStyle)
                        playerView = this
                    }
                },
                update = { view ->
                    applyCaptionStyle(view, captionStyle)
                    if (connected) view.player = player.mediaController
                },
                onRelease = { view ->
                    view.player = null
                    if (playerView === view) playerView = null
                },
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
            if (!membersOnly && !playing && !buffering && !controlsVisible) {
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
            if (buffering && !membersOnly) {
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

            if (!membersOnly) {
                PlayerChrome(
                    visible = controlsVisible,
                    title = title,
                    fullscreen = effectiveFullscreen,
                    playing = playing,
                    buffering = buffering,
                    queueSize = queue.size,
                    sliderValue = sliderValue,
                    scrubbing = scrub != null,
                    positionText = positionLabel,
                    durationText = formatDuration(duration / 1000),
                    live = isLiveStream,
                    atLiveEdge = atLiveEdge,
                    onGoLive = { if (duration > 0) player.seekTo(duration) },
                    chapterTitle = chapterTitle,
                    chapterMarks = chapterMarks,
                    qualityLabel = qualityLabel,
                    captionsAvailable = captionsAvailable,
                    captionsOn = subtitle != null,
                    autoplayOn = autoplayNext,
                    onToggleAutoplay = { player.setAutoplayNext(it) },
                    onQuality = { qualitySheet = true },
                    onCaptions = { captionsSheet = true },

                    onBack = { ui.back() },
                    onTogglePlay = { player.togglePlayPause() },
                    onSeekBy = { player.seekBy(it) },
                    onNext = { player.next() },
                    onQueue = { queueSheet = true },
                    onChapters = { chaptersSheet = true },
                    onToggleFullscreen = { toggleFullscreen() },
                    onScrub = { scrub = it },
                    onScrubFinished = {
                        scrub?.let { player.seekTo((it * duration).toLong()) }
                        scrub = null
                    },
                )
            }

            // SponsorBlock's "Jump ahead" pill: shown while the playhead sits
            // inside a submitted segment, drawn over the video the way
            // Premium surfaces its own jump-ahead suggestion.
            jumpSegment?.let { segment ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(
                            end = 12.dp,
                            bottom = if (controlsVisible) 98.dp else 16.dp,
                        ),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xF2FFFFFF))
                            .clickable {
                                player.seekTo(segment.endMs)
                                dismissedSponsors = dismissedSponsors + segment.UUID
                            }
                            .padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.FastForward,
                            contentDescription = null,
                            tint = Color(0xFF0F0F0F),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Jump ahead",
                            color = Color(0xFF0F0F0F),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            // Toast-like confirmation for an automatic skip, bottom-left so
            // it never covers the manual pill on the right.
            skipNotice?.let { notice ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(
                            start = 12.dp,
                            bottom = if (controlsVisible) 98.dp else 16.dp,
                        ),
                    contentAlignment = Alignment.BottomStart,
                ) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xE6212121))
                            .padding(start = 10.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.FastForward,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            notice,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            // "Up next in 5…" card: appears when autoplay is about to move on.
            // Play now skips the wait, Cancel leaves the video paused at its end.
            upNext?.let { pending ->
                UpNextCard(
                    state = pending,
                    onPlayNow = { player.playUpNextNow() },
                    onCancel = { player.cancelUpNext() },
                    modifier = Modifier.padding(bottom = if (controlsVisible) 96.dp else 12.dp),
                )
            }

            if (membersOnly) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xF2000000))
                        .pointerInput(Unit) { detectTapGestures { } },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            tint = Color(0xFFE6E6E6),
                            modifier = Modifier.size(34.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Members-only video",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Join this channel on YouTube to watch it here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFBDBDBD),
                        )
                    }
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (twoPane && !effectiveFullscreen) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    playerSurface()
                    LazyColumn(
                        state = pageState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    ) {
                        watchDetails()
                    }
                }
                Box(Modifier.fillMaxHeight().width(1.dp).background(YtDivider))
                UpNextRail(
                    videos = watch?.related.orEmpty().recommendableVideos(),
                    autoplay = autoplayNext,
                    onToggleAutoplay = { player.setAutoplayNext(it) },
                    onOpen = { v -> ui.openWatch(v, watch?.related.orEmpty().recommendableVideos()) },
                    onChannelClick = { v -> ui.openChannelOf(v) },
                    onPlayNext = { v -> ui.playNext(v) },
                    onAddToQueue = { v -> ui.addToQueue(v) },
                    modifier = Modifier.width(layout.upNextRailWidth).fillMaxHeight(),
                )
            }
        } else {
            playerSurface()
            if (!effectiveFullscreen) {
                LazyColumn(state = pageState, modifier = Modifier.fillMaxSize()) {
                    watchDetails()
                    upNextSection()
                }
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

    if (queueSheet) {
        QueueSheet(
            queue = queue,
            currentIndex = queueIndex,
            explicitIds = explicitIds,
            onSelect = { player.playQueueItem(it) },
            onRemove = { player.removeFromQueue(it) },
            onMove = { from, to -> player.moveInQueue(from, to) },
            onClearQueue = {
                if (player.clearQueue()) ui.toast("Queue cleared")
            },
            onSaveQueue = { name ->
                val entries = QueueOps.visibleEntries(queue, queueIndex, explicitIds)
                if (entries.isEmpty()) {
                    ui.toast("Queue is empty")
                } else {
                    val playlist = container.library.createPlaylist(name)
                    entries.forEach { container.library.addToPlaylist(playlist.id, it.video) }
                    ui.toast("Saved ${entries.size} videos to ${playlist.name}")
                }
            },
            onDismiss = { queueSheet = false },
        )
    }

    if (chaptersSheet && chapters.isNotEmpty()) {
        ChaptersSheet(
            chapters = chapters,
            currentIndex = chapters.indexOfLast { position >= it.startMs },
            onSelect = { chapter ->
                player.seekTo(chapter.startMs)
                chaptersSheet = false
            },
            onDismiss = { chaptersSheet = false },
        )
    }

    if (qualitySheet) {
        QualitySheet(
            qualities = qualities,
            error = qualityError,
            selectedHeight = qualityHeight,
            downloaded = downloaded,
            defaultHeight = defaultQuality,
            onSelectDefault = { height ->
                player.setDefaultQualityHeight(height)
                ui.toast(
                    if (height == null) "Default quality: Auto" else "Default quality: ${height}p",
                )
            },
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

    if (downloadSheet) {
        DownloadQualitySheet(
            qualities = qualities,
            error = qualityError,
            defaultHeight = downloadSettings.defaultHeight,
            onSelect = { height ->
                container.downloads.start(merged, height)
                val label = if (height == null) "Auto" else "${height}p"
                ui.toast("Download started ($label)")
                downloadSheet = false
            },
            onDismiss = { downloadSheet = false },
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
                    playerView?.subtitleView?.setCues(null)
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
            onStyle = {
                captionsSheet = false
                captionStyleSheet = true
            },
            onDismiss = { captionsSheet = false },
        )
    }

    if (captionStyleSheet) {
        CaptionStyleSheet(
            prefs = container.captions,
            style = captionStyle,
            onDismiss = { captionStyleSheet = false },
        )
    }
}

@Composable
private fun AutoplaySwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val fraction by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "autoplay",
    )
    val trackColor = lerp(YtSurfaceHi, YtRed, fraction)
    val thumbColor = lerp(Color(0xFFBDBDBD), Color.White, fraction)
    Box(
        modifier = Modifier
            .size(width = 48.dp, height = 32.dp)
            .clip(RoundedCornerShape(50))
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .semantics {
                contentDescription = "Autoplay"
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(width = 36.dp, height = 20.dp)) {
            val trackHeight = size.height
            val trackWidth = size.width
            val radius = trackHeight / 2f
            drawRoundRect(color = trackColor, cornerRadius = CornerRadius(radius))
            if (fraction < 1f) {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.4f * (1f - fraction)),
                    cornerRadius = CornerRadius(radius),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            val inset = 3.dp.toPx()
            val thumbRadius = (trackHeight - inset * 2f) / 2f
            val travel = trackWidth - inset * 2f - thumbRadius * 2f
            val centerX = inset + thumbRadius + travel * fraction
            val centerY = trackHeight / 2f
            drawCircle(
                color = Color.Black.copy(alpha = 0.22f),
                radius = thumbRadius,
                center = Offset(centerX, centerY + 0.5.dp.toPx()),
            )
            drawCircle(
                color = thumbColor,
                radius = thumbRadius,
                center = Offset(centerX, centerY),
            )
        }
    }
}

// YouTube's tablet watch page keeps recommendations in a rail beside the
// video. It scrolls independently of the page on the left and carries the
// same Autoplay switch as the inline Up next section.
@Composable
internal fun UpNextRail(
    videos: List<VideoItem>,
    autoplay: Boolean,
    onToggleAutoplay: (Boolean) -> Unit,
    onOpen: (VideoItem) -> Unit,
    onChannelClick: (VideoItem) -> Unit,
    onPlayNext: (VideoItem) -> Unit,
    onAddToQueue: (VideoItem) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    LazyColumn(modifier, state = listState) {
        item(key = "up-next-header") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Up next",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = YtText,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Autoplay",
                    style = MaterialTheme.typography.labelMedium,
                    color = YtMuted,
                )
                Spacer(Modifier.width(4.dp))
                AutoplaySwitch(checked = autoplay, onCheckedChange = onToggleAutoplay)
            }
            HorizontalDivider(color = YtDivider)
        }
        if (videos.isEmpty()) {
            item(key = "up-next-empty") {
                Text(
                    "No recommendations right now",
                    color = YtMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                )
            }
        }
        items(videos, key = { "rail-" + it.videoId }) { v ->
            // Slightly denser than the inline list so the title keeps room
            // next to the thumbnail in the narrower rail.
            VideoRow(
                v,
                height = 84.dp,
                onClick = { onOpen(v) },
                onChannelClick = { onChannelClick(v) },
                trailing = {
                    VideoActionsMenu(
                        onPlayNext = { onPlayNext(v) },
                        onAddToQueue = { onAddToQueue(v) },
                    )
                },
            )
        }
        item { Spacer(Modifier.height(10.dp)) }
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
    live: Boolean,
    atLiveEdge: Boolean,
    onGoLive: () -> Unit,
    chapterTitle: String?,
    chapterMarks: List<Float>,
    qualityLabel: String,
    captionsAvailable: Boolean,
    captionsOn: Boolean,
    autoplayOn: Boolean,
    onToggleAutoplay: (Boolean) -> Unit,
    onQuality: () -> Unit,
    onCaptions: () -> Unit,

    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onNext: () -> Unit,
    onQueue: () -> Unit,
    onChapters: () -> Unit,
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
                if (!chapterTitle.isNullOrBlank()) {
                    Text(
                        chapterTitle,
                        color = Color(0xE6FFFFFF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(start = 2.dp, bottom = 2.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable(onClickLabel = "Show chapters", onClick = onChapters)
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(positionText, color = Color.White, fontSize = 11.sp)
                    YtSeekBar(
                        value = sliderValue,
                        markers = chapterMarks,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        onScrub = onScrub,
                        onScrubFinished = onScrubFinished,
                    )
                    if (live) {
                        if (atLiveEdge) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(7.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(YtRed),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "LIVE",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        } else {
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xF2FFFFFF))
                                    .clickable(onClick = onGoLive)
                                    .padding(horizontal = 5.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier
                                        .size(6.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(YtRed),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "GO LIVE",
                                    color = Color(0xFF0F0F0F),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    } else {
                        Text(durationText, color = Color.White, fontSize = 11.sp)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Autoplay",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.width(4.dp))
                    AutoplaySwitch(
                        checked = autoplayOn,
                        onCheckedChange = onToggleAutoplay,
                    )
                    Spacer(Modifier.weight(1f))
                    if (queueSize > 1) {
                        IconButton(onClick = onQueue, modifier = Modifier.size(40.dp)) {
                            Icon(
                                Icons.AutoMirrored.Filled.QueueMusic,
                                "Queue",
                                tint = Color.White,
                                modifier = Modifier.size(25.dp),
                            )
                        }
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
    markers: List<Float> = emptyList(),
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
                // Chapter boundaries: tiny, low-contrast dots instead of
                // tall ticks so the timeline stays calm.
                if (markers.isNotEmpty()) {
                    val dotR = 1.25.dp.toPx()
                    markers.forEach { fraction ->
                        val x = size.width * fraction.coerceIn(0f, 1f)
                        if (x > dotR && x < size.width - dotR) {
                            drawCircle(
                                color = Color(0x73FFFFFF),
                                radius = dotR,
                                center = Offset(x, cy),
                            )
                        }
                    }
                }
                if (dragging) {
                    val cx = activeW.coerceIn(9.dp.toPx(), (size.width - 9.dp.toPx()).coerceAtLeast(9.dp.toPx()))
                    drawCircle(Color(0x66FFFFFF), radius = 9.dp.toPx(), center = Offset(cx, cy))
                    drawCircle(YtRed, radius = 6.5f.dp.toPx(), center = Offset(cx, cy))
                }
            },
    )
}

private data class CommentRepliesState(
    val expanded: Boolean = false,
    val loading: Boolean = false,
    val replies: List<CommentItem> = emptyList(),
    val continuation: String? = null,
    val error: Boolean = false,
)

// Comment text with its timestamps rendered as links. Tapping "1:42" seeks,
// exactly like the YouTube app. Links are built as annotations so the rest of
// the comment keeps normal text behaviour (maxLines, ellipsis, Read more).
@Composable
private fun CommentText(
    text: String,
    nested: Boolean,
    maxLines: Int,
    maxSeconds: Long,
    onOverflow: (Boolean) -> Unit,
    onSeek: ((Long) -> Unit)?,
) {
    val seekNow = rememberUpdatedState(onSeek)
    val spans = remember(text, maxSeconds) { findCommentTimestamps(text, maxSeconds) }
    val annotated = remember(text, spans) {
        buildAnnotatedString {
            append(text)
            spans.forEach { span ->
                addLink(
                    LinkAnnotation.Clickable(
                        tag = "comment-ts-${span.start}-${span.seconds}",
                        styles = TextLinkStyles(style = SpanStyle(color = YtBlue)),
                    ) {
                        seekNow.value?.invoke(span.seconds)
                    },
                    span.start,
                    span.end,
                )
            }
        }
    }
    Text(
        annotated,
        style = MaterialTheme.typography.bodyMedium,
        color = YtText,
        lineHeight = if (nested) 17.sp else 19.sp,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { result ->
            if (maxLines != Int.MAX_VALUE) onOverflow(result.hasVisualOverflow)
        },
    )
}

// Shimmer-free placeholders shaped like real comment rows. Three of them fill
// the gap while the first page loads, instead of one anonymous grey block.
@Composable
private fun CommentSkeleton() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(50))
                .background(YtSurfaceHi),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Box(
                Modifier
                    .fillMaxWidth(0.35f)
                    .height(9.dp)
                    .clip(RoundedCornerShape(50))
                    .background(YtSurfaceHi),
            )
            Spacer(Modifier.height(9.dp))
            Box(
                Modifier
                    .fillMaxWidth(0.92f)
                    .height(9.dp)
                    .clip(RoundedCornerShape(50))
                    .background(YtSurfaceHi),
            )
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth(0.65f)
                    .height(9.dp)
                    .clip(RoundedCornerShape(50))
                    .background(YtSurfaceHi),
            )
        }
    }
}

@Composable
private fun CommentRow(
    c: CommentItem,
    replies: CommentRepliesState? = null,
    onToggleReplies: (() -> Unit)? = null,
    onLoadMoreReplies: (() -> Unit)? = null,
    nested: Boolean = false,
    maxSeconds: Long = 0,
    onSeek: ((Long) -> Unit)? = null,
) {
    // Long comments collapse to six lines with a Read more link, the way the
    // YouTube app does, instead of pushing every other comment off screen.
    var expanded by remember(c.id) { mutableStateOf(false) }
    var overflows by remember(c.id) { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(
                start = if (nested) 6.dp else 12.dp,
                end = 12.dp,
                top = if (nested) 5.dp else 8.dp,
                bottom = if (nested) 3.dp else 8.dp,
            ),
    ) {
        ChannelAvatar(c.author, c.avatar, if (nested) 24.dp else 32.dp)
        Spacer(Modifier.width(if (nested) 9.dp else 12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.author,
                    fontSize = if (nested) 11.5.sp else 12.sp,
                    color = YtMuted,
                    maxLines = 1,
                )
                if (!c.publishedText.isNullOrBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        c.publishedText,
                        fontSize = if (nested) 10.5.sp else 11.sp,
                        color = YtMuted,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            CommentText(
                text = c.text,
                nested = nested,
                maxLines = if (expanded) Int.MAX_VALUE else if (nested) 4 else 6,
                maxSeconds = maxSeconds,
                onOverflow = { if (!expanded) overflows = it },
                onSeek = onSeek,
            )
            if (overflows) {
                Text(
                    if (expanded) "Show less" else "Read more",
                    fontSize = 12.sp,
                    color = YtMuted,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { expanded = !expanded }
                        .padding(vertical = 4.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.ThumbUp,
                    null,
                    tint = YtMuted,
                    modifier = Modifier.size(if (nested) 13.dp else 15.dp),
                )
                if (!c.likesText.isNullOrBlank() && c.likesText != "0") {
                    Spacer(Modifier.width(5.dp))
                    Text(c.likesText, fontSize = 11.5.sp, color = YtMuted)
                }
                if (!c.replyCountText.isNullOrBlank() || onToggleReplies != null) {
                    Spacer(Modifier.width(14.dp))
                    val label = when {
                        replies?.expanded == true -> "Hide replies"
                        !c.replyCountText.isNullOrBlank() -> c.replyCountText
                        else -> "View replies"
                    }
                    Text(
                        label,
                        fontSize = 11.5.sp,
                        color = YtMuted,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable(enabled = onToggleReplies != null) { onToggleReplies?.invoke() }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
            if (replies != null && replies.expanded) {
                Spacer(Modifier.height(4.dp))
                when {
                    replies.loading && replies.replies.isEmpty() ->
                        LoadingBox(Modifier.height(36.dp))
                    replies.error && replies.replies.isEmpty() ->
                        Text(
                            "Couldn't load replies — tap to retry",
                            fontSize = 11.5.sp,
                            color = YtMuted,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { onToggleReplies?.invoke() }
                                .padding(vertical = 4.dp),
                        )
                    else -> {
                        replies.replies.forEach { reply ->
                            CommentRow(
                                reply,
                                nested = true,
                                maxSeconds = maxSeconds,
                                onSeek = onSeek,
                            )
                        }
                        if (replies.continuation != null) {
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onLoadMoreReplies?.invoke() }
                                    .padding(horizontal = 6.dp, vertical = 5.dp),
                            ) {
                                Text(
                                    "Show more replies",
                                    fontSize = 11.5.sp,
                                    color = YtMuted,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
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
                    icon = { Icon(Icons.AutoMirrored.Outlined.PlaylistPlay, null, tint = YtText, modifier = Modifier.size(22.dp)) },
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
    defaultHeight: Int?,
    onSelectDefault: (Int?) -> Unit,
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
            DefaultQualitySection(
                selectedHeight = defaultHeight,
                onSelect = onSelectDefault,
            )
        }
    }
}

// The rungs a default quality can be pinned to. Fixed on purpose: the default
// is global, and a video that does not offer the chosen rung simply starts on
// the next lower one it has.
internal val DefaultQualityHeights = listOf(360, 480, 720, 1080, 1440, 2160)

// What new videos start on instead of plain Auto. Kept apart from the ladder
// above it so the sheet always offers the choice, even while a video's
// qualities are still loading (or failed to load).
@Composable
internal fun DefaultQualitySection(
    selectedHeight: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            "Default for new videos",
            style = MaterialTheme.typography.titleMedium,
            color = YtText,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp),
        )
        Text(
            "Every video starts on this quality; one that doesn't offer it uses the next lower rung. " +
                "Picking a quality for the video you're watching still wins for that video.",
            style = MaterialTheme.typography.bodySmall,
            color = YtMuted,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 8.dp),
        )
        ChoiceRow(
            label = "Auto",
            subtitle = "YouTube's pick — starts fastest",
            selected = selectedHeight == null,
            onClick = { onSelect(null) },
        )
        DefaultQualityHeights.forEach { height ->
            val badge = when {
                height >= 2160 -> "4K"
                height >= 1440 -> "2K"
                height >= 1080 -> "Full HD"
                height >= 720 -> "HD"
                else -> "SD"
            }
            ChoiceRow(
                label = "${height}p",
                subtitle = badge,
                selected = selectedHeight == height,
                onClick = { onSelect(height) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadQualitySheet(
    qualities: VideoQualities?,
    error: Boolean,
    defaultHeight: Int?,
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
                "Download quality",
                style = MaterialTheme.typography.titleMedium,
                color = YtText,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
            )
            Text(
                "Downloads save one file with video and audio for offline playback.",
                style = MaterialTheme.typography.bodySmall,
                color = YtMuted,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 8.dp),
            )
            when {
                qualities == null -> {
                    if (error) {
                        Text(
                            "Couldn't load the quality options. Try again in a moment.",
                            style = MaterialTheme.typography.bodySmall,
                            color = YtMuted,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                        )
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
                    val downloadable = qualities.downloadOptions()
                    val autoHeight = qualities.auto?.height
                    val preferred = defaultHeight?.takeIf { height -> downloadable.any { it.height == height } }
                    ChoiceRow(
                        label = "Auto",
                        subtitle = if (autoHeight != null) {
                            "Best single file (${autoHeight}p) \u2022 recommended"
                        } else {
                            "Best single file"
                        },
                        selected = preferred == null,
                        onClick = { onSelect(null) },
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp)
                            .height(1.dp)
                            .background(Color(0x1FFFFFFF)),
                    )
                    downloadable.forEach { option ->
                        val badge = when {
                            option.height >= 2160 -> "4K"
                            option.height >= 1440 -> "2K"
                            option.height >= 720 -> "HD"
                            else -> null
                        }
                        val merged = option.muxed == null
                        val subtitle = listOfNotNull(
                            badge,
                            if (merged) "Video + audio" else null,
                        ).joinToString(" • ").ifBlank { null }
                        ChoiceRow(
                            label = option.label,
                            subtitle = subtitle,
                            selected = preferred == option.height,
                            onClick = { onSelect(option.height) },
                        )
                    }
                    if (downloadable.isEmpty()) {
                        Text(
                            "No downloadable file was offered for this video.",
                            style = MaterialTheme.typography.bodySmall,
                            color = YtMuted,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        )
                    }
                    Text(
                        "Higher qualities use more data. Video and audio are combined into one file.",
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
    onStyle: () -> Unit,
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
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .height(1.dp)
                    .background(Color(0x1FFFFFFF)),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onStyle)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = null,
                    tint = YtText,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    "Caption settings",
                    style = MaterialTheme.typography.bodyLarge,
                    color = YtText,
                )
            }
        }
    }
}

// YouTube-style caption customization: live preview, size, text colour,
// background opacity, edge style and font. Every change is applied to the
// player immediately (the style is observed by WatchScreen) and persisted.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptionStyleSheet(
    prefs: CaptionPrefs,
    style: CaptionStyleSettings,
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
                .padding(bottom = 24.dp),
        ) {
            Text(
                "Caption settings",
                style = MaterialTheme.typography.titleMedium,
                color = YtText,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
            )
            Text(
                "Applies to every video, including auto-generated captions.",
                style = MaterialTheme.typography.bodySmall,
                color = YtMuted,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp),
            )

            // Live preview with the exact colours and size in use.
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF161616))
                    .height(96.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Captions preview",
                    color = Color(style.textColor),
                    fontSize = style.textSizeSp.sp,
                    fontFamily = captionFontFamily(style.fontFamily),
                    modifier = Modifier
                        .background(Color(style.backgroundColor))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }

            CaptionSettingLabel("Text size")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Slider(
                    value = style.textSizeSp,
                    onValueChange = { prefs.setTextSize(it) },
                    valueRange = 10f..24f,
                    steps = 13,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "${style.textSizeSp.toInt()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = YtMuted,
                )
            }

            CaptionSettingLabel("Text colour")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CAPTION_TEXT_COLORS.forEach { (color, name) ->
                    val selected = style.textColor == color
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(color))
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) Color.White else Color(0x33FFFFFF),
                                shape = CircleShape,
                            )
                            .clickable { prefs.setTextColor(color) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = name,
                                tint = if (Color(color).luminance() > 0.5f) Color.Black else Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            CaptionSettingLabel("Background opacity — ${style.backgroundOpacity}%")
            Slider(
                value = style.backgroundOpacity.toFloat(),
                onValueChange = { prefs.setBackgroundOpacity(it.toInt()) },
                valueRange = 0f..100f,
                steps = 9,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            CaptionSettingLabel("Bottom margin — ${style.bottomMarginPercent}%")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Slider(
                    value = style.bottomMarginPercent.toFloat(),
                    onValueChange = { prefs.setBottomMargin(it.toInt()) },
                    valueRange = 0f..30f,
                    steps = 5,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "${style.bottomMarginPercent}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = YtMuted,
                )
            }

            CaptionSettingLabel("Edge style")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CAPTION_EDGE_OPTIONS.forEach { (value, label) ->
                    CaptionOptionPill(
                        label = label,
                        selected = style.edgeStyle == value,
                        onClick = { prefs.setEdgeStyle(value) },
                    )
                }
            }

            CaptionSettingLabel("Font")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CAPTION_FONT_OPTIONS.forEach { (value, label) ->
                    CaptionOptionPill(
                        label = label,
                        selected = style.fontFamily == value,
                        onClick = { prefs.setFontFamily(value) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CaptionSettingLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = YtText,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun CaptionOptionPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) Color.Black else YtText,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Color.White else Color(0x1FFFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

private fun captionFontFamily(name: String) = when (name) {
    "serif" -> FontFamily.Serif
    "monospace" -> FontFamily.Monospace
    else -> FontFamily.SansSerif
}

private val CAPTION_TEXT_COLORS = listOf(
    0xFFFFFFFF.toInt() to "White",
    0xFFFFFF00.toInt() to "Yellow",
    0xFF00FF00.toInt() to "Green",
    0xFF00FFFF.toInt() to "Cyan",
    0xFFFF00FF.toInt() to "Magenta",
    0xFFFF0000.toInt() to "Red",
    0xFF000000.toInt() to "Black",
)

private val CAPTION_EDGE_OPTIONS = listOf(
    CaptionStyleSettings.EDGE_NONE to "None",
    CaptionStyleSettings.EDGE_OUTLINE to "Outline",
    CaptionStyleSettings.EDGE_SHADOW to "Shadow",
)

private val CAPTION_FONT_OPTIONS = listOf(
    "sans-serif" to "Sans",
    "serif" to "Serif",
    "monospace" to "Mono",
)

// Applies the user's caption look to a PlayerView. Embedded styles stay off so
// auto-generated captions cannot override the chosen appearance, and the
// bottom padding lifts captions clear of the player controls.
private fun applyCaptionStyle(
    view: PlayerView,
    style: CaptionStyleSettings,
) {
    view.subtitleView?.apply {
        setStyle(
            CaptionStyleCompat(
                style.textColor,
                style.backgroundColor,
                android.graphics.Color.TRANSPARENT,
                when (style.edgeStyle) {
                    CaptionStyleSettings.EDGE_OUTLINE ->
                        CaptionStyleCompat.EDGE_TYPE_OUTLINE
                    CaptionStyleSettings.EDGE_SHADOW ->
                        CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
                    else -> CaptionStyleCompat.EDGE_TYPE_NONE
                },
                android.graphics.Color.BLACK,
                Typeface.create(style.fontFamily, Typeface.NORMAL),
            ),
        )
        setApplyEmbeddedStyles(false)
        setApplyEmbeddedFontSizes(false)
        setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, style.textSizeSp)
        // User-tunable distance from the bottom edge — small by default so
        // captions hug the bottom like YouTube, with a little breathing room.
        setBottomPaddingFraction(style.bottomPaddingFraction)
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

@Composable
private fun VideoActionsMenu(onPlayNext: () -> Unit, onAddToQueue: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.MoreVert,
                "More options",
                tint = YtMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Play next") },
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Outlined.PlaylistPlay, null, tint = YtText)
                },
                onClick = {
                    open = false
                    onPlayNext()
                },
            )
            DropdownMenuItem(
                text = { Text("Add to queue") },
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = YtText)
                },
                onClick = {
                    open = false
                    onAddToQueue()
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(
    queue: List<VideoItem>,
    currentIndex: Int,
    explicitIds: Set<String>,
    onSelect: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onClearQueue: () -> Unit,
    onSaveQueue: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val entries = QueueOps.visibleEntries(queue, currentIndex, explicitIds)
    val upcoming = entries.count { it.index != currentIndex }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmClear by remember { mutableStateOf(false) }
    var saveOpen by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("My queue") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = YtSurface,
        contentColor = YtText,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Queue",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = YtText,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (upcoming == 1) "1 video" else "$upcoming videos",
                    style = MaterialTheme.typography.bodySmall,
                    color = YtMuted,
                )
                if (upcoming > 0) {
                    IconButton(
                        onClick = {
                            saveName = "My queue"
                            saveOpen = true
                        },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.PlaylistAdd,
                            "Save queue as playlist",
                            tint = YtText,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(
                        onClick = { confirmClear = true },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Delete,
                            "Clear queue",
                            tint = YtText,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            HorizontalDivider(color = YtDivider)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                itemsIndexed(entries, key = { _, entry -> entry.index }) { displayIndex, entry ->
                    val current = entry.index == currentIndex
                    // The playing row is entries[0]; never move a queued item
                    // above it, or it would count as already watched.
                    val canMoveUp = displayIndex > 1
                    val canMoveDown = displayIndex < entries.lastIndex
                    QueueRow(
                        video = entry.video,
                        current = current,
                        number = if (current) {
                            0
                        } else {
                            entries.take(displayIndex).count { it.index != currentIndex } + 1
                        },
                        canMoveUp = canMoveUp,
                        canMoveDown = canMoveDown,
                        onSelect = { onSelect(entry.index) },
                        onRemove = { onRemove(entry.index) },
                        onMoveUp = {
                            entries.getOrNull(displayIndex - 1)?.let { onMove(entry.index, it.index) }
                        },
                        onMoveDown = {
                            entries.getOrNull(displayIndex + 1)?.let { onMove(entry.index, it.index) }
                        },
                    )
                }
                if (upcoming == 0) {
                    item(key = "queue-empty") {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 22.dp)) {
                            Text(
                                "Your queue is empty",
                                style = MaterialTheme.typography.titleSmall,
                                color = YtText,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Videos you add with Play next or Add to queue will show up here.",
                                style = MaterialTheme.typography.bodySmall,
                                color = YtMuted,
                                lineHeight = 18.sp,
                            )
                        }
                    }
                }
            }
        }
    }

    if (saveOpen) {
        AlertDialog(
            onDismissRequest = { saveOpen = false },
            containerColor = YtSurface,
            titleContentColor = YtText,
            textContentColor = YtMuted,
            title = { Text("Save queue") },
            text = {
                Column {
                    Text(
                        "Saves the playing video and $upcoming queued ${if (upcoming == 1) "video" else "videos"} to a new playlist.",
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = saveName,
                        onValueChange = { saveName = it },
                        singleLine = true,
                        placeholder = { Text("Playlist name", color = YtMuted) },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = saveName.isNotBlank(),
                    onClick = {
                        onSaveQueue(saveName.trim())
                        saveOpen = false
                    },
                ) {
                    Text("Save", color = if (saveName.isNotBlank()) YtBlue else YtMuted)
                }
            },
            dismissButton = {
                TextButton(onClick = { saveOpen = false }) {
                    Text("Cancel", color = YtMuted)
                }
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = YtSurface,
            titleContentColor = YtText,
            textContentColor = YtMuted,
            title = { Text("Clear queue?") },
            text = {
                Text(
                    "This removes the $upcoming queued ${if (upcoming == 1) "video" else "videos"}. The video you're watching keeps playing.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onClearQueue()
                    confirmClear = false
                }) {
                    Text("Clear", color = YtRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text("Cancel", color = YtMuted)
                }
            },
        )
    }
}

@Composable
private fun QueueRow(
    video: VideoItem,
    current: Boolean,
    number: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (current) YtSurfaceHi.copy(alpha = 0.5f) else Color.Transparent)
            // Tapping the playing row would seek it back to 0: it is a status
            // line, not a destination.
            .clickable(enabled = !current, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
            if (current) {
                Icon(
                    Icons.Filled.PlayArrow,
                    "Now playing",
                    tint = YtRed,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                Text("$number", color = YtMuted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.width(6.dp))
        Thumb(
            model = video.thumbnail,
            badge = video.durationText,
            live = video.isLive,
            corner = 6.dp,
            membersOnly = video.membersOnly,
            progress = LocalWatchProgress.current(video),
            modifier = Modifier.width(72.dp).height(40.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                video.title,
                style = MaterialTheme.typography.titleSmall,
                color = YtText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (current) "Now playing" else video.channelName.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = if (current) YtRed else YtMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!current) {
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.MoreVert,
                        "Queue options",
                        tint = YtMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Move up") },
                        leadingIcon = { Icon(Icons.Filled.KeyboardArrowUp, null, tint = YtText) },
                        enabled = canMoveUp,
                        onClick = {
                            menuOpen = false
                            onMoveUp()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Move down") },
                        leadingIcon = { Icon(Icons.Filled.KeyboardArrowDown, null, tint = YtText) },
                        enabled = canMoveDown,
                        onClick = {
                            menuOpen = false
                            onMoveDown()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Remove from queue") },
                        leadingIcon = { Icon(Icons.Filled.Close, null, tint = YtText) },
                        onClick = {
                            menuOpen = false
                            onRemove()
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChaptersSheet(
    chapters: List<Chapter>,
    currentIndex: Int,
    onSelect: (Chapter) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = currentIndex.coerceAtLeast(0),
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = YtSurface,
        contentColor = YtText,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Chapters",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = YtText,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (chapters.size == 1) "1 chapter" else "${chapters.size} chapters",
                    style = MaterialTheme.typography.bodySmall,
                    color = YtMuted,
                )
            }
            HorizontalDivider(color = YtDivider)
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
            ) {
                itemsIndexed(chapters, key = { index, chapter -> "$index-${chapter.startMs}" }) { index, chapter ->
                    val current = index == currentIndex
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (current) YtSurfaceHi.copy(alpha = 0.5f) else Color.Transparent)
                            .clickable { onSelect(chapter) }
                            .padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            chapterTimestamp(chapter.startMs),
                            color = if (current) YtRed else YtBlue,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(52.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            chapter.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (current) YtRed else YtText,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (current) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                "Playing",
                                tint = YtRed,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
