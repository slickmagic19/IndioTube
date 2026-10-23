package com.indiotube.app.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.indiotube.app.Container
import com.indiotube.app.data.VideoItem

@Composable
fun LibraryListScreen(container: Container, ui: UiRefs, kind: LibKind) {
    val history by container.library.history.collectAsState()
    val historyPaused by container.library.historyPaused.collectAsState()
    val watchLater by container.library.watchLater.collectAsState()
    val liked by container.library.liked.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }

    // Batch selection: long-press a row (or tap Select) to start checking
    // videos, then remove them all at once. YouTube's own library screens use
    // the same pattern, and it beats removing twenty rows one Close at a time.
    var selecting by remember(kind) { mutableStateOf(false) }
    var selected by remember(kind) { mutableStateOf<Set<String>>(emptySet()) }

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

    fun toggleSelection(videoId: String) {
        selected = if (videoId in selected) selected - videoId else selected + videoId
    }

    fun removeSelected() {
        val ids = selected
        if (ids.isEmpty()) return
        when (kind) {
            LibKind.HISTORY -> container.library.removeFromHistory(ids)
            LibKind.WATCH_LATER -> container.library.removeFromWatchLater(ids)
            LibKind.LIKED -> container.library.removeLiked(ids)
        }
        ui.toast("${ids.size} removed")
        selecting = false
        selected = emptySet()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                if (selecting) {
                    selecting = false
                    selected = emptySet()
                } else {
                    ui.back()
                }
            }) {
                Icon(
                    if (selecting) Icons.Filled.Close else Icons.AutoMirrored.Filled.ArrowBack,
                    if (selecting) "Cancel selection" else "Back",
                    tint = YtText,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (selecting) "${selected.size} selected" else title,
                    style = MaterialTheme.typography.titleMedium,
                    color = YtText,
                    maxLines = 1,
                )
                Text(
                    if (selecting) title else "${videos.size} videos",
                    style = MaterialTheme.typography.bodySmall,
                    color = YtMuted,
                )
            }
            if (selecting) {
                IconButton(enabled = selected.isNotEmpty(), onClick = { removeSelected() }) {
                    Icon(
                        Icons.Outlined.Delete,
                        "Remove selected",
                        tint = if (selected.isEmpty()) YtMuted else YtRed,
                    )
                }
            } else {
                if (videos.isNotEmpty()) {
                    IconButton(onClick = { ui.play(videos, 0) }) {
                        Icon(Icons.Filled.PlayArrow, "Play all", tint = YtText)
                    }
                    TextButton(onClick = { selecting = true }) {
                        Text("Select", color = YtText)
                    }
                }
            }
        }

        if (kind == LibKind.HISTORY) {
            HorizontalDivider(color = YtDivider)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { container.library.setHistoryPaused(!historyPaused) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (historyPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    null,
                    tint = YtText,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Pause watch history",
                        style = MaterialTheme.typography.bodyLarge,
                        color = YtText,
                    )
                    Text(
                        if (historyPaused) {
                            "History is paused. Videos you watch won't be saved."
                        } else {
                            "Stop saving videos you watch to this account."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = YtMuted,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Switch(
                    checked = historyPaused,
                    onCheckedChange = { container.library.setHistoryPaused(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                        checkedTrackColor = YtRed,
                        uncheckedThumbColor = YtMuted,
                        uncheckedTrackColor = YtSurfaceHi,
                        uncheckedBorderColor = YtDivider,
                    ),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = videos.isNotEmpty()) { confirmClear = true }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Delete,
                    null,
                    tint = if (videos.isEmpty()) YtMuted else YtText,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Clear all watch history",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (videos.isEmpty()) YtMuted else YtText,
                    )
                    Text(
                        "Remove every video saved in this account's history.",
                        style = MaterialTheme.typography.bodySmall,
                        color = YtMuted,
                    )
                }
            }
            HorizontalDivider(color = YtDivider)
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
                    // The trailing remove button belongs to single-item mode;
                    // while selecting, the checkbox carries the action.
                    val trailing: (@Composable () -> Unit)? = if (selecting) {
                        null
                    } else {
                        {
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
                        }
                    }
                    VideoRow(
                        v,
                        onClick = {
                            if (selecting) toggleSelection(v.videoId) else ui.openWatch(v, videos)
                        },
                        onChannelClick = if (selecting) null else {
                            { ui.openChannelOf(v) }
                        },
                        onLongClick = {
                            if (!selecting) {
                                selecting = true
                                selected = setOf(v.videoId)
                            }
                        },
                        selecting = selecting,
                        selected = v.videoId in selected,
                        trailing = trailing,
                    )
                }
                item { Spacer(Modifier.height(10.dp)) }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = YtSurface,
            titleContentColor = YtText,
            textContentColor = YtMuted,
            title = { Text("Clear watch history?") },
            text = {
                Text("This removes all videos from this account's history and can't be undone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    container.library.clearHistory()
                    confirmClear = false
                    ui.toast("Watch history cleared")
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
