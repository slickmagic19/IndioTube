package com.indiotube.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.indiotube.app.Container

@Composable
fun LocalPlaylistScreen(container: Container, ui: UiRefs, id: String) {
    val playlists by container.library.playlists.collectAsState()
    val playlist = playlists.firstOrNull { it.id == id }
    val videos = playlist?.videos.orEmpty()

    var renameOpen by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var deleteOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 6.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { ui.back() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = YtText)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    playlist?.name ?: "Playlist",
                    style = MaterialTheme.typography.titleMedium,
                    color = YtText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${videos.size} videos",
                    style = MaterialTheme.typography.bodySmall,
                    color = YtMuted,
                )
            }
            IconButton(onClick = {
                renameText = playlist?.name.orEmpty()
                renameOpen = true
            }) {
                Icon(Icons.Filled.Edit, "Rename playlist", tint = YtText, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = { deleteOpen = true }) {
                Icon(Icons.Filled.Delete, "Delete playlist", tint = YtText, modifier = Modifier.size(20.dp))
            }
        }

        if (videos.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
                PlayPill("Play all", Icons.Filled.PlayArrow, Modifier.weight(1f)) {
                    ui.play(videos, 0)
                }
                Spacer(Modifier.width(10.dp))
                PlayPill("Shuffle", Icons.Filled.Shuffle, Modifier.weight(1f)) {
                    ui.play(videos, 0, shuffle = true)
                }
            }
        }

        if (videos.isEmpty()) {
            EmptyState(
                title = "This playlist is empty",
                subtitle = "Use Save on any video to add it to ${playlist?.name ?: "this playlist"}.",
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(videos.size, key = { videos[it].videoId }) { i ->
                    val v = videos[i]
                    VideoRow(
                        v,
                        index = i + 1,
                        onClick = { ui.openWatch(v, videos) },
                        onChannelClick = { ui.openChannelOf(v) },
                        trailing = {
                            IconButton(onClick = {
                                container.library.removeFromPlaylist(id, v.videoId)
                                ui.toast("Removed from playlist")
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

    if (renameOpen) {
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            containerColor = YtSurface,
            titleContentColor = YtText,
            title = { Text("Rename playlist") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameText.isNotBlank(),
                    onClick = {
                        container.library.renamePlaylist(id, renameText)
                        renameOpen = false
                    },
                ) {
                    Text("Rename", color = if (renameText.isNotBlank()) YtBlue else YtMuted)
                }
            },
            dismissButton = {
                TextButton(onClick = { renameOpen = false }) { Text("Cancel", color = YtMuted) }
            },
        )
    }

    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            containerColor = YtSurface,
            titleContentColor = YtText,
            title = { Text("Delete playlist?") },
            text = { Text("\u201C${playlist?.name ?: "Playlist"}\u201D will be removed from your library.", color = YtMuted) },
            confirmButton = {
                TextButton(onClick = {
                    container.library.deletePlaylist(id)
                    deleteOpen = false
                    ui.toast("Playlist deleted")
                    ui.back()
                }) {
                    Text("Delete", color = Color(0xFFFF6B6B))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteOpen = false }) { Text("Cancel", color = YtMuted) }
            },
        )
    }
}

@Composable
fun PlayPill(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .height(38.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(YtChip)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = YtText, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = YtText)
    }
}
