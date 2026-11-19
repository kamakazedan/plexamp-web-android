package io.github.kamakazedan.plexampweb.downloads

import org.json.JSONArray
import org.json.JSONObject

/** A track on the phone: its file, once however many playlists have it. */
data class SavedTrack(
    val id: String,
    /** Its file's name, in tracks/. */
    val file: String,
    val bytes: Long,
    /** "audio/flac", "audio/mpeg"… */
    val mime: String,
    /** What it was downloaded as (see DownloadRules.versionOf): downloaded again where that changes. */
    val version: String,
    /** Its album's cover, as the server names it. */
    val cover: String?,
)

/** A playlist kept on the phone. */
data class SavedPlaylist(
    val id: String,
    val title: String,
    /** When it was added: they're listed, and downloaded, in that order. */
    val added: Long,
    /** Its tracks' ids, in its order, as the server last said. */
    val tracks: List<String>,
    /** When the server was last asked about it (0: not yet). */
    val checkedAt: Long = 0,
    /** Why some of its tracks aren't on the phone, after the last sync: SPACE, FAILED, or null. */
    val problem: String? = null,
) {
    companion object {
        /** Not enough room for some of its tracks. */
        const val SPACE = "space"

        /** Some of its tracks didn't come (they're tried again). */
        const val FAILED = "failed"
    }
}

/** What's downloaded: the playlists kept, the tracks they have, and their albums' covers. */
data class DownloadIndex(
    val playlists: List<SavedPlaylist> = emptyList(),
    val tracks: Map<String, SavedTrack> = emptyMap(),
    /** Covers kept, by the server's name for each: their files' names, in covers/. */
    val covers: Map<String, String> = emptyMap(),
    /** When everything was last brought up to date (0: not yet). */
    val syncedAt: Long = 0,
) {
    /** Every track some playlist has. */
    fun wanted(): Set<String> = playlists.flatMapTo(HashSet()) { it.tracks }

    fun playlist(id: String): SavedPlaylist? = playlists.firstOrNull { it.id == id }

    /** The bytes a playlist's tracks take on the phone (each once). */
    fun bytesOf(playlist: SavedPlaylist): Long = playlist.tracks.toSet().sumOf { tracks[it]?.bytes ?: 0L }

    /** How many of a playlist's tracks are on the phone (a track it has twice counts twice). */
    fun savedOf(playlist: SavedPlaylist): Int = playlist.tracks.count { it in tracks }

    fun toJson(): String {
        val lists = JSONArray()
        for (p in playlists) {
            lists.put(
                JSONObject()
                    .put("id", p.id)
                    .put("title", p.title)
                    .put("added", p.added)
                    .put("tracks", JSONArray(p.tracks))
                    .put("checkedAt", p.checkedAt)
                    .putOpt("problem", p.problem),
            )
        }
        val saved = JSONObject()
        for ((id, t) in tracks) {
            saved.put(
                id,
                JSONObject()
                    .put("file", t.file)
                    .put("bytes", t.bytes)
                    .put("mime", t.mime)
                    .put("version", t.version)
                    .putOpt("cover", t.cover),
            )
        }
        return JSONObject()
            .put("version", 1)
            .put("playlists", lists)
            .put("tracks", saved)
            .put("covers", JSONObject(covers))
            .put("syncedAt", syncedAt)
            .toString()
    }

    companion object {
        fun fromJson(text: String): DownloadIndex {
            val json = JSONObject(text)
            val lists = json.optJSONArray("playlists") ?: JSONArray()
            val playlists = (0 until lists.length()).mapNotNull { i ->
                val p = lists.optJSONObject(i) ?: return@mapNotNull null
                val ids = p.optJSONArray("tracks") ?: JSONArray()
                SavedPlaylist(
                    id = p.getString("id"),
                    title = p.optString("title"),
                    added = p.optLong("added"),
                    tracks = (0 until ids.length()).map { ids.getString(it) },
                    checkedAt = p.optLong("checkedAt"),
                    problem = p.stringOrNull("problem"),
                )
            }
            val tracks = HashMap<String, SavedTrack>()
            json.optJSONObject("tracks")?.let { saved ->
                for (id in saved.keys()) {
                    val t = saved.getJSONObject(id)
                    tracks[id] = SavedTrack(id, t.getString("file"), t.getLong("bytes"), t.getString("mime"), t.getString("version"), t.stringOrNull("cover"))
                }
            }
            val covers = HashMap<String, String>()
            json.optJSONObject("covers")?.let { c -> for (path in c.keys()) covers[path] = c.getString(path) }
            return DownloadIndex(playlists, tracks, covers, json.optLong("syncedAt"))
        }

        private fun JSONObject.stringOrNull(name: String): String? = if (!has(name) || isNull(name)) null else getString(name)
    }
}
