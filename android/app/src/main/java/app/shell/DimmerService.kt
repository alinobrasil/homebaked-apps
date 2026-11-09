package app.shell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.os.Build
import android.os.IBinder
import android.view.View
import android.view.WindowManager

/**
 * The "overlay" ability: a black overlay over the whole screen, for apps that dim it (bedtime
 * reading, a night light). Needs Android's "Display over other apps" switch, which the person
 * turns on in Settings; it is never on by default.
 */
class DimmerService : Service() {

    private var overlay: View? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val level = intent?.getFloatExtra(EXTRA_LEVEL, 0.3f) ?: 0.3f
        startAsForeground()
        showOverlay(level)
        return START_STICKY
    }

    private fun startAsForeground() {
        val channelId = "dimmer"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Dimmer", NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        // Always visible, with a Stop button, so the dimming can be switched off from anywhere.
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DimmerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Screen dimmer on")
            .setContentText("Tap Stop to turn it off")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pi)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        startForeground(1, notification)
    }

    /**
     * How opaque an overlay is allowed to be before Android 12+ starts dropping
     * every touch that passes through it. The system measures
     * WindowManager.LayoutParams.alpha, not View.alpha, and FLAG_NOT_TOUCHABLE
     * does not exempt a window from the check. Ask the device for its real
     * limit rather than assuming the 0.8 default, since a manufacturer can
     * lower it.
     */
    private fun maxSafeOpacity(): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 1f
        return try {
            val im = getSystemService(InputManager::class.java)
            val max = im.maximumObscuringOpacityForTouch
            if (max > 0f) max else 0.8f
        } catch (e: Throwable) {
            0.8f
        }
    }

    private fun showOverlay(level: Float) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        // Stay a hair under the limit. The check is a strict comparison against
        // the device maximum and floats arriving from JavaScript are not exact.
        val ceiling = (maxSafeOpacity() - 0.01f).coerceAtLeast(0f)
        val dim = level.coerceIn(0f, ceiling)

        var p = params
        if (p == null || overlay == null) {
            val view = View(this)
            // The view itself stays fully opaque black. The dim comes from the
            // window alpha below, which is the property Android actually reads.
            view.setBackgroundColor(Color.BLACK)
            p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            p.alpha = dim
            wm.addView(view, p)
            overlay = view
            params = p
            return
        }

        p.alpha = dim
        overlay?.let { wm.updateViewLayout(it, p) }
    }

    override fun onDestroy() {
        overlay?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        overlay = null
        params = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "app.shell.STOP_DIM"
        const val EXTRA_LEVEL = "level"
    }
}
