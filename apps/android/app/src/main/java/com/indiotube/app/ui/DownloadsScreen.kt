package com.indiotube.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indiotube.app.Container
import com.indiotube.app.data.DOWNLOAD_QUALITY_CHOICES
import com.indiotube.app.data.DlStatus
import com.indiotube.app.data.DownloadSettings
import com.indiotube.app.data.DownloadTask
import com.indiotube.app.data.SavedDownload
import com.indiotube.app.data.VideoItem
import com.indiotube.app.player.PlaybackController

@Composable
fun DownloadsScreen(container: Container, ui: UiRefs, player: PlaybackController) {
    val tasks by container.downloads.tasks.collectAsState()
    val items by container.downloads.items.collectAsState()
    val settings by container.downloadPrefs.settings.collectAsState()

    val active = tasks.values.sortedBy { it.video.title.lowercase() }
    val done = items.values.sortedByDescending { it.at }
    val doneVideos = done.map { it.video }
    val doneIds = done.map { it.video.videoId }.toSet()
    val activeCount = active.count { it.status == DlStatus.ACTIVE }
    val resumableCount = active.count { it.status != DlStatus.ACTIVE }
    val usedBytes = done.sumOf { it.bytes } + active.sumOf { it.bytesDone }
    val freeBytes = container.downloads.storageFreeBytes()

    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var settingsOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Set<String>?>(null) }
    var pendingDeleteAll by remember { mutableStateOf(false) }

    fun exitSelection() {
        selecting = false
        selected = emptySet()
    }

    LaunchedEffect(doneIds) {
        selected = selected.intersect(doneIds)
        if (doneIds.isEmpty()) selecting = false
    }

    BackHandler(enabled = selecting) { exitSelection() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 6.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                IconButton(onClick = { exitSelection() }) {
                    Icon(Icons.Filled.Close, "Cancel selection", tint = YtText)
                }
                Text(
                    if (selected.isEmpty()) "Select downloads" else "${selected.size} selected",
                    style = MaterialTheme.typography.titleMedium,
                    color = YtText,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        selected = if (selected.size == doneIds.size) emptySet() else doneIds
                    },
                ) {
                    Text(if (selected.size == doneIds.size) "None" else "All", color = YtBlue)
                }
                TextButton(
                    enabled = selected.isNotEmpty(),
                    onClick = {
                        pendingDeleteAll = false
                        pendingDelete = selected
                    },
                ) {
                    Text("Delete", color = if (selected.isEmpty()) YtMuted else YtRed)
                }
            } else {
                IconButton(onClick = { ui.back() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = YtText)
                }
                Text(
                    "Downloads",
                    style = MaterialTheme.typography.titleMedium,
                    color = YtText,
                    modifier = Modifier.weight(1f),
                )
                if (done.isNotEmpty()) {
                    TextButton(onClick = { selecting = true }) {
                        Text("Select", color = YtBlue)
                    }
                }
                IconButton(onClick = { settingsOpen = true }) {
                    Icon(Icons.Filled.Settings, "Download settings", tint = YtText)
                }
            }
        }

        if (active.isEmpty() && done.isEmpty()) {
            EmptyState(
                title = "No downloads yet",
                subtitle = "Tap Download under any video to save it here - ad-free offline viewing, no account needed.",
                icon = { Icon(Icons.Outlined.Download, null, tint = YtMuted, modifier = Modifier.size(48.dp)) },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    DownloadStorageCard(
                        savedCount = done.size,
                        activeCount = activeCount,
                        usedBytes = usedBytes,
                        freeBytes = freeBytes,
                    )
                }
                if (active.isNotEmpty()) {
                    item {
                        DownloadSectionHeader(
                            title = "Downloading",
                            action = when {
                                activeCount > 0 -> "Pause all"
                                resumableCount > 0 -> "Resume all"
                                else -> null
                            },
                            onAction = {
                                if (activeCount > 0) container.downloads.pauseAll()
                                else container.downloads.resumeAll()
                            },
                        )
                    }
                    items(active, key = { "t-" + it.video.videoId }) { task ->
                        ActiveDownloadRow(container, ui, task)
                    }
                }
                if (done.isNotEmpty()) {
                    item { SectionHeader("Downloaded") }
                    items(done, key = { "d-" + it.video.videoId }) { saved ->
                        SavedDownloadRow(
                            container = container,
                            ui = ui,
                            saved = saved,
                            doneVideos = doneVideos,
                            selecting = selecting,
                            selected = saved.video.videoId in selected,
                            onToggle = {
                                val id = saved.video.videoId
                                selected = if (id in selected) selected - id else selected + id
                            },
                        )
                    }
                }
                item { Spacer(Modifier.height(10.dp)) }
            }
        }
    }

    if (settingsOpen) {
        DownloadSettingsSheet(
            settings = settings,
            savedCount = done.size,
            activeCount = activeCount,
            usedBytes = usedBytes,
            freeBytes = freeBytes,
            hasDownloads = active.isNotEmpty() || done.isNotEmpty(),
            onDefaultHeight = container.downloadPrefs::setDefaultHeight,
            onWifiOnly = container.downloadPrefs::setWifiOnly,
            onDeleteAll = {
                settingsOpen = false
                pendingDeleteAll = true
                pendingDelete = doneIds
            },
            onDismiss = { settingsOpen = false },
        )
    }

    val deleteIds = pendingDelete
    if (deleteIds != null) {
        val count = deleteIds.size
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = YtSurface,
            title = {
                Text(
                    if (pendingDeleteAll) "Delete all downloads?"
                    else "Delete $count download${if (count == 1) "" else "s"}?",
                )
            },
            text = {
                Text(
                    if (pendingDeleteAll && active.isNotEmpty()) {
                        "Saved and in-progress downloads will be removed from this device."
                    } else {
                        "Downloaded files will be removed from this device."
                    },
                    color = YtMuted,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (pendingDeleteAll) {
                            container.downloads.clearAll()
                            ui.toast("All downloads removed")
                        } else {
                            deleteIds.forEach(container.downloads::delete)
                            ui.toast(if (count == 1) "Download removed" else "$count downloads removed")
                        }
                        pendingDelete = null
                        pendingDeleteAll = false
                        exitSelection()
                    },
                ) {
                    Text("Delete", color = YtRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel", color = YtMuted) }
            },
        )
    }
}

@Composable
private fun SavedDownloadRow(
    container: Container,
    ui: UiRefs,
    saved: SavedDownload,
    doneVideos: List<VideoItem>,
    selecting: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    VideoRow(
        saved.video,
        onClick = {
            if (selecting) onToggle() else ui.openWatch(saved.video, doneVideos)
        },
        thumbModel = container.downloads.thumb(saved.video.videoId) ?: saved.video.thumbnail,
        trailing = {
            if (selecting) {
                Checkbox(checked = selected, onCheckedChange = { onToggle() })
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.End) {
                        saved.height?.let {
                            Text(
                                "${it}p",
                                style = MaterialTheme.typography.labelSmall,
                                color = YtMuted,
                            )
                        }
                        Text(
                            formatBytes(saved.bytes),
                            fontSize = 11.sp,
                            color = YtMuted,
                        )
                    }
                    IconButton(onClick = {
                        container.downloads.delete(saved.video.videoId)
                        ui.toast("Download removed")
                    }) {
                        Icon(
                            Icons.Filled.Delete,
                            "Delete download",
                            tint = YtMuted,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun DownloadStorageCard(
    savedCount: Int,
    activeCount: Int,
    usedBytes: Long,
    freeBytes: Long,
) {
    val total = usedBytes + freeBytes
    val fraction = if (total > 0) (usedBytes.toFloat() / total).coerceIn(0f, 1f) else 0f
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(YtSurface)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Downloads storage",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = YtText,
                )
                Text(
                    when {
                        savedCount == 0 && activeCount > 0 -> "$activeCount downloading"
                        savedCount == 1 && activeCount > 0 -> "1 download - $activeCount downloading"
                        savedCount == 1 -> "1 download"
                        activeCount > 0 -> "$savedCount downloads - $activeCount downloading"
                        else -> "$savedCount downloads"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = YtMuted,
                )
            }
            Text(
                formatBytes(usedBytes),
                style = MaterialTheme.typography.titleSmall,
                color = YtText,
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(YtSurfaceHi),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(YtRed),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "${formatBytes(freeBytes)} free",
            style = MaterialTheme.typography.bodySmall,
            color = YtMuted,
        )
    }
}

@Composable
private fun DownloadSectionHeader(
    title: String,
    action: String?,
    onAction: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionHeader(title, Modifier.weight(1f))
        if (action != null) {
            TextButton(onClick = onAction) {
                Text(action, color = YtBlue, fontSize = 13.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadSettingsSheet(
    settings: DownloadSettings,
    savedCount: Int,
    activeCount: Int,
    usedBytes: Long,
    freeBytes: Long,
    hasDownloads: Boolean,
    onDefaultHeight: (Int?) -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onDeleteAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = YtSurface,
        contentColor = YtText,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 20.dp),
        ) {
            Text(
                "Download settings",
                style = MaterialTheme.typography.titleMedium,
                color = YtText,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Storage", style = MaterialTheme.typography.bodyLarge, color = YtText)
                    Text(
                        (when {
                            savedCount == 0 && activeCount > 0 -> "$activeCount downloading"
                            activeCount > 0 -> "$savedCount saved - $activeCount downloading"
                            savedCount == 1 -> "1 download"
                            else -> "$savedCount downloads"
                        }) + " - ${formatBytes(usedBytes)} used",
                        style = MaterialTheme.typography.bodySmall,
                        color = YtMuted,
                    )
                }
                Text(
                    "${formatBytes(freeBytes)} free",
                    style = MaterialTheme.typography.bodySmall,
                    color = YtMuted,
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .height(1.dp)
                    .background(Color(0x1FFFFFFF)),
            )
            Text(
                "Default quality",
                style = MaterialTheme.typography.bodyLarge,
                color = YtText,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            Text(
                "Preselected when you tap Download. Auto picks the best single file.",
                style = MaterialTheme.typography.bodySmall,
                color = YtMuted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
            DOWNLOAD_QUALITY_CHOICES.forEach { height ->
                SettingsChoiceRow(
                    label = height?.let { "${it}p" } ?: "Auto",
                    subtitle = if (height == null) "Best available single file" else null,
                    selected = settings.defaultHeight == height,
                    onClick = { onDefaultHeight(height) },
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .height(1.dp)
                    .background(Color(0x1FFFFFFF)),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onWifiOnly(!settings.wifiOnly) }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Download over Wi-Fi only",
                        style = MaterialTheme.typography.bodyLarge,
                        color = YtText,
                    )
                    Text(
                        "New downloads require Wi-Fi.",
                        style = MaterialTheme.typography.bodySmall,
                        color = YtMuted,
                    )
                }
                Switch(checked = settings.wifiOnly, onCheckedChange = onWifiOnly)
            }
            if (hasDownloads) {
                TextButton(
                    onClick = onDeleteAll,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text("Delete all downloads", color = YtRed)
                }
            }
        }
    }
}

@Composable
private fun SettingsChoiceRow(
    label: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) YtSurfaceHi else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) YtBlue else YtText,
                fontWeight = if (selected) FontWeight.SemiBold else null,
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = YtMuted)
            }
        }
        if (selected) {
            Icon(Icons.Filled.Check, "Selected", tint = YtBlue, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun ActiveDownloadRow(container: Container, ui: UiRefs, task: DownloadTask) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(
            model = task.video.thumbnail,
            badge = task.height?.let { "${it}p" },
            corner = 8.dp,
            modifier = Modifier.width(120.dp).height(68.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                task.video.title,
                style = MaterialTheme.typography.titleSmall,
                color = YtText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            when (task.status) {
                DlStatus.ACTIVE -> {
                    if (task.indeterminate) {
                        LinearProgressIndicator(
                            color = YtRed,
                            trackColor = YtSurfaceHi,
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { task.progress },
                            color = YtRed,
                            trackColor = YtSurfaceHi,
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        if (task.indeterminate) "Downloading..." else {
                            val pct = (task.progress * 100).toInt()
                            if (task.totalBytes > 0) "$pct% of ${formatBytes(task.totalBytes)}" else "$pct%"
                        },
                        fontSize = 12.sp,
                        color = YtMuted,
                    )
                }
                DlStatus.PAUSED -> {
                    Text(
                        "Paused${if (task.bytesDone > 0) " - ${formatBytes(task.bytesDone)} saved" else ""}",
                        fontSize = 12.sp,
                        color = YtMuted,
                    )
                }
                DlStatus.FAILED -> {
                    Text(
                        task.error ?: "Download failed",
                        fontSize = 12.sp,
                        color = Color(0xFFFF8A8A),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (task.status) {
                    DlStatus.ACTIVE -> {
                        IconButton(onClick = { container.downloads.pause(task.video.videoId) }) {
                            Icon(Icons.Filled.Pause, "Pause download", tint = YtText, modifier = Modifier.size(20.dp))
                        }
                    }
                    DlStatus.PAUSED -> {
                        IconButton(onClick = { container.downloads.resume(task.video.videoId) }) {
                            Icon(Icons.Filled.PlayArrow, "Resume download", tint = YtText, modifier = Modifier.size(22.dp))
                        }
                    }
                    DlStatus.FAILED -> {
                        IconButton(onClick = { container.downloads.resume(task.video.videoId) }) {
                            Icon(Icons.Filled.Refresh, "Retry download", tint = YtText, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                IconButton(onClick = {
                    container.downloads.cancel(task.video.videoId)
                    ui.toast("Download cancelled")
                }) {
                    Icon(Icons.Filled.Close, "Cancel download", tint = YtMuted, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val mb = bytes / (1024.0 * 1024.0)
    return when {
        mb >= 1024 -> "%.2f GB".format(mb / 1024.0)
        mb >= 10 -> "%.0f MB".format(mb)
        mb >= 1 -> "%.1f MB".format(mb)
        else -> "%.0f KB".format(bytes / 1024.0)
    }
}
