package io.github.kamakazedan.plexampweb.downloads

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import kotlin.concurrent.thread

/**
 * A small HTTP server on this computer, for tests (the JDK's own isn't in Android's
 * classes, which unit tests are compiled against). One request on each connection, each
 * answered on a thread of its own by `handle`.
 */
class StandInServer(private val handle: (Exchange) -> Unit) : AutoCloseable {
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))

    val uri = "http://127.0.0.1:${socket.localPort}"

    /** A request, and its answer. */
    class Exchange(val rawPath: String, val rawQuery: String?, private val headers: Map<String, String>, private val out: OutputStream) {
        val path: String = URI(rawPath).path

        val query: Map<String, String> = (rawQuery ?: "").split('&').filter { it.contains('=') }
            .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

        fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]

        /**
         * Answers: `body`, or only its first `upTo` bytes (a connection that drops part-way,
         * as the whole is still said to be coming).
         */
        fun send(status: Int, type: String, body: ByteArray, headers: Map<String, String> = emptyMap(), upTo: Int = body.size) {
            val head = StringBuilder("HTTP/1.1 $status ${REASONS[status] ?: "Status"}\r\n")
            head.append("Content-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n")
            for ((name, value) in headers) head.append("$name: $value\r\n")
            head.append("\r\n")
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            out.write(body, 0, upTo)
            out.flush()
        }
    }

    init {
        thread(isDaemon = true, name = "stand-in server") {
            while (!socket.isClosed) {
                val conn = try {
                    socket.accept()
                } catch (e: IOException) {
                    break
                }
                thread(isDaemon = true) { serve(conn) }
            }
        }
    }

    private fun serve(conn: Socket) {
        conn.use {
            try {
                val input = BufferedInputStream(it.getInputStream())
                val lines = readHead(input) ?: return
                val target = lines.first().split(' ').getOrNull(1) ?: return
                val headers = lines.drop(1).filter { line -> line.contains(':') }
                    .associate { line -> line.substringBefore(':').trim().lowercase(Locale.ROOT) to line.substringAfter(':').trim() }
                val out = it.getOutputStream()
                handle(Exchange(target.substringBefore('?'), target.substringAfter('?', "").ifEmpty { null }, headers, out))
                out.flush()
            } catch (e: IOException) {
                // (The client went, or an answer was cut short on purpose.)
            }
        }
    }

    // The request line and its headers, up to the empty line (no request here has a body).
    private fun readHead(input: BufferedInputStream): List<String>? {
        val bytes = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            bytes.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == END_OF_HEAD) break
            if (bytes.size() > MAX_HEAD) return null
        }
        return bytes.toString(Charsets.ISO_8859_1.name()).split("\r\n").filter { it.isNotEmpty() }
    }

    override fun close() {
        socket.close()
    }

    private companion object {
        const val END_OF_HEAD = 0x0d0a0d0a
        const val MAX_HEAD = 64 * 1024
        val REASONS = mapOf(200 to "OK", 206 to "Partial Content", 401 to "Unauthorized", 404 to "Not Found", 416 to "Range Not Satisfiable")
    }
}
