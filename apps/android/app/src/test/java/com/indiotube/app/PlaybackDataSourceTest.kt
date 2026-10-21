package com.indiotube.app

import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.indiotube.app.player.IndioTubeDataSource
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackDataSourceTest {

    @Test
    fun playerRangeReadsUseTheExpectedHeaders() {
        val body = ByteArray(64) { it.toByte() }
        val server = MockWebServer()
        server.enqueue(rangedResponse(body, start = 16, end = 47, total = body.size))
        server.start()

        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        val dataSource = OkHttpDataSource.Factory(client)
            .setUserAgent(IndioTubeDataSource.USER_AGENT)
            .setDefaultRequestProperties(mapOf("Accept-Encoding" to "identity"))
            .createDataSource()

        try {
            val spec = DataSpec.Builder()
                .setUri(server.url("/media").toString())
                .setPosition(16)
                .setLength(32)
                .build()

            assertEquals(32L, dataSource.open(spec))
            val actual = ByteArray(32)
            var offset = 0
            while (offset < actual.size) {
                val read = dataSource.read(actual, offset, actual.size - offset)
                if (read == -1) break
                offset += read
            }

            assertArrayEquals(body.copyOfRange(16, 48), actual)
            server.takeRequest().let { request ->
                assertEquals("bytes=16-47", request.getHeader("Range"))
                assertEquals("identity", request.getHeader("Accept-Encoding"))
                assertEquals(IndioTubeDataSource.USER_AGENT, request.getHeader("User-Agent"))
            }
        } finally {
            dataSource.close()
            server.shutdown()
        }
    }

    private fun rangedResponse(
        body: ByteArray,
        start: Int,
        end: Int,
        total: Int,
    ): MockResponse {
        val chunk = body.copyOfRange(start, end + 1)
        return MockResponse()
            .setResponseCode(206)
            .setHeader("Content-Type", "video/mp4")
            .setHeader("Content-Range", "bytes $start-$end/$total")
            .setHeader("Content-Length", chunk.size.toString())
            .setBody(Buffer().write(chunk))
    }
}
