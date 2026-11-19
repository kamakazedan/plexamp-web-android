package io.github.kamakazedan.plexampweb.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.github.kamakazedan.plexampweb.BuildConfig
import io.github.kamakazedan.plexampweb.PlexampWebApp
import io.github.kamakazedan.plexampweb.Prefs
import io.github.kamakazedan.plexampweb.R
import io.github.kamakazedan.plexampweb.auth.PlexSignIn
import io.github.kamakazedan.plexampweb.databinding.ActivityMainBinding
import io.github.kamakazedan.plexampweb.downloads.DownloadServer
import io.github.kamakazedan.plexampweb.gui.GuiUpdateWorker
import io.github.kamakazedan.plexampweb.gui.GuiUpdater
import io.github.kamakazedan.plexampweb.gui.NewVersion
import io.github.kamakazedan.plexampweb.media.MediaBridge
import io.github.kamakazedan.plexampweb.net.Connectivity
import io.github.kamakazedan.plexampweb.player.GuiServer
import io.github.kamakazedan.plexampweb.player.Handoff
import io.github.kamakazedan.plexampweb.player.PageBridge
import io.github.kamakazedan.plexampweb.player.PlayerChrome
import io.github.kamakazedan.plexampweb.player.PlayerClient
import io.github.kamakazedan.plexampweb.setup.PlayerAddress
import io.github.kamakazedan.plexampweb.setup.PlayerCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * The app's one screen: the player (plexamp-web, in a WebView, opened from the copy saved
 * on the phone) and, before it, the player's address, signing in with Plex, and saving
 * the player. Kept through rotation and the like (see the manifest), so the music never
 * stops for those.
 */
class MainActivity : AppCompatActivity(), PlayerClient.Host, PlayerChrome.Host, PageBridge.Host {
    private enum class Screen { NONE, ADDRESS, SIGN_IN, PREPARING, PLAYER }

    private lateinit var ui: ActivityMainBinding
    private val app: PlexampWebApp get() = PlexampWebApp.from(this)

    private var screen = Screen.NONE
    private var screenJob: Job? = null
    private var webView: WebView? = null
    private var webBase: String? = null
    private var server: GuiServer? = null
    private var bridge: PageBridge? = null

    /** A Plex sign-in made here, waiting to be handed to the web app. */
    private var handoff: Handoff? = null

    /** The web app's own sign-in page is wanted (for its offline password, or a server's token). */
    private var webSignIn = false

    /** Asks after a waiting sign-in at once (when the app's back from plex.tv). */
    private val pinPokes = Channel<Unit>(Channel.CONFLATED)

    private var filesCallback: ValueCallback<Array<Uri>>? = null
    private var fileToSave: ByteArray? = null

    /** The player's Settings › Downloads is wanted (from the downloads' notification). */
    private var downloadsWanted = false

    /** Something's been touched (or a key pressed) since the app was last opened. */
    private var touched = false

    /** The player's About asked to check for updates, and that's under way: see checkPlayer. */
    private var checkAsked = false

    /** The version the player's being reloaded onto, till its page starts. */
    private var reloadingTo: String? = null

    // (Downloads' progress shows in a notification: asked once, when the first starts.)
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val pickFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uris = if (result.resultCode == RESULT_OK) urisFrom(result.data) else null
        filesCallback?.onReceiveValue(uris)
        filesCallback = null
    }

    private val saveTo = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val bytes = fileToSave
        fileToSave = null
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null && bytes != null) writeFile(uri, bytes)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        ui = ActivityMainBinding.inflate(layoutInflater)
        setContentView(ui.root)
        ViewCompat.setOnApplyWindowInsetsListener(ui.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            WindowInsetsCompat.CONSUMED
        }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = goBack()
            },
        )
        ui.address.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_GO && screen == Screen.ADDRESS) {
                submitAddress()
                true
            } else {
                false
            }
        }
        lifecycleScope.launch { app.resets.drop(1).collect { onPlayerForgotten() } }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { app.gui.updated.collect { if (!checkAsked) useNewVersion() } }
        }
        downloadsWanted = savedInstanceState == null && intent?.action == ACTION_SHOW_DOWNLOADS
        route()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action != ACTION_SHOW_DOWNLOADS) return
        downloadsWanted = true
        if (screen == Screen.PLAYER) showPlayer()
    }

    override fun onStart() {
        super.onStart()
        touched = false
        app.gui.checkSoon()
        // (Saved while the app was out of sight, by the background check or Settings' button.)
        useNewVersion()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) touched = true
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) touched = true
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        pinPokes.trySend(Unit)
    }

    override fun onDestroy() {
        filesCallback?.onReceiveValue(null)
        filesCallback = null
        teardownWebView(clearCache = false)
        super.onDestroy()
    }

    // ---------- which screen ----------

    private fun route() {
        when {
            app.prefs.playerUrl == null -> showAddress()
            handoff == null && !app.prefs.signedIn && !webSignIn -> showSignIn()
            app.gui.current() == null -> showPreparing()
            else -> showPlayer()
        }
    }

    private fun setScreen(next: Screen) {
        screenJob?.cancel()
        screenJob = null
        screen = next
        val player = next == Screen.PLAYER
        ui.playerContainer.isVisible = player
        ui.setup.isVisible = !player
        if (!player) {
            ui.setup.scrollTo(0, 0)
            ui.addressLayout.isVisible = next == Screen.ADDRESS
            ui.progress.isVisible = false
            setStatus(null)
        }
    }

    private fun goBack() {
        when (screen) {
            Screen.PLAYER -> {
                val view = webView
                when {
                    webSignIn && !app.prefs.signedIn -> {
                        webSignIn = false
                        showSignIn()
                    }
                    view != null && view.canGoBack() -> view.goBack()
                    // (Not closed: that would stop the music.)
                    else -> moveTaskToBack(true)
                }
            }
            Screen.SIGN_IN -> if (webView != null) moveTaskToBack(true) else showAddress()
            Screen.ADDRESS -> finish()
            else -> moveTaskToBack(true)
        }
    }

    // ---------- the player's address ----------

    private fun showAddress() {
        setScreen(Screen.ADDRESS)
        ui.title.setText(R.string.address_title)
        ui.body.setText(R.string.address_body)
        ui.addressLayout.error = null
        if (ui.address.text.isNullOrEmpty()) ui.address.setText(app.prefs.playerUrl.orEmpty())
        button(ui.primary, R.string.address_continue) { submitAddress() }
        button(ui.secondary, null)
        button(ui.tertiary, null)
    }

    private fun submitAddress() {
        val base = PlayerAddress.normalize(ui.address.text?.toString().orEmpty())
        if (base == null) {
            ui.addressLayout.error = getString(R.string.address_not_an_address)
            return
        }
        ui.addressLayout.error = null
        WindowCompat.getInsetsController(window, ui.address).hide(WindowInsetsCompat.Type.ime())
        checkAddress(base)
    }

    private fun checkAddress(address: String) {
        screenJob?.cancel()
        ui.primary.isEnabled = false
        ui.progress.isVisible = true
        setStatus(getString(R.string.address_checking))
        screenJob = lifecycleScope.launch {
            val result = PlayerCheck.check(address)
            ui.primary.isEnabled = true
            ui.progress.isVisible = false
            setStatus(null)
            when (result) {
                is PlayerCheck.Result.Found -> confirmPlayer(address, result.base)
                is PlayerCheck.Result.Unencrypted -> ui.addressLayout.error = getString(R.string.problem_downgrade, result.to)
                is PlayerCheck.Result.Problem -> ui.addressLayout.error = getString(result.message, result.code)
            }
        }
    }

    /**
     * The player where it was found, once you've said so where that needs a word: where
     * signing in through it would cross the internet unencrypted, or where the address
     * you typed sent the app on to another server. (Asked of where it ended up, not of
     * what you typed: an http:// address that moves to https:// is fine.)
     */
    private fun confirmPlayer(typed: String, found: String) {
        val unencrypted = PlayerAddress.isUnencryptedPublic(found)
        if (!unencrypted && !PlayerAddress.otherServer(typed, found)) return onPlayerFound(found)
        MaterialAlertDialogBuilder(this)
            .setTitle(if (unencrypted) R.string.unencrypted_title else R.string.moved_title)
            .setMessage(getString(if (unencrypted) R.string.unencrypted_body else R.string.moved_body, found))
            .setPositiveButton(if (unencrypted) R.string.unencrypted_continue else R.string.address_continue) { _, _ -> onPlayerFound(found) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun onPlayerFound(base: String) {
        val before = app.prefs.playerUrl
        if (before != null && before != base) {
            teardownWebView(clearCache = true)
            app.forgetPlayer(notify = false)
        }
        app.prefs.playerUrl = base
        app.prefs.signedIn = false
        GuiUpdateWorker.schedule(this)
        // Saved meanwhile, while you sign in.
        app.gui.start()
        showSignIn()
    }

    // ---------- signing in with Plex ----------

    private fun showSignIn() {
        setScreen(Screen.SIGN_IN)
        ui.title.setText(R.string.signin_title)
        ui.body.setText(R.string.signin_body)
        button(ui.primary, R.string.signin_button) { startSignIn() }
        button(ui.secondary, R.string.signin_other_ways) { openWebSignIn() }
        button(ui.tertiary, R.string.signin_other_player) { showAddress() }
        val pending = app.prefs.pendingPin
        if (pending != null && System.currentTimeMillis() - pending.createdAt < PIN_TTL_MS) {
            // Back after the app was closed while you signed in: carry on.
            screenJob = lifecycleScope.launch { waitForPlex(PlexSignIn(app.prefs.clientId), pending) }
        } else if (pending != null) {
            app.prefs.pendingPin = null
        }
    }

    private fun startSignIn() {
        screenJob?.cancel()
        signInBusy(R.string.signin_opening)
        screenJob = lifecycleScope.launch {
            val plex = PlexSignIn(app.prefs.clientId)
            val pin = try {
                plex.createPin()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                signInIdle(R.string.signin_unreachable)
                return@launch
            }
            val pending = Prefs.PendingPin(pin.id, System.currentTimeMillis())
            app.prefs.pendingPin = pending
            if (!openInBrowser(plex.authUrl(pin))) {
                app.prefs.pendingPin = null
                signInIdle(R.string.signin_no_browser)
                return@launch
            }
            waitForPlex(plex, pending)
        }
    }

    private suspend fun waitForPlex(plex: PlexSignIn, pending: Prefs.PendingPin) {
        signInBusy(R.string.signin_waiting)
        // (Back without signing in: it can be started again.)
        ui.primary.isEnabled = true
        while (System.currentTimeMillis() - pending.createdAt < PIN_TTL_MS) {
            val token = try {
                plex.tokenFor(pending.id)
            } catch (e: PlexSignIn.PinGone) {
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // (plex.tv didn't answer this time: asked again)
            }
            if (token != null) {
                signInBusy(R.string.signin_finishing)
                val account = plex.account(token)
                app.prefs.pendingPin = null
                onSignedIn(Handoff(token, account.name ?: getString(R.string.signed_in_name_fallback), account.thumb))
                return
            }
            withTimeoutOrNull(PIN_POLL_MS) { pinPokes.receive() }
        }
        app.prefs.pendingPin = null
        signInIdle(R.string.signin_not_finished)
    }

    private fun onSignedIn(signedIn: Handoff) {
        handoff = signedIn
        webSignIn = false
        // Closes plex.tv's page, where Android lets the app come back by itself (otherwise
        // closing it shows the player).
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (e: Exception) {
            // (Not allowed from the background: fine.)
        }
        route()
    }

    private fun openWebSignIn() {
        app.prefs.pendingPin = null
        webSignIn = true
        route()
    }

    private fun signInBusy(@StringRes status: Int) {
        ui.progress.isVisible = true
        ui.primary.isEnabled = false
        setStatus(getString(status))
    }

    private fun signInIdle(@StringRes status: Int) {
        ui.progress.isVisible = false
        ui.primary.isEnabled = true
        setStatus(getString(status))
    }

    // ---------- saving the player ----------

    private fun showPreparing() {
        setScreen(Screen.PREPARING)
        ui.title.setText(R.string.prepare_title)
        ui.body.setText(R.string.prepare_body)
        ui.progress.isVisible = true
        button(ui.primary, null)
        button(ui.secondary, null)
        button(ui.tertiary, null)
        screenJob = lifecycleScope.launch {
            val counting = launch {
                app.gui.saving.collect { n ->
                    if (n != null && n > 0) setStatus(resources.getQuantityString(R.plurals.prepare_saved, n, n))
                }
            }
            val outcome = app.gui.ensure()
            counting.cancel()
            ui.progress.isVisible = false
            if (outcome is GuiUpdater.Outcome.Saved && app.gui.current() != null) {
                route()
            } else {
                setStatus(if (outcome is GuiUpdater.Outcome.Failed) problem(outcome) else getString(R.string.prepare_offline))
                button(ui.primary, R.string.prepare_try_again) { showPreparing() }
                button(ui.tertiary, R.string.signin_other_player) { showAddress() }
            }
        }
    }

    private fun problem(failed: GuiUpdater.Outcome.Failed): String = when (failed.reason) {
        GuiUpdater.Reason.UNREACHABLE ->
            getString(if (Connectivity.isOnline(this)) R.string.problem_unreachable else R.string.prepare_offline)
        GuiUpdater.Reason.NOT_PLAYER -> getString(R.string.problem_not_player)
        GuiUpdater.Reason.REFUSED ->
            if (failed.status == 401) getString(R.string.problem_password) else getString(R.string.problem_refused, failed.status)
        GuiUpdater.Reason.MISSING -> getString(R.string.problem_error, failed.status)
        GuiUpdater.Reason.TOO_BIG -> getString(R.string.problem_too_big)
        GuiUpdater.Reason.STORAGE -> getString(R.string.player_save_failed)
    }

    // ---------- the player ----------

    private fun showPlayer() {
        val base = app.prefs.playerUrl ?: return showAddress()
        setScreen(Screen.PLAYER)
        val view = webView?.takeIf { webBase == base } ?: createWebView(base)
        when {
            handoff != null -> {
                server?.handoffPending = true
                view.loadUrl(base + GuiServer.HANDOFF_PATH)
            }
            webSignIn -> view.loadUrl("$base#/login")
            downloadsWanted -> {
                downloadsWanted = false
                view.loadUrl("$base#$DOWNLOADS_ROUTE")
            }
            view.url == null -> view.loadUrl(base)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(base: String): WebView {
        teardownWebView(clearCache = false)
        val view = WebView(this)
        view.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // The next track starts by itself, and buttons on the lock screen or a headset play
            // it: neither is a tap on the page.
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            setGeolocationEnabled(false)
            // (The web app's own text size setting decides.)
            textZoom = 100
            userAgentString = "$userAgentString plexamp-web-android/${BuildConfig.VERSION_NAME}"
        }
        if (app.prefs.clearWebCache) {
            view.clearCache(true)
            app.prefs.clearWebCache = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        val guiServer = GuiServer(app.gui, base, DownloadServer(app.downloads))
        view.webViewClient = PlayerClient(this, guiServer)
        view.webChromeClient = PlayerChrome(this)
        view.setDownloadListener { url, _, _, _, _ ->
            // (A file the page makes itself, a playlist say, comes through the bridge instead.)
            if (!url.startsWith("blob:")) openOutside(url.toUri(), userAsked = true)
        }
        val pageBridge = PageBridge(this, app.media, app.downloads, app.updates, lifecycleScope)
        pageBridge.attach(this, view, base)
        ui.playerContainer.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        webView = view
        webBase = base
        server = guiServer
        bridge = pageBridge
        return view
    }

    private fun teardownWebView(clearCache: Boolean) {
        val view = webView ?: return
        bridge?.detach()
        bridge = null
        if (clearCache) view.clearCache(true)
        ui.playerContainer.removeView(view)
        view.stopLoading()
        view.destroy()
        webView = null
        webBase = null
        server = null
        reloadingTo = null
        app.gui.inUse = null
    }

    // (The web app's pages: listed in plexamp-web's docs/android-app.md, whose CI fails where they change.)
    override fun onRoute(route: String) {
        val signInPage = route == "/login" || route.startsWith("/login?")
        if (!signInPage) {
            if (!app.prefs.signedIn) app.prefs.signedIn = true
            webSignIn = false
            return
        }
        val wasSignedIn = app.prefs.signedIn
        app.prefs.signedIn = false
        // Pictures the WebView kept have the sign-in in their address.
        if (wasSignedIn) webView?.clearCache(true)
        // Its own sign-in page where that's wanted, or where it says why you were signed out
        // (offline mode ending): its "Sign in with Plex" comes back here.
        if (webSignIn || route.contains("offline=")) return
        if (screen == Screen.PLAYER && handoff == null) showSignIn()
    }

    override fun onWebSignInWanted() {
        webSignIn = false
        showSignIn()
        startSignIn()
    }

    override fun onPlayerPageStarted() {
        reloadingTo = null
        bridge?.onNewPage()
    }

    override fun onHandoffLoaded(view: WebView) {
        val signedIn = handoff ?: return
        val base = webBase ?: return
        handoff = null
        view.evaluateJavascript(signedIn.script(base, app.prefs.clientId)) { result ->
            server?.handoffPending = false
            if (result?.contains("ok") == true) {
                app.prefs.signedIn = true
            } else {
                showSignIn()
                setStatus(getString(R.string.signin_not_finished))
            }
        }
    }

    override fun onPlayerLoadFailed() {
        if (app.gui.current() == null && screen == Screen.PLAYER) showPreparing()
    }

    override fun onRendererGone(view: WebView) {
        if (view !== webView) {
            view.destroy()
            return
        }
        teardownWebView(clearCache = false)
        if (screen == Screen.PLAYER) {
            Toast.makeText(this, R.string.player_restarting, Toast.LENGTH_LONG).show()
            showPlayer()
        }
    }

    override fun openOutside(uri: Uri, userAsked: Boolean) {
        if (!userAsked) return
        try {
            when (uri.scheme?.lowercase(Locale.ROOT)) {
                "http", "https" -> customTab().launchUrl(this, uri)
                "mailto", "tel" -> startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
        } catch (e: ActivityNotFoundException) {
            toast(R.string.player_no_app)
        }
    }

    private fun openInBrowser(uri: Uri): Boolean = try {
        customTab().launchUrl(this, uri)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    private fun customTab() = CustomTabsIntent.Builder()
        .setShowTitle(true)
        .setColorScheme(CustomTabsIntent.COLOR_SCHEME_DARK)
        .build()

    /**
     * A newer version of the player saved than the one on screen: reloaded onto it, or
     * Reload offered, as NewVersion says. (As the app's opened, and as one's saved; and,
     * `asked`, as a check asked for ends, as if the app had just opened.)
     */
    private fun useNewVersion(asked: Boolean = false): NewVersion.Step {
        val view = webView ?: return NewVersion.Step.NONE
        if (screen != Screen.PLAYER) return NewVersion.Step.NONE
        val newest = app.gui.current()?.id
        if (newest != null && newest == reloadingTo) return NewVersion.Step.NONE
        val step = NewVersion.step(
            inUse = app.gui.inUse,
            newest = newest,
            opening = !touched || asked,
            playing = app.media.state == MediaBridge.State.PLAYING,
            waiting = handoff != null || webSignIn || filesCallback != null || fileToSave != null,
        )
        when (step) {
            NewVersion.Step.RELOAD -> {
                reloadingTo = newest
                view.reload()
            }
            NewVersion.Step.OFFER -> offerReload(view)
            NewVersion.Step.NONE -> Unit
        }
        return step
    }

    // (The player's About, "Check for updates": see AppBridge.)
    override suspend fun checkPlayer(): String {
        checkAsked = true
        try {
            val outcome = app.gui.start().await()
            return when (useNewVersion(asked = true)) {
                NewVersion.Step.RELOAD -> "reloading"
                NewVersion.Step.OFFER -> "offered"
                NewVersion.Step.NONE -> if (outcome is GuiUpdater.Outcome.Failed) "failed" else "same"
            }
        } finally {
            checkAsked = false
        }
    }

    private fun offerReload(view: WebView) {
        Snackbar.make(ui.root, R.string.player_updated, Snackbar.LENGTH_LONG)
            .setAction(R.string.player_reload) { if (webView === view) view.reload() }
            .setDuration(UPDATE_NOTICE_MS)
            .show()
    }

    private fun onPlayerForgotten() {
        teardownWebView(clearCache = true)
        handoff = null
        webSignIn = false
        ui.address.setText("")
        route()
    }

    // ---------- files ----------

    override fun chooseFiles(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        filesCallback?.onReceiveValue(null)
        filesCallback = callback
        val types = params.acceptTypes?.filter { !it.isNullOrBlank() }.orEmpty()
        val images = types.isNotEmpty() && types.all { it.startsWith("image/") }
        val intent = Intent(Intent.ACTION_GET_CONTENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(if (images) "image/*" else "*/*")
        if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            pickFiles.launch(intent)
        } catch (e: ActivityNotFoundException) {
            filesCallback = null
            callback.onReceiveValue(null)
            toast(R.string.player_no_app)
        }
        return true
    }

    private fun urisFrom(data: Intent?): Array<Uri>? {
        if (data == null) return null
        data.clipData?.let { clip -> return Array(clip.itemCount) { clip.getItemAt(it).uri } }
        return data.data?.let { arrayOf(it) }
    }

    override fun saveFile(name: String, mime: String, bytes: ByteArray) {
        fileToSave = bytes
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mime.ifBlank { "application/octet-stream" })
            .putExtra(Intent.EXTRA_TITLE, name)
        try {
            saveTo.launch(intent)
        } catch (e: ActivityNotFoundException) {
            fileToSave = null
            toast(R.string.player_no_app)
        }
    }

    private fun writeFile(uri: Uri, bytes: ByteArray) {
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null
                } catch (e: Exception) {
                    false
                }
            }
            toast(if (saved) R.string.player_saved_file else R.string.player_save_failed)
        }
    }

    override fun onDownloadsStarted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || app.prefs.askedForNotifications) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        app.prefs.askedForNotifications = true
        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun share(title: String?, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        if (title != null) send.putExtra(Intent.EXTRA_SUBJECT, title)
        try {
            startActivity(Intent.createChooser(send, title))
        } catch (e: ActivityNotFoundException) {
            toast(R.string.player_no_app)
        }
    }

    // ---------- bits ----------

    private fun button(view: MaterialButton, @StringRes text: Int?, onClick: (() -> Unit)? = null) {
        view.isVisible = text != null
        if (text == null) return
        view.setText(text)
        view.isEnabled = true
        view.setOnClickListener { onClick?.invoke() }
    }

    private fun setStatus(text: String?) {
        ui.status.isVisible = text != null
        ui.status.text = text
    }

    private fun toast(@StringRes text: Int) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        /** Opens the player's Settings › Downloads (the downloads' notification). */
        const val ACTION_SHOW_DOWNLOADS = "io.github.kamakazedan.plexampweb.SHOW_DOWNLOADS"

        /** The player's page for downloads (listed in plexamp-web's docs/android-app.md). */
        private const val DOWNLOADS_ROUTE = "/settings/downloads"

        /** As long as plex.tv keeps a sign-in waiting (and the web app waits for one). */
        private const val PIN_TTL_MS = 15 * 60 * 1000L
        private const val PIN_POLL_MS = 2000L
        private const val UPDATE_NOTICE_MS = 10_000
    }
}
