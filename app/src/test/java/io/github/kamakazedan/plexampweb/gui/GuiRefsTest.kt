package io.github.kamakazedan.plexampweb.gui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class GuiRefsTest {
    private val base = URI("https://music.example.com/")

    private fun refs(path: String, text: String, at: URI = base) =
        GuiRefs.find(at, path, text).associate { it.path to it.required }

    @Test
    fun thePagesScriptsAndStylesAreNeededAndItsIconsAreNot() {
        val html = """
            <link rel="manifest" href="manifest.webmanifest" />
            <link rel="icon" href="icons/icon.svg" type="image/svg+xml" />
            <link rel="stylesheet" href="css/app.css?v=2" />
            <script src="config.js"></script>
            <script type="module" src="js/app.js"></script>
            <script src="https://cdn.example.com/x.js"></script>
        """.trimIndent()
        assertEquals(
            mapOf("manifest.webmanifest" to true, "icons/icon.svg" to false, "css/app.css" to true, "config.js" to true, "js/app.js" to true),
            refs(GuiRefs.INDEX, html),
        )
    }

    @Test
    fun aModulesImportsAreNeeded() {
        val js = """
            import { a,
              b } from './state.js';
            import * as ui from "../ui.js";
            import './side-effect.js';
            export { c } from './c.js';
            import Hls from 'hls.js';
            // import nope from './commented-out.js';
            const later = () => import('./lazy.js');
        """.trimIndent()
        assertEquals(
            mapOf("js/views/state.js" to true, "js/ui.js" to true, "js/views/side-effect.js" to true, "js/views/c.js" to true, "js/views/lazy.js" to true),
            refs("js/views/home.js", js),
        )
    }

    @Test
    fun pathsInAScriptsOwnStringsAreThePagesAndNotNeeded() {
        val js = """
            import { x } from './x.js';
            const HLS_JS = 'vendor/hls.min.js';
            const icon = `icons/icon-192.png`;
            const notAPath = 'hls.js';
            const url = `${'$'}{base}/library/sections`;
        """.trimIndent()
        assertEquals(
            mapOf("js/x.js" to true, "vendor/hls.min.js" to false, "icons/icon-192.png" to false),
            refs("js/player.js", js),
        )
    }

    @Test
    fun aStylesheetsImportsAreNeededAndItsPicturesAreNot() {
        val css = """
            @import url("fonts.css");
            .a { background: url(../icons/check.svg) }
            .b { background: url("data:image/svg+xml,%3Csvg%3E") }
        """.trimIndent()
        assertEquals(mapOf("css/fonts.css" to true, "icons/check.svg" to false), refs("css/app.css", css))
    }

    @Test
    fun theManifestsIcons() {
        val manifest = """{ "icons": [ { "src": "icons/icon-192.png", "sizes": "192x192" }, { "src": "icons/icon.svg" } ] }"""
        assertEquals(mapOf("icons/icon-192.png" to false, "icons/icon.svg" to false), refs("manifest.webmanifest", manifest))
    }

    @Test
    fun aPlayerInAFolderKeepsToIt() {
        val sub = URI("https://example.com/music/")
        assertEquals("css/a.css", GuiRefs.resolve(sub, "js/app.js", "../css/a.css"))
        assertEquals("js/b.js", GuiRefs.resolve(sub, "js/app.js", "/music/js/b.js"))
        assertNull(GuiRefs.resolve(sub, "js/app.js", "/elsewhere.js"))
        assertNull(GuiRefs.resolve(sub, "js/app.js", "../../outside.js"))
        assertNull(GuiRefs.resolve(sub, "js/app.js", "https://example.com/music/js/c.js".replace("https", "http")))
        assertNull(GuiRefs.resolve(sub, "js/app.js", "//cdn.example.com/x.js"))
        assertNull(GuiRefs.resolve(sub, "js/app.js", "data:text/javascript,1"))
    }

    @Test
    fun addressesUnderThePlayer() {
        assertEquals("", GuiRefs.relative(base, "https", "music.example.com", -1, "/"))
        assertEquals("js/app.js", GuiRefs.relative(base, "https", "MUSIC.example.com", 443, "/js/app.js"))
        assertNull(GuiRefs.relative(base, "http", "music.example.com", -1, "/js/app.js"))
        assertNull(GuiRefs.relative(base, "https", "music.example.com", 8443, "/js/app.js"))
        assertNull(GuiRefs.relative(base, "https", "other.example.com", -1, "/js/app.js"))
        val v6 = URI("http://[fd00::1]:8080/")
        assertEquals("js/app.js", GuiRefs.relative(v6, "http", "[fd00::1]", 8080, "/js/app.js"))
        assertEquals("js/app.js", GuiRefs.relative(v6, "http", "fd00::1", 8080, "/js/app.js"))
    }

    @Test
    fun theServiceIsNeverAFile() {
        for (path in listOf("api/health", "pms/library/sections", "ollama/api/tags", "signed-out")) {
            assertTrue(path, GuiRefs.isServicePath(path))
            assertFalse(path, GuiRefs.isFilePath(path))
        }
        assertFalse(GuiRefs.isServicePath("js/api.js"))
        assertTrue(GuiRefs.isFilePath("js/api.js"))
        assertFalse(GuiRefs.isFilePath(".env"))
        assertFalse(GuiRefs.isFilePath("js/.hidden.js"))
        assertFalse(GuiRefs.isFilePath("__plexamp-web-android/handoff"))
    }

    @Test
    fun aPageForAScriptMeansNoScript() {
        assertTrue(GuiRefs.typeMatches("js/app.js", "application/javascript"))
        assertTrue(GuiRefs.typeMatches("js/app.js", "text/javascript; charset=utf-8"))
        assertFalse(GuiRefs.typeMatches("js/gone.js", "text/html"))
        assertTrue(GuiRefs.typeMatches(GuiRefs.INDEX, "text/html; charset=utf-8"))
        assertTrue(GuiRefs.typeMatches("manifest.webmanifest", "application/manifest+json"))
        assertTrue(GuiRefs.typeMatches("icons/icon.svg", "image/svg+xml"))
        assertFalse(GuiRefs.typeMatches("icons/icon.png", "text/html"))
        assertTrue(GuiRefs.typeMatches("css/app.css", null))
    }

    @Test
    fun thePlayersOwnPage() {
        assertTrue(GuiRefs.looksLikePlexampWeb("""<title>plexamp-web</title><script src="config.js"></script><script type="module" src="js/app.js"></script>"""))
        assertFalse(GuiRefs.looksLikePlexampWeb("<title>Sign in</title><form></form>"))
        assertFalse(GuiRefs.looksLikePlexampWeb("<title>Attention Required! | Cloudflare</title>"))
    }

    @Test
    fun theWebAppsVersion() {
        val plex = "export const APP_INFO = { product: 'plexamp-web', version: '0.39.4' };"
        assertEquals("0.39.4", GuiRefs.appVersion("js/plex.js", plex))
        assertNull(GuiRefs.appVersion("js/app.js", plex))
    }
}
