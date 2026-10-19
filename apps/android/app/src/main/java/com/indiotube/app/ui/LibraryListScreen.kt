package com.indiotube.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.indiotube.app.Container
import com.indiotube.app.data.VideoItem

@Composable
fun LibraryListScreen(container: Container, ui: UiRefs, kind: LibKind) {
    val history by container.library.history.collectAsState()
    val watchLater by container.library.watchLater.collectAsState()
    val liked by container.library.liked.collectAsState()

    val videos: List<VideoItem> = when (kind) {
        LibKind.HISTORY -> history.map { it.video }
        LibKind.WATCH_LATER -> watchLater
        LibKind.LIKED -> liked
    }

    val title = when (kind) {
        LibKind.HISTORY -> "Watch history"
        LibKind.WATCH_LATER -> "Watch later"
        LibKind.LIKED -> "Liked videos"
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { ui.back() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = YtText)
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = YtText, maxLines = 1)
                Text(
                    "${videos.size} videos",
                    style = MaterialTheme.typography.bodySmall,
                    color = YtMuted,
                )
            }
            if (videos.isNotEmpty()) {
                IconButton(onClick = { ui.play(videos, 0) }) {
                    Icon(Icons.Filled.PlayArrow, "Play all", tint = YtText)
                }
            }
        }

        if (videos.isEmpty()) {
            val (t, s) = when (kind) {
                LibKind.HISTORY -> "No watch history" to "Videos you watch show up here."
                LibKind.WATCH_LATER -> "Watch later is empty" to "Tap Save under any video to add it here."
                LibKind.LIKED -> "No liked videos" to "Videos you like show up here."
            }
            EmptyState(title = t, subtitle = s, modifier = Modifier.fillMaxSize())
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(videos, key = { it.videoId }) { v ->
                    VideoRow(
                        v,
                        onClick = { ui.openWatch(v, videos) },
                        onChannelClick = { ui.openChannelOf(v) },
                        trailing = {
                            IconButton(onClick = {
                                when (kind) {
                                    LibKind.HISTORY -> container.library.removeFromHistory(v.videoId)
                                    LibKind.WATCH_LATER -> {
                                        container.library.toggleWatchLater(v)
                                        ui.toast("Removed from Watch later")
                                    }
                                    LibKind.LIKED -> {
                                        container.library.toggleLike(v)
                                        ui.toast("Removed from Liked videos")
                                    }
                                }
                            }) {
                                Icon(Icons.Filled.Close, "Remove", tint = YtMuted, modifier = Modifier.size(18.dp))
                            }
                        },
                    )
                }
                item { Spacer(Modifier.height(10.dp)) }
            }
        }
    }
}
