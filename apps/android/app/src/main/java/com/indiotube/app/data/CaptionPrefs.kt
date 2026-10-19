package com.indiotube.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// User-tunable caption appearance, mirroring YouTube's caption settings.
// Values are persisted so the choice survives restarts and applies to every
// video, auto-generated and manual alike.
data class CaptionStyleSettings(
    val textSizeSp: Float = 15f,
    val textColor: Int = 0xFFFFFFFF.toInt(),
    val backgroundOpacity: Int = 50,
    val edgeStyle: Int = EDGE_NONE,
    val fontFamily: String = "sans-serif",
    // How far captions sit above the bottom edge of the player, in percent of
    // its height. Small default: captions hug the bottom the way YouTube
    // plays them, with just a little breathing room above the controls.
    val bottomMarginPercent: Int = 6,
) {
    // Black box whose transparency follows the chosen opacity, like YouTube.
    val backgroundColor: Int
        get() = Math.round(backgroundOpacity.coerceIn(0, 100) * 255f / 100f) shl 24

    // The same margin as PlayerView wants it: a fraction of the player height.
    val bottomPaddingFraction: Float
        get() = bottomMarginPercent.coerceIn(0, 30) / 100f

    companion object {
        const val EDGE_NONE = 0
        const val EDGE_OUTLINE = 1
        const val EDGE_SHADOW = 2
    }
}

// Remember the caption language and look across videos, like YouTube does.
// The chosen tag is applied again whenever the next video offers that
// language, and the look is applied to every caption track.
class CaptionPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("indiotube_captions", Context.MODE_PRIVATE)

    private val _language = MutableStateFlow(prefs.getString(KEY_LANGUAGE, null))
    val language: StateFlow<String?> = _language.asStateFlow()

    private val _style = MutableStateFlow(readStyle())
    val style: StateFlow<CaptionStyleSettings> = _style.asStateFlow()

    fun setLanguage(languageTag: String?) {
        val clean = languageTag?.trim()?.takeIf { it.isNotEmpty() }
        prefs.edit().apply {
            if (clean == null) remove(KEY_LANGUAGE) else putString(KEY_LANGUAGE, clean)
        }.apply()
        _language.value = clean
    }

    fun setTextSize(sizeSp: Float) {
        update(_style.value.copy(textSizeSp = sizeSp.coerceIn(10f, 24f)))
    }

    fun setTextColor(color: Int) {
        update(_style.value.copy(textColor = color))
    }

    fun setBackgroundOpacity(opacity: Int) {
        update(_style.value.copy(backgroundOpacity = opacity.coerceIn(0, 100)))
    }

    fun setEdgeStyle(edgeStyle: Int) {
        update(_style.value.copy(edgeStyle = edgeStyle.coerceIn(
            CaptionStyleSettings.EDGE_NONE,
            CaptionStyleSettings.EDGE_SHADOW,
        )))
    }

    fun setFontFamily(fontFamily: String) {
        update(_style.value.copy(fontFamily = fontFamily))
    }

    fun setBottomMargin(percent: Int) {
        update(_style.value.copy(bottomMarginPercent = percent.coerceIn(0, 30)))
    }

    private fun update(next: CaptionStyleSettings) {
        prefs.edit()
            .putFloat(KEY_TEXT_SIZE, next.textSizeSp)
            .putInt(KEY_TEXT_COLOR, next.textColor)
            .putInt(KEY_BACKGROUND_OPACITY, next.backgroundOpacity)
            .putInt(KEY_EDGE_STYLE, next.edgeStyle)
            .putString(KEY_FONT_FAMILY, next.fontFamily)
            .putInt(KEY_BOTTOM_MARGIN, next.bottomMarginPercent)
            .apply()
        _style.value = next
    }

    private fun readStyle() = CaptionStyleSettings(
        textSizeSp = prefs.getFloat(KEY_TEXT_SIZE, 15f).coerceIn(10f, 24f),
        textColor = prefs.getInt(KEY_TEXT_COLOR, 0xFFFFFFFF.toInt()),
        backgroundOpacity = prefs.getInt(KEY_BACKGROUND_OPACITY, 50).coerceIn(0, 100),
        edgeStyle = prefs.getInt(KEY_EDGE_STYLE, CaptionStyleSettings.EDGE_NONE)
            .coerceIn(CaptionStyleSettings.EDGE_NONE, CaptionStyleSettings.EDGE_SHADOW),
        fontFamily = prefs.getString(KEY_FONT_FAMILY, "sans-serif") ?: "sans-serif",
        bottomMarginPercent = prefs.getInt(KEY_BOTTOM_MARGIN, 6).coerceIn(0, 30),
    )

    private companion object {
        const val KEY_LANGUAGE = "language"
        const val KEY_TEXT_SIZE = "text_size_sp"
        const val KEY_TEXT_COLOR = "text_color"
        const val KEY_BACKGROUND_OPACITY = "background_opacity"
        const val KEY_EDGE_STYLE = "edge_style"
        const val KEY_FONT_FAMILY = "font_family"
        const val KEY_BOTTOM_MARGIN = "bottom_margin_percent"
    }
}
