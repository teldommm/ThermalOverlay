/**
 * Floating process manager: draggable/lockable window with a live process
 * list (Apps/All filter, sorted by CPU), tap-to-kill with confirmation, and
 * a minimize toggle.
 */
package com.thermaloverlay.overlay.ui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
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
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.thermaloverlay.overlay.R
import com.thermaloverlay.overlay.metrics.ProcessUtilsSimple
import com.thermaloverlay.overlay.utils.WindowCompatHelper
import java.util.Timer
import java.util.TimerTask

class FloatTaskManager(private val context: Context) {
    private val processUtils = ProcessUtilsSimple()

    val supported: Boolean
        get() = processUtils.supported()

    fun showPopupWindow(): Boolean {
        if (show) return true

        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context)) {
            Toast.makeText(context, context.getString(R.string.permission_float), Toast.LENGTH_LONG).show()
            return false
        }

        setup()

        val monitorStorage = context.getSharedPreferences("float_task_storage", Context.MODE_PRIVATE)
        mWindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams()
        params.type = WindowCompatHelper.overlayWindowType()
        params.format = PixelFormat.TRANSLUCENT
        params.width = WindowManager.LayoutParams.WRAP_CONTENT
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.gravity = Gravity.TOP or Gravity.START
        params.x = monitorStorage.getInt("x", 0)
        params.y = monitorStorage.getInt("y", 0)

        @Suppress("DEPRECATION")
        params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_FULLSCREEN

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        try {
            mWindowManager!!.addView(mView, params)

            mView!!.setOnTouchListener(object : View.OnTouchListener {
                private var isTouchDown = false
                private var touchStartX = 0f
                private var touchStartY = 0f

                @SuppressLint("ClickableViewAccessibility")
                override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                    if (locked) return false
                    if (event != null) {
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                touchStartX = event.x
                                touchStartY = event.y
                                isTouchDown = true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                if (isTouchDown) {
                                    params.x = (event.rawX - touchStartX).toInt()
                                    params.y = (event.rawY - touchStartY).toInt()
                                    mWindowManager!!.updateViewLayout(v, params)
                                }
                            }
                            MotionEvent.ACTION_UP -> {
                                monitorStorage.edit().putInt("x", params.x).putInt("y", params.y).apply()
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

            val pinButton = mView!!.findViewById<View>(R.id.fw_float_pin)
            pinButton.setOnLongClickListener {
                @Suppress("DEPRECATION")
                params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                mView!!.setBackgroundColor(Color.argb(128, 255, 255, 255))
                mWindowManager!!.updateViewLayout(mView, params)
                true
            }

            startTimer()
            registerScreenReceiver()
            return true
        } catch (ex: Exception) {
            mView = null
            Toast.makeText(context, "FloatTaskManager Error\n" + ex.message, Toast.LENGTH_LONG).show()
            return false
        }
    }

    private fun setup() {
        locked = false
        minimized = false
        mView = LayoutInflater.from(context).inflate(R.layout.fw_process, null)

        val processList = mView!!.findViewById<ListView>(R.id.process_list).apply {
            adapter = AdapterProcessMini(context)
        }
        val minimizeButton = mView!!.findViewById<ImageButton>(R.id.fw_float_minimize)
        val filterText = mView!!.findViewById<TextView>(R.id.process_filter)
        val pinButton = mView!!.findViewById<View>(R.id.fw_float_pin)
        val closeButton = mView!!.findViewById<ImageButton>(R.id.fw_float_close)

        var filterMode = AdapterProcessMini.FILTER_ANDROID
        filterText.setOnClickListener {
            filterMode = if (filterMode == AdapterProcessMini.FILTER_ANDROID) AdapterProcessMini.FILTER_ALL else AdapterProcessMini.FILTER_ANDROID
            (processList.adapter as AdapterProcessMini).updateFilterMode(filterMode)
            filterText.text = if (filterMode == AdapterProcessMini.FILTER_ANDROID) "Apps" else "All"
        }

        // Kill requires a second tap on the same process within 3s — cheap
        // insurance against fat-fingering a kill on a scrolling list. The
        // periodic refresh is paused for that window too (see updateData()),
        // otherwise the list can reshuffle right as the confirming tap lands.
        var lastKillCandidatePid: Int? = null
        processList.setOnItemClickListener { _, _, position, _ ->
            val adapter = processList.adapter as AdapterProcessMini
            val processInfo = adapter.getItem(position)
            if (processInfo.name == context.packageName) {
                Toast.makeText(context, "Suicide is not allowed~", Toast.LENGTH_SHORT).show()
                return@setOnItemClickListener
            }
            val now = System.currentTimeMillis()
            if (now - lastTapTime > 3000 || processInfo.pid != lastKillCandidatePid) {
                lastTapTime = now
                lastKillCandidatePid = processInfo.pid
                Toast.makeText(context, "To end the process, tap again", Toast.LENGTH_SHORT).show()
            } else {
                processUtils.killProcess(processInfo)
                adapter.removeItem(position)
            }
        }

        pinButton.setOnClickListener {
            locked = !locked
            it.alpha = if (locked) 1f else 0.3f
            if (locked) {
                Toast.makeText(context, "Position locked — long-press this icon to also make the window click-through", Toast.LENGTH_LONG).show()
            }
        }

        closeButton.setOnClickListener { hidePopupWindow() }

        minimizeButton.setOnClickListener {
            if (processList.visibility == View.VISIBLE) {
                processList.visibility = View.GONE
                filterText.visibility = View.GONE
                closeButton.visibility = View.GONE
                minimizeButton.setImageDrawable(context.getDrawable(R.drawable.dialog_maximize))
                minimized = true
                stopTimer()
            } else {
                processList.visibility = View.VISIBLE
                filterText.visibility = View.VISIBLE
                closeButton.visibility = View.VISIBLE
                minimizeButton.setImageDrawable(context.getDrawable(R.drawable.dialog_minimize))
                minimized = false
                startTimer()
            }
        }
    }

    private var minimized = false
    private var screenReceiver: BroadcastReceiver? = null

    // Same reasoning as FloatMonitor: the process list is invisible while
    // the screen is off, but the timer would otherwise keep running `top`/
    // `ps` for nothing every 3s.
    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> stopTimer()
                    // Don't resume if the user had minimized it — minimize
                    // already means "I don't want this polling right now".
                    Intent.ACTION_SCREEN_ON -> if (show && !minimized) startTimer()
                }
            }
        }
        try {
            context.registerReceiver(screenReceiver, IntentFilter().apply {
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
                context.unregisterReceiver(it)
            } catch (ex: Exception) {
            }
        }
        screenReceiver = null
    }

    // Set from the item-click handler in setup(); read here to pause the
    // periodic refresh right after a tap (see the comment in setup()).
    private var lastTapTime = 0L

    private fun updateData() {
        if (System.currentTimeMillis() - lastTapTime < 3000) return
        val data = processUtils.allProcess
        handler.post {
            (mView?.findViewById<ListView>(R.id.process_list)?.adapter as AdapterProcessMini?)?.setList(data)
        }
    }

    private val handler = Handler(Looper.getMainLooper())

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
        private var locked = false

        @SuppressLint("StaticFieldLeak")
        private var mView: View? = null
        private var timer: Timer? = null

        val show: Boolean
            get() = mView != null
    }
}
