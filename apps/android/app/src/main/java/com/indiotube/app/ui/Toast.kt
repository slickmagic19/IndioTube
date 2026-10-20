package com.indiotube.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// App-wide transient messages rendered above every layer (player, sheets).
// An optional action turns the toast into YouTube's Undo snackbar.
data class ToastMsg(
    val id: Long,
    val text: String,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null,
)

class ToastState {
    private val counter = AtomicLong(0)
    private val _current = MutableStateFlow<ToastMsg?>(null)
    val current: StateFlow<ToastMsg?> = _current.asStateFlow()

    fun show(text: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        _current.value = ToastMsg(counter.incrementAndGet(), text, actionLabel, action)
    }

    fun dismiss(id: Long) {
        if (_current.value?.id == id) _current.value = null
    }
}

@Composable
fun ToastHost(state: ToastState, modifier: Modifier = Modifier) {
    val msg by state.current.collectAsState()
    var lastText by remember { mutableStateOf("") }
    LaunchedEffect(msg) { msg?.let { lastText = it.text } }
    LaunchedEffect(msg?.id) {
        val id = msg?.id ?: return@LaunchedEffect
        delay(3000)
        state.dismiss(id)
    }
    AnimatedVisibility(
        visible = msg != null,
        enter = fadeIn() + slideInVertically { it / 3 },
        exit = fadeOut() + slideOutVertically { it / 3 },
        modifier = modifier,
    ) {
        Row(
            Modifier
                .padding(horizontal = 20.dp)
                .shadow(8.dp, RoundedCornerShape(22.dp))
                .clip(RoundedCornerShape(22.dp))
                .background(YtSurfaceHi)
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Info, null, tint = YtBlue, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(9.dp))
            Text(
                msg?.text ?: lastText,
                style = MaterialTheme.typography.bodyMedium,
                color = YtText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            val actionLabel = msg?.actionLabel
            val action = msg?.action
            val id = msg?.id
            if (actionLabel != null && action != null && id != null) {
                Spacer(Modifier.size(8.dp))
                Text(
                    actionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = YtBlue,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            action()
                            state.dismiss(id)
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}
