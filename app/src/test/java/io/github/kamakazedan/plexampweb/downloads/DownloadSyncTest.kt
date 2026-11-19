package io.github.kamakazedan.plexampweb.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class DownloadSyncTest {
    @get:Rule
    val temp = TemporaryFolder()

    private var now = 10_000_000L
    private lateinit var store: DownloadStore
    private val plex = FakePlex()

    /** A Plex server, as DownloadSync sees one. */
    private class FakePlex : PlexSource {
        val playlists = HashMap<String, FetchedPlaylist>()
        val downloads = ArrayList<String>()
        val conversions = ArrayList<Pair<String, Int>>()
        val covers = ArrayList<String>()
        val failing = HashSet<String>()
        val troubled = HashSet<String>()
        var unreachable = false
        var signIn = false
        var onDownload: (String) -> Unit = {}

        override fun playlist(id: String): FetchedPlaylist? {
            check()
            return playlists[id]
        }

        override fun download(spec: TrackSpec, to: File, progress: (Long) -> Unit) {
            check(spec.id)
            downloads += spec.id
            to.writeBytes(ByteArray(spec.partSize.toInt()))
            onDownload(spec.id)
        }

        override fun convert(spec: TrackSpec, kbps: Int, to: File, progress: (Long) -> Unit): String {
            check(spec.id)
            conversions += spec.id to kbps
            to.writeBytes(ByteArray(kbps))
            return "aac"
        }

        override fun cover(path: String, to: File) {
            check()
            covers += path
            to.writeText("jpeg")
        }

        private fun check(id: String? = null) {
            if (unreachable) throw PlexSource.Unreachable()
            if (signIn) throw PlexSource.SignInRefused()
            if (id in failing) throw PlexSource.TrackFailed("The server answered 500.")
            if (id in troubled) throw IOException("Connection reset.")
        }
    }

    @Before
    fun setUp() {
        store = DownloadStore(File(temp.root, "downloads")) { now }
        // (Tracks 1 and 2 are on one album.)
        plex.playlists["10"] = PlexSamples.fetched("10", "Road trip", listOf(PlexSamples.track("1", thumb = ALBUM), PlexSamples.track("2", thumb = ALBUM)))
        plex.playlists["11"] = PlexSamples.fetched("11", "Calm", listOf(PlexSamples.track("2", thumb = ALBUM), PlexSamples.track("3", thumb = OTHER_ALBUM)))
        plex.playlists["12"] = PlexSamples.fetched("12", "Later", listOf(PlexSamples.track("4")))
    }

    // As adding one does it: the playlist as the server has it, just now.
    private fun add(id: String) {
        val p = plex.playlists.getValue(id)
        store.addPlaylist(SavedPlaylist(id, p.title, added = now, tracks = p.tracks.map { it.id }, checkedAt = now), p.json)
    }

    private fun sync(settings: DownloadSettings = DownloadSettings(), room: () -> Long = { Long.MAX_VALUE }, stopped: () -> Boolean = { false }) =
        DownloadSync(store, plex, settings, room, clock = { now }, stopped = stopped).run()

    private fun later() {
        now += 10 * 60 * 1000L
    }

    private fun problem(id: String) = store.index().playlist(id)!!.problem

    private companion object {
        const val ALBUM = "/library/metadata/90/thumb/1"
        const val OTHER_ALBUM = "/library/metadata/91/thumb/1"
    }

    @Test
    fun eachTrackOnceWithItsCover() {
        add("10")
        add("11")
        val progress = ArrayList<DownloadSync.Progress>()
        val outcome = DownloadSync(store, plex, DownloadSettings(), { Long.MAX_VALUE }, clock = { now }, onProgress = { progress += it }).run()
        assertEquals(DownloadSync.Outcome.DONE, outcome)
        assertEquals(listOf("1", "2", "3"), plex.downloads)
        assertEquals(setOf("1", "2", "3"), store.index().tracks.keys)
        assertTrue(store.index().tracks.values.all { store.trackFile(it.file).length() == 1000L && it.mime == "audio/flac" })
        // Each album's cover, once.
        assertEquals(listOf(ALBUM, OTHER_ALBUM), plex.covers)
        assertEquals(setOf(ALBUM, OTHER_ALBUM), store.index().covers.keys)
        assertNull(problem("10"))
        assertEquals(now, store.index().syncedAt)
        assertEquals(DownloadSync.Progress(3, 3, null), progress.last())
        assertEquals("10", progress.first().playlist)
        // Kept on disk.
        assertEquals(store.index(), DownloadStore(File(temp.root, "downloads")).index())
        // Nothing more to do next time.
        later()
        assertEquals(DownloadSync.Outcome.DONE, sync())
        assertEquals(3, plex.downloads.size)
    }

    @Test
    fun progressCountsWhatsOnThePhoneAlready() {
        add("10")
        sync()
        // Put in on the server: 2 of its 3 tracks on the phone already, the third to come.
        plex.playlists["10"] = PlexSamples.fetched("10", "Road trip", listOf(PlexSamples.track("1", thumb = ALBUM), PlexSamples.track("2", thumb = ALBUM), PlexSamples.track("5")))
        later()
        val progress = ArrayList<DownloadSync.Progress>()
        val counting = { DownloadSync(store, plex, DownloadSettings(), { Long.MAX_VALUE }, clock = { now }, onProgress = { progress += it }).run() }
        counting()
        assertEquals(DownloadSync.Progress(2, 3, "10"), progress.first())
        assertEquals(DownloadSync.Progress(3, 3, null), progress.last())
        // Nothing to download: nothing counted.
        progress.clear()
        later()
        counting()
        assertEquals(listOf(DownloadSync.Progress(0, 0, null)), progress.distinct())
    }

    @Test
    fun askedToCheckRightAfterTheLastTime() {
        add("10")
        sync()
        // Changed on the server a minute after: not asked about again so soon...
        plex.playlists["10"] = PlexSamples.fetched("10", "Road trip", listOf(PlexSamples.track("5")))
        now += 60_000L
        sync()
        assertEquals(listOf("1", "2"), store.index().playlist("10")!!.tracks)
        // ...unless asked to check.
        store.askAgain()
        assertEquals(DownloadSync.Outcome.DONE, sync())
        assertEquals(listOf("5"), store.index().playlist("10")!!.tracks)
        assertEquals(setOf("5"), store.index().tracks.keys)
    }

    @Test
    fun keptUpToDateWithTheServer() {
        add("10")
        add("11")
        sync()
        val before = store.trackFile(store.index().tracks.getValue("2").file)
        // On the server: track 1 taken out of Road trip, 5 put in, and 2's file replaced.
        plex.playlists["10"] = PlexSamples.fetched(
            "10",
            "Road trip!",
            listOf(PlexSamples.track("5"), PlexSamples.track("2", size = 2000, part = "/library/parts/2/1800000000/file.flac")),
        )
        later()
        assertEquals(DownloadSync.Outcome.DONE, sync())
        assertEquals(listOf("5", "2", "3"), store.index().playlists.flatMap { it.tracks }.distinct())
        assertEquals(setOf("2", "3", "5"), store.index().tracks.keys)
        assertEquals(2000L, store.index().tracks.getValue("2").bytes)
        assertFalse(before.exists())
        assertEquals("Road trip!", store.index().playlist("10")!!.title)
        assertEquals(3, File(temp.root, "downloads/tracks").list()!!.size)
    }

    @Test
    fun aPlaylistGoneFromTheServerGoesFromThePhone() {
        add("10")
        add("11")
        sync()
        plex.playlists.remove("11")
        later()
        assertEquals(DownloadSync.Outcome.DONE, sync())
        assertEquals(listOf("10"), store.index().playlists.map { it.id })
        assertEquals(setOf("1", "2"), store.index().tracks.keys)
    }

    @Test
    fun tracksThatDontFitAreLeftOutAndThePlaylistSaysSo() {
        add("10")
        add("11")
        val budget = 2500L
        assertEquals(DownloadSync.Outcome.DONE, sync(room = { budget - store.bytesUsed() }))
        assertEquals(setOf("1", "2"), store.index().tracks.keys)
        assertNull(problem("10"))
        assertEquals(SavedPlaylist.SPACE, problem("11"))
        // With room again, it comes, and the playlist's whole.
        later()
        sync()
        assertEquals(setOf("1", "2", "3"), store.index().tracks.keys)
        assertNull(problem("11"))
    }

    @Test
    fun aTrackTheServerWontGiveIsTriedAgainNextTime() {
        add("10")
        add("11")
        plex.failing += "2"
        assertEquals(DownloadSync.Outcome.DONE, sync())
        assertEquals(setOf("1", "3"), store.index().tracks.keys)
        assertEquals(SavedPlaylist.FAILED, problem("10"))
        assertEquals(SavedPlaylist.FAILED, problem("11"))
        plex.failing.clear()
        sync()
        assertEquals(setOf("1", "2", "3"), store.index().tracks.keys)
        assertNull(problem("10"))
    }

    @Test
    fun connectionTroubleTimeAfterTimeIsTriedAgainLater() {
        add("10")
        add("11")
        add("12")
        plex.troubled += listOf("1", "2", "3")
        assertEquals(DownloadSync.Outcome.RETRY, sync())
        assertTrue(store.index().tracks.isEmpty())
        // (Once, it's that track's: the rest go on.)
        plex.troubled.clear()
        plex.troubled += "1"
        assertEquals(DownloadSync.Outcome.DONE, sync())
        assertEquals(setOf("2", "3", "4"), store.index().tracks.keys)
    }

    @Test
    fun theQualityConvertsAndChangingItDownloadsAgain() {
        add("10")
        assertEquals(DownloadSync.Outcome.DONE, sync(DownloadSettings(quality = "128")))
        assertEquals(listOf("1" to 128, "2" to 128), plex.conversions)
        assertTrue(store.index().tracks.values.all { it.file.endsWith(".aac") && it.mime == "audio/aac" && it.bytes == 128L })
        assertEquals(DownloadSync.Outcome.DONE, sync(DownloadSettings(quality = "original")))
        assertEquals(listOf("1", "2"), plex.downloads)
        assertTrue(store.index().tracks.values.all { it.file.endsWith(".flac") })
        assertEquals(2, File(temp.root, "downloads/tracks").list()!!.size)
    }

    @Test
    fun aPlaylistAddedMeanwhileComesToo() {
        add("10")
        plex.onDownload = { id -> if (id == "1") add("12") }
        assertEquals(DownloadSync.Outcome.DONE, sync())
        assertEquals(setOf("1", "2", "4"), store.index().tracks.keys)
    }

    @Test
    fun whatAPlaylistWouldTake() {
        add("10")
        sync()
        val index = store.index()
        val calm = plex.playlists.getValue("11").tracks
        // Track 2's on the phone already: only 3 to come.
        assertEquals(1000L, DownloadSync.bytesFor(calm, index, DownloadSettings()))
        assertEquals(0L, DownloadSync.bytesFor(calm, index, DownloadSettings(), except = setOf("3")))
        // At another quality, both: converted, by their length (200 s at 128 kbps).
        assertEquals(2 * 200_000L * 128 / 8, DownloadSync.bytesFor(calm, index, DownloadSettings(quality = "128")))
        assertEquals(listOf("1", "2"), DownloadSync.specsIn(store.playlistJson("10")).map { it.id })
        assertEquals(emptyList<TrackSpec>(), DownloadSync.specsIn("not json"))
    }

    @Test
    fun outOfReachRefusedOrStopped() {
        add("10")
        plex.unreachable = true
        assertEquals(DownloadSync.Outcome.RETRY, sync())
        plex.unreachable = false
        plex.signIn = true
        assertEquals(DownloadSync.Outcome.SIGN_IN, sync())
        plex.signIn = false
        assertEquals(DownloadSync.Outcome.STOPPED, sync(stopped = { true }))
        assertTrue(store.index().tracks.isEmpty())
    }
}
