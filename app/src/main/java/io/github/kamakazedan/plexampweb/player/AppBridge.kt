package io.github.kamakazedan.plexampweb.player

import io.github.kamakazedan.plexampweb.BuildConfig
import io.github.kamakazedan.plexampweb.update.AppUpdates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * What the player's Settings › About asks the app (see assets/page-bridge.js, which gives
 * the page plexampAndroid.app). The page asks, `id` matching each answer to its question:
 *
 *     state     this app's version, and a newer one found by a check
 *     check     the same, with the app asked about on GitHub now, and the player on its
 *               server (a new version of it is opened as it would be on opening the app)
 *
 * and is answered { type: "app", id, ok, version, update }, update being { version, url }
 * or null; to check, with { app, player } too, how each went: app "newer", "same" or
 * "failed"; player "reloading" (a new version, opening now), "offered" (Reload offered, as
 * music's playing), "same" or "failed".
 */
class AppBridge(
    private val updates: AppUpdates,
    private val scope: CoroutineScope,
    /** Checks the player for a new version now, and opens it: how that went (see above). */
    private val checkPlayer: suspend () -> String,
) {
    private var checking = false

    /** A message from the page, of type "app"; `post`, to answer it (on the main thread). */
    fun onMessage(msg: JSONObject, post: (String) -> Unit) {
        val id = msg.optInt("id", 0)
        scope.launch {
            val reply = JSONObject().put("type", "app").put("id", id)
            when (msg.optString("op")) {
                "state" -> Unit
                "check" -> {
                    if (checking) {
                        post(reply.put("ok", false).put("error", JSONObject().put("code", "busy")).toString())
                        return@launch
                    }
                    checking = true
                    try {
                        // (The app first: a new version of the player may open, and About with it.)
                        val found = withContext(Dispatchers.IO) { updates.check() }
                        reply.put(
                            "app",
                            when {
                                found == null -> "failed"
                                updates.newer() != null -> "newer"
                                else -> "same"
                            },
                        )
                        reply.put("player", checkPlayer())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        post(reply.put("ok", false).put("error", JSONObject().put("code", "failed")).toString())
                        return@launch
                    } finally {
                        checking = false
                    }
                }
                else -> {
                    post(reply.put("ok", false).put("error", JSONObject().put("code", "unknown")).toString())
                    return@launch
                }
            }
            val update = updates.newer()?.let { JSONObject().put("version", it.version).put("url", it.url) }
            post(reply.put("ok", true).put("version", BuildConfig.VERSION_NAME).put("update", update ?: JSONObject.NULL).toString())
        }
    }
}
