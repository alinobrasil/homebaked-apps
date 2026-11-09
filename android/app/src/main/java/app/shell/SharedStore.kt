package app.shell

import android.content.Context
import org.json.JSONArray

/**
 * Shared storage buckets: the one way two mini-apps can see the same data.
 *
 * Every mini-app has its own origin, so its localStorage and IndexedDB are private to it.
 * When two apps should share something (points earned in a language app that unlock
 * games in another), both list the same bucket in manifest.json:
 *
 *   "shared": ["rewards"]
 *
 * and use AndroidBridge.sharedGet / sharedSet / sharedRemove / sharedKeys. An app can only
 * touch buckets its own manifest lists; WebAppActivity enforces that.
 *
 * One SharedPreferences file per bucket. Reads come from memory, so calls are synchronous
 * like localStorage, and only one mini-app is ever open at a time.
 */
object SharedStore {

    /** Generous for app state, small enough that a runaway loop cannot fill the phone. */
    const val MAX_VALUE_CHARS = 512 * 1024

    private fun prefs(ctx: Context, bucket: String) =
        ctx.applicationContext.getSharedPreferences("bucket.$bucket", Context.MODE_PRIVATE)

    fun get(ctx: Context, bucket: String, key: String): String? =
        prefs(ctx, bucket).getString(key, null)

    fun set(ctx: Context, bucket: String, key: String, value: String): Boolean {
        if (value.length > MAX_VALUE_CHARS) return false
        prefs(ctx, bucket).edit().putString(key, value).apply()
        return true
    }

    fun remove(ctx: Context, bucket: String, key: String) {
        prefs(ctx, bucket).edit().remove(key).apply()
    }

    fun keys(ctx: Context, bucket: String): String =
        JSONArray(prefs(ctx, bucket).all.keys.sorted()).toString()
}
