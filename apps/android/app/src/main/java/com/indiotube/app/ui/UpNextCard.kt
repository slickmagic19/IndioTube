package com.indiotube.app.ui

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indiotube.app.player.UP_NEXT_COUNTDOWN_SECONDS
import com.indiotube.app.player.UpNextState

// The "Up next in 5…" card that appears over the player at the end of a video
// instead of jumping straight into the next one. Play now skips the countdown;
// the close button cancels it and leaves the video paused at its end.
@Composable
internal fun UpNextCard(
    state: UpNextState,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        Column(
            Modifier
                .padding(12.dp)
                .widthIn(max = 340.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xF21F1F1F))
                .padding(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumb(
                    model = state.video.thumbnail,
                    corner = 8.dp,
                    modifier = Modifier.width(92.dp).height(52.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Up next in ${state.secondsLeft}s",
                        color = Color(0xFFAAAAAA),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        state.video.title,
                        color = Color.White,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        state.video.channelName.orEmpty(),
                        color = Color(0xFFAAAAAA),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = {
                    (state.secondsLeft.toFloat() / UP_NEXT_COUNTDOWN_SECONDS)
                        .coerceIn(0f, 1f)
                },
                color = YtRed,
                trackColor = Color(0x33FFFFFF),
                modifier = Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(50)),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Play now",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0x26FFFFFF))
                        .clickable(onClick = onPlayNow)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "Cancel",
                    color = Color(0xFFAAAAAA),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onCancel)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
                IconButton(onClick = onCancel, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        "Cancel autoplay",
                        tint = Color(0xFFAAAAAA),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}
