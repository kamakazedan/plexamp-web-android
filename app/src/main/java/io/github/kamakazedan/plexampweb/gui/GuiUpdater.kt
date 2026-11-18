package io.github.kamakazedan.plexampweb.gui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.net.URI
import kotlin.random.Random

/** Fetches one of the player's files. */
fun interface GuiFetcher {
    /** GET `url`; with `etag` or `lastModified`, only if it's changed since (304 if it hasn't). */
    fun get(url: String, etag: String?, lastModified: String?): Fetched
}

class Fetched(
    val status: Int,
    val contentType: String?,
    val etag: String?,
    val lastModified: String?,
    /** The headers kept with a saved file (see HttpGuiFetcher.KEPT_HEADERS). */
    val headers: Map<String, String>,
    val body: ByteArray?,
)

/**
 * Saves the player's web app: every file it's made of (see GuiRefs), fetched from the
 * player, then put in place in one step, and only once every file it needs has come.
 * Files saved before are asked for only if they've changed since.
 */
class GuiUpdater(
    private val store: GuiStore,
    private val fetcher: GuiFetcher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed interface Outcome {
        /** Saved: a new version (`changed`), or the same as before. */
        data class Saved(val snapshot: GuiSnapshot, val changed: Boolean) : Outcome

        data class Failed(val reason: Reason, val status: Int = 0) : Outcome
    }

    enum class Reason {
        /** The player didn't answer. */
        UNREACHABLE,

        /** What answered isn't plexamp-web (a sign-in or error page in front of it, say). */
        NOT_PLAYER,

        /** The player (or something in front of it) said no: 401 or 403. */
        REFUSED,

        /** A file the app needs wasn't there. */
        MISSING,

        /** Far more files, or far larger ones, than the player has. */
        TOO_BIG,

        /** The phone couldn't keep them (out of space, say). */
        STORAGE,
    }

    private sealed interface One {
        class Got(val file: GuiFile, val bytes: ByteArray) : One
        class Missing(val status: Int) : One
        class Refused(val status: Int) : One
        data object Unreachable : One
    }

    /**
     * Saves the player at `base`. `previous` (its last saved copy) saves asking for
     * files again; `extra` are paths the page asked for that weren't saved before.
     */
    suspend fun update(
        base: String,
        previous: GuiSnapshot?,
        extra: Collection<String> = emptyList(),
        onSaved: (files: Int) -> Unit = {},
    ): Outcome = coroutineScope {
        val baseUri = URI(base)
        val before = previous?.takeIf { it.base == base }
        val files = LinkedHashMap<String, GuiFile>()
        val seen = HashSet<String>()
        val skipped = HashSet<String>() // asked for, as not needed, and not there
        val parallel = Semaphore(PARALLEL)
        var appVersion: String? = null
        var bytes = 0L
        var level = listOf(GuiRefs.Ref(GuiRefs.INDEX, true)) + extra.filter(GuiRefs::isFilePath).map { GuiRefs.Ref(it, false) }
        while (level.isNotEmpty()) {
            // Each path once, needed if anything needs it; one found missing before, now
            // needed, means the app can't start.
            val wanted = LinkedHashMap<String, Boolean>()
            for (ref in level) wanted[ref.path] = (wanted[ref.path] ?: false) || ref.required
            val todo = ArrayList<GuiRefs.Ref>()
            for ((path, required) in wanted) {
                if (path in skipped) {
                    if (required) return@coroutineScope Outcome.Failed(Reason.MISSING, 404)
                } else if (seen.add(path)) {
                    todo += GuiRefs.Ref(path, required)
                }
            }
            if (seen.size > MAX_FILES) return@coroutineScope Outcome.Failed(Reason.TOO_BIG)
            val results = todo.map { ref ->
                async(Dispatchers.IO) { parallel.withPermit { ref to fetchOne(baseUri, ref.path, before) } }
            }.awaitAll()
            val next = ArrayList<GuiRefs.Ref>()
            for ((ref, result) in results) {
                when (result) {
                    is One.Got -> {
                        val text = if (GuiRefs.kindOf(ref.path) != GuiRefs.Kind.OTHER) String(result.bytes, Charsets.UTF_8) else null
                        if (ref.path == GuiRefs.INDEX && (text == null || !GuiRefs.looksLikePlexampWeb(text))) {
                            return@coroutineScope Outcome.Failed(Reason.NOT_PLAYER)
                        }
                        files[ref.path] = result.file
                        bytes += result.bytes.size
                        if (bytes > MAX_BYTES) return@coroutineScope Outcome.Failed(Reason.TOO_BIG)
                        if (text != null) {
                            next += GuiRefs.find(baseUri, ref.path, text)
                            if (appVersion == null) appVersion = GuiRefs.appVersion(ref.path, text)
                        }
                        onSaved(files.size)
                    }
                    is One.Missing -> if (ref.required) return@coroutineScope Outcome.Failed(Reason.MISSING, result.status) else skipped += ref.path
                    is One.Refused -> if (ref.required) return@coroutineScope Outcome.Failed(Reason.REFUSED, result.status) else skipped += ref.path
                    One.Unreachable -> if (ref.required) return@coroutineScope Outcome.Failed(Reason.UNREACHABLE) else skipped += ref.path
                }
            }
            level = next
        }
        val snapshot = GuiSnapshot(newId(), base, clock(), appVersion, files)
        if (before != null && before.sameFilesAs(snapshot)) {
            // The same version: kept as it was, with what the server said of each file now.
            val same = snapshot.copy(id = before.id, savedAt = before.savedAt)
            store.install(same)
            Outcome.Saved(same, changed = false)
        } else {
            store.install(snapshot)
            Outcome.Saved(snapshot, changed = true)
        }
    }

    private fun fetchOne(base: URI, path: String, before: GuiSnapshot?): One {
        val old = before?.files?.get(path)?.takeIf { store.hasBlob(it.blob) }
        val url = if (path == GuiRefs.INDEX) base.toString() else base.resolve(URI(null, null, path, null)).toString()
        val res = try {
            fetcher.get(url, old?.etag, old?.lastModified)
        } catch (e: IOException) {
            return One.Unreachable
        }
        return when (res.status) {
            304 -> {
                if (old == null) return One.Missing(304)
                val kept = try {
                    store.readBlob(old.blob)
                } catch (e: IOException) {
                    return One.Unreachable
                }
                One.Got(old.copy(etag = res.etag ?: old.etag, lastModified = res.lastModified ?: old.lastModified), kept)
            }
            200 -> {
                val body = res.body ?: return One.Unreachable
                if (!GuiRefs.typeMatches(path, res.contentType)) return One.Missing(415)
                val sha = store.putBlob(body)
                One.Got(GuiFile(sha, res.contentType ?: GuiRefs.guessType(path), res.etag, res.lastModified, res.headers), body)
            }
            401, 403, 407 -> One.Refused(res.status)
            else -> One.Missing(res.status)
        }
    }

    private fun newId() = "%x-%04x".format(clock(), Random.nextInt(0x10000))

    companion object {
        private const val PARALLEL = 4
        private const val MAX_FILES = 500
        private const val MAX_BYTES = 64L shl 20
    }
}
