package io.github.kamakazedan.plexampweb.player

import android.content.Context
import android.util.Base64
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.kamakazedan.plexampweb.downloads.DownloadBridge
import io.github.kamakazedan.plexampweb.downloads.Downloads
import io.github.kamakazedan.plexampweb.media.MediaBridge
import io.github.kamakazedan.plexampweb.setup.PlayerAddress
import kotlinx.coroutines.CoroutineScope
import org.json.JSONException
import org.json.JSONObject

/**
 * What the player's page and the app say to each other (see assets/page-bridge.js):
 * what's playing, for the lock screen, the notification, headsets and cars, with their
 * buttons sent back; files the page saves; things it shares; and downloads (see
 * DownloadBridge). Only the player's own page can use it: WebView gives it to pages at
 * the player's origin alone.
 *
 * Without the WebView features it needs (an old, never-updated WebView), the player
 * still works, without those.
 */
class PageBridge(
    private val host: Host,
    private val media: MediaBridge,
    private val downloads: Downloads,
    private val scope: CoroutineScope,
) {
    interface Host {
        fun saveFile(name: String, mime: String, bytes: ByteArray)
        fun share(title: String?, text: String)

        /** A playlist was added to downloads. */
        fun onDownloadsStarted()
    }

    private var reply: JavaScriptReplyProxy? = null
    private var script: ScriptHandler? = null
    private var downloadBridge: DownloadBridge? = null

    fun attach(context: Context, webView: WebView, base: String): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return false
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return false
        val origins = setOf(PlayerAddress.origin(base))
        WebViewCompat.addWebMessageListener(webView, OBJECT_NAME, origins) { _, message, _, isMainFrame, replyProxy ->
            val data = message.data
            if (isMainFrame && data != null) onMessage(data, replyProxy)
        }
        val code = context.assets.open(SCRIPT_ASSET).bufferedReader().use { it.readText() }
        script = WebViewCompat.addDocumentStartJavaScript(webView, code, origins)
        media.connect { json -> reply?.postMessage(json) }
        downloadBridge = DownloadBridge(downloads, scope, PlayerAddress.origin(base), host::onDownloadsStarted)
        return true
    }

    /** A new page: what the last one said no longer stands. */
    fun onNewPage() {
        reply = null
        media.onNewPage()
        downloadBridge?.stop()
    }

    fun detach() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) script?.remove()
        script = null
        reply = null
        media.disconnect()
        downloadBridge?.stop()
        downloadBridge = null
    }

    private fun onMessage(data: String, proxy: JavaScriptReplyProxy) {
        if (data.length > MAX_MESSAGE) return
        val msg = try {
            JSONObject(data)
        } catch (e: JSONException) {
            return
        }
        reply = proxy
        when (msg.optString("type")) {
            "hello" -> media.onNewPage()
            "download" -> {
                val bytes = try {
                    Base64.decode(msg.optString("data"), Base64.DEFAULT)
                } catch (e: IllegalArgumentException) {
                    return
                }
                host.saveFile(safeName(msg.optString("name")), msg.optString("mime"), bytes)
            }
            "share" -> {
                val text = msg.optString("text").takeIf { it.isNotBlank() } ?: return
                host.share(msg.optString("title").takeIf { it.isNotBlank() && !msg.isNull("title") }, text)
            }
            // (Answered to the page that asked, if it's still the one there. WebView can answer
            // wherever the page could write: asked all the same, as postMessage wants.)
            "downloads" -> downloadBridge?.onMessage(msg) { json ->
                if (reply === proxy && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) proxy.postMessage(json)
            }
            else -> media.onPageMessage(msg)
        }
    }

    private fun safeName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().take(120).ifBlank { "plexamp-web" }

    private companion object {
        const val OBJECT_NAME = "PlexampAndroid"
        const val SCRIPT_ASSET = "page-bridge.js"
        const val MAX_MESSAGE = 40 shl 20
    }
}
