package io.github.kamakazedan.plexampweb.update

import org.json.JSONException
import org.json.JSONObject

/**
 * A version of this app, as published on GitHub: its version ("0.2.4") and where to get
 * it (its APK, or else its release's page). Only ever one of this app's own releases.
 */
data class AppRelease(val version: String, val url: String) {
    companion object {
        /** GitHub's answer for the app's newest release (not a draft, nor a pre-release). */
        const val LATEST = "https://api.github.com/repos/kamakazedan/plexamp-web-android/releases/latest"

        /** Where a release's APK, or its page, may be: nowhere else. */
        private val URL = Regex("https://github\\.com/kamakazedan/plexamp-web-android/releases/(download/v?\\d[\\d.]*/[\\w.-]+\\.apk|tag/v?\\d[\\d.]*)")

        /** A release's tag: "v0.2.4", or "0.2.4". */
        private val TAG = Regex("v?(\\d{1,4}(?:\\.\\d{1,4}){1,3})")

        /** The release in GitHub's answer for the newest, or null where it isn't one to offer. */
        fun of(json: String): AppRelease? {
            val release = try {
                JSONObject(json)
            } catch (e: JSONException) {
                return null
            }
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
            val version = versionOf(release.optString("tag_name")) ?: return null
            val assets = release.optJSONArray("assets")
            val apk = (0 until (assets?.length() ?: 0))
                .mapNotNull { assets?.optJSONObject(it)?.optString("browser_download_url") }
                .firstOrNull { URL.matches(it) && it.endsWith(".apk") }
            val page = release.optString("html_url").takeIf { URL.matches(it) }
            return AppRelease(version, apk ?: page ?: return null)
        }

        /** The version a release's tag names, or null where it names none. */
        fun versionOf(tag: String): String? = TAG.matchEntire(tag.trim())?.groupValues?.get(1)

        /** Whether version `a` is a later one than `b` ("0.2.10" is later than "0.2.9"). */
        fun newer(a: String, b: String): Boolean {
            val x = a.split('.').map { it.toIntOrNull() ?: 0 }
            val y = b.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(x.size, y.size)) {
                val p = x.getOrElse(i) { 0 }
                val q = y.getOrElse(i) { 0 }
                if (p != q) return p > q
            }
            return false
        }
    }
}
