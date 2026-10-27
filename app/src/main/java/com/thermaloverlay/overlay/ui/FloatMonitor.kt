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
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.text.TextPaint
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
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

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

    /** The detailed view was requested: the poller builds the detailed text. */
    @Volatile
    private var showOtherInfo = false

    /** Main thread: last detailed text, shown at once when the view is opened again. */
    private var lastDetails: HudDetails? = null

    /** CPU groups seen by the last tick; lets the placeholder have the final shape. */
    @Volatile
    private var layoutHint: List<List<Int>> = emptyList()

    // Detailed mode needs a few slow sources (simpleperf start, FPS via
    // dumpsys). They run on their own thread and the tick only reads their
    // latest result, so a slow source can't hold up the HUD.
    private var slowExecutor: ScheduledExecutorService? = null
    private val slowBusy = AtomicBoolean(false)

    @Volatile
    private var lastFps: String? = null

    // Rows that appeared once stay (as "--" when the value is briefly
    // unavailable) so the detailed view never changes height by itself.
    // Poller thread only.
    @Volatile
    private var rowDdr = false

    @Volatile
    private var rowGmem = false

    @Volatile
    private var rowPwr = false


    private var defaultInfoMinWidth = 0

    // Paints of the detailed text view (set before the poller starts).
    @Volatile
    private var hudMetrics: HudMetrics? = null

    // The window starts fully transparent and is revealed by the first
    // snapshot (or after REVEAL_FALLBACK_MS), so the user never sees the
    // not-yet-filled first frame. Main thread only.
    private var revealPending = false
    private val revealRunnable = Runnable { revealIfPending() }

    // Values the cheap "preview" text reuses while the HUD is compact, so the
    // detailed view can show real numbers on the very frame it opens.
    // Poller thread only.
    private var cachedGmem: String? = null
    private var cachedDdr: String = ""
    private var cachedSwap = 0
    private var cachedTemp = "--"
    private var cacheAt = 0L

    // Last shape written to the preferences (poller thread only).
    private var savedShape = ""

    /** The state the HUD was in before the tap that began a possible double tap. */
    private var expandedBeforeTap = false

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
        // Invisible until the first snapshot has filled it in.
        view.alpha = 0f

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

        // Restore the last mode (and the shape of the detailed text) before
        // the first tick, so the HUD opens the way it was left.
        restoreShape(storage)
        lastDetails = null
        if (storage.getBoolean(KEY_EXPANDED, false)) {
            showOtherInfo = true
            otherInfo?.let { tv ->
                val d = buildPlaceholder()
                tv.text = d.text
                keepWidthMonotonic(d.width)
            }
            applyDetailedLayout()
        }
        revealPending = true
        mainHandler.postDelayed(revealRunnable, REVEAL_FALLBACK_MS)

        // A saved position can be off-screen after a rotation / resolution
        // change; pull the window back as soon as it has a size.
        clampAfterLayout()

        executor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "HudPoller").apply { isDaemon = true }
        }
        slowExecutor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "HudSlowSources").apply { isDaemon = true }
        }
        // Kill a simpleperf stream orphaned by a previous process death
        // before this session can start its own.
        executor?.execute { CpuCyclesUtils.cleanupOrphans() }
        restartTicking()
        // Resolve the one-time probes the detailed view needs (thermal zones,
        // DDR / GPU memory nodes) shortly after start, while the user is still
        // looking at the compact view, so the first switch to detailed is quick.
        try {
            executor?.schedule(Runnable { prewarmDetails() }, PREWARM_DELAY_MS, TimeUnit.MILLISECONDS)
        } catch (ignored: Exception) {
        }
        registerScreenReceiver()
        return true
    }

    fun hidePopupWindow() {
        if (!shown) return
        shown = false

        unregisterScreenReceiver()
        tickFuture?.cancel(false)
        tickFuture = null

        // Shutdown order matters: the poller finishes its last tick first, and
        // only then the slow thread (which may be starting simpleperf) stops
        // the root-side stream and gives the idle root shells back. Doing it
        // the other way round could leave a stream running behind our back.
        val slow = slowExecutor
        executor?.let { ex ->
            try {
                ex.execute {
                    try {
                        slow?.execute {
                            try {
                                CpuCyclesUtils.stopStream()
                                KeepShellPublic.releaseIdleShells()
                            } catch (ignored: Exception) {
                            }
                        }
                    } catch (ignored: RejectedExecutionException) {
                    }
                    slow?.shutdown()
                }
            } catch (ignored: Exception) {
                slow?.shutdown()
            }
            ex.shutdown()
        }
        executor = null
        slowExecutor = null
        // In memory only: the remembered mode stays in the preferences.
        showOtherInfo = false
        mainHandler.removeCallbacks(revealRunnable)
        revealPending = false

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
        // No monospace font: many ROMs map "monospace" to their proportional
        // system font. The columns are pixel-measured (HudTextGrid), so
        // whatever font the TextView really draws with is the one measured.
        otherInfo?.let { hudMetrics = HudMetrics(it.paint) }
        defaultInfoMinWidth = otherInfo?.minWidth ?: 0
        return view
    }

    /**
     * Switches between the compact ring view and the detailed text view, at
     * once, on the tap itself (as the first version did). Opening the detailed
     * view shows the last known text, or a placeholder with the same shape
     * (same rows and column widths, "--" instead of numbers), so the window
     * has its final size immediately and the real numbers just replace the
     * dashes a moment later.
     */
    private fun toggleMode() {
        if (showOtherInfo) {
            showOtherInfo = false
            applyCompactLayout()
            lastFps = null
            // The simpleperf stream is only needed for the per-core view.
            slowExecute { CpuCyclesUtils.stopStream() }
        } else {
            lastFps = null
            showOtherInfo = true
            otherInfo?.let { tv ->
                val d = lastDetails ?: buildPlaceholder()
                tv.text = d.text
                keepWidthMonotonic(d.width)
            }
            applyDetailedLayout()
        }
        saveExpanded(showOtherInfo)
        // Re-arm (with no initial delay) so fresh numbers arrive right away
        // and the interval matches the new mode.
        restartTicking()
    }

    private fun saveExpanded(expanded: Boolean) {
        context.getSharedPreferences(POSITION_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_EXPANDED, expanded).apply()
    }

    private fun revealIfPending() {
        if (!revealPending) return
        revealPending = false
        mainHandler.removeCallbacks(revealRunnable)
        rootView?.alpha = 1f
    }

    /** Remembers the CPU grouping and optional rows, so the next start has the final shape at once. */
    private fun persistShape() {
        val sig = layoutHint.joinToString(";") { it.joinToString(",") } +
            "|" + (if (rowDdr) 1 else 0) + (if (rowGmem) 1 else 0) + (if (rowPwr) 1 else 0)
        if (sig == savedShape) return
        savedShape = sig
        context.getSharedPreferences(POSITION_PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SHAPE, sig).apply()
    }

    /** Inverse of [persistShape]; the first tick replaces it with measured data anyway. */
    private fun restoreShape(storage: android.content.SharedPreferences) {
        val sig = storage.getString(KEY_SHAPE, null) ?: return
        savedShape = sig
        val parts = sig.split('|')
        try {
            layoutHint = parts[0].split(';').filter { it.isNotEmpty() }
                .map { g -> g.split(',').map { it.toInt() } }
            val rows = parts.getOrNull(1) ?: ""
            rowDdr = rows.getOrNull(0) == '1'
            rowGmem = rows.getOrNull(1) == '1'
            rowPwr = rows.getOrNull(2) == '1'
        } catch (ignored: Exception) {
            layoutHint = emptyList()
        }
    }

    private fun newGrid(groups: List<List<Int>>): HudTextGrid {
        val m = hudMetrics ?: HudMetrics(TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = 20f })
        val labels = groups.filter { it.isNotEmpty() }.map { "#${it.first()}~${it.last()}" }
        return HudTextGrid(m, labels)
    }

    /** Same rows and columns as [buildDetails], with "--" for every value. */
    private fun buildPlaceholder(): HudDetails {
        val dash = "--"
        val g = newGrid(layoutHint)
        g.ramRow(dash, dash)
        if (rowDdr) g.mutedRow(LABEL_DDR, dash)
        if (rowGmem) g.boldRow(LABEL_GMEM, dash)
        g.boldRow(LABEL_CPU, dash)
        for (cluster in layoutHint) {
            if (cluster.isEmpty()) continue
            g.clusterHeader("#${cluster.first()}~${cluster.last()}", dash)
            repeat(cluster.size) { g.coreRow(dash, dash) }
        }
        if (rowPwr) g.boldRow(LABEL_PWR, dash)
        g.boldRow(LABEL_FPS, dash)
        return g.build()
    }

    private fun applyDetailedLayout() {
        otherInfo?.visibility = View.VISIBLE
        chartList?.orientation = LinearLayout.VERTICAL
        rootView?.orientation = LinearLayout.HORIZONTAL
        clampAfterLayout()
    }

    private fun applyCompactLayout() {
        otherInfo?.let {
            it.visibility = View.GONE
            // The detailed view only ever widens while it is shown (so it
            // can't wobble); start from the default again next time.
            it.minWidth = defaultInfoMinWidth
        }
        chartList?.orientation = LinearLayout.HORIZONTAL
        rootView?.orientation = LinearLayout.VERTICAL
        clampAfterLayout()
    }

    private fun slowExecute(block: () -> Unit) {
        try {
            slowExecutor?.execute {
                try {
                    block()
                } catch (ignored: Exception) {
                }
            }
        } catch (ignored: RejectedExecutionException) {
        }
    }

    /** Poller thread: hands the slow sources to their own thread, one at a time. */
    private fun refreshSlowSources() {
        if (!slowBusy.compareAndSet(false, true)) return
        try {
            slowExecutor?.execute {
                try {
                    CpuCyclesUtils.ensureStarted()
                    lastFps = if (showOtherInfo) fpsUtils.currentFps else null
                } catch (ignored: Exception) {
                } finally {
                    slowBusy.set(false)
                }
            } ?: slowBusy.set(false)
        } catch (ex: RejectedExecutionException) {
            slowBusy.set(false)
        }
    }

    private fun refreshDetailCache() {
        cachedGmem = GpuUtils.getMemoryUsage()
        cachedSwap = SwapUtils.getZramFillPercent()
        cachedDdr = cpuFrequencyUtils.getDdrFrequency()
        cachedTemp = cpuLoadUtils.getCpuTemperatureText()
        cacheAt = SystemClock.elapsedRealtime()
    }

    /** Poller thread. Fills the one-time probes and the preview cache. */
    private fun prewarmDetails() {
        if (!shown) return
        refreshDetailCache()
        // Rows that exist on this device are known before the first tap, so
        // the placeholder already contains them.
        if (cachedDdr.isNotEmpty()) rowDdr = true
        if (cachedGmem != null) rowGmem = true
        if (computePowerText() != null) rowPwr = true
        persistShape()
    }

    private fun stopOverlay() {
        // Same path as the Stop button / notification action, so the shared
        // state is cleaned up by OverlayService.onDestroy().
        OverlayService.requestStop(context)
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

        // A tap acts on touch-up, immediately — waiting to find out whether it
        // is the first half of a double tap (GestureDetector's confirm delay)
        // added a visible lag. Two taps within DOUBLE_TAP_MS close the overlay.
        private var lastTapAt = 0L

        private fun onTap(now: Long) {
            if (lastTapAt != 0L && now - lastTapAt < DOUBLE_TAP_MS) {
                lastTapAt = 0L
                // The first tap of this double tap already switched the mode;
                // closing must not leave that accidental switch remembered.
                saveExpanded(expandedBeforeTap)
                stopOverlay()
            } else {
                lastTapAt = now
                expandedBeforeTap = showOtherInfo
                toggleMode()
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
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
                    if (dragging) {
                        savePosition(storage)
                    } else if (event.eventTime - event.downTime < TAP_MAX_MS) {
                        onTap(event.eventTime)
                    }
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
                        slowExecute { CpuCyclesUtils.stopStream() }
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
        val details: HudDetails?,
    )

    /** kHz -> whole MHz text, "--" if unknown. */
    private fun mhzText(khz: Long): String = if (khz > 0) (khz / 1000).toString() else "--"

    private fun updateInfo() {
        if (!shown) return

        if (coreCount < 1) coreCount = cpuFrequencyUtils.getCoreCount()
        // Re-scanned on every tick while some CPUs are offline (the kernel
        // drops the cpufreq dir of an offline cluster) so they show up the
        // moment they come back.
        val scan = cpuFrequencyUtils.getClusters()
        layoutHint = if (scan.missing.isEmpty()) scan.clusters else scan.clusters + listOf(scan.missing)

        // Frequencies are sampled FIRST, before anything that starts a
        // process. Reading /proc/stat usually goes through `su` + `cat`; a
        // freshly forked task makes the scheduler ramp its cluster up for a
        // few milliseconds, so sampling right after it would show the
        // overlay's own load (e.g. the raised max) instead of the idle state.
        val clusterFreqs = scan.clusters.map { cpuFrequencyUtils.getClusterFrequencyKHz(it) }
        val maxFreqKhz = clusterFreqs.maxOrNull() ?: -1L
        val gpuFreq = GpuUtils.getGpuFreq()

        val load = cpuLoadUtils.sample()
        val loads = load?.perCore ?: emptyMap()
        if (load != null) {
            val online = loads.keys
            val previouslyOnline = lastOnlineCores
            if (previouslyOnline != null && previouslyOnline != online) {
                // A core was hot-(un)plugged by someone: simpleperf opens its
                // counters once at launch and would never see a re-onlined
                // core, so relaunch it (lazily, on the next detailed tick).
                slowExecute { CpuCyclesUtils.restartAfterCoreHotplug() }
            }
            lastOnlineCores = HashSet(online)
        }

        val gpuLoad = GpuUtils.getGpuLoad()

        BatteryStatusReader.update(context)

        // Detailed mode: the full text. Compact mode: a cheap preview built
        // from cached values, so tapping to expand shows real numbers on the
        // very first frame instead of dashes.
        val detailed = showOtherInfo
        if (detailed) refreshSlowSources()
        val details = buildDetails(scan, clusterFreqs, loads, preview = !detailed)
        persistShape()

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

    private fun pct(v: Int): String = "$v%"

    private fun buildDetails(
        scan: com.thermaloverlay.overlay.metrics.ClusterScan,
        clusterFreqs: List<Long>,
        loads: Map<Int, Double>,
        preview: Boolean,
    ): HudDetails {
        activityManager.getMemoryInfo(memInfo)
        val totalMem = (memInfo.totalMem / 1024 / 1024f).toInt()
        val availMem = (memInfo.availMem / 1024 / 1024f).toInt()
        val ramPercent = if (totalMem > 0) (totalMem - availMem) * 100 / totalMem else 0

        // GPU memory, zram, DDR and temperature cost a file / shell read each.
        // The full text reads them every tick; the compact-mode preview reuses
        // the values from prewarmDetails() and refreshes them only every
        // PREVIEW_REFRESH_MS, so the first compact tick (which reveals the
        // window) stays as fast as before.
        val now = SystemClock.elapsedRealtime()
        if (!preview || (cacheAt != 0L && now - cacheAt > PREVIEW_REFRESH_MS)) refreshDetailCache()
        val gpuMemoryUsage = cachedGmem
        val swapPercent = cachedSwap
        val ddrFreq = cachedDdr

        // Rows that appeared once stay (as "--"), so the view never changes height by itself.
        if (ddrFreq.isNotEmpty()) rowDdr = true
        if (gpuMemoryUsage != null) rowGmem = true

        // CPUs that belong to no known policy (a fully offline cluster) get a
        // group of their own, so they are visible even before the kernel
        // reveals their cluster.
        val groups = ArrayList<List<Int>>(scan.clusters)
        if (scan.missing.isNotEmpty()) groups.add(scan.missing)

        val g = newGrid(groups)
        g.ramRow("$ramPercent%", "$swapPercent%")
        if (rowDdr) g.mutedRow(LABEL_DDR, ddrFreq.ifEmpty { "--" })
        if (rowGmem) g.boldRow(LABEL_GMEM, gpuMemoryUsage ?: "--")
        g.boldRow(LABEL_CPU, cachedTemp)

        // Only reads the latest value; the stream itself is started by the
        // slow-sources thread.
        val perCoreCycles = if (preview) null else CpuCyclesUtils.readLatest()

        for ((groupIndex, cluster) in groups.withIndex()) {
            if (cluster.isEmpty()) continue

            val freqKhz = clusterFreqs.getOrNull(groupIndex) ?: -1L
            val freqText = if (freqKhz > 0) mhzText(freqKhz) + "MHz" else "--MHz"
            g.clusterHeader("#${cluster.first()}~${cluster.last()}", freqText)

            for (core in cluster) {
                val coreLoad = loads[core]

                // null means the core is offline (it disappears from
                // /proc/stat): show "×" in both columns, skip the freq read.
                if (coreLoad == null) {
                    g.coreRow("×", "×")
                    continue
                }

                val cyclesMhz = perCoreCycles?.getOrNull(core)
                val displayMhz = if (cyclesMhz != null && cyclesMhz > 0) {
                    cyclesMhz
                } else {
                    // The preview has no per-core reads: it estimates from the
                    // cluster frequency, which the tick has already sampled.
                    val khz = if (preview) freqKhz else cpuFrequencyUtils.getCoreFrequencyKHz(core)
                    if (khz > 0) (khz / 1000).toInt() * coreLoad.toInt() / 100 else null
                }
                g.coreRow(pct(coreLoad.toInt()), if (displayMhz != null) "${displayMhz}M" else "--")
            }
        }

        // Power, then FPS last. The FPS row is always there ("--" until the
        // first value, which takes a second or so): whether a device has FPS
        // can't be known before a measurement, and a row that appears only
        // then makes the whole view jump.
        val powerText = computePowerText()
        if (powerText != null) rowPwr = true
        if (rowPwr) g.boldRow(LABEL_PWR, powerText ?: "--")
        g.boldRow(LABEL_FPS, lastFps ?: "--")
        return g.build()
    }

    /** Battery power as text ("+3.20W"), or null when current/voltage are unusable. */
    private fun computePowerText(): String? {
        val currentNow = batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: return null
        val multiplier = if (OverlayPrefs.isDualBattery(context)) 2 else 1
        val ma = (currentNow / BatteryStatusReader.currentUnitDivisor) * multiplier
        val volts = BatteryStatusReader.batteryVoltage
        if (ma <= -20000 || ma >= 20000 || volts <= 0) return null
        val watts = (ma * volts) / 1000.0
        val pattern = if (abs(watts) >= 100) "%+.1fW" else "%+.2fW"
        return String.format(Locale.US, pattern, watts)
    }

    /** The detailed text only ever gets wider while shown, so it can't wobble. */
    private fun keepWidthMonotonic(contentWidth: Int) {
        val tv = otherInfo ?: return
        val needed = contentWidth + tv.paddingLeft + tv.paddingRight
        if (needed > tv.minWidth) tv.minWidth = needed
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
        // The text is remembered in both modes (in compact mode it is the
        // cheap preview), so opening the detailed view has real numbers ready.
        if (s.details != null) {
            lastDetails = s.details
            if (showOtherInfo) {
                otherInfo?.text = s.details.text
                keepWidthMonotonic(s.details.width)
            }
        }
        revealIfPending()
    }

    private companion object {
        const val LABEL_DDR = " DDR"
        const val LABEL_GMEM = "#GMEM"
        const val LABEL_CPU = "#CPU"
        const val LABEL_PWR = "#PWR"
        const val LABEL_FPS = "#FPS"

        const val POSITION_PREFS = "float_monitor_storage"

        // A touch shorter than this is a tap; two taps closer than this close the HUD.
        const val TAP_MAX_MS = 250L
        const val DOUBLE_TAP_MS = 300L

        // Delay before the one-time detailed-view probes run in the background.
        const val PREWARM_DELAY_MS = 1500L

        const val KEY_EXPANDED = "expanded"
        const val KEY_SHAPE = "shape"

        // The window stays transparent at most this long waiting for the first snapshot.
        const val REVEAL_FALLBACK_MS = 1500L

        // In compact mode the cached slow values of the preview are refreshed this often.
        const val PREVIEW_REFRESH_MS = 10_000L
    }
}
