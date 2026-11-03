package com.indiotube.app.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.indiotube.app.Container
import com.indiotube.app.data.SyncMerge
import com.indiotube.app.data.SyncOutcome
import com.indiotube.app.data.SyncStats
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch

sealed interface SyncPhase {
    data object Idle : SyncPhase
    data object Syncing : SyncPhase
    data class Done(val outcome: SyncOutcome) : SyncPhase
    data class Failed(val message: String) : SyncPhase
}

// "Sync with PC": scan the QR the desktop app shows, pull its library, merge
// add-only, push ours back. A manual host/port/token entry path covers phones
// without a working camera.
@Composable
fun SyncScreen(container: Container, ui: UiRefs) {
    var phase by remember { mutableStateOf<SyncPhase>(SyncPhase.Idle) }
    var manualOpen by remember { mutableStateOf(false) }
    var manualText by remember { mutableStateOf("") }

    fun start(payload: String) {
        phase = SyncPhase.Syncing
        ui.scope.launch {
            try {
                val (library, subs, outcome) = container.syncClient.run(
                    payload,
                    container.library.currentFile(),
                    container.accounts.subscriptions.value,
                    Build.MODEL ?: "Android phone",
                )
                container.library.applyMerged(library)
                container.accounts.applyMergedSubscriptions(subs)
                phase = SyncPhase.Done(outcome)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                phase = SyncPhase.Failed(e.message ?: "Sync failed")
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents
        if (text != null) start(text)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(YtBg)
            .padding(horizontal = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
            IconButton(onClick = { ui.back() }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = YtText)
            }
            Text("Sync with PC", style = MaterialTheme.typography.titleLarge, color = YtText)
        }

        when (val p = phase) {
            SyncPhase.Idle -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(24.dp))
                Icon(Icons.Rounded.QrCode2, null, tint = YtBlue, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(14.dp))
                Text("Bring your library together", style = MaterialTheme.typography.titleMedium, color = YtText)
                Spacer(Modifier.height(10.dp))
                Text(
                    "On your PC, open IndioTube → Settings → “Open sync”. A QR code appears. Scan it with this phone — both devices need to be on the same Wi-Fi.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = YtMuted,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Sync only adds: history with playback positions, Watch later, Liked videos, playlists (matched by name) and subscriptions. Nothing is ever deleted on either side.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = YtMuted,
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        launcher.launch(
                            ScanOptions()
                                .setOrientationLocked(false)
                                .setPrompt("Scan the QR code from IndioTube on your PC"),
                        )
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = YtRed),
                ) {
                    Icon(Icons.Rounded.QrCodeScanner, null)
                    Spacer(Modifier.size(10.dp))
                    Text("Scan QR code")
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { manualOpen = true }) {
                    Text("Enter PC address manually", color = YtBlue)
                }
            }

            SyncPhase.Syncing -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(color = YtBlue)
                Spacer(Modifier.height(18.dp))
                Text("Syncing…", style = MaterialTheme.typography.titleMedium, color = YtText)
                Spacer(Modifier.height(6.dp))
                Text("Merging your PC and phone libraries. This only ever adds.",
                    style = MaterialTheme.typography.bodyMedium, color = YtMuted, textAlign = TextAlign.Center)
            }

            is SyncPhase.Done -> DoneContent(
                outcome = p.outcome,
                onDone = { ui.back() },
                onAgain = { phase = SyncPhase.Idle },
            )

            is SyncPhase.Failed -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Rounded.ErrorOutline, null, tint = YtRed, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(14.dp))
                Text("Sync didn't finish", style = MaterialTheme.typography.titleMedium, color = YtText)
                Spacer(Modifier.height(6.dp))
                Text(p.message, style = MaterialTheme.typography.bodyMedium, color = YtMuted, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Check that both devices are on the same Wi-Fi and that the PC's sync window is still open (codes expire after 15 quiet minutes).",
                    style = MaterialTheme.typography.bodySmall, color = YtMuted, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = { phase = SyncPhase.Idle },
                    colors = ButtonDefaults.buttonColors(containerColor = YtRed),
                ) { Text("Try again") }
                TextButton(onClick = { ui.back() }) { Text("Close", color = YtMuted) }
            }
        }
    }

    if (manualOpen) {
        AlertDialog(
            onDismissRequest = { manualOpen = false },
            containerColor = YtSurface,
            titleContentColor = YtText,
            textContentColor = YtMuted,
            title = { Text("Manual pairing") },
            text = {
                Column {
                    Text("Type the address, port and token shown under the PC's QR code, separated by spaces.",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = manualText,
                        onValueChange = { manualText = it },
                        singleLine = true,
                        placeholder = { Text("192.168.1.20 47711 a1b2c3…", color = YtMuted) },
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = manualText.isNotBlank(), onClick = {
                    manualOpen = false
                    start(manualText)
                }) { Text("Sync", color = if (manualText.isNotBlank()) YtBlue else YtMuted) }
            },
            dismissButton = {
                TextButton(onClick = { manualOpen = false }) { Text("Cancel", color = YtMuted) }
            },
        )
    }
}

@Composable
private fun DoneContent(outcome: SyncOutcome, onDone: () -> Unit, onAgain: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(28.dp))
        Text("Synced with ${outcome.pcName}", style = MaterialTheme.typography.titleLarge, color = YtText)
        Spacer(Modifier.height(16.dp))
        StatsBlock("Added to this phone", outcome.received)
        Spacer(Modifier.height(12.dp))
        StatsBlock("Added to the PC", outcome.sent)
        Spacer(Modifier.height(16.dp))
        Text(
            "Add-only, always: nothing was deleted on either side.",
            style = MaterialTheme.typography.bodySmall, color = YtMuted, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = YtRed),
        ) { Text("Done") }
        TextButton(onClick = onAgain) { Text("Sync again", color = YtMuted) }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun StatsBlock(label: String, stats: SyncStats) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(YtSurfaceHi)
            .padding(16.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = YtText)
        Spacer(Modifier.height(8.dp))
        if (stats.total == 0) {
            Text("Nothing new — already in sync.", style = MaterialTheme.typography.bodySmall, color = YtMuted)
            return@Column
        }
        StatRow("history", stats.historyAdded)
        StatRow("watch later", stats.watchLaterAdded)
        StatRow("liked", stats.likedAdded)
        StatRow("playlists", stats.playlistsAdded)
        StatRow("playlists updated", stats.playlistsMerged)
        StatRow("playlist videos", stats.videosAdded)
        StatRow("subscriptions", stats.subsAdded)
    }
}

@Composable
private fun StatRow(label: String, value: Int) {
    if (value <= 0) return
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("+$value $label", style = MaterialTheme.typography.bodyMedium, color = YtBlue)
    }
}
