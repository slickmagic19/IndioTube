package com.indiotube.app.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indiotube.app.Container
import com.indiotube.app.data.DlStatus
import com.indiotube.app.data.DownloadTask
import com.indiotube.app.data.SavedDownload
import com.indiotube.app.player.PlaybackController

@Composable
fun DownloadsScreen(container: Container, ui: UiRefs, player: PlaybackController) {
    val tasks by container.downloads.tasks.collectAsState()
    val items by container.downloads.items.collectAsState()

    val active = tasks.values.sortedBy { it.video.title.lowercase() }
    val done = items.values.sortedByDescending { it.at }
    val doneVideos = done.map { it.video }

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
            Text(
                "Downloads",
                style = MaterialTheme.typography.titleMedium,
                color = YtText,
                modifier = Modifier.weight(1f),
            )
        }

        if (active.isEmpty() && done.isEmpty()) {
            EmptyState(
                title = "No downloads yet",
                subtitle = "Tap Download under any video to save it here — ad-free offline viewing, no account needed.",
                icon = { Icon(Icons.Outlined.Download, null, tint = YtMuted, modifier = Modifier.size(48.dp)) },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                if (active.isNotEmpty()) {
                    item { SectionHeader("Downloading") }
                    items(active, key = { "t-" + it.video.videoId }) { task ->
                        ActiveDownloadRow(container, ui, task)
                    }
                }
                if (done.isNotEmpty()) {
                    item { SectionHeader("Downloaded") }
                    items(done, key = { "d-" + it.video.videoId }) { saved ->
                        VideoRow(
                            saved.video,
                            onClick = { ui.openWatch(saved.video, doneVideos) },
                            thumbModel = container.downloads.thumb(saved.video.videoId) ?: saved.video.thumbnail,
                            trailing = {
                                IconButton(onClick = {
                                    container.downloads.delete(saved.video.videoId)
                                    ui.toast("Download removed")
                                }) {
                                    Icon(Icons.Filled.Delete, "Delete download", tint = YtMuted, modifier = Modifier.size(20.dp))
                                }
                            },
                        )
                    }
                }
                item { Spacer(Modifier.height(10.dp)) }
            }
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
                        if (task.indeterminate) "Downloading…" else {
                            val pct = (task.progress * 100).toInt()
                            if (task.totalBytes > 0) "$pct% of ${formatBytes(task.totalBytes)}" else "$pct%"
                        },
                        fontSize = 12.sp,
                        color = YtMuted,
                    )
                }
                DlStatus.PAUSED -> {
                    Text(
                        "Paused${if (task.bytesDone > 0) " — ${formatBytes(task.bytesDone)} saved" else ""}",
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
