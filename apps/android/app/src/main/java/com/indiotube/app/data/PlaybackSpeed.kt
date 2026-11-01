package com.indiotube.app.data

import kotlin.math.round

// Speeds the menus offer, the same ladder YouTube's player shows.
val PLAYBACK_SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

const val MIN_PLAYBACK_SPEED = 0.25f
const val MAX_PLAYBACK_SPEED = 2f

// What a press-and-hold on the video snaps to, like YouTube's 2x gesture.
const val BOOST_PLAYBACK_SPEED = 2f

// Anything outside the ladder (a corrupted preference, a zero from an old
// build) falls back to normal speed instead of silencing or speeding up
// playback out of control.
fun clampPlaybackSpeed(speed: Float): Float =
    if (speed.isNaN() || speed <= 0f) 1f else speed.coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED)

// "1x", "1.5x", "0.75x": trailing zeros dropped the way YouTube writes them.
fun speedLabel(speed: Float): String {
    val value = round(clampPlaybackSpeed(speed) * 100f) / 100f
    return if (value % 1f == 0f) "${value.toInt()}\u00d7" else "${value}\u00d7"
}

// Wall-clock time saved by watching above 1x. Shown in the statistics page,
// where it is the one number YouTube's own watch-time card doesn't offer.
fun timeSavedBySpeed(playedMs: Long, speed: Float): Long =
    if (speed <= 1f || playedMs <= 0) 0L else (playedMs * (speed - 1f)).toLong()
