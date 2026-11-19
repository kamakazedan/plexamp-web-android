package io.github.kamakazedan.plexampweb.downloads

import org.json.JSONObject

/**
 * Brings downloads up to date with the server: each playlist as it is there now (tracks
 * added, and tracks gone let go), each track's file as it is now (one changed on the
 * server, or wanted at another quality, downloaded again), and their albums' covers.
 * Playlists go in the order they were added. A track that won't fit (see `room`) is left
 * out, and its playlists say so; one the server won't give is tried again next time.
 *
 * Blocking: run off the main thread, one at a time.
 */
class DownloadSync(
    private val store: DownloadStore,
    private val source: PlexSource,
    private val settings: DownloadSettings,
    /** The bytes downloads may take still: the phone's free space, less what it keeps for itself. */
    private val room: () -> Long,
    private val playable: Set<String> = DownloadRules.PLAYABLE,
    private val clock: () -> Long = System::currentTimeMillis,
    private val stopped: () -> Boolean = { false },
    private val onProgress: (Progress) -> Unit = {},
) {
    /**
     * Of every track the playlists have (each once), `done` of `total` on the phone as they
     * should be, counted as Settings › Downloads counts each playlist's; and the playlist the
     * one coming is in. 0 of 0 while there's nothing to download.
     */
    data class Progress(val done: Int, val total: Int, val playlist: String?)

    enum class Outcome {
        /** Up to date (save for what didn't fit, or didn't come: see each playlist's problem). */
        DONE,

        /** The server couldn't be reached: tried again later. */
        RETRY,

        /** The server refused the sign-in: nothing more till the player gives a new one. */
        SIGN_IN,

        /** Called off part-way. */
        STOPPED,
    }

    fun run(): Outcome {
        // (Again where a playlist was added or removed meanwhile.)
        repeat(MAX_ROUNDS) {
            val generation = store.generation
            val outcome = try {
                pass()
            } catch (e: PlexSource.Unreachable) {
                Outcome.RETRY
            } catch (e: PlexSource.SignInRefused) {
                Outcome.SIGN_IN
            } catch (e: PlexSource.Stopped) {
                Outcome.STOPPED
            } finally {
                store.flush()
            }
            if (outcome != Outcome.DONE || store.generation == generation) return outcome
        }
        return Outcome.DONE
    }

    private fun pass(): Outcome {
        refresh()
        store.removeUnwanted()
        store.flush()
        val queue = toDownload()
        val before = if (queue.isEmpty()) 0 else (store.index().wanted().size - queue.size).coerceAtLeast(0)
        val total = if (queue.isEmpty()) 0 else before + queue.size
        val problems = HashMap<String, String?>()
        store.index().playlists.forEach { problems[it.id] = null }
        var done = 0
        var troubleInARow = 0
        var lastFlush = clock()
        onProgress(Progress(before, total, queue.firstOrNull()?.playlists?.first()))
        for (item in queue) {
            if (stopped()) throw PlexSource.Stopped()
            val kbps = DownloadRules.kbpsFor(item.spec, settings.quality, playable)
            if (DownloadRules.estimate(item.spec, kbps) > room()) {
                item.playlists.forEach { problems[it] = SavedPlaylist.SPACE }
            } else {
                onProgress(Progress(before + done, total, item.playlists.first()))
                try {
                    fetch(item.spec, kbps)
                    troubleInARow = 0
                } catch (e: Exception) {
                    if (e is PlexSource.Unreachable || e is PlexSource.SignInRefused || e is PlexSource.Stopped) throw e
                    item.playlists.forEach { if (problems[it] == null) problems[it] = SavedPlaylist.FAILED }
                    // (Trouble with the connection, not this track, time after time: the server's tried again later.)
                    val trackOnly = e is PlexSource.TrackFailed || e is TsDemuxer.NotAudio
                    if (!trackOnly && ++troubleInARow >= MAX_TROUBLE) throw PlexSource.Unreachable(e)
                }
            }
            done++
            if (clock() - lastFlush > FLUSH_MS) {
                store.flush()
                lastFlush = clock()
            }
        }
        store.setProblems(problems)
        store.update { it.copy(syncedAt = clock()) }
        onProgress(Progress(total, total, null))
        return Outcome.DONE
    }

    // Each playlist as the server has it now (unless it was asked about only just now).
    private fun refresh() {
        for (p in store.index().playlists) {
            if (stopped()) throw PlexSource.Stopped()
            if (clock() - p.checkedAt in 0 until FRESH_MS) continue
            val now = try {
                source.playlist(p.id)
            } catch (e: Exception) {
                if (e is PlexSource.Unreachable || e is PlexSource.SignInRefused || e is PlexSource.Stopped) throw e
                continue // (As it was last time, for now.)
            }
            // (Gone from the server: gone from the phone too.)
            if (now == null) store.removePlaylist(p.id) else store.refreshPlaylist(p.id, now.title, now.tracks.map { it.id }, now.json)
        }
    }

    private class Wanted(val spec: TrackSpec, val playlists: MutableList<String>)

    // Tracks not on the phone as they should be (or not at all), each once, in the playlists' order.
    private fun toDownload(): List<Wanted> {
        val index = store.index()
        val queue = LinkedHashMap<String, Wanted>()
        for (p in index.playlists) {
            for (spec in specsIn(store.playlistJson(p.id))) {
                if (!needed(spec, index, settings, playable)) continue
                val wanted = queue.getOrPut(spec.id) { Wanted(spec, ArrayList()) }
                if (p.id !in wanted.playlists) wanted.playlists += p.id
            }
        }
        return queue.values.toList()
    }

    private fun fetch(spec: TrackSpec, kbps: Int?) {
        val version = DownloadRules.versionOf(spec, settings.quality, playable)
        val part = store.partFile(spec.id, version)
        val extension = if (kbps == null) {
            source.download(spec, part) {}
            DownloadRules.extensionOf(spec.partKey)
        } else {
            source.convert(spec, kbps, part) {}
        }
        val name = store.fileName(spec.id, version, extension)
        val saved = SavedTrack(spec.id, name, part.length(), DownloadRules.mimeOf(name), version, spec.cover)
        if (store.commit(saved, part)) spec.cover?.let(::fetchCover)
    }

    // An album's cover, once (best effort: one that doesn't come is asked for next time).
    private fun fetchCover(path: String) {
        if (path in store.index().covers || room() < COVER_ROOM) return
        val part = store.coverPart(path)
        try {
            source.cover(path, part)
            store.putCover(path, part)
        } catch (e: Exception) {
            part.delete()
            if (e is PlexSource.Unreachable || e is PlexSource.SignInRefused || e is PlexSource.Stopped) throw e
        }
    }

    companion object {
        /** The tracks in a playlist's JSON, as it's kept (see DownloadStore.playlistJson). */
        fun specsIn(json: String?): List<TrackSpec> {
            val items = json?.let { runCatching { JSONObject(it).optJSONArray("items") }.getOrNull() } ?: return emptyList()
            return (0 until items.length()).mapNotNull { i -> items.optJSONObject(i)?.let(TrackSpec::of) }
        }

        /** Whether a track's to be downloaded: it isn't on the phone, or not as it should be now. */
        fun needed(spec: TrackSpec, index: DownloadIndex, settings: DownloadSettings, playable: Set<String> = DownloadRules.PLAYABLE): Boolean =
            index.tracks[spec.id]?.version != DownloadRules.versionOf(spec, settings.quality, playable)

        /** About how many bytes downloading `specs` takes: those needed, each once, but those in `except`. */
        fun bytesFor(
            specs: Iterable<TrackSpec>,
            index: DownloadIndex,
            settings: DownloadSettings,
            playable: Set<String> = DownloadRules.PLAYABLE,
            except: Set<String> = emptySet(),
        ): Long {
            val seen = HashSet(except)
            return specs.filter { seen.add(it.id) && needed(it, index, settings, playable) }
                .sumOf { DownloadRules.estimate(it, DownloadRules.kbpsFor(it, settings.quality, playable)) }
        }

        private const val MAX_ROUNDS = 5
        private const val MAX_TROUBLE = 3

        /** A playlist asked about this lately isn't asked about again (one just added, say). */
        private const val FRESH_MS = 5 * 60 * 1000L

        private const val FLUSH_MS = 15_000L
        private const val COVER_ROOM = 8L shl 20
    }
}
