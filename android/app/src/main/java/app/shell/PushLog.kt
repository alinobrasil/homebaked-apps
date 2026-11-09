package app.shell

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject
import java.io.File

/**
 * A record of every mini-app install that reached the phone: pushes from a linked computer and
 * deletes, including the ones that failed.
 *
 * One JSON object per line in the app's private files, newest last on disk. Writers are
 * the upload server's threads and the launcher, so every access is synchronized.
 */
object PushLog {

    data class Entry(
        val time: Long,
        val id: String,          // folder name
        val name: String,        // manifest name when known, else the folder name
        val action: String,      // New, Updated, File, Deleted, Failed
        val source: String,      // which linked computer, or "this phone"
        val detail: String,      // files and size, or why it failed
        val ok: Boolean
    )

    private const val MAX = 300

    /** Bumped on every change, so an open log view redraws. Main thread only. */
    val revision = mutableStateOf(0)

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "push-log.jsonl")

    @Synchronized
    fun add(ctx: Context, e: Entry) {
        val f = file(ctx)
        val line = JSONObject()
            .put("t", e.time).put("id", e.id).put("name", e.name).put("action", e.action)
            .put("source", e.source).put("detail", e.detail).put("ok", e.ok)
            .toString()
        runCatching {
            f.appendText(line + "\n")
            // Trim in batches rather than on every write.
            val lines = f.readLines()
            if (lines.size > MAX + 50) f.writeText(lines.takeLast(MAX).joinToString("\n", postfix = "\n"))
        }
        Handler(Looper.getMainLooper()).post { revision.value++ }
    }

    /** Newest first. Lines that do not parse are skipped. */
    @Synchronized
    fun read(ctx: Context): List<Entry> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return runCatching { f.readLines() }.getOrDefault(emptyList()).mapNotNull { line ->
            runCatching {
                val o = JSONObject(line)
                Entry(
                    o.getLong("t"), o.getString("id"), o.optString("name", o.getString("id")),
                    o.getString("action"), o.optString("source"), o.optString("detail"),
                    o.optBoolean("ok", true)
                )
            }.getOrNull()
        }.asReversed()
    }

    @Synchronized
    fun clear(ctx: Context) {
        file(ctx).delete()
        Handler(Looper.getMainLooper()).post { revision.value++ }
    }

    /* ---------- helpers ---------- */

    fun isInstalled(appsRoot: File, id: String) = File(appsRoot, "$id/index.html").exists()

    fun displayName(appsRoot: File, id: String): String =
        runCatching {
            JSONObject(File(appsRoot, "$id/manifest.json").readText()).optString("name", id)
        }.getOrDefault(id).ifEmpty { id }

    fun size(bytes: Long): String =
        if (bytes < 1024) "$bytes B" else if (bytes < 1024 * 1024) "${bytes / 1024} KB"
        else "%.1f MB".format(bytes / 1048576.0)
}
