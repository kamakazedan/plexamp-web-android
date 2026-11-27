package io.github.kamakazedan.plexampweb.media

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.content.ContextCompat
import io.github.kamakazedan.plexampweb.PlexampWebApp
import io.github.kamakazedan.plexampweb.net.Http
import io.github.kamakazedan.plexampweb.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * What the player's page is playing (from its Media Session: see page-bridge.js), as
 * Android's media session, so the lock screen, the notification, headsets, watches and
 * cars show it and control it. Their buttons go back to the page.
 *
 * While it plays: the playback service keeps the app running (PlaybackService), the
 * phone and its Wi-Fi stay awake enough to stream, and unplugging headphones (or a
 * Bluetooth headset or car going) pauses it, as music apps do.
 *
 * Main thread only.
 */
class MediaBridge(private val app: PlexampWebApp) {
    enum class State { NONE, PAUSED, PLAYING }

    /** (Read from any thread: downloads go gently while it's PLAYING.) */
    @Volatile
    var state = State.NONE
        private set
    var title: String? = null
        private set
    var artist: String? = null
        private set
    var album: String? = null
        private set
    var art: Bitmap? = null
        private set

    private var artUrl: String? = null
    private var artJob: Job? = null
    private var actions: Set<String> = emptySet()
    private var durationMs = 0L
    private var positionMs = 0L
    private var positionAt = 0L
    private var rate = 1f
    private var send: ((String) -> Unit)? = null
    private val listeners = LinkedHashSet<() -> Unit>()

    val session = MediaSessionCompat(app, "plexamp-web").apply {
        setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() = command("play")
            override fun onPause() = command("pause")
            override fun onStop() = command("stop")
            override fun onSkipToNext() = command("nexttrack")
            override fun onSkipToPrevious() = command("previoustrack")
            override fun onFastForward() = command("seekforward")
            override fun onRewind() = command("seekbackward")
            override fun onSeekTo(pos: Long) = command("seekto", pos / 1000.0)
        })
        setSessionActivity(
            PendingIntent.getActivity(
                app,
                0,
                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
    }

    private val wakeLock by lazy {
        (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "plexamp-web:playing")
            .apply { setReferenceCounted(false) }
    }

    @Suppress("DEPRECATION") // (WIFI_MODE_FULL_HIGH_PERF is still the one that keeps Wi-Fi up with the screen off.)
    private val wifiLock by lazy {
        (app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "plexamp-web:playing")
            .apply { setReferenceCounted(false) }
    }

    private var noisyRegistered = false
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && state == State.PLAYING) command("pause")
        }
    }

    /** The page's end: where buttons are sent. */
    fun connect(sender: (String) -> Unit) {
        send = sender
    }

    /** The page has gone (the player closed): nothing plays now. */
    fun disconnect() {
        send = null
        onNewPage()
    }

    /** A new page: nothing it hasn't said yet stands. */
    fun onNewPage() {
        actions = emptySet()
        clearMetadata()
        setState(State.NONE)
    }

    /** As if the page had gone (the player's being forgotten). */
    fun reset() = disconnect()

    fun onPageMessage(msg: JSONObject) {
        when (msg.optString("type")) {
            "actions" -> {
                val list = msg.optJSONArray("actions")
                actions = if (list == null) emptySet() else (0 until list.length()).map { list.optString(it) }.toSet()
                publishState()
            }
            "metadata" -> if (msg.optBoolean("empty")) clearMetadata() else setMetadata(msg)
            "state" -> setState(
                when (msg.optString("state")) {
                    "playing" -> State.PLAYING
                    "paused" -> State.PAUSED
                    else -> State.NONE
                },
            )
            "position" -> {
                val duration = seconds(msg, "duration")
                positionMs = seconds(msg, "position").coerceAtMost(if (duration > 0) duration else Long.MAX_VALUE)
                positionAt = SystemClock.elapsedRealtime()
                rate = msg.optDouble("rate", 1.0).toFloat().takeIf { it.isFinite() && it > 0f } ?: 1f
                if (kotlin.math.abs(duration - durationMs) >= 1000) {
                    durationMs = duration
                    publishMetadata()
                }
                publishState()
                if (state == State.PLAYING) keepAwake(true)
            }
        }
    }

    /** Paused a long while: no longer offered to the lock screen and headsets. */
    fun idle() {
        if (state != State.PLAYING) session.isActive = false
    }

    /** Whether the page has a handler for a button ("nexttrack", say). */
    fun can(action: String) = action in actions

    /** A button, sent to the page's own handler for it. */
    fun command(action: String, seekTime: Double? = null) {
        val msg = JSONObject().put("type", "action").put("action", action)
        if (seekTime != null) msg.put("seekTime", seekTime)
        send?.invoke(msg.toString())
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun changed() = listeners.toList().forEach { it() }

    private fun setState(new: State) {
        if (new == state) {
            publishState()
            return
        }
        positionMs = currentPosition()
        positionAt = SystemClock.elapsedRealtime()
        state = new
        keepAwake(new == State.PLAYING)
        listenForNoisy(new == State.PLAYING)
        session.isActive = new != State.NONE
        publishState()
        if (new == State.PLAYING) PlaybackService.start(app)
        changed()
    }

    private fun currentPosition(): Long {
        if (state != State.PLAYING) return positionMs
        val now = positionMs + ((SystemClock.elapsedRealtime() - positionAt) * rate).toLong()
        return if (durationMs > 0) now.coerceAtMost(durationMs) else now
    }

    private fun publishState() {
        var available = 0L
        if (can("play")) available = available or PlaybackStateCompat.ACTION_PLAY
        if (can("pause")) available = available or PlaybackStateCompat.ACTION_PAUSE
        if (can("play") || can("pause")) available = available or PlaybackStateCompat.ACTION_PLAY_PAUSE
        if (can("stop")) available = available or PlaybackStateCompat.ACTION_STOP
        if (can("nexttrack")) available = available or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        if (can("previoustrack")) available = available or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        if (can("seekto")) available = available or PlaybackStateCompat.ACTION_SEEK_TO
        if (can("seekforward")) available = available or PlaybackStateCompat.ACTION_FAST_FORWARD
        if (can("seekbackward")) available = available or PlaybackStateCompat.ACTION_REWIND
        val playback = when (state) {
            State.PLAYING -> PlaybackStateCompat.STATE_PLAYING
            State.PAUSED -> PlaybackStateCompat.STATE_PAUSED
            State.NONE -> PlaybackStateCompat.STATE_NONE
        }
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(available)
                .setState(playback, positionMs, if (state == State.PLAYING) rate else 0f, positionAt)
                .build(),
        )
    }

    private fun setMetadata(msg: JSONObject) {
        title = text(msg, "title")
        artist = text(msg, "artist")
        album = text(msg, "album")
        val url = text(msg, "artwork")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        if (url != artUrl) {
            artUrl = url
            art = null
            loadArt(url)
        }
        publishMetadata()
        changed()
    }

    private fun clearMetadata() {
        artJob?.cancel()
        title = null
        artist = null
        album = null
        art = null
        artUrl = null
        durationMs = 0
        positionMs = 0
        session.setMetadata(null)
        changed()
    }

    private fun publishMetadata() {
        if (title == null && artist == null) return
        val meta = MediaMetadataCompat.Builder()
        title?.let { meta.putString(MediaMetadataCompat.METADATA_KEY_TITLE, it) }
        artist?.let { meta.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, it) }
        album?.let { meta.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, it) }
        if (durationMs > 0) meta.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
        art?.let { meta.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
        session.setMetadata(meta.build())
    }

    // The cover, for the lock screen: from where the page shows it (its address can carry the
    // Plex token, so it goes nowhere else, and is never logged).
    private fun loadArt(url: String?) {
        artJob?.cancel()
        if (url == null) return
        artJob = app.scope.launch {
            val bitmap = withContext(Dispatchers.IO) { fetchBitmap(url) }
            if (bitmap != null && url == artUrl) {
                art = bitmap
                publishMetadata()
                changed()
            }
        }
    }

    private fun fetchBitmap(url: String): Bitmap? = try {
        val res = Http.request(url, headers = mapOf("Accept" to "image/*"), maxBytes = 12 shl 20, readTimeoutMs = 20_000)
        if (res.status == 200) decodeScaled(res.body, ART_SIZE) else null
    } catch (e: Exception) {
        null
    }

    private fun decodeScaled(bytes: ByteArray, max: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= max && bounds.outHeight / (sample * 2) >= max) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    // (Renewed while it plays: so a page that stops talking can't keep the phone awake for long.)
    private fun keepAwake(on: Boolean) {
        if (on) {
            wakeLock.acquire(AWAKE_FOR_MS)
            if (!wifiLock.isHeld) wifiLock.acquire()
        } else {
            if (wakeLock.isHeld) wakeLock.release()
            if (wifiLock.isHeld) wifiLock.release()
        }
    }

    private fun listenForNoisy(on: Boolean) {
        if (on && !noisyRegistered) {
            ContextCompat.registerReceiver(
                app,
                noisy,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            noisyRegistered = true
        } else if (!on && noisyRegistered) {
            app.unregisterReceiver(noisy)
            noisyRegistered = false
        }
    }

    private fun text(msg: JSONObject, name: String): String? =
        if (!msg.has(name) || msg.isNull(name)) null else msg.optString(name).takeIf { it.isNotBlank() }

    private fun seconds(msg: JSONObject, name: String): Long {
        val value = msg.optDouble(name, 0.0)
        return if (value.isFinite() && value > 0) (value * 1000).toLong() else 0L
    }

    private companion object {
        const val ART_SIZE = 512
        const val AWAKE_FOR_MS = 10 * 60 * 1000L
    }
}
