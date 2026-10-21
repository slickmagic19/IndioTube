package com.indiotube.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.indiotube.app.data.VideoItem
import com.indiotube.app.data.normalizeImageUrl

// ---- thumbnails ---------------------------------------------------------------

// Ambient lookup for the red "already watched" progress bar under thumbnails.
// Provided once at the app root from the library history; the default returns
// nothing, so previews and tests don't need a provider.
val LocalWatchProgress = compositionLocalOf<(VideoItem) -> Float?> { { null } }

@Composable
fun Thumb(
    model: Any?,
    modifier: Modifier = Modifier,
    badge: String? = null,
    live: Boolean = false,
    corner: Dp = 12.dp,
    progress: Float? = null,
    membersOnly: Boolean = false,
) {
    val image = if (model is String) normalizeImageUrl(model) else model
    Box(modifier) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(corner))
                .background(YtSurfaceHi),
        ) {
            if (image != null) {
                AsyncImage(
                    model = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Filled.PlayArrow,
                    null,
                    tint = Color(0xFF555555),
                    modifier = Modifier.align(Alignment.Center).size(36.dp),
                )
            }
            // YouTube's red watch-progress line along the bottom edge.
            if (progress != null && progress > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(YtRed),
                )
            }
        }
        if (membersOnly) {
            Text(
                "MEMBERS ONLY",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xCC272727))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
        if (live) {
            Text(
                "LIVE",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(YtRed)
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
        } else if (!badge.isNullOrBlank()) {
            Text(
                badge,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(YtBadge)
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
fun ChannelAvatar(
    name: String?,
    url: String?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val initial = name?.trim()?.firstOrNull()?.uppercase() ?: "?"
    val image = normalizeImageUrl(url)
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(avatarColor(name ?: "?")),
        contentAlignment = Alignment.Center,
    ) {
        // The initial is painted first and the real photo sits on top, so a
        // slow or failing avatar image degrades to a readable initial instead
        // of a blank circle.
        Text(
            initial,
            color = Color.White,
            fontSize = (size.value * 0.42f).sp,
            fontWeight = FontWeight.Medium,
        )
        if (image != null) {
            AsyncImage(
                model = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// ---- video cards ---------------------------------------------------------------

// Feedback actions offered by the card overflow menu, mirroring YouTube's
// "Not interested" / "Don't recommend channel" plus a positive counterpart.
enum class FeedFeedback { INTERESTED, NOT_INTERESTED, DONT_RECOMMEND_CHANNEL }

// Full-width feed card: 16:9 thumbnail, then avatar + title + meta.
@Composable
fun VideoCard(
    video: VideoItem,
    onClick: () -> Unit,
    onChannelClick: (() -> Unit)? = null,
    onFeedback: ((FeedFeedback) -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(bottom = 14.dp),
    ) {
        Thumb(
            model = video.thumbnail,
            badge = video.durationText,
            live = video.isLive,
            membersOnly = video.membersOnly,
            progress = LocalWatchProgress.current(video),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .padding(horizontal = 12.dp),
        )
        Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp)) {
            ChannelAvatar(
                name = video.channelName,
                url = video.channelAvatar,
                size = 36.dp,
                modifier = Modifier
                    .clickable(enabled = onChannelClick != null) { onChannelClick?.invoke() },
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    video.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = YtText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 19.sp,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    video.metaLine(),
                    style = MaterialTheme.typography.bodySmall,
                    color = YtMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onFeedback != null || onDownload != null) {
                CardFeedbackMenu(video, onFeedback, onDownload)
            }
        }
    }
}

@Composable
private fun CardFeedbackMenu(
    video: VideoItem,
    onFeedback: ((FeedFeedback) -> Unit)?,
    onDownload: (() -> Unit)?,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { open = true },
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = "More options",
                tint = YtMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (onDownload != null) {
                DropdownMenuItem(
                    text = { Text("Download") },
                    leadingIcon = { Icon(Icons.Outlined.Download, null, tint = YtText) },
                    onClick = {
                        open = false
                        onDownload()
                    },
                )
            }
            if (onFeedback != null) {
                DropdownMenuItem(
                    text = { Text("Interested") },
                    leadingIcon = { Icon(Icons.Outlined.ThumbUp, null, tint = YtText) },
                    onClick = {
                        open = false
                        onFeedback(FeedFeedback.INTERESTED)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Not interested") },
                    leadingIcon = { Icon(Icons.Outlined.Block, null, tint = YtText) },
                    onClick = {
                        open = false
                        onFeedback(FeedFeedback.NOT_INTERESTED)
                    },
                )
                if (video.channelId != null) {
                    DropdownMenuItem(
                        text = { Text("Don't recommend channel") },
                        leadingIcon = { Icon(Icons.Outlined.VisibilityOff, null, tint = YtText) },
                        onClick = {
                            open = false
                            onFeedback(FeedFeedback.DONT_RECOMMEND_CHANNEL)
                        },
                    )
                }
            }
        }
    }
}

// Shimmering placeholders that mirror VideoCard: rounded 16:9 thumbnail,
// avatar and the two title lines plus meta. One shared alpha keeps the whole
// skeleton in sync and the layout calm while the network catches up. With
// showChips it also covers the filter-tag row, so the first paint matches the
// Home layout before the chips arrive; showHeader adds the section title bone
// used by pages with a category header above the list.
@Composable
fun SkeletonFeed(
    modifier: Modifier = Modifier,
    rows: Int = 4,
    showChips: Boolean = false,
    showHeader: Boolean = false,
    scrollable: Boolean = false,
) {
    val bone = rememberSkeletonBone()
    // Full-screen loading states use a LazyColumn: unlike a plain Column it
    // measures every card with unbounded height and clips the list, so tall
    // 16:9 placeholders can't collide with the next row or bleed under the
    // bottom bar.
    if (scrollable) {
        LazyColumn(modifier.fillMaxSize()) {
            if (showChips) item(key = "skeleton-chips") { SkeletonChips(bone) }
            if (showHeader) item(key = "skeleton-header") { SkeletonHeader(bone) }
            repeat(rows.coerceAtLeast(1)) { item(key = "skeleton-row-$it") { SkeletonCard(bone) } }
        }
    } else {
        Column(modifier.fillMaxWidth()) {
            if (showChips) SkeletonChips(bone)
            if (showHeader) SkeletonHeader(bone)
            repeat(rows.coerceAtLeast(1)) { SkeletonCard(bone) }
        }
    }
}

@Composable
private fun rememberSkeletonBone(): Color {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val shimmer by transition.animateFloat(
        initialValue = 0.30f,
        targetValue = 0.80f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeletonAlpha",
    )
    return YtSurfaceHi.copy(alpha = shimmer)
}

@Composable
private fun SkeletonChips(bone: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(52.dp, 70.dp, 60.dp, 84.dp).forEach { width ->
            Box(
                Modifier
                    .width(width)
                    .height(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(bone),
            )
        }
    }
}

@Composable
private fun SkeletonHeader(bone: Color) {
    Box(
        Modifier
            .padding(start = 12.dp, top = 12.dp, bottom = 6.dp)
            .width(118.dp)
            .height(20.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(bone),
    )
}

@Composable
private fun SkeletonCard(bone: Color) {
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(bone),
        )
        Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp)) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(bone),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Box(
                    Modifier
                        .fillMaxWidth(0.92f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(bone),
                )
                Spacer(Modifier.height(5.dp))
                Box(
                    Modifier
                        .fillMaxWidth(0.64f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(bone),
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth(0.45f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(bone),
                )
            }
        }
    }
}

// Compact horizontal row: small thumbnail on the left, text on the right.
@Composable
fun VideoRow(
    video: VideoItem,
    onClick: () -> Unit,
    onChannelClick: (() -> Unit)? = null,
    thumbModel: Any? = video.thumbnail,
    trailing: @Composable (() -> Unit)? = null,
    index: Int? = null,
    height: Dp = 94.dp,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) {
            Text(
                "$index",
                style = MaterialTheme.typography.bodySmall,
                color = YtMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(22.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Thumb(
            model = thumbModel,
            badge = video.durationText,
            live = video.isLive,
            corner = 8.dp,
            membersOnly = video.membersOnly,
            progress = LocalWatchProgress.current(video),
            modifier = Modifier
                .width(height * 16f / 9f)
                .height(height),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                video.title,
                style = MaterialTheme.typography.titleSmall,
                color = YtText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(3.dp))
            val meta = if (video.channelName != null && !video.metaLine().startsWith(video.channelName)) {
                video.metaLine()
            } else {
                video.metaLine()
            }
            Text(
                meta,
                style = MaterialTheme.typography.bodySmall,
                color = YtMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(6.dp))
            trailing()
        }
    }
}

// ---- small bits ---------------------------------------------------------------

@Composable
fun LiveBadge() {
    Row(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(YtRed)
            .padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.LiveTv, null, tint = Color.White, modifier = Modifier.size(11.dp))
        Spacer(Modifier.width(3.dp))
        Text("LIVE", style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        color = YtText,
        modifier = modifier.padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = YtRed, strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
    }
}

@Composable
fun EmptyState(
    title: String,
    subtitle: String? = null,
    icon: (@Composable () -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 36.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon?.invoke()
        if (icon != null) Spacer(Modifier.height(16.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = YtText,
            textAlign = TextAlign.Center,
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = YtMuted,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(YtChip)
                    .clickable(onClick = onAction)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge, color = YtText)
            }
        }
    }
}

@Composable
fun ErrorState(message: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 36.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = YtMuted,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(YtChip)
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text("Retry", style = MaterialTheme.typography.labelLarge, color = YtBlue)
            }
        }
    }
}

// ---- watch-page actions ----------------------------------------------------------

@Composable
fun ActionButton(
    icon: @Composable () -> Unit,
    label: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .widthIn(min = 62.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.height(5.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.5.sp),
            color = if (active) YtBlue else YtText,
            maxLines = 1,
        )
    }
}

@Composable
fun SubscribeButton(subscribed: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (subscribed) YtChip else Color(0xFFF1F1F1))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
    ) {
        Text(
            if (subscribed) "Subscribed" else "Subscribe",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = if (subscribed) YtText else Color(0xFF0F0F0F),
        )
    }
}

@Composable
fun FillRowButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(YtChip)
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = YtText)
    }
}
