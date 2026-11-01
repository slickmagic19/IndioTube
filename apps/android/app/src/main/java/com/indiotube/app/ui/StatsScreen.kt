package com.indiotube.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indiotube.app.Container
import com.indiotube.app.data.COMPLETED_FRACTION
import com.indiotube.app.data.ChannelWatch
import com.indiotube.app.data.WatchStats
import com.indiotube.app.data.averageActiveDayMs
import com.indiotube.app.data.dayInitial
import com.indiotube.app.data.formatWatchTime
import com.indiotube.app.data.lastDays
import com.indiotube.app.data.topChannels
import com.indiotube.app.data.todayMs
import com.indiotube.app.data.watchStreak
import com.indiotube.app.data.weekMs
import com.indiotube.app.data.watchedFraction
import java.time.LocalDate

// The statistics page. Everything here is computed locally from the watch-time
// ledger and the library, so it works offline and no Google account is needed.
@Composable
fun StatsScreen(container: Container, ui: UiRefs) {
    val stats by container.stats.stats.collectAsState()
    val history by container.library.history.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    val now = remember(stats) { System.currentTimeMillis() }

    val week = remember(stats, now) { lastDays(stats, 7, now) }
    val today = remember(stats, now) { todayMs(stats, now) }
    val thisWeek = remember(stats, now) { weekMs(stats, now) }
    val streak = remember(stats, now) { watchStreak(stats, now) }
    val completed = remember(history) {
        history.count { (it.watchedFraction() ?: 0f) >= COMPLETED_FRACTION }
    }
    val channels = remember(stats) { topChannels(stats, 5) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { ui.back() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = YtText)
            }
            Text(
                "Your activity",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = YtText,
                modifier = Modifier.weight(1f),
            )
        }

        LazyColumn(Modifier.fillMaxSize()) {
            if (stats.totalMs <= 0) {
                item {
                    EmptyState(
                        title = "No watch time yet",
                        subtitle = "Watch something and your watch time, streak and top channels will show up here.",
                        icon = {
                            Icon(
                                Icons.Outlined.History,
                                null,
                                tint = YtMuted,
                                modifier = Modifier.size(42.dp),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                item { TotalCard(stats) }
                item {
                    RowOfTiles(
                        listOf(
                            "Today" to formatWatchTime(today),
                            "This week" to formatWatchTime(thisWeek),
                        ),
                    )
                }
                item {
                    RowOfTiles(
                        listOf(
                            "Day streak" to if (streak > 0) "$streak" else "0",
                            "Active days" to "${stats.activeDays}",
                        ),
                    )
                }
                item {
                    RowOfTiles(
                        listOf(
                            "Avg / active day" to formatWatchTime(averageActiveDayMs(stats)),
                            "Longest session" to formatWatchTime(stats.longestSessionMs),
                        ),
                    )
                }
                item {
                    RowOfTiles(
                        listOf(
                            "Videos watched" to "${history.size}",
                            "Finished" to "$completed",
                        ),
                    )
                }
                if (stats.savedMs > 0) {
                    item { SpeedSavedCard(stats.savedMs) }
                }

                item { SectionHeader("Last 7 days") }
                item { WeekChart(week, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }

                if (channels.isNotEmpty()) {
                    item { SectionHeader("Most watched channels") }
                    items(channels.size) { index ->
                        ChannelRow(channels[index], channels.first().ms)
                    }
                }
            }

            item { SectionHeader("History") }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.FastForward, null, tint = YtText, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Playback time", style = MaterialTheme.typography.bodyLarge, color = YtText)
                        Text(
                            "${stats.sessions} session${if (stats.sessions == 1) "" else "s"} recorded",
                            style = MaterialTheme.typography.bodySmall,
                            color = YtMuted,
                        )
                    }
                }
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Delete, null, tint = YtText, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(18.dp))
                    TextButton(onClick = { confirmClear = true }) {
                        Text("Clear statistics", color = YtRed)
                    }
                }
            }
            item {
                Text(
                    "Watch time is measured on this device only while a video is actually playing. " +
                        "Nothing is uploaded anywhere.",
                    color = YtMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }

    if (confirmClear) {
        AlertDialog(
            modifier = tabletDialogWidth(),
            onDismissRequest = { confirmClear = false },
            containerColor = YtSurface,
            titleContentColor = YtText,
            textContentColor = YtMuted,
            title = { Text("Clear statistics?") },
            text = { Text("This removes the watch-time ledger for this account. Watch history is kept.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        container.stats.clear()
                        confirmClear = false
                        ui.toast("Statistics cleared")
                    },
                ) { Text("Clear", color = YtRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel", color = YtMuted) }
            },
        )
    }
}

@Composable
private fun TotalCard(stats: WatchStats) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(YtSurface)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Text(
            "Total watch time",
            style = MaterialTheme.typography.labelLarge,
            color = YtMuted,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            formatWatchTime(stats.totalMs),
            color = YtText,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
        )
        stats.firstDay?.let { day ->
            Spacer(Modifier.height(2.dp))
            Text(
                "Tracking since ${prettyDay(day)}",
                style = MaterialTheme.typography.bodySmall,
                color = YtMuted,
            )
        }
    }
}

@Composable
private fun SpeedSavedCard(savedMs: Long) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(YtSurface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.FastForward, null, tint = YtBlue, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${formatWatchTime(savedMs)} saved with playback speed",
                style = MaterialTheme.typography.bodyLarge,
                color = YtText,
            )
            Text(
                "Time you did not spend watching at 1x",
                style = MaterialTheme.typography.bodySmall,
                color = YtMuted,
            )
        }
    }
}

@Composable
private fun RowOfTiles(tiles: List<Pair<String, String>>) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tiles.forEach { (label, value) ->
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(YtSurface)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                Text(value, color = YtText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(label, color = YtMuted, fontSize = 11.sp, lineHeight = 14.sp)
            }
        }
    }
}

// Seven columns, one per day, scaled to the busiest day of the week so the
// shape of the week is readable at a glance.
@Composable
private fun WeekChart(days: List<Pair<String, Long>>, modifier: Modifier = Modifier) {
    val max = days.maxOfOrNull { it.second }?.coerceAtLeast(1L) ?: 1L
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        days.forEach { (day, ms) ->
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(92.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    val fraction = (ms.toFloat() / max).coerceIn(0f, 1f)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height((92f * fraction).dp.coerceAtLeast(3.dp))
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (ms > 0) YtRed else YtSurfaceHi),
                    )
                }
                Spacer(Modifier.height(5.dp))
                Text(dayInitial(day), color = YtMuted, fontSize = 10.sp)
                Text(
                    if (ms > 0) formatWatchTime(ms) else "",
                    color = YtMuted,
                    fontSize = 9.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ChannelRow(channel: ChannelWatch, topMs: Long) {
    val ratio = if (topMs <= 0) 0f else (channel.ms.toFloat() / topMs).coerceIn(0f, 1f)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelAvatar(name = channel.name, url = null, size = 28.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                channel.name.ifBlank { "Unknown channel" },
                color = YtText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            Spacer(Modifier.height(5.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(YtSurfaceHi),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(ratio)
                        .fillMaxSize()
                        .background(YtRed),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(formatWatchTime(channel.ms), color = YtMuted, fontSize = 11.sp)
    }
}

// "2026-11-01" -> "1 Nov 2026" without a locale-dependent formatter, so the
// page reads the same on every device.
private fun prettyDay(day: String): String = runCatching {
    val date = LocalDate.parse(day)
    val month = date.month.name.lowercase().replaceFirstChar { it.uppercase() }
    "${date.dayOfMonth} $month ${date.year}"
}.getOrDefault(day)
