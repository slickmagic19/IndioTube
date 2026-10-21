package com.indiotube.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indiotube.app.Container
import com.indiotube.app.data.ChannelItem
import com.indiotube.app.data.InnerTube
import com.indiotube.app.data.PlaylistItem
import com.indiotube.app.data.SearchEntry
import com.indiotube.app.data.SearchFilter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchState {
    var query by mutableStateOf("")
    var filter by mutableStateOf<String?>(null)
    var entries by mutableStateOf<List<SearchEntry>?>(null)
    var continuation by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var suggestMode by mutableStateOf(false)
    var suggestions by mutableStateOf<List<String>>(emptyList())
    var focusedOnce = false
    var scrollIndex = 0
    var scrollOffset = 0

    fun reset() {
        query = ""
        filter = null
        entries = null
        continuation = null
        error = null
        suggestMode = false
        suggestions = emptyList()
        focusedOnce = false
        scrollIndex = 0
        scrollOffset = 0
    }
}

@Composable
fun SearchScreen(container: Container, ui: UiRefs) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = ui.search.scrollIndex,
        initialFirstVisibleItemScrollOffset = ui.search.scrollOffset,
    )
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    var query by rememberSaveable { mutableStateOf(ui.search.query) }
    var filter by rememberSaveable { mutableStateOf(ui.search.filter) }
    var entries by remember { mutableStateOf(ui.search.entries) }
    var continuation by remember { mutableStateOf(ui.search.continuation) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(ui.search.error) }
    var suggestMode by remember { mutableStateOf(ui.search.suggestMode) }
    var suggestions by remember { mutableStateOf(ui.search.suggestions) }

    LaunchedEffect(query, filter, entries, continuation, error, suggestMode, suggestions) {
        ui.search.query = query
        ui.search.filter = filter
        ui.search.entries = entries
        ui.search.continuation = continuation
        ui.search.error = error
        ui.search.suggestMode = suggestMode
        ui.search.suggestions = suggestions
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                ui.search.scrollIndex = index
                ui.search.scrollOffset = offset
            }
    }

    fun runSearch(q: String = query, f: String? = filter) {
        val trimmed = q.trim()
        if (trimmed.isEmpty()) return
        suggestMode = false
        keyboard?.hide()
        query = trimmed
        // Clearing the old page makes every new query or filter switch show
        // the skeleton instead of the previous results.
        loading = true
        error = null
        entries = null
        continuation = null
        scope.launch {
            try {
                val result = container.innerTube.search(trimmed, f)
                entries = result.entries
                continuation = result.continuation
                container.library.noteSearch(trimmed)
                if (ui.recentSearches.firstOrNull() != trimmed) {
                    ui.recentSearches.remove(trimmed)
                    ui.recentSearches.add(0, trimmed)
                    while (ui.recentSearches.size > 12) ui.recentSearches.removeAt(ui.recentSearches.lastIndex)
                }
            } catch (e: Exception) {
                error = e.message ?: "Search failed"
                entries = entries ?: emptyList()
            }
            loading = false
        }
    }

    // type-ahead: debounce the keystrokes, then ask YouTube for completions
    LaunchedEffect(query, suggestMode) {
        if (!suggestMode || query.isBlank()) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        val asked = query
        val result = container.innerTube.suggestions(asked)
        if (asked == query) suggestions = result
    }

    LaunchedEffect(Unit) {
        if (!ui.search.focusedOnce) {
            ui.search.focusedOnce = true
            focusRequester.requestFocus()
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                val list = entries ?: return@collect
                val token = continuation ?: return@collect
                if (last >= list.size - 3 && !loadingMore && last >= 0) {
                    loadingMore = true
                    try {
                        val more = container.innerTube.searchContinuation(token)
                        entries = list + more.entries
                        continuation = more.continuation
                    } catch (_: Exception) { continuation = null }
                    loadingMore = false
                }
            }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 10.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { ui.back() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = YtText)
            }
            Row(
                Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(YtSurfaceHi)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, null, tint = YtMuted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text("Search YouTube", color = YtMuted, fontSize = 15.sp)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = {
                            query = it
                            suggestMode = true
                        },
                        singleLine = true,
                        textStyle = TextStyle(color = YtText, fontSize = 15.sp),
                        cursorBrush = SolidColor(YtBlue),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = ImeAction.Search,
                            capitalization = KeyboardCapitalization.None,
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onSearch = { runSearch() },
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                    )
                }
                if (query.isNotEmpty()) {
                    Icon(
                        Icons.Filled.Close,
                        "Clear",
                        tint = YtMuted,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable {
                                query = ""
                                entries = null
                                error = null
                                suggestions = emptyList()
                            },
                    )
                }
            }
        }

        val inSuggestMode = suggestMode && query.isNotBlank()

        // type filters
        if (query.isNotEmpty() && !inSuggestMode) {
            LazyRow(
                Modifier.padding(top = 2.dp, bottom = 4.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                val filters = listOf(
                    "All" to null,
                    "Videos" to SearchFilter.VIDEOS,
                    "Channels" to SearchFilter.CHANNELS,
                    "Playlists" to SearchFilter.PLAYLISTS,
                )
                items(filters) { (label, value) ->
                    FeedChip(label, filter == value) {
                        filter = value
                        if (query.isNotBlank()) runSearch(f = value)
                    }
                    Spacer(Modifier.width(8.dp))
                }
            }
        }

        when {
            inSuggestMode -> {
                if (suggestions.isNotEmpty()) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 8.dp),
                    ) {
                        items(suggestions) { s ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { runSearch(q = s) }
                                    .padding(horizontal = 16.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.Search, null, tint = YtMuted, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(14.dp))
                                Text(s, color = YtText, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
            loading && entries == null -> SkeletonFeed(Modifier.fillMaxSize(), scrollable = true)
            entries == null -> {
                // recent searches / idle state
                if (ui.recentSearches.isEmpty()) {
                    EmptyState(
                        title = "Search IndioTube",
                        subtitle = "Find videos, channels and playlists — no ads, no Shorts.",
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 8.dp),
                    ) {
                        item { SectionHeader("Recent searches") }
                        items(ui.recentSearches.toList()) { q ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { runSearch(q = q) }
                                    .padding(horizontal = 16.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.History, null, tint = YtMuted, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(14.dp))
                                Text(q, color = YtText, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
            else -> {
                val list = entries.orEmpty()
                if (list.isEmpty() && !loading) {
                    EmptyState(
                        title = "No results",
                        subtitle = "Try a different search term or filter.",
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        // One uniform gap for every entry type: video cards,
                        // channel rows and playlist rows all sit on the same
                        // 14dp rhythm instead of three different paddings.
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(top = 6.dp, bottom = 10.dp),
                    ) {
                        items(list.size + if (loadingMore) 1 else 0) { idx ->
                            if (idx >= list.size) {
                                LoadingBox(Modifier.height(70.dp))
                                return@items
                            }
                            when (val e = list[idx]) {
                                is SearchEntry.Video -> VideoCard(
                                    e.video,
                                    onClick = {
                                        ui.openWatch(e.video, list.filterIsInstance<SearchEntry.Video>().map { it.video })
                                    },
                                    onChannelClick = { ui.openChannelOf(e.video) },
                                    onFeedback = { ui.feedback(e.video, it) },
                                    onDownload = { ui.download(e.video) },
                                    bottomPadding = 0.dp,
                                )
                                is SearchEntry.Channel -> ChannelResultRow(e.channel, ui)
                                is SearchEntry.Playlist -> PlaylistResultRow(e.playlist, ui)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelResultRow(channel: ChannelItem, ui: UiRefs) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { ui.openChannel(channel.channelId, channel.name, channel.avatar) }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelAvatar(channel.name, channel.avatar, 56.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                channel.name,
                style = MaterialTheme.typography.titleSmall,
                color = YtText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(channel.subscribersText, channel.videoCountText).joinToString(" • ")
            if (sub.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(sub, style = MaterialTheme.typography.bodySmall, color = YtMuted, maxLines = 1)
            }
        }
    }
}

@Composable
private fun PlaylistResultRow(playlist: PlaylistItem, ui: UiRefs) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { ui.open(Screen.YtPlaylist(playlist.playlistId, playlist.title)) }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Thumb(
                model = playlist.thumbnail,
                corner = 8.dp,
                modifier = Modifier
                    .width(140.dp)
                    .height(79.dp),
            )
            // playlist badge on top of the thumbnail
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                    .background(YtBadge)
                    .padding(vertical = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    playlist.videoCountText ?: "Playlist",
                    style = MaterialTheme.typography.labelSmall,
                    color = androidx.compose.ui.graphics.Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                playlist.title,
                style = MaterialTheme.typography.titleSmall,
                color = YtText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!playlist.channelName.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(playlist.channelName, style = MaterialTheme.typography.bodySmall, color = YtMuted, maxLines = 1)
            }
        }
    }
}
