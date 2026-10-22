package com.indiotube.app.player

import com.indiotube.app.data.VideoItem

// Pure queue edits shared by PlaybackController and unit tests. Each function
// returns the updated queue + current index, or null when the edit is invalid.
object QueueOps {

    data class Result(val queue: List<VideoItem>, val index: Int)

    fun add(
        queue: List<VideoItem>,
        index: Int,
        video: VideoItem,
        playNext: Boolean,
    ): Result? {
        if (queue.any { it.videoId == video.videoId }) return null
        if (queue.isEmpty()) return Result(listOf(video), 0)
        val current = index.coerceAtLeast(0)
        val at = if (playNext) (current + 1).coerceIn(0, queue.size) else queue.size
        val next = queue.toMutableList().apply { add(at, video) }
        return Result(next, if (at <= current) current + 1 else current)
    }

    fun remove(queue: List<VideoItem>, index: Int, remove: Int): Result? {
        if (remove !in queue.indices || remove == index) return null
        val next = queue.toMutableList().apply { removeAt(remove) }
        return Result(next, if (remove < index) index - 1 else index)
    }

    fun move(queue: List<VideoItem>, index: Int, from: Int, to: Int): Result? {
        if (from !in queue.indices || to !in queue.indices || from == to) return null
        val next = queue.toMutableList().apply { add(to, removeAt(from)) }
        val nextIndex = when {
            index == from -> to
            from < index && to >= index -> index - 1
            from > index && to <= index -> index + 1
            else -> index
        }
        return Result(next, nextIndex)
    }
}
