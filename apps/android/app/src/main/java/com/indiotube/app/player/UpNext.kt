package com.indiotube.app.player

import com.indiotube.app.data.VideoItem

// How long the "Up next" card waits before moving on, like YouTube.
const val UP_NEXT_COUNTDOWN_SECONDS = 5

// A pending automatic transition: the next queue item and the seconds left on
// the card. Null whenever nothing is counting down.
data class UpNextState(
    val video: VideoItem,
    val secondsLeft: Int,
)

// The player pauses at the end of every item when autoplay is on, so "did this
// video end?" is a position question rather than a player-state one. The
// tolerance absorbs the last polling tick before the exact end.
fun atEndOfItem(
    positionMs: Long,
    durationMs: Long,
    toleranceMs: Long = 750L,
): Boolean = durationMs > 0 && positionMs >= durationMs - toleranceMs

// Decides whether the poller should start (or immediately perform) the
// automatic transition. Kept pure so the edge cases are testable without a
// MediaController.
fun shouldStartAutoplayCountdown(
    autoplayEnabled: Boolean,
    hasNext: Boolean,
    playbackReady: Boolean,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    alreadyPending: Boolean,
    isLive: Boolean = false,
): Boolean = autoplayEnabled &&
    hasNext &&
    playbackReady &&
    !isPlaying &&
    !alreadyPending &&
    !isLive &&
    atEndOfItem(positionMs, durationMs)
