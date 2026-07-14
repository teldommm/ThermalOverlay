/**
 * The floating HUD window: layout, drag handling, click-to-cycle, and periodic
 * metrics polling.
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
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.thermaloverlay.overlay.OverlayPrefs
import com.thermaloverlay.overlay.R
import com.thermaloverlay.overlay.metrics.BatteryStatusReader
import com.thermaloverlay.overlay.metrics.CpuCyclesUtils
import com.thermaloverlay.overlay.metrics.CpuFrequencyUtils
import com.thermaloverlay.overlay.metrics.CpuLoadUtils
import com.thermaloverlay.overlay.metrics.FpsUtils
import com.thermaloverlay.overlay.metrics.GpuUtils
import com.thermaloverlay.overlay.metrics.SwapUtils
import com.thermaloverlay.overlay.utils.WindowCompatHelper
import java.util.Timer
import java.util.TimerTask

class FloatMonitor(private val mContext: Context) {
    private val cpuLoadUtils = CpuLoadUtils()
    private val cpuFrequencyUtils = CpuFrequencyUtils()
    private val fpsUtils = FpsUtils()

    fun showPopupWindow(): Boolean {
        if (show) return true
        if (batteryManager == null) {
            batteryManager = mContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        }

        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(mContext)) {
            Toast.makeText(mContext, mContext.getString(R.string.permission_float), Toast.LENGTH_LONG).show()
            return false
        }

        show = true
        mWindowManager = mContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val view = setUpView(mContext)

        val params = LayoutParams()
        val monitorStorage = mContext.getSharedPreferences("float_monitor_storage", Context.MODE_PRIVATE)

        params.type = WindowCompatHelper.overlayWindowType()
        params.format = PixelFormat.TRANSLUCENT
        params.width = LayoutParams.WRAP_CONTENT
        params.height = LayoutParams.WRAP_CONTENT
        params.gravity = Gravity.TOP or Gravity.LEFT
        params.x = monitorStorage.getInt("x", 0)
        params.y = monitorStorage.getInt("y", 0)

        @Suppress("DEPRECATION")
        params.flags = LayoutParams.FLAG_NOT_TOUCH_MODAL or LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_FULLSCREEN

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        try {
            mWindowManager!!.addView(view, params)
            mView = view

            view.setOnTouchListener(object : View.OnTouchListener {
                private var isTouchDown = false
                private var touchStartRawX = 0f
                private var touchStartRawY = 0f

                private var paramStartX = 0
                private var paramStartY = 0
                private var touchStartTime = 0L
                private var lastClickTime = 0L

                private fun onClick() {
                    try {
                        if (System.currentTimeMillis() - lastClickTime < 300) {
                            hidePopupWindow()
                        } else {
                            lastClickTime = System.currentTimeMillis()
                        }
                    } catch (ex: Exception) {
                    }
                }

                @SuppressLint("ClickableViewAccessibility")
                override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                    if (event != null) {
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                touchStartRawX = event.rawX
                                touchStartRawY = event.rawY
                                paramStartX = params.x
                                paramStartY = params.y
                                isTouchDown = true
                                touchStartTime = System.currentTimeMillis()
                            }
                            MotionEvent.ACTION_MOVE -> {
                                if (isTouchDown) {

                                    params.x = paramStartX + (event.rawX - touchStartRawX).toInt()
                                    params.y = paramStartY + (event.rawY - touchStartRawY).toInt()
                                    mWindowManager!!.updateViewLayout(v, params)
                                }
                            }
                            MotionEvent.ACTION_UP -> {
                                val moved = Math.abs(event.rawX - touchStartRawX) > 15 ||
                                    Math.abs(event.rawY - touchStartRawY) > 15
                                if (moved) {

                                    monitorStorage.edit().putInt("x", params.x).putInt("y", params.y).apply()
                                } else if (System.currentTimeMillis() - touchStartTime < 180) {
                                    onClick()
                                }
                                isTouchDown = false
                                if (moved) {
                                    return true
                                }
                            }
                            MotionEvent.ACTION_OUTSIDE,
                            MotionEvent.ACTION_CANCEL -> {
                                isTouchDown = false
                            }
                        }
                    }
                    return false
                }
            })

            startTimer()
            registerScreenReceiver()
            return true
        } catch (ex: Exception) {
            // If addView failed (e.g. the overlay permission was revoked while
            // the service was running), reset the flag — otherwise every later
            // showPopupWindow() returns true without a window and the overlay
            // can never be shown again until the process dies.
            show = false
            Toast.makeText(mContext, "FloatMonitor Error\n" + ex.message, Toast.LENGTH_LONG).show()
            return false
        }
    }

    private fun stopTimer() {
        timer?.cancel()
        timer = null
    }

    private var screenReceiver: BroadcastReceiver? = null

    // The overlay is invisible while the screen is off, but the timer kept
    // polling the root shell (and dumpsys/simpleperf in detailed mode),
    // preventing the device from resting. Pause everything on SCREEN_OFF.
    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        stopTimer()
                        Thread { CpuCyclesUtils.stopStream() }.start()
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        if (show) startTimer()
                    }
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

    private fun subFreqStr(freq: String?): String {
        if (freq == null) return ""
        return when {
            freq.length > 3 -> freq.substring(0, freq.length - 3)
            freq.isEmpty() -> "0"
            else -> freq
        }
    }

    private var view: View? = null
    private var cpuChart: FloatMonitorChartView? = null
    private var cpuFreqText: TextView? = null
    private var gpuChart: FloatMonitorChartView? = null
    private var gpuFreqText: TextView? = null
    private var temperatureChart: FloatMonitorBatteryView? = null
    private var temperatureText: TextView? = null
    private var batteryLevelText: TextView? = null
    private var chargerView: ImageView? = null
    private var otherInfo: TextView? = null

    private var activityManager: ActivityManager? = null
    private val myHandler = Handler(Looper.getMainLooper())
    private val info = ActivityManager.MemoryInfo()

    private var totalMem = 0
    private var availMem = 0
    private var coreCount = -1
    private var showOtherInfo = false
    private var clusters = ArrayList<Array<String>>()
    private var clustersFreq = ArrayList<String>()

    private var batteryManager: BatteryManager? = null

    private fun whiteBoldSpan(text: String): SpannableString {
        return SpannableString(text).apply {
            setSpan(ForegroundColorSpan(android.graphics.Color.WHITE), 0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun updateInfo() {
        if (coreCount < 1) {
            coreCount = cpuFrequencyUtils.getCoreCount()
        }
        // Retried separately from coreCount: on first launch the su prompt may
        // not be confirmed yet, so cluster info can come back empty while the
        // core count (plain File.exists, no root) already succeeded.
        if (clusters.isEmpty()) {
            clusters = cpuFrequencyUtils.getClusterInfo()
        }
        clustersFreq.clear()
        for (coreIndex in 0 until clusters.size) {
            clustersFreq.add(cpuFrequencyUtils.getCurrentFrequency(coreIndex))
        }
        val loads = cpuLoadUtils.cpuLoad
        val gpuFreq = GpuUtils.getGpuFreq() + "Mhz"
        val gpuLoad = GpuUtils.getGpuLoad()

        var maxFreq = 0
        for (item in clustersFreq) {
            if (item.isNotEmpty()) {
                try {
                    val freq = item.toInt()
                    if (freq > maxFreq) maxFreq = freq
                } catch (ex: Exception) {
                }
            }
        }
        val cpuFreq = maxFreq.toString()

        activityManager!!.getMemoryInfo(info)

        var cpuLoad = cpuLoadUtils.cpuLoadSum
        if (cpuLoad < 0) cpuLoad = 0.0

        BatteryStatusReader.update(mContext)

        val batteryCurrentNow = batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val dualBatteryMultiplier = if (OverlayPrefs.isDualBattery(mContext)) 2 else 1
        val batteryCurrentNowMa = batteryCurrentNow?.let { (it / BatteryStatusReader.currentUnitDivisor) * dualBatteryMultiplier }

        val otherInfoBuilder = SpannableStringBuilder()
        if (showOtherInfo) {
            // Read GPU memory only here: it's a shell call per tick and the
            // value is not shown anywhere in compact mode.
            val gpuMemoryUsage = GpuUtils.getMemoryUsage()
            totalMem = (info.totalMem / 1024 / 1024f).toInt()
            availMem = (info.availMem / 1024 / 1024f).toInt()
            val swapPercent = SwapUtils.getZramFillPercent()
            val ramInfoText = "#RAM  " + ((totalMem - availMem) * 100 / totalMem).toString() + "%   " + swapPercent + "%"
            val ddrFreq = cpuFrequencyUtils.getDdrFrequency()

            otherInfoBuilder.run {
                append(whiteBoldSpan(ramInfoText))
                append("\n")

                if (ddrFreq.isNotEmpty()) {
                    append(" DDR  $ddrFreq")
                    append("\n")
                }

                if (gpuMemoryUsage != null) {
                    append(whiteBoldSpan("#GMEM " + gpuMemoryUsage))
                    append("\n")
                }

                append(whiteBoldSpan("#CPU  " + cpuLoadUtils.getCpuTemperatureText()))
                append("\n")

                val perCoreCycles = CpuCyclesUtils.getPerCoreCyclesMhz()

                for ((clusterIndex, cluster) in clusters.withIndex()) {
                    if (clusterIndex != 0) append("\n")
                    if (cluster.isNotEmpty()) {
                        try {
                            val title = "#" + cluster[0] + "~" + cluster[cluster.size - 1] + "  " + subFreqStr(clustersFreq.getOrNull(clusterIndex)) + "MHz"
                            append(whiteBoldSpan(title))

                            val otherInfos = StringBuilder("")
                            for (core in cluster) {
                                val coreIndex = core.toInt()
                                otherInfos.append("\n")
                                val load = loads[coreIndex]

                                val loadStr = if (load != null) "${load.toInt()}%" else "×"
                                otherInfos.append(loadStr.padStart(4))
                                otherInfos.append("  ")

                                val cyclesMhz = perCoreCycles?.getOrNull(coreIndex)
                                val displayMhz = if (cyclesMhz != null && cyclesMhz > 0) {
                                    cyclesMhz
                                } else {
                                    val coreFreq = subFreqStr(cpuFrequencyUtils.getCoreCurrentFrequency(core)).toIntOrNull()
                                    val loadInt = load?.toInt() ?: 0
                                    if (coreFreq != null) coreFreq * loadInt / 100 else null
                                }

                                val mhzStr = if (displayMhz != null) "${displayMhz}M" else "--"
                                otherInfos.append(mhzStr)
                            }
                            append(otherInfos.toString())
                        } catch (ex: Exception) {
                        }
                    }
                }

                fpsUtils.currentFps?.run {
                    append("\n")
                    append(whiteBoldSpan("#FPS  $this"))
                }

                batteryCurrentNowMa?.run {
                    if (this > -20000 && this < 20000 && BatteryStatusReader.batteryVoltage > 0) {
                        val watts = (this * BatteryStatusReader.batteryVoltage) / 1000.0
                        val formatted = if (watts >= 100 || watts <= -100) {
                            String.format("%+.1fW", watts)
                        } else {
                            String.format("%+.2fW", watts)
                        }
                        append("\n")
                        append(whiteBoldSpan("#PWR  $formatted"))
                    }
                }
            }
        }

        val temperature = BatteryStatusReader.temperatureCurrent

        myHandler.post {
            // Guarded: an exception here runs on the main thread and would
            // crash the whole app (views may already be detached mid-hide).
            try {
                if (showOtherInfo) {
                    otherInfo?.text = null
                    otherInfo?.text = otherInfoBuilder
                }

                cpuChart!!.setData(100f, (100 - cpuLoad).toFloat())
                cpuFreqText!!.text = subFreqStr(cpuFreq) + "Mhz"

                gpuFreqText!!.text = gpuFreq
                if (gpuLoad > -1) {
                    gpuChart!!.setData(100f, (100f - gpuLoad))
                }

                temperatureChart!!.setData(100.0, 100.0 - BatteryStatusReader.batteryCapacity, temperature)
                temperatureText!!.text = "$temperature°C"
                batteryLevelText!!.text = "${BatteryStatusReader.batteryCapacity}%"
                chargerView!!.visibility = if (BatteryStatusReader.batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            } catch (ex: Exception) {
            }
        }
    }

    private fun startTimer() {
        stopTimer()
        // Detailed mode benefits from faster updates; compact mode is fine at
        // 2s, cutting the number of shell round trips by a quarter.
        val interval = if (showOtherInfo) 1500L else 2000L
        timer = Timer()
        timer!!.schedule(object : TimerTask() {
            override fun run() {
                // java.util.Timer permanently dies on an uncaught exception in
                // a task — the HUD would silently freeze. Never let one tick
                // kill all future updates.
                try {
                    updateInfo()
                } catch (ex: Exception) {
                }
            }
        }, 0, interval)
    }

    fun hidePopupWindow() {
        stopTimer()
        unregisterScreenReceiver()

        Thread { CpuCyclesUtils.stopStream() }.start()
        if (show && mView != null) {
            try {
                mWindowManager?.removeViewImmediate(mView)
            } catch (ex: Exception) {
            }
            mView = null
            show = false
        }
    }

    @SuppressLint("ApplySharedPref", "ClickableViewAccessibility")
    private fun setUpView(context: Context): View {
        view = LayoutInflater.from(context).inflate(R.layout.fw_monitor, null)

        cpuChart = view!!.findViewById(R.id.fw_cpu_load)
        gpuChart = view!!.findViewById(R.id.fw_gpu_load)
        temperatureChart = view!!.findViewById(R.id.fw_battery_chart)

        cpuFreqText = view!!.findViewById(R.id.fw_cpu_freq)
        gpuFreqText = view!!.findViewById(R.id.fw_gpu_freq)
        temperatureText = view!!.findViewById(R.id.fw_battery_temp)
        batteryLevelText = view!!.findViewById(R.id.fw_battery_level)
        chargerView = view!!.findViewById(R.id.fw_charger)
        otherInfo = view!!.findViewById(R.id.fw_other_info)

        activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        otherInfo?.typeface = try {
            android.graphics.Typeface.createFromFile("/system/fonts/DroidSansMono.ttf")
        } catch (ex: Exception) {
            android.graphics.Typeface.MONOSPACE
        }

        view!!.setOnClickListener {
            try {
                otherInfo?.visibility = if (showOtherInfo) View.GONE else View.VISIBLE
                it.findViewById<LinearLayout>(R.id.fw_chart_list).orientation =
                    if (showOtherInfo) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                (mView as LinearLayout).orientation =
                    if (showOtherInfo) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                showOtherInfo = !showOtherInfo
                if (!showOtherInfo) {
                    // Collapsed back to compact: the simpleperf stream is only
                    // needed for the per-core view — without this it kept
                    // running at 2 Hz until the overlay was fully hidden.
                    Thread { CpuCyclesUtils.stopStream() }.start()
                }
                // Re-arm the timer so the interval matches the new mode.
                startTimer()
            } catch (ex: Exception) {
            }
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
