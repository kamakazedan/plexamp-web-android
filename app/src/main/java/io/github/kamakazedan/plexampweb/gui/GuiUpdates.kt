package io.github.kamakazedan.plexampweb.gui

import io.github.kamakazedan.plexampweb.PlexampWebApp
import io.github.kamakazedan.plexampweb.net.Connectivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * When the player is saved on the phone: first while setting up (on any connection),
 * then whenever there's a new version, on Wi-Fi only: when the app opens, when the
 * phone joins Wi-Fi, and every few hours in the background (GuiUpdateWorker). A new
 * version is used the next time the player opens (or reloads).
 *
 * Its own methods are for the main thread.
 */
class GuiUpdates(private val app: PlexampWebApp) {
    val store = GuiStore(File(app.filesDir, "gui"))
    private val prefs get() = app.prefs
    private var job: Deferred<GuiUpdater.Outcome>? = null

    private val _saving = MutableStateFlow<Int?>(null)

    /** Files saved so far by the update under way; null when there's none. */
    val saving: StateFlow<Int?> = _saving.asStateFlow()

    private val _updated = MutableSharedFlow<GuiSnapshot>(extraBufferCapacity = 1)

    /** A new version, saved while the player was open. */
    val updated: SharedFlow<GuiSnapshot> = _updated.asSharedFlow()

    /** The copy the player on screen opened from: kept till it's reopened. (Set from the WebView's thread.) */
    @Volatile
    var inUse: String? = null

    /** The saved copy of the player set up now, if there is one. */
    fun current(): GuiSnapshot? = store.current()?.takeIf { it.base == prefs.playerUrl }

    /** Saves the player (or checks for a new version), unless that's already under way: then that. */
    fun start(): Deferred<GuiUpdater.Outcome> {
        job?.takeIf { it.isActive }?.let { return it }
        val base = prefs.playerUrl
            ?: return CompletableDeferred(GuiUpdater.Outcome.Failed(GuiUpdater.Reason.UNREACHABLE))
        val previous = current()
        val extra = prefs.misses
        _saving.value = 0
        val update = app.scope.async {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    val result = GuiUpdater(store, HttpGuiFetcher()).update(base, previous, extra) { _saving.value = it }
                    if (result is GuiUpdater.Outcome.Saved) store.clean(setOfNotNull(result.snapshot.id, inUse))
                    result
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    GuiUpdater.Outcome.Failed(GuiUpdater.Reason.STORAGE)
                }
            }
            finish(base, previous, outcome)
            outcome
        }
        update.invokeOnCompletion { _saving.value = null }
        job = update
        return update
    }

    private fun finish(base: String, previous: GuiSnapshot?, outcome: GuiUpdater.Outcome) {
        if (prefs.playerUrl != base) return // forgotten meanwhile
        prefs.lastCheckAt = System.currentTimeMillis()
        prefs.lastCheckOutcome = when (outcome) {
            is GuiUpdater.Outcome.Saved -> if (outcome.changed) "updated" else "same"
            is GuiUpdater.Outcome.Failed -> "failed"
        }
        if (outcome is GuiUpdater.Outcome.Saved) {
            prefs.misses = prefs.misses - outcome.snapshot.files.keys
            if (outcome.changed && previous != null) _updated.tryEmit(outcome.snapshot)
        }
    }

    /** The saved copy, saving it first where there's none (on any connection). */
    suspend fun ensure(): GuiUpdater.Outcome =
        current()?.let { GuiUpdater.Outcome.Saved(it, changed = false) } ?: start().await()

    /** A check for a new version, if one's due and the phone is on Wi-Fi. */
    fun checkSoon() {
        if (current() == null) return
        if (System.currentTimeMillis() - prefs.lastCheckAt < AUTO_CHECK_EVERY_MS) return
        if (!Connectivity.isUnmetered(app)) return
        start()
    }

    /** The background check (GuiUpdateWorker), on Wi-Fi. */
    suspend fun checkInBackground() {
        val update = withContext(Dispatchers.Main) {
            if (current() != null && Connectivity.isUnmetered(app)) start() else null
        }
        update?.await()
    }

    /** A file the page asked for that the saved copy hadn't got: saved too, next time. (Any thread.) */
    fun noteMiss(path: String) {
        if (GuiRefs.isFilePath(path)) prefs.addMiss(path)
    }

    /** Every saved copy, gone (and any update under way, stopped). */
    fun forget() {
        job?.cancel()
        job = null
        inUse = null
        store.clear()
    }

    private companion object {
        const val AUTO_CHECK_EVERY_MS = 30 * 60 * 1000L
    }
}
