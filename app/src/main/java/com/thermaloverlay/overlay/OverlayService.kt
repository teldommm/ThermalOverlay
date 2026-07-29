/**
 * Foreground service that keeps the floating overlay alive in the background,
 * independent of whether MainActivity is open.
 */
package com.thermaloverlay.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.thermaloverlay.overlay.metrics.CpuCyclesUtils
import com.thermaloverlay.overlay.ui.FloatMonitor

class OverlayService : Service() {
    private var floatMonitor: FloatMonitor? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForeground(NOTIFICATION_ID, buildNotification())

        // Kill a simpleperf stream orphaned by a previous process death
        // (shell call — must not run on the main thread).
        Thread { CpuCyclesUtils.cleanupOrphans() }.start()

        if (!FloatMonitor.show) {
            floatMonitor = FloatMonitor(this)
            floatMonitor?.showPopupWindow()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // Set first: this only runs on a clean stop (stopSelf/stopService).
        // A force-stop, OOM kill, or crash takes the whole process down
        // without ever reaching here — isRunning's default (false) on the
        // next process start is what actually protects against a stale
        // "enabled" flag in that case, not this line. See MainActivity.refreshUi().
        isRunning = false
        floatMonitor?.hidePopupWindow()
        floatMonitor = null
        OverlayPrefs.setEnabled(this, false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_MIN
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setSmallIcon(android.R.drawable.stat_sys_speakerphone)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "perf_overlay_service"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.thermaloverlay.overlay.action.STOP"

        // Same-process ground truth for "is an actual Service instance alive
        // right now". Defaults to false on every fresh process start, which
        // is exactly what makes it useful: if the process was killed without
        // onDestroy() running, this correctly reads false even though
        // OverlayPrefs.isEnabled() may still (wrongly) say true.
        @Volatile var isRunning: Boolean = false
            private set
    }
}
