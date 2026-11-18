package io.github.kamakazedan.plexampweb.media

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.kamakazedan.plexampweb.PlexampWebApp
import io.github.kamakazedan.plexampweb.R
import io.github.kamakazedan.plexampweb.ui.MainActivity
import androidx.media.app.NotificationCompat as MediaNotificationCompat

/**
 * Keeps the app running while the player plays (with the screen off, or in another
 * app), with what's playing in a notification. Started once something plays; ends
 * once nothing does, or after a while paused, or when the app is swiped away (the
 * player lives in the app's screen, so that's where the music stops too).
 */
class PlaybackService : Service() {
    private val media get() = PlexampWebApp.from(this).media
    private val handler = Handler(Looper.getMainLooper())
    // Paused a long while: the app stops keeping itself running, so the lock screen and
    // headsets stop offering to play it too, until it's played from the app again.
    private val stopNow = Runnable {
        media.idle()
        stopSelf()
    }
    private val onChange: () -> Unit = { refresh() }
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        media.addListener(onChange)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> media.command("play")
            ACTION_PAUSE -> media.command("pause")
            ACTION_NEXT -> media.command("nexttrack")
            ACTION_PREVIOUS -> media.command("previoustrack")
        }
        // (Started as a foreground service, it must say so at once, whatever happens next.)
        goForeground()
        refresh()
        return START_NOT_STICKY
    }

    @SuppressLint("InlinedApi") // (ServiceCompat passes the service's type on Android 10 and later only.)
    private fun goForeground() {
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            foreground = true
        } catch (e: Exception) {
            // Android didn't allow it just now (from the background, on Android 12 and later).
            foreground = false
        }
    }

    private fun refresh() {
        when (media.state) {
            MediaBridge.State.PLAYING -> {
                handler.removeCallbacks(stopNow)
                if (foreground) show() else goForeground()
            }
            MediaBridge.State.PAUSED -> {
                show()
                handler.removeCallbacks(stopNow)
                handler.postDelayed(stopNow, PAUSED_FOR_MS)
            }
            MediaBridge.State.NONE -> stopSelf()
        }
    }

    // Media notifications don't need the notification permission (Android 13 and later); without it
    // this one still shows.
    @SuppressLint("MissingPermission")
    private fun show() {
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification())
        } catch (e: SecurityException) {
            // (Notifications turned off for the app.)
        }
    }

    private fun notification(): Notification {
        val playing = media.state == MediaBridge.State.PLAYING
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(media.title ?: getString(R.string.app_name))
            .setContentText(media.artist)
            .setSubText(media.album)
            .setLargeIcon(media.art)
            .setContentIntent(openApp())
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setOngoing(playing)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        val compact = ArrayList<Int>()
        if (media.can("previoustrack")) {
            builder.addAction(R.drawable.ic_previous, getString(R.string.action_previous), action(ACTION_PREVIOUS))
            compact += compact.size
        }
        if (playing) {
            builder.addAction(R.drawable.ic_pause, getString(R.string.action_pause), action(ACTION_PAUSE))
        } else {
            builder.addAction(R.drawable.ic_play, getString(R.string.action_play), action(ACTION_PLAY))
        }
        compact += compact.size
        if (media.can("nexttrack")) {
            builder.addAction(R.drawable.ic_next, getString(R.string.action_next), action(ACTION_NEXT))
            compact += compact.size
        }
        builder.setStyle(
            MediaNotificationCompat.MediaStyle()
                .setMediaSession(media.session.sessionToken)
                .setShowActionsInCompactView(*compact.toIntArray()),
        )
        return builder.build()
    }

    private fun action(name: String): PendingIntent = PendingIntent.getForegroundService(
        this,
        name.hashCode(),
        Intent(this, PlaybackService::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(stopNow)
        media.removeListener(onChange)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
        private const val PAUSED_FOR_MS = 10 * 60 * 1000L
        private const val ACTION_PLAY = "io.github.kamakazedan.plexampweb.PLAY"
        private const val ACTION_PAUSE = "io.github.kamakazedan.plexampweb.PAUSE"
        private const val ACTION_NEXT = "io.github.kamakazedan.plexampweb.NEXT"
        private const val ACTION_PREVIOUS = "io.github.kamakazedan.plexampweb.PREVIOUS"

        fun createChannel(context: Context) {
            val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.playback_channel))
                .setDescription(context.getString(R.string.playback_channel_description))
                .setShowBadge(false)
                .build()
            NotificationManagerCompat.from(context).createNotificationChannel(channel)
        }

        /** Once something plays. (Android may not allow it from the background; it's then already running.) */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
            } catch (e: Exception) {
                // Not allowed just now.
            }
        }
    }
}
