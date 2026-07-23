/**
 * Small floating pill (top-right) showing live FPS with a record button.
 * Tapping starts a session that logs FPS/CPU/GPU/battery once a second to
 * FpsWatchStore; the session auto-stops if the foreground app changes,
 * the screen turns off, or the battery is connected/disconnected — a
 * charging-state change makes the power/wattage numbers not comparable to
 * the rest of the session, so it ends rather than silently splicing two
 * different regimes together. Ported from the current Scene app's
 * FloatMonitorFPS (renamed at some point from the older FloatFpsWatch).
 *
 * Draggable like the other monitors (same raw-coordinate-anchored touch
 * handling), but — matching the real app exactly — the dragged position is
 * NOT persisted; it resets to the default top-right spot every time the
 * overlay is shown again.
 *
 * Duration presets (5/10/15/30 min): tapping one sets a target tick count;
 * once the running session hits it, the session ends on its own. Picking
 * a preset is optional — recording still starts immediately on tap and
 * can always be stopped manually before (or without) a preset firing.
 *
 * Not carried over from the source on purpose: recordings there also get
 * uploaded to Scene's own backend once they're long enough, plus a
 * separate telemetry report — we have no such backend and no reason to
 * phone home, so that whole path is simply absent here.
 *
 * Design notes: FPS comes from the existing FpsUtils (which already covers
 * an fpsgo-status / SurfaceFlinger-binder-call fallback chain, so there's
 * no need for a near-duplicate class), and app-switch detection is done by
 * polling GlobalStatus.lastPackageName each tick rather than a generic
 * EventBus for one subscriber. The CPU reading uses the same "dominant
 * big core" substitution as the mini monitor: if a big core is pegged
 * above 70% and higher than the average, that's shown instead, since a
 * single saturated core is usually what actually causes the stutter.
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
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.TextView
import android.widget.Toast
import com.thermaloverlay.overlay.OverlayPrefs
import com.thermaloverlay.overlay.R
import com.thermaloverlay.overlay.data.GlobalStatus
import com.thermaloverlay.overlay.metrics.BatteryStatusReader
import com.thermaloverlay.overlay.metrics.CpuCyclesUtils
import com.thermaloverlay.overlay.metrics.CpuFrequencyUtils
import com.thermaloverlay.overlay.metrics.CpuLoadUtils
import com.thermaloverlay.overlay.metrics.FpsUtils
import com.thermaloverlay.overlay.metrics.FrameStatsUtils
import com.thermaloverlay.overlay.metrics.GpuUtils
import com.thermaloverlay.overlay.store.FpsWatchStore
import com.thermaloverlay.overlay.utils.WindowCompatHelper
import java.util.Timer
import java.util.TimerTask

class FloatFpsWatch(private val mContext: Context) {
    private val fpsWatchStore = FpsWatchStore(mContext)
    private val fpsUtils = FpsUtils()
    private val cpuLoadUtils = CpuLoadUtils()
    private val cpuFrequencyUtils = CpuFrequencyUtils()
    private val frameStatsUtils = FrameStatsUtils()
    private var batteryManager: BatteryManager? = null
    private var coreCount = -1

    private var sessionId = -1L
    private var sessionApp: String? = null

    // Ticks since the current session started (1 tick = 1s); compared
    // against targetDurationSeconds to auto-stop on a chosen preset.
    private var tickCount = 0
    private var targetDurationSeconds = 0

    private fun dp2px(context: Context, dpValue: Float): Int {
        val scale = context.resources.displayMetrics.density
        return (dpValue * scale + 0.5f).toInt()
    }

    fun showPopupWindow(): Boolean {
        if (show) return true

        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(mContext)) {
            Toast.makeText(mContext, mContext.getString(R.string.permission_float), Toast.LENGTH_LONG).show()
            return false
        }

        show = true
        mWindowManager = mContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        view = setUpView(mContext)

        val params = LayoutParams()
        params.type = WindowCompatHelper.overlayWindowType()
        params.format = PixelFormat.TRANSLUCENT
        params.width = LayoutParams.WRAP_CONTENT
        params.height = LayoutParams.WRAP_CONTENT
        params.gravity = Gravity.TOP or Gravity.END
        params.x = dp2px(mContext, 10f)
        params.y = dp2px(mContext, 65f)

        @Suppress("DEPRECATION")
        params.flags = LayoutParams.FLAG_NOT_TOUCH_MODAL or LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_FULLSCREEN

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        try {
            mWindowManager!!.addView(view, params)
            mView = view

            // Draggable, but — matching the real app — never persisted:
            // it's always back at the default spot next time this shows.
            view!!.setOnTouchListener(object : View.OnTouchListener {
                private var isTouchDown = false
                private var touchStartRawX = 0f
                private var touchStartRawY = 0f
                private var paramStartX = 0
                private var paramStartY = 0

                @SuppressLint("ClickableViewAccessibility")
                override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                    if (event != null) {
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                touchStartRawX = event.rawX
                                touchStartRawY = event.rawY

                                // params.x starts out measured from the RIGHT
                                // edge (Gravity.END, for the default top-right
                                // spot) — the raw+paramStart formula below
                                // assumes a LEFT-based x like every other
                                // overlay uses, so mixing the two inverts the
                                // horizontal drag direction. The real app hits
                                // the same mismatch and fixes it by switching
                                // to Gravity.START on the first move; do the
                                // same here, converting x to the window's
                                // actual on-screen left edge first so the
                                // switch doesn't jump the view.
                                if (v != null && params.gravity != (Gravity.TOP or Gravity.START)) {
                                    val loc = IntArray(2)
                                    v.getLocationOnScreen(loc)
                                    params.gravity = Gravity.TOP or Gravity.START
                                    params.x = loc[0]
                                }

                                paramStartX = params.x
                                paramStartY = params.y
                                isTouchDown = true
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
                                isTouchDown = false
                                if (moved) return true
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
            show = false
            Toast.makeText(mContext, "FloatFpsWatch Error\n" + ex.message, Toast.LENGTH_LONG).show()
            return false
        }
    }

    private var screenReceiver: BroadcastReceiver? = null

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    // Same reasoning as FloatMonitor: invisible while the
                    // screen is off, but the timer would otherwise keep
                    // polling FPS/CPU/GPU for nothing every second.
                    Intent.ACTION_SCREEN_OFF -> {
                        stopTimer()
                        endSession(mContext.getString(R.string.fps_record_stop_screen))
                    }
                    Intent.ACTION_SCREEN_ON -> if (show) startTimer()
                    // A charging-state change makes the power/wattage
                    // numbers not comparable to the rest of the session,
                    // so end it rather than splice two regimes together.
                    Intent.ACTION_POWER_CONNECTED,
                    Intent.ACTION_POWER_DISCONNECTED -> endSession(mContext.getString(R.string.fps_record_stop_power))
                }
            }
        }
        try {
            mContext.registerReceiver(screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
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
    private var fpsText: TextView? = null
    private var actionView: View? = null
    private var durationContainer: View? = null
    private var durationButtons: List<TextView> = emptyList()
    private val myHandler = Handler(Looper.getMainLooper())

    private fun resetDurationButtons() {
        targetDurationSeconds = 0
        durationButtons.forEach { it.alpha = 0.3f }
    }

    private fun endSession(toastMessage: String) {
        if (sessionId <= 0) return
        sessionId = -1
        Thread { CpuCyclesUtils.stopStream() }.start()
        sessionApp?.let { frameStatsUtils.reset(it) }
        myHandler.post {
            Toast.makeText(mContext, toastMessage, Toast.LENGTH_SHORT).show()
            actionView?.alpha = 1f
            durationContainer?.visibility = View.GONE
            resetDurationButtons()
        }
    }

    private fun updateInfo() {
        val fps = fpsUtils.fps
        val gpuLoad = GpuUtils.getGpuLoad()

        if (coreCount < 1) coreCount = cpuFrequencyUtils.getCoreCount()

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

        if (sessionId > 0 && GlobalStatus.lastPackageName.isNotEmpty() && GlobalStatus.lastPackageName != sessionApp) {
            endSession(mContext.getString(R.string.fps_record_stop_foreground))
        }

        if (sessionId > 0) {
            BatteryStatusReader.update(mContext)

            // Only gathered while actually recording — these aren't needed
            // for the live pill, and several involve shell calls (DDR/cycles)
            // that would be wasted overhead on every idle tick otherwise.
            val cpuTemp = cpuLoadUtils.getCpuTemperatureText().removeSuffix("°C").toDoubleOrNull()
            val ddrFreq = cpuFrequencyUtils.getDdrFrequency().toIntOrNull()
            val coreCycles = CpuCyclesUtils.getPerCoreCyclesMhz()
            val clusterFreqs = (0 until cpuFrequencyUtils.getClusterInfo().size).map {
                cpuFrequencyUtils.getCurrentFrequency(it).toIntOrNull() ?: 0
            }
            val coreLoadsInt = (0 until coreCount).map { (loads[it] ?: 0.0).toInt() }

            if (batteryManager == null) {
                batteryManager = mContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            }
            // Same sanity gate and current->power formula FloatMonitor/
            // FloatMonitorMini already use for their own #PWR readouts.
            val rawCurrent = batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            val currentMa = rawCurrent?.let { it / BatteryStatusReader.currentUnitDivisor.toDouble() }
                ?.takeIf { it > -20000 && it < 20000 }

            val frameStats = sessionApp?.let { frameStatsUtils.poll(mContext, it) }

            fpsWatchStore.addHistory(
                sessionId,
                fps,
                cpuLoad,
                gpuLoad.toDouble(),
                BatteryStatusReader.batteryCapacity,
                BatteryStatusReader.temperatureCurrent,
                cpuTemp = cpuTemp,
                ddrFreq = ddrFreq,
                currentMa = currentMa,
                voltage = BatteryStatusReader.batteryVoltage.takeIf { it > 0 },
                coreLoads = coreLoadsInt,
                clusterFreqs = clusterFreqs,
                coreCycles = coreCycles,
                jankCount = frameStats?.jankCount,
                bigJankCount = frameStats?.bigJankCount,
                frameTimeMs = frameStats?.maxFrameTimeMs
            )

            tickCount++
            if (targetDurationSeconds > 0 && tickCount >= targetDurationSeconds) {
                endSession(mContext.getString(R.string.fps_record_complete))
            }
        }

        myHandler.post {
            fpsText?.text = String.format("%.1f", fps)
        }
    }

    private fun startTimer() {
        stopTimer()
        timer = Timer()
        timer!!.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                try {
                    updateInfo()
                } catch (ex: Exception) {
                }
            }
        }, 0, 1000)
    }

    private fun stopTimer() {
        timer?.cancel()
        timer = null
    }

    fun hidePopupWindow() {
        stopTimer()
        unregisterScreenReceiver()
        if (sessionId > 0) Thread { CpuCyclesUtils.stopStream() }.start()
        if (show && mView != null) {
            try {
                mWindowManager?.removeViewImmediate(mView)
            } catch (ex: Exception) {
            }
            mView = null
        }
        show = false
        sessionId = -1
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setUpView(context: Context): View {
        val v = LayoutInflater.from(context).inflate(R.layout.fw_fps_watch, null)
        fpsText = v.findViewById(R.id.fw_fps)
        actionView = v.findViewById(R.id.fw_action)
        durationContainer = v.findViewById(R.id.fw_fps_duration)
        durationButtons = listOf(R.id.duration_5m, R.id.duration_10m, R.id.duration_15m, R.id.duration_30m)
            .map { v.findViewById<TextView>(it) }

        // Swallows taps on the picker's own background/padding so they
        // don't bubble up to the root's click listener below and
        // immediately stop the recording that button is meant to time.
        durationContainer?.setOnClickListener { }

        for (button in durationButtons) {
            button.setOnClickListener {
                button.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                resetDurationButtons()
                button.alpha = 1f
                targetDurationSeconds = (button.text.toString().toIntOrNull() ?: 0) * 60
                tickCount = 0
            }
        }

        v.setOnClickListener {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (sessionId > 0) {
                endSession(mContext.getString(R.string.fps_record_complete))
            } else {
                // A battery that reads outside this range means the
                // voltage sensor itself isn't trustworthy right now, so
                // the wattage figures this session would log wouldn't
                // mean anything either.
                BatteryStatusReader.update(mContext)
                val voltage = BatteryStatusReader.batteryVoltage
                if (voltage < 3.2 || voltage >= 10.0) {
                    Toast.makeText(mContext, mContext.getString(R.string.fps_voltage_error), Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                val app = GlobalStatus.lastPackageName.ifEmpty { "android" }
                sessionId = fpsWatchStore.createSession(app)
                sessionApp = app
                tickCount = 0
                actionView?.alpha = 0.6f
                durationContainer?.visibility = View.VISIBLE
                resetDurationButtons()
            }
        }

        return v
    }

    companion object {
        private var mWindowManager: WindowManager? = null
        var show: Boolean = false

        @SuppressLint("StaticFieldLeak")
        private var mView: View? = null
        private var timer: Timer? = null
    }
}
