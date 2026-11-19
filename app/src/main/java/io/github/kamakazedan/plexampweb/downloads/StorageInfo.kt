package io.github.kamakazedan.plexampweb.downloads

import android.annotation.SuppressLint
import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.storage.StorageManager

/** The phone's storage: how much is free (as Settings › Storage says), and how much downloads may take. */
object StorageInfo {
    data class Space(val free: Long, val total: Long)

    /** Free and in all, as the phone's own Settings counts them. */
    @SuppressLint("UsableSpace") // (Only where the phone won't say, as its Settings would.)
    fun space(context: Context): Space {
        val counted = try {
            context.getSystemService(StorageStatsManager::class.java)?.let {
                Space(it.getFreeBytes(StorageManager.UUID_DEFAULT), it.getTotalBytes(StorageManager.UUID_DEFAULT))
            }
        } catch (e: Exception) {
            null
        }
        return counted ?: context.filesDir.let { Space(it.usableSpace, it.totalSpace) }
    }

    /**
     * The bytes downloads may take still: what's free now (not counting caches the phone
     * could clear, which it doesn't for a download), less what it keeps for itself (see
     * DownloadRules.reserve). Not getAllocatableBytes, which counts those caches too: only
     * allocateBytes clears them, and a download is written as it comes.
     */
    @SuppressLint("UsableSpace")
    fun room(context: Context): Long =
        (context.filesDir.usableSpace - DownloadRules.reserve(context.filesDir.totalSpace)).coerceAtLeast(0)
}
