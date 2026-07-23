/**
 * Small floating pill (top-right) showing live FPS with a record button.
 * Tapping record starts a session that logs FPS/CPU/GPU/battery once a
 * second to FpsWatchStore; the session auto-stops if the foreground app
 * changes or the screen turns off, so a recording never silently spans two
 * different things. The CPU reading uses the same "dominant big core"
 * substitution as the mini monitor: if a big core is pegged above 70% and
 * higher than the average, that's shown instead, since a single saturated
 * core is usually what actually causes the stutter.
 *
 * Design notes: FPS comes from the existing FpsUtils (which already covers
 * an fpsgo-status / SurfaceFlinger-binder-call fallback chain, so there's
 * no need for a near-duplicate class), and app-switch/screen-off detection
 * is done by polling GlobalStatus.lastPackageName each tick plus a screen
 * on/off receiver, rather than a generic EventBus for one subscriber.
 */
package com.thermaloverlay.overlay.ui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import com.thermaloverlay.overlay.OverlayPrefs
import com.thermaloverlay.overlay.R
import com.thermaloverlay.overlay.data.GlobalStatus
import com.thermaloverlay.overlay.metrics.BatteryStatusReader
import com.thermaloverlay.overlay.metrics.CpuFrequencyUtils
import com.thermaloverlay.overlay.metrics.CpuLoadUtils
import com.thermaloverlay.overlay.metrics.FpsUtils
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
    private var coreCount = -1

    private var sessionId = -1L
    private var sessionApp: String? = null

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
        params.gravity = Gravity.TOP or Gravity.RIGHT
        params.y = dp2px(mContext, 55f)

        @Suppress("DEPRECATION")
        params.flags = LayoutParams.FLAG_NOT_TOUCH_MODAL or LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_FULLSCREEN

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
                    Intent.ACTION_SCREEN_OFF -> {
                        // Same reasoning as FloatMonitor: invisible while the
                        // screen is off, but the timer would otherwise keep
                        // polling FPS/CPU/GPU for nothing every second.
                        stopTimer()
                        endSession("The screen turned off, frame rate recording ended")
                    }
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
    private var fpsText: TextView? = null
    private var recordBtn: ImageButton? = null
    private val myHandler = Handler(Looper.getMainLooper())

    private fun endSession(toastMessage: String) {
        if (sessionId <= 0) return
        sessionId = -1
        myHandler.post {
            Toast.makeText(mContext, toastMessage, Toast.LENGTH_SHORT).show()
            recordBtn?.setImageResource(R.drawable.play)
            view?.alpha = 1f
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
            endSession("The foreground app changed, frame rate recording ended")
        }

        if (sessionId > 0) {
            BatteryStatusReader.update(mContext)
            fpsWatchStore.addHistory(
                sessionId,
                fps,
                cpuLoad,
                gpuLoad.toDouble(),
                BatteryStatusReader.batteryCapacity,
                BatteryStatusReader.temperatureCurrent
            )
        }

        myHandler.post {
            fpsText?.text = if (fps >= 100) fps.toInt().toString() else String.format("%.1f", fps)
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
        recordBtn = v.findViewById(R.id.fw_action)

        v.setOnClickListener {
            if (sessionId > 0) {
                sessionId = -1
                recordBtn?.setImageResource(R.drawable.play)
                v.alpha = 1f
                Toast.makeText(mContext, "Frame rate recording ended", Toast.LENGTH_SHORT).show()
            } else {
                val app = GlobalStatus.lastPackageName.ifEmpty { "android" }
                sessionId = fpsWatchStore.createSession(app)
                sessionApp = GlobalStatus.lastPackageName
                recordBtn?.setImageResource(R.drawable.stop)
                v.alpha = 0.6f
                Toast.makeText(mContext, "Recording started — stay in this app until you stop it", Toast.LENGTH_LONG).show()
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
