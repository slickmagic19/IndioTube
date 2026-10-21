package com.indiotube.app.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

internal const val DOWNLOAD_COPY_BUFFER = 128 * 1024
internal const val DOWNLOAD_SEGMENT_TARGET = 8L * 1024 * 1024

internal data class ByteRange(val start: Long, val end: Long) {
    init {
        require(start >= 0)
        require(end >= start)
    }

    val length: Long get() = end - start + 1
}

internal data class ContentRange(val start: Long, val end: Long, val total: Long?)

private val contentRangePattern = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)

internal fun parseContentRange(value: String?): ContentRange? {
    val match = contentRangePattern.find(value ?: return null) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    val total = match.groupValues[3].takeIf { it != "*" }?.toLongOrNull()
    return ContentRange(start, end, total)
}

internal fun planByteRanges(
    totalBytes: Long,
    maxSegments: Int,
    targetSegmentBytes: Long = DOWNLOAD_SEGMENT_TARGET,
): List<ByteRange> {
    if (totalBytes <= 0 || maxSegments <= 0 || targetSegmentBytes <= 0) return emptyList()
    val count = minOf(
        maxSegments.toLong(),
        ((totalBytes + targetSegmentBytes - 1) / targetSegmentBytes).coerceAtLeast(1L),
    ).toInt()
    if (count <= 1) return listOf(ByteRange(0, totalBytes - 1))
    val base = totalBytes / count
    val extra = (totalBytes % count).toInt()
    var start = 0L
    return List(count) { index ->
        val length = base + if (index < extra) 1L else 0L
        ByteRange(start, start + length - 1).also { start += length }
    }
}

internal fun planSeededByteRanges(
    totalBytes: Long,
    completedBytes: Long,
    maxSegments: Int,
    targetSegmentBytes: Long = DOWNLOAD_SEGMENT_TARGET,
): List<ByteRange> {
    if (completedBytes <= 0 || completedBytes >= totalBytes || maxSegments <= 1) return emptyList()
    val first = ByteRange(0, completedBytes - 1)
    val remaining = planByteRanges(
        totalBytes - completedBytes,
        maxSegments - 1,
        targetSegmentBytes,
    )
    return listOf(first) + remaining.map { range ->
        ByteRange(completedBytes + range.start, completedBytes + range.end)
    }
}

internal class RangeUnsupportedException : IOException("Server ignored the byte range")

internal fun downloadRange(
    client: OkHttpClient,
    url: String,
    file: File,
    range: ByteRange,
    isCancelled: () -> Boolean = { false },
    onCall: (Call) -> Unit = {},
    onCallDone: (Call) -> Unit = {},
    onBytes: (Int) -> Unit = {},
) {
    var copied = file.length()
    if (copied >= range.length) return
    val requestStart = range.start + copied
    val request = Request.Builder()
        .url(url)
        .header("Range", "bytes=$requestStart-${range.end}")
        .header("Accept-Encoding", "identity")
        .build()
    val call = client.newCall(request)
    onCall(call)
    try {
        if (isCancelled()) {
            call.cancel()
            throw IOException("Download cancelled")
        }
        call.execute().use { response ->
            when (response.code) {
                206 -> Unit
                200 -> throw RangeUnsupportedException()
                416 -> throw IOException("Download failed (HTTP 416)")
                else -> throw IOException("Download failed (HTTP ${response.code})")
            }
            val contentRange = parseContentRange(response.header("Content-Range"))
                ?: throw IOException("Download failed (missing Content-Range)")
            if (contentRange.start != requestStart) {
                throw IOException("Download range mismatch")
            }
            if (contentRange.total != null && contentRange.total <= range.end) {
                throw IOException("Download range is short")
            }
            val expected = range.end - requestStart + 1
            val contentLength = response.body?.contentLength() ?: -1L
            if (contentLength > 0 && contentLength != expected) {
                throw IOException("Download range length mismatch")
            }
            val body = response.body ?: throw IOException("Download failed (empty response)")
            FileOutputStream(file, true).use { output ->
                val input = body.byteStream()
                val buffer = ByteArray(DOWNLOAD_COPY_BUFFER)
                var received = 0L
                while (true) {
                    if (isCancelled()) throw IOException("Download cancelled")
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    received += read
                    onBytes(read)
                }
                if (received != expected) throw IOException("Download stalled near the end")
            }
        }
        if (file.length() != range.length) throw IOException("Download stalled near the end")
    } finally {
        onCallDone(call)
    }
}

internal suspend fun downloadByteRanges(
    client: OkHttpClient,
    url: String,
    ranges: List<ByteRange>,
    segmentFile: (Int) -> File,
    isCancelled: () -> Boolean,
    onCall: (Call) -> Unit,
    onCallDone: (Call) -> Unit,
    onBytes: (Int) -> Unit,
) = coroutineScope {
    ranges.indices.map { index ->
        async(Dispatchers.IO) {
            downloadRange(
                client = client,
                url = url,
                file = segmentFile(index),
                range = ranges[index],
                isCancelled = isCancelled,
                onCall = onCall,
                onCallDone = onCallDone,
                onBytes = onBytes,
            )
        }
    }.awaitAll()
}
