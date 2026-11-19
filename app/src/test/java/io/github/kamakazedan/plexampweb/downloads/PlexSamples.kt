package io.github.kamakazedan.plexampweb.downloads

import org.json.JSONArray
import org.json.JSONObject

/** Plex's JSON for playlists and their tracks: for the tests. */
object PlexSamples {
    fun track(
        id: String,
        size: Long = 1000,
        codec: String = "flac",
        bitrate: Int = 900,
        durationMs: Long = 200_000,
        part: String = "/library/parts/$id/1700000000/file.$codec",
        thumb: String? = "/library/metadata/9$id/thumb/1",
    ): JSONObject = JSONObject()
        .put("ratingKey", id)
        .put("type", "track")
        .put("title", "Track $id")
        .put("grandparentTitle", "An artist")
        .put("duration", durationMs)
        .putOpt("parentThumb", thumb)
        .put(
            "Media",
            JSONArray().put(
                JSONObject()
                    .put("audioCodec", codec)
                    .put("bitrate", bitrate)
                    .put(
                        "Part",
                        JSONArray().put(
                            JSONObject()
                                .put("key", part)
                                .put("size", size)
                                .put("file", "/music/An artist/$id.$codec")
                                .put("Stream", JSONArray().put(JSONObject().put("streamType", 2))),
                        ),
                    ),
            ),
        )

    fun playlistInfo(id: String, title: String) = JSONObject()
        .put("ratingKey", id)
        .put("title", title)
        .put("playlistType", "audio")
        .put("smart", false)

    fun fetched(id: String, title: String, tracks: List<JSONObject>): FetchedPlaylist {
        val items = JSONArray()
        tracks.forEach(items::put)
        val json = JSONObject().put("playlist", playlistInfo(id, title)).put("items", items).toString()
        return FetchedPlaylist(id, title, tracks.mapNotNull(TrackSpec::of), json)
    }
}
