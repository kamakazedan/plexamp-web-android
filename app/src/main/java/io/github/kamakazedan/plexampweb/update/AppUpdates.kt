package io.github.kamakazedan.plexampweb.update

import io.github.kamakazedan.plexampweb.BuildConfig
import io.github.kamakazedan.plexampweb.Prefs
import io.github.kamakazedan.plexampweb.net.Http
import java.io.IOException

/**
 * Whether there's a newer version of this app, from its releases on GitHub: asked only
 * when you ask (the player's Settings › About, "Check for updates"), never by itself.
 * What it finds is kept, so About can offer it till the app's updated.
 */
class AppUpdates(private val prefs: Prefs) {
    /** The newest version found, where it's newer than this one. */
    fun newer(): AppRelease? {
        val version = prefs.appNewest ?: return null
        val url = prefs.appNewestUrl ?: return null
        return AppRelease(version, url).takeIf { AppRelease.newer(version, BuildConfig.VERSION_NAME) }
    }

    /** Asks GitHub for the newest version: null where it couldn't tell. Blocking: off the main thread. */
    fun check(): AppRelease? {
        val answer = try {
            Http.request(
                AppRelease.LATEST,
                headers = mapOf("Accept" to "application/vnd.github+json"),
                maxBytes = MAX_BYTES,
                connectTimeoutMs = TIMEOUT_MS,
                readTimeoutMs = TIMEOUT_MS,
            )
        } catch (e: IOException) {
            return null
        }
        if (answer.status != 200) return null
        val release = AppRelease.of(answer.text()) ?: return null
        prefs.appNewest = release.version
        prefs.appNewestUrl = release.url
        return release
    }

    private companion object {
        const val MAX_BYTES = 1 shl 20
        const val TIMEOUT_MS = 10_000
    }
}
