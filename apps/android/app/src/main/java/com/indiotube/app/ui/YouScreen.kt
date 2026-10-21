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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.indiotube.app.Container
import com.indiotube.app.ui.Screen.LibraryList

@Composable
fun YouScreen(container: Container, ui: UiRefs) {
    val account by container.accounts.active.collectAsState()
    val allAccounts by container.accounts.accounts.collectAsState()
    val history by container.library.history.collectAsState()
    val watchLater by container.library.watchLater.collectAsState()
    val playlists by container.library.playlists.collectAsState()
    val liked by container.library.liked.collectAsState()
    val downloads by container.downloads.items.collectAsState()
    val downloadTasks by container.downloads.tasks.collectAsState()

    var newPlaylistOpen by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 10.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "You",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = YtText,
                modifier = Modifier.weight(1f),
            )
            ChannelAvatar(
                name = account?.name,
                url = null,
                size = 30.dp,
                modifier = Modifier.clickable { ui.openAccounts() },
            )
        }

        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { ui.openAccounts() }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChannelAvatar(account?.name, null, 44.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            account?.name ?: "You",
                            style = MaterialTheme.typography.titleMedium,
                            color = YtText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (allAccounts.size == 1) "Tap to add another account" else "Tap to switch accounts",
                            style = MaterialTheme.typography.bodySmall,
                            color = YtMuted,
                        )
                    }
                }
            }

            item { SectionHeader("Your library") }
            item {
                YouRow(Icons.Outlined.History, "History", "${history.size} videos") {
                    ui.open(LibraryList(LibKind.HISTORY))
                }
            }
            item {
                YouRow(Icons.Outlined.Schedule, "Watch later", "${watchLater.size} videos") {
                    ui.open(LibraryList(LibKind.WATCH_LATER))
                }
            }
            item {
                YouRow(Icons.Outlined.ThumbUp, "Liked videos", "${liked.size} videos") {
                    ui.open(LibraryList(LibKind.LIKED))
                }
            }
            item {
                val subtitle = when {
                    downloadTasks.isNotEmpty() && downloads.isNotEmpty() ->
                        "${downloads.size} saved - ${downloadTasks.size} downloading"
                    downloadTasks.isNotEmpty() -> "${downloadTasks.size} downloading"
                    else -> "${downloads.size} videos"
                }
                YouRow(Icons.Outlined.Download, "Downloads", subtitle) {
                    ui.open(Screen.Downloads)
                }
            }

            item { SectionHeader("Playlists") }
            items(playlists, key = { it.id }) { pl ->
                YouRow(Icons.Outlined.PlaylistPlay, pl.name, "${pl.videos.size} videos") {
                    ui.open(Screen.LocalPlaylist(pl.id))
                }
            }
            item {
                YouRow(Icons.Filled.Add, "New playlist", null) {
                    newPlaylistName = ""
                    newPlaylistOpen = true
                }
            }

            item { SectionHeader("Account") }
            item {
                YouRow(Icons.Outlined.Subscriptions, "Manage subscriptions", null) {
                    ui.open(Screen.ManageSubscriptions)
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }

    if (newPlaylistOpen) {
        AlertDialog(
            onDismissRequest = { newPlaylistOpen = false },
            containerColor = YtSurface,
            titleContentColor = YtText,
            textContentColor = YtMuted,
            title = { Text("New playlist") },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    singleLine = true,
                    placeholder = { Text("Playlist name", color = YtMuted) },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newPlaylistName.isNotBlank(),
                    onClick = {
                        container.library.createPlaylist(newPlaylistName.trim())
                        ui.toast("Playlist created")
                        newPlaylistOpen = false
                    },
                ) {
                    Text("Create", color = if (newPlaylistName.isNotBlank()) YtBlue else YtMuted)
                }
            },
            dismissButton = {
                TextButton(onClick = { newPlaylistOpen = false }) {
                    Text("Cancel", color = YtMuted)
                }
            },
        )
    }
}

@Composable
private fun YouRow(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = YtText, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = YtText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = YtMuted)
            }
        }
    }
}
