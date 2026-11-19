package io.github.kamakazedan.plexampweb.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private var now = System.currentTimeMillis()
    private val root by lazy { File(temp.root, "downloads") }
    private fun store() = DownloadStore(root) { now }

    private fun playlist(id: String, vararg tracks: String) = SavedPlaylist(id, "List $id", added = now, tracks = tracks.toList())

    // A track downloaded, as a sync does it.
    private fun download(store: DownloadStore, id: String, text: String, cover: String? = null): Boolean {
        val part = store.partFile(id, "v-$text")
        part.writeText(text)
        val name = store.fileName(id, "v-$text", "flac")
        return store.commit(SavedTrack(id, name, part.length(), "audio/flac", "v-$text", cover), part)
    }

    @Test
    fun playlistsAndTheirTracksComeAndGo() {
        val store = DownloadStore(root) { now }
        store.addPlaylist(playlist("10", "1", "2"), """{"playlist":{},"items":[]}""")
        store.addPlaylist(playlist("11", "2", "3"), "{}")
        assertEquals(listOf("10", "11"), store.index().playlists.map { it.id })
        assertEquals("""{"playlist":{},"items":[]}""", store.playlistJson("10"))
        assertTrue(download(store, "1", "one", cover = "/c/1"))
        assertTrue(download(store, "2", "two"))
        store.putCover("/c/1", store.coverPart("/c/1").apply { writeText("jpeg") })
        assertEquals("one", store.trackFile(store.index().tracks.getValue("1").file).readText())

        // A playlist removed: its tracks no other playlist has go, with their covers.
        val generation = store.generation
        store.removePlaylist("10")
        assertNotEquals(generation, store.generation)
        assertEquals(setOf("2"), store.index().tracks.keys)
        assertTrue(store.index().covers.isEmpty())
        assertNull(store.playlistJson("10"))
        assertEquals(listOf("2-"), root.resolve("tracks").list()!!.map { it.take(2) })
        assertEquals(0, root.resolve("covers").list()!!.size)
    }

    @Test
    fun aPlaylistsSizeIsCountedAsWhatDownloadsUseIs() {
        val store = store()
        store.addPlaylist(playlist("10", "1", "2", "2"), "{}")
        download(store, "1", "one", cover = "/c/1")
        download(store, "2", "two!", cover = "/c/1")
        store.putCover("/c/1", store.coverPart("/c/1").apply { writeText("jpeg") })
        // Its tracks and their album's cover, each once: all there is, here.
        assertEquals(3L + 4L + 4L, store.bytesOf(store.index().playlist("10")!!))
        assertEquals(store.bytesUsed(), store.bytesOf(store.index().playlist("10")!!))
    }

    @Test
    fun askedAboutAgain() {
        val store = store()
        store.addPlaylist(playlist("10", "1").copy(checkedAt = now), "{}")
        val generation = store.generation
        store.askAgain()
        assertEquals(0L, store.index().playlist("10")!!.checkedAt)
        assertNotEquals(generation, store.generation)
        assertEquals(0L, store().index().playlist("10")!!.checkedAt)
    }

    @Test
    fun aTrackNoPlaylistHasAnyMoreIsntKept() {
        val store = store()
        store.addPlaylist(playlist("10", "1"), "{}")
        store.removePlaylist("10")
        val part = store.partFile("1", "v")
        part.writeText("late")
        assertFalse(store.commit(SavedTrack("1", store.fileName("1", "v", "flac"), 4, "audio/flac", "v", null), part))
        assertFalse(part.exists())
        assertTrue(store.index().tracks.isEmpty())
    }

    @Test
    fun aNewVersionReplacesTheOldFile() {
        val store = store()
        store.addPlaylist(playlist("10", "1"), "{}")
        download(store, "1", "old")
        val old = store.trackFile(store.index().tracks.getValue("1").file)
        download(store, "1", "new")
        assertFalse(old.exists())
        assertEquals("new", store.trackFile(store.index().tracks.getValue("1").file).readText())
        // ...and one that didn't play is let go, to come again.
        store.dropTrack("1")
        assertTrue(store.index().tracks.isEmpty())
        assertEquals(0, root.resolve("tracks").list()!!.size)
    }

    @Test
    fun keptOnDiskAtEachFlush() {
        val store = store()
        store.addPlaylist(playlist("10", "1"), "{}")
        download(store, "1", "one")
        store.refreshPlaylist("10", "Renamed", listOf("1", "5"), "{}")
        store.setProblems(mapOf("10" to SavedPlaylist.FAILED))
        store.flush()
        val again = store()
        assertEquals(store.index(), again.index())
        assertEquals("Renamed", again.index().playlist("10")!!.title)
        assertEquals(now, again.index().playlist("10")!!.checkedAt)
        assertEquals(SavedPlaylist.FAILED, again.index().playlist("10")!!.problem)
        assertEquals(3L, again.bytesUsed())
        // (A playlist removed meanwhile isn't brought back.)
        again.removePlaylist("10")
        again.refreshPlaylist("10", "Back", listOf("1"), "{}")
        assertNull(again.index().playlist("10"))
    }

    @Test
    fun aDamagedIndexStartsAfresh() {
        root.mkdirs()
        root.resolve("index.json").writeText("{ not json")
        assertEquals(DownloadIndex(), store().index())
    }

    @Test
    fun theSweepLetsGoOfWhatTheIndexDoesntName() {
        val store = store()
        store.addPlaylist(playlist("10", "1"), "{}")
        download(store, "1", "one")
        store.flush()
        val tracks = root.resolve("tracks")
        tracks.resolve("9-stray.flac").writeText("x")
        val fresh = tracks.resolve("2-abc.part").apply { writeText("coming") }
        val stale = tracks.resolve("3-abc.part").apply { writeText("old") }
        stale.setLastModified(now - 3 * 24 * 60 * 60 * 1000L)
        fresh.setLastModified(now)
        root.resolve("playlists/12.json").writeText("{}")
        root.resolve("covers").mkdirs()
        root.resolve("covers/stray").writeText("x")
        store.sweep()
        assertEquals(setOf(store.index().tracks.getValue("1").file, "2-abc.part"), tracks.list()!!.toSet())
        assertEquals(setOf("10.json"), root.resolve("playlists").list()!!.toSet())
        assertEquals(0, root.resolve("covers").list()!!.size)
    }

    @Test
    fun clearLetsGoOfEverything() {
        val store = store()
        store.addPlaylist(playlist("10", "1"), "{}")
        download(store, "1", "one")
        store.clear()
        assertFalse(root.exists())
        assertEquals(DownloadIndex(), store.index())
        assertEquals(DownloadIndex(), store().index())
    }

    @Test
    fun onlyIdsAsTheServerHasThem() {
        val store = store()
        assertNull(store.playlistJson("../index"))
        try {
            store.addPlaylist(playlist("../x"), "{}")
            throw AssertionError("A playlist id that isn't one was kept.")
        } catch (e: IllegalArgumentException) {
            // as it should be
        }
    }
}
