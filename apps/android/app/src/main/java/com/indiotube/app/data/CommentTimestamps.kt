package com.indiotube.app.data

// A "1:42" or "1:02:03" inside a comment, ready to be rendered as a link that
// seeks the video. Offsets index into the original comment text.
data class CommentTimestamp(
    val start: Int,
    val end: Int,
    val seconds: Long,
)

// mm:ss, or h:mm:ss when the hours group is present. The lookarounds keep the
// match from starting mid-number, and require two digits so "1:2" and version
// strings like "1:2" don't turn into links.
private val TIMESTAMP_RE = Regex("""(?<![\d:])(\d{1,2}):([0-5]\d)(?::([0-5]\d))?(?![\d:])""")

// Comments sometimes mention a time slightly past a stream's reported
// duration; allow a small slack before dropping those.
private const val TIMESTAMP_SLACK_SECONDS = 5L

// All timestamps in a comment, in order. `maxSeconds` is the current video's
// duration once known (0 = unknown): timestamps beyond it are not links,
// because seeking there would only hit the end.
fun findCommentTimestamps(text: String, maxSeconds: Long = 0L): List<CommentTimestamp> =
    TIMESTAMP_RE.findAll(text).mapNotNull { match ->
        val first = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
        val second = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
        val third = match.groupValues[3].takeIf { it.isNotEmpty() }?.toLongOrNull()
        val seconds = if (third != null) {
            first * 3_600 + second * 60 + third
        } else {
            first * 60 + second
        }
        if (maxSeconds > 0 && seconds > maxSeconds + TIMESTAMP_SLACK_SECONDS) {
            null
        } else {
            CommentTimestamp(
                start = match.range.first,
                end = match.range.last + 1,
                seconds = seconds,
            )
        }
    }.toList()
