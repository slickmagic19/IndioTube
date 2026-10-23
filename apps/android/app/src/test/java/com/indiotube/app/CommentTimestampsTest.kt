package com.indiotube.app

import com.indiotube.app.data.findCommentTimestamps
import org.junit.Assert.assertEquals
import org.junit.Test

// Comment timestamps become seek links only when the token really is a time;
// this pins the accepted formats and the false positives that must stay text.
class CommentTimestampsTest {

    @Test
    fun minutesAndSecondsAreFound() {
        val text = "Intro at 0:30 and the demo at 12:45."
        val stamps = findCommentTimestamps(text)
        assertEquals(listOf(30L, 765L), stamps.map { it.seconds })
        assertEquals("0:30", text.substring(stamps[0].start, stamps[0].end))
        assertEquals("12:45", text.substring(stamps[1].start, stamps[1].end))
    }

    @Test
    fun hoursAreIncludedWhenPresent() {
        val stamps = findCommentTimestamps("the good part is 1:02:03")
        assertEquals(3_723L, stamps.single().seconds)
        assertEquals("1:02:03", "the good part is 1:02:03".substring(stamps.single().start, stamps.single().end))
    }

    @Test
    fun malformedTimesStayPlainText() {
        assertEquals(emptyList<Long>(), findCommentTimestamps("version 1:2 and 123:45").map { it.seconds })
        assertEquals(emptyList<Long>(), findCommentTimestamps("ratio 4:3").map { it.seconds })
        assertEquals(emptyList<Long>(), findCommentTimestamps("1:60 is not a minute").map { it.seconds })
    }

    @Test
    fun urlsAndLongNumbersDoNotBecomeLinks() {
        assertEquals(emptyList<Long>(), findCommentTimestamps("see https://example.com/watch?v=12").map { it.seconds })
        assertEquals(emptyList<Long>(), findCommentTimestamps("id 12345:678").map { it.seconds })
    }

    @Test
    fun everyTimestampInALongCommentIsCaptured() {
        val text = "0:00 start, 2:10 middle, 5:59 end and 1:00:00 much later"
        assertEquals(listOf(0L, 130L, 359L, 3_600L), findCommentTimestamps(text).map { it.seconds })
    }

    @Test
    fun timesPastTheVideoAreDroppedButASmallSlackIsKept() {
        // A 10-minute video: 20:00 is not a link, 10:03 still is.
        assertEquals(emptyList<Long>(), findCommentTimestamps("20:00 later", maxSeconds = 600).map { it.seconds })
        assertEquals(listOf(603L), findCommentTimestamps("10:03 later", maxSeconds = 600).map { it.seconds })
        assertEquals(listOf(1200L), findCommentTimestamps("20:00 later", maxSeconds = 0).map { it.seconds })
    }

    @Test
    fun theSameTimestampRepeatedGetsOneLinkEach() {
        val stamps = findCommentTimestamps("0:15 here and again 0:15 there")
        assertEquals(listOf(15L, 15L), stamps.map { it.seconds })
        assertEquals(2, stamps.size)
    }
}
