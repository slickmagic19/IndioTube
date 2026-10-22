package com.indiotube.app.data

import java.util.Locale

// A creator-defined chapter marker from YouTube's watch response.
data class Chapter(val title: String, val startMs: Long)

// The chapter covering this playback position. Null before the first marker
// when a video's chapters don't start at 0:00.
fun chapterAt(chapters: List<Chapter>, positionMs: Long): Chapter? =
    chapters.lastOrNull { positionMs >= it.startMs }

// "0:00", "2:19", "1:02:03" - chapter timestamps need 0:00 even though the
// duration formatter treats zero as "unknown" and returns an empty string.
fun chapterTimestamp(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%d:%02d", m, s)
    }
}

// YouTube repeats the chapter timestamps as the first lines of the
// description. Once the chapter list is rendered separately those lines are
// noise, but only lines that match an actual chapter start are removed so the
// rest of the description stays untouched.
fun stripChapterLines(description: String, chapters: List<Chapter>): String {
    if (chapters.isEmpty()) return description
    val stamps = chapters.map { chapterTimestamp(it.startMs) }
        .sortedByDescending { it.length }
    val kept = description.lineSequence().filterNot { line ->
        val trimmed = line.trimStart()
        val stamp = stamps.firstOrNull { trimmed.startsWith(it) } ?: return@filterNot false
        val rest = trimmed.removePrefix(stamp)
        rest.isEmpty() || rest.first().let {
            it == ' ' || it == '\t' || it == '-' || it == '–' || it == '—' ||
                it == ':' || it == '|'
        }
    }.joinToString("\n")
    return kept.trim()
}
