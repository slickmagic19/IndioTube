package com.indiotube.app.player

import android.net.Uri
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import com.indiotube.app.Container
import java.io.IOException

// URI scheme for the player:
//   indio://stream/{videoId}  → resolve a fresh ad-free URL (NewPipeExtractor)
//   indio://offline/{videoId} → play the saved file, else fall back to stream
// Everything else falls through to plain HTTP(S).
class IndioTubeDataSource(private val container: Container) : BaseDataSource(true) {

    private var upstream: DataSource? = null
    private var uri: Uri? = null
    private var started = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val scheme = dataSpec.uri.scheme ?: ""
        val last = dataSpec.uri.lastPathSegment

        transferInitializing(dataSpec)

        var spec = dataSpec
        val source: DataSource = if (scheme == "indio") {
            if (last.isNullOrBlank()) throw IOException("Malformed media URI")
            val offline = container.downloads.file(last)
            if (offline != null) {
                spec = dataSpec.withUri(Uri.fromFile(offline))
                FileDataSource()
            } else {
                val resolved = container.resolver.resolveBlocking(last)
                spec = dataSpec.withUri(Uri.parse(resolved.url))
                httpSource()
            }
        } else {
            httpSource()
        }

        val length = source.open(spec)
        upstream = source
        started = true
        transferStarted(dataSpec)
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val n = upstream?.read(buffer, offset, length) ?: -1
        if (n > 0) bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            upstream?.close()
        } finally {
            upstream = null
            uri = null
            if (started) {
                started = false
                transferEnded()
            }
        }
    }

    class Factory(private val container: Container) : DataSource.Factory {
        override fun createDataSource(): DataSource = IndioTubeDataSource(container)
    }

    private fun httpSource(): DataSource = DefaultHttpDataSource.Factory()
        .setUserAgent(USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
        .createDataSource()

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Mobile Safari/537.36"
    }
}
