package com.indiotube.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SponsorCategory(
    val id: String,
    val label: String,
    val description: String,
)

val SPONSOR_CATEGORIES = listOf(
    SponsorCategory("sponsor", "Sponsor", "Paid promotion, paid referrals and direct advertisements"),
    SponsorCategory("selfpromo", "Self-promotion", "Unpaid promotion of the creator's own merch, products or channels"),
    SponsorCategory("interaction", "Interaction", "Like, subscribe and comment reminders"),
    SponsorCategory("intro", "Intro", "Opening animation or title sequence"),
    SponsorCategory("outro", "Outro", "End cards and credits"),
    SponsorCategory("preview", "Preview", "Recap or preview of the video's contents"),
    SponsorCategory("music_offtopic", "Non-music section", "Non-music portions of a music video"),
    SponsorCategory("filler", "Filler", "Tangential scenes added only for filler or humour"),
)

val DEFAULT_SPONSOR_CATEGORIES: Set<String> = SPONSOR_CATEGORIES.map { it.id }.toSet()

data class SponsorBlockSettings(
    val autoSkip: Boolean = false,
    val categories: Set<String> = DEFAULT_SPONSOR_CATEGORIES,
)

class SponsorBlockPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("indiotube_sponsorblock", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<SponsorBlockSettings> = _settings.asStateFlow()

    fun setAutoSkip(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SKIP, enabled).apply()
        _settings.value = _settings.value.copy(autoSkip = enabled)
    }

    fun setCategory(id: String, enabled: Boolean) {
        val next = if (enabled) {
            _settings.value.categories + id
        } else {
            _settings.value.categories - id
        }
        prefs.edit().putStringSet(KEY_CATEGORIES, next).apply()
        _settings.value = _settings.value.copy(categories = next)
    }

    private fun read(): SponsorBlockSettings {
        val stored = prefs.getStringSet(KEY_CATEGORIES, null)
        return SponsorBlockSettings(
            autoSkip = prefs.getBoolean(KEY_AUTO_SKIP, false),
            categories = stored?.toSet() ?: DEFAULT_SPONSOR_CATEGORIES,
        )
    }

    private companion object {
        const val KEY_AUTO_SKIP = "auto_skip"
        const val KEY_CATEGORIES = "categories"
    }
}
