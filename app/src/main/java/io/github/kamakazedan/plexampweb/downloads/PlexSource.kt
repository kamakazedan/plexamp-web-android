package io.github.kamakazedan.plexampweb.downloads

import java.io.File
import java.io.IOException

/** Where downloads come from: the Plex server (PlexClient), or a test's. Blocking: called off the main thread. */
interface PlexSource {
    /** A playlist and its tracks, as the server has them now, or null where it hasn't got it (any more). */
    fun playlist(id: String): FetchedPlaylist?

    /** A track's file as it is, to `to`: going on from where a part-way one got to. */
    fun download(spec: TrackSpec, to: File, progress: (Long) -> Unit)

    /** A track converted to `kbps`, to `to`: the extension its file takes ("mp3", "aac"). */
    fun convert(spec: TrackSpec, kbps: Int, to: File, progress: (Long) -> Unit): String

    /** An album's cover (`path`, as the server names it), to `to`. */
    fun cover(path: String, to: File)

    /** None of the server's addresses answered as it (no connection, say): tried again later. */
    class Unreachable(cause: Throwable? = null) : IOException("Your Plex server couldn't be reached.", cause)

    /** The server said no to the sign-in (401): the player's needed to sign in again. */
    class SignInRefused : IOException("Your Plex server refused the sign-in.")

    /** Stopped part-way (the work was called off): tried again later. */
    class Stopped : IOException("Stopped.")

    /** The server wouldn't give this one track (or gave something that isn't one): the others go on. */
    class TrackFailed(message: String) : IOException(message)
}

/** A playlist as the server has it now. */
data class FetchedPlaylist(
    val id: String,
    val title: String,
    /** Its tracks, in its order: the ones with a file to download (not videos, say). */
    val tracks: List<TrackSpec>,
    /** The server's JSON for it and its tracks: { "playlist": {…}, "items": […] }, for the player offline. */
    val json: String,
)
