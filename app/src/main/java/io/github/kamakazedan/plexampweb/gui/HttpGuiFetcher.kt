package io.github.kamakazedan.plexampweb.gui

import io.github.kamakazedan.plexampweb.net.Http

/** The player's files, from the player itself. */
class HttpGuiFetcher : GuiFetcher {
    override fun get(url: String, etag: String?, lastModified: String?): Fetched {
        val headers = buildMap {
            put("Accept", "*/*")
            // (Past any cache between here and the player.)
            put("Cache-Control", "no-cache")
            if (etag != null) put("If-None-Match", etag)
            if (lastModified != null) put("If-Modified-Since", lastModified)
        }
        val res = Http.request(url, headers = headers, maxBytes = MAX_FILE_BYTES)
        val kept = KEPT_HEADERS.mapNotNull { name -> res.header(name)?.let { name to it } }.toMap()
        return Fetched(
            status = res.status,
            contentType = res.header("content-type"),
            etag = res.header("etag"),
            lastModified = res.header("last-modified"),
            headers = kept,
            body = if (res.status == 200) res.body else null,
        )
    }

    companion object {
        private const val MAX_FILE_BYTES = 16 shl 20

        /** What keeps the page as safe as the player made it: served with each saved file. */
        val KEPT_HEADERS = listOf(
            "Content-Security-Policy",
            "Referrer-Policy",
            "Permissions-Policy",
            "X-Content-Type-Options",
            "Cross-Origin-Opener-Policy",
            "Cross-Origin-Embedder-Policy",
            "Cross-Origin-Resource-Policy",
        )
    }
}
