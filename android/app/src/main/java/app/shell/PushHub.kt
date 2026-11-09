package app.shell

import android.content.Context
import android.content.SharedPreferences
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.WebStorage
import androidx.compose.runtime.mutableStateOf
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Owns the push server, the linked computers, and the LAN advertisement.
 *
 * The launcher and every open mini-app hold a reference while they are started, so the
 * server listens while either is in front and never in the background. Activity
 * transitions start the next screen before stopping the last, so the count never dips to
 * zero on the way between them.
 *
 * A linked computer installs with no tap: linking itself (scan + Link) was the consent.
 */
object PushHub {

    data class Computer(val id: String, val name: String, val key: ByteArray, val added: Long)

    /** Compose state for the launcher. Main thread only. */
    val serving = mutableStateOf(false)
    val computers = mutableStateOf<List<Computer>>(emptyList())
    var port = 0
        private set
    var startError: String? = null
        private set

    private lateinit var app: Context
    private var server: UploadServer? = null
    private var refs = 0
    private var pausedByUser = false
    private var advert: NsdManager.RegistrationListener? = null
    private val changed = CopyOnWriteArraySet<(String) -> Unit>()
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())

    private fun prefs(): SharedPreferences = app.getSharedPreferences("shell", Context.MODE_PRIVATE)

    fun appsRoot(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), "apps").apply { mkdirs() }

    /* ---------- lifetime ---------- */

    fun init(ctx: Context) {
        if (!::app.isInitialized) app = ctx.applicationContext
    }

    fun acquire(ctx: Context) {
        init(ctx)
        refs++
        computers.value = loadComputers()
        if (!pausedByUser) start()
    }

    fun release() {
        refs = (refs - 1).coerceAtLeast(0)
        if (refs == 0) stop()
    }

    /** The Pause/Resume button on the status card. Sticks until the next tap. */
    fun setPaused(paused: Boolean) {
        pausedByUser = paused
        if (paused) stop() else if (refs > 0) start()
    }

    private fun start() {
        if (server != null) return
        // The usual port first; if something else on the phone holds it, any free port.
        // The computer learns the real one from the link callback or the LAN advertisement.
        for (p in listOf(Brand.DEFAULT_PORT, 0)) {
            val s = UploadServer(p, hooks, File(app.filesDir, "replay.txt"))
            try {
                s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
                server = s
                port = s.listeningPort
                serving.value = true
                startError = null
                advertise()
                return
            } catch (e: Exception) {
                startError = "Could not start: ${e.message}"
            }
        }
        serving.value = false
    }

    private fun stop() {
        unadvertise()
        server?.stop()
        server = null
        serving.value = false
    }

    fun addChangeListener(l: (String) -> Unit) = changed.add(l)
    fun removeChangeListener(l: (String) -> Unit) = changed.remove(l)

    /* ---------- this phone ---------- */

    /** Random, stable per install. Lets a computer tell two phones apart on the LAN. */
    fun phoneId(): String =
        prefs().getString("phone.id", null)
            ?: ByteArray(4).also(SecureRandom()::nextBytes).hex().also {
                prefs().edit().putString("phone.id", it).apply()
            }

    fun phoneName(): String =
        "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
            .replace(Regex("[^A-Za-z0-9._ -]"), "")
            .trim()
            .take(40)

    /**
     * mDNS/DNS-SD, so a linked computer finds the phone again after its IP address changes.
     * The phone's id rides in a TXT record; the computer still proves who it is on /hello.
     */
    private fun advertise() {
        if (advert != null) return
        val nsd = app.getSystemService(NsdManager::class.java) ?: return
        val info = NsdServiceInfo().apply {
            serviceName = "${Brand.NAME} ${phoneId()}"
            serviceType = Brand.NSD_TYPE
            port = this@PushHub.port
            setAttribute("id", phoneId())
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { advert = null }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        advert = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { advert = null }
    }

    private fun unadvertise() {
        val l = advert ?: return
        advert = null
        runCatching { app.getSystemService(NsdManager::class.java)?.unregisterService(l) }
    }

    /* ---------- linked computers ---------- */

    private fun loadComputers(): List<Computer> =
        prefs().all.mapNotNull { (k, v) ->
            if (!k.startsWith("dev.") || v !is String) return@mapNotNull null
            val parts = v.split('|')
            if (parts.size != 3) return@mapNotNull null
            runCatching {
                Computer(k.removePrefix("dev."), parts[0], hexToBytes(parts[1]), parts[2].toLong())
            }.getOrNull()
        }.sortedBy { it.added }

    /**
     * Called after the person taps Link on a scanned offer. Keeps the agreed key, and the
     * phone's public half so a computer whose callback was blocked can still finish (/link).
     */
    fun link(linked: Linker.Linked) {
        val o = linked.offer
        prefs().edit()
            .putString("dev.${o.id}", "${o.name}|${linked.key.hex()}|${System.currentTimeMillis()}")
            .putString("pub.${o.id}", linked.phonePublic.hex())
            .commit()
        computers.value = loadComputers()
    }

    fun unlink(id: String) {
        prefs().edit().remove("dev.$id").remove("pub.$id").apply()
        computers.value = loadComputers()
    }

    private fun computerName(id: String): String =
        loadComputers().firstOrNull { it.id == id }?.name ?: "linked computer"

    /* ---------- apps ---------- */

    fun installedApps(): List<AppInfo> =
        appsRoot(app).listFiles { f -> f.isDirectory && File(f, "index.html").exists() }
            ?.map { AppInfo.read(it) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    /** From the server thread (a computer) or the launcher (this phone). */
    fun delete(name: String, source: String): UploadServer.Outcome = synchronized(lock) {
        val root = appsRoot(app)
        val label = PushLog.displayName(root, name)
        if (!Installer.remove(root, name)) {
            return UploadServer.Outcome(false, "no app called $name")
        }
        // Its saved data and reminders go with it. Shared buckets stay: other apps may use them.
        Reminders.cancelAll(app, name)
        val origin = "https://$name.${Brand.ORIGIN_SUFFIX}"
        main.post { runCatching { WebStorage.getInstance().deleteOrigin(origin) } }
        PushLog.add(app, PushLog.Entry(System.currentTimeMillis(), name, label, "Deleted", source, "", true))
        changed.forEach { it(name) }
        UploadServer.Outcome(true, "deleted $name")
    }

    private val hooks = object : UploadServer.Hooks {

        override fun deviceKey(id: String): ByteArray? =
            loadComputers().firstOrNull { it.id == id }?.key

        override fun hello(): String = JSONObject()
            .put("app", Brand.NAME)
            .put("version", BuildConfig.VERSION_NAME)
            .put("phone", phoneName())
            .put("phoneId", phoneId())
            .put("port", port)
            .toString()

        override fun linkInfo(id: String): Pair<ByteArray, ByteArray>? {
            val key = loadComputers().firstOrNull { it.id == id }?.key ?: return null
            val pub = prefs().getString("pub.$id", null)?.let(::hexToBytes) ?: return null
            return Linker.linkInfo(pub, port) to key
        }

        override fun listApps(): String =
            JSONArray(installedApps().map { it.toJson() }).toString()

        override fun delete(name: String, deviceId: String) =
            this@PushHub.delete(name, computerName(deviceId))

        override fun upload(
            name: String,
            files: List<UploadServer.Upload>,
            deviceId: String
        ): UploadServer.Outcome = synchronized(lock) {
            val root = appsRoot(app)
            val existed = PushLog.isInstalled(root, name)
            val bytes = files.sumOf { it.tmp.length() }
            val zip = files.any { it.fileName.endsWith(".zip", ignoreCase = true) }
            val source = computerName(deviceId)
            fun log(action: String, detail: String, ok: Boolean) = PushLog.add(
                app, PushLog.Entry(System.currentTimeMillis(), name, PushLog.displayName(root, name),
                                   action, source, detail, ok)
            )
            try {
                val dir = try {
                    Installer.install(root, name, files)
                } catch (e: Exception) {
                    log("Failed", e.message ?: e.javaClass.simpleName, false)
                    throw e
                }
                when {
                    !existed -> log("New", "whole app, ${PushLog.size(bytes)}", true)
                    zip -> log("Updated", "whole app, ${PushLog.size(bytes)}", true)
                    else -> log("File", files.joinToString(", ") { it.fileName.substringAfterLast('/') } +
                                        ", ${PushLog.size(bytes)}", true)
                }
                changed.forEach { it(dir.name) }
                UploadServer.Outcome(true, "installed ${dir.name}")
            } finally {
                files.forEach { it.tmp.delete() }
            }
        }
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
