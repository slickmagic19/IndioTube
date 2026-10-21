package com.indiotube.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DownloadSettings(
    val defaultHeight: Int? = null,
    val wifiOnly: Boolean = false,
)

val DOWNLOAD_QUALITY_CHOICES: List<Int?> = listOf(null, 1080, 720, 480, 360, 240, 144)

class DownloadPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("indiotube_downloads", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<DownloadSettings> = _settings.asStateFlow()

    fun setDefaultHeight(height: Int?) {
        val clean = height?.takeIf { it > 0 }
        prefs.edit().putInt(KEY_DEFAULT_HEIGHT, clean ?: 0).apply()
        _settings.value = _settings.value.copy(defaultHeight = clean)
    }

    fun setWifiOnly(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WIFI_ONLY, enabled).apply()
        _settings.value = _settings.value.copy(wifiOnly = enabled)
    }

    private fun read(): DownloadSettings = DownloadSettings(
        defaultHeight = prefs.getInt(KEY_DEFAULT_HEIGHT, 0).takeIf { it > 0 },
        wifiOnly = prefs.getBoolean(KEY_WIFI_ONLY, false),
    )

    private companion object {
        const val KEY_DEFAULT_HEIGHT = "default_height"
        const val KEY_WIFI_ONLY = "wifi_only"
    }
}
