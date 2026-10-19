package com.indiotube.app.ui

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

// ---- thumbnails ---------------------------------------------------------------

@Composable
fun Thumb(
    model: Any?,
    modifier: Modifier = Modifier,
    badge: String? = null,
    live: Boolean = false,
    corner: Dp = 12.dp,
) {
    Box(modifier) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(corner))
                .background(YtSurfaceHi),
        ) {
            if (model != null) {
                AsyncImage(
                    model = model,
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
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// ---- video cards ---------------------------------------------------------------

// Full-width feed card: 16:9 thumbnail, then avatar + title + meta.
@Composable
fun VideoCard(
    video: VideoItem,
    onClick: () -> Unit,
    onChannelClick: (() -> Unit)? = null,
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
