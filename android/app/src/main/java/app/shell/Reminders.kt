package app.shell

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONObject

/**
 * Reminders an app schedules with AndroidBridge.notify(...): a notification at a given time,
 * optionally repeating, that arrives even when Homebaked is closed. Tapping it opens that app.
 *
 * Stored in prefs (so they come back after a restart, see [BootReceiver]) and set as alarms.
 * Exact to the minute when the phone allows exact alarms ("Alarms & reminders" in the app's
 * settings); otherwise Android may deliver them a few minutes late to save battery.
 */
object Reminders {
    private const val CHANNEL = "reminders"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    private fun key(app: String, id: String) = "$app/$id"

    fun schedule(ctx: Context, app: String, id: String, title: String, body: String, at: Long, repeatMinutes: Int) {
        val r = JSONObject().put("app", app).put("id", id).put("title", title.take(80)).put("body", body.take(240))
            .put("at", at).put("repeat", repeatMinutes.coerceIn(0, 60 * 24 * 31))
        prefs(ctx).edit().putString(key(app, id), r.toString()).apply()
        arm(ctx, r)
    }

    fun cancel(ctx: Context, app: String, id: String) {
        prefs(ctx).getString(key(app, id), null)?.let { disarm(ctx, JSONObject(it)) }
        prefs(ctx).edit().remove(key(app, id)).apply()
    }

    /** Every reminder of an app, e.g. when the app is deleted. */
    fun cancelAll(ctx: Context, app: String) {
        prefs(ctx).all.keys.filter { it.startsWith("$app/") }.forEach { cancel(ctx, app, it.substringAfter('/')) }
    }

    /** JSON array of an app's reminders: [{id, title, body, at, repeat}]. */
    fun list(ctx: Context, app: String): String =
        org.json.JSONArray(prefs(ctx).all.filterKeys { it.startsWith("$app/") }.values.mapNotNull {
            runCatching { JSONObject(it as String).apply { remove("app") } }.getOrNull()
        }).toString()

    fun rearmAll(ctx: Context) {
        prefs(ctx).all.values.forEach { v -> runCatching { arm(ctx, JSONObject(v as String)) } }
    }

    private fun pending(ctx: Context, r: JSONObject, flags: Int): PendingIntent? {
        val i = Intent(ctx, ReminderReceiver::class.java)
            .setAction("app.shell.REMINDER")
            .setData(android.net.Uri.parse("homebaked-reminder://" + key(r.getString("app"), r.getString("id"))))
        return PendingIntent.getBroadcast(ctx, 0, i, flags or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun arm(ctx: Context, r: JSONObject) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        var at = r.getLong("at")
        val repeat = r.optInt("repeat", 0)
        val now = System.currentTimeMillis()
        if (at < now) {
            if (repeat <= 0) return
            val step = repeat * 60_000L
            at += ((now - at) / step + 1) * step          // next one still to come
        }
        val pi = pending(ctx, r, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    private fun disarm(ctx: Context, r: JSONObject) {
        val pi = pending(ctx, r, PendingIntent.FLAG_NO_CREATE) ?: return
        ctx.getSystemService(AlarmManager::class.java)?.cancel(pi)
    }

    /** Called by the alarm: show it, then set the next one or forget it. */
    fun fire(ctx: Context, key: String) {
        val raw = prefs(ctx).getString(key, null) ?: return
        val r = JSONObject(raw)
        val app = r.getString("app")
        val dir = java.io.File(PushHub.appsRoot(ctx), app)
        if (!java.io.File(dir, "index.html").exists()) { prefs(ctx).edit().remove(key).apply(); return }
        val info = AppInfo.read(dir)
        if ("notifications" !in info.permissions) { prefs(ctx).edit().remove(key).apply(); return }

        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders from your apps", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            ctx, key.hashCode(),
            Intent(ctx, WebAppActivity::class.java).putExtra("folder", app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = android.app.Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(r.optString("title").ifBlank { info.name })
            .setContentText(r.optString("body"))
            .setSubText(info.name)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(key.hashCode(), n) }

        if (r.optInt("repeat", 0) > 0) arm(ctx, r) else prefs(ctx).edit().remove(key).apply()
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val key = intent.data?.toString()?.removePrefix("homebaked-reminder://") ?: return
        Reminders.fire(ctx, key)
    }
}

/** Alarms don't survive a restart; this sets them again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Reminders.rearmAll(ctx)
        }
    }
}
