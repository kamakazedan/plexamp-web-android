package io.github.kamakazedan.plexampweb.downloads

import io.github.kamakazedan.plexampweb.net.Http
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.net.UnknownHostException
import java.security.SecureRandom

/**
 * Downloads from the Plex server, asked as the player asks it: at the first of the
 * server's addresses that answers as it, with the player's sign-in, as the same player
 * (this phone's id with Plex, and the player's name in Plex). Converting a track is
 * asked for as the player streams one, and its segments kept as one file (TsDemuxer).
 *
 * The sign-in goes in each address, as the player's own requests carry it (its /pms/
 * takes it nowhere else): nothing here is logged.
 */
class PlexClient(
    private val account: DownloadAccount,
    private val clientId: String,
    private val version: String,
    private val stopped: () -> Boolean = { false },
) : PlexSource {
    /** The address that answered as the server, once one has. */
    @Volatile
    var uri: String? = null
        private set

    // ---------- playlists ----------

    override fun playlist(id: String): FetchedPlaylist? {
        require(DownloadStore.ID.matches(id))
        val info = getJson("/playlists/$id")?.let { firstOf(it, "Metadata") } ?: return null
        if (info.optString("playlistType", "audio") != "audio") return null
        val items = JSONArray()
        var start = 0
        while (items.length() < MAX_TRACKS) {
            val page = getJson("/playlists/$id/items", mapOf("X-Plex-Container-Start" to "$start", "X-Plex-Container-Size" to "$PAGE")) ?: return null
            val list = page.optJSONArray("Metadata") ?: JSONArray()
            for (i in 0 until list.length()) list.optJSONObject(i)?.let { items.put(trimmed(it)) }
            start += list.length()
            val total = if (page.has("totalSize")) page.optInt("totalSize") else -1
            if (list.length() == 0 || (total >= 0 && start >= total) || (total < 0 && list.length() < PAGE)) break
        }
        val tracks = (0 until items.length()).mapNotNull { TrackSpec.of(items.getJSONObject(it)) }
        val json = JSONObject().put("playlist", scalars(info)).put("items", items).toString()
        return FetchedPlaylist(id, info.optString("title").trim().ifEmpty { "Playlist" }, tracks, json)
    }

    // ---------- tracks ----------

    override fun download(spec: TrackSpec, to: File, progress: (Long) -> Unit) {
        val have = if (to.isFile) to.length() else 0L
        val headers = if (have > 0) mapOf("Range" to "bytes=$have-") else emptyMap()
        net {
            Http.stream(url(connect(), spec.partKey), headers, readTimeoutMs = 60_000) { r ->
                when (r.status) {
                    200 -> save(r.body, to, append = false, from = 0, total = r.header("Content-Length")?.toLongOrNull(), progress)
                    206 -> {
                        val range = CONTENT_RANGE.matchEntire(r.header("Content-Range").orEmpty())
                        if (range == null || range.groupValues[1].toLong() != have) {
                            to.delete()
                            throw PlexSource.TrackFailed("The server answered with another part of the file.")
                        }
                        save(r.body, to, append = true, from = have, total = range.groupValues[3].toLongOrNull(), progress)
                    }
                    // (Asked for what's past its end: whole already, or it's changed.)
                    416 -> if (UNSATISFIED.matchEntire(r.header("Content-Range").orEmpty())?.groupValues?.get(1)?.toLongOrNull() != have) {
                        to.delete()
                        throw PlexSource.TrackFailed("The file changed on the server.")
                    }
                    else -> refused(r.status)
                }
            }
        }
    }

    override fun convert(spec: TrackSpec, kbps: Int, to: File, progress: (Long) -> Unit): String {
        val base = connect()
        val session = "$clientId-${spec.id}-${randomHex()}"
        val start = url(
            base,
            "/audio/:/transcode/universal/start.m3u8",
            linkedMapOf(
                "path" to "/library/metadata/${spec.id}",
                "mediaIndex" to "0",
                "partIndex" to "0",
                "protocol" to "hls",
                "directPlay" to "0",
                "directStream" to "0",
                "directStreamAudio" to "0",
                "hasMDE" to "1",
                "musicBitrate" to "$kbps",
                "session" to session,
                "transcodeSessionId" to session,
                "X-Plex-Session-Identifier" to session,
                "X-Plex-Client-Profile-Extra" to PROFILE_EXTRA,
            ),
        )
        try {
            return net {
                BufferedOutputStream(FileOutputStream(to), BUFFER).use { out ->
                    val demuxer = TsDemuxer(out)
                    segments(start, base) { segment ->
                        Http.stream(segment, readTimeoutMs = 120_000) { r ->
                            if (r.status != 200) refused(r.status)
                            copy(r.body) { buffer, n -> demuxer.write(buffer, 0, n) }
                        }
                        demuxer.endOfSegment()
                        progress(demuxer.written)
                    }
                    demuxer.finish()
                    demuxer.codec!!.extension
                }
            }
        } finally {
            // (The server's left to stop it itself, where this doesn't reach it.)
            runCatching {
                Http.request(url(base, "/audio/:/transcode/universal/stop", mapOf("session" to session)), connectTimeoutMs = 5000, readTimeoutMs = 5000)
            }
        }
    }

    // Each segment of a conversion, in order: its playlist (HLS's), from the list of
    // versions it starts with, read again till it says it's whole.
    private fun segments(start: String, base: String, each: (String) -> Unit) {
        var at = start
        var text = playlistText(at)
        if (text.contains("#EXT-X-STREAM-INF")) {
            val variant = text.lineSequence().map { it.trim() }.dropWhile { !it.startsWith("#EXT-X-STREAM-INF") }.drop(1)
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: throw PlexSource.TrackFailed("The conversion had no stream.")
            at = sameServer(at, variant, base)
            text = playlistText(at)
        }
        val done = HashSet<String>()
        var waited = 0
        while (true) {
            val fresh = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { sameServer(at, it, base) }.filter { done.add(it) }.toList()
            if (done.size > MAX_SEGMENTS) throw PlexSource.TrackFailed("The conversion was too long.")
            for (segment in fresh) {
                if (stopped()) throw PlexSource.Stopped()
                each(segment)
            }
            if (text.contains("#EXT-X-ENDLIST")) return
            if (fresh.isEmpty() && ++waited > MAX_WAITS) throw PlexSource.TrackFailed("The conversion stopped part-way.")
            if (fresh.isNotEmpty()) waited = 0
            if (fresh.isEmpty()) Thread.sleep(WAIT_MS)
            text = playlistText(at)
        }
    }

    private fun playlistText(url: String): String {
        val r = Http.request(url, maxBytes = 1 shl 20, readTimeoutMs = 60_000)
        if (r.status != 200) refused(r.status)
        return r.text().takeIf { it.startsWith("#EXTM3U") } ?: throw PlexSource.TrackFailed("The conversion's playlist couldn't be read.")
    }

    // An address the server's playlist names, at the server: nowhere else.
    private fun sameServer(from: String, ref: String, base: String): String {
        val resolved = try {
            URI(from).resolve(ref).toString()
        } catch (e: Exception) {
            throw PlexSource.TrackFailed("The conversion named an address that isn't one.")
        }
        val origin = URI(base).let { "${it.scheme}://${it.rawAuthority}/" }
        if (!resolved.startsWith(origin)) throw PlexSource.TrackFailed("The conversion named another server.")
        return resolved
    }

    // ---------- covers ----------

    override fun cover(path: String, to: File) {
        val r = net {
            Http.request(
                url(connect(), "/photo/:/transcode", mapOf("width" to "$COVER_SIZE", "height" to "$COVER_SIZE", "minSize" to "1", "upscale" to "1", "url" to path)),
                headers = mapOf("Accept" to "image/*"),
                maxBytes = MAX_COVER,
            )
        }
        if (r.status != 200) refused(r.status)
        if (r.header("Content-Type")?.startsWith("image/") != true) throw PlexSource.TrackFailed("The cover wasn't a picture.")
        to.writeBytes(r.body)
    }

    // ---------- the server ----------

    /** The first of the server's addresses that answers as it. */
    fun connect(): String {
        uri?.let { return it }
        var refusedAll = true
        var last: Exception? = null
        for (candidate in account.uris) {
            if (stopped()) throw PlexSource.Stopped()
            try {
                val r = Http.request(url(candidate, "/identity"), headers = ACCEPT_JSON, maxBytes = 64 shl 10, connectTimeoutMs = 6000, readTimeoutMs = 10_000)
                if (r.status != 401) refusedAll = false
                val id = if (r.status == 200) JSONObject(r.text()).optJSONObject("MediaContainer")?.optString("machineIdentifier") else null
                if (id == account.serverId) {
                    uri = candidate
                    return candidate
                }
            } catch (e: IOException) {
                refusedAll = false
                last = e
            } catch (e: JSONException) {
                refusedAll = false
                last = e
            }
        }
        throw if (refusedAll) PlexSource.SignInRefused() else PlexSource.Unreachable(last)
    }

    // The MediaContainer of the server's answer, or null where it hasn't got that (404).
    private fun getJson(path: String, params: Map<String, String> = emptyMap()): JSONObject? {
        val r = net { Http.request(url(connect(), path, params), headers = ACCEPT_JSON, maxBytes = MAX_JSON, readTimeoutMs = 60_000) }
        if (r.status == 404) return null
        if (r.status != 200) refused(r.status)
        return try {
            JSONObject(r.text()).getJSONObject("MediaContainer")
        } catch (e: JSONException) {
            throw IOException("Your Plex server's answer couldn't be read.")
        }
    }

    // (The player's own X-Plex-* values last, so nothing else can stand in for them.)
    private fun url(base: String, path: String, params: Map<String, String> = emptyMap()): String {
        val all = LinkedHashMap(params)
        all["X-Plex-Product"] = PRODUCT
        all["X-Plex-Version"] = version
        all["X-Plex-Client-Identifier"] = clientId
        // The server picks how to convert from the platform: the player's, in the WebView.
        all["X-Plex-Platform"] = "Chrome"
        all["X-Plex-Device"] = "Android"
        all["X-Plex-Device-Name"] = account.device
        all["X-Plex-Token"] = account.token
        return base + path + "?" + all.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
    }

    // A connection that can't be made is the server out of reach (it's asked about again
    // next time): anything else, this one request's trouble.
    private inline fun <T> net(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        if (e is ConnectException || e is NoRouteToHostException || e is UnknownHostException || e is SocketTimeoutException) {
            uri = null
            throw PlexSource.Unreachable(e)
        }
        throw e
    }

    private fun refused(status: Int): Nothing =
        throw if (status == 401) PlexSource.SignInRefused() else PlexSource.TrackFailed("Your Plex server answered $status.")

    // (What comes is kept, however much of it: the rest is asked for next time.)
    private fun save(body: InputStream, to: File, append: Boolean, from: Long, total: Long?, progress: (Long) -> Unit) {
        var at = from
        BufferedOutputStream(FileOutputStream(to, append), BUFFER).use { out ->
            copy(body) { buffer, n ->
                out.write(buffer, 0, n)
                at += n
                progress(at)
            }
        }
        if (total != null && at != total) throw IOException("The download was cut short.")
    }

    private inline fun copy(body: InputStream, write: (ByteArray, Int) -> Unit) {
        val buffer = ByteArray(BUFFER)
        while (true) {
            if (stopped()) throw PlexSource.Stopped()
            val n = body.read(buffer)
            if (n < 0) break
            if (n > 0) write(buffer, n)
        }
    }

    companion object {
        const val PRODUCT = "plexamp-web"

        // As the player asks for a stream (plex.js transcodeUrl).
        private const val PROFILE_EXTRA =
            "add-transcode-target(type=musicProfile&context=streaming&protocol=hls&container=mpegts&audioCodec=aac,mp3)"

        private const val PAGE = 500
        private const val MAX_TRACKS = 10_000
        private const val MAX_JSON = 32 shl 20
        private const val MAX_COVER = 5 shl 20
        private const val COVER_SIZE = 600
        private const val MAX_SEGMENTS = 20_000
        private const val MAX_WAITS = 30
        private const val WAIT_MS = 2000L
        private const val BUFFER = 64 * 1024

        private val ACCEPT_JSON = mapOf("Accept" to "application/json")
        private val CONTENT_RANGE = Regex("^bytes (\\d+)-(\\d+)/(\\d+|\\*)$")
        private val UNSATISFIED = Regex("^bytes \\*/(\\d+)$")
        private val random = SecureRandom()

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        private fun randomHex(): String = ByteArray(6).also(random::nextBytes).joinToString("") { "%02x".format(it) }

        // An item as the player needs it offline: what it says of itself and of its first
        // file, without the rest (each file's streams, its path on the server).
        private fun trimmed(item: JSONObject): JSONObject {
            val out = scalars(item)
            firstOf(item, "Media")?.let { media ->
                val m = scalars(media)
                firstOf(media, "Part")?.let { part -> m.put("Part", JSONArray().put(scalars(part).apply { remove("file") })) }
                out.put("Media", JSONArray().put(m))
            }
            return out
        }

        private fun scalars(o: JSONObject): JSONObject {
            val out = JSONObject()
            for (key in o.keys()) {
                val value = o.opt(key)
                if (value !is JSONObject && value !is JSONArray) out.put(key, value)
            }
            return out
        }
    }
}
