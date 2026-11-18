package io.github.kamakazedan.plexampweb.downloads

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.Collections
import java.util.Random

/** PlexClient, against a stand-in Plex server on this computer. */
class PlexClientTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val token = "token-123456"
    private val clientId = "phone-1"
    private val asked: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val stops: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val file = ByteArray(100_000).also { Random(1).nextBytes(it) }
    private val frames = TsSamples.frames(40, 371)

    /** The first download of the file stops there, as a connection that drops. */
    private var cutAt = -1

    private lateinit var plex: HttpServer
    private lateinit var other: HttpServer

    @Before
    fun start() {
        plex = server("abc", ::answer)
        other = server("someone-else") { send(it, 404, "text/plain", ByteArray(0)) }
    }

    @After
    fun stop() {
        plex.stop(0)
        other.stop(0)
    }

    private fun uri(server: HttpServer) = "http://127.0.0.1:${server.address.port}"

    private fun client(vararg uris: String, sign: String = token) =
        PlexClient(DownloadAccount("abc", sign, "owner", uris.toList(), "Kitchen"), clientId, "1.0")

    private fun server(id: String, handle: (HttpExchange) -> Unit): HttpServer {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { ex ->
            try {
                asked += ex.requestURI.rawPath + "?" + (ex.requestURI.rawQuery ?: "")
                if (ex.requestURI.path == "/identity") json(ex, JSONObject().put("MediaContainer", JSONObject().put("machineIdentifier", id)))
                else handle(ex)
            } catch (e: IOException) {
                // (A download cut short on purpose.)
            } finally {
                runCatching { ex.close() }
            }
        }
        s.start()
        return s
    }

    private fun query(ex: HttpExchange): Map<String, String> = (ex.requestURI.rawQuery ?: "").split('&').filter { it.contains('=') }
        .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    private fun answer(ex: HttpExchange) {
        val path = ex.requestURI.path
        val q = query(ex)
        // (A conversion's files go by its session alone, as Plex's do.)
        if (!path.contains("/session/") && q["X-Plex-Token"] != token) return send(ex, 401, "text/plain", ByteArray(0))
        when {
            path == "/playlists/5" -> json(ex, JSONObject().put("MediaContainer", JSONObject().put("Metadata", JSONArray().put(PlexSamples.playlistInfo("5", "Road trip")))))
            path == "/playlists/5/items" -> {
                val start = q["X-Plex-Container-Start"]!!.toInt()
                val all = listOf(PlexSamples.track("1"), JSONObject().put("ratingKey", "2").put("type", "episode"), PlexSamples.track("3"))
                val page = if (start == 0) all.take(2) else all.drop(start)
                json(ex, JSONObject().put("MediaContainer", JSONObject().put("totalSize", all.size).put("Metadata", JSONArray(page))))
            }
            path.startsWith("/playlists/") -> send(ex, 404, "text/plain", ByteArray(0))
            path == "/library/parts/1/1700000000/file.flac" -> part(ex)
            path == "/audio/:/transcode/universal/start.m3u8" -> {
                assertEquals("/library/metadata/1", q["path"])
                assertEquals("128", q["musicBitrate"])
                m3u8(ex, "#EXTM3U\n#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=128000\nsession/${q["session"]}/base/index.m3u8\n")
            }
            path.endsWith("/base/index.m3u8") ->
                m3u8(ex, "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\n00000.ts\n#EXTINF:10,\n00001.ts\n#EXT-X-ENDLIST\n")
            path.endsWith("/base/00000.ts") -> send(ex, 200, "video/mp2t", TsSamples.segment(frames.take(25)))
            path.endsWith("/base/00001.ts") -> send(ex, 200, "video/mp2t", TsSamples.segment(frames.drop(25)))
            path == "/audio/:/transcode/universal/stop" -> {
                stops += q["session"]!!
                send(ex, 200, "text/plain", ByteArray(0))
            }
            path == "/photo/:/transcode" -> send(ex, 200, "image/jpeg", "jpeg of ${q["url"]}".toByteArray())
            else -> send(ex, 404, "text/plain", ByteArray(0))
        }
    }

    // The track's file, a part of it where that's asked for.
    private fun part(ex: HttpExchange) {
        val range = ex.requestHeaders.getFirst("Range")?.let { ByteRange.of(it, file.size.toLong()) }
        if (range == null) {
            ex.responseHeaders.add("Content-Type", "audio/flac")
            ex.sendResponseHeaders(200, file.size.toLong())
            val upTo = if (cutAt >= 0) cutAt.also { cutAt = -1 } else file.size
            ex.responseBody.write(file, 0, upTo)
            return
        }
        if (range == ByteRange.UNSATISFIABLE) {
            ex.responseHeaders.add("Content-Range", "bytes */${file.size}")
            return send(ex, 416, "text/plain", ByteArray(0))
        }
        ex.responseHeaders.add("Content-Range", range.contentRange(file.size.toLong()))
        send(ex, 206, "audio/flac", file.copyOfRange(range.start.toInt(), range.end.toInt() + 1))
    }

    private fun json(ex: HttpExchange, body: JSONObject) = send(ex, 200, "application/json", body.toString().toByteArray())

    private fun m3u8(ex: HttpExchange, text: String) = send(ex, 200, "application/vnd.apple.mpegurl", text.toByteArray())

    private fun send(ex: HttpExchange, status: Int, type: String, body: ByteArray) {
        ex.responseHeaders.add("Content-Type", type)
        ex.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) ex.responseBody.write(body)
    }

    private fun closedAddress(): String = ServerSocket(0).use { "http://127.0.0.1:${it.localPort}" }

    @Test
    fun theFirstAddressThatAnswersAsTheServerIsUsed() {
        val client = client(closedAddress(), uri(other), uri(plex))
        assertEquals(uri(plex), client.connect())
        assertTrue(asked.any { it.startsWith("/identity?") && it.contains("X-Plex-Client-Identifier=phone-1") })
    }

    @Test
    fun noneAnswering() {
        assertThrows(PlexSource.Unreachable::class.java) { client(closedAddress(), uri(other)).connect() }
    }

    @Test
    fun aPlaylistPageByPageWithWhatThePlayerNeedsOffline() {
        val playlist = client(uri(plex)).playlist("5")!!
        assertEquals("Road trip", playlist.title)
        assertEquals(listOf("1", "3"), playlist.tracks.map { it.id })
        val json = JSONObject(playlist.json)
        assertEquals("Road trip", json.getJSONObject("playlist").getString("title"))
        val items = json.getJSONArray("items")
        assertEquals(3, items.length())
        val part = items.getJSONObject(0).getJSONArray("Media").getJSONObject(0).getJSONArray("Part").getJSONObject(0)
        assertEquals("/library/parts/1/1700000000/file.flac", part.getString("key"))
        // (Not the file's streams, nor where it is on the server.)
        assertFalse(part.has("Stream"))
        assertFalse(part.has("file"))
        val requests = asked.filter { it.startsWith("/playlists/5/items") }
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.contains("X-Plex-Token=$token") && it.contains("X-Plex-Device-Name=Kitchen") })
    }

    @Test
    fun aPlaylistTheServerHasntGot() {
        assertNull(client(uri(plex)).playlist("6"))
    }

    @Test
    fun aRefusedSignIn() {
        assertThrows(PlexSource.SignInRefused::class.java) { client(uri(plex), sign = "another-token").playlist("5") }
    }

    @Test
    fun aDownloadGoesOnFromWhereItGotTo() {
        val spec = TrackSpec.of(PlexSamples.track("1", size = file.size.toLong()))!!
        val to = temp.newFile("1.part")
        cutAt = 40_000
        val client = client(uri(plex))
        assertThrows(IOException::class.java) { client.download(spec, to) {} }
        assertEquals(40_000L, to.length())
        var progress = 0L
        client.download(spec, to) { progress = it }
        assertArrayEquals(file, to.readBytes())
        assertEquals(file.size.toLong(), progress)
        assertTrue(asked.any { it.startsWith("/library/parts/1/") })
    }

    @Test
    fun aDownloadAlreadyWhole() {
        val spec = TrackSpec.of(PlexSamples.track("1", size = file.size.toLong()))!!
        val to = temp.newFile("1.part")
        to.writeBytes(file)
        client(uri(plex)).download(spec, to) {}
        assertArrayEquals(file, to.readBytes())
    }

    @Test
    fun aConvertedTrackIsOneFile() {
        val spec = TrackSpec.of(PlexSamples.track("1"))!!
        val to = temp.newFile("1.part")
        val extension = client(uri(plex)).convert(spec, 128, to) {}
        assertEquals("aac", extension)
        assertArrayEquals(frames.fold(ByteArray(0)) { all, f -> all + f }, to.readBytes())
        // The server's told it's done with.
        assertEquals(1, stops.size)
        assertTrue(stops[0].startsWith("phone-1-1-"))
        // Its segments are asked for without the sign-in, as the player asks for them.
        assertTrue(asked.filter { it.contains("/session/") }.none { it.contains("X-Plex-Token") })
    }

    @Test
    fun aCover() {
        val to = temp.newFile("cover.part")
        client(uri(plex)).cover("/library/metadata/91/thumb/1", to)
        assertEquals("jpeg of /library/metadata/91/thumb/1", to.readText())
    }
}
