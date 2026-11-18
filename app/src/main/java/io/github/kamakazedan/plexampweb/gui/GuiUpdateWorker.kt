package io.github.kamakazedan.plexampweb.gui

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.kamakazedan.plexampweb.PlexampWebApp
import java.util.concurrent.TimeUnit

/** Checks for a new version of the player every few hours, on Wi-Fi, while the app isn't open too. */
class GuiUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        PlexampWebApp.from(applicationContext).gui.checkInBackground()
        return Result.success()
    }

    companion object {
        private const val NAME = "player-update"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<GuiUpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
