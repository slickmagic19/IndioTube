package com.indiotube.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Playback behaviour that survives restarts. Autoplay is on by default, like
// YouTube; turning it off pauses at the end of each video instead of moving on
// to the next one in the queue.
class PlaybackPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("indiotube_playback", Context.MODE_PRIVATE)

    private val _autoplayNext = MutableStateFlow(prefs.getBoolean(KEY_AUTOPLAY_NEXT, true))
    val autoplayNext: StateFlow<Boolean> = _autoplayNext.asStateFlow()

    fun setAutoplayNext(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTOPLAY_NEXT, enabled).apply()
        _autoplayNext.value = enabled
    }

    private companion object {
        const val KEY_AUTOPLAY_NEXT = "autoplay_next"
    }
}
