/**
 * Compact floating readout: CPU/GPU load as tiny inline bar charts plus
 * frequency numbers, a third column (FPS, or used-RAM% if that setting is
 * on), and battery. Non-interactive by design (fixed position, not
 * draggable) — meant to sit unobtrusively during gameplay rather than be
 * interacted with like the main FloatMonitor.
 *
 * Updated to match the current Scene app: the headline CPU number is now
 * the highest active cluster frequency (MHz) rather than load% — load
 * moved to the new per-core bar chart instead. GPU gained the same
 * chart+frequency pairing (as a rolling history strip, since there's only
 * one GPU value per tick rather than one per core). Battery's alternating
 * cell switched from raw current (mA) to computed power (W), same
 * current×voltage math FloatMonitor already uses for its own #PWR line.
 */
package com.thermaloverlay.overlay.ui

import android.annotation.SuppressLint
import android.app.ActivityManager
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
        if (activityManager == null) {
            activityManager = mContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
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
    private var cpuChart: CpuChartBarView? = null
    private var cpuFreqText: TextView? = null
    private var gpuChart: CpuChartBarView? = null
    private var gpuFreqText: TextView? = null
    private var col3Text: TextView? = null
    private var batteryText: TextView? = null

    private val myHandler = Handler(Looper.getMainLooper())
    private var coreCount = -1
    private var clusters = ArrayList<Array<String>>()
    private var clustersFreq = ArrayList<String>()
    private var batteryManager: BatteryManager? = null
    private var activityManager: ActivityManager? = null
    private val memoryInfo = ActivityManager.MemoryInfo()

    // Recomputing RAM% is heavier than the other readings here, so (like
    // the source app) it only refreshes every 3rd tick — the third column
    // keeps showing its last value in between.
    private var ramTickCounter = 0
    private var lastRamText = "--"

    // Alternates the last cell between battery power (W) and temperature
    // every other tick, same as the source app — one text slot, two readings.
    private var pollingPhase = 0

    private fun subFreqStr(freq: String?): String {
        if (freq == null) return ""
        return when {
            freq.length > 3 -> freq.substring(0, freq.length - 3)
            freq.isEmpty() -> "0"
            else -> freq
        }
    }

    private fun updateInfo() {
        pollingPhase = (pollingPhase + 1) % 4

        if (coreCount < 1) {
            coreCount = cpuFrequencyUtils.getCoreCount()
            clusters = cpuFrequencyUtils.getClusterInfo()
        }

        // Per-cluster frequencies, same computation FloatMonitor already
        // does for its own headline number — the highest one is shown here
        // too, replacing the old load% readout.
        clustersFreq.clear()
        for (clusterIndex in 0 until clusters.size) {
            clustersFreq.add(cpuFrequencyUtils.getCurrentFrequency(clusterIndex))
        }
        var maxFreq = 0
        for (item in clustersFreq) {
            if (item.isNotEmpty()) {
                item.toIntOrNull()?.let { if (it > maxFreq) maxFreq = it }
            }
        }

        val loads = cpuLoadUtils.cpuLoad
        val coreLoads = Array(coreCount) { i -> (loads[i] ?: 0.0).toInt() }

        val gpuLoad = GpuUtils.getGpuLoad()
        val gpuFreq = GpuUtils.getGpuFreq().toIntOrNull() ?: -1

        val fps = fpsUtils.fps

        val ramMode = OverlayPrefs.isMiniMonitorRamMode(mContext)
        if (ramMode) {
            ramTickCounter = (ramTickCounter + 1) % 3
            if (ramTickCounter == 0) {
                activityManager?.getMemoryInfo(memoryInfo)
                val totalMem = (memoryInfo.totalMem / 1024 / 1024f).toInt()
                val availMem = (memoryInfo.availMem / 1024 / 1024f).toInt()
                if (totalMem > 0) {
                    lastRamText = "${(totalMem - availMem) * 100 / totalMem}%"
                }
            }
        }

        BatteryStatusReader.update(mContext)
        var battInfo: String? = null
        if (pollingPhase != 0) {
            val now = batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            val dualBatteryMultiplier = if (OverlayPrefs.isDualBattery(mContext)) 2 else 1
            val nowMa = now?.let { (it / BatteryStatusReader.currentUnitDivisor) * dualBatteryMultiplier }
            nowMa?.let {
                if (it > -20000 && it < 20000 && BatteryStatusReader.batteryVoltage > 0) {
                    val watts = (it * BatteryStatusReader.batteryVoltage) / 1000.0
                    battInfo = if (watts >= 100 || watts <= -100) {
                        String.format("%.1fW", watts)
                    } else {
                        String.format("%.2fW", watts)
                    }
                }
            }
        }
        if (battInfo == null) {
            battInfo = "${BatteryStatusReader.temperatureCurrent}\u2103"
        }

        myHandler.post {
            // Guarded like FloatMonitor.updateInfo(): views may already be
            // detached mid-hide, and this callback runs on the main thread.
            try {
                cpuChart?.setData(coreLoads)
                cpuFreqText?.text = subFreqStr(maxFreq.toString())
                gpuChart?.pushRolling(100f, 100f - gpuLoad)
                gpuFreqText?.text = if (gpuFreq > -1) gpuFreq.toString() else "--"
                col3Text?.text = if (ramMode) lastRamText else {
                    if (fps >= 100) fps.toInt().toString() else String.format("%.1f", fps)
                }
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
        cpuChart = view!!.findViewById(R.id.fw_cpu_chart)
        cpuFreqText = view!!.findViewById(R.id.fw_cpu_freq)
        gpuChart = view!!.findViewById(R.id.fw_gpu_chart)
        gpuFreqText = view!!.findViewById(R.id.fw_gpu_freq)
        col3Text = view!!.findViewById(R.id.fw_mini_col3_value)
        batteryText = view!!.findViewById(R.id.fw_battery_temp)

        val accent = context.getColor(R.color.accent)
        cpuChart?.apply {
            setMinAlpha(225)
            setMaxAlpha(225)
            setAccentColor(accent)
        }
        gpuChart?.apply {
            setMaxHistory(2)
            setMinAlpha(225)
            setMaxAlpha(225)
            setAccentColor(accent)
        }

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
