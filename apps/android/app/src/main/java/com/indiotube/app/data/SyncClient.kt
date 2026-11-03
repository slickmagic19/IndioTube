package com.indiotube.app.data

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class SyncOutcome(
    val pcName: String,
    val received: SyncStats,
    val sent: SyncStats,
)

// Phone side of the desktop pairing protocol (electron/sync.js):
// 1. GET  /indio/v1/snapshot?token=…  → the PC's library
// 2. merge into the local library (add-only)
// 3. POST /indio/v1/merge?token=…     → our library, PC merges it back
class SyncClient(private val http: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /** Decodes the QR payload and runs the whole round trip. Returns the
     * merged library + subscriptions and a two-sided stats summary. */
    suspend fun run(
        payloadJson: String,
        local: LibraryFile,
        localSubs: List<Subscription>,
        deviceName: String,
    ): Triple<LibraryFile, List<Subscription>, SyncOutcome> = withContext(Dispatchers.IO) {
        val pairing = parsePairing(payloadJson)
        if (pairing.host.isBlank() || pairing.port <= 0 || pairing.token.isBlank()) {
            throw IOException("That QR code isn't an IndioTube pairing code.")
        }
        if (pairing.v > 1) {
            throw IOException("This pairing code is from a newer IndioTube — update this app first.")
        }

        val base = "http://${pairing.host}:${pairing.port}/indio/v1"

        val snapRoot = json.parseToJsonElement(get("$base/snapshot?token=${pairing.token}")).jsonObject
        if (snapRoot["ok"]?.jsonPrimitive?.contentOrNullBool() == false) {
            throw IOException(snapRoot["error"]?.jsonPrimitive?.content ?: "The PC refused to share its library.")
        }
        val incoming = try {
            json.decodeFromJsonElement(
                SyncSnapshot.serializer(),
                snapRoot["snapshot"] ?: throw IOException("The PC sent an unreadable library."),
            )
        } catch (e: Exception) {
            if (e is IOException) throw e
            throw IOException("The PC's library couldn't be read (version mismatch?).")
        }

        val merged = SyncMerge.merge(local, localSubs, incoming)
        val mergedLibrary = merged.library
        val mergedSubs = merged.subscriptions
        val received = merged.stats

        // Push the merged phone snapshot back; the PC merges it add-only too.
        val snapshot = SyncMerge.snapshot(deviceName, mergedLibrary, mergedSubs)
        val body = json.encodeToString(SyncSnapshot.serializer(), snapshot)
        val respRoot = json.parseToJsonElement(post("$base/merge?token=${pairing.token}", body)).jsonObject
        if (respRoot["ok"]?.jsonPrimitive?.contentOrNullBool() == false) {
            throw IOException(respRoot["error"]?.jsonPrimitive?.content ?: "The PC couldn't finish the merge.")
        }
        val sent = readStats(respRoot["stats"])

        Triple(
            mergedLibrary,
            mergedSubs,
            SyncOutcome(
                pcName = pairing.name?.takeIf { it.isNotBlank() } ?: "the PC",
                received = received,
                sent = sent,
            ),
        )
    }

    /** Accepts the raw QR text, and the manual "host port token" fallback. */
    fun parsePairing(text: String): SyncPairing {
        val trimmed = text.trim()
        if (trimmed.startsWith("{")) {
            val pairing = try {
                json.decodeFromString(SyncPairing.serializer(), trimmed)
            } catch (_: Exception) {
                throw IOException("That QR code isn't an IndioTube pairing code.")
            }
            if (pairing.indio != "sync") {
                throw IOException("That QR code isn't an IndioTube pairing code.")
            }
            return pairing
        }
        // Manual fallback: "192.168.1.20 47711 0a1b2c3d…" (space or colon separated).
        val parts = trimmed.split(Regex("[\\s:]+")).filter { it.isNotBlank() }
        if (parts.size < 3) throw IOException("Enter the PC's address, port and token, separated by spaces.")
        val host = parts[0]
        val port = parts[1].toIntOrNull() ?: throw IOException("That port doesn't look right.")
        val token = parts[2]
        return SyncPairing(indio = "sync", v = 1, host = host, port = port, token = token, name = parts.getOrNull(3))
    }

    private fun readStats(el: kotlinx.serialization.json.JsonElement?): SyncStats {
        val stats = SyncStats()
        val obj = el?.jsonObject ?: return stats
        fun i(key: String) = obj[key]?.jsonPrimitive?.intOrNull ?: 0
        stats.historyAdded = i("historyAdded")
        stats.watchLaterAdded = i("watchLaterAdded")
        stats.likedAdded = i("likedAdded")
        stats.playlistsAdded = i("playlistsAdded")
        stats.playlistsMerged = i("playlistsMerged")
        stats.videosAdded = i("videosAdded")
        stats.subsAdded = i("subsAdded")
        return stats
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullBool(): Boolean? =
        content.toBooleanStrictOrNull() ?: (content == "true")

    private fun get(url: String): String {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException(errorFor(r.code, text))
            return text
        }
    }

    private fun post(url: String, json: String): String {
        val req = Request.Builder().url(url).post(json.toRequestBody(JSON_MEDIA)).build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException(errorFor(r.code, text))
            return text
        }
    }

    private fun errorFor(code: Int, body: String): String {
        try {
            val msg = json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content
            if (!msg.isNullOrBlank()) return msg
        } catch (_: Exception) { }
        return when (code) {
            401 -> "The pairing window expired. Open Sync on the PC for a fresh QR code."
            404 -> "The PC isn't running an IndioTube sync right now."
            else -> "The PC replied with an error (HTTP $code)."
        }
    }
}
