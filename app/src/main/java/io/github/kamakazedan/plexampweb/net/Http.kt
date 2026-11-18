package io.github.kamakazedan.plexampweb.net

import android.os.Build
import io.github.kamakazedan.plexampweb.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * The app's own requests (the player's address check, plex.tv, saving the player,
 * artwork for the lock screen). Blocking: call it off the main thread. Redirects
 * aren't followed (each caller decides), nothing is cached, and nothing is logged:
 * addresses can carry a Plex token.
 */
object Http {
    val userAgent = "plexamp-web-android/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE})"

    class Response(val status: Int, private val headers: Map<String, List<String>>, val body: ByteArray) {
        fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]?.firstOrNull()
        fun text(): String = String(body, Charsets.UTF_8)
    }

    class TooLarge : IOException("The answer was larger than expected.")

    fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        maxBytes: Int = 8 shl 20,
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 30_000,
    ): Response {
        // (Only http:// and https://: a redirect could name file: or jar:, which aren't.)
        val conn = URL(url).openConnection() as? HttpURLConnection ?: throw IOException("Not an http:// or https:// address.")
        try {
            conn.requestMethod = method
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("User-Agent", userAgent)
            for ((name, value) in headers) conn.setRequestProperty(name, value)
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val bytes = stream?.use { readUpTo(it, maxBytes) } ?: ByteArray(0)
            val names = HashMap<String, List<String>>()
            for ((name, values) in conn.headerFields) {
                if (name != null) names[name.lowercase(Locale.ROOT)] = values
            }
            return Response(status, names, bytes)
        } catch (e: IOException) {
            conn.disconnect()
            throw e
        }
    }

    private fun readUpTo(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > max) throw TooLarge()
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
