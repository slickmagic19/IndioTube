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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indiotube.app.Container
import com.indiotube.app.data.DOWNLOAD_QUALITY_CHOICES
import com.indiotube.app.data.SPONSOR_CATEGORIES

@Composable
fun SettingsScreen(container: Container, ui: UiRefs) {
    val playback by container.playbackPrefs.autoplayNext.collectAsState()
    val defaultQuality by container.playbackPrefs.defaultQualityHeight.collectAsState()
    val downloadSettings by container.downloadPrefs.settings.collectAsState()
    val captionStyle by container.captions.style.collectAsState()
    val sponsor by container.sponsorBlockPrefs.settings.collectAsState()
    val historyPaused by container.library.historyPaused.collectAsState()
    val searches by container.library.searches.collectAsState()
    val downloads by container.downloads.items.collectAsState()
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }

    var qualitySheet by remember { mutableStateOf(false) }
    var downloadQualitySheet by remember { mutableStateOf(false) }
    var captionSheet by remember { mutableStateOf(false) }
    var confirmClearHistory by remember { mutableStateOf(false) }
    var confirmClearSearches by remember { mutableStateOf(false) }

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
                "Settings",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = YtText,
                modifier = Modifier.weight(1f),
            )
        }

        LazyColumn(Modifier.fillMaxSize()) {
            item { SettingsSection("Playback") }
            item {
                SettingsToggleRow(
                    icon = Icons.Outlined.PlayCircle,
                    title = "Autoplay next video",
                    subtitle = "Play the next video in the queue when one ends",
                    checked = playback,
                    onCheckedChange = { container.playbackPrefs.setAutoplayNext(it) },
                )
            }
            item {
                SettingsRow(
                    title = "Default video quality",
                    subtitle = defaultQuality?.let { "${it}p" } ?: "Auto",
                    onClick = { qualitySheet = true },
                )
            }

            item { SettingsSection("Downloads") }
            item {
                SettingsRow(
                    icon = Icons.Outlined.Download,
                    title = "Download quality",
                    subtitle = downloadSettings.defaultHeight?.let { "${it}p" } ?: "Auto",
                    onClick = { downloadQualitySheet = true },
                )
            }
            item {
                SettingsToggleRow(
                    title = "Download over Wi-Fi only",
                    subtitle = "Wait for Wi-Fi before starting downloads",
                    checked = downloadSettings.wifiOnly,
                    onCheckedChange = { container.downloadPrefs.setWifiOnly(it) },
                )
            }
            item {
                SettingsRow(
                    title = "Manage downloads",
                    subtitle = "${downloads.size} saved",
                    onClick = { ui.open(Screen.Downloads) },
                )
            }

            item { SettingsSection("Captions") }
            item {
                SettingsRow(
                    icon = Icons.Outlined.Subtitles,
                    title = "Caption appearance",
                    subtitle = "Text size, colour, background and edge style",
                    onClick = { captionSheet = true },
                )
            }

            item { SettingsSection("SponsorBlock") }
            item {
                SettingsToggleRow(
                    icon = Icons.Outlined.FastForward,
                    title = "Skip sponsors automatically",
                    subtitle = "Seek past submitted segments while watching",
                    checked = sponsor.autoSkip,
                    onCheckedChange = { container.sponsorBlockPrefs.setAutoSkip(it) },
                )
            }
            items(SPONSOR_CATEGORIES, key = { it.id }) { category ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            container.sponsorBlockPrefs.setCategory(
                                category.id,
                                category.id !in sponsor.categories,
                            )
                        }
                        .padding(start = 16.dp, end = 16.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = category.id in sponsor.categories,
                        onCheckedChange = {
                            container.sponsorBlockPrefs.setCategory(category.id, it)
                        },
                        colors = androidx.compose.material3.CheckboxDefaults.colors(
                            checkedColor = YtRed,
                            uncheckedColor = YtMuted,
                            checkmarkColor = androidx.compose.ui.graphics.Color.White,
                        ),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            category.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = YtText,
                        )
                        Text(
                            category.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = YtMuted,
                        )
                    }
                }
            }
            item {
                Text(
                    "Segments are submitted by the SponsorBlock community. IndioTube never skips anything unless you turn this on.",
                    color = YtMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }

            item { SettingsSection("History and privacy") }
            item {
                SettingsToggleRow(
                    icon = Icons.Outlined.Lock,
                    title = "Pause watch history",
                    subtitle = "Stop recording videos you watch",
                    checked = historyPaused,
                    onCheckedChange = { container.library.setHistoryPaused(it) },
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Outlined.History,
                    title = "Clear watch history",
                    subtitle = "Remove every video from this account's history",
                    onClick = { confirmClearHistory = true },
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Outlined.Delete,
                    title = "Clear search history",
                    subtitle = "${searches.size} searches",
                    onClick = { confirmClearSearches = true },
                )
            }

            item { SettingsSection("About") }
            item {
                SettingsRow(
                    icon = Icons.Outlined.Info,
                    title = "IndioTube",
                    subtitle = if (versionName.isBlank()) "Unofficial YouTube client" else "Version $versionName",
                )
            }
            item {
                Text(
                    "IndioTube is an unofficial, ad-free YouTube client. It is not affiliated with YouTube or Google. SponsorBlock segment data is provided by the SponsorBlock community.",
                    color = YtMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item { Spacer(Modifier.height(18.dp)) }
        }
    }

    if (qualitySheet) {
        QualityDialog(
            title = "Default video quality",
            choices = listOf(null, 2160, 1440, 1080, 720, 480, 360, 240, 144),
            selected = defaultQuality,
            onSelect = {
                container.playbackPrefs.setDefaultQualityHeight(it)
                qualitySheet = false
            },
            onDismiss = { qualitySheet = false },
        )
    }

    if (downloadQualitySheet) {
        QualityDialog(
            title = "Download quality",
            choices = DOWNLOAD_QUALITY_CHOICES,
            selected = downloadSettings.defaultHeight,
            onSelect = {
                container.downloadPrefs.setDefaultHeight(it)
                downloadQualitySheet = false
            },
            onDismiss = { downloadQualitySheet = false },
        )
    }

    if (captionSheet) {
        CaptionStyleSheet(
            prefs = container.captions,
            style = captionStyle,
            onDismiss = { captionSheet = false },
        )
    }

    if (confirmClearHistory) {
        ConfirmDialog(
            title = "Clear watch history?",
            message = "This removes every watched video and its progress from this account.",
            confirmLabel = "Clear",
            onConfirm = {
                container.library.clearHistory()
                confirmClearHistory = false
                ui.toast("Watch history cleared")
            },
            onDismiss = { confirmClearHistory = false },
        )
    }

    if (confirmClearSearches) {
        ConfirmDialog(
            title = "Clear search history?",
            message = "Your recent searches will be removed from Search and the home recommendations.",
            confirmLabel = "Clear",
            onConfirm = {
                container.library.clearSearches()
                ui.recentSearches.clear()
                confirmClearSearches = false
                ui.toast("Search history cleared")
            },
            onDismiss = { confirmClearSearches = false },
        )
    }
}

@Composable
private fun SettingsSection(title: String) {
    Column {
        HorizontalDivider(color = YtSurfaceHi, modifier = Modifier.padding(top = 14.dp))
        Text(
            title,
            color = YtRed,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = YtText, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = YtText)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = YtMuted)
            }
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = YtText, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = YtText)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = YtMuted)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                checkedTrackColor = YtRed,
                uncheckedThumbColor = YtMuted,
                uncheckedTrackColor = YtSurfaceHi,
            ),
        )
    }
}

@Composable
private fun QualityDialog(
    title: String,
    choices: List<Int?>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = YtSurface,
        titleContentColor = YtText,
        textContentColor = YtText,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                choices.forEach { choice ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(choice) }
                            .padding(vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            choice?.let { "${it}p" } ?: "Auto",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (choice == selected) YtRed else YtText,
                            modifier = Modifier.weight(1f),
                        )
                        if (choice == selected) {
                            Text("Selected", color = YtRed, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = YtMuted) }
        },
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = YtSurface,
        titleContentColor = YtText,
        textContentColor = YtMuted,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = YtRed) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = YtMuted) }
        },
    )
}
