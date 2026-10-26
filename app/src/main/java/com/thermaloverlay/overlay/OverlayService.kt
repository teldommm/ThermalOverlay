/**
 * Foreground service that keeps the floating overlay alive in the background,
 * independent of whether MainActivity is open.
 */
package com.thermaloverlay.overlay

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
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
        if (RootState.isGranted && Settings.canDrawOverlays(this)) {
            showOverlay()
        } else {
            Thread({
                val ok = RootState.isGranted || RootState.check()
                // Root can switch "display over other apps" on by itself.
                val allowed = ok && RootState.ensureOverlayPermission(this)
                mainHandler.post {
                    if (destroyed) return@post
                    if (allowed) {
                        showOverlay()
                    } else {
                        Toast.makeText(
                            this,
                            if (ok) R.string.permission_float else R.string.root_required_toast,
                            Toast.LENGTH_LONG
                        ).show()
                        OverlayPrefs.setWanted(this, false)
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
            OverlayPrefs.setWanted(this, false)
            stopSelf()
            return
        }
        floatMonitor = monitor
        OverlayState.isRunning = true
        // From here on the overlay stays until the user stops it, also across
        // the system killing the process.
        OverlayPrefs.setWanted(this, true)
        exemptFromBackgroundLimitsOnce()
    }

    /**
     * Root-only, once per install: whitelist the app from Doze / battery
     * optimisation. Delayed so it doesn't compete with the first HUD ticks
     * for the root shell.
     */
    private fun exemptFromBackgroundLimitsOnce() {
        if (OverlayPrefs.isBackgroundExemptDone(this)) return
        Thread({
            try {
                Thread.sleep(3000)
                RootState.ensureBackgroundExemptions(this)
                OverlayPrefs.setBackgroundExemptDone(this)
            } catch (ignored: Exception) {
            }
        }, "OverlayBgExempt").start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            OverlayPrefs.setWanted(this, false)
            cancelRestart(this)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    // Swiping the app out of the recents list: the foreground service normally
    // survives that (stopWithTask is false), but some ROMs kill the whole
    // process anyway. If the overlay is wanted, ask the system to start the
    // service again a moment later. Starting a foreground service from the
    // background is allowed here because the app holds "display over other
    // apps".
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!OverlayPrefs.isWanted(this)) return
        try {
            getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + RESTART_DELAY_MS,
                restartIntent(this)
            )
        } catch (ignored: Exception) {
        }
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
        private const val RESTART_DELAY_MS = 1500L
        private const val RESTART_REQUEST = 2

        private fun restartIntent(context: Context): PendingIntent = PendingIntent.getForegroundService(
            context, RESTART_REQUEST, Intent(context, OverlayService::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        private fun cancelRestart(context: Context) {
            try {
                context.getSystemService(AlarmManager::class.java).cancel(restartIntent(context))
            } catch (ignored: Exception) {
            }
        }

        /**
         * The one way for the user to switch the overlay off (button, tile,
         * double tap): forgets that it is wanted, so it isn't revived.
         */
        fun requestStop(context: Context) {
            OverlayPrefs.setWanted(context, false)
            cancelRestart(context)
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
