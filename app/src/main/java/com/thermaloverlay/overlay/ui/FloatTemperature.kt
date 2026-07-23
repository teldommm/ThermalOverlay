/**
 * Small floating readout of named temperature sensors (CPU/GPU/DDR/
 * Camera — whichever the device actually exposes) plus battery temp.
 * Draggable with double-tap-to-hide, same touch handling as FloatMonitor/
 * FloatTaskManager. Ported from vtools' FloatTemperature.
 *
 * The source app's tap-to-expand click handler references a view id
 * (fw_chart_list) that its own fw_temperature.xml layout doesn't contain —
 * confirmed against the shipped APK's resources, so that path always
 * silently no-ops there (caught exception). Not carried over here since
 * it isn't actually reachable behavior in the original either.
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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.TextView
import android.widget.Toast
import com.thermaloverlay.overlay.R
import com.thermaloverlay.overlay.metrics.BatteryStatusReader
import com.thermaloverlay.overlay.metrics.TemperatureSensorUtils
import com.thermaloverlay.overlay.utils.WindowCompatHelper
import java.util.Timer
import java.util.TimerTask

class FloatTemperature(private val mContext: Context) {

    fun showPopupWindow(): Boolean {
        if (show) return true

        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(mContext)) {
            Toast.makeText(mContext, mContext.getString(R.string.permission_float), Toast.LENGTH_LONG).show()
            return false
        }

        show = true
        mWindowManager = mContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val view = setUpView(mContext)
        val monitorStorage = mContext.getSharedPreferences("float_temperature_storage", Context.MODE_PRIVATE)

        val params = LayoutParams()
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
            Toast.makeText(mContext, "FloatTemperature Error\n" + ex.message, Toast.LENGTH_LONG).show()
            return false
        }
    }

    private var screenReceiver: BroadcastReceiver? = null

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
    private var infoText: TextView? = null
    private val myHandler = Handler(Looper.getMainLooper())

    private fun updateInfo() {
        val sb = StringBuilder()

        BatteryStatusReader.update(mContext)
        val batteryTemp = BatteryStatusReader.temperatureCurrent
        if (batteryTemp > -1) {
            sb.append("# BAT ").append(batteryTemp).append("\u2103\n")
        }

        for ((label, value) in TemperatureSensorUtils.readAvailable()) {
            sb.append("# ").append(label).append(' ').append(value).append("\u2103\n")
        }

        val text = sb.toString().trim()
        myHandler.post {
            try {
                infoText?.text = text
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

    @SuppressLint("ClickableViewAccessibility")
    private fun setUpView(context: Context): View {
        view = LayoutInflater.from(context).inflate(R.layout.fw_temperature, null)
        infoText = view!!.findViewById(R.id.fw_info)
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
