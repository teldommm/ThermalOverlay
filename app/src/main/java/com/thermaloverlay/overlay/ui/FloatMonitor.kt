/**
 * The floating HUD window: layout, drag handling, tap gestures, and periodic
 * metrics polling.
 *
 * Threading: all metric reads run on ONE single-thread executor, so two ticks
 * can never overlap (re-arming the schedule just queues behind a running tick).
 * Views are only touched on the main thread.
 */
package com.thermaloverlay.overlay.ui

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
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
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.thermaloverlay.overlay.OverlayPrefs
import com.thermaloverlay.overlay.OverlayService
import com.thermaloverlay.overlay.R
import com.thermaloverlay.overlay.metrics.BatteryStatusReader
import com.thermaloverlay.overlay.metrics.CpuCyclesUtils
import com.thermaloverlay.overlay.metrics.CpuFrequencyUtils
import com.thermaloverlay.overlay.metrics.CpuLoadUtils
import com.thermaloverlay.overlay.metrics.FpsUtils
import com.thermaloverlay.overlay.metrics.GpuUtils
import com.thermaloverlay.overlay.metrics.SwapUtils
import com.thermaloverlay.overlay.shell.KeepShellPublic
import com.thermaloverlay.overlay.utils.WindowCompatHelper
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class FloatMonitor(private val context: Context) {
    private val cpuLoadUtils = CpuLoadUtils()
    private val cpuFrequencyUtils = CpuFrequencyUtils()
    private val fpsUtils = FpsUtils()

    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var rootView: LinearLayout? = null
    private var params: LayoutParams? = null

    @Volatile
    private var shown = false

    @Volatile
    private var showOtherInfo = false

    private var executor: ScheduledExecutorService? = null
    private var tickFuture: ScheduledFuture<*>? = null
    private var screenReceiver: BroadcastReceiver? = null

    // Views
    private var cpuChart: FloatMonitorChartView? = null
    private var cpuFreqText: TextView? = null
    private var gpuChart: FloatMonitorChartView? = null
    private var gpuFreqText: TextView? = null
    private var batteryChart: FloatMonitorBatteryView? = null
    private var batteryTempText: TextView? = null
    private var batteryLevelText: TextView? = null
    private var chargerView: ImageView? = null
    private var otherInfo: TextView? = null
    private var chartList: LinearLayout? = null

    // Only touched from the executor thread.
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val memInfo = ActivityManager.MemoryInfo()
    private var coreCount = -1
    private var lastOnlineCores: Set<Int>? = null

    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    // --- show / hide ---

    @SuppressLint("ClickableViewAccessibility", "RtlHardcoded")
    fun showPopupWindow(): Boolean {
        if (shown) return true

        if (!Settings.canDrawOverlays(context)) {
            Toast.makeText(context, context.getString(R.string.permission_float), Toast.LENGTH_LONG).show()
            return false
        }

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = inflateView()
        val storage = context.getSharedPreferences(POSITION_PREFS, Context.MODE_PRIVATE)

        val lp = LayoutParams().apply {
            type = WindowCompatHelper.overlayWindowType()
            format = PixelFormat.TRANSLUCENT
            width = LayoutParams.WRAP_CONTENT
            height = LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.LEFT
            x = storage.getInt("x", 0)
            y = storage.getInt("y", 0)
            @Suppress("DEPRECATION")
            flags = LayoutParams.FLAG_NOT_TOUCH_MODAL or LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_FULLSCREEN
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        try {
            wm.addView(view, lp)
        } catch (ex: Exception) {
            // e.g. the overlay permission was revoked while the service was
            // running. Nothing was added, so there is nothing to clean up.
            Toast.makeText(
                context,
                context.getString(R.string.overlay_error, ex.message ?: ""),
                Toast.LENGTH_LONG
            ).show()
            return false
        }

        windowManager = wm
        rootView = view
        params = lp
        shown = true

        view.setOnTouchListener(HudTouchListener(lp, storage))
        // A saved position can be off-screen after a rotation / resolution
        // change; pull the window back as soon as it has a size.
        clampAfterLayout()

        executor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "HudPoller").apply { isDaemon = true }
        }
        // Kill a simpleperf stream orphaned by a previous process death
        // before this session can start its own.
        executor?.execute { CpuCyclesUtils.cleanupOrphans() }
        restartTicking()
        registerScreenReceiver()
        return true
    }

    fun hidePopupWindow() {
        if (!shown) return
        shown = false

        unregisterScreenReceiver()
        tickFuture?.cancel(false)
        tickFuture = null

        // Last job on the poller thread: stop the root-side stream and give
        // back the idle root shells, then let the thread end.
        executor?.let { ex ->
            try {
                ex.execute {
                    CpuCyclesUtils.stopStream()
                    KeepShellPublic.releaseIdleShells()
                }
            } catch (ignored: Exception) {
            }
            ex.shutdown()
        }
        executor = null

        try {
            rootView?.let { windowManager?.removeViewImmediate(it) }
        } catch (ignored: Exception) {
        }
        rootView = null
        params = null
        windowManager = null
        clearViewRefs()
    }

    private fun clearViewRefs() {
        cpuChart = null
        cpuFreqText = null
        gpuChart = null
        gpuFreqText = null
        batteryChart = null
        batteryTempText = null
        batteryLevelText = null
        chargerView = null
        otherInfo = null
        chartList = null
    }

    /** Call when the display configuration (rotation, size) changed. */
    fun onConfigurationChanged() {
        if (shown) clampAfterLayout()
    }

    // --- view ---

    @SuppressLint("InflateParams")
    private fun inflateView(): LinearLayout {
        val view = LayoutInflater.from(context).inflate(R.layout.fw_monitor, null) as LinearLayout
        cpuChart = view.findViewById(R.id.fw_cpu_load)
        gpuChart = view.findViewById(R.id.fw_gpu_load)
        batteryChart = view.findViewById(R.id.fw_battery_chart)
        cpuFreqText = view.findViewById(R.id.fw_cpu_freq)
        gpuFreqText = view.findViewById(R.id.fw_gpu_freq)
        batteryTempText = view.findViewById(R.id.fw_battery_temp)
        batteryLevelText = view.findViewById(R.id.fw_battery_level)
        chargerView = view.findViewById(R.id.fw_charger)
        otherInfo = view.findViewById(R.id.fw_other_info)
        chartList = view.findViewById(R.id.fw_chart_list)
        otherInfo?.typeface = Typeface.MONOSPACE
        return view
    }

    /** Switches between the compact ring view and the detailed text view. */
    private fun toggleMode() {
        val detailed = !showOtherInfo
        showOtherInfo = detailed
        otherInfo?.visibility = if (detailed) View.VISIBLE else View.GONE
        chartList?.orientation = if (detailed) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        rootView?.orientation = if (detailed) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        if (!detailed) {
            // The simpleperf stream is only needed for the per-core view.
            executor?.execute { CpuCyclesUtils.stopStream() }
        }
        // Re-arm so the interval matches the new mode.
        restartTicking()
        clampAfterLayout()
    }

    private fun stopOverlay() {
        // Same path as the Stop button / notification action, so the shared
        // state is cleaned up by OverlayService.onDestroy().
        context.stopService(Intent(context, OverlayService::class.java))
    }

    // --- touch ---

    private inner class HudTouchListener(
        private val lp: LayoutParams,
        private val storage: android.content.SharedPreferences,
    ) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        // Single tap (confirmed, i.e. not the first half of a double tap)
        // toggles the layout; double tap closes the overlay. Detecting them
        // together is what stops a double tap from also flipping the layout.
        private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleMode()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                stopOverlay()
                return true
            }
        })

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            detector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        clampParams()
                        try {
                            windowManager?.updateViewLayout(v, lp)
                        } catch (ignored: Exception) {
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) savePosition(storage)
                    dragging = false
                }
                MotionEvent.ACTION_CANCEL -> dragging = false
            }
            return true
        }
    }

    private fun savePosition(storage: android.content.SharedPreferences) {
        val lp = params ?: return
        storage.edit().putInt("x", lp.x).putInt("y", lp.y).apply()
    }

    private fun screenSize(): Point {
        val wm = windowManager ?: return Point(0, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            val p = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            p
        }
    }

    /** Keeps the window inside the screen. Returns true if it moved. */
    private fun clampParams(): Boolean {
        val lp = params ?: return false
        val view = rootView ?: return false
        if (view.width == 0 || view.height == 0) return false
        val screen = screenSize()
        if (screen.x == 0 || screen.y == 0) return false
        val nx = lp.x.coerceIn(0, maxOf(0, screen.x - view.width))
        val ny = lp.y.coerceIn(0, maxOf(0, screen.y - view.height))
        val moved = nx != lp.x || ny != lp.y
        lp.x = nx
        lp.y = ny
        return moved
    }

    private fun clampAfterLayout() {
        val view = rootView ?: return
        view.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnGlobalLayoutListener(this)
                if (!shown) return
                if (clampParams()) {
                    try {
                        windowManager?.updateViewLayout(view, params)
                    } catch (ignored: Exception) {
                    }
                    context.getSharedPreferences(POSITION_PREFS, Context.MODE_PRIVATE).let { savePosition(it) }
                }
            }
        })
    }

    // --- screen on/off ---

    // The overlay is invisible while the screen is off, but polling kept the
    // root shell (and dumpsys/simpleperf in detailed mode) busy, preventing
    // the device from resting. Pause everything on SCREEN_OFF.
    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        tickFuture?.cancel(false)
                        tickFuture = null
                        executor?.execute { CpuCyclesUtils.stopStream() }
                    }
                    Intent.ACTION_SCREEN_ON -> if (shown) restartTicking()
                }
            }
        }
        try {
            context.registerReceiver(receiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            })
            screenReceiver = receiver
        } catch (ex: Exception) {
            screenReceiver = null
        }
    }

    private fun unregisterScreenReceiver() {
        screenReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (ignored: Exception) {
            }
        }
        screenReceiver = null
    }

    // --- polling ---

    private fun restartTicking() {
        val ex = executor ?: return
        tickFuture?.cancel(false)
        // Detailed mode benefits from faster updates; compact mode is fine at 2s.
        val interval = if (showOtherInfo) 1500L else 2000L
        try {
            tickFuture = ex.scheduleWithFixedDelay({
                // An uncaught exception would silently end the periodic task
                // and freeze the HUD, so a tick must never throw.
                try {
                    updateInfo()
                } catch (ignored: Exception) {
                }
            }, 0, interval, TimeUnit.MILLISECONDS)
        } catch (ignored: Exception) {
            // executor already shut down
        }
    }

    private class Snapshot(
        val cpuLoad: Int,
        val cpuFreqText: String,
        val gpuFreqText: String,
        val gpuLoad: Int,
        val batteryLevel: Int,
        val batteryTemp: Double,
        val charging: Boolean,
        val details: CharSequence?,
    )

    private fun whiteBoldSpan(text: String): SpannableString {
        return SpannableString(text).apply {
            setSpan(ForegroundColorSpan(android.graphics.Color.WHITE), 0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), 0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** kHz -> whole MHz text, "--" if unknown. */
    private fun mhzText(khz: Long): String = if (khz > 0) (khz / 1000).toString() else "--"

    private fun updateInfo() {
        if (!shown) return

        if (coreCount < 1) coreCount = cpuFrequencyUtils.getCoreCount()
        // Re-scanned on every tick while some CPUs are offline (the kernel
        // drops the cpufreq dir of an offline cluster) so they show up the
        // moment they come back.
        val scan = cpuFrequencyUtils.getClusters()

        val load = cpuLoadUtils.sample()
        val loads = load?.perCore ?: emptyMap()
        if (load != null) {
            val online = loads.keys
            val previouslyOnline = lastOnlineCores
            if (previouslyOnline != null && previouslyOnline != online) {
                // A core was hot-(un)plugged by someone: simpleperf opens its
                // counters once at launch and would never see a re-onlined
                // core, so relaunch it (lazily, on the next detailed tick).
                CpuCyclesUtils.restartAfterCoreHotplug()
            }
            lastOnlineCores = HashSet(online)
        }

        val clusterFreqs = scan.clusters.map { cpuFrequencyUtils.getClusterFrequencyKHz(it) }
        val maxFreqKhz = clusterFreqs.maxOrNull() ?: -1L

        val gpuFreq = GpuUtils.getGpuFreq()
        val gpuLoad = GpuUtils.getGpuLoad()

        BatteryStatusReader.update(context)

        val details = if (showOtherInfo) buildDetails(scan, clusterFreqs, loads) else null

        val snapshot = Snapshot(
            cpuLoad = Math.ceil(load?.total ?: 0.0).toInt().coerceIn(0, 100),
            cpuFreqText = (if (maxFreqKhz > 0) "${maxFreqKhz / 1000}" else "0") + "MHz",
            gpuFreqText = if (gpuFreq.isEmpty()) "--MHz" else "${gpuFreq}MHz",
            gpuLoad = gpuLoad,
            batteryLevel = BatteryStatusReader.batteryCapacity,
            batteryTemp = BatteryStatusReader.temperatureCurrent,
            charging = BatteryStatusReader.batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING,
            details = details,
        )
        mainHandler.post { applySnapshot(snapshot) }
    }

    private fun buildDetails(
        scan: com.thermaloverlay.overlay.metrics.ClusterScan,
        clusterFreqs: List<Long>,
        loads: Map<Int, Double>,
    ): CharSequence {
        activityManager.getMemoryInfo(memInfo)
        // Read GPU memory only here: it's a shell call per tick and the value
        // is not shown anywhere in compact mode.
        val gpuMemoryUsage = GpuUtils.getMemoryUsage()
        val totalMem = (memInfo.totalMem / 1024 / 1024f).toInt()
        val availMem = (memInfo.availMem / 1024 / 1024f).toInt()
        val ramPercent = if (totalMem > 0) (totalMem - availMem) * 100 / totalMem else 0
        val swapPercent = SwapUtils.getZramFillPercent()
        val ddrFreq = cpuFrequencyUtils.getDdrFrequency()

        val b = SpannableStringBuilder()
        b.append(whiteBoldSpan("#RAM  $ramPercent%   $swapPercent%"))
        b.append("\n")

        if (ddrFreq.isNotEmpty()) {
            b.append(" DDR  $ddrFreq")
            b.append("\n")
        }

        if (gpuMemoryUsage != null) {
            b.append(whiteBoldSpan("#GMEM $gpuMemoryUsage"))
            b.append("\n")
        }

        b.append(whiteBoldSpan("#CPU  " + cpuLoadUtils.getCpuTemperatureText()))
        b.append("\n")

        val perCoreCycles = CpuCyclesUtils.getPerCoreCyclesMhz()

        // CPUs that belong to no known policy (a fully offline cluster) get a
        // group of their own, so they are visible even before the kernel
        // reveals their cluster.
        val groups = ArrayList<List<Int>>(scan.clusters)
        if (scan.missing.isNotEmpty()) groups.add(scan.missing)

        for ((groupIndex, cluster) in groups.withIndex()) {
            if (groupIndex != 0) b.append("\n")
            if (cluster.isEmpty()) continue

            val freqKhz = clusterFreqs.getOrNull(groupIndex) ?: -1L
            b.append(whiteBoldSpan("#" + cluster.first() + "~" + cluster.last() + "  " + mhzText(freqKhz) + "MHz"))

            val lines = StringBuilder()
            for (core in cluster) {
                lines.append("\n")
                val coreLoad = loads[core]

                // null means the core is offline (it disappears from
                // /proc/stat): show "×" in both columns, skip the freq read.
                if (coreLoad == null) {
                    lines.append("×".padStart(4)).append("  ").append("×")
                    continue
                }

                lines.append("${coreLoad.toInt()}%".padStart(4)).append("  ")

                val cyclesMhz = perCoreCycles?.getOrNull(core)
                val displayMhz = if (cyclesMhz != null && cyclesMhz > 0) {
                    cyclesMhz
                } else {
                    val khz = cpuFrequencyUtils.getCoreFrequencyKHz(core)
                    if (khz > 0) (khz / 1000).toInt() * coreLoad.toInt() / 100 else null
                }
                lines.append(if (displayMhz != null) "${displayMhz}M" else "--")
            }
            b.append(lines.toString())
        }

        fpsUtils.currentFps?.let {
            b.append("\n")
            b.append(whiteBoldSpan("#FPS  $it"))
        }

        val currentNow = batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (currentNow != null) {
            val multiplier = if (OverlayPrefs.isDualBattery(context)) 2 else 1
            val ma = (currentNow / BatteryStatusReader.currentUnitDivisor) * multiplier
            val volts = BatteryStatusReader.batteryVoltage
            if (ma > -20000 && ma < 20000 && volts > 0) {
                val watts = (ma * volts) / 1000.0
                val pattern = if (abs(watts) >= 100) "%+.1fW" else "%+.2fW"
                b.append("\n")
                b.append(whiteBoldSpan("#PWR  " + String.format(Locale.US, pattern, watts)))
            }
        }
        return b
    }

    private fun applySnapshot(s: Snapshot) {
        // The window may have been hidden while this was queued.
        if (!shown) return
        cpuChart?.setPercent(s.cpuLoad)
        cpuFreqText?.text = s.cpuFreqText
        gpuFreqText?.text = s.gpuFreqText
        if (s.gpuLoad > -1) gpuChart?.setPercent(s.gpuLoad)

        batteryChart?.setData(s.batteryLevel, s.batteryTemp)
        batteryTempText?.text =
            if (s.batteryTemp.isNaN()) "--°C" else String.format(Locale.US, "%.1f°C", s.batteryTemp)
        batteryLevelText?.text = "${s.batteryLevel}%"
        chargerView?.visibility = if (s.charging) View.VISIBLE else View.GONE

        // Compare against the live flag: the user may have switched back to
        // compact mode while this snapshot was being computed.
        if (showOtherInfo && s.details != null) {
            otherInfo?.text = s.details
        }
    }

    private companion object {
        const val POSITION_PREFS = "float_monitor_storage"
    }
}
