package app.shell

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.io.File

/**
 * The cage every mini-app runs in, including a pushed home screen.
 *
 * Each app is served from its own made-up origin, https://<folder>.homebaked.internal/, by
 * intercepting requests and reading files from its folder. Nothing about that host ever
 * touches the network. The origin is what keeps apps apart: localStorage, IndexedDB and
 * cookies are per origin, and no app can read another's files.
 *
 * Offline unless declared. A request to any other host is refused unless the app's manifest
 * lists it under "network". Three layers, because each misses something:
 *   - shouldInterceptRequest: fetch, XHR, images, scripts, styles, fonts, media
 *   - a Content-Security-Policy header on every page: also covers WebSockets and beacons
 *   - a document-start script removing RTCPeerConnection: WebRTC goes around both
 * This guards against sloppy or tracking code (a CDN font, an analytics snippet Claude added
 * out of habit), not a determined attacker; it is your own app.
 *
 * [extra] lets the host serve a few more paths on the app's own origin (the home screen uses
 * it for other apps' icons, and nothing else of theirs).
 */
class Sandbox(
    private val activity: Activity,
    private val info: () -> AppInfo?,
    private val warnOnce: (String, String) -> Unit,
    private val extra: ((path: String) -> WebResourceResponse?)? = null
) {

    @SuppressLint("SetJavaScriptEnabled")
    fun install(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false)
        }
        webView.webViewClient = Client()
        webView.webChromeClient = object : WebChromeClient() {
            // Camera and microphone go through the bridge, where the shell can ask properly.
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, NO_WEBRTC, setOf("*"))
        }
        // A service worker's own requests bypass the WebViewClient; refuse them all.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)
        ) {
            ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(
                object : ServiceWorkerClientCompat() {
                    override fun shouldInterceptRequest(request: WebResourceRequest) = refused()
                }
            )
        }
    }

    private inner class Client : WebViewClient() {

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val app = info() ?: return refused()
            val url = request.url
            val scheme = url.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null   // data:, blob:, about:
            val host = url.host?.lowercase().orEmpty()
            return when {
                host == app.host -> extra?.invoke(url.path.orEmpty()) ?: serveLocal(app, url)
                host == Brand.ORIGIN_SUFFIX || host.endsWith(".${Brand.ORIGIN_SUFFIX}") -> refused()
                scheme == "https" && app.allowsHost(host) -> null    // declared: real network
                else -> {
                    warnOnce(host, "${app.name} tried to reach $host. Blocked: it isn't in the app's network list.")
                    refused()
                }
            }
        }

        /**
         * Navigating away from the app. A link the person tapped can open in the browser after
         * a confirmation; anything the page does on its own is dropped, so an app cannot
         * smuggle data out in a URL behind your back.
         */
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val app = info() ?: return true
            if (request.url.host?.lowercase() == app.host) return false
            if (request.isForMainFrame && request.hasGesture()) confirmExternal(request.url)
            return true
        }
    }

    private fun confirmExternal(url: Uri) {
        val where = url.host ?: url.scheme ?: "another app"
        AlertDialog.Builder(activity)
            .setTitle("Leave the app?")
            .setMessage("Open $where outside ${Brand.NAME}?\n\n$url")
            .setPositiveButton("Open") { _, _ ->
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, url)) }
            }
            .setNegativeButton("Stay", null)
            .show()
    }

    private fun serveLocal(app: AppInfo, url: Uri): WebResourceResponse {
        var path = url.path.orEmpty().ifEmpty { "/" }
        if (path.endsWith("/")) path += "index.html"
        val file = File(app.dir, path.trimStart('/'))
        val inside = runCatching { Installer.guard(app.dir, file); true }.getOrDefault(false)
        if (!inside || !file.isFile) return notFound()
        val headers = HashMap<String, String>()
        if (file.extension.lowercase() in setOf("html", "htm")) headers["Content-Security-Policy"] = csp(app)
        return serveFile(file, headers)
    }

    /**
     * Declared hosts are allowed for data (fetch, images, media, fonts, styles) but never
     * scripts: an app's code is the code you pushed, nothing loaded from elsewhere.
     */
    private fun csp(app: AppInfo): String {
        val hosts = app.network.joinToString(" ") { "https://$it" }
        return listOf(
            "default-src 'self' data: blob:",
            "script-src 'self' 'unsafe-inline' 'unsafe-eval' data: blob:",
            "style-src 'self' 'unsafe-inline' data: $hosts",
            "img-src 'self' data: blob: $hosts",
            "media-src 'self' data: blob: $hosts",
            "font-src 'self' data: $hosts",
            "connect-src 'self' data: blob: $hosts",
            "frame-src 'self' data: blob:",
            "worker-src 'self' blob:",
            "form-action 'self'",
            "base-uri 'self'"
        ).joinToString("; ") { it.trim() }
    }

    companion object {
        private val MIME = mapOf(
            "html" to "text/html", "htm" to "text/html",
            "js" to "text/javascript", "mjs" to "text/javascript",
            "css" to "text/css", "json" to "application/json", "txt" to "text/plain",
            "svg" to "image/svg+xml", "png" to "image/png", "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg", "gif" to "image/gif", "webp" to "image/webp",
            "ico" to "image/x-icon",
            "mp3" to "audio/mpeg", "wav" to "audio/wav", "ogg" to "audio/ogg",
            "m4a" to "audio/mp4", "mp4" to "video/mp4", "webm" to "video/webm",
            "woff" to "font/woff", "woff2" to "font/woff2", "ttf" to "font/ttf", "otf" to "font/otf",
            "wasm" to "application/wasm"
        )

        /** Runs before any page script, in every frame. */
        private const val NO_WEBRTC = """
(function () {
  ['RTCPeerConnection', 'webkitRTCPeerConnection', 'RTCDataChannel',
   'RTCSessionDescription', 'RTCIceCandidate'].forEach(function (k) {
    try { Object.defineProperty(window, k, { value: undefined, writable: false, configurable: false }); }
    catch (e) {}
  });
})();
"""

        fun serveFile(file: File, headers: Map<String, String> = emptyMap()): WebResourceResponse {
            val mime = MIME[file.extension.lowercase()] ?: "application/octet-stream"
            val all = HashMap(headers)
            // Pushes replace files in place; never show a stale copy after a reload.
            all["Cache-Control"] = "no-cache"
            val encoding = if (mime.startsWith("text/") || mime.endsWith("javascript") || mime.endsWith("json")) "utf-8" else null
            return WebResourceResponse(mime, encoding, 200, "OK", all, file.inputStream())
        }

        fun notFound() = WebResourceResponse(
            "text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0))
        )

        fun refused() = WebResourceResponse(
            "text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0))
        )
    }
}
