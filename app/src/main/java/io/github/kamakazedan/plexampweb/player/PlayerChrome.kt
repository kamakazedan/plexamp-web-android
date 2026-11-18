package io.github.kamakazedan.plexampweb.player

import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import io.github.kamakazedan.plexampweb.BuildConfig

/** Choosing files for the page (playlist files, a playlist's picture); no camera, microphone or location. */
class PlayerChrome(private val host: Host) : WebChromeClient() {
    interface Host {
        fun chooseFiles(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: WebChromeClient.FileChooserParams,
    ): Boolean = host.chooseFiles(filePathCallback, fileChooserParams)

    override fun onPermissionRequest(request: PermissionRequest) = request.deny()

    override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) =
        callback.invoke(origin, false, false)

    // Release builds keep the page's messages out of the phone's log: they can have addresses in them.
    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean = !BuildConfig.DEBUG
}
