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
import com.thermaloverlay.overlay.ui.FloatFpsWatch
import com.thermaloverlay.overlay.ui.FloatMonitor
import com.thermaloverlay.overlay.ui.FloatMonitorMini
import com.thermaloverlay.overlay.ui.FloatTaskManager
import com.thermaloverlay.overlay.ui.FloatTemperature

class OverlayService : Service() {
    private var floatMonitor: FloatMonitor? = null
    private var floatMonitorMini: FloatMonitorMini? = null
    private var floatTaskManager: FloatTaskManager? = null
    private var floatFpsWatch: FloatFpsWatch? = null
    private var floatTemperature: FloatTemperature? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        OverlayPrefs.setEnabled(this, true)

        // Kill a simpleperf stream orphaned by a previous process death
        // (shell call — must not run on the main thread).
        Thread { CpuCyclesUtils.cleanupOrphans() }.start()

        if (OverlayPrefs.isLoadMonitorEnabled(this) && !FloatMonitor.show) {
            floatMonitor = FloatMonitor(this)
            floatMonitor?.showPopupWindow()
        }
        if (OverlayPrefs.isMiniMonitorEnabled(this) && !FloatMonitorMini.show) {
            floatMonitorMini = FloatMonitorMini(this)
            floatMonitorMini?.showPopupWindow()
        }
        if (OverlayPrefs.isProcessMonitorEnabled(this) && !FloatTaskManager.show) {
            floatTaskManager = FloatTaskManager(this)
            floatTaskManager?.showPopupWindow()
        }
        if (OverlayPrefs.isFpsRecorderEnabled(this) && !FloatFpsWatch.show) {
            floatFpsWatch = FloatFpsWatch(this)
            floatFpsWatch?.showPopupWindow()
        }
        if (OverlayPrefs.isTemperatureMonitorEnabled(this) && !FloatTemperature.show) {
            floatTemperature = FloatTemperature(this)
            floatTemperature?.showPopupWindow()
        }

        // Nothing to show (e.g. the QS tile was tapped after every monitor
        // had been switched off) — don't sit around as a bare notification.
        stopIfNothingEnabled()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            // Lets MainActivity flip these switches live, without tearing
            // down the whole foreground service — and, since every monitor
            // is independently switchable now, without a separate Start/Stop:
            // the service starts itself the moment any one of these turns on
            // (MainActivity just calls startForegroundService regardless of
            // whether it's already running), and stopIfNothingEnabled() below
            // tears it down once the last one turns off.
            ACTION_TOGGLE_LOAD -> {
                if (OverlayPrefs.isLoadMonitorEnabled(this)) {
                    if (!FloatMonitor.show) {
                        floatMonitor = FloatMonitor(this)
                        floatMonitor?.showPopupWindow()
                    }
                } else {
                    floatMonitor?.hidePopupWindow()
                    floatMonitor = null
                }
            }
            ACTION_TOGGLE_MINI -> {
                if (OverlayPrefs.isMiniMonitorEnabled(this)) {
                    if (!FloatMonitorMini.show) {
                        floatMonitorMini = FloatMonitorMini(this)
                        floatMonitorMini?.showPopupWindow()
                    }
                } else {
                    floatMonitorMini?.hidePopupWindow()
                    floatMonitorMini = null
                }
            }
            ACTION_TOGGLE_PROCESS -> {
                if (OverlayPrefs.isProcessMonitorEnabled(this)) {
                    if (!FloatTaskManager.show) {
                        floatTaskManager = FloatTaskManager(this)
                        floatTaskManager?.showPopupWindow()
                    }
                } else {
                    floatTaskManager?.hidePopupWindow()
                    floatTaskManager = null
                }
            }
            ACTION_TOGGLE_FPS -> {
                if (OverlayPrefs.isFpsRecorderEnabled(this)) {
                    if (!FloatFpsWatch.show) {
                        floatFpsWatch = FloatFpsWatch(this)
                        floatFpsWatch?.showPopupWindow()
                    }
                } else {
                    floatFpsWatch?.hidePopupWindow()
                    floatFpsWatch = null
                }
            }
            ACTION_TOGGLE_TEMPERATURE -> {
                if (OverlayPrefs.isTemperatureMonitorEnabled(this)) {
                    if (!FloatTemperature.show) {
                        floatTemperature = FloatTemperature(this)
                        floatTemperature?.showPopupWindow()
                    }
                } else {
                    floatTemperature?.hidePopupWindow()
                    floatTemperature = null
                }
            }
        }
        stopIfNothingEnabled()
        return START_STICKY
    }

    private fun stopIfNothingEnabled() {
        if (!OverlayPrefs.anyMonitorEnabled(this)) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        floatMonitor?.hidePopupWindow()
        floatMonitor = null
        floatMonitorMini?.hidePopupWindow()
        floatMonitorMini = null
        floatTaskManager?.hidePopupWindow()
        floatTaskManager = null
        floatFpsWatch?.hidePopupWindow()
        floatFpsWatch = null
        floatTemperature?.hidePopupWindow()
        floatTemperature = null
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
        const val ACTION_TOGGLE_LOAD = "com.thermaloverlay.overlay.action.TOGGLE_LOAD"
        const val ACTION_TOGGLE_MINI = "com.thermaloverlay.overlay.action.TOGGLE_MINI"
        const val ACTION_TOGGLE_PROCESS = "com.thermaloverlay.overlay.action.TOGGLE_PROCESS"
        const val ACTION_TOGGLE_FPS = "com.thermaloverlay.overlay.action.TOGGLE_FPS"
        const val ACTION_TOGGLE_TEMPERATURE = "com.thermaloverlay.overlay.action.TOGGLE_TEMPERATURE"
    }
}
