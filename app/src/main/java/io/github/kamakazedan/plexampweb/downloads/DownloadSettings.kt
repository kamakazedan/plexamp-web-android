package io.github.kamakazedan.plexampweb.downloads

import org.json.JSONObject

/**
 * How downloads are made and played, as chosen in the player's Settings › Downloads:
 * the quality they're made at, which connections they're made on, and when the
 * player plays them rather than streaming.
 */
data class DownloadSettings(
    /** "original" (the files as they are), or the kbps they're converted to: "320", "192", "128". */
    val quality: String = ORIGINAL,
    /** "wifi": on Wi-Fi only; "any": on mobile data too. */
    val network: String = WIFI,
    /** "always", "mobile" (on mobile data, and offline), or "offline" (only when the server can't be reached). */
    val prefer: String = ALWAYS,
) {
    /** The bitrate tracks are converted to, or null for the files as they are. */
    val kbps: Int? get() = quality.toIntOrNull()

    fun toJson(): JSONObject = JSONObject().put("quality", quality).put("network", network).put("prefer", prefer)

    /** These, with each given that's a choice there is (anything else left as it is). */
    fun with(quality: String?, network: String?, prefer: String?) = DownloadSettings(
        quality = quality?.takeIf { it in QUALITIES } ?: this.quality,
        network = network?.takeIf { it in NETWORKS } ?: this.network,
        prefer = prefer?.takeIf { it in PREFERS } ?: this.prefer,
    )

    companion object {
        const val ORIGINAL = "original"
        val QUALITIES = setOf(ORIGINAL, "320", "192", "128")

        const val WIFI = "wifi"
        const val ANY = "any"
        val NETWORKS = setOf(WIFI, ANY)

        const val ALWAYS = "always"
        const val MOBILE = "mobile"
        const val OFFLINE = "offline"
        val PREFERS = setOf(ALWAYS, MOBILE, OFFLINE)

        /** As kept (anything that isn't a choice there is, as it is to begin with). */
        fun of(quality: String?, network: String?, prefer: String?) = DownloadSettings().with(quality, network, prefer)
    }
}
