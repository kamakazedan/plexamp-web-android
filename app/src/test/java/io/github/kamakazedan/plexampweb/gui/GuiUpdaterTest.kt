package io.github.kamakazedan.plexampweb.gui

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GuiUpdaterTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val base = "https://music.example.com/"

    /** A player, as nginx serves one: its page for any address that isn't a file (try_files … /index.html). */
    private class Player(val base: String, val files: MutableMap<String, Pair<String, ByteArray>>) : GuiFetcher {
        val asked = ArrayList<String>()
        var conditional = 0

        override fun get(url: String, etag: String?, lastModified: String?): Fetched {
            synchronized(asked) { asked += url }
            val path = url.removePrefix(base).ifEmpty { GuiRefs.INDEX }
            val file = files[path] ?: files.getValue(GuiRefs.INDEX).takeIf { !path.endsWith("/") }
            val (type, body) = file ?: return Fetched(404, "text/plain", null, null, emptyMap(), null)
            val tag = "\"" + GuiStore.sha256(body).take(16) + "\""
            if (etag != null) synchronized(asked) { conditional++ }
            if (etag == tag) return Fetched(304, null, tag, null, emptyMap(), null)
            return Fetched(200, type, tag, null, mapOf("Content-Security-Policy" to "default-src 'self'"), body)
        }
    }

    private fun app(): MutableMap<String, Pair<String, ByteArray>> = mutableMapOf(
        GuiRefs.INDEX to html(
            """
            <title>plexamp-web</title>
            <link rel="manifest" href="manifest.webmanifest" />
            <link rel="stylesheet" href="css/app.css" />
            <script src="config.js"></script>
            <script type="module" src="js/app.js"></script>
            """,
        ),
        "manifest.webmanifest" to ("application/manifest+json" to """{ "icons": [ { "src": "icons/icon-192.png" } ] }""".toByteArray()),
        "css/app.css" to ("text/css" to ".a { background: url(../icons/check.svg) }".toByteArray()),
        "config.js" to js("window.PW_CONFIG = {};"),
        "js/app.js" to js("import { a } from './a.js';\nimport './views/home.js';\nconst HLS = 'vendor/hls.min.js';\nconst gone = 'vendor/not-there.js';"),
        "js/a.js" to js("export const a = 1;"),
        "js/views/home.js" to js("import { a } from '../a.js';"),
        "js/plex.js" to js("export const APP_INFO = { product: 'plexamp-web', version: '1.2.3' };"),
        "vendor/hls.min.js" to js("/* hls */"),
        "icons/icon-192.png" to ("image/png" to byteArrayOf(1, 2, 3)),
        "icons/check.svg" to ("image/svg+xml" to "<svg/>".toByteArray()),
    )

    private fun html(text: String) = "text/html" to text.trimIndent().toByteArray()

    private fun js(text: String) = "application/javascript" to text.toByteArray()

    private fun update(store: GuiStore, player: Player, previous: GuiSnapshot? = store.current(), extra: Collection<String> = emptyList()) =
        runBlocking { GuiUpdater(store, player).update(base, previous, extra) }

    @Test
    fun everyFileThePlayerNeedsIsSaved() {
        val store = GuiStore(temp.newFolder())
        val outcome = update(store, Player(base, app())) as GuiUpdater.Outcome.Saved
        assertTrue(outcome.changed)
        assertEquals(
            setOf(
                GuiRefs.INDEX, "manifest.webmanifest", "css/app.css", "config.js", "js/app.js", "js/a.js", "js/views/home.js",
                "vendor/hls.min.js", "icons/icon-192.png", "icons/check.svg",
            ),
            outcome.snapshot.files.keys,
        )
        assertEquals(outcome.snapshot, store.current())
        val page = outcome.snapshot.files.getValue(GuiRefs.INDEX)
        assertEquals("default-src 'self'", page.headers["Content-Security-Policy"])
        assertTrue(store.readBlob(page.blob).toString(Charsets.UTF_8).contains("js/app.js"))
    }

    @Test
    fun aSecondCheckAsksOnlyWhatChangedAndKeepsTheSameVersion() {
        val store = GuiStore(temp.newFolder())
        val player = Player(base, app())
        val first = update(store, player) as GuiUpdater.Outcome.Saved
        val second = update(store, player) as GuiUpdater.Outcome.Saved
        assertFalse(second.changed)
        assertEquals(first.snapshot.id, second.snapshot.id)
        assertEquals(first.snapshot.files.size, player.conditional)
    }

    @Test
    fun aChangedFileIsANewVersionAndTheOldOneIsCleanedAway() {
        val store = GuiStore(temp.newFolder())
        val files = app()
        val player = Player(base, files)
        val first = update(store, player) as GuiUpdater.Outcome.Saved
        val oldBlob = first.snapshot.files.getValue("js/a.js").blob
        files["js/a.js"] = js("export const a = 2;")
        val second = update(store, player) as GuiUpdater.Outcome.Saved
        assertTrue(second.changed)
        assertNotEquals(first.snapshot.id, second.snapshot.id)
        store.clean(setOf(second.snapshot.id))
        assertFalse(store.hasBlob(oldBlob))
        assertTrue(second.snapshot.files.values.all { store.hasBlob(it.blob) })
        assertEquals(second.snapshot, GuiStore(store.blobFile("x").parentFile!!.parentFile!!).current())
    }

    @Test
    fun aMissingModuleKeepsTheCopyThereWas() {
        val store = GuiStore(temp.newFolder())
        val files = app()
        val player = Player(base, files)
        val first = update(store, player) as GuiUpdater.Outcome.Saved
        files["js/a.js"] = js("import './gone.js';")
        val failed = update(store, player) as GuiUpdater.Outcome.Failed
        assertEquals(GuiUpdater.Reason.MISSING, failed.reason)
        assertEquals(first.snapshot, store.current())
    }

    @Test
    fun aModuleOnlyMentionedFirstIsStillNeededOnceImported() {
        val store = GuiStore(temp.newFolder())
        val files = app()
        // Mentioned as a path by the page's first script, imported by a later one: still needed.
        files["js/app.js"] = js("const later = 'js/missing.js';\nimport './a.js';")
        files["js/a.js"] = js("import './missing.js';")
        val failed = update(store, Player(base, files)) as GuiUpdater.Outcome.Failed
        assertEquals(GuiUpdater.Reason.MISSING, failed.reason)
        assertNull(store.current())
    }

    @Test
    fun somethingElseAnsweringIsntSaved() {
        val store = GuiStore(temp.newFolder())
        val portal = mutableMapOf(GuiRefs.INDEX to html("<title>Sign in to the Wi-Fi</title>"))
        val failed = update(store, Player(base, portal)) as GuiUpdater.Outcome.Failed
        assertEquals(GuiUpdater.Reason.NOT_PLAYER, failed.reason)
        assertNull(store.current())
    }

    @Test
    fun theUnreachablePlayerChangesNothing() {
        val store = GuiStore(temp.newFolder())
        val first = update(store, Player(base, app())) as GuiUpdater.Outcome.Saved
        val down = GuiFetcher { _, _, _ -> throw java.io.IOException("no route") }
        val failed = runBlocking { GuiUpdater(store, down).update(base, store.current()) } as GuiUpdater.Outcome.Failed
        assertEquals(GuiUpdater.Reason.UNREACHABLE, failed.reason)
        assertEquals(first.snapshot, store.current())
    }

    /** The player, with `path` not coming this time: no answer at all, or `status`. */
    private fun withBlip(player: Player, path: String, status: Int? = null) = GuiFetcher { url, etag, lastModified ->
        if (url != base + path) return@GuiFetcher player.get(url, etag, lastModified)
        if (status == null) throw java.io.IOException("connection reset")
        Fetched(status, "text/plain", null, null, emptyMap(), null)
    }

    @Test
    fun anOptionalFileThatDoesntComeThisTimeIsKeptAsItWas() {
        for (status in listOf(null, 500, 502, 503, 429, 403)) {
            val store = GuiStore(temp.newFolder())
            val player = Player(base, app())
            val first = update(store, player) as GuiUpdater.Outcome.Saved
            val hls = first.snapshot.files.getValue("vendor/hls.min.js")
            val blip = withBlip(player, "vendor/hls.min.js", status)
            val second = runBlocking { GuiUpdater(store, blip).update(base, store.current()) } as GuiUpdater.Outcome.Saved
            // The same version: nothing to reload, and nothing gone from it.
            assertFalse("$status", second.changed)
            assertEquals("$status", first.snapshot.id, second.snapshot.id)
            assertEquals("$status", hls, second.snapshot.files["vendor/hls.min.js"])
            store.clean(setOf(second.snapshot.id))
            assertTrue("$status", store.hasBlob(hls.blob))
        }
    }

    @Test
    fun anOptionalFileKeptThroughABlipStillComesWithANewVersion() {
        val store = GuiStore(temp.newFolder())
        val files = app()
        val player = Player(base, files)
        val first = update(store, player) as GuiUpdater.Outcome.Saved
        files["js/a.js"] = js("export const a = 2;")
        val second = runBlocking { GuiUpdater(store, withBlip(player, "vendor/hls.min.js")).update(base, store.current()) } as GuiUpdater.Outcome.Saved
        assertTrue(second.changed)
        assertEquals(first.snapshot.files.getValue("vendor/hls.min.js"), second.snapshot.files["vendor/hls.min.js"])
        store.clean(setOf(second.snapshot.id))
        assertTrue(second.snapshot.files.values.all { store.hasBlob(it.blob) })
    }

    @Test
    fun anOptionalFileThePlayerNoLongerHasIsLeftOut() {
        for (status in listOf(404, 410)) {
            val store = GuiStore(temp.newFolder())
            val player = Player(base, app())
            update(store, player)
            val second = runBlocking {
                GuiUpdater(store, withBlip(player, "vendor/hls.min.js", status)).update(base, store.current())
            } as GuiUpdater.Outcome.Saved
            assertTrue("$status", second.changed)
            assertFalse("$status", "vendor/hls.min.js" in second.snapshot.files)
        }
        // ...as where it's answered with the player's page (try_files … /index.html).
        val store = GuiStore(temp.newFolder())
        val files = app()
        val player = Player(base, files)
        update(store, player)
        files.remove("vendor/hls.min.js")
        val second = update(store, player) as GuiUpdater.Outcome.Saved
        assertTrue(second.changed)
        assertFalse("vendor/hls.min.js" in second.snapshot.files)
    }

    @Test
    fun aFileTheAppNeedsThatDoesntComeKeepsTheCopyThereWas() {
        for (status in listOf(null, 503)) {
            val store = GuiStore(temp.newFolder())
            val player = Player(base, app())
            val first = update(store, player) as GuiUpdater.Outcome.Saved
            val failed = runBlocking { GuiUpdater(store, withBlip(player, "js/a.js", status)).update(base, store.current()) }
            assertTrue("$status", failed is GuiUpdater.Outcome.Failed)
            assertEquals("$status", first.snapshot, store.current())
        }
    }

    @Test
    fun filesThePageAskedForAreSavedNextTime() {
        val store = GuiStore(temp.newFolder())
        val files = app()
        files["js/extra.js"] = js("/* loaded by name */")
        val outcome = update(store, Player(base, files), extra = listOf("js/extra.js", "api/health", "js/none.js")) as GuiUpdater.Outcome.Saved
        assertTrue("js/extra.js" in outcome.snapshot.files)
        assertFalse("api/health" in outcome.snapshot.files)
        assertFalse("js/none.js" in outcome.snapshot.files)
    }

    @Test
    fun theWebAppsVersionIsRead() {
        val store = GuiStore(temp.newFolder())
        val files = app()
        files["js/app.js"] = js("import './plex.js';")
        val outcome = update(store, Player(base, files)) as GuiUpdater.Outcome.Saved
        assertEquals("1.2.3", outcome.snapshot.appVersion)
    }

    /**
     * The real plexamp-web: its web/ folder (PLEXAMP_WEB_DIR, set by CI from a checkout of
     * kamakazedan/plexamp-web), served as its container serves it. Every file in it must be
     * saved, and hls.js, which the container adds.
     */
    @Test
    fun theRealPlayerIsSavedWhole() {
        val dir = System.getenv("PLEXAMP_WEB_DIR")?.let(::File)
        assumeTrue("PLEXAMP_WEB_DIR isn't set", dir != null && File(dir, GuiRefs.INDEX).isFile)
        val root = dir!!
        val files = mutableMapOf<String, Pair<String, ByteArray>>()
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            val path = file.relativeTo(root).invariantSeparatorsPath
            files[path] = GuiRefs.guessType(path) to file.readBytes()
        }
        files["vendor/hls.min.js"] = js("/* hls.js */")
        val store = GuiStore(temp.newFolder())
        val outcome = update(store, Player(base, files)) as GuiUpdater.Outcome.Saved
        assertEquals(files.keys, outcome.snapshot.files.keys)
        assertTrue(outcome.snapshot.appVersion!!.matches(Regex("\\d+\\.\\d+\\.\\d+")))
    }
}
