package io.github.kamakazedan.plexampweb

import android.app.Application
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import io.github.kamakazedan.plexampweb.downloads.DownloadWorker
import io.github.kamakazedan.plexampweb.downloads.Downloads
import io.github.kamakazedan.plexampweb.gui.GuiUpdateWorker
import io.github.kamakazedan.plexampweb.gui.GuiUpdates
import io.github.kamakazedan.plexampweb.media.MediaBridge
import io.github.kamakazedan.plexampweb.media.PlaybackService
import io.github.kamakazedan.plexampweb.net.Connectivity
import io.github.kamakazedan.plexampweb.update.AppUpdates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PlexampWebApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var gui: GuiUpdates
        private set
    lateinit var media: MediaBridge
        private set
    lateinit var downloads: Downloads
        private set
    lateinit var updates: AppUpdates
        private set

    /** Work that outlives a screen (saving the player), on the main thread. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _resets = MutableStateFlow(0)

    /** Goes up each time the player is forgotten (Settings › Use another player). */
    val resets: StateFlow<Int> = _resets.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        gui = GuiUpdates(this)
        media = MediaBridge(this)
        downloads = Downloads(this, prefs) { media.state == MediaBridge.State.PLAYING }
        updates = AppUpdates(prefs)
        PlaybackService.createChannel(this)
        DownloadWorker.createChannel(this)
        Connectivity.watch(this) { online, unmetered ->
            if (unmetered) scope.launch { gui.checkSoon() }
            downloads.networkChanged(online, unmetered)
        }
        if (prefs.playerUrl != null) GuiUpdateWorker.schedule(this)
    }

    /**
     * Signs out here and forgets the player: its address, its saved copy, downloads, and
     * everything the web app kept in the WebView (its sign-in, settings and queue).
     * `notify`: tell the screens, so they start again.
     */
    fun forgetPlayer(notify: Boolean = true) {
        media.reset()
        gui.forget()
        scope.launch(Dispatchers.IO) { downloads.forget() }
        prefs.forgetPlayer()
        prefs.clearWebCache = true
        GuiUpdateWorker.cancel(this)
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
        if (notify) _resets.value++
    }

    companion object {
        fun from(context: Context): PlexampWebApp = context.applicationContext as PlexampWebApp
    }
}
