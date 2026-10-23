package com.indiotube.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indiotube.app.data.LiveChatMessage

private val OwnerColor = Color(0xFFFFD54F)
private val ModeratorColor = Color(0xFF8AB4F8)
private val MemberColor = Color(0xFF81C995)

// The live conversation under a stream, and the replay of it once the stream
// has finished. YouTube puts chat in a collapsible side panel on phones; here
// it is a card under the title with the same header, newest-message-at-bottom
// behaviour and small badge colours.
@Composable
fun LiveChatSection(
    messages: List<LiveChatMessage>,
    replay: Boolean,
    viewersText: String?,
    expanded: Boolean,
    loading: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberLazyListState()

    // Stick to the newest message the way a chat does, but only while the
    // reader is already at the bottom. Scrolling up through history must not
    // be yanked back by the next message.
    LaunchedEffect(messages.size, expanded) {
        if (!expanded || messages.isEmpty()) return@LaunchedEffect
        val lastVisible = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val nearBottom = lastVisible < 0 || lastVisible >= messages.size - 4
        if (nearBottom || messages.size <= 20) {
            state.animateScrollToItem(messages.lastIndex)
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(YtSurfaceHi),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!replay) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(YtRed),
                )
                Spacer(Modifier.width(7.dp))
            }
            Text(
                if (replay) "Chat replay" else "Live chat",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = YtText,
            )
            if (!viewersText.isNullOrBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    viewersText,
                    style = MaterialTheme.typography.bodySmall,
                    color = YtMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse chat" else "Expand chat",
                tint = YtMuted,
                modifier = Modifier.size(20.dp),
            )
        }

        if (expanded) {
            when {
                loading && messages.isEmpty() -> {
                    Column(Modifier.fillMaxWidth().height(74.dp).padding(horizontal = 12.dp)) {
                        Spacer(Modifier.height(10.dp))
                        Text("Connecting to chat…", style = MaterialTheme.typography.bodySmall, color = YtMuted)
                    }
                }
                messages.isEmpty() -> {
                    Box(
                        Modifier.fillMaxWidth().height(64.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (replay) "Chat replay isn't available for this stream." else "Chat is quiet right now.",
                            style = MaterialTheme.typography.bodySmall,
                            color = YtMuted,
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        state = state,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(230.dp)
                            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        items(messages, key = { it.id }) { message ->
                            LiveChatRow(message)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveChatRow(message: LiveChatMessage) {
    Row(Modifier.fillMaxWidth()) {
        ChannelAvatar(message.author, message.avatar, 18.dp)
        Spacer(Modifier.width(7.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                message.badge?.let { badge ->
                    Text(
                        badge,
                        fontSize = 9.sp,
                        lineHeight = 10.sp,
                        color = Color(0xFF0F0F0F),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                when (badge) {
                                    "Owner" -> OwnerColor
                                    "Moderator" -> ModeratorColor
                                    else -> MemberColor
                                },
                            )
                            .padding(horizontal = 3.dp, vertical = 1.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    message.author,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = when (message.badge) {
                        "Owner" -> OwnerColor
                        "Moderator" -> ModeratorColor
                        "Member" -> MemberColor
                        else -> YtMuted
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                message.timestampText?.let { ts ->
                    Spacer(Modifier.width(5.dp))
                    Text(ts, fontSize = 10.sp, color = YtMuted.copy(alpha = 0.75f), maxLines = 1)
                }
            }
            Spacer(Modifier.height(1.dp))
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    message.text,
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp,
                    color = YtText,
                    modifier = Modifier.weight(1f, fill = false),
                )
                message.amountText?.let { amount ->
                    Spacer(Modifier.width(6.dp))
                    Text(
                        amount,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OwnerColor,
                    )
                }
            }
        }
    }
}
