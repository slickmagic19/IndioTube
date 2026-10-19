package com.indiotube.app.data

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream

// Ad-free video URLs via NewPipeExtractor - the same extractor the NewPipe app
// uses. Progressive (non-segmented) streams only:
//  - muxed: video+audio in one file, plays directly and saves offline as a
//    single playable file
//  - video-only + audio: paired by the player (MergingMediaSource) to offer
//    the higher resolutions YouTube only serves separately
data class ResolvedStream(
    val url: String,
    val ext: String,
    val mime: String?,
    val height: Int,
) {
    val isVideo: Boolean get() = mime?.startsWith("video/") == true
    val isAudio: Boolean get() = mime?.startsWith("audio/") == true
}

// One selectable quality. A height is either backed by a muxed file (single,
// simplest) or by a video-only file that gets paired with the best audio.
data class QualityOption(
    val height: Int,
    val label: String,
    val fps: Int,
    val muxed: ResolvedStream?,
    val videoOnly: ResolvedStream?,
) {
    val hasStream: Boolean get() = muxed != null || videoOnly != null
}

data class VideoQualities(
    val options: List<QualityOption>, // highest first
    val auto: ResolvedStream?,        // best muxed file: what plain "Auto" plays
    val audio: ResolvedStream?,       // best audio-only pair for video-only streams
) {
    val bestHeight: Int get() = auto?.height ?: options.firstOrNull()?.height ?: 0

    // Highest quality at or below the requested height, else the lowest
    // available one, so a stale preference never leaves playback without a
    // source.
    fun nearest(height: Int): QualityOption? =
        options.filter { it.height <= height }.maxByOrNull { it.height }
            ?: options.minByOrNull { it.height }

    fun labelFor(height: Int?): String? =
        if (height == null) null else options.firstOrNull { it.height == height }?.label

    fun muxedAt(height: Int): ResolvedStream? =
        options.filter { it.muxed != null && it.height <= height }
            .maxByOrNull { it.height }?.muxed
            ?: options.filter { it.muxed != null }.minByOrNull { it.height }?.muxed

    fun videoAt(height: Int): ResolvedStream? =
        options.filter { it.videoOnly != null && it.height <= height }
            .maxByOrNull { it.height }?.videoOnly
            ?: options.filter { it.videoOnly != null }.minByOrNull { it.height }?.videoOnly
}

class StreamResolver(private val http: OkHttpClient) {

    private data class Entry(val qualities: VideoQualities, val at: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val misses = ConcurrentHashMap<String, Long>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    companion object {
        private const val TTL_MS = 5 * 3600 * 1000L // URLs live ~6h
        private const val MISS_TTL_MS = 60 * 1000L
        @Volatile private var inited = false

        fun init(http: OkHttpClient) {
            if (inited) return
            synchronized(this) {
                if (inited) return
                NewPipe.init(NpDownloader(http))
                inited = true
            }
        }
    }

    // forceRefresh bypasses both cache and negative-cache - used when a
    // download retry needs a fresh URL (expired or rejected by the CDN).
    fun resolveBlocking(videoId: String, forceRefresh: Boolean = false): ResolvedStream {
        val qualities = qualities(videoId, forceRefresh)
        return qualities.auto ?: throw IOException("This video has no downloadable stream without ads")
    }

    // Streams behind an explicit quality selection. "video" and "audio" are
    // the two halves the player merges; "muxed" is the one-file variant.
    fun resolveTrackBlocking(videoId: String, kind: String, height: Int): ResolvedStream {
        val qualities = qualities(videoId)
        return when (kind) {
            "audio" -> qualities.audio
                ?: throw IOException("No audio stream was offered for this video")
            "video" -> qualities.videoAt(height)
                ?: throw IOException("This quality isn't available for this video")
            "muxed" -> qualities.muxedAt(height)
                ?: throw IOException("This quality isn't available for this video")
            else -> qualities.auto
                ?: throw IOException("This video has no downloadable stream without ads")
        }
    }

    fun qualities(videoId: String, forceRefresh: Boolean = false): VideoQualities {
        if (!forceRefresh) {
            cache[videoId]?.let { if (System.currentTimeMillis() - it.at < TTL_MS) return it.qualities }
            misses[videoId]?.let {
                if (System.currentTimeMillis() - it < MISS_TTL_MS) {
                    throw IOException("Video stream unavailable right now")
                }
            }
        }
        // one extraction per video even under concurrent requests
        val mutex = locks.computeIfAbsent(videoId) { Mutex() }
        return runBlocking {
            mutex.withLock {
                if (!forceRefresh) {
                    cache[videoId]?.let {
                        if (System.currentTimeMillis() - it.at < TTL_MS) return@withLock it.qualities
                    }
                }
                try {
                    val qualities = extract(videoId)
                    if (qualities.auto == null && qualities.options.isEmpty()) {
                        throw IOException("This video has no downloadable stream without ads")
                    }
                    cache[videoId] = Entry(qualities, System.currentTimeMillis())
                    misses.remove(videoId)
                    qualities
                } catch (e: Exception) {
                    misses[videoId] = System.currentTimeMillis()
                    throw if (e is IOException) e else IOException(e.message ?: "Couldn't resolve a stream", e)
                }
            }
        }
    }

    private fun extract(videoId: String): VideoQualities {
        val info = StreamInfo.getInfo(NewPipe.getService(0), "https://www.youtube.com/watch?v=$videoId")

        // Some extractor versions return video-only entries in both lists;
        // de-duplicate by URL.
        val allVideo = LinkedHashMap<String, VideoStream>()
        (info.videoStreams + info.videoOnlyStreams).forEach { vs ->
            val url = vs.url
            if (vs.isUrl && url != null && vs.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP) {
                allVideo[url] = vs
            }
        }
        val muxed = allVideo.values.filter { !it.isVideoOnly }
        val videoOnly = allVideo.values.filter { it.isVideoOnly }

        val auto = muxed.maxWithOrNull(
            compareBy<VideoStream>({ streamHeight(it) }, { it.bitrate }),
        )?.let { it.toResolved(streamHeight(it)) }

        val audio = bestAudio(info.audioStreams)

        val heights = (muxed.map { streamHeight(it) } + videoOnly.map { streamHeight(it) })
            .filter { it > 0 }
            .distinct()
            .sortedDescending()

        val options = heights.mapNotNull { height ->
            val m = muxed.filter { streamHeight(it) == height }.maxByOrNull { it.bitrate }
            // A video-only track is only useful when there is audio to pair
            // it with; otherwise prefer the muxed variant at this height.
            val v = if (audio != null) {
                videoOnly.filter { streamHeight(it) == height }
                    .maxWithOrNull(compareBy<VideoStream>({ codecRank(it) }, { it.fps }, { it.bitrate }))
            } else {
                null
            }
            val chosen = v ?: m ?: return@mapNotNull null
            val fps = chosen.fps.takeIf { it > 0 } ?: 0
            QualityOption(
                height = height,
                label = "${height}p" + if (fps >= 50) "$fps" else "",
                fps = fps,
                muxed = m?.toResolved(height),
                videoOnly = v?.toResolved(height),
            )
        }

        return VideoQualities(options = options, auto = auto, audio = audio)
    }

    // Prefer AAC/m4a for the widest device support, then the highest bitrate.
    // Only the original audio track is used (not dubs/descriptions).
    private fun bestAudio(all: List<AudioStream>): ResolvedStream? {
        val usable = all.filter { s ->
            val url = s.url
            s.isUrl && url != null && s.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
        }
        val original = usable.filter {
            val t = it.audioTrackType
            t == null || t == AudioTrackType.ORIGINAL
        }
        val pool = original.ifEmpty { usable.filter { it.audioTrackType != AudioTrackType.DESCRIPTIVE } }
        val best = pool.maxWithOrNull(
            compareBy<AudioStream>(
                { audioRank(it) },
                { it.averageBitrate.takeIf { b -> b > 0 } ?: it.bitrate },
            ),
        ) ?: return null
        return best.toResolved()
    }

    private fun streamHeight(v: VideoStream): Int =
        v.height.takeIf { h -> h > 0 }
            ?: v.resolution?.removeSuffix("p")?.toIntOrNull()
            ?: 0

    // H.264 decodes on every Android device; VP9 next; AV1 last.
    private fun codecRank(v: VideoStream): Int = when {
        v.codec?.startsWith("avc") == true -> 0
        v.codec?.startsWith("vp9") == true || v.codec?.startsWith("vp09") == true -> 1
        v.codec?.startsWith("av01") == true -> 2
        else -> 3
    }

    private fun audioRank(s: AudioStream): Int = when {
        s.format == MediaFormat.M4A -> 2
        s.codec?.startsWith("mp4a") == true -> 2
        s.format == MediaFormat.OPUS || s.format == MediaFormat.WEBMA_OPUS -> 1
        s.codec?.startsWith("opus") == true -> 1
        else -> 0
    }

    private fun Stream.toResolved(height: Int = 0): ResolvedStream {
        val streamUrl = url ?: throw IOException("No stream URL was offered for this video")
        val fmt = format
        val mime = fmt?.mimeType
        val ext = fmt?.suffix?.takeIf { it.isNotBlank() } ?: when {
            mime?.contains("mp4") == true -> "mp4"
            mime?.contains("webm") == true -> "webm"
            mime?.contains("3gpp") == true -> "3gp"
            mime?.startsWith("audio/") == true -> "m4a"
            else -> "mp4"
        }
        return ResolvedStream(streamUrl, ext, mime, height)
    }
}

private class NpDownloader(private val http: OkHttpClient) : Downloader() {
    override fun execute(request: Request): Response {
        val method = request.httpMethod().uppercase()
        val data = request.dataToSend()
        val body = if (data != null) {
            val ct = request.headers()["Content-Type"]?.firstOrNull()
                ?: request.headers()["content-type"]?.firstOrNull()
                ?: "application/json"
            data.toRequestBody(ct.toMediaTypeOrNull())
        } else null

        val rb = okhttp3.Request.Builder().url(request.url())
        rb.method(method, if (method == "GET" || method == "HEAD") null else (body ?: ByteArray(0).toRequestBody(null)))
        for ((name, values) in request.headers()) {
            when (name.lowercase()) {
                // OkHttp manages these itself; forwarding them breaks gzip
                // transparency and body framing
                "accept-encoding", "content-length", "transfer-encoding", "host", "connection" -> {}
                else -> for (v in values) rb.addHeader(name, v)
            }
        }
        http.newCall(rb.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            return Response(r.code, r.message, r.headers.toMultimap(), text, r.request.url.toString())
        }
    }
}
