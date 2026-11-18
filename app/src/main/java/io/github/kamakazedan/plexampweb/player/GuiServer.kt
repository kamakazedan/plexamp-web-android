package io.github.kamakazedan.plexampweb.player

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import io.github.kamakazedan.plexampweb.gui.GuiRefs
import io.github.kamakazedan.plexampweb.gui.GuiSnapshot
import io.github.kamakazedan.plexampweb.gui.GuiUpdates
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.IOException
import java.net.URI

/**
 * Answers the WebView's requests for the player's own files (its page, scripts, styles
 * and icons) from the saved copy, at the player's own address, so it opens without a
 * connection and the web app can't tell. Everything else goes to the network as it
 * would in a browser: the player's service (api/), Plex (pms/ or the server itself),
 * artwork.
 *
 * Called on WebView's own threads.
 */
class GuiServer(private val gui: GuiUpdates, base: String) {
    private val baseUri = URI(base)

    /** The copy the page on screen was opened from: its files come from it till it's reopened. */
    @Volatile
    private var pinned: GuiSnapshot? = null

    /** While set, the page that hands the sign-in over is served (see Handoff). */
    @Volatile
    var handoffPending = false

    /** The path of an address under the player's ("" for the player itself), or null where it's elsewhere. */
    fun pathOf(url: Uri): String? = GuiRefs.relative(baseUri, url.scheme, url.host, url.port, url.encodedPath)

    fun respond(request: WebResourceRequest): WebResourceResponse? {
        if (!request.method.equals("GET", ignoreCase = true)) return null
        val rel = pathOf(request.url) ?: return null
        if (rel == HANDOFF_PATH) return if (handoffPending) page(HANDOFF_PAGE) else notFound()
        if (GuiRefs.isServicePath(rel)) return null
        val path = rel.ifEmpty { GuiRefs.INDEX }
        val snapshot = if (request.isForMainFrame && path == GuiRefs.INDEX) {
            gui.current().also { now ->
                pinned = now
                gui.inUse = now?.id
            }
        } else {
            pinned ?: gui.current()
        }
        val file = snapshot?.files?.get(path)
        if (file == null) {
            if (snapshot != null) gui.noteMiss(path)
            return null
        }
        val stream = try {
            FileInputStream(gui.store.blobFile(file.blob))
        } catch (e: IOException) {
            return null
        }
        val type = file.type.substringBefore(';').trim()
        val charset = CHARSET.find(file.type)?.groupValues?.get(1)
            ?: if (type.startsWith("text/") || type.contains("javascript") || type.contains("json")) "utf-8" else null
        return WebResourceResponse(type, charset, 200, "OK", file.headers, stream)
    }

    private fun page(html: String) = WebResourceResponse(
        "text/html",
        "utf-8",
        200,
        "OK",
        mapOf("Cache-Control" to "no-store"),
        ByteArrayInputStream(html.toByteArray()),
    )

    private fun notFound() = WebResourceResponse(
        "text/plain",
        "utf-8",
        404,
        "Not Found",
        mapOf("Cache-Control" to "no-store"),
        ByteArrayInputStream(ByteArray(0)),
    )

    companion object {
        private val CHARSET = Regex("charset=\"?([^\";]+)", RegexOption.IGNORE_CASE)

        /** Never asked of the player itself: answered here, only while a sign-in is being handed over. */
        const val HANDOFF_PATH = "__plexamp-web-android/handoff"

        private const val HANDOFF_PAGE =
            "<!doctype html><meta charset=\"utf-8\"><meta name=\"color-scheme\" content=\"dark\">" +
                "<title>plexamp-web</title><body style=\"background:#0b0d12\"></body>"
    }
}
