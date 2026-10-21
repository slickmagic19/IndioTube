package com.indiotube.app.data

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.StatFs
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

enum class DlStatus { ACTIVE, PAUSED, FAILED }

// One tracked download. bytesDone/totalBytes are the truth; progress is 0f..1f
// once the size is known, negative while the stream is still being resolved.
data class DownloadTask(
    val video: VideoItem,
    val status: DlStatus,
    val bytesDone: Long = 0,
    val totalBytes: Long = 0,
    val error: String? = null,
    val height: Int? = null,
) {
    val progress: Float get() = if (totalBytes > 0) (bytesDone.toFloat() / totalBytes).coerceIn(0f, 1f) else -1f
    val indeterminate: Boolean get() = totalBytes <= 0
}

// Broadcast so any layer (or none at all) can surface toasts/notifications.
sealed interface DlEvent {
    data class Started(val video: VideoItem) : DlEvent
    data class Finished(val video: VideoItem) : DlEvent
    data class Failed(val video: VideoItem, val message: String) : DlEvent
    data class Paused(val video: VideoItem) : DlEvent
    data class Resumed(val video: VideoItem) : DlEvent
    data class Cancelled(val video: VideoItem) : DlEvent
}

private enum class Intent { NONE, PAUSE, CANCEL }

internal fun downloadBlockReason(online: Boolean, wifiOnly: Boolean, unmetered: Boolean): String? = when {
    !online -> "You're offline"
    wifiOnly && !unmetered -> "Connect to Wi-Fi or turn off Wi-Fi only"
    else -> null
}

// Offline downloads: one progressive muxed video file (+ thumbnail) per video.
// The writer streams into a .part file and resumes with HTTP Range requests,
// so pausing, network hiccups mid-transfer and app restarts all continue from
// where they stopped instead of restarting or failing.
class Downloads(
    context: Context,
    private val resolver: StreamResolver,
    private val http: OkHttpClient,
    private val online: () -> Boolean = { true },
    private val unmetered: () -> Boolean = { true },
    private val wifiOnly: () -> Boolean = { false },
) {
    private val dir = File(context.filesDir, "downloads").apply { mkdirs() }
    private val indexFile = File(context.filesDir, "downloads.json")
    private val queueFile = File(context.filesDir, "download_queue.json")
    private val indexSerializer = MapSerializer(String.serializer(), SavedDownload.serializer())
    private val queueSerializer = MapSerializer(String.serializer(), QueueEntry.serializer())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _items = MutableStateFlow<Map<String, SavedDownload>>(emptyMap())
    val items: StateFlow<Map<String, SavedDownload>> = _items.asStateFlow()

    private val _tasks = MutableStateFlow<Map<String, DownloadTask>>(emptyMap())
    val tasks: StateFlow<Map<String, DownloadTask>> = _tasks.asStateFlow()

    private val _events = MutableSharedFlow<DlEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<DlEvent> = _events.asSharedFlow()

    private val jobs = HashMap<String, Job>()
    private val calls = HashMap<String, Call>()
    private val intents = HashMap<String, Intent>()
    private var queue: Map<String, QueueEntry> = emptyMap()

    init {
        load()
        restoreQueue()
        cleanOrphans()
    }

    private companion object {
        const val MAX_ATTEMPTS = 6
        const val FRESH_FILE_MS = 60_000L
    }

    // ---- index (completed downloads) ------------------------------------

    private fun load() {
        try {
            if (indexFile.exists()) {
                _items.value = SyncJson.decodeFromString(indexSerializer, indexFile.readText())
            }
        } catch (_: Exception) {
            // unreadable index — keep files on disk, rebuild lazily
        }
        val pruned = _items.value.filterValues { it.fileName.isNotEmpty() && File(dir, it.fileName).exists() }
        if (pruned.size != _items.value.size) {
            _items.value = pruned
            saveIndex()
        }
    }

    private fun saveIndex() {
        writeAtomic(indexFile, SyncJson.encodeToString(indexSerializer, _items.value))
    }

    // ---- queue (in-flight downloads) ------------------------------------

    private fun loadQueue() {
        queue = try {
            if (queueFile.exists()) SyncJson.decodeFromString(queueSerializer, queueFile.readText()) else emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveQueue() {
        writeAtomic(queueFile, SyncJson.encodeToString(queueSerializer, queue))
    }

    private fun writeAtomic(target: File, text: String) {
        try {
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(target)) {
                target.writeText(text)
                tmp.delete()
            }
        } catch (_: Exception) { }
    }

    // Downloads interrupted by a kill (or a crash) come back as PAUSED tasks
    // backed by their .part file, so the user can resume with one tap.
    private fun restoreQueue() {
        loadQueue()
        val loaded = queue.size
        val keep = LinkedHashMap<String, QueueEntry>()
        val restored = LinkedHashMap<String, DownloadTask>()
        for ((id, entry) in queue) {
            val parts = findParts(id)
            val bytes = parts.sumOf { it.length() }
            if (!_items.value.containsKey(id) && bytes > 0) {
                restored[id] = DownloadTask(
                    video = entry.video,
                    status = DlStatus.PAUSED,
                    bytesDone = bytes,
                    totalBytes = entry.totalBytes,
                    height = entry.height,
                )
                keep[id] = entry
            }
        }
        queue = keep
        if (restored.isNotEmpty()) _tasks.value = _tasks.value + restored
        if (keep.size != loaded) saveQueue()
    }

    private fun cleanOrphans() {
        try {
            val known = _items.value.values.map { it.fileName }.toHashSet() +
                _items.value.values.mapNotNull { it.thumbFile }.toHashSet() +
                _items.value.values.mapNotNull { it.thumbFile?.removeSuffix(".jpg")?.plus(".webp") }.toHashSet()
            val queued = synchronized(this) { queue.keys.toSet() }
            dir.listFiles()?.forEach { f ->
                val stem = f.name.substringBeforeLast('.')
                val orphan = if (f.name.endsWith(".part")) {
                    f.name.substringBefore('.') !in queued
                } else {
                    f.name !in known && stem !in queued
                }
                // keep very fresh files — a download may be in flight right now
                if (orphan && System.currentTimeMillis() - f.lastModified() > FRESH_FILE_MS) f.delete()
            }
        } catch (_: Exception) { }
    }

    private fun findParts(id: String): List<File> = try {
        dir.listFiles()
            ?.filter { it.name.startsWith("$id.") && it.name.endsWith(".part") }
            ?.sortedBy { it.name }
            .orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    // ---- public state ----------------------------------------------------

    fun entry(videoId: String): SavedDownload? = _items.value[videoId]

    fun file(videoId: String): File? = _items.value[videoId]?.let { e ->
        if (e.fileName.isEmpty()) null else File(dir, e.fileName).takeIf { it.exists() }
    }

    fun thumb(videoId: String): File? = _items.value[videoId]?.let { e ->
        e.thumbFile?.let { File(dir, it).takeIf { f -> f.exists() } }
    }

    fun isDownloaded(videoId: String): Boolean = file(videoId) != null

    fun isActive(videoId: String): Boolean = _tasks.value.containsKey(videoId)

    val downloadedIds: Set<String> get() = _items.value.keys

    fun storageFreeBytes(): Long = try {
        StatFs(dir.absolutePath).availableBytes
    } catch (_: Exception) {
        0L
    }

    // ---- controls ----------------------------------------------------------

    fun start(video: VideoItem, height: Int? = null) {
        val id = video.videoId
        if (video.membersOnly) {
            _events.tryEmit(DlEvent.Failed(video, "Members-only videos can't be downloaded"))
            return
        }
        downloadBlockReason(online(), wifiOnly(), unmetered())?.let { reason ->
            _events.tryEmit(DlEvent.Failed(video, reason))
            return
        }
        synchronized(this) {
            if (_items.value.containsKey(id) || _tasks.value.containsKey(id)) return
            intents[id] = Intent.NONE
            queue = queue + (id to QueueEntry(video, 0, System.currentTimeMillis(), height))
            saveQueue()
            _tasks.value = _tasks.value + (id to DownloadTask(video, DlStatus.ACTIVE, height = height))
        }
        _events.tryEmit(DlEvent.Started(video))
        launchWorker(id, video, height)
    }

    fun pause(id: String) {
        val call: Call?
        synchronized(this) {
            val task = _tasks.value[id] ?: return
            if (task.status != DlStatus.ACTIVE) return
            intents[id] = Intent.PAUSE
            call = calls[id]
        }
        call?.cancel()
    }

    fun resume(id: String) {
        val video: VideoItem
        val height: Int?
        synchronized(this) {
            val task = _tasks.value[id] ?: return
            if (task.status == DlStatus.ACTIVE || jobs.containsKey(id)) return
            downloadBlockReason(online(), wifiOnly(), unmetered())?.let { reason ->
                _tasks.value = _tasks.value + (id to task.copy(status = DlStatus.FAILED, error = reason))
                _events.tryEmit(DlEvent.Failed(task.video, reason))
                return
            }
            intents[id] = Intent.NONE
            _tasks.value = _tasks.value + (id to task.copy(status = DlStatus.ACTIVE, error = null))
            video = task.video
            height = task.height
        }
        _events.tryEmit(DlEvent.Resumed(video))
        launchWorker(id, video, height)
    }

    fun cancel(id: String) {
        val video: VideoItem
        val call: Call?
        val workerAlive: Boolean
        synchronized(this) {
            val task = _tasks.value[id] ?: return
            video = task.video
            call = calls[id]
            workerAlive = jobs.containsKey(id)
            if (workerAlive) intents[id] = Intent.CANCEL
        }
        if (workerAlive) {
            call?.cancel()
        } else {
            // paused/failed task with no worker — clean up right here
            finishCancel(id, video)
        }
    }

    fun delete(videoId: String) {
        val e = _items.value[videoId] ?: return
        try { if (e.fileName.isNotEmpty()) File(dir, e.fileName).delete() } catch (_: Exception) { }
        try { e.thumbFile?.let { File(dir, it).delete() } } catch (_: Exception) { }
        synchronized(this) {
            _items.value = _items.value - videoId
            saveIndex()
        }
    }

    fun pauseAll() {
        _tasks.value.values
            .filter { it.status == DlStatus.ACTIVE }
            .map { it.video.videoId }
            .forEach(::pause)
    }

    fun resumeAll() {
        _tasks.value.values
            .filter { it.status != DlStatus.ACTIVE }
            .map { it.video.videoId }
            .forEach(::resume)
    }

    fun clearAll() {
        _tasks.value.keys.toList().forEach(::cancel)
        _items.value.keys.toList().forEach(::delete)
    }

    // ---- worker --------------------------------------------------------------

    private fun launchWorker(id: String, video: VideoItem, height: Int?) {
        // LAZY so the job cannot complete (and remove itself) before it is
        // registered — otherwise a fast failure could leave a stale job entry
        // that would block resume/cancel forever
        val job = scope.launch(start = CoroutineStart.LAZY) { runWorker(id, video, height) }
        synchronized(this) { jobs[id] = job }
        job.start()
    }

    private fun intent(id: String): Intent = synchronized(this) { intents[id] ?: Intent.NONE }

    private class PartComplete : Exception()

    private data class DownloadPart(val stream: ResolvedStream, val file: File)

    private suspend fun runWorker(id: String, video: VideoItem, height: Int?) {
        var attempt = 0
        try {
            while (true) {
                synchronized(this) { if (!_tasks.value.containsKey(id)) return }
                when (intent(id)) {
                    Intent.CANCEL -> { finishCancel(id, video); return }
                    Intent.PAUSE -> { finishPause(id, video); return }
                    Intent.NONE -> {}
                }
                if (wifiOnly() && !unmetered()) {
                    finishPause(id, video)
                    return
                }
                try {
                    // after a failure a fresh URL helps: cached ones may have expired
                    val sources = resolver.resolveDownloadBlocking(id, height, forceRefresh = attempt > 0)
                    val parts = prepareParts(id, sources)
                    val totals = HashMap<File, Long>()
                    for (spec in parts) {
                        try {
                            downloadPart(id, video, spec, parts, totals, height)
                        } catch (_: PartComplete) {
                        }
                    }
                    if (sources.audio != null) {
                        val out = File(dir, "$id.mp4")
                        muxParts(
                            video = parts.first { it.stream.isVideo }.file,
                            audio = parts.first { it.stream.isAudio }.file,
                            target = out,
                        )
                        if (intent(id) == Intent.CANCEL) {
                            out.delete()
                            finishCancel(id, video)
                            return
                        }
                        parts.forEach { it.file.delete() }
                        finishSaved(id, video, out, sources.video.height)
                    } else {
                        complete(id, video, parts.first().file, sources.video.height)
                    }
                    return
                } catch (e: CancellationException) {
                    // the process is going away — keep the .part files and queue
                    // entry so this download comes back as a resumable paused task
                    throw e
                } catch (e: Exception) {
                    when (intent(id)) {
                        Intent.CANCEL -> { finishCancel(id, video); return }
                        Intent.PAUSE -> { finishPause(id, video); return }
                        Intent.NONE -> {}
                    }
                    attempt++
                    if (!online()) {
                        finishFailed(id, video, "You're offline")
                        return
                    }
                    if (attempt > MAX_ATTEMPTS) {
                        finishFailed(id, video, e.message ?: "Download failed")
                        return
                    }
                    delay(650L * attempt)
                } finally {
                    synchronized(this) { calls.remove(id) }
                }
            }
        } finally {
            synchronized(this) {
                jobs.remove(id)
                intents.remove(id)
            }
        }
    }

    private fun prepareParts(id: String, sources: DownloadSources): List<DownloadPart> {
        synchronized(this) {
            val existing = findParts(id)
            val adaptive = sources.audio != null
            if (adaptive) {
                val audioName = "$id.audio.${sources.audio!!.ext}.part"
                val videoName = "$id.video.${sources.video.ext}.part"
                existing.filterNot { it.name == audioName || it.name == videoName }
                    .forEach { it.delete() }
                return listOf(
                    DownloadPart(sources.audio, existing.firstOrNull { it.name == audioName } ?: File(dir, audioName)),
                    DownloadPart(sources.video, existing.firstOrNull { it.name == videoName } ?: File(dir, videoName)),
                )
            }
            val singleName = "$id.${sources.video.ext}.part"
            existing.filterNot { it.name == singleName }.forEach { it.delete() }
            return listOf(
                DownloadPart(sources.video, existing.firstOrNull { it.name == singleName } ?: File(dir, singleName)),
            )
        }
    }

    private fun downloadPart(
        id: String,
        video: VideoItem,
        spec: DownloadPart,
        allParts: List<DownloadPart>,
        totals: MutableMap<File, Long>,
        height: Int?,
    ) {
        var copied = spec.file.length()
        var total = totals[spec.file] ?: 0L
        val req = Request.Builder().url(spec.stream.url)
        if (copied > 0) req.header("Range", "bytes=$copied-")
        val call = http.newCall(req.build())
        synchronized(this) { calls[id] = call }
        if (intent(id) != Intent.NONE) call.cancel()
        call.execute().use { r ->
            if (r.code == 416 && copied > 0) {
                val end = r.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
                if (end != null && end > 0 && copied >= end) {
                    totals[spec.file] = end
                    throw PartComplete()
                }
                throw IOException("Download failed (HTTP 416)")
            }
            if (!r.isSuccessful) throw IOException("Download failed (HTTP ${r.code})")
            val body = r.body ?: throw IOException("Download failed (empty response)")
            val contentRangeTotal = r.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
            val contentLength = body.contentLength()
            if (r.code == 200) {
                // server ignored the range (or fresh start) — never append twice
                if (copied > 0) {
                    spec.file.delete()
                    copied = 0
                }
                if (contentLength > 0) total = contentLength
            } else if (contentRangeTotal != null && contentRangeTotal > 0) {
                total = contentRangeTotal
            }
            if (total > 0) totals[spec.file] = total
            val allTotalsKnown = allParts.all { (totals[it.file] ?: 0) > 0 }
            val combinedTotal = if (allTotalsKnown) allParts.sumOf { totals[it.file] ?: 0 } else 0L
            val doneBefore = allParts.sumOf { it.file.length() } - copied
            if (combinedTotal > 0) {
                persistTotal(id, video, combinedTotal, height)
                updateTask(id) {
                    it.copy(
                        status = DlStatus.ACTIVE,
                        totalBytes = combinedTotal,
                        bytesDone = doneBefore + copied,
                        error = null,
                    )
                }
            } else {
                updateTask(id) {
                    it.copy(
                        status = DlStatus.ACTIVE,
                        totalBytes = 0,
                        bytesDone = doneBefore + copied,
                        error = null,
                    )
                }
            }
            FileOutputStream(spec.file, true).use { out ->
                copyWithProgress(body.byteStream(), out, combinedTotal, doneBefore) { done ->
                    updateTask(id) { it.copy(bytesDone = done) }
                }
            }
            val have = spec.file.length()
            if (total > 0 && have < total) {
                // short read with no error — retry from the current offset
                throw IOException("Download stalled near the end")
            }
            if (total <= 0) totals[spec.file] = have
        }
    }

    private fun muxParts(video: File, audio: File, target: File) {
        if (target.exists()) target.delete()
        val muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        try {
            videoExtractor.setDataSource(video.absolutePath)
            audioExtractor.setDataSource(audio.absolutePath)
            val videoTrack = firstTrack(videoExtractor, "video/")
                ?: throw IOException("Downloaded video has no video track")
            val audioTrack = firstTrack(audioExtractor, "audio/")
                ?: throw IOException("Downloaded audio has no audio track")
            videoExtractor.selectTrack(videoTrack)
            audioExtractor.selectTrack(audioTrack)
            val videoOut = muxer.addTrack(videoExtractor.getTrackFormat(videoTrack))
            val audioOut = muxer.addTrack(audioExtractor.getTrackFormat(audioTrack))
            muxer.start()
            val buffer = ByteBuffer.allocateDirect(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var videoDone = false
            var audioDone = false
            while (!videoDone || !audioDone) {
                val videoTime = if (!videoDone) videoExtractor.sampleTime else -1L
                val audioTime = if (!audioDone) audioExtractor.sampleTime else -1L
                if (videoTime < 0) videoDone = true
                if (audioTime < 0) audioDone = true
                if (videoDone && audioDone) break
                val useVideo = !videoDone && (audioDone || videoTime <= audioTime)
                val extractor = if (useVideo) videoExtractor else audioExtractor
                val outputTrack = if (useVideo) videoOut else audioOut
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    if (useVideo) videoDone = true else audioDone = true
                    continue
                }
                info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
                muxer.writeSampleData(outputTrack, buffer, info)
                extractor.advance()
            }
            muxer.stop()
        } catch (e: Exception) {
            if (target.exists()) target.delete()
            throw if (e is IOException) e else IOException(e.message ?: "Couldn't combine video and audio", e)
        } finally {
            try { videoExtractor.release() } catch (_: Exception) { }
            try { audioExtractor.release() } catch (_: Exception) { }
            try { muxer.release() } catch (_: Exception) { }
        }
    }

    private fun firstTrack(extractor: MediaExtractor, mimePrefix: String): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return i
        }
        return null
    }

    private fun persistTotal(id: String, video: VideoItem, total: Long, height: Int?) {
        if (total <= 0) return
        synchronized(this) {
            val e = queue[id] ?: QueueEntry(video, 0, System.currentTimeMillis(), height)
            if (e.totalBytes == total && queue.containsKey(id)) return
            queue = queue + (id to e.copy(video = video, totalBytes = total))
            saveQueue()
        }
    }

    private fun updateTask(id: String, transform: (DownloadTask) -> DownloadTask) {
        synchronized(this) {
            val t = _tasks.value[id] ?: return
            _tasks.value = _tasks.value + (id to transform(t))
        }
    }

    private fun complete(id: String, video: VideoItem, part: File, height: Int?) {
        val target = File(dir, part.name.removeSuffix(".part"))
        try {
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
        } catch (e: Exception) {
            finishFailed(id, video, e.message ?: "Couldn't save the file")
            return
        }
        finishSaved(id, video, target, height)
    }

    private fun finishSaved(id: String, video: VideoItem, target: File, height: Int?) {
        val thumbFile = saveThumbnail(id, video.thumbnail)
        val dl = SavedDownload(
            video = video,
            bytes = target.length(),
            at = System.currentTimeMillis(),
            fileName = target.name,
            thumbFile = thumbFile,
            height = height,
        )
        synchronized(this) {
            _items.value = _items.value + (id to dl)
            _tasks.value = _tasks.value - id
            queue = queue - id
            saveIndex()
            saveQueue()
        }
        _events.tryEmit(DlEvent.Finished(video))
    }

    // Best effort: the .jpg next to the video makes the Downloads screen work
    // fully offline (no image cache required).
    private fun saveThumbnail(id: String, url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            val req = Request.Builder().url(url).build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                val name = "$id.jpg"
                FileOutputStream(File(dir, name)).use { out ->
                    r.body?.byteStream()?.copyTo(out) ?: return null
                }
                name
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun finishPause(id: String, video: VideoItem) {
        val bytes = findParts(id).sumOf { it.length() }
        synchronized(this) {
            val t = _tasks.value[id] ?: return
            _tasks.value = _tasks.value + (id to t.copy(status = DlStatus.PAUSED, bytesDone = bytes, error = null))
        }
        _events.tryEmit(DlEvent.Paused(video))
    }

    private fun finishCancel(id: String, video: VideoItem) {
        findParts(id).forEach {
            try { it.delete() } catch (_: Exception) { }
        }
        synchronized(this) {
            _tasks.value = _tasks.value - id
            queue = queue - id
            saveQueue()
        }
        _events.tryEmit(DlEvent.Cancelled(video))
    }

    private fun finishFailed(id: String, video: VideoItem, message: String) {
        synchronized(this) {
            val t = _tasks.value[id] ?: return
            _tasks.value = _tasks.value + (id to t.copy(status = DlStatus.FAILED, error = message))
        }
        _events.tryEmit(DlEvent.Failed(video, message))
    }
}

// Streams input to output, reporting the ABSOLUTE number of bytes done at most
// once per whole percent (plus one final call). startOffset is the number of
// bytes already on disk when resuming from a Range request. When totalBytes is
// unknown (<= 0) no progress is reported and the UI stays indeterminate.
internal fun copyWithProgress(
    input: InputStream,
    output: OutputStream,
    totalBytes: Long,
    startOffset: Long = 0,
    onProgress: (Long) -> Unit,
): Long {
    val buf = ByteArray(64 * 1024)
    var copied = 0L
    var lastPct = -1
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        output.write(buf, 0, n)
        copied += n
        if (totalBytes > 0) {
            val done = startOffset + copied
            val pct = ((done * 100) / totalBytes).toInt()
            if (pct > lastPct) {
                lastPct = pct
                onProgress(done)
            }
        }
    }
    if (totalBytes > 0) onProgress(startOffset + copied)
    return copied
}
