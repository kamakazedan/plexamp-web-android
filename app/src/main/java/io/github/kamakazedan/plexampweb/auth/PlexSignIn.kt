package io.github.kamakazedan.plexampweb.auth

import android.net.Uri
import android.os.Build
import io.github.kamakazedan.plexampweb.BuildConfig
import io.github.kamakazedan.plexampweb.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * Signing in with Plex, as Plex asks apps to: a PIN made here, claimed by signing in
 * on plex.tv in the phone's browser (so Google, Apple and password managers work),
 * and asked after until it has a token. As the web app does it, with the same
 * product name and this phone's id, so Plex lists one device.
 */
class PlexSignIn(private val clientId: String) {
    data class Pin(val id: Long, val code: String)

    /** Who signed in: the name the web app shows, and their picture (plex.tv's). */
    data class Account(val name: String?, val thumb: String?)

    /** The PIN has expired, or plex.tv doesn't know it. */
    class PinGone : IOException("The sign-in has expired.")

    private val headers: Map<String, String>
        get() = mapOf(
            "Accept" to "application/json",
            "X-Plex-Product" to PRODUCT,
            "X-Plex-Version" to BuildConfig.VERSION_NAME,
            "X-Plex-Client-Identifier" to clientId,
            "X-Plex-Platform" to "Android",
            "X-Plex-Platform-Version" to Build.VERSION.RELEASE,
            "X-Plex-Device" to "Android",
            "X-Plex-Device-Name" to "$PRODUCT (Android)",
        )

    suspend fun createPin(): Pin = withContext(Dispatchers.IO) {
        val res = Http.request(
            "$PLEX_TV/api/v2/pins",
            method = "POST",
            headers = headers + ("Content-Type" to "application/x-www-form-urlencoded"),
            body = "strong=true".toByteArray(),
        )
        if (res.status !in 200..299) throw IOException("plex.tv answered ${res.status}")
        val json = JSONObject(res.text())
        Pin(json.getLong("id"), json.getString("code"))
    }

    /** Where to sign in. No forwardUrl: plex.tv only sends browsers back to web pages, so it says you're done instead. */
    fun authUrl(pin: Pin): Uri {
        val params = listOf(
            "clientID" to clientId,
            "code" to pin.code,
            "context[device][product]" to PRODUCT,
            "context[device][version]" to BuildConfig.VERSION_NAME,
            "context[device][platform]" to "Android",
            "context[device][device]" to "Android",
        ).joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }
        return Uri.parse("https://app.plex.tv/auth/#!?$params")
    }

    /** The token, once the PIN's been claimed on plex.tv; null till then. */
    suspend fun tokenFor(pinId: Long): String? = withContext(Dispatchers.IO) {
        val res = Http.request("$PLEX_TV/api/v2/pins/$pinId", headers = headers)
        when (res.status) {
            in 200..299 -> JSONObject(res.text()).stringOrNull("authToken")
            404, 410 -> throw PinGone()
            else -> throw IOException("plex.tv answered ${res.status}")
        }
    }

    /** Who a token's for; nothing where plex.tv doesn't say (the sign-in still works). */
    suspend fun account(token: String): Account = withContext(Dispatchers.IO) {
        try {
            val res = Http.request("$PLEX_TV/api/v2/user", headers = headers + ("X-Plex-Token" to token))
            if (res.status !in 200..299) return@withContext Account(null, null)
            val json = JSONObject(res.text())
            val name = listOf("username", "title", "email").firstNotNullOfOrNull { json.stringOrNull(it) }
            Account(name, json.stringOrNull("thumb")?.takeIf { it.startsWith("https://") })
        } catch (e: Exception) {
            Account(null, null)
        }
    }

    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8")

    private fun JSONObject.stringOrNull(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

    companion object {
        const val PRODUCT = "plexamp-web"
        private const val PLEX_TV = "https://plex.tv"
    }
}
