package io.github.kamakazedan.plexampweb.downloads

import android.content.Context
import io.github.kamakazedan.plexampweb.BuildConfig
import io.github.kamakazedan.plexampweb.Prefs
import io.github.kamakazedan.plexampweb.net.Connectivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Playlists kept on the phone, to play without a connection or mobile data: what the
 * player's Settings › Downloads shows and changes (through DownloadBridge), kept up to
 * date in the background (DownloadWorker), and played from the phone (DownloadServer).
 *
 * They're the signed-in person's, from their server: signing out, someone else signing
 * in, or another server, and they're gone (see account and forget).
 */
class Downloads(private val context: Context, private val prefs: Prefs) {
    /** (Read from disk when first wanted, off the main thread.) */
    val store: DownloadStore by lazy { DownloadStore(File(context.filesDir, "downloads")).also { it.sweep() } }

    private val vault = TokenVault(context)
    private val syncing = Mutex()

    /** The account the player gave this time it's open: kept in the vault while there are downloads. */
    @Volatile
    private var given: DownloadAccount? = null

    /** The phone's connection, as last seen (see networkChanged). */
    @Volatile
    private var network: Pair<Boolean, Boolean>? = null

    /** Where the sync's got to, while one runs. */
    @Volatile
    private var progress: DownloadSync.Progress? = null

    /** When the player asked for a check (see check), till a sync starts. */
    @Volatile
    private var checkAsked = 0L

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Something the player shows changed: the player's told (see DownloadBridge). */
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    var settings: DownloadSettings
        get() = DownloadSettings.of(prefs.downloadQuality, prefs.downloadNetwork, prefs.downloadPrefer)
        private set(value) {
            prefs.downloadQuality = value.quality
            prefs.downloadNetwork = value.network
            prefs.downloadPrefer = value.prefer
        }

    /** Why a playlist wasn't added: `code` says (see DownloadBridge), with the bytes it needs, and has, for "space". */
    class Refused(val code: String, val needed: Long = 0, val free: Long = 0) : Exception(code)

    // ---------- what the player asks ----------

    /** Everything Settings › Downloads shows, and the tracks the player can play from the phone. Off the main thread. */
    fun state(): JSONObject {
        val index = store.index()
        val space = StorageInfo.space(context)
        val playlists = JSONArray()
        for (p in index.playlists) {
            playlists.put(
                JSONObject()
                    .put("id", p.id)
                    .put("title", p.title)
                    .put("tracks", p.tracks.size)
                    .put("saved", index.savedOf(p))
                    .put("bytes", store.bytesOf(p))
                    .putOpt("problem", p.problem)
                    // (Its first album cover on the phone: its picture, offline too.)
                    .putOpt("cover", p.tracks.firstNotNullOfOrNull { id -> index.tracks[id]?.cover?.takeIf { it in index.covers } }),
            )
        }
        return JSONObject()
            .put("settings", settings.toJson())
            .put("storage", JSONObject().put("free", space.free).put("total", space.total).put("used", store.bytesUsed()))
            .put("status", status(index))
            .put("playlists", playlists)
            .put("tracks", JSONArray(index.tracks.keys))
            .put("syncedAt", index.syncedAt)
            .put("metered", !Connectivity.isUnmetered(context))
    }

    private fun status(index: DownloadIndex): JSONObject {
        progress?.let { return JSONObject().put("state", "downloading").put("done", it.done).put("total", it.total).putOpt("playlist", it.playlist) }
        val trouble = prefs.downloadsTrouble
        val asked = System.currentTimeMillis() - checkAsked in 0 until CHECK_MS
        val state = when {
            index.playlists.isEmpty() -> "idle"
            // (Asked to check: it waits for a network it may use, or it's about to start.)
            asked && !Connectivity.isOnline(context) -> "offline"
            asked && settings.network == DownloadSettings.WIFI && !Connectivity.isUnmetered(context) -> "wifi"
            asked -> return JSONObject().put("state", "downloading")
            trouble != null -> trouble
            // (Tracks not tried yet: those that were say why on their playlist.)
            index.playlists.none { it.problem == null && index.savedOf(it) < it.tracks.size } -> "idle"
            !Connectivity.isOnline(context) -> "offline"
            settings.network == DownloadSettings.WIFI && !Connectivity.isUnmetered(context) -> "wifi"
            else -> "waiting"
        }
        return JSONObject().put("state", state)
    }

    /**
     * Keeps a playlist on the phone: refused (see Refused) where it's not on the server,
     * or it won't fit, counting what's still to come for those kept already.
     */
    suspend fun add(id: String) = withContext(Dispatchers.IO) {
        require(DownloadStore.ID.matches(id))
        if (store.index().playlist(id) != null) return@withContext
        val account = given ?: throw Refused("account")
        val fetched = try {
            client(account).playlist(id)
        } catch (e: PlexSource.SignInRefused) {
            throw Refused("signin")
        } catch (e: PlexSource.Unreachable) {
            throw Refused("unreachable")
        } catch (e: IOException) {
            throw Refused("server")
        }
        if (fetched == null) throw Refused("missing")
        if (fetched.tracks.isEmpty()) throw Refused("empty")
        val index = store.index()
        val settings = settings
        val needed = DownloadSync.bytesFor(fetched.tracks, index, settings)
        val others = index.playlists.flatMap { DownloadSync.specsIn(store.playlistJson(it.id)) }
        val coming = DownloadSync.bytesFor(others, index, settings, except = fetched.tracks.mapTo(HashSet()) { it.id })
        val free = (StorageInfo.room(context) - coming).coerceAtLeast(0)
        if (needed > free) throw Refused("space", needed, free)
        keep(account)
        val now = System.currentTimeMillis()
        store.addPlaylist(SavedPlaylist(id, fetched.title, added = now, tracks = fetched.tracks.map { it.id }, checkedAt = now), fetched.json)
        DownloadWorker.schedule(context, settings, restart = false)
        changed()
    }

    /** Not kept any more: it, and its tracks no other playlist has. */
    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        store.removePlaylist(id)
        if (store.index().playlists.isEmpty()) stopKeeping()
        changed()
    }

    /** The quality, network or preference changed (each one given that's a choice there is). */
    suspend fun set(quality: String?, network: String?, prefer: String?) = withContext(Dispatchers.IO) {
        val before = settings
        val after = before.with(quality, network, prefer)
        settings = after
        // (Downloaded again at the new quality, or on the new network: what's under way starts again.)
        val redo = after.quality != before.quality || after.network != before.network
        if (redo && store.index().playlists.isNotEmpty()) DownloadWorker.schedule(context, after, restart = true)
        changed()
    }

    /**
     * The player's sign-in to the server, as the player gives it each time it opens: kept
     * for downloads, if there are any. Someone else's (or another server's), and the
     * downloads there are, which aren't theirs, are gone.
     */
    suspend fun account(account: DownloadAccount) = withContext(Dispatchers.IO) {
        given = account
        val index = store.index()
        if (index.playlists.isEmpty()) return@withContext
        val owner = prefs.downloadsOwner
        if (owner != null && owner != account.owner) {
            forgetNow()
            return@withContext
        }
        val kept = vault.read()
        keep(account)
        val stale = System.currentTimeMillis() - index.syncedAt > STALE_MS
        if (kept?.token != account.token || prefs.downloadsTrouble != null || stale) {
            prefs.downloadsTrouble = null
            DownloadWorker.schedule(context, settings, restart = false)
        }
        changed()
    }

    /**
     * Brought up to date now, each playlist asked about again (Settings › Downloads'
     * "Up to date", tapped): on a network downloads may use, as ever.
     */
    suspend fun check() = withContext(Dispatchers.IO) {
        if (store.index().playlists.isEmpty()) return@withContext
        checkAsked = System.currentTimeMillis()
        store.askAgain()
        // (A sync under way goes round again. With none, one waiting to try the server
        // again later, after it couldn't be reached, tries now instead.)
        DownloadWorker.schedule(context, settings, restart = progress == null)
        changed()
    }

    /** Signed out: every download, and the sign-in kept for them, gone. */
    suspend fun forget() = withContext(Dispatchers.IO) { forgetNow() }

    /** A track that wouldn't play from the phone: let go, to be downloaded again. */
    suspend fun dropTrack(id: String) = withContext(Dispatchers.IO) {
        store.dropTrack(id)
        changed()
    }

    /**
     * The phone's connection changed: the player's told where it matters to it (offline,
     * on Wi-Fi or on mobile data: which play from the phone, and what's waiting for what).
     */
    fun networkChanged(online: Boolean, unmetered: Boolean) {
        val now = online to unmetered
        if (now == network) return
        network = now
        changed()
    }

    // ---------- the sync ----------

    /** Brings downloads up to date (one at a time): DownloadWorker's work. */
    suspend fun sync(stopped: () -> Boolean, onProgress: (DownloadSync.Progress) -> Unit): DownloadSync.Outcome = syncing.withLock {
        withContext(Dispatchers.IO) {
            if (store.index().playlists.isEmpty()) return@withContext DownloadSync.Outcome.DONE
            val account = vault.read() ?: given?.takeIf { it.owner == prefs.downloadsOwner }
            if (account == null) {
                prefs.downloadsTrouble = "signin"
                changed()
                return@withContext DownloadSync.Outcome.SIGN_IN
            }
            val sync = DownloadSync(
                store = store,
                source = client(account, stopped),
                settings = settings,
                room = { StorageInfo.room(context) },
                stopped = stopped,
                onProgress = { p ->
                    progress = p
                    onProgress(p)
                    changed()
                },
            )
            val outcome = try {
                progress = DownloadSync.Progress(0, 0, null)
                checkAsked = 0
                changed()
                sync.run()
            } finally {
                progress = null
                // (Asked meanwhile: the sync went round again for it.)
                checkAsked = 0
            }
            when (outcome) {
                DownloadSync.Outcome.DONE -> prefs.downloadsTrouble = null
                DownloadSync.Outcome.SIGN_IN -> prefs.downloadsTrouble = "signin"
                DownloadSync.Outcome.RETRY -> prefs.downloadsTrouble = "unreachable"
                DownloadSync.Outcome.STOPPED -> Unit
            }
            changed()
            outcome
        }
    }

    // ---------- bits ----------

    private fun client(account: DownloadAccount, stopped: () -> Boolean = { false }) =
        PlexClient(account, prefs.clientId, BuildConfig.VERSION_NAME, stopped)

    private fun keep(account: DownloadAccount) {
        if (prefs.downloadsOwner != account.owner) prefs.downloadsOwner = account.owner
        if (vault.read() != account) vault.write(account)
    }

    private fun stopKeeping() {
        DownloadWorker.cancel(context)
        vault.clear()
        prefs.downloadsOwner = null
        prefs.downloadsTrouble = null
    }

    /** (Also when the player's forgotten: see PlexampWebApp.forgetPlayer.) Off the main thread. */
    fun forgetNow() {
        given = null
        stopKeeping()
        store.clear()
        changed()
    }

    private fun changed() {
        _changes.tryEmit(Unit)
    }

    private companion object {
        /** Opening the player checks for changes, where it's been this long. */
        const val STALE_MS = 60 * 60 * 1000L

        /** A check asked for shows as starting for this long at most (till the sync does). */
        const val CHECK_MS = 2 * 60 * 1000L
    }
}
