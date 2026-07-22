/**
 * Compact floating readout: CPU / GPU / FPS / battery in a single row, no
 * ring charts. Non-interactive by design (fixed position, not draggable,
 * not touchable) — meant to sit unobtrusively during gameplay rather than
 * be interacted with like the main FloatMonitor.
 *
 * "dominant big-core load" logic is preserved as-is: a single saturated
 * performance core stalls frame pacing even when the average load across
 * all cores looks fine, so whichever number is worse gets shown.
 */
package com.thermaloverlay.overlay.ui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.TextView
import android.widget.Toast
import com.thermaloverlay.overlay.OverlayPrefs
import com.thermaloverlay.overlay.R
import com.thermaloverlay.overlay.metrics.BatteryStatusReader
import com.thermaloverlay.overlay.metrics.CpuFrequencyUtils
import com.thermaloverlay.overlay.metrics.CpuLoadUtils
import com.thermaloverlay.overlay.metrics.FpsUtils
import com.thermaloverlay.overlay.metrics.GpuUtils
import com.thermaloverlay.overlay.utils.WindowCompatHelper
import java.util.Timer
import java.util.TimerTask

class FloatMonitorMini(private val mContext: Context) {
    private val cpuLoadUtils = CpuLoadUtils()
    private val cpuFrequencyUtils = CpuFrequencyUtils()
    private val fpsUtils = FpsUtils()

    fun showPopupWindow(): Boolean {
        if (show) return true

        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(mContext)) {
            Toast.makeText(mContext, mContext.getString(R.string.permission_float), Toast.LENGTH_LONG).show()
            return false
        }
        if (batteryManager == null) {
            batteryManager = mContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        }

        show = true
        mWindowManager = mContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val view = setUpView(mContext)

        val params = LayoutParams()
        params.type = WindowCompatHelper.overlayWindowType()
        params.format = PixelFormat.TRANSLUCENT
        params.width = LayoutParams.WRAP_CONTENT
        params.height = LayoutParams.WRAP_CONTENT
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.y = dp2px(mContext, 6f)

        @Suppress("DEPRECATION")
        params.flags = LayoutParams.FLAG_NOT_TOUCH_MODAL or LayoutParams.FLAG_NOT_FOCUSABLE or
            LayoutParams.FLAG_NOT_TOUCHABLE or LayoutParams.FLAG_FULLSCREEN

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        try {
            mWindowManager!!.addView(view, params)
            mView = view
            startTimer()
            registerScreenReceiver()
            return true
        } catch (ex: Exception) {
            // Same reasoning as FloatMonitor: reset the flag on failure so a
            // revoked overlay permission doesn't permanently wedge showPopupWindow()
            // into a no-op that returns true without ever adding a view.
            show = false
            Toast.makeText(mContext, "FloatMonitorMini Error\n" + ex.message, Toast.LENGTH_LONG).show()
            return false
        }
    }

    private fun dp2px(context: Context, dpValue: Float): Int {
        val scale = context.resources.displayMetrics.density
        return (dpValue * scale + 0.5f).toInt()
    }

    private var screenReceiver: BroadcastReceiver? = null

    // Same reasoning as FloatMonitor: this is invisible while the screen is
    // off, but the timer would otherwise keep polling CPU/GPU/battery/FPS
    // for nothing.
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

    private var view: View? = null
    private var cpuLoadText: TextView? = null
    private var gpuLoadText: TextView? = null
    private var fpsText: TextView? = null
    private var batteryText: TextView? = null

    private val myHandler = Handler(Looper.getMainLooper())
    private var coreCount = -1
    private var clusters = ArrayList<Array<String>>()
    private var batteryManager: BatteryManager? = null

    // Alternates the last cell between battery current (mA) and temperature
    // every other tick, same as the source app — one text slot, two readings.
    private var pollingPhase = 0

    private fun updateInfo() {
        pollingPhase = (pollingPhase + 1) % 4

        if (coreCount < 1) {
            coreCount = cpuFrequencyUtils.getCoreCount()
            clusters = cpuFrequencyUtils.getClusterInfo()
        }

        val gpuLoad = GpuUtils.getGpuLoad()
        var cpuLoad = cpuLoadUtils.cpuLoadSum
        val loads = cpuLoadUtils.cpuLoad

        val centerIndex = coreCount / 2
        var bigCoreLoadMax = 0.0
        if (centerIndex >= 2) {
            try {
                for (i in centerIndex until coreCount) {
                    val coreLoad = loads[i] ?: continue
                    if (coreLoad > bigCoreLoadMax) bigCoreLoadMax = coreLoad
                }
                if (bigCoreLoadMax > 70 && bigCoreLoadMax > cpuLoad) {
                    cpuLoad = bigCoreLoadMax
                }
            } catch (ex: Exception) {
            }
        }
        if (cpuLoad < 0) cpuLoad = 0.0

        val fps = fpsUtils.currentFps

        BatteryStatusReader.update(mContext)
        var battInfo: String? = null
        if (pollingPhase != 0) {
            val now = batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            val dualBatteryMultiplier = if (OverlayPrefs.isDualBattery(mContext)) 2 else 1
            val nowMa = now?.let { (it / BatteryStatusReader.currentUnitDivisor) * dualBatteryMultiplier }
            nowMa?.let {
                if (it > -20000 && it < 20000) {
                    battInfo = (if (it > 0) "+$it" else "$it") + "mA"
                }
            }
        }
        if (battInfo == null) {
            battInfo = "${BatteryStatusReader.temperatureCurrent}°C"
        }

        myHandler.post {
            // Guarded like FloatMonitor.updateInfo(): views may already be
            // detached mid-hide, and this callback runs on the main thread.
            try {
                cpuLoadText?.text = "${cpuLoad.toInt()}%"
                gpuLoadText?.text = if (gpuLoad > -1) "$gpuLoad%" else "--"
                fpsText?.text = fps ?: "--"
                batteryText?.text = battInfo
            } catch (ex: Exception) {
            }
        }
    }

    private fun startTimer() {
        stopTimer()
        timer = Timer()
        timer!!.schedule(object : TimerTask() {
            override fun run() {
                try {
                    updateInfo()
                } catch (ex: Exception) {
                }
            }
        }, 0, 1500)
    }

    private fun stopTimer() {
        timer?.cancel()
        timer = null
    }

    fun hidePopupWindow() {
        stopTimer()
        unregisterScreenReceiver()
        if (show && mView != null) {
            try {
                mWindowManager?.removeViewImmediate(mView)
            } catch (ex: Exception) {
            }
            mView = null
        }
        show = false
    }

    private fun setUpView(context: Context): View {
        view = LayoutInflater.from(context).inflate(R.layout.fw_monitor_mini, null)
        cpuLoadText = view!!.findViewById(R.id.fw_cpu_load)
        gpuLoadText = view!!.findViewById(R.id.fw_gpu_load)
        fpsText = view!!.findViewById(R.id.fw_fps)
        batteryText = view!!.findViewById(R.id.fw_battery_temp)
        return view!!
    }

    companion object {
        private var mWindowManager: WindowManager? = null
        var show: Boolean = false

        @SuppressLint("StaticFieldLeak")
        private var mView: View? = null
        private var timer: Timer? = null
    }
}
