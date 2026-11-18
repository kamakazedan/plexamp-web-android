package io.github.kamakazedan.plexampweb.downloads

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.kamakazedan.plexampweb.PlexampWebApp
import io.github.kamakazedan.plexampweb.R
import io.github.kamakazedan.plexampweb.ui.MainActivity
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Brings downloads up to date (see Downloads.sync): soon after a playlist's added or a
 * setting changes, and every few hours, whenever the phone's on a connection downloads
 * may use (Wi-Fi only, unless you've said mobile data too). Shows its progress in a
 * notification while it downloads.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val downloads = PlexampWebApp.from(applicationContext).downloads
        val shows = try {
            setForeground(foregroundInfo(null))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // (Android doesn't allow it from the background at times: it goes on without, for as long as it may.)
            false
        }
        var shown = 0L
        val outcome = downloads.sync(stopped = { isStopped }) { p ->
            val now = SystemClock.elapsedRealtime()
            if (shows && (now - shown >= NOTIFY_MS || p.done == p.total)) {
                shown = now
                setForegroundAsync(foregroundInfo(p))
            }
        }
        return when (outcome) {
            DownloadSync.Outcome.RETRY, DownloadSync.Outcome.STOPPED -> Result.retry()
            DownloadSync.Outcome.DONE, DownloadSync.Outcome.SIGN_IN -> Result.success()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

    private fun foregroundInfo(progress: DownloadSync.Progress?): ForegroundInfo {
        val context = applicationContext
        val counted = progress?.takeIf { it.total > 0 }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.downloads_notification_title))
            .setContentText(
                if (counted != null) {
                    context.getString(R.string.downloads_notification_progress, counted.done, counted.total)
                } else {
                    context.getString(R.string.downloads_notification_checking)
                },
            )
            .setProgress(counted?.total ?: 0, counted?.done ?: 0, counted == null)
            .setContentIntent(openDownloads(context))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val ONCE = "downloads"
        private const val EVERY_FEW_HOURS = "downloads-periodic"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 2
        private const val NOTIFY_MS = 1000L
        private const val HOURS = 6L

        /**
         * Soon (`restart`: what's under way now starts again, with the settings as they
         * are now), and every few hours.
         */
        fun schedule(context: Context, settings: DownloadSettings, restart: Boolean) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (settings.network == DownloadSettings.ANY) NetworkType.CONNECTED else NetworkType.UNMETERED)
                .setRequiresStorageNotLow(true)
                .build()
            val work = WorkManager.getInstance(context)
            work.enqueueUniqueWork(
                ONCE,
                if (restart) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<DownloadWorker>()
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                    .build(),
            )
            work.enqueueUniquePeriodicWork(
                EVERY_FEW_HOURS,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<DownloadWorker>(HOURS, TimeUnit.HOURS).setConstraints(constraints).build(),
            )
        }

        fun cancel(context: Context) {
            val work = WorkManager.getInstance(context)
            work.cancelUniqueWork(ONCE)
            work.cancelUniqueWork(EVERY_FEW_HOURS)
        }

        fun createChannel(context: Context) {
            val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.downloads_channel))
                .setDescription(context.getString(R.string.downloads_channel_description))
                .setShowBadge(false)
                .build()
            NotificationManagerCompat.from(context).createNotificationChannel(channel)
        }

        /** The player's Settings › Downloads. */
        private fun openDownloads(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_SHOW_DOWNLOADS)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
