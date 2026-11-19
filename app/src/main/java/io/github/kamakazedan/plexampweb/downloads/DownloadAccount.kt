package io.github.kamakazedan.plexampweb.downloads

import io.github.kamakazedan.plexampweb.gui.GuiStore
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale

/**
 * The Plex server downloads come from, and the player's sign-in to it, as the player last
 * said. Kept (encrypted: see TokenVault) only while something's downloaded, so they can
 * be kept up to date with the player closed.
 */
data class DownloadAccount(
    /** The server's id (its machineIdentifier): only an address that answers as it is used. */
    val serverId: String,
    /** The player's sign-in to the server (the server's token). */
    val token: String,
    /** Who's signed in, as a fingerprint (see ownerOf): someone else, or another server, starts downloads afresh. */
    val owner: String,
    /** The server's addresses, the player's best first (its own /pms/ among them, where it has it). */
    val uris: List<String>,
    /** The name the player goes by in Plex (its dashboard). */
    val device: String,
) {
    fun toJson(): String = JSONObject()
        .put("serverId", serverId)
        .put("token", token)
        .put("owner", owner)
        .put("uris", JSONArray(uris))
        .put("device", device)
        .toString()

    // (Never the token: this ends up in logs, say, where it's printed.)
    override fun toString() = "DownloadAccount(serverId=$serverId, uris=${uris.size})"

    companion object {
        private val SERVER_ID = Regex("^[A-Za-z0-9_-]{1,100}$")
        private val TOKEN = Regex("^[A-Za-z0-9_.~-]{8,200}$")
        private const val MAX_URIS = 12

        fun fromJson(text: String): DownloadAccount? = try {
            val json = JSONObject(text)
            val uris = json.optJSONArray("uris") ?: JSONArray()
            of(
                serverId = json.optString("serverId"),
                token = json.optString("token"),
                owner = json.optString("owner"),
                uris = (0 until uris.length()).map { uris.optString(it) },
                device = json.optString("device"),
                playerOrigin = null,
            )
        } catch (e: Exception) {
            null
        }

        /**
         * An account as the player gives it, or null where something in it isn't what it
         * should be. Only addresses downloads may use are kept (see usable): `playerOrigin`,
         * the player's own (null: those kept already, checked when they were).
         */
        fun of(serverId: String, token: String, owner: String, uris: List<String>, device: String, playerOrigin: String?): DownloadAccount? {
            if (!SERVER_ID.matches(serverId) || !TOKEN.matches(token) || owner.isEmpty() || owner.length > 100) return null
            val kept = uris.asSequence()
                .map { it.trim().trimEnd('/') }
                .filter { playerOrigin == null || usable(it, playerOrigin) }
                .distinct()
                .take(MAX_URIS)
                .toList()
            if (kept.isEmpty()) return null
            val name = device.replace(Regex("[\\u0000-\\u001f\\u007f]"), "").trim().take(60).ifEmpty { "plexamp-web (Android)" }
            return DownloadAccount(serverId, token, owner, kept, name)
        }

        /**
         * An address downloads may use: https://, whose certificate is checked, or the
         * player's own address (its /pms/), which the player itself is used at. Never plain
         * http:// elsewhere: away from home, an address on the home network can be anyone's,
         * and the sign-in would go to them unencrypted.
         */
        fun usable(uri: String, playerOrigin: String): Boolean {
            val u = try {
                URI(uri)
            } catch (e: Exception) {
                return false
            }
            val scheme = u.scheme?.lowercase(Locale.ROOT) ?: return false
            if (u.rawUserInfo != null || u.host.isNullOrEmpty() || u.rawQuery != null || u.rawFragment != null) return false
            if (scheme == "https") return true
            val player = try {
                originOf(URI(playerOrigin))
            } catch (e: Exception) {
                return false
            }
            return scheme == "http" && originOf(u) == player
        }

        private fun originOf(u: URI): String {
            val scheme = u.scheme?.lowercase(Locale.ROOT) ?: ""
            val port = if (u.port == -1 || (scheme == "http" && u.port == 80) || (scheme == "https" && u.port == 443)) "" else ":${u.port}"
            return "$scheme://${u.host?.lowercase(Locale.ROOT)}$port"
        }

        /** Who's signed in, to which server, as a fingerprint: not their name. */
        fun ownerOf(serverId: String, user: String): String = GuiStore.sha256("$serverId\n$user".toByteArray()).take(32)
    }
}
