package io.github.kamakazedan.plexampweb.downloads

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Each server's downloads, apart, so switching to another server loses none of them:
 *
 *     downloads/servers/<server's id>/    that server's (see DownloadStore)
 *
 * Downloads from before 0.3.0 were one server's, straight in downloads/: they're moved
 * to their server's place once it's known (see adoptOld).
 */
class DownloadShelves(private val root: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val serversDir = File(root, SERVERS)
    private val stores = HashMap<String, DownloadStore>()
    private val lock = Any()

    /** The servers there are downloads from, by id (each with a playlist or not: see sweep). */
    fun servers(): List<String> = synchronized(lock) {
        serversDir.listFiles()?.filter { it.isDirectory && DownloadStore.SERVER.matches(it.name) }?.map { it.name }?.sorted() ?: emptyList()
    }

    /** A server's downloads (none yet, where there aren't any). */
    fun store(server: String): DownloadStore = synchronized(lock) {
        require(DownloadStore.SERVER.matches(server))
        stores.getOrPut(server) { DownloadStore(File(serversDir, server), clock).also { it.sweep() } }
    }

    /** A server's downloads, where there are any (null where there aren't, or `server` isn't a server's id). */
    fun storeIfAny(server: String?): DownloadStore? = synchronized(lock) {
        if (server == null || !DownloadStore.SERVER.matches(server)) return@synchronized null
        stores[server] ?: if (File(serversDir, server).isDirectory) store(server) else null
    }

    /** A server's downloads, gone (a sync under way for them goes round again, and finds none). */
    fun remove(server: String) = synchronized(lock) {
        if (!DownloadStore.SERVER.matches(server)) return@synchronized
        val open = stores[server]
        if (open != null) open.clear() else File(serversDir, server).deleteRecursively()
    }

    /** Every server's downloads, and any from before 0.3.0: gone. */
    fun clear() = synchronized(lock) {
        stores.values.forEach { it.clear() }
        root.deleteRecursively()
    }

    /** The bytes every server's downloads take. */
    fun bytesUsed(): Long = servers().sumOf { store(it).bytesUsed() }

    /** Places with nothing downloaded in them, gone (a server's whose last playlist was removed, say). */
    fun sweep() = synchronized(lock) {
        serversDir.listFiles()?.forEach { f -> if (!f.isDirectory || !DownloadStore.SERVER.matches(f.name)) f.deleteRecursively() }
        for (server in servers()) {
            if (store(server).index().playlists.isEmpty()) remove(server)
        }
        if (!hasOld()) dropOld()
    }

    // ---------- from before 0.3.0 ----------

    /** Whether there are downloads from before 0.3.0, not yet moved to their server's place. */
    fun hasOld(): Boolean = File(root, INDEX).isFile

    /**
     * Downloads from before 0.3.0 (one server's, straight in downloads/), moved to their
     * server's place as they are, as `owner`'s (see DownloadAccount.ownerOf). The index
     * goes last: a move cut short (the phone switched off, say) carries on next time.
     */
    fun adoptOld(server: String, owner: String) = synchronized(lock) {
        require(DownloadStore.SERVER.matches(server))
        val oldIndex = File(root, INDEX)
        if (!oldIndex.isFile) return@synchronized
        val target = File(serversDir, server)
        val newIndex = File(target, INDEX)
        if (newIndex.exists() || server in stores) {
            // (That server's own are there already: never so, as those came after these.)
            dropOld()
            return@synchronized
        }
        target.mkdirs()
        for (dir in OLD_DIRS) moveAll(File(root, dir), File(target, dir))
        Files.move(oldIndex.toPath(), newIndex.toPath(), StandardCopyOption.ATOMIC_MOVE)
        dropOld()
        store(server).claim(owner, null)
    }

    /** Downloads from before 0.3.0, gone (or what's left of them, once moved). */
    fun dropOld() = synchronized(lock) {
        File(root, INDEX).delete()
        for (dir in OLD_DIRS) File(root, dir).deleteRecursively()
    }

    // Each file in `from` to `to` (the whole folder, where `to` isn't there yet), those there already left be.
    private fun moveAll(from: File, to: File) {
        if (!from.isDirectory) return
        if (!to.exists() && from.renameTo(to)) return
        to.mkdirs()
        from.listFiles()?.forEach { f ->
            val there = File(to, f.name)
            if (!there.exists()) Files.move(f.toPath(), there.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private companion object {
        const val SERVERS = "servers"
        const val INDEX = "index.json"
        val OLD_DIRS = listOf("tracks", "covers", "playlists")
    }
}
