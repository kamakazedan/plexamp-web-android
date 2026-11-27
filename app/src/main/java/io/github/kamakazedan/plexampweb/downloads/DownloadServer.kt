package io.github.kamakazedan.plexampweb.downloads

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Downloads, as the player's page asks for them, at the player's own address (so its
 * audio and fetch() take them as its own): see GuiServer, which hands these on.
 *
 *     __plexamp-web-android/downloads/track/<id>         a track's file (a part of it, where asked: as a server would)
 *     __plexamp-web-android/downloads/playlist/<id>      a playlist and its tracks, as the server last gave them
 *     __plexamp-web-android/downloads/cover?path=<path>  an album's cover, by the server's name for it
 *
 * each from the server `?server=<id>` says (`&server=` for a cover), or the one the player's
 * on where it doesn't. Called on WebView's own threads.
 */
class DownloadServer(private val downloads: Downloads) {
    /** `path`: what follows downloads/ in the address. */
    fun respond(request: WebResourceRequest, path: String): WebResourceResponse {
        val store = downloads.storeFor(request.url.getQueryParameter("server")) ?: return notFound()
        return when {
            path.startsWith("track/") -> {
                val saved = store.index().tracks[path.removePrefix("track/")] ?: return notFound()
                file(store.trackFile(saved.file), saved.mime, request)
            }
            path.startsWith("playlist/") -> {
                val json = store.playlistJson(path.removePrefix("playlist/")) ?: return notFound()
                WebResourceResponse("application/json", "utf-8", 200, "OK", NO_STORE, ByteArrayInputStream(json.toByteArray()))
            }
            path == "cover" -> {
                val name = request.url.getQueryParameter("path")?.let { store.index().covers[it] } ?: return notFound()
                val file = store.coverFile(name)
                file(file, imageTypeOf(file), request)
            }
            else -> notFound()
        }
    }

    private fun file(file: File, mime: String, request: WebResourceRequest): WebResourceResponse {
        if (!file.isFile) return notFound()
        val total = file.length()
        val header = request.requestHeaders.entries.firstOrNull { it.key.equals("Range", ignoreCase = true) }?.value
        val headers = HashMap(NO_STORE)
        headers["Accept-Ranges"] = "bytes"
        return try {
            when (val range = ByteRange.of(header, total)) {
                null -> {
                    headers["Content-Length"] = "$total"
                    WebResourceResponse(mime, null, 200, "OK", headers, FileInputStream(file))
                }
                ByteRange.UNSATISFIABLE -> {
                    headers["Content-Range"] = "bytes */$total"
                    WebResourceResponse(mime, null, 416, "Range Not Satisfiable", headers, ByteArrayInputStream(ByteArray(0)))
                }
                else -> {
                    headers["Content-Range"] = range.contentRange(total)
                    headers["Content-Length"] = "${range.length}"
                    WebResourceResponse(mime, null, 206, "Partial Content", headers, Slice(file, range))
                }
            }
        } catch (e: IOException) {
            notFound()
        }
    }

    /** Part of a file, as a stream. */
    private class Slice(file: File, range: ByteRange) : InputStream() {
        private val input = FileInputStream(file).also { it.channel.position(range.start) }
        private var left = range.length

        override fun read(): Int {
            if (left <= 0) return -1
            return input.read().also { if (it >= 0) left-- }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = input.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }

        override fun available(): Int = minOf(input.available().toLong(), left).toInt()

        override fun close() = input.close()
    }

    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", NO_STORE, ByteArrayInputStream(ByteArray(0)))

    companion object {
        /** Where downloads are, under the player's address: never asked of the player itself. */
        const val PATH = "__plexamp-web-android/downloads/"

        private val NO_STORE = mapOf("Cache-Control" to "no-store")

        // (What Plex sends as covers: its first bytes say.)
        private fun imageTypeOf(file: File): String {
            val head = ByteArray(12)
            val n = try {
                FileInputStream(file).use { it.read(head) }
            } catch (e: IOException) {
                0
            }
            return when {
                n >= 8 && head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() -> "image/png"
                n >= 12 && String(head, 0, 4, Charsets.US_ASCII) == "RIFF" && String(head, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
                else -> "image/jpeg"
            }
        }
    }
}
