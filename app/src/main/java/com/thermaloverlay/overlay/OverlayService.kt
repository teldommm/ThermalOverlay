/**
 * Foreground service that keeps the floating overlay alive in the background,
 * independent of whether MainActivity is open.
 */
package com.thermaloverlay.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.thermaloverlay.overlay.ui.FloatMonitor

class OverlayService : Service() {
    private var floatMonitor: FloatMonitor? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())

        // The app is useless without root. Normally the screen/tile already
        // know the answer; after a process restart (START_STICKY) they don't,
        // so ask here, off the main thread (the root prompt can take a while).
        if (RootState.isGranted) {
            showOverlay()
        } else {
            Thread({
                val ok = RootState.check()
                mainHandler.post {
                    if (destroyed) return@post
                    if (ok) {
                        showOverlay()
                    } else {
                        Toast.makeText(this, R.string.root_required_toast, Toast.LENGTH_LONG).show()
                        stopSelf()
                    }
                }
            }, "OverlayRootCheck").start()
        }
    }

    private fun showOverlay() {
        val monitor = FloatMonitor(this)
        if (!monitor.showPopupWindow()) {
            // No window (permission revoked, addView failed): a service that
            // shows nothing but still holds a notification is just noise.
            stopSelf()
            return
        }
        floatMonitor = monitor
        OverlayState.isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        floatMonitor?.onConfigurationChanged()
    }

    override fun onDestroy() {
        // Runs only on a clean stop. After a crash / OOM kill the whole
        // process is gone and OverlayState starts from false in the next one,
        // so it can't be left stale.
        destroyed = true
        OverlayState.isRunning = false
        floatMonitor?.hidePopupWindow()
        floatMonitor = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_MIN
            )
        )

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setSmallIcon(R.drawable.ic_tile_overlay)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.stop_overlay), stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "perf_overlay_service"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.thermaloverlay.overlay.action.STOP"
    }
}
