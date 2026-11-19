package io.github.kamakazedan.plexampweb

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

/**
 * What the app keeps for itself. The web app keeps its own (its sign-in among it)
 * in the WebView, as in a browser: no Plex token is ever kept here. (Downloads keep the
 * player's sign-in to your server, encrypted, while there are any: see TokenVault.)
 */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("plexamp-web", Context.MODE_PRIVATE)

    /** The player's address, ending in a slash: https://music.example.com/ */
    var playerUrl: String?
        get() = prefs.getString(PLAYER_URL, null)
        set(value) = prefs.edit { if (value == null) remove(PLAYER_URL) else putString(PLAYER_URL, value) }

    /**
     * Whether the web app was signed in when last seen (by the page it was on):
     * the player opens at start if so, the sign-in screen if not.
     */
    var signedIn: Boolean
        get() = prefs.getBoolean(SIGNED_IN, false)
        set(value) = prefs.edit { putBoolean(SIGNED_IN, value) }

    /** This phone's id with Plex (X-Plex-Client-Identifier), shared with the web app. */
    val clientId: String
        get() = prefs.getString(CLIENT_ID, null)
            ?: UUID.randomUUID().toString().also { id -> prefs.edit { putString(CLIENT_ID, id) } }

    /** A sign-in waiting on plex.tv: its PIN's id (nothing secret on its own) and when it began. */
    var pendingPin: PendingPin?
        get() {
            val id = prefs.getLong(PIN_ID, 0)
            return if (id > 0) PendingPin(id, prefs.getLong(PIN_AT, 0)) else null
        }
        set(value) = prefs.edit {
            if (value == null) {
                remove(PIN_ID)
                remove(PIN_AT)
            } else {
                putLong(PIN_ID, value.id)
                putLong(PIN_AT, value.createdAt)
            }
        }

    /** When the player was last checked for a new version, and what came of it. */
    var lastCheckAt: Long
        get() = prefs.getLong(LAST_CHECK_AT, 0)
        set(value) = prefs.edit { putLong(LAST_CHECK_AT, value) }

    /** "updated", "same" or "failed". */
    var lastCheckOutcome: String?
        get() = prefs.getString(LAST_CHECK_OUTCOME, null)
        set(value) = prefs.edit { putString(LAST_CHECK_OUTCOME, value) }

    /** Why it failed, where it did (GuiUpdater.Outcome.Failed.code): "REFUSED 403". */
    var lastCheckProblem: String?
        get() = prefs.getString(LAST_CHECK_PROBLEM, null)
        set(value) = prefs.edit { if (value == null) remove(LAST_CHECK_PROBLEM) else putString(LAST_CHECK_PROBLEM, value) }

    /**
     * Files the page asked for that the saved copy hadn't got: saved too, next time
     * the player is.
     */
    var misses: Set<String>
        get() = prefs.getStringSet(MISSES, null)?.toSet() ?: emptySet()
        set(value) = prefs.edit { putStringSet(MISSES, value.take(MAX_MISSES).toSet()) }

    /** The WebView's own cache (artwork from the last player, say) is cleared before it's next used. */
    var clearWebCache: Boolean
        get() = prefs.getBoolean(CLEAR_WEB_CACHE, false)
        set(value) = prefs.edit { putBoolean(CLEAR_WEB_CACHE, value) }

    /** Downloads' settings, as chosen in the player (see DownloadSettings): null for as they are to begin with. */
    var downloadQuality: String?
        get() = prefs.getString(DOWNLOAD_QUALITY, null)
        set(value) = prefs.edit { putString(DOWNLOAD_QUALITY, value) }

    var downloadNetwork: String?
        get() = prefs.getString(DOWNLOAD_NETWORK, null)
        set(value) = prefs.edit { putString(DOWNLOAD_NETWORK, value) }

    var downloadPrefer: String?
        get() = prefs.getString(DOWNLOAD_PREFER, null)
        set(value) = prefs.edit { putString(DOWNLOAD_PREFER, value) }

    /** Whose downloads they are (a fingerprint: see DownloadAccount.ownerOf), while there are any. */
    var downloadsOwner: String?
        get() = prefs.getString(DOWNLOADS_OWNER, null)
        set(value) = prefs.edit { if (value == null) remove(DOWNLOADS_OWNER) else putString(DOWNLOADS_OWNER, value) }

    /** Why downloads couldn't be brought up to date last time: "signin", "unreachable", or null. */
    var downloadsTrouble: String?
        get() = prefs.getString(DOWNLOADS_TROUBLE, null)
        set(value) = prefs.edit { if (value == null) remove(DOWNLOADS_TROUBLE) else putString(DOWNLOADS_TROUBLE, value) }

    /** Whether the app has asked to show notifications (for downloads' progress): it asks once. */
    var askedForNotifications: Boolean
        get() = prefs.getBoolean(ASKED_NOTIFICATIONS, false)
        set(value) = prefs.edit { putBoolean(ASKED_NOTIFICATIONS, value) }

    fun addMiss(path: String) {
        val now = misses
        if (path !in now && now.size < MAX_MISSES) misses = now + path
    }

    /** Everything about the player (not this phone's id with Plex). */
    fun forgetPlayer() = prefs.edit {
        remove(PLAYER_URL)
        remove(SIGNED_IN)
        remove(PIN_ID)
        remove(PIN_AT)
        remove(LAST_CHECK_AT)
        remove(LAST_CHECK_OUTCOME)
        remove(LAST_CHECK_PROBLEM)
        remove(MISSES)
    }

    data class PendingPin(val id: Long, val createdAt: Long)

    private companion object {
        const val PLAYER_URL = "playerUrl"
        const val SIGNED_IN = "signedIn"
        const val CLIENT_ID = "clientId"
        const val PIN_ID = "pinId"
        const val PIN_AT = "pinAt"
        const val LAST_CHECK_AT = "lastCheckAt"
        const val LAST_CHECK_OUTCOME = "lastCheckOutcome"
        const val LAST_CHECK_PROBLEM = "lastCheckProblem"
        const val MISSES = "misses"
        const val CLEAR_WEB_CACHE = "clearWebCache"
        const val DOWNLOAD_QUALITY = "downloadQuality"
        const val DOWNLOAD_NETWORK = "downloadNetwork"
        const val DOWNLOAD_PREFER = "downloadPrefer"
        const val DOWNLOADS_OWNER = "downloadsOwner"
        const val DOWNLOADS_TROUBLE = "downloadsTrouble"
        const val ASKED_NOTIFICATIONS = "askedNotifications"
        const val MAX_MISSES = 50
    }
}
