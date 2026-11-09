package app.shell

import android.webkit.JavascriptInterface
import android.webkit.WebResourceResponse
import org.json.JSONArray
import java.io.File

/**
 * A home screen you push like any app: the folder named "home".
 *
 * When it exists, the launcher shows it (in a [Sandbox], under a thin native bar that keeps
 * linking, status and the switch back to the built-in home screen out of its reach) instead
 * of the built-in grid. It gets `window.HomeBridge`:
 *
 *   HomeBridge.apps()       JSON array of the other apps:
 *                           [{id, name, category, version, emoji, color, icon, online, network, shared}]
 *                           icon is a same-origin URL ("/_apps/<id>/icon") or "" when there is none
 *   HomeBridge.open(id)     open an app
 *   HomeBridge.details(id)  the native info sheet (what it can reach, delete)
 *   HomeBridge.toast(msg)
 *   window.onAppsChanged()  called when an app is added, updated or removed
 *
 * It can see the list of apps and their icons, nothing else of theirs: not their files, not
 * their saved data.
 */
object Home {
    const val FOLDER = "home"

    fun dir(appsRoot: File) = File(appsRoot, FOLDER)

    fun iconFile(appDir: File): File? =
        listOf("icon.png", "icon.webp", "icon.jpg", "icon.jpeg").map { File(appDir, it) }.firstOrNull { it.isFile }

    fun appsJson(apps: List<AppInfo>): String = JSONArray(apps.map { a ->
        a.toJson()
            .put("emoji", a.emoji)
            .put("color", a.color?.let { "#%06X".format(it and 0xFFFFFF) } ?: "")
            .put("icon", if (iconFile(a.dir) != null) "/_apps/${a.folder}/icon" else "")
            .put("online", !a.offline)
    }).toString()

    /** Serves /_apps/<id>/icon on the home origin; null for any other path. */
    fun serveIcon(appsRoot: File, path: String): WebResourceResponse? {
        if (!path.startsWith("/_apps/")) return null
        val parts = path.removePrefix("/_apps/").split('/')
        if (parts.size != 2 || parts[1] != "icon") return Sandbox.notFound()
        val id = Installer.sanitize(parts[0])
        if (id.isEmpty() || id == FOLDER) return Sandbox.notFound()
        val appDir = File(appsRoot, id)
        val icon = runCatching { Installer.guard(appsRoot, appDir); iconFile(appDir) }.getOrNull()
            ?: return Sandbox.notFound()
        return Sandbox.serveFile(icon)
    }
}

class HomeBridge(
    private val apps: () -> String,
    private val open: (String) -> Unit,
    private val details: (String) -> Unit,
    private val toast: (String) -> Unit
) {
    @JavascriptInterface fun apps(): String = apps.invoke()
    @JavascriptInterface fun open(id: String) = open.invoke(id)
    @JavascriptInterface fun details(id: String) = details.invoke(id)
    @JavascriptInterface fun toast(msg: String) = toast.invoke(msg)
}
