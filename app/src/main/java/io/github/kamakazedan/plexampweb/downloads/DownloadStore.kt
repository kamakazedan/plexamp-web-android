package io.github.kamakazedan.plexampweb.downloads

import io.github.kamakazedan.plexampweb.gui.GuiStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Downloads on the phone, in the app's own storage:
 *
 *     index.json             what's kept (DownloadIndex)
 *     playlists/<id>.json    each playlist and its tracks as the server last gave them, for the player offline
 *     tracks/<file>          each track's file, once however many playlists have it
 *     covers/<file>          their albums' covers
 *
 * One lock for all of it, so a sync and the player's changes (a playlist added or
 * removed) take turns. The index is kept in memory, and on disk at each flush: a file
 * the index doesn't name is let go at the next sweep.
 */
class DownloadStore(private val root: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val tracksDir = File(root, "tracks")
    private val coversDir = File(root, "covers")
    private val playlistsDir = File(root, "playlists")
    private val indexFile = File(root, "index.json")
    private val lock = Any()
    private var index = read()
    private var dirty = false

    /** Goes up with each playlist added or removed: a sync under way then goes round again. */
    @Volatile
    var generation = 0L
        private set

    fun index(): DownloadIndex = synchronized(lock) { index }

    /** Changes the index: kept on disk at the next flush. */
    fun update(change: (DownloadIndex) -> DownloadIndex): DownloadIndex = synchronized(lock) {
        index = change(index)
        dirty = true
        index
    }

    /** The index, on disk. */
    fun flush() = synchronized(lock) {
        if (!dirty) return@synchronized
        root.mkdirs()
        GuiStore.writeAtomically(indexFile, index.toJson().toByteArray())
        dirty = false
    }

    // ---------- playlists ----------

    /** A playlist to keep, last, with the server's JSON for it and its tracks (see playlistJson). */
    fun addPlaylist(playlist: SavedPlaylist, json: String) = synchronized(lock) {
        savePlaylistJson(playlist.id, json)
        update { it.copy(playlists = it.playlists.filter { p -> p.id != playlist.id } + playlist) }
        flush()
        generation++
    }

    /** A playlist as the server has it now: its title and tracks (and the JSON for the player offline). */
    fun refreshPlaylist(id: String, title: String, tracks: List<String>, json: String) = synchronized(lock) {
        if (index.playlist(id) == null) return@synchronized // removed meanwhile
        savePlaylistJson(id, json)
        update { idx -> idx.copy(playlists = idx.playlists.map { if (it.id == id) it.copy(title = title, tracks = tracks, checkedAt = clock()) else it }) }
    }

    /** Why some of each playlist's tracks aren't on the phone (null for none of them). */
    fun setProblems(problems: Map<String, String?>) = update { idx ->
        idx.copy(playlists = idx.playlists.map { p -> if (p.id in problems) p.copy(problem = problems[p.id]) else p })
    }

    /** Not kept any more: it, and its tracks no other playlist has. */
    fun removePlaylist(id: String) = synchronized(lock) {
        require(ID.matches(id))
        update { it.copy(playlists = it.playlists.filter { p -> p.id != id }) }
        File(playlistsDir, "$id.json").delete()
        removeUnwanted()
        flush()
        generation++
    }

    /** The server's JSON for a playlist and its tracks: { "playlist": {…}, "items": […] }. */
    fun playlistJson(id: String): String? =
        File(playlistsDir, "$id.json").takeIf { ID.matches(id) && it.isFile }?.let { runCatching { it.readText() }.getOrNull() }

    private fun savePlaylistJson(id: String, json: String) {
        require(ID.matches(id))
        playlistsDir.mkdirs()
        GuiStore.writeAtomically(File(playlistsDir, "$id.json"), json.toByteArray())
    }

    // ---------- tracks ----------

    fun trackFile(name: String) = File(tracksDir, name)

    /** Where a track's download goes as it comes (for that version: one going on from where it got to is the same file). */
    fun partFile(id: String, version: String): File {
        tracksDir.mkdirs()
        return File(tracksDir, "$id-${hashOf(version)}.part")
    }

    /** The name a track's file gets. */
    fun fileName(id: String, version: String, extension: String) = "$id-${hashOf(version)}.$extension"

    /**
     * A track downloaded (to `from`): put in place of the one there was, where some
     * playlist still has it. False where none does any more (removed meanwhile): then it's let go.
     */
    fun commit(saved: SavedTrack, from: File): Boolean = synchronized(lock) {
        if (saved.id !in index.wanted()) {
            from.delete()
            return@synchronized false
        }
        Files.move(from.toPath(), trackFile(saved.file).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        val old = index.tracks[saved.id]
        update { it.copy(tracks = it.tracks + (saved.id to saved)) }
        if (old != null && old.file != saved.file) trackFile(old.file).delete()
        true
    }

    /** A track's file let go (one that didn't play, say): downloaded again at the next sync. */
    fun dropTrack(id: String) = synchronized(lock) {
        val old = index.tracks[id] ?: return@synchronized
        update { it.copy(tracks = it.tracks - id) }
        trackFile(old.file).delete()
        flush()
    }

    /** Tracks no playlist has any more, gone (and covers no track has). */
    fun removeUnwanted() = synchronized(lock) {
        val wanted = index.wanted()
        val gone = index.tracks.values.filter { it.id !in wanted }
        if (gone.isEmpty()) return@synchronized
        update { it.copy(tracks = it.tracks - gone.map { t -> t.id }.toSet()) }
        gone.forEach { trackFile(it.file).delete() }
        removeUnusedCovers()
    }

    // ---------- covers ----------

    fun coverFile(name: String) = File(coversDir, name)

    /** Where a cover goes as it comes. */
    fun coverPart(path: String): File {
        coversDir.mkdirs()
        return File(coversDir, "${hashOf(path)}.part")
    }

    fun putCover(path: String, from: File) = synchronized(lock) {
        val name = hashOf(path)
        Files.move(from.toPath(), coverFile(name).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        update { it.copy(covers = it.covers + (path to name)) }
    }

    private fun removeUnusedCovers() {
        val used = index.tracks.values.mapNotNullTo(HashSet()) { it.cover }
        val gone = index.covers.filterKeys { it !in used }
        if (gone.isEmpty()) return
        update { it.copy(covers = it.covers - gone.keys) }
        gone.values.forEach { coverFile(it).delete() }
    }

    // ---------- all of it ----------

    /** The bytes downloads take: tracks and covers. */
    fun bytesUsed(): Long = synchronized(lock) {
        index.tracks.values.sumOf { it.bytes } + index.covers.values.sumOf { coverFile(it).length() }
    }

    /**
     * Files the index doesn't name, gone: left by a sync that stopped part-way (one coming
     * in is kept a while, to go on from where it got to), or a crash before a flush.
     */
    fun sweep() = synchronized(lock) {
        val now = clock()
        val tracks = index.tracks.values.mapTo(HashSet()) { it.file }
        tracksDir.listFiles()?.forEach { f ->
            val stale = f.name.endsWith(".part") && now - f.lastModified() > PART_KEPT_MS
            if ((f.name !in tracks && !f.name.endsWith(".part")) || stale) f.delete()
        }
        val covers = index.covers.values.toHashSet()
        coversDir.listFiles()?.forEach { f -> if (f.name !in covers) f.delete() }
        val lists = index.playlists.mapTo(HashSet()) { "${it.id}.json" }
        playlistsDir.listFiles()?.forEach { f -> if (f.name !in lists) f.delete() }
    }

    /** Everything, gone. */
    fun clear() = synchronized(lock) {
        root.deleteRecursively()
        index = DownloadIndex()
        dirty = false
        generation++
    }

    private fun read(): DownloadIndex = try {
        if (indexFile.isFile) DownloadIndex.fromJson(indexFile.readText()) else DownloadIndex()
    } catch (e: Exception) {
        // (Damaged: started again. The files it named are swept away, and downloaded again.)
        DownloadIndex()
    }

    companion object {
        /** Playlists' and tracks' ids, as the server has them. */
        val ID = Regex("^\\d{1,12}$")

        private const val PART_KEPT_MS = 2 * 24 * 60 * 60 * 1000L

        private fun hashOf(text: String) = GuiStore.sha256(text.toByteArray()).take(16)
    }
}
