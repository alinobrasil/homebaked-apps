package app.shell

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Phone abilities an app can switch on by listing them in its manifest:
 *
 *   "permissions": ["photos", "files", "notifications", "location", "overlay"]
 *
 * Nothing is asked at install. An app that doesn't list an ability can't use it; one that does
 * gets Android's own prompt the first time it actually uses it (and only for the abilities that
 * need one: taking a photo needs the camera, location needs location, reminders need
 * notifications; picking a photo and saving or opening files go through Android's own pickers
 * and need no permission at all).
 *
 * Results come back to the page through callbacks, once per call:
 *   window.onPhoto(json)       {ok, dataUrl, width, height} | {ok:false, error}
 *   window.onFileSaved(json)   {ok, name} | {ok:false, error}
 *   window.onFileOpened(json)  {ok, name, mime, size, text | dataUrl} | {ok:false, error}
 *   window.onLocation(json)    {ok, lat, lon, accuracy, time} | {ok:false, error}
 *
 * Reminders (notifications) are in [Reminders]; the screen overlay is [DimmerService].
 * Registered at construction, which has to happen before the activity starts.
 */
class Abilities(
    private val activity: ComponentActivity,
    private val app: () -> AppInfo?,
    private val toPage: (callback: String, json: String) -> Unit,
    private val warnOnce: (String, String) -> Unit
) {
    companion object {
        val ALL = setOf("photos", "files", "notifications", "location", "overlay")
        private const val MAX_OPEN_BYTES = 10 * 1024 * 1024
        private const val PHOTO_MAX_SIDE = 1600
    }

    /** True if the app's manifest lists the ability; otherwise tells the person once, and false. */
    fun allowed(ability: String): Boolean {
        val a = app() ?: return false
        if (ability in a.permissions) return true
        warnOnce("perm:$ability", "${a.name} tried to use $ability, which its manifest doesn't list.")
        return false
    }

    private fun fail(callback: String, error: String) =
        toPage(callback, JSONObject().put("ok", false).put("error", error).toString())

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(activity, p) == PackageManager.PERMISSION_GRANTED

    /* ---------------- photos ---------------- */

    private var photoFile: File? = null

    private val cameraPermission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) launchCamera() else fail("onPhoto", "permission")
    }

    private val takePicture = activity.registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = photoFile
        photoFile = null
        if (!ok || f == null || !f.exists()) { f?.delete(); fail("onPhoto", "cancelled"); return@registerForActivityResult }
        Thread { deliverPhoto(Uri.fromFile(f)); f.delete() }.start()
    }

    private val pickPhoto = activity.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) fail("onPhoto", "cancelled") else Thread { deliverPhoto(uri) }.start()
    }

    fun takePhoto() {
        if (!allowed("photos")) return fail("onPhoto", "not-declared")
        // Homebaked declares CAMERA (for QR scanning), and Android then requires it to be granted
        // before handing a photo request to the camera app too.
        if (granted(Manifest.permission.CAMERA)) launchCamera()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun launchCamera() {
        val dir = File(activity.cacheDir, "shared").apply { mkdirs() }
        val f = File(dir, "photo-${System.currentTimeMillis()}.jpg")
        photoFile = f
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", f)
        try { takePicture.launch(uri) } catch (e: Exception) { photoFile = null; fail("onPhoto", "no-camera") }
    }

    fun pickPhoto() {
        if (!allowed("photos")) return fail("onPhoto", "not-declared")
        try {
            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (e: Exception) { fail("onPhoto", "unavailable") }
    }

    /** Downscaled to 1600px on the long side, turned upright, as a JPEG data URL. Off the main thread. */
    private fun deliverPhoto(uri: Uri) {
        try {
            val cr = activity.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PHOTO_MAX_SIDE) sample *= 2
            var bmp = cr.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return fail("onPhoto", "unreadable")
            val rotation = cr.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
            val scale = minOf(1f, PHOTO_MAX_SIDE.toFloat() / maxOf(bmp.width, bmp.height))
            if (rotation != 0f || scale < 1f) {
                val m = Matrix().apply { postScale(scale, scale); postRotate(rotation) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            val dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            toPage("onPhoto", JSONObject().put("ok", true).put("dataUrl", dataUrl)
                .put("width", bmp.width).put("height", bmp.height).toString())
        } catch (e: Exception) {
            fail("onPhoto", "unreadable")
        }
    }

    /* ---------------- files ---------------- */

    private var pendingSave: ByteArray? = null
    private var pendingSaveName = ""

    private val createDoc = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = pendingSave
        pendingSave = null
        val uri = r.data?.data
        if (r.resultCode != Activity.RESULT_OK || uri == null || data == null) return@registerForActivityResult fail("onFileSaved", "cancelled")
        Thread {
            try {
                activity.contentResolver.openOutputStream(uri, "wt")?.use { it.write(data) }
                toPage("onFileSaved", JSONObject().put("ok", true).put("name", displayName(uri) ?: pendingSaveName).toString())
            } catch (e: Exception) { fail("onFileSaved", "write-failed") }
        }.start()
    }

    private val openDoc = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data
        if (r.resultCode != Activity.RESULT_OK || uri == null) return@registerForActivityResult fail("onFileOpened", "cancelled")
        Thread { deliverFile(uri) }.start()
    }

    /** content is text, or base64 when isBase64 (for binary files such as images). */
    fun saveFile(name: String, mime: String, content: String, isBase64: Boolean) {
        if (!allowed("files")) return fail("onFileSaved", "not-declared")
        val bytes = try {
            if (isBase64) Base64.decode(content.substringAfter("base64,"), Base64.DEFAULT) else content.toByteArray()
        } catch (e: Exception) { return fail("onFileSaved", "bad-content") }
        pendingSave = bytes
        pendingSaveName = name
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mime.ifBlank { "application/octet-stream" })
            .putExtra(Intent.EXTRA_TITLE, name.ifBlank { "file" })
        try { createDoc.launch(i) } catch (e: Exception) { pendingSave = null; fail("onFileSaved", "unavailable") }
    }

    /** mimes: comma-separated, e.g. "text/csv,application/json"; empty for any file. */
    fun openFile(mimes: String) {
        if (!allowed("files")) return fail("onFileOpened", "not-declared")
        val types = mimes.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(if (types.size == 1) types[0] else "*/*")
        if (types.size > 1) i.putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
        try { openDoc.launch(i) } catch (e: Exception) { fail("onFileOpened", "unavailable") }
    }

    private fun deliverFile(uri: Uri) {
        try {
            val cr = activity.contentResolver
            val mime = cr.getType(uri) ?: "application/octet-stream"
            val bytes = cr.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_OPEN_BYTES) return fail("onFileOpened", "too-large")
                }
                out.toByteArray()
            } ?: return fail("onFileOpened", "unreadable")
            val o = JSONObject().put("ok", true).put("name", displayName(uri) ?: "file").put("mime", mime).put("size", bytes.size)
            val textual = mime.startsWith("text/") || mime.endsWith("json") || mime.endsWith("csv") || mime.endsWith("xml")
            if (textual) o.put("text", String(bytes, Charsets.UTF_8))
            else o.put("dataUrl", "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP))
            toPage("onFileOpened", o.toString())
        } catch (e: Exception) {
            fail("onFileOpened", "unreadable")
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

    /** Android's share sheet with text (and an optional title). Nothing leaves unless the person picks an app. */
    fun share(text: String, title: String) {
        if (!allowed("files")) return
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        if (title.isNotBlank()) i.putExtra(Intent.EXTRA_SUBJECT, title)
        runCatching { activity.startActivity(Intent.createChooser(i, title.ifBlank { null })) }
    }

    /** Share a file the app made (e.g. a CSV or a picture), via the share sheet. */
    fun shareFile(name: String, mime: String, base64: String) {
        if (!allowed("files")) return
        try {
            val dir = File(activity.cacheDir, "shared").apply { mkdirs() }
            val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "file" }
            val f = File(dir, safe)
            f.writeBytes(Base64.decode(base64.substringAfter("base64,"), Base64.DEFAULT))
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", f)
            val i = Intent(Intent.ACTION_SEND).setType(mime.ifBlank { "application/octet-stream" })
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activity.startActivity(Intent.createChooser(i, null))
        } catch (e: Exception) { }
    }

    /* ---------------- reminders ---------------- */

    private var afterNotifyPermission: (() -> Unit)? = null

    private val notifyPermission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val then = afterNotifyPermission
        afterNotifyPermission = null
        if (ok) then?.invoke() else warnOnce("notify-denied", "Notifications are off for ${Brand.NAME}, so reminders won't show.")
    }

    /**
     * A notification at [at] (ms since 1970), repeating every [repeatMinutes] if above 0. A new
     * reminder with the same id replaces the old one. Asks for notifications the first time.
     */
    fun notify(id: String, title: String, body: String, at: Long, repeatMinutes: Int) {
        if (!allowed("notifications")) return
        val a = app() ?: return
        val go = { Reminders.schedule(activity, a.folder, id.take(40), title, body, at, repeatMinutes) }
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            afterNotifyPermission = go
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else go()
    }

    fun cancelNotification(id: String) {
        if (!allowed("notifications")) return
        app()?.let { Reminders.cancel(activity, it.folder, id) }
    }

    fun listNotifications(): String {
        val a = app() ?: return "[]"
        return if ("notifications" in a.permissions) Reminders.list(activity, a.folder) else "[]"
    }

    /* ---------------- screen overlay ---------------- */

    fun setDim(level: Float) {
        if (!allowed("overlay")) return
        if (!android.provider.Settings.canDrawOverlays(activity)) {
            warnOnce("overlay-off", "Turn on \"Display over other apps\" for ${Brand.NAME}, then try again.")
            runCatching {
                activity.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                              Uri.parse("package:${activity.packageName}")))
            }
            return
        }
        val i = Intent(activity, DimmerService::class.java).putExtra(DimmerService.EXTRA_LEVEL, level)
        runCatching { activity.startForegroundService(i) }
    }

    fun stopDim() {
        runCatching { activity.startService(Intent(activity, DimmerService::class.java).setAction(DimmerService.ACTION_STOP)) }
    }

    /** What this app declared and what the phone currently allows, as JSON, so apps can explain. */
    fun status(): String {
        val a = app()
        val o = JSONObject()
        o.put("declared", org.json.JSONArray(a?.permissions ?: emptyList<String>()))
        o.put("camera", granted(Manifest.permission.CAMERA))
        o.put("location", granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION))
        o.put("notifications", Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))
        o.put("exactReminders", Build.VERSION.SDK_INT < 31 ||
            activity.getSystemService(android.app.AlarmManager::class.java)?.canScheduleExactAlarms() == true)
        o.put("overlay", android.provider.Settings.canDrawOverlays(activity))
        return o.toString()
    }

    /* ---------------- location ---------------- */

    private val locationPermission = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { it }) fetchLocation() else fail("onLocation", "permission")
    }

    /** One position fix, while the app is open. Never in the background, never stored by Homebaked. */
    fun getLocation() {
        if (!allowed("location")) return fail("onLocation", "not-declared")
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)) fetchLocation()
        else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private fun fetchLocation() {
        val lm = activity.getSystemService(LocationManager::class.java) ?: return fail("onLocation", "unavailable")
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return fail("onLocation", "off")
        val main = Handler(Looper.getMainLooper())
        var answered = false
        fun answer(loc: Location?) {
            if (answered) return
            answered = true
            if (loc == null) fail("onLocation", "timeout")
            else toPage("onLocation", JSONObject().put("ok", true).put("lat", loc.latitude).put("lon", loc.longitude)
                .put("accuracy", loc.accuracy.toDouble()).put("time", loc.time).toString())
        }
        main.postDelayed({ answer(null) }, 20_000)
        val provider = providers.first()
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(provider, CancellationSignal(), ContextCompat.getMainExecutor(activity)) { answer(it) }
            } else {
                lm.requestSingleUpdate(provider, { answer(it) }, Looper.getMainLooper())
            }
        } catch (e: Exception) {
            answer(null)
        }
    }
}
