package com.indiotube.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.indiotube.app.Container

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSheet(container: Container, ui: UiRefs, onClose: () -> Unit) {
    val accounts by container.accounts.accounts.collectAsState()
    val activeId by container.accounts.activeId.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var adding by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = YtSurface,
        contentColor = YtText,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                "Accounts",
                style = MaterialTheme.typography.titleMedium,
                color = YtText,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Text(
                "Local profiles — your subscriptions and history stay on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = YtMuted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Spacer(Modifier.size(8.dp))

            accounts.forEach { acc ->
                val isActive = acc.id == activeId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (!isActive) {
                                container.accounts.switchTo(acc.id)
                                ui.toast("Switched to ${acc.name}")
                            }
                            onClose()
                        }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChannelAvatar(acc.name, null, 38.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            acc.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = YtText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${acc.subscriptions.size} subscriptions",
                            style = MaterialTheme.typography.bodySmall,
                            color = YtMuted,
                        )
                    }
                    if (isActive) {
                        Icon(Icons.Filled.Check, "Active account", tint = YtBlue, modifier = Modifier.size(20.dp))
                    } else if (accounts.size > 1) {
                        Icon(
                            Icons.Filled.Close,
                            "Remove account",
                            tint = YtMuted,
                            modifier = Modifier
                                .size(18.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .clickable {
                                    container.accounts.removeAccount(acc.id)
                                    ui.toast("Account removed")
                                },
                        )
                    }
                }
            }

            Spacer(Modifier.size(4.dp))
            if (adding) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        placeholder = { Text("Profile name", color = YtMuted) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "Create profile",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (newName.isBlank()) YtMuted else YtBlue,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(enabled = newName.isNotBlank()) {
                                val acc = container.accounts.addAccount(newName.trim())
                                container.accounts.switchTo(acc.id)
                                ui.toast("Switched to ${acc.name}")
                                newName = ""
                                adding = false
                                onClose()
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { adding = true }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Add, null, tint = YtText, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(14.dp))
                    Text("Add account", style = MaterialTheme.typography.bodyLarge, color = YtText)
                }
            }
        }
    }
}
