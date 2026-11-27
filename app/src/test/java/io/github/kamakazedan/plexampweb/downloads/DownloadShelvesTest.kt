package io.github.kamakazedan.plexampweb.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadShelvesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private var now = System.currentTimeMillis()
    private val root by lazy { File(temp.root, "downloads") }
    private fun shelves() = DownloadShelves(root) { now }

    private fun playlist(id: String, vararg tracks: String) = SavedPlaylist(id, "List $id", added = now, tracks = tracks.toList())

    // A track downloaded, as a sync does it.
    private fun download(store: DownloadStore, id: String, text: String, cover: String? = null) {
        val part = store.partFile(id, "v-$text")
        part.writeText(text)
        val name = store.fileName(id, "v-$text", "flac")
        assertTrue(store.commit(SavedTrack(id, name, part.length(), "audio/flac", "v-$text", cover), part))
        store.flush()
    }

    @Test
    fun eachServersDownloadsAreKeptApart() {
        val shelves = shelves()
        val home = shelves.store("home")
        val work = shelves.store("work")
        home.addPlaylist(playlist("10", "1"), """{"from":"home"}""")
        work.addPlaylist(playlist("10", "1"), """{"from":"work"}""")
        download(home, "1", "home's")
        download(work, "1", "work's!")

        // The same ids on two servers are two playlists, and two tracks.
        assertEquals(listOf("home", "work"), shelves.servers())
        assertEquals("""{"from":"home"}""", shelves.store("home").playlistJson("10"))
        assertEquals("""{"from":"work"}""", shelves.store("work").playlistJson("10"))
        assertEquals("home's", home.trackFile(home.index().tracks.getValue("1").file).readText())
        assertEquals("work's!", work.trackFile(work.index().tracks.getValue("1").file).readText())
        assertEquals(6L + 7L, shelves.bytesUsed())

        // As they're read again (the app started again).
        val again = shelves()
        assertEquals(listOf("home", "work"), again.servers())
        assertEquals(setOf("1"), again.storeIfAny("work")!!.index().tracks.keys)

        // One server's gone: the other's as it was.
        again.remove("home")
        assertEquals(listOf("work"), again.servers())
        assertNull(again.storeIfAny("nowhere"))
        assertEquals(listOf("10"), shelves().store("work").index().playlists.map { it.id })
        assertEquals(7L, shelves().bytesUsed())
    }

    @Test
    fun onlyAServersIdNamesItsPlace() {
        val shelves = shelves()
        assertNull(shelves.storeIfAny("../work"))
        assertNull(shelves.storeIfAny(""))
        assertNull(shelves.storeIfAny(null))
        assertThrows { shelves.store("../work") }
        assertThrows { shelves.store("a/b") }
        shelves.remove("..")
        assertTrue(temp.root.exists())
    }

    @Test
    fun everythingGoes() {
        val shelves = shelves()
        shelves.store("home").addPlaylist(playlist("10", "1"), "{}")
        shelves.store("work").addPlaylist(playlist("11", "2"), "{}")
        val home = shelves.store("home")
        shelves.clear()
        assertFalse(root.exists())
        assertTrue(shelves.servers().isEmpty())
        // (A store that was open forgets what it had, so nothing's served from it.)
        assertTrue(home.index().playlists.isEmpty())
    }

    @Test
    fun placesWithNothingInThemAreSweptAway() {
        val shelves = shelves()
        shelves.store("home").addPlaylist(playlist("10", "1"), "{}")
        val work = shelves.store("work")
        work.addPlaylist(playlist("11", "2"), "{}")
        work.removePlaylist("11")
        File(root, "servers/not a server").mkdirs()
        shelves.sweep()
        assertEquals(listOf("home"), shelves().servers())
        assertFalse(File(root, "servers/not a server").exists())
    }

    // ---------- from before 0.3.0 ----------

    // Downloads as 0.2 kept them, straight in downloads/.
    private fun oldDownloads(): DownloadStore {
        val old = DownloadStore(root) { now }
        old.addPlaylist(playlist("10", "1", "2"), """{"playlist":{"title":"Road trip"},"items":[]}""")
        download(old, "1", "one", cover = "/c/1")
        download(old, "2", "two")
        old.putCover("/c/1", old.coverPart("/c/1").apply { writeText("jpeg") })
        old.update { it.copy(syncedAt = 5) }
        old.flush()
        return old
    }

    @Test
    fun downloadsFromBefore030MoveToTheirServersPlace() {
        oldDownloads()
        val shelves = shelves()
        assertTrue(shelves.hasOld())
        assertTrue(shelves.servers().isEmpty())

        shelves.adoptOld("home", "owner-a")
        assertFalse(shelves.hasOld())
        assertEquals(listOf("home"), shelves.servers())
        val home = shelves.store("home")
        val index = home.index()
        assertEquals(listOf("10"), index.playlists.map { it.id })
        assertEquals(setOf("1", "2"), index.tracks.keys)
        assertEquals(5L, index.syncedAt)
        assertEquals("owner-a", index.owner)
        assertEquals("one", home.trackFile(index.tracks.getValue("1").file).readText())
        assertEquals("jpeg", home.coverFile(index.covers.getValue("/c/1")).readText())
        assertEquals("""{"playlist":{"title":"Road trip"},"items":[]}""", home.playlistJson("10"))
        // Nothing's left where they were.
        assertEquals(setOf("servers"), root.list()!!.toSet())
        // As they're read again.
        assertEquals("owner-a", shelves().store("home").index().owner)
    }

    @Test
    fun aMoveCutShortCarriesOn() {
        oldDownloads()
        // (Cut short with the tracks moved, and one cover, but not the rest.)
        val target = File(root, "servers/home").apply { mkdirs() }
        assertTrue(File(root, "tracks").renameTo(File(target, "tracks")))
        val covers = File(root, "covers").listFiles()!!
        File(target, "covers").mkdirs()
        assertTrue(covers[0].renameTo(File(target, "covers/${covers[0].name}")))

        val shelves = shelves()
        assertTrue(shelves.hasOld())
        shelves.adoptOld("home", "owner-a")
        val home = shelves.store("home")
        val index = home.index()
        assertEquals(setOf("1", "2"), index.tracks.keys)
        index.tracks.values.forEach { assertTrue(home.trackFile(it.file).isFile) }
        assertEquals("jpeg", home.coverFile(index.covers.getValue("/c/1")).readText())
        assertNotNull(home.playlistJson("10"))
        assertEquals(setOf("servers"), root.list()!!.toSet())
    }

    @Test
    fun downloadsFromBefore030CanBeLetGo() {
        oldDownloads()
        val shelves = shelves()
        shelves.store("work").addPlaylist(playlist("11", "3"), "{}")
        shelves.dropOld()
        assertFalse(shelves.hasOld())
        assertEquals(setOf("servers"), root.list()!!.toSet())
        assertEquals(listOf("work"), shelves.servers())
    }

    private fun assertThrows(block: () -> Unit) {
        val threw = try {
            block()
            false
        } catch (e: IllegalArgumentException) {
            true
        }
        assertTrue(threw)
    }
}
