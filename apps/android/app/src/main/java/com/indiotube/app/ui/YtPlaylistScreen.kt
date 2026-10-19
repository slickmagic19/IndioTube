package com.indiotube.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.indiotube.app.Container
import com.indiotube.app.data.PlaylistPage
import kotlinx.coroutines.launch

@Composable
fun YtPlaylistScreen(container: Container, ui: UiRefs, playlistId: String, title: String?) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var page by remember { mutableStateOf<PlaylistPage?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(playlistId) {
        loading = true
        try {
            page = container.innerTube.playlist(playlistId)
            error = null
        } catch (e: Exception) {
            error = e.message ?: "Couldn't load this playlist"
        }
        loading = false
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                val p = page ?: return@collect
                val token = p.continuation ?: return@collect
                if (last >= p.videos.size - 3 && !loadingMore && last >= 0) {
                    loadingMore = true
                    try {
                        val more = container.innerTube.playlistContinuation(token)
                        page = PlaylistPage(p.title, p.author, p.videoCountText, p.videos + more.videos, more.continuation)
                    } catch (_: Exception) { /* keep what we have */ }
                    loadingMore = false
                }
            }
    }

    val videos = page?.videos.orEmpty()
    val dispTitle = page?.title?.takeIf { it.isNotBlank() } ?: title ?: "Playlist"
    val subLine = listOfNotNull(
        page?.author,
        page?.videoCountText ?: if (videos.isNotEmpty()) "${videos.size} videos" else null,
    ).joinToString(" • ")

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 10.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { ui.back() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = YtText)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    dispTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = YtText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subLine.isNotBlank()) {
                    Text(subLine, style = MaterialTheme.typography.bodySmall, color = YtMuted, maxLines = 1)
                }
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

        when {
            loading && page == null -> LoadingBox(Modifier.fillMaxSize())
            error != null && page == null -> ErrorState(error!!, onRetry = {
                scope.launch {
                    loading = true
                    try {
                        page = container.innerTube.playlist(playlistId)
                        error = null
                    } catch (e: Exception) {
                        error = e.message
                    }
                    loading = false
                }
            }, modifier = Modifier.fillMaxSize())
            videos.isEmpty() -> EmptyState(
                title = "Nothing here",
                subtitle = "This playlist doesn't have any playable videos.",
                modifier = Modifier.fillMaxSize(),
            )
            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(videos.size, key = { videos[it].videoId }) { i ->
                    val v = videos[i]
                    VideoRow(
                        v,
                        index = i + 1,
                        onClick = { ui.openWatch(v, videos) },
                        onChannelClick = { ui.openChannelOf(v) },
                    )
                }
                if (loadingMore) {
                    item { LoadingBox(Modifier.height(70.dp)) }
                }
                item { Spacer(Modifier.height(10.dp)) }
            }
        }
    }
}
