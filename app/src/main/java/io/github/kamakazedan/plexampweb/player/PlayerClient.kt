package io.github.kamakazedan.plexampweb.player

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * The player's WebView: its own files come from the saved copy (GuiServer), it stays
 * on the player's address (anything else opens outside the app), and which page the
 * web app is on tells the app whether it's signed in.
 */
class PlayerClient(private val host: Host, private val server: GuiServer) : WebViewClient() {
    interface Host {
        /** The web app's own "Sign in with Plex" was tapped (its sign-in page, from offline mode, say). */
        fun onWebSignInWanted()

        /** The web app's page now: "/login", "/setup", "/" (the part after #). */
        fun onRoute(route: String)

        /** A page of the player's is starting to load (the web app starts again). */
        fun onPlayerPageStarted()

        /** The page that hands the sign-in over is ready. */
        fun onHandoffLoaded(view: WebView)

        /** The player's page couldn't load (not saved, and no connection). */
        fun onPlayerLoadFailed()

        /** The WebView's page was stopped by Android (out of memory, say), so the WebView can't be used again. */
        fun onRendererGone(view: WebView)

        /** A link to elsewhere: `userAsked` where it was tapped. */
        fun openOutside(uri: Uri, userAsked: Boolean)
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        server.respond(request)

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        if (server.pathOf(url) != null) return false
        // (A frame going elsewhere isn't followed: the player has none.)
        if (!request.isForMainFrame) return true
        if (url.scheme == "https" && url.host == "app.plex.tv" && url.path.orEmpty().startsWith("/auth")) {
            host.onWebSignInWanted()
        } else {
            host.openOutside(url, request.hasGesture())
        }
        return true
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        val path = server.pathOf(Uri.parse(url)) ?: return
        if (path != GuiServer.HANDOFF_PATH) host.onPlayerPageStarted()
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        routeOf(url)?.let(host::onRoute)
    }

    override fun onPageFinished(view: WebView, url: String) {
        val uri = Uri.parse(url)
        if (server.pathOf(uri) == GuiServer.HANDOFF_PATH) host.onHandoffLoaded(view) else routeOf(url)?.let(host::onRoute)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame && server.pathOf(request.url) != null) host.onPlayerLoadFailed()
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        host.onRendererGone(view)
        return true
    }

    private fun routeOf(url: String): String? {
        val uri = Uri.parse(url)
        val path = server.pathOf(uri) ?: return null
        if (path == GuiServer.HANDOFF_PATH) return null
        return uri.fragment?.takeIf { it.startsWith("/") }
    }
}
