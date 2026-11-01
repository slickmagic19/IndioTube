package com.indiotube.app.ui

import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.positionChange
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

// Press-and-hold on the video runs it at 2x until the finger lifts, the way
// YouTube's own hold gesture works.
//
// The detector sits on the player container and only ever *observes* the
// Initial pass: taps, double-tap seeks and swipes keep working because the
// layers above still receive (and consume) everything they always did. A
// release or a slide before the long-press timeout is an ordinary gesture and
// changes nothing.
internal suspend fun PointerInputScope.detectHoldToBoost(
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
) {
    awaitPointerEventScope {
        while (true) {
            val down = awaitPointerEvent(PointerEventPass.Initial)
                .changes.firstOrNull { it.changedToDown() } ?: continue

            val held = try {
                withTimeout(viewConfiguration.longPressTimeoutMillis) {
                    awaitReleaseOrSlide(down.id)
                }
                false
            } catch (_: PointerEventTimeoutCancellationException) {
                true
            } catch (_: TimeoutCancellationException) {
                true
            }
            if (!held) continue

            // The finally covers cancellation too (the screen closing mid-hold),
            // so the player can never be left stuck at the boost speed.
            try {
                onHoldStart()
                awaitPointerRelease(down.id)
            } finally {
                onHoldEnd()
            }
        }
    }
}

// Returns as soon as the finger lifts or slides past the touch slop.
private suspend fun AwaitPointerEventScope.awaitReleaseOrSlide(id: PointerId) {
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Initial)
            .changes.firstOrNull { it.id == id } ?: return
        if (!change.pressed) return
        if (change.positionChange().getDistance() > viewConfiguration.touchSlop) return
    }
}

private suspend fun AwaitPointerEventScope.awaitPointerRelease(id: PointerId) {
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Initial)
            .changes.firstOrNull { it.id == id } ?: return
        if (!change.pressed) return
    }
}
