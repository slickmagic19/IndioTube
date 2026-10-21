package com.indiotube.app

import com.indiotube.app.data.ByteRange
import com.indiotube.app.data.RangeUnsupportedException
import com.indiotube.app.data.downloadByteRanges
import com.indiotube.app.data.downloadRange
import com.indiotube.app.data.parseContentRange
import com.indiotube.app.data.planByteRanges
import com.indiotube.app.data.planSeededByteRanges
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadTransferTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun rangesCoverTheWholeFileWithoutGaps() {
        val ranges = planByteRanges(100_003, maxSegments = 4, targetSegmentBytes = 25_000)
        assertEquals(4, ranges.size)
        assertEquals(0, ranges.first().start)
        assertEquals(100_002, ranges.last().end)
        ranges.zipWithNext().forEach { (current, next) ->
            assertEquals(current.end + 1, next.start)
        }
        assertEquals(100_003, ranges.sumOf { it.length })
    }

    @Test
    fun smallFilesUseOneRange() {
        val ranges = planByteRanges(1_024, maxSegments = 4, targetSegmentBytes = 8_192)
        assertEquals(listOf(ByteRange(0, 1_023)), ranges)
    }

    @Test
    fun seededRangesPreserveExistingBytesAndCoverTheRest() {
        val ranges = planSeededByteRanges(
            totalBytes = 100_000,
            completedBytes = 17_321,
            maxSegments = 4,
            targetSegmentBytes = 25_000,
        )
        assertEquals(ByteRange(0, 17_320), ranges.first())
        assertEquals(100_000L, ranges.sumOf { it.length })
        ranges.zipWithNext().forEach { (current, next) ->
            assertEquals(current.end + 1, next.start)
        }
    }

    @Test
    fun contentRangeIsParsed() {
        val range = parseContentRange("bytes 120-199/500")
        assertEquals(120L, range?.start)
        assertEquals(199L, range?.end)
        assertEquals(500L, range?.total)
    }

    @Test
    fun rangeDownloadResumesAtTheExistingOffset() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 3-9/10")
                .setBody("defghij"),
        )
        server.start()
        try {
            val file = File(temporaryFolder.root, "resume.bin")
            file.writeText("abc")
            downloadRange(
                client = OkHttpClient(),
                url = server.url("/video").toString(),
                file = file,
                range = ByteRange(0, 9),
            )
            assertEquals("abcdefghij", file.readText())
            assertEquals("bytes=3-9", server.takeRequest().getHeader("Range"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun fullResponseWithoutRangesIsRejected() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("not a range response"))
        server.start()
        try {
            val file = File(temporaryFolder.root, "rejected.bin")
            assertThrows(RangeUnsupportedException::class.java) {
                downloadRange(
                    client = OkHttpClient(),
                    url = server.url("/video").toString(),
                    file = file,
                    range = ByteRange(0, 19),
                )
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun parallelRangesReassembleTheOriginalFile() {
        val bytes = ByteArray(100_003) { (it % 251).toByte() }
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val values = request.getHeader("Range")
                    ?.removePrefix("bytes=")
                    ?.split("-")
                    .orEmpty()
                val start = values.getOrNull(0)?.toLongOrNull() ?: 0L
                val end = values.getOrNull(1)?.toLongOrNull() ?: (bytes.size - 1L)
                val length = (end - start + 1).toInt()
                return MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-$end/${bytes.size}")
                    .setHeader("Content-Length", length.toString())
                    .setBody(Buffer().write(bytes, start.toInt(), length))
            }
        }
        server.start()
        try {
            val ranges = planByteRanges(bytes.size.toLong(), maxSegments = 4, targetSegmentBytes = 25_000)
            val files = ranges.indices.map { File(temporaryFolder.root, "segment-$it.bin") }
            runBlocking {
                downloadByteRanges(
                    client = OkHttpClient(),
                    url = server.url("/video").toString(),
                    ranges = ranges,
                    segmentFile = { files[it] },
                    isCancelled = { false },
                    onCall = {},
                    onCallDone = {},
                    onBytes = {},
                )
            }
            val assembled = File(temporaryFolder.root, "assembled.bin")
            FileOutputStream(assembled).use { output ->
                files.forEach { segment -> segment.inputStream().use { it.copyTo(output) } }
            }
            assertArrayEquals(bytes, assembled.readBytes())
            assertEquals(ranges.size, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
