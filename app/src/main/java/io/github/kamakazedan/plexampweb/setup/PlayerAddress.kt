package io.github.kamakazedan.plexampweb.setup

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/** Player addresses: what someone typed, as the address the app keeps (and checks). */
object PlayerAddress {
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val LOCAL_SUFFIXES = listOf(".localhost", ".local", ".lan", ".home", ".home.arpa", ".internal", ".ts.net")

    /**
     * "music.example.com" as https://music.example.com/, "192.168.1.50:8080" as
     * http://192.168.1.50:8080/ (an address on your own network is usually plain
     * http://), or null where it isn't an address. Always ends in a slash.
     */
    fun normalize(input: String): String? {
        var text = input.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null
        if (!SCHEME.containsMatchIn(text)) {
            val host = hostOf(text) ?: return null
            val plain = isLocal(host) || IPV4.matches(host) || host.startsWith("[")
            text = (if (plain) "http://" else "https://") + text
        }
        val uri = try {
            URI(text)
        } catch (e: URISyntaxException) {
            return null
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return null
        if (uri.rawUserInfo != null) return null // never a password in the address
        val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        val port = uri.port.takeIf { it != -1 && !(scheme == "http" && it == 80) && !(scheme == "https" && it == 443) }
        var path = uri.rawPath.orEmpty()
        if (path.endsWith("/index.html")) path = path.removeSuffix("index.html")
        if (!path.startsWith("/")) path = "/$path"
        if (!path.endsWith("/")) path += "/"
        return "$scheme://$host${if (port != null) ":$port" else ""}$path"
    }

    /** The host in something typed without http:// or https:// ("192.168.1.50" from "192.168.1.50:8080/x"). */
    internal fun hostOf(text: String): String? {
        val authority = text.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
        if (authority.isEmpty()) return null
        return if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
    }

    /**
     * Whether a host is on your own network or a private one (Tailscale's, say), where
     * a plain http:// address is usual and doesn't cross the internet.
     */
    fun isLocal(rawHost: String): Boolean {
        val host = rawHost.removePrefix("[").removeSuffix("]").lowercase(Locale.ROOT).removeSuffix(".")
        if (host.isEmpty()) return false
        if (host == "localhost" || LOCAL_SUFFIXES.any { host.endsWith(it) }) return true
        if (IPV4.matches(host)) {
            val parts = host.split('.').map { it.toInt() }
            if (parts.any { it > 255 }) return false
            val (a, b) = parts
            return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                (a == 169 && b == 254) || (a == 100 && b in 64..127)
        }
        if (host.contains(':')) {
            return host == "::1" || host.startsWith("fc") || host.startsWith("fd") ||
                host.startsWith("fe8") || host.startsWith("fe9") || host.startsWith("fea") || host.startsWith("feb")
        }
        // A name of one part ("nas") is one only your own network knows.
        return !host.contains('.')
    }

    /** Whether signing in through this address would cross the internet unencrypted. */
    fun isUnencryptedPublic(base: String): Boolean {
        val uri = URI(base)
        return uri.scheme == "http" && !isLocal(uri.host ?: return false)
    }

    /** Where a redirect goes, and whether it's followed there. */
    sealed interface Redirect {
        /** On to `url`. */
        data class To(val url: String) : Redirect

        /** From https:// to plain http:// (`url`): never followed, as it would drop the encryption. */
        data class Unencrypted(val url: String) : Redirect

        /** Somewhere that isn't an http:// or https:// address (file:, say), or with a password in it: never followed. */
        data object Nowhere : Redirect
    }

    /** Where a redirect from `from`, with `location` (its Location header), goes. */
    fun redirect(from: String, location: String): Redirect {
        val target = try {
            URI(from).resolve(location.trim())
        } catch (e: Exception) {
            return Redirect.Nowhere
        }
        val scheme = target.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return Redirect.Nowhere
        if (target.rawUserInfo != null || target.host.isNullOrEmpty()) return Redirect.Nowhere
        val url = target.toString()
        return if (scheme == "http" && URI(from).scheme.equals("https", ignoreCase = true)) Redirect.Unencrypted(url) else Redirect.To(url)
    }

    /** Whether `found`, where checking `typed` ended up, is on another server (another host) than `typed`. */
    fun otherServer(typed: String, found: String): Boolean =
        !URI(typed).host.equals(URI(found).host, ignoreCase = true)

    /** scheme://host[:port], as WebView's rules for which pages may use the app's bridge take it. */
    fun origin(base: String): String {
        val uri = URI(base)
        return "${uri.scheme}://${uri.host}${if (uri.port != -1) ":${uri.port}" else ""}"
    }
}
