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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.indiotube.app.Container

@Composable
fun ManageSubsScreen(container: Container, ui: UiRefs) {
    val subs by container.accounts.subscriptions.collectAsState()

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
            Column(Modifier.weight(1f)) {
                Text("Manage subscriptions", style = MaterialTheme.typography.titleMedium, color = YtText, maxLines = 1)
                Text("${subs.size} channels", style = MaterialTheme.typography.bodySmall, color = YtMuted)
            }
        }

        if (subs.isEmpty()) {
            EmptyState(
                title = "No subscriptions",
                subtitle = "Subscribe to channels and they'll show up in your Subscriptions feed.",
                icon = { Icon(Icons.Outlined.Subscriptions, null, tint = YtMuted, modifier = Modifier.padding(0.dp)) },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(subs, key = { it.channelId }) { sub ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            Modifier
                                .weight(1f)
                                .clickable { ui.openChannel(sub.channelId, sub.name, sub.avatar) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ChannelAvatar(sub.name, sub.avatar, 42.dp)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    sub.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = YtText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text("Subscribed", style = MaterialTheme.typography.bodySmall, color = YtMuted)
                            }
                        }
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .background(YtChip)
                                .clickable {
                                    container.accounts.unsubscribe(sub.channelId)
                                    ui.toast("Unsubscribed from ${sub.name}")
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text("Unsubscribe", style = MaterialTheme.typography.labelMedium, color = YtText)
                        }
                    }
                }
            }
        }
    }
}
