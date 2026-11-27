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
 * Each server's are kept apart (see DownloadShelves). Those from the server the player's
 * on are kept up to date; another server's stay as they were, and still play from the
 * phone, till the player's on that server again. They're the signed-in person's: signing
 * out, or someone else signing in, and they're gone (see account and forget).
 */
class Downloads(
    private val context: Context,
    private val prefs: Prefs,
    /** Whether music's playing now: downloads go gently then (see Pacer). */
    private val playing: () -> Boolean = { false },
) {
    /** Each server's downloads (read from disk when first wanted, off the main thread). */
    private val shelves: DownloadShelves by lazy { DownloadShelves(File(context.filesDir, "downloads")).also(::settle) }

    private val vault = TokenVault(context)
    private val syncing = Mutex()

    /** What the player asks to change, one at a time. */
    private val changing = Mutex()

    /** The account the player gave this time it's open: kept in the vault while there are downloads from its server. */
    @Volatile
    private var given: DownloadAccount? = null

    /** Its server's name, as the player gave it with the account. */
    @Volatile
    private var givenName: String? = null

    /** The phone's connection, as last seen (see networkChanged). */
    @Volatile
    private var network: Pair<Boolean, Boolean>? = null

    /** Where the sync's got to, and for which server, while one runs. */
    @Volatile
    private var running: Running? = null

    private class Running(val server: String, val progress: DownloadSync.Progress)

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

    /** The server the player's on, as it last said: the one whose downloads are kept up to date. */
    private val current: String? get() = prefs.downloadsServer

    /** Why a playlist wasn't added: `code` says (see DownloadBridge), with the bytes it needs, and has, for "space". */
    class Refused(val code: String, val needed: Long = 0, val free: Long = 0) : Exception(code)

    // ---------- what the player asks ----------

    /**
     * Everything Settings › Downloads shows, and the tracks the player can play from the
     * phone: those from the server the player's on (`server`), and each other server's
     * (`others`), with its name. Off the main thread.
     */
    fun state(): JSONObject {
        val shelves = shelves
        val server = current
        val store = shelves.storeIfAny(server)
        val index = store?.index() ?: DownloadIndex()
        val space = StorageInfo.space(context)
        val others = JSONArray()
        for (id in shelves.servers()) {
            if (id == server) continue
            val other = shelves.store(id)
            val kept = other.index()
            if (kept.playlists.isEmpty()) continue
            others.put(
                JSONObject()
                    .put("server", id)
                    .putOpt("name", kept.name)
                    .put("playlists", playlistsOf(other, kept))
                    .put("tracks", JSONArray(kept.tracks.keys))
                    .put("syncedAt", kept.syncedAt),
            )
        }
        return JSONObject()
            .put("settings", settings.toJson())
            .put("storage", JSONObject().put("free", space.free).put("total", space.total).put("used", shelves.bytesUsed()))
            .put("status", status(index))
            .putOpt("server", server)
            .putOpt("name", index.name)
            .put("playlists", if (store != null) playlistsOf(store, index) else JSONArray())
            .put("tracks", JSONArray(index.tracks.keys))
            .put("syncedAt", index.syncedAt)
            .put("metered", !Connectivity.isUnmetered(context))
            .put("others", others)
    }

    private fun playlistsOf(store: DownloadStore, index: DownloadIndex): JSONArray {
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
        return playlists
    }

    private fun status(index: DownloadIndex): JSONObject {
        val wifiOnly = settings.network == DownloadSettings.WIFI
        // (Not on a network downloads may use: what's under way stops, and waits for one.)
        val waiting = when {
            !Connectivity.isOnline(context) -> "offline"
            wifiOnly && !Connectivity.isUnmetered(context) -> "wifi"
            else -> null
        }
        // (The playlist it's on: being checked, or downloading, so far of its tracks.)
        running?.takeIf { it.server == current }?.progress?.let { p ->
            val now = JSONObject().put("state", waiting ?: "downloading").putOpt("playlist", p.playlist)
            if (p.checking) now.put("checking", true)
            if (p.total > 0) now.put("done", p.done).put("total", p.total)
            return now
        }
        val trouble = prefs.downloadsTrouble
        val asked = System.currentTimeMillis() - checkAsked in 0 until CHECK_MS
        val state = when {
            index.playlists.isEmpty() -> "idle"
            // (Asked to check: it waits for a network it may use, or it's about to start.)
            asked -> waiting ?: return JSONObject().put("state", "downloading")
            trouble != null -> trouble
            // (Tracks not tried yet: those that were say why on their playlist.)
            index.playlists.none { it.problem == null && index.savedOf(it) < it.tracks.size } -> "idle"
            else -> waiting ?: "waiting"
        }
        return JSONObject().put("state", state)
    }

    /**
     * Keeps a playlist from the server the player's on (`server`, where it says, to be
     * sure): refused (see Refused) where it's not on the server, or it won't fit,
     * counting what's still to come for those kept already.
     */
    suspend fun add(id: String, server: String?) = withContext(Dispatchers.IO) {
        require(DownloadStore.ID.matches(id))
        val account = given ?: throw Refused("account")
        // (The player's on another server than it last said: it says so first.)
        if (server != null && server != account.serverId) throw Refused("account")
        if (shelves.storeIfAny(account.serverId)?.index()?.playlist(id) != null) return@withContext
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
        changing.withLock {
            // (Signed out, or on another server, meanwhile: not kept.)
            if (given != account) throw Refused("account")
            val store = shelves.store(account.serverId)
            val index = store.index()
            val settings = settings
            val needed = DownloadSync.bytesFor(fetched.tracks, index, settings)
            val others = index.playlists.flatMap { DownloadSync.specsIn(store.playlistJson(it.id)) }
            val coming = DownloadSync.bytesFor(others, index, settings, except = fetched.tracks.mapTo(HashSet()) { it.id })
            val free = (StorageInfo.room(context) - coming).coerceAtLeast(0)
            if (needed > free) throw Refused("space", needed, free)
            keep(account)
            store.claim(account.owner, givenName)
            val now = System.currentTimeMillis()
            store.addPlaylist(SavedPlaylist(id, fetched.title, added = now, tracks = fetched.tracks.map { it.id }, checkedAt = now), fetched.json)
            DownloadWorker.schedule(context, settings, restart = false)
        }
        changed()
    }

    /** Not kept any more (from `server`: the one the player's on, where it doesn't say): it, and its tracks no other playlist has. */
    suspend fun remove(id: String, server: String?) = withContext(Dispatchers.IO) {
        require(DownloadStore.ID.matches(id))
        changing.withLock {
            val from = server ?: current ?: return@withLock
            val store = shelves.storeIfAny(from) ?: return@withLock
            store.removePlaylist(id)
            if (store.index().playlists.isEmpty()) {
                if (from == current) stopKeeping()
                shelves.remove(from)
            }
        }
        changed()
    }

    /** The quality, network or preference changed (each one given that's a choice there is). */
    suspend fun set(quality: String?, network: String?, prefer: String?) = withContext(Dispatchers.IO) {
        changing.withLock {
            val before = settings
            val after = before.with(quality, network, prefer)
            settings = after
            // (Downloaded again at the new quality, or on the new network: what's under way starts again.)
            val redo = after.quality != before.quality || after.network != before.network
            if (redo && hasAny(current)) DownloadWorker.schedule(context, after, restart = true)
        }
        changed()
    }

    /**
     * The player's sign-in to its server, as the player gives it each time it opens (and
     * as it switches servers): `user`, who's signed in, and `serverName`, the server's.
     * That server's downloads are the ones kept up to date from now on; another server's
     * stay as they are. Anyone else's, which aren't theirs, are gone.
     */
    suspend fun account(account: DownloadAccount, user: String, serverName: String?) = withContext(Dispatchers.IO) {
        changing.withLock {
            given = account
            givenName = serverName
            val shelves = shelves
            // (From before 0.3.0, and the vault couldn't say whose: this server's, if they're who they were.)
            if (shelves.hasOld()) {
                if (prefs.downloadsOwner == account.owner) shelves.adoptOld(account.serverId, account.owner) else shelves.dropOld()
                prefs.downloadsOwner = null
            }
            // (Someone else signed in, without signing out first.)
            for (id in shelves.servers()) {
                if (shelves.store(id).index().owner != DownloadAccount.ownerOf(id, user)) shelves.remove(id)
            }
            val switched = current != account.serverId
            if (switched) {
                prefs.downloadsServer = account.serverId
                prefs.downloadsTrouble = null
                checkAsked = 0
            }
            val store = shelves.storeIfAny(account.serverId)
            val index = store?.index()
            if (store == null || index == null || index.playlists.isEmpty()) {
                // (Nothing from this server: nothing kept up to date, and no sign-in kept.)
                stopKeeping()
                return@withLock
            }
            store.claim(account.owner, serverName)
            val kept = vault.read()
            keep(account)
            val stale = System.currentTimeMillis() - index.syncedAt > STALE_MS
            if (switched || kept?.token != account.token || prefs.downloadsTrouble != null || stale) {
                prefs.downloadsTrouble = null
                // (Another server's under way: it stops, and this one's start.)
                DownloadWorker.schedule(context, settings, restart = switched)
            }
        }
        changed()
    }

    /**
     * Brought up to date now, each playlist asked about again (Settings › Downloads'
     * "Up to date", tapped): on a network downloads may use, as ever.
     */
    suspend fun check() = withContext(Dispatchers.IO) {
        changing.withLock {
            val store = shelves.storeIfAny(current)?.takeIf { it.index().playlists.isNotEmpty() } ?: return@withLock
            checkAsked = System.currentTimeMillis()
            store.askAgain()
            // (A sync under way goes round again. With none, one waiting to try the server
            // again later, after it couldn't be reached, tries now instead.)
            DownloadWorker.schedule(context, settings, restart = running == null)
        }
        changed()
    }

    /** Signed out (or the player forgotten: see PlexampWebApp.forgetPlayer): every download, and the sign-in kept for them, gone. */
    suspend fun forget() = withContext(Dispatchers.IO) {
        changing.withLock {
            given = null
            givenName = null
            stopKeeping()
            shelves.clear()
            prefs.downloadsServer = null
            prefs.downloadsOwner = null
        }
        changed()
    }

    /**
     * A track that wouldn't play from the phone: let go, to be downloaded again. Not another
     * server's (`server`), which couldn't be till the player's on it again.
     */
    suspend fun dropTrack(id: String, server: String?) = withContext(Dispatchers.IO) {
        changing.withLock {
            if (server != null && server != current) return@withLock
            shelves.storeIfAny(current)?.dropTrack(id)
        }
        changed()
    }

    /**
     * Where a server's downloads are on the phone, for DownloadServer (`server` null: the
     * one the player's on), or null where there are none.
     */
    fun storeFor(server: String?): DownloadStore? = shelves.storeIfAny(server ?: current)

    /** The title of a playlist the sync's on (for its notification). */
    fun playlistTitle(id: String): String? = shelves.storeIfAny(running?.server ?: current)?.index()?.playlist(id)?.title

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

    /** Brings the downloads from the server the player's on up to date (one at a time): DownloadWorker's work. */
    suspend fun sync(stopped: () -> Boolean, onProgress: (DownloadSync.Progress) -> Unit): DownloadSync.Outcome = syncing.withLock {
        withContext(Dispatchers.IO) {
            val server = current ?: return@withContext DownloadSync.Outcome.DONE
            val store = shelves.storeIfAny(server)?.takeIf { it.index().playlists.isNotEmpty() } ?: return@withContext DownloadSync.Outcome.DONE
            val owner = store.index().owner
            val account = listOfNotNull(vault.read(), given).firstOrNull { it.serverId == server && it.owner == owner }
            if (account == null) {
                prefs.downloadsTrouble = "signin"
                changed()
                return@withContext DownloadSync.Outcome.SIGN_IN
            }
            val settings = settings
            val wifiOnly = settings.network == DownloadSettings.WIFI
            // (On another server now, or off Wi-Fi where it's Wi-Fi only: it stops, and goes on when it may.)
            val halted = { stopped() || current != server || (wifiOnly && network?.second == false) }
            val sync = DownloadSync(
                store = store,
                source = client(account, halted, Pacer(playing)),
                settings = settings,
                room = { StorageInfo.room(context) },
                stopped = halted,
                onProgress = { p ->
                    running = Running(server, p)
                    onProgress(p)
                    changed()
                },
            )
            val outcome = try {
                running = Running(server, DownloadSync.Progress(null))
                checkAsked = 0
                changed()
                sync.run()
            } finally {
                running = null
                // (Asked meanwhile: the sync went round again for it.)
                checkAsked = 0
            }
            if (current == server) {
                when (outcome) {
                    DownloadSync.Outcome.DONE -> prefs.downloadsTrouble = null
                    DownloadSync.Outcome.SIGN_IN -> prefs.downloadsTrouble = "signin"
                    DownloadSync.Outcome.RETRY -> prefs.downloadsTrouble = "unreachable"
                    DownloadSync.Outcome.STOPPED -> Unit
                }
            }
            changed()
            outcome
        }
    }

    // ---------- bits ----------

    private fun client(account: DownloadAccount, stopped: () -> Boolean = { false }, pacer: Pacer? = null) =
        PlexClient(account, prefs.clientId, BuildConfig.VERSION_NAME, stopped, pacer)

    private fun hasAny(server: String?) = shelves.storeIfAny(server)?.index()?.playlists?.isNotEmpty() == true

    private fun keep(account: DownloadAccount) {
        if (vault.read() != account) vault.write(account)
    }

    private fun stopKeeping() {
        DownloadWorker.cancel(context)
        vault.clear()
        prefs.downloadsTrouble = null
    }

    // At first: downloads from before 0.3.0 moved to their server's place (the vault says
    // which: where it can't, the player's next sign-in does, in account). Places left
    // empty, gone.
    private fun settle(shelves: DownloadShelves) {
        if (shelves.hasOld()) {
            vault.read()?.let { kept ->
                shelves.adoptOld(kept.serverId, kept.owner)
                prefs.downloadsServer = kept.serverId
                prefs.downloadsOwner = null
            }
        }
        shelves.sweep()
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
