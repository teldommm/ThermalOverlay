/**
 * Full-screen, click-through overlay showing the top 15 threads (by CPU) of
 * whatever app ForegroundAppService currently reports as foreground.
 *
 * Requires the accessibility service (Settings → Accessibility) — without
 * it GlobalStatus.lastPackageName never gets set and this just shows the
 * "enable accessibility" message.
 */
package com.thermaloverlay.overlay.ui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.thermaloverlay.overlay.R
import com.thermaloverlay.overlay.data.GlobalStatus
import com.thermaloverlay.overlay.metrics.ProcessUtilsSimple
import com.thermaloverlay.overlay.utils.WindowCompatHelper
import java.util.Timer
import java.util.TimerTask

class FloatMonitorThreads(private val mContext: Context) {
    private val processUtils = ProcessUtilsSimple()
    private val handler = Handler(Looper.getMainLooper())

    private var view: View? = null
    private var textView: TextView? = null

    private var lastApp = ""
    private var lastPid = -1

    private val pid: Int
        get() {
            val app = GlobalStatus.lastPackageName
            if (app != lastApp || lastPid < 1) {
                if (app.isNotEmpty()) {
                    lastApp = app
                    lastPid = processUtils.getAppMainProcess(app)
                }
            }
            return lastPid
        }

    private fun updateData() {
        val currentPid = pid
        val text = if (currentPid > 0) {
            val top15 = processUtils.getThreadLoads(currentPid)
            top15.joinToString("\n", "$lastApp [$lastPid]\nTop 15, sorted by %CPU\n") {
                "${it.cpuLoad}% [${it.tid}] ${it.name}"
            }
        } else {
            mContext.getString(R.string.thread_monitor_no_foreground_app)
        }
        handler.post { textView?.text = text }
    }

    private fun startTimer() {
        stopTimer()
        timer = Timer()
        timer!!.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                try {
                    updateData()
                } catch (ex: Exception) {
                }
            }
        }, 0, 3000)
    }

    private fun stopTimer() {
        timer?.cancel()
        timer = null
    }

    private var screenReceiver: BroadcastReceiver? = null

    // Same reasoning as FloatMonitor: invisible while the screen is off, but
    // the timer would otherwise keep running `top -H` for nothing every 3s.
    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> stopTimer()
                    Intent.ACTION_SCREEN_ON -> if (show) startTimer()
                }
            }
        }
        try {
            mContext.registerReceiver(screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            })
        } catch (ex: Exception) {
            screenReceiver = null
        }
    }

    private fun unregisterScreenReceiver() {
        screenReceiver?.let {
            try {
                mContext.unregisterReceiver(it)
            } catch (ex: Exception) {
            }
        }
        screenReceiver = null
    }

    fun showPopupWindow(): Boolean {
        if (show) return true

        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(mContext)) {
            Toast.makeText(mContext, mContext.getString(R.string.permission_float), Toast.LENGTH_LONG).show()
            return false
        }

        view = LayoutInflater.from(mContext).inflate(R.layout.fw_threads, null)
        textView = view!!.findViewById(R.id.fw_logs)

        val params = WindowManager.LayoutParams().apply {
            height = WindowManager.LayoutParams.MATCH_PARENT
            width = WindowManager.LayoutParams.MATCH_PARENT
            screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            type = WindowCompatHelper.overlayWindowType()
            format = PixelFormat.TRANSLUCENT
            x = 0
            y = 0
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        mWindowManager = mContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return try {
            mWindowManager!!.addView(view, params)
            mView = view
            startTimer()
            registerScreenReceiver()
            true
        } catch (ex: Exception) {
            // Same reasoning as every other monitor here: don't leave `show`
            // truthy (via a leftover mView) after a failed addView, or this
            // becomes a permanent no-op.
            mView = null
            Toast.makeText(mContext, "FloatMonitorThreads Error\n" + ex.message, Toast.LENGTH_LONG).show()
            false
        }
    }

    fun hidePopupWindow() {
        stopTimer()
        unregisterScreenReceiver()
        mView?.let {
            try {
                mWindowManager?.removeViewImmediate(it)
            } catch (ex: Exception) {
            }
            mView = null
        }
    }

    companion object {
        private var mWindowManager: WindowManager? = null

        @SuppressLint("StaticFieldLeak")
        private var mView: View? = null
        private var timer: Timer? = null

        val show: Boolean
            get() = mView != null
    }
}
