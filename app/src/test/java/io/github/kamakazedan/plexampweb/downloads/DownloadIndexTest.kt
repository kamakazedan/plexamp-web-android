package io.github.kamakazedan.plexampweb.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadIndexTest {
    private val index = DownloadIndex(
        playlists = listOf(
            SavedPlaylist("10", "Road trip", added = 1, tracks = listOf("1", "2", "2"), checkedAt = 5, problem = SavedPlaylist.SPACE),
            SavedPlaylist("11", "Calm", added = 2, tracks = listOf("2", "3")),
        ),
        tracks = mapOf(
            "1" to SavedTrack("1", "1-a.flac", 1000, "audio/flac", "v1", "/library/metadata/1/thumb/2"),
            "2" to SavedTrack("2", "2-b.mp3", 300, "audio/mpeg", "v2", null),
        ),
        covers = mapOf("/library/metadata/1/thumb/2" to "abc"),
        syncedAt = 99,
        name = "Home",
        owner = "0123456789abcdef0123456789abcdef",
    )

    @Test
    fun keptAsJson() {
        assertEquals(index, DownloadIndex.fromJson(index.toJson()))
        assertEquals(DownloadIndex(), DownloadIndex.fromJson("{}"))
        // (From before 0.3.0: no server's name, and no one's claimed them yet.)
        val old = DownloadIndex.fromJson(index.copy(name = null, owner = null).toJson())
        assertNull(old.name)
        assertNull(old.owner)
    }

    @Test
    fun whatsWantedAndWhatItTakes() {
        assertEquals(setOf("1", "2", "3"), index.wanted())
        val road = index.playlist("10")!!
        assertEquals(1300L, index.bytesOf(road))
        assertEquals(3, index.savedOf(road))
        assertEquals(1, index.savedOf(index.playlist("11")!!))
        assertNull(index.playlist("12"))
    }
}
