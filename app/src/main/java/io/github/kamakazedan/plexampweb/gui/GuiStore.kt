package io.github.kamakazedan.plexampweb.gui

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * The saved copies of the player, in the app's own storage:
 *
 *     blobs/<sha256>        each file's contents, once however many copies have it
 *     snapshots/<id>.json   each copy's list of files
 *     current               the copy in use
 *
 * A new copy is put in place in one step (current is replaced whole), so the player
 * is never served half of one version and half of another.
 */
class GuiStore(private val root: File) {
    private val blobs = File(root, "blobs")
    private val snapshots = File(root, "snapshots")
    private val currentFile = File(root, "current")
    private val lock = Any()

    @Volatile private var cached: GuiSnapshot? = null
    @Volatile private var loaded = false

    /** The copy in use, or null where there's none (or it's damaged). */
    fun current(): GuiSnapshot? {
        if (loaded) return cached
        synchronized(lock) {
            if (!loaded) {
                cached = readCurrent()
                loaded = true
            }
            return cached
        }
    }

    private fun readCurrent(): GuiSnapshot? = try {
        val id = currentFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { ID.matches(it) }
        id?.let { GuiSnapshot.fromJson(File(snapshots, "$it.json").readText()) }
            ?.takeIf { snapshot -> snapshot.files.values.all { blobFile(it.blob).isFile } }
    } catch (e: Exception) {
        null
    }

    fun blobFile(sha: String) = File(blobs, sha)

    fun hasBlob(sha: String) = blobFile(sha).isFile

    fun readBlob(sha: String): ByteArray = blobFile(sha).readBytes()

    /** Keeps `bytes` (once), and gives their name: their SHA-256. */
    fun putBlob(bytes: ByteArray): String {
        val sha = sha256(bytes)
        val file = blobFile(sha)
        if (!file.isFile) {
            blobs.mkdirs()
            writeAtomically(file, bytes)
        }
        return sha
    }

    /** Makes `snapshot` the copy in use. */
    fun install(snapshot: GuiSnapshot) = synchronized(lock) {
        require(ID.matches(snapshot.id))
        snapshots.mkdirs()
        writeAtomically(File(snapshots, "${snapshot.id}.json"), snapshot.toJson().toByteArray())
        writeAtomically(currentFile, snapshot.id.toByteArray())
        cached = snapshot
        loaded = true
    }

    /** Removes every copy but those in `keep` (and the one in use), and the files only they had. */
    fun clean(keep: Set<String>) = synchronized(lock) {
        val ids = keep + listOfNotNull(cached?.id)
        val used = HashSet<String>()
        snapshots.listFiles()?.forEach { file ->
            val id = file.name.removeSuffix(".json")
            if (file.name.endsWith(".json") && id in ids) {
                try {
                    GuiSnapshot.fromJson(file.readText()).files.values.mapTo(used) { it.blob }
                } catch (e: Exception) {
                    file.delete()
                }
            } else {
                file.delete()
            }
        }
        blobs.listFiles()?.forEach { if (it.name !in used) it.delete() }
    }

    /** Everything, gone. */
    fun clear() = synchronized(lock) {
        root.deleteRecursively()
        cached = null
        loaded = true
    }

    companion object {
        private val ID = Regex("^[A-Za-z0-9-]{1,64}$")

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** Written in full, then swapped in, so a crash part-way never leaves half a file. */
        fun writeAtomically(file: File, bytes: ByteArray) {
            val temp = File(file.parentFile, "${file.name}.${System.nanoTime()}.tmp")
            try {
                FileOutputStream(temp).use { out ->
                    out.write(bytes)
                    out.fd.sync()
                }
                if (!temp.renameTo(file)) throw IOException("Couldn't save ${file.name}.")
            } finally {
                temp.delete()
            }
        }
    }
}
