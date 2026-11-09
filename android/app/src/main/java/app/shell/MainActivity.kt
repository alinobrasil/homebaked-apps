package app.shell

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebView
import android.widget.Toast
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import kotlin.math.absoluteValue
import androidx.compose.foundation.lazy.items as listItems

private class Tile(val info: AppInfo, val icon: ImageBitmap?) {
    val section: String = when (info.category.trim().lowercase()) {
        "utility", "utilities", "tool", "tools" -> "Utilities"
        "", "game", "games" -> "Games"
        else -> info.category.trim().replaceFirstChar { it.uppercase() }
    }
}

/** How the info sheet names each ability an app can declare. */
private val ABILITY_NAMES = mapOf(
    "photos" to "camera and photos", "files" to "saving, opening and sharing files",
    "notifications" to "reminders", "location" to "your location (while open)",
    "overlay" to "dimming the screen"
)

/** Fallback tile colours, picked by name so an app keeps the same one. */
private val TILE_COLORS = listOf(
    0xFF4C6EF5, 0xFF37B24D, 0xFFE8590C, 0xFF9C36B5, 0xFF1098AD, 0xFFF08C00
)

class MainActivity : ComponentActivity() {

    private var tiles by mutableStateOf<List<Tile>>(emptyList())
    private var note by mutableStateOf<String?>(null)
    private var offer by mutableStateOf<Linker.Offer?>(null)
    private var details by mutableStateOf<AppInfo?>(null)
    private var showLog by mutableStateOf(false)
    private var showStatus by mutableStateOf(false)

    /** A pushed home screen (the "home" folder), unless the person switched back to the built-in one. */
    private var customHome by mutableStateOf(false)
    private var homeView: WebView? = null
    @Volatile private var homeInfo: AppInfo? = null
    private val warned = HashSet<String>()

    private val onChanged: (String) -> Unit = { name ->
        runOnUiThread {
            tiles = scan()
            val there = File(PushHub.appsRoot(this), name).exists()
            note = when {
                name == Home.FOLDER && there -> "Home screen updated"
                name == Home.FOLDER -> "Custom home screen removed"
                there -> "Installed $name"
                else -> "Removed $name"
            }
            if (name == Home.FOLDER) refreshHome(reload = true)
            else homeView?.evaluateJavascript("window.onAppsChanged && window.onAppsChanged()", null)
        }
    }

    private val scanner = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let(::receiveLink)
    }

    private fun startScan() {
        scanner.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Point at the QR code Claude is showing")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
        )
    }

    /** From the in-app scanner, or a QR the phone's own camera app opened. */
    private fun receiveLink(raw: String) {
        try {
            offer = Linker.parse(raw)
        } catch (e: Linker.BadLink) {
            note = e.message
        }
    }

    private fun confirmLink(o: Linker.Offer) {
        offer = null
        val linked = try {
            Linker.accept(o)
        } catch (e: Linker.BadLink) {
            note = e.message
            return
        }
        PushHub.link(linked)
        note = "Linking ${o.name}…"
        val port = PushHub.port
        Thread {
            val reached = Linker.announce(linked, port)
            runOnUiThread {
                note = if (reached != null) {
                    "Linked to ${o.name}. Claude can now put apps on this phone."
                } else {
                    "Linked to ${o.name}. Its computer didn't answer yet; keep ${Brand.NAME} open " +
                        "on the same Wi-Fi and it will find this phone."
                }
            }
        }.start()
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == Brand.SCHEME) receiveLink(data.toString())
        intent.data = null
    }

    /* ---------- apps ---------- */

    /**
     * Decoded once per scan rather than per frame. Downsampled on the way in so a pushed
     * bundle with an oversized icon cannot blow up memory.
     */
    private fun loadIcon(dir: File): ImageBitmap? {
        val file = listOf("icon.png", "icon.webp", "icon.jpg", "icon.jpeg")
            .map { File(dir, it) }
            .firstOrNull { it.exists() } ?: return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = if (longest > 320) longest / 320 else 1
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)?.asImageBitmap()
        }.getOrNull()
    }

    private fun scan(): List<Tile> =
        PushHub.installedApps().filter { it.folder != Home.FOLDER }.map { Tile(it, loadIcon(it.dir)) }

    private fun open(app: AppInfo) {
        startActivity(Intent(this, WebAppActivity::class.java).putExtra("folder", app.folder))
    }

    /* ---------- pushed home screen ---------- */

    private fun prefs() = getSharedPreferences("shell", MODE_PRIVATE)

    private fun builtInChosen() = prefs().getBoolean("home.builtin", false)

    private fun warnOnce(key: String, msg: String) {
        synchronized(warned) { if (!warned.add(key)) return }
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    /**
     * Show the pushed home screen if there is one and it wasn't switched off; otherwise drop
     * its WebView and fall back to the built-in grid.
     */
    private fun refreshHome(reload: Boolean = false) {
        val root = PushHub.appsRoot(this)
        val dir = Home.dir(root)
        val wanted = File(dir, "index.html").exists() && !builtInChosen()
        if (!wanted) {
            homeView?.destroy()
            homeView = null
            homeInfo = null
            customHome = false
            return
        }
        homeInfo = AppInfo.read(dir)
        val existing = homeView
        if (existing != null) {
            if (reload) existing.reload()
        } else {
            val wv = WebView(this)
            Sandbox(this, { homeInfo }, ::warnOnce) { path -> Home.serveIcon(root, path) }.install(wv)
            wv.addJavascriptInterface(HomeBridge(
                apps = { Home.appsJson(PushHub.installedApps().filter { it.folder != Home.FOLDER }) },
                open = { id -> runOnUiThread { appById(id)?.let(::open) } },
                details = { id -> runOnUiThread { details = appById(id) } },
                toast = { msg -> runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }
            ), "HomeBridge")
            wv.loadUrl("https://${homeInfo!!.host}/index.html")
            homeView = wv
        }
        customHome = true
    }

    private fun appById(id: String): AppInfo? =
        PushHub.installedApps().firstOrNull { it.folder == Installer.sanitize(id) && it.folder != Home.FOLDER }

    private fun setBuiltIn(builtIn: Boolean) {
        prefs().edit().putBoolean("home.builtin", builtIn).apply()
        refreshHome(reload = true)
    }

    /** Wi-Fi (and Tailscale, 100.64.0.0/10) addresses, shown for troubleshooting. */
    private fun addresses(): List<String> = try {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback }
            .flatMap { Collections.list(it.inetAddresses) }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .map { it.hostAddress.orEmpty() }
    } catch (e: Exception) {
        emptyList()
    }

    /* ---------- lifecycle ---------- */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PushHub.init(this)
        tiles = scan()
        refreshHome()
        handleIntent(intent)
        setContent { MaterialTheme { Launcher() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        tiles = scan()
        PushHub.addChangeListener(onChanged)
        PushHub.acquire(this)
        PushHub.startError?.let { if (!PushHub.serving.value) note = it }
    }

    override fun onStop() {
        super.onStop()
        PushHub.removeChangeListener(onChanged)
        PushHub.release()
    }

    override fun onDestroy() {
        super.onDestroy()
        homeView?.destroy()
        homeView = null
    }

    /* ---------- ui ---------- */

    @Composable
    fun Launcher() {
        val computers by PushHub.computers
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            // The native bar stays even under a pushed home screen: linking, status and the way
            // back to the built-in home screen are always here, out of any app's reach.
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(Brand.NAME, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (computers.isNotEmpty()) StatusChip()
            }
            note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                     modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            }

            val home = homeView
            if (computers.isEmpty()) {
                Welcome(Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 8.dp))
            } else if (customHome && home != null) {
                AndroidView(factory = { home }, modifier = Modifier.weight(1f).fillMaxWidth())
            } else Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                if (tiles.isEmpty()) {
                    Spacer(Modifier.height(24.dp))
                    Text("No apps yet.", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Ask Claude on your computer to make one, for example: " +
                            "\"Make me a tip calculator and put it on my phone.\" " +
                            "Keep this screen open while it does.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    AppGrid(Modifier.weight(1f))
                }
            }
        }
        if (showStatus) StatusDialog()

        offer?.let { o ->
            AlertDialog(
                onDismissRequest = { offer = null },
                title = { Text("Link \"${o.name}\"?") },
                text = {
                    Text(
                        "Claude on that computer will be able to add, update and remove apps " +
                            "on this phone without asking each time.\n\n" +
                            "Only link a computer you own. You can unlink it any time."
                    )
                },
                confirmButton = { Button(onClick = { confirmLink(o) }) { Text("Link") } },
                dismissButton = { TextButton(onClick = { offer = null }) { Text("Cancel") } }
            )
        }
        details?.let { DetailsDialog(it) }
        if (showLog) LogDialog(onClose = { showLog = false })
    }

    /** First run: nothing linked yet. Three steps and one big button. */
    @Composable
    fun Welcome(modifier: Modifier) {
        Column(modifier.verticalScroll(rememberScrollState())) {
            Text(
                "Your own apps, made by you with Claude. No ads, no tracking, " +
                    "and offline unless an app really needs the internet.",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(20.dp))
            Step("1", "On your computer, open the Claude desktop app and go to the Code tab.")
            Step("2", "Ask: \"Link my phone to ${Brand.NAME}.\" Claude shows a QR code.")
            Step("3", "Make sure this phone is on the same Wi-Fi, then tap Scan below.")
            Spacer(Modifier.height(20.dp))
            Button(onClick = ::startScan, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Scan QR code", fontSize = 17.sp)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "First time? The setup guide explains how to add the ${Brand.NAME} skill to Claude.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    @Composable
    fun Step(n: String, text: String) {
        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) { Text(n, color = Color.White, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 2.dp))
        }
    }

    /** Top-right: whether pushes can land. Tap for the details. */
    @Composable
    fun StatusChip() {
        val serving by PushHub.serving
        val ready = serving && remember(serving) { addresses() }.isNotEmpty()
        Row(
            Modifier.clip(RoundedCornerShape(16.dp)).clickable { showStatus = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(9.dp).clip(CircleShape)
                    .background(if (ready) Color(0xFF37B24D) else Color(0xFFADB5BD)))
            Spacer(Modifier.width(6.dp))
            Text(when { ready -> "Ready"; !serving -> "Paused"; else -> "No Wi-Fi" },
                 style = MaterialTheme.typography.labelLarge)
            Text("  ⋮", style = MaterialTheme.typography.labelLarge)
        }
    }

    /** Whether pushes can land, which computers can send them, and which home screen to use. */
    @Composable
    fun StatusDialog() {
        val serving by PushHub.serving
        val computers by PushHub.computers
        val ips = remember(serving) { addresses() }
        val hasCustomHome = File(Home.dir(PushHub.appsRoot(this)), "index.html").exists()
        AlertDialog(
            onDismissRequest = { showStatus = false },
            title = { Text(if (serving && ips.isNotEmpty()) "Ready for apps from Claude" else if (!serving) "Paused" else "Not on Wi-Fi") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        if (ips.isEmpty()) "Join Wi-Fi, then reopen this screen."
                        else "This phone: " + ips.joinToString(", ") { "$it:${PushHub.port}" },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text("Apps arrive while ${Brand.NAME} is open, on the same Wi-Fi as your computer.",
                         style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    Text("Linked computers", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    computers.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                PushHub.unlink(c.id)
                                note = "Unlinked ${c.name}. It can no longer send apps."
                            }) { Text("Unlink") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { showStatus = false; startScan() }) { Text("Link another") }
                        TextButton(onClick = { PushHub.setPaused(serving) }) { Text(if (serving) "Pause" else "Resume") }
                        TextButton(onClick = { showStatus = false; showLog = true }) { Text("History") }
                    }
                    if (hasCustomHome) {
                        Spacer(Modifier.height(8.dp))
                        Text("Home screen", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        Text(if (builtInChosen()) "Using the built-in one. Claude sent a custom one."
                             else "Using the custom one Claude sent.",
                             style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { setBuiltIn(!builtInChosen()); showStatus = false }) {
                            Text(if (builtInChosen()) "Use the custom home screen" else "Use the built-in home screen")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showStatus = false }) { Text("Close") } }
        )
    }

    /** Sections in a fixed order, with anything unexpected appended alphabetically. */
    @Composable
    fun AppGrid(modifier: Modifier = Modifier) {
        val grouped = remember(tiles) { tiles.groupBy { it.section } }
        val preferred = listOf("Games", "Utilities")
        val sections = remember(grouped) {
            preferred.filter { grouped.containsKey(it) } +
                grouped.keys.filterNot { preferred.contains(it) }.sorted()
        }
        LazyVerticalGrid(
            modifier = modifier,
            columns = GridCells.Adaptive(minSize = 64.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
        ) {
            sections.forEach { section ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        section.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 6.dp, bottom = 0.dp)
                    )
                }
                items(grouped[section].orEmpty()) { t -> AppTile(t) }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun AppTile(t: Tile) {
        val app = t.info
        Column(
            modifier = Modifier.combinedClickable(
                onClick = { open(app) },
                onLongClick = { details = app }
            ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                Box(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp))
                        .background(app.color?.let { Color(it) } ?: fallbackColor(app.name)),
                    contentAlignment = Alignment.Center
                ) {
                    val icon = t.icon
                    if (icon != null) {
                        Image(icon, app.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else if (app.emoji.isNotEmpty()) {
                        Text(app.emoji, fontSize = 28.sp)
                    } else {
                        Text(app.name.take(1).uppercase(), fontSize = 22.sp,
                             fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
                // Online apps are the exception, so they are the ones that get marked.
                if (!app.offline) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(2.dp).size(17.dp)
                            .clip(CircleShape).background(Color.White),
                        contentAlignment = Alignment.Center
                    ) { Text("🌐", fontSize = 9.sp) }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(app.name, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                 overflow = TextOverflow.Ellipsis)
        }
    }

    private fun fallbackColor(name: String): Color =
        Color(TILE_COLORS[name.hashCode().absoluteValue % TILE_COLORS.size])

    /** Long-press on a tile: what this app can do, and a way to remove it. */
    @Composable
    fun DetailsDialog(app: AppInfo) {
        var confirmDelete by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { details = null },
            title = { Text(app.name) },
            text = {
                Column {
                    if (app.offline) {
                        Text("🔒 Offline. This app can't use the internet.")
                    } else {
                        Text("🌐 Can connect to:")
                        app.network.forEach { Text("   $it") }
                    }
                    if (app.permissions.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Can ask to use: " + app.permissions.joinToString(", ") { ABILITY_NAMES[it] ?: it })
                    }
                    Spacer(Modifier.height(8.dp))
                    if (app.shared.isEmpty()) {
                        Text("Its saved data is private to it.")
                    } else {
                        Text("Shares data with other apps through: " + app.shared.joinToString(", "))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Folder: ${app.folder}" + if (app.version.isNotEmpty()) " · version ${app.version}" else "",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = { TextButton(onClick = { details = null }) { Text("Close") } },
            dismissButton = {
                TextButton(onClick = {
                    // Two taps, so a stray one cannot delete an app and its saved data.
                    if (confirmDelete) {
                        details = null
                        Thread { PushHub.delete(app.folder, "this phone") }.start()
                    } else {
                        confirmDelete = true
                    }
                }) {
                    Text(if (confirmDelete) "Tap again: delete app and its data" else "Delete",
                         color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }

    /** Every install and delete, newest first, including failed ones. */
    @Composable
    fun LogDialog(onClose: () -> Unit) {
        val rev by PushLog.revision
        val entries = remember(rev) { PushLog.read(this) }
        var confirmClear by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("History") },
            text = {
                if (entries.isEmpty()) {
                    Text("Nothing yet. Every app Claude sends to this phone shows up here.")
                } else {
                    LazyColumn(Modifier.heightIn(max = 460.dp)) {
                        listItems(entries) { e -> LogRow(e) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
            dismissButton = {
                if (entries.isNotEmpty()) {
                    TextButton(onClick = {
                        if (confirmClear) PushLog.clear(this@MainActivity) else confirmClear = true
                    }) {
                        Text(if (confirmClear) "Tap again to clear" else "Clear",
                             color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        )
    }

    @Composable
    fun LogRow(e: PushLog.Entry) {
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (e.name == e.id) e.id else "${e.name} (${e.id})",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    DateUtils.formatDateTime(
                        this@MainActivity, e.time,
                        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = muted
                )
            }
            Text(
                "${e.action} · ${e.source}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = if (e.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            if (e.detail.isNotEmpty()) Text(e.detail, style = MaterialTheme.typography.bodySmall, color = muted)
        }
        HorizontalDivider()
    }
}
