package io.github.kamakazedan.plexampweb.downloads

import java.util.Locale

/** How each track is downloaded, how much room that takes, and how much room the phone keeps for itself. */
object DownloadRules {
    /** Formats a phone's WebView plays as they are, where the player hasn't said which it plays. */
    val PLAYABLE = setOf("flac", "mp3", "aac", "opus", "vorbis", "wav", "pcm")

    private val LOSSLESS = setOf("flac", "alac", "wav", "aiff", "aif", "pcm", "dsd", "dsf", "dff", "ape", "wv", "wavpack", "tta", "mlp")

    /** Converted to, for Original, a file the player can't play as it is (as it is when it streams). */
    const val CONVERTED_ORIGINAL = 320

    /** Kept free for the phone itself: 1 GB, or a tenth of a phone with less than 10 GB in all. */
    fun reserve(totalBytes: Long): Long = minOf(1L shl 30, totalBytes / 10)

    /**
     * How a track is downloaded at `quality`: its file as it is (null), or converted to
     * that many kbps. As the player streams it: a file it can't play as it is, converted;
     * and at a lower quality, one already at it or lower (a 128 kbps MP3, say) as it is,
     * as converting it would only make it bigger. (`playable`: the formats the player
     * plays, as it said.)
     */
    fun kbpsFor(spec: TrackSpec, quality: String, playable: Set<String> = PLAYABLE): Int? {
        val wanted = quality.toIntOrNull()
        val codec = spec.codec.lowercase(Locale.ROOT)
        val canPlay = codec.isEmpty() || codec in playable
        val already = wanted != null && codec !in LOSSLESS && spec.bitrate in 1..wanted
        return if ((wanted == null || already) && canPlay) null else wanted ?: CONVERTED_ORIGINAL
    }

    /** What a track is downloaded as, at `quality`: a new one's downloaded where this changes (its file, or the quality). */
    fun versionOf(spec: TrackSpec, quality: String, playable: Set<String> = PLAYABLE): String =
        "${spec.partKey}|${spec.partSize}|${kbpsFor(spec, quality, playable) ?: DownloadSettings.ORIGINAL}"

    /** About how many bytes a track takes, downloaded as `kbps` says (see kbpsFor). */
    fun estimate(spec: TrackSpec, kbps: Int?): Long = when {
        kbps != null -> bytesAt(spec.durationMs, kbps)
        spec.partSize > 0 -> spec.partSize
        // (A file whose size the server doesn't say: as CD quality, unless it says its bitrate.)
        else -> bytesAt(spec.durationMs, spec.bitrate.takeIf { it > 0 } ?: 1411)
    }

    private fun bytesAt(durationMs: Long, kbps: Int): Long = durationMs * kbps / 8

    /** A downloaded file's type, by its name's extension (what the WebView is told it is). */
    fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "flac" -> "audio/flac"
        "mp3" -> "audio/mpeg"
        "aac" -> "audio/aac"
        "m4a", "mp4", "alac" -> "audio/mp4"
        "ogg", "oga", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "aif", "aiff" -> "audio/aiff"
        "wma" -> "audio/x-ms-wma"
        else -> "application/octet-stream"
    }

    /** The extension a file as it is gets (its own, from its name on the server), or "bin". */
    fun extensionOf(partKey: String): String =
        partKey.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT).takeIf { EXT.matches(it) } ?: "bin"

    private val EXT = Regex("^[a-z0-9]{1,5}$")
}
