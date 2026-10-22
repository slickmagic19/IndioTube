package com.indiotube.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Playback behaviour that survives restarts. Autoplay is on by default, like
// YouTube; turning it off pauses at the end of each video instead of moving on
// to the next one in the queue.
//
// The default quality is what a video starts on instead of plain Auto: null
// keeps YouTube's behaviour (the best single file, fastest start), a height
// pins the rung - every video that offers it starts there, and one that does
// not uses the next lower rung it has.
class PlaybackPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("indiotube_playback", Context.MODE_PRIVATE)

    private val _autoplayNext = MutableStateFlow(prefs.getBoolean(KEY_AUTOPLAY_NEXT, true))
    val autoplayNext: StateFlow<Boolean> = _autoplayNext.asStateFlow()

    fun setAutoplayNext(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTOPLAY_NEXT, enabled).apply()
        _autoplayNext.value = enabled
    }

    private val _defaultQualityHeight = MutableStateFlow(prefs.getInt(KEY_DEFAULT_QUALITY, -1).takeIf { it > 0 })
    val defaultQualityHeight: StateFlow<Int?> = _defaultQualityHeight.asStateFlow()

    fun setDefaultQualityHeight(height: Int?) {
        prefs.edit().putInt(KEY_DEFAULT_QUALITY, height ?: -1).apply()
        _defaultQualityHeight.value = height?.takeIf { it > 0 }
    }

    private companion object {
        const val KEY_AUTOPLAY_NEXT = "autoplay_next"
        const val KEY_DEFAULT_QUALITY = "default_quality"
    }
}
