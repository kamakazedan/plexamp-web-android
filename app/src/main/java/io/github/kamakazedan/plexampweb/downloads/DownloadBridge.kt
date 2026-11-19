package io.github.kamakazedan.plexampweb.downloads

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * What the player's page and the app say about downloads (see assets/page-bridge.js,
 * which gives the page plexampAndroid.downloads). The page asks, `id` matching each
 * answer to its question:
 *
 *     state                              everything Settings › Downloads shows (see Downloads.state)
 *     add { playlist }                   keep a playlist: refused with { code: "space", needed, free }, say
 *     remove { playlist }
 *     set { quality, network, prefer }
 *     account { serverId, token, user, uris, device }   the player's sign-in to its server, each time it opens
 *     forget                             signed out
 *     bad { track }                      a downloaded track that wouldn't play
 *
 * and is answered { type: "downloads", id, ok, state } or { …, ok: false, error: { code } }, the code
 * one of space, account (no sign-in given yet), signin, unreachable, server, missing,
 * empty, bad (a question that makes no sense), unknown (one there isn't) or failed.
 * While it's interested, it's told as things change: { type: "downloads-state", state }.
 */
class DownloadBridge(
    private val downloads: Downloads,
    private val scope: CoroutineScope,
    /** The player's own address (its origin): the only plain http:// one downloads may use (see DownloadAccount.usable). */
    private val playerOrigin: String,
    /** A playlist was added: downloads have started. */
    private val onStarted: () -> Unit,
) {
    private var pushing: Job? = null

    /** A message from the page, of type "downloads"; `post`, to answer it (on the main thread). */
    fun onMessage(msg: JSONObject, post: (String) -> Unit) {
        startPushing(post)
        val id = msg.optInt("id", 0)
        scope.launch {
            val reply = JSONObject().put("type", "downloads").put("id", id)
            try {
                when (msg.optString("op")) {
                    "state" -> Unit
                    "add" -> {
                        downloads.add(msg.getString("playlist"))
                        onStarted()
                    }
                    "remove" -> downloads.remove(msg.getString("playlist"))
                    "set" -> downloads.set(msg.stringOrNull("quality"), msg.stringOrNull("network"), msg.stringOrNull("prefer"))
                    "account" -> downloads.account(accountOf(msg) ?: throw Downloads.Refused("account"))
                    "forget" -> downloads.forget()
                    "bad" -> downloads.dropTrack(msg.getString("track"))
                    else -> throw Downloads.Refused("unknown")
                }
                reply.put("ok", true).put("state", withContext(Dispatchers.IO) { downloads.state() })
            } catch (e: Downloads.Refused) {
                reply.put("ok", false).put("error", JSONObject().put("code", e.code).put("needed", e.needed).put("free", e.free))
            } catch (e: JSONException) {
                reply.put("ok", false).put("error", JSONObject().put("code", "bad"))
            } catch (e: IllegalArgumentException) {
                reply.put("ok", false).put("error", JSONObject().put("code", "bad"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // (The phone's storage, say: the page's told, and the app carries on.)
                reply.put("ok", false).put("error", JSONObject().put("code", "failed"))
            }
            post(reply.toString())
        }
    }

    /** A new page: it says when it's interested again. */
    fun stop() {
        pushing?.cancel()
        pushing = null
    }

    // What changes, told to the page: at most so often while a download goes.
    private fun startPushing(post: (String) -> Unit) {
        if (pushing != null) return
        pushing = scope.launch {
            downloads.changes.collect {
                val state = withContext(Dispatchers.IO) { downloads.state() }
                post(JSONObject().put("type", "downloads-state").put("state", state).toString())
                delay(PUSH_MS)
            }
        }
    }

    private fun accountOf(msg: JSONObject): DownloadAccount? {
        val serverId = msg.optString("serverId")
        val uris = msg.optJSONArray("uris") ?: JSONArray()
        return DownloadAccount.of(
            serverId = serverId,
            token = msg.optString("token"),
            owner = DownloadAccount.ownerOf(serverId, msg.optString("user")),
            uris = (0 until minOf(uris.length(), 50)).map { uris.optString(it) },
            device = msg.optString("device"),
            playerOrigin = playerOrigin,
        )
    }

    private fun JSONObject.stringOrNull(name: String): String? = if (has(name) && !isNull(name)) optString(name) else null

    private companion object {
        const val PUSH_MS = 1000L
    }
}
