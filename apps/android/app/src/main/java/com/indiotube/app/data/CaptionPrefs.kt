package com.indiotube.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Remember the caption language across videos, like YouTube does. The chosen
// tag is applied again whenever the next video offers that language.
class CaptionPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("indiotube_captions", Context.MODE_PRIVATE)

    private val _language = MutableStateFlow(prefs.getString(KEY_LANGUAGE, null))
    val language: StateFlow<String?> = _language.asStateFlow()

    fun setLanguage(languageTag: String?) {
        val clean = languageTag?.trim()?.takeIf { it.isNotEmpty() }
        prefs.edit().apply {
            if (clean == null) remove(KEY_LANGUAGE) else putString(KEY_LANGUAGE, clean)
        }.apply()
        _language.value = clean
    }

    private companion object {
        const val KEY_LANGUAGE = "language"
    }
}
