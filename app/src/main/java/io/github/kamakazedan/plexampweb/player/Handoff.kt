package io.github.kamakazedan.plexampweb.player

import org.json.JSONObject

/**
 * A Plex sign-in made here, waiting to be handed to the web app. Kept in memory only:
 * once handed over, the web app keeps it, as it would its own (in the WebView's
 * storage for the player's address), and the app forgets it.
 */
class Handoff(private val token: String, private val name: String, private val thumb: String?) {
    /**
     * The script that hands it over, run in a page of the player's own (so with its
     * storage): the sign-in goes where the web app's own sign-in puts it ("pw.auth", with
     * this phone's id with Plex as "pw.clientId"), then the web app opens, to choose a
     * server, as after signing in there. Where someone's signed in there already, theirs
     * is left as it is. Gives "ok" once done.
     */
    fun script(base: String, clientId: String): String {
        val auth = JSONObject()
            .put("token", token)
            .put("username", name)
            .put("thumb", thumb ?: JSONObject.NULL)
            .put("manual", false)
        return """
            (function (auth, clientId, chooseServer, home) {
              try {
                var before = null;
                try { before = JSON.parse(localStorage.getItem('pw.auth') || 'null'); } catch (e) {}
                var fresh = !(before && before.token);
                localStorage.setItem('pw.clientId', JSON.stringify(clientId));
                localStorage.removeItem('pw.pendingPin');
                if (fresh) localStorage.setItem('pw.auth', JSON.stringify(auth));
                location.replace(fresh ? chooseServer : home);
                return 'ok';
              } catch (e) {
                return 'failed';
              }
            })($auth, ${JSONObject.quote(clientId)}, ${JSONObject.quote("$base#/setup")}, ${JSONObject.quote("$base#/")})
        """.trimIndent()
    }
}
