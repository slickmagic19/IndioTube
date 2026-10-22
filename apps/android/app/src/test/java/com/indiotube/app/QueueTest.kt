package com.indiotube.app

import com.indiotube.app.data.VideoItem
import com.indiotube.app.player.QueueOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// QueueOps keeps the ordering/remap rules for "Play next", "Add to queue",
// remove and move in one pure place; PlaybackController only mirrors the
// result onto the media session.
class QueueTest {

    private fun video(id: String) = VideoItem(id, "Video $id")

    private fun ids(result: QueueOps.Result?) = result?.queue?.map { it.videoId }

    @Test
    fun addAppendsAndRejectsDuplicates() {
        val queue = listOf(video("a"), video("b"))

        val result = QueueOps.add(queue, 0, video("c"), playNext = false)
        assertEquals(listOf("a", "b", "c"), ids(result))
        assertEquals(0, result?.index)

        assertNull(QueueOps.add(queue, 0, video("b"), playNext = false))
    }

    @Test
    fun playNextInsertsAfterThePlayingItem() {
        val result = QueueOps.add(listOf(video("a"), video("b")), 0, video("x"), playNext = true)
        assertEquals(listOf("a", "x", "b"), ids(result))
        assertEquals(0, result?.index)
    }

    @Test
    fun playNextUsesTheCurrentIndexNotTheFirstRow() {
        val result = QueueOps.add(
            listOf(video("a"), video("b"), video("c")),
            1,
            video("x"),
            playNext = true,
        )
        assertEquals(listOf("a", "b", "x", "c"), ids(result))
        assertEquals(1, result?.index)
    }

    @Test
    fun addingToAnEmptyQueueCreatesIt() {
        val result = QueueOps.add(emptyList(), -1, video("a"), playNext = false)
        assertEquals(listOf("a"), ids(result))
        assertEquals(0, result?.index)
    }

    @Test
    fun removeRefusesThePlayingRow() {
        val queue = listOf(video("a"), video("b"), video("c"))
        assertNull(QueueOps.remove(queue, 1, 1))
    }

    @Test
    fun removingALaterRowKeepsTheCurrentIndex() {
        val result = QueueOps.remove(listOf(video("a"), video("b"), video("c")), 1, 2)
        assertEquals(listOf("a", "b"), ids(result))
        assertEquals(1, result?.index)
    }

    @Test
    fun removingAnEarlierRowShiftsTheCurrentIndex() {
        val result = QueueOps.remove(listOf(video("a"), video("b"), video("c")), 2, 0)
        assertEquals(listOf("b", "c"), ids(result))
        assertEquals(1, result?.index)
    }

    @Test
    fun movingThePlayingRowUpdatesTheIndex() {
        val result = QueueOps.move(listOf(video("a"), video("b"), video("c")), 0, 0, 2)
        assertEquals(listOf("b", "c", "a"), ids(result))
        assertEquals(2, result?.index)
    }

    @Test
    fun movingAnotherRowAcrossThePlayingRowKeepsItSelected() {
        val result = QueueOps.move(listOf(video("a"), video("b"), video("c")), 1, 2, 0)
        assertEquals(listOf("c", "a", "b"), ids(result))
        assertEquals(2, result?.index)
    }

    @Test
    fun invalidQueueEditsAreRejected() {
        val queue = listOf(video("a"), video("b"))
        assertNull(QueueOps.remove(queue, 0, -1))
        assertNull(QueueOps.remove(queue, 0, 2))
        assertNull(QueueOps.move(queue, 0, 0, 0))
        assertNull(QueueOps.move(queue, 0, 0, 5))
    }
}
