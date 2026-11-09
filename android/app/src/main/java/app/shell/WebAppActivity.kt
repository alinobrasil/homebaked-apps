package app.shell

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File

/**
 * Runs one mini-app full screen, inside a [Sandbox] (its own origin, offline unless
 * declared), with the AndroidBridge for speech and shared buckets.
 */
class WebAppActivity : ComponentActivity() {

    private lateinit var speaker: Speaker
    private lateinit var ears: Ears
    private lateinit var dir: File
    private var webView: WebView? = null
    private var pendingListenLang: String? = null

    /** Re-read on every push, so a changed network list or bucket list applies on reload. */
    @Volatile private var info: AppInfo? = null

    private val warned = HashSet<String>()

    // Registered here, before the activity starts, as Android requires for activity results.
    private val abilities = Abilities(this, { info }, { cb, json -> toPageCallback(cb, json) }, ::warnOnce)

    /** Hand an ability's result to the page: window.<callback>(jsonString). */
    private fun toPageCallback(callback: String, json: String) {
        runOnUiThread {
            webView?.evaluateJavascript(
                "window.$callback && window.$callback(" + org.json.JSONObject.quote(json) + ")", null
            )
        }
    }

    // A push to the app on screen reloads it, so an edit shows up without backing out to
    // the launcher. Saved data survives the reload. A delete closes it.
    private val onChanged: (String) -> Unit = { name ->
        if (name == info?.folder) runOnUiThread {
            if (File(dir, "index.html").exists()) {
                info = AppInfo.read(dir)
                webView?.reload()
            } else {
                finish()
            }
        }
    }

    // Asked the first time a mini-app wants to listen, not on launch, so an app that never
    // uses the microphone never raises the question.
    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val lang = pendingListenLang
            pendingListenLang = null
            if (granted && lang != null) ears.listen(lang)
            else toPage("{\"ok\":false,\"error\":\"permission\"}")
        }

    /** Hand a speech result to the page. */
    private fun toPage(json: String) {
        runOnUiThread {
            webView?.evaluateJavascript(
                "window.onSpeechResult && window.onSpeechResult(" + org.json.JSONObject.quote(json) + ")",
                null
            )
        }
    }

    private fun requestListen(lang: String) {
        if (ears.hasPermission()) { ears.listen(lang); return }
        pendingListenLang = lang
        micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    /** Once per thing per session, so a blocked font in a loop does not flood the screen. */
    private fun warnOnce(key: String, msg: String) {
        synchronized(warned) { if (!warned.add(key)) return }
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val folder = intent.getStringExtra("folder") ?: run { finish(); return }
        dir = File(PushHub.appsRoot(this), folder)
        if (!File(dir, "index.html").exists()) { finish(); return }
        info = AppInfo.read(dir)

        // Started here rather than on first use: the engine takes a moment to come up, and
        // an app that speaks on its opening screen would otherwise lose that first line.
        speaker = Speaker(this)
        ears = Ears(this) { json -> toPage(json) }

        val webView = WebView(this)
        this.webView = webView
        Sandbox(this, { info }, ::warnOnce).install(webView)

        webView.addJavascriptInterface(
            NativeBridge(this, speaker, ears, { lang -> requestListen(lang) }, { info }, ::warnOnce, abilities),
            "AndroidBridge"
        )
        webView.loadUrl("https://${info!!.host}/index.html")

        // targetSdk 35 forces edge to edge on Android 15+, so the window draws behind the
        // status bar and the gesture bar. Insets are consumed here so pages do not have to
        // deal with env(safe-area-inset-*) themselves and can just use 100dvh.
        val frame = FrameLayout(this)
        frame.addView(webView)
        setContentView(frame)
        ViewCompat.setOnApplyWindowInsetsListener(frame) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    // Leaving the mini-app cuts speech off rather than letting a word carry on over the
    // launcher. A linked computer can push while a mini-app is open.
    override fun onStart() {
        super.onStart()
        PushHub.addChangeListener(onChanged)
        PushHub.acquire(this)
    }

    override fun onStop() {
        super.onStop()
        PushHub.removeChangeListener(onChanged)
        PushHub.release()
        if (::speaker.isInitialized) speaker.stop()
        if (::ears.isInitialized) ears.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::speaker.isInitialized) speaker.shutdown()
        if (::ears.isInitialized) ears.shutdown()
        webView?.destroy()
        webView = null
    }

    class NativeBridge(
        private val activity: Activity,
        private val speaker: Speaker,
        private val ears: Ears,
        private val startListening: (String) -> Unit,
        private val app: () -> AppInfo?,
        private val warnOnce: (String, String) -> Unit,
        private val abilities: Abilities
    ) {

        @JavascriptInterface
        fun toast(msg: String) {
            activity.runOnUiThread {
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
            }
        }

        /**
         * Speak text through the phone's own engine. lang is a BCP 47 tag such as "ko-KR",
         * empty for the phone's language. rate is a multiplier, 1 is normal pace. Each call
         * interrupts the previous one.
         */
        @JavascriptInterface
        fun speak(text: String, lang: String, rate: Float) {
            activity.runOnUiThread { speaker.speak(text, lang, rate) }
        }

        @JavascriptInterface
        fun stopSpeaking() {
            activity.runOnUiThread { speaker.stop() }
        }

        /** JSON: ready, error, engine, lang, langStatus, langSupported. */
        @JavascriptInterface
        fun ttsStatus(lang: String): String = speaker.status(lang)

        /**
         * Listen for one utterance, on the phone only. The result arrives asynchronously as
         * window.onSpeechResult(jsonString), exactly once per call:
         * {"ok":true,"matches":["..."]} or {"ok":false,"error":"..."}.
         */
        @JavascriptInterface
        fun listen(lang: String) {
            activity.runOnUiThread { startListening(lang) }
        }

        @JavascriptInterface
        fun stopListening() {
            activity.runOnUiThread { ears.stop() }
        }

        /** JSON: available, onDevice, permission. */
        @JavascriptInterface
        fun asrStatus(): String = ears.status()

        /**
         * Keep the screen from turning off while this app is open (a timer, a recipe being
         * cooked). Only for as long as the app is on screen; leaving it lets the screen sleep
         * as usual. Without this the phone's screen timeout pauses the app.
         */
        @JavascriptInterface
        fun keepScreenOn(on: Boolean) {
            activity.runOnUiThread {
                if (on) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        /* ---------- shared buckets (see SharedStore) ---------- */

        private fun allowed(bucket: String): Boolean {
            val a = app() ?: return false
            if (bucket in a.shared) return true
            warnOnce("bucket:$bucket",
                     "${a.name} tried to use shared storage \"$bucket\", which its manifest doesn't list.")
            return false
        }

        @JavascriptInterface
        fun sharedGet(bucket: String, key: String): String? =
            if (allowed(bucket)) SharedStore.get(activity, bucket, key) else null

        /** false if the bucket isn't declared or the value is over 512K characters. */
        @JavascriptInterface
        fun sharedSet(bucket: String, key: String, value: String): Boolean =
            allowed(bucket) && SharedStore.set(activity, bucket, key, value)

        @JavascriptInterface
        fun sharedRemove(bucket: String, key: String) {
            if (allowed(bucket)) SharedStore.remove(activity, bucket, key)
        }

        /** JSON array of key names. */
        @JavascriptInterface
        fun sharedKeys(bucket: String): String =
            if (allowed(bucket)) SharedStore.keys(activity, bucket) else "[]"

        /* ---------- abilities an app lists under "permissions" (see Abilities) ---------- */

        /** JSON: declared abilities and what the phone currently allows. */
        @JavascriptInterface fun abilities(): String = abilities.status()

        /** "photos". Result: window.onPhoto(json). */
        @JavascriptInterface fun takePhoto() = activity.runOnUiThread { abilities.takePhoto() }
        @JavascriptInterface fun pickPhoto() = activity.runOnUiThread { abilities.pickPhoto() }

        /** "files". Results: window.onFileSaved(json), window.onFileOpened(json). */
        @JavascriptInterface fun saveFile(name: String, mime: String, content: String) =
            activity.runOnUiThread { abilities.saveFile(name, mime, content, false) }
        @JavascriptInterface fun saveFileBase64(name: String, mime: String, base64: String) =
            activity.runOnUiThread { abilities.saveFile(name, mime, base64, true) }
        @JavascriptInterface fun openFile(mimes: String) = activity.runOnUiThread { abilities.openFile(mimes) }
        @JavascriptInterface fun share(text: String, title: String) = activity.runOnUiThread { abilities.share(text, title) }
        @JavascriptInterface fun shareFile(name: String, mime: String, base64: String) =
            activity.runOnUiThread { abilities.shareFile(name, mime, base64) }

        /** "notifications". at = ms since 1970; repeatMinutes 0 for once. Same id replaces. */
        @JavascriptInterface fun notify(id: String, title: String, body: String, at: Double, repeatMinutes: Int) =
            activity.runOnUiThread { abilities.notify(id, title, body, at.toLong(), repeatMinutes) }
        @JavascriptInterface fun cancelNotification(id: String) = activity.runOnUiThread { abilities.cancelNotification(id) }
        @JavascriptInterface fun listNotifications(): String = abilities.listNotifications()

        /** "location", while the app is open. Result: window.onLocation(json). */
        @JavascriptInterface fun getLocation() = activity.runOnUiThread { abilities.getLocation() }

        /** "overlay": dims the whole screen, level 0 to 0.79. */
        @JavascriptInterface fun setDim(level: Float) = activity.runOnUiThread { abilities.setDim(level) }
        @JavascriptInterface fun stopDim() = activity.runOnUiThread { abilities.stopDim() }
    }
}
