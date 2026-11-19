package io.github.kamakazedan.plexampweb.downloads

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** A track to have on the phone, as the server describes it now: what it takes to download it. */
data class TrackSpec(
    /** Its id on the server (Plex's ratingKey). */
    val id: String,
    /** Its file on the server: /library/parts/123/1700000000/file.flac */
    val partKey: String,
    /** The file's size in bytes (0 where the server doesn't say). */
    val partSize: Long,
    /** "flac", "mp3"… ("" where the server doesn't say). */
    val codec: String,
    val durationMs: Long,
    /** In kbps (0 where the server doesn't say). */
    val bitrate: Int,
    /** Its album's cover, as the server names it (/library/metadata/1/thumb/2), or null. */
    val cover: String?,
) {
    companion object {
        private val ID = Regex("^\\d{1,12}$")

        // (Asked for as it is, so nothing in it that would make it another address.)
        private val PART_KEY = Regex("^/library/parts/[A-Za-z0-9._~%/-]{1,500}$")

        /**
         * A playlist entry (Plex's JSON for it) as a track to download, or null where it's
         * no track with a file of its own (a video, say, or a track whose file has gone).
         * Its first file, as the player plays it.
         */
        fun of(item: JSONObject): TrackSpec? {
            if (item.optString("type") != "track") return null
            val id = item.optString("ratingKey").takeIf { ID.matches(it) } ?: return null
            val media = firstOf(item, "Media") ?: return null
            val part = firstOf(media, "Part") ?: return null
            val key = part.optString("key").takeIf { PART_KEY.matches(it) && !it.contains("..") } ?: return null
            return TrackSpec(
                id = id,
                partKey = key,
                partSize = part.optLong("size", 0).coerceAtLeast(0),
                codec = media.optString("audioCodec").lowercase(Locale.ROOT),
                durationMs = item.optLong("duration", 0).takeIf { it > 0 } ?: part.optLong("duration", 0).coerceAtLeast(0),
                bitrate = media.optInt("bitrate", 0).coerceAtLeast(0),
                cover = listOf("parentThumb", "thumb", "grandparentThumb").firstNotNullOfOrNull { name ->
                    item.optString(name).takeIf { it.startsWith("/") }
                },
            )
        }
    }
}

/** The first of a list Plex gives (or the one thing on its own, as it can be). */
internal fun firstOf(item: JSONObject, name: String): JSONObject? = when (val value = item.opt(name)) {
    is JSONArray -> value.optJSONObject(0)
    is JSONObject -> value
    else -> null
}
