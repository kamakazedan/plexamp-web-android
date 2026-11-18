package io.github.kamakazedan.plexampweb.gui

import java.net.URI
import java.util.Locale

/**
 * Which files make up the player's web app, found as a browser finds them: index.html's
 * scripts and styles, each module's imports, the stylesheet's pictures, the manifest's
 * icons, and file paths the scripts load by themselves (vendor/hls.min.js). Paths are
 * relative to the player's address, its base: "js/app.js".
 *
 * Required files are ones the app can't start without; a saved copy is only kept with
 * all of them. Others (icons, paths only mentioned in a script) are saved where the
 * player has them.
 *
 * What this relies on in the web app (its page's scripts, APP_INFO, the paths its
 * container answers) is listed in plexamp-web's docs/android-app.md, and its CI fails
 * where one changes.
 */
object GuiRefs {
    data class Ref(val path: String, val required: Boolean)

    enum class Kind { HTML, JS, CSS, MANIFEST, OTHER }

    /** The page itself, whatever its address ends in. */
    const val INDEX = "index.html"

    private const val ASSET = "(?:js|mjs|css|svg|png|jpe?g|webp|gif|ico|json|webmanifest|woff2?|ttf|txt)"
    private val HTML_REF = Regex(
        """<(?:script|link|img)\b[^>]*?\s(?:src|href)\s*=\s*(["'])(.*?)\1""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    // Module imports, at the start of a line as statements are (so not ones in comments).
    private val JS_FROM = Regex("""^[ \t]*(?:import|export)\b[^;'"`]*?\bfrom\s*(['"])(.+?)\1""", RegexOption.MULTILINE)
    private val JS_BARE = Regex("""^[ \t]*import\s*(['"])(.+?)\1""", RegexOption.MULTILINE)
    private val JS_DYNAMIC = Regex("""\bimport\(\s*(['"])(.+?)\1\s*\)""")

    // A file path in a script's own strings ('vendor/hls.min.js'): a folder and a file.
    private val JS_ASSET = Regex("""(['"`])([A-Za-z0-9_\-][A-Za-z0-9_\-.]*/[A-Za-z0-9_\-./]*\.$ASSET)\1""")
    private val CSS_IMPORT = Regex("""@import\s+(?:url\(\s*)?(['"])(.+?)\1""")
    private val CSS_URL = Regex("""url\(\s*(['"]?)([^'")]+)\1\s*\)""")
    private val MANIFEST_SRC = Regex("\"src\"\\s*:\\s*\"([^\"]+)\"")
    private val APP_VERSION = Regex("""APP_INFO\s*=\s*\{[^}]*?version:\s*['"]([^'"]+)['"]""")
    private val FILE = Regex("""\.(?:html|$ASSET)$""", RegexOption.IGNORE_CASE)
    private val CODE = Regex("""\.(?:js|mjs|css|webmanifest)$""", RegexOption.IGNORE_CASE)
    private val URL_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    /** Whether a page is plexamp-web's, and not a sign-in or error page put in front of it. */
    fun looksLikePlexampWeb(html: String): Boolean =
        html.contains("js/app.js") && (html.contains("config.js") || html.contains("plexamp-web", ignoreCase = true))

    /** The version the web app says it is (APP_INFO in js/plex.js), or null. */
    fun appVersion(path: String, text: String): String? =
        if (path.endsWith("plex.js")) APP_VERSION.find(text)?.groupValues?.get(1) else null

    /** Paths the player's service answers (not files): never saved, never answered from the saved copy. */
    fun isServicePath(path: String): Boolean =
        path == "signed-out" || path.startsWith("api/") || path.startsWith("pms/") || path.startsWith("ollama/")

    /** A path that could be one of the app's own files. */
    fun isFilePath(path: String): Boolean =
        path.isNotEmpty() && !isServicePath(path) && FILE.containsMatchIn(path) &&
            path.split('/').none { it.isEmpty() || it.startsWith(".") }

    fun kindOf(path: String): Kind {
        val p = path.lowercase(Locale.ROOT)
        return when {
            p.isEmpty() || p.endsWith(".html") -> Kind.HTML
            p.endsWith(".js") || p.endsWith(".mjs") -> Kind.JS
            p.endsWith(".css") -> Kind.CSS
            p.endsWith(".webmanifest") || p.endsWith("manifest.json") -> Kind.MANIFEST
            else -> Kind.OTHER
        }
    }

    /** The files the file at `path` (with its contents, `text`) refers to. */
    fun find(base: URI, path: String, text: String): List<Ref> {
        val found = ArrayList<Ref>()
        fun add(from: String, spec: String, required: Boolean) {
            val target = resolve(base, from, spec) ?: return
            if (isFilePath(target)) found += Ref(target, required)
        }
        when (kindOf(path)) {
            Kind.HTML -> HTML_REF.findAll(text).forEach { m ->
                val spec = m.groupValues[2]
                add(path, spec, CODE.containsMatchIn(spec.substringBefore('?').substringBefore('#')))
            }
            Kind.JS -> {
                for (rx in listOf(JS_FROM, JS_BARE, JS_DYNAMIC)) {
                    rx.findAll(text).forEach { m ->
                        val spec = m.groupValues[2]
                        // (Not bare names like "hls.js": a browser can't load those either.)
                        if (spec.startsWith("./") || spec.startsWith("../") || spec.startsWith("/")) add(path, spec, true)
                    }
                }
                // Paths in the script's own strings are the page's, so relative to it.
                val rest = JS_DYNAMIC.replace(JS_BARE.replace(JS_FROM.replace(text, " "), " "), " ")
                JS_ASSET.findAll(rest).forEach { m -> add(INDEX, m.groupValues[2], false) }
            }
            Kind.CSS -> {
                CSS_IMPORT.findAll(text).forEach { m -> add(path, m.groupValues[2], true) }
                CSS_URL.findAll(text).forEach { m -> add(path, m.groupValues[2], false) }
            }
            Kind.MANIFEST -> MANIFEST_SRC.findAll(text).forEach { m -> add(path, m.groupValues[1], false) }
            Kind.OTHER -> Unit
        }
        // (Once each: needed if any mention of it needs it.)
        return found.groupBy { it.path }.map { (target, refs) -> Ref(target, refs.any { it.required }) }
    }

    /** `spec`, as written in the file at `from`, as a path under `base`; null where it's elsewhere. */
    fun resolve(base: URI, from: String, spec: String): String? {
        val s = spec.trim().substringBefore('#').substringBefore('?')
        if (s.isEmpty() || s.startsWith("//") || URL_SCHEME.containsMatchIn(s)) return null
        val target = try {
            base.resolve(URI(null, null, from, null)).resolve(URI(null, null, s, null))
        } catch (e: Exception) {
            return null
        }
        return relative(base, target.scheme, target.host, target.port, target.rawPath)
    }

    /**
     * The path of an address under `base` ("" for the base itself), or null where the
     * address is elsewhere: another server, another port, outside the base's folder.
     */
    fun relative(base: URI, scheme: String?, host: String?, port: Int, rawPath: String?): String? {
        if (scheme == null || !scheme.equals(base.scheme, ignoreCase = true)) return null
        if (host == null || !host.trim('[', ']').equals(base.host?.trim('[', ']'), ignoreCase = true)) return null
        if (portOf(scheme, port) != portOf(base.scheme, base.port)) return null
        val path = rawPath ?: return null
        val basePath = base.rawPath ?: return null
        if (!path.startsWith(basePath)) return null
        val rel = path.substring(basePath.length)
        if (rel.split('/').any { it == ".." || it == "." }) return null
        return rel
    }

    private fun portOf(scheme: String, port: Int) = when {
        port != -1 -> port
        scheme.equals("https", ignoreCase = true) -> 443
        scheme.equals("http", ignoreCase = true) -> 80
        else -> -1
    }

    /**
     * Whether what a server answered for `path` is that kind of file. A player sends its
     * page for any address it hasn't a file for, so a script's address answered with a
     * page means there's no such script.
     */
    fun typeMatches(path: String, contentType: String?): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return true
        val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "html", "" -> type == "text/html"
            "js", "mjs" -> "javascript" in type || "ecmascript" in type
            "css" -> type == "text/css"
            "json" -> "json" in type
            "webmanifest" -> "json" in type || "manifest" in type
            "svg" -> type == "image/svg+xml"
            "png", "jpg", "jpeg", "webp", "gif", "ico" -> type.startsWith("image/")
            "woff", "woff2", "ttf" -> "font" in type || type == "application/octet-stream"
            "txt" -> type == "text/plain"
            else -> type != "text/html"
        }
    }

    /** A Content-Type for a file whose server didn't say. */
    fun guessType(path: String): String = when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "html", "" -> "text/html"
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        "webmanifest" -> "application/manifest+json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "ico" -> "image/x-icon"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }
}
