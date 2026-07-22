/**
 * Session history + stats screen for the framerate recorder: lists past
 * recordings, shows max/min/avg FPS plus "smooth" (% of frames >=45fps) and
 * "fever" (% of samples with battery temp >46°C) ratios for the selected
 * session, and a chart with FPS on the left axis and a switchable right
 * axis (temperature/battery/CPU+GPU load).
 *
 * Notes: doesn't include a platform/phone/OS info row (that would need
 * icon assets not present here) or a search/keyword-highlight path in the
 * session list (nothing exposes a search box for it). Uses plain
 * findViewById + a styled Button instead of ViewBinding + a Material
 * FloatingActionButton, matching how the rest of ThermalOverlay's
 * activities are built.
 */
package com.thermaloverlay.overlay.activities

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.thermaloverlay.overlay.ForegroundAppService
import com.thermaloverlay.overlay.OverlayPrefs
import com.thermaloverlay.overlay.OverlayService
import com.thermaloverlay.overlay.R
import com.thermaloverlay.overlay.model.FpsWatchSession
import com.thermaloverlay.overlay.store.FpsWatchStore
import com.thermaloverlay.overlay.ui.AdapterSessions
import com.thermaloverlay.overlay.ui.FloatFpsWatch
import com.thermaloverlay.overlay.ui.FpsDataView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ActivityFpsChart : AppCompatActivity(), AdapterSessions.OnItemClickListener {
    private lateinit var fpsWatchStore: FpsWatchStore

    private lateinit var recordButton: Button
    private lateinit var sessionsList: RecyclerView
    private lateinit var sessionsEmpty: TextView
    private lateinit var sessionDetail: View
    private lateinit var sessionName: TextView
    private lateinit var sessionTime: TextView
    private lateinit var fpsMax: TextView
    private lateinit var fpsMin: TextView
    private lateinit var fpsAvg: TextView
    private lateinit var smoothRatio: TextView
    private lateinit var feverRatio: TextView
    private lateinit var tempMax: TextView
    private lateinit var rightDimensionLabel: TextView
    private lateinit var chartView: FpsDataView

    private var adapter: AdapterSessions? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fps_chart)

        fpsWatchStore = FpsWatchStore(this)

        recordButton = findViewById(R.id.chart_record_button)
        sessionsList = findViewById(R.id.chart_sessions)
        sessionsEmpty = findViewById(R.id.chart_sessions_empty)
        sessionDetail = findViewById(R.id.chart_session_detail)
        sessionName = findViewById(R.id.chart_session_name)
        sessionTime = findViewById(R.id.chart_session_time)
        fpsMax = findViewById(R.id.chart_fps_max)
        fpsMin = findViewById(R.id.chart_fps_min)
        fpsAvg = findViewById(R.id.chart_fps_avg)
        smoothRatio = findViewById(R.id.chart_smooth_ratio)
        feverRatio = findViewById(R.id.chart_fever_ratio)
        tempMax = findViewById(R.id.chart_temp_max)
        rightDimensionLabel = findViewById(R.id.chart_right)
        chartView = findViewById(R.id.chart_session_view)

        sessionsList.layoutManager = LinearLayoutManager(this)

        recordButton.setOnClickListener { onRecordButtonClicked() }
        rightDimensionLabel.setOnClickListener { onRightDimensionClicked() }

        loadSessions()
    }

    override fun onResume() {
        super.onResume()
        refreshRecordButton()
    }

    private fun hasOverlayPermission(): Boolean {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
    }

    private fun refreshRecordButton() {
        recordButton.text = if (FloatFpsWatch.show) {
            getString(R.string.fps_chart_stop_recording)
        } else {
            getString(R.string.fps_chart_start_recording)
        }
    }

    private fun hasAccessibilityPermission(): Boolean {
        val expected = "$packageName/${ForegroundAppService::class.java.name}"
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(":").any { it.equals(expected, ignoreCase = true) }
    }

    private fun onRecordButtonClicked() {
        val startingNow = !FloatFpsWatch.show
        if (startingNow) {
            if (!hasOverlayPermission()) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                return
            }
            // The auto-stop-on-app-switch behavior silently does nothing
            // without this, so nudge the user the same way MainActivity's
            // switch does rather than let it fail quietly.
            if (!hasAccessibilityPermission()) {
                Toast.makeText(this, getString(R.string.accessibility_required_toast), Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return
            }
        }

        // Go through OverlayPrefs + OverlayService rather than managing
        // FloatFpsWatch directly here — otherwise this button and
        // MainActivity's "Framerate recorder" switch drift out of sync
        // (switch stays unchecked while a recording started here keeps
        // running, and a recording stopped here doesn't clear the pref, so
        // it silently reappears next time the service restarts), and a
        // recording started here would run unprotected by the foreground
        // service if the main overlay wasn't already on.
        OverlayPrefs.setFpsRecorderEnabled(this, startingNow)
        if (OverlayPrefs.isEnabled(this)) {
            val serviceIntent = Intent(this, OverlayService::class.java)
                .setAction(OverlayService.ACTION_TOGGLE_FPS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } else if (startingNow) {
            // Nothing is holding the process alive yet — start it now so
            // recording survives the app going to background, same as
            // tapping Start on the main screen.
            OverlayPrefs.setEnabled(this, true)
            val serviceIntent = Intent(this, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }

        if (startingNow) {
            Toast.makeText(this, getString(R.string.fps_chart_hint), Toast.LENGTH_LONG).show()
        }
        // The actual window add/remove happens async inside OverlayService,
        // so reflect the intended state now rather than re-reading
        // FloatFpsWatch.show before it's had a chance to update.
        recordButton.text = if (startingNow) {
            getString(R.string.fps_chart_stop_recording)
        } else {
            getString(R.string.fps_chart_start_recording)
        }
    }

    private fun onRightDimensionClicked() {
        val values = FpsDataView.Dimension.values()
        val next = values[(values.indexOf(chartView.getRightDimension()) + 1) % values.size]
        chartView.setRightDimension(next)
        rightDimensionLabel.text = when (next) {
            FpsDataView.Dimension.TEMPERATURE -> getString(R.string.fps_chart_dimension_temperature)
            FpsDataView.Dimension.CAPACITY -> getString(R.string.fps_chart_dimension_battery)
            FpsDataView.Dimension.LOAD -> getString(R.string.fps_chart_dimension_load)
        }
    }

    // Resolves app name/icon for each session off the main thread — one-off
    // list load, not a hot path, so no need for AppInfoLoader's cache here.
    private fun loadSessions() {
        Thread {
            val sessions = fpsWatchStore.sessions()
            sessions.forEach { session ->
                try {
                    val appInfo = packageManager.getApplicationInfo(session.packageName, 0)
                    session.appName = "" + appInfo.loadLabel(packageManager)
                    session.appIcon = appInfo.loadIcon(packageManager)
                } catch (ex: Exception) {
                }
            }
            mainHandler.post { showSessions(sessions) }
        }.start()
    }

    private fun showSessions(sessions: ArrayList<FpsWatchSession>) {
        if (sessions.isEmpty()) {
            sessionsList.visibility = View.GONE
            sessionsEmpty.visibility = View.VISIBLE
            sessionDetail.visibility = View.GONE
            return
        }
        sessionsList.visibility = View.VISIBLE
        sessionsEmpty.visibility = View.GONE

        adapter = AdapterSessions(this, sessions).apply {
            setOnItemClickListener(this@ActivityFpsChart)
            setOnItemDeleteClickListener(object : AdapterSessions.OnItemClickListener {
                override fun onItemClick(position: Int) = onSessionDeleteClick(position)
            })
        }
        sessionsList.adapter = adapter
        onItemClick(0)
    }

    private fun onSessionDeleteClick(position: Int) {
        val currentAdapter = adapter ?: return
        val item = currentAdapter.getItem(position)
        fpsWatchStore.deleteSession(item.sessionId)
        currentAdapter.removeItem(position)
        if (currentAdapter.itemCount == 0) {
            sessionsList.visibility = View.GONE
            sessionsEmpty.visibility = View.VISIBLE
            sessionDetail.visibility = View.GONE
        } else {
            onItemClick(0)
        }
    }

    override fun onItemClick(position: Int) {
        val currentAdapter = adapter ?: return
        if (position >= currentAdapter.itemCount) return
        val item = currentAdapter.getItem(position)
        val sessionId = item.sessionId

        val fpsData = fpsWatchStore.sessionFpsData(sessionId)
        val temperatureData = fpsWatchStore.sessionTemperatureData(sessionId)
        if (fpsData.isEmpty()) return

        val smooth = fpsData.count { it >= 45 } * 100.0 / fpsData.size
        val fever = if (temperatureData.isNotEmpty()) temperatureData.count { it > 46 } * 100.0 / temperatureData.size else 0.0

        sessionDetail.visibility = View.VISIBLE
        fpsMax.text = String.format("%.1f", fpsWatchStore.sessionMaxFps(sessionId))
        fpsMin.text = String.format("%.1f", fpsWatchStore.sessionMinFps(sessionId))
        fpsAvg.text = String.format("%.1f", fpsWatchStore.sessionAvgFps(sessionId))
        smoothRatio.text = String.format("%.1f%%", smooth)
        feverRatio.text = String.format("%.1f%%", fever)
        tempMax.text = if (temperatureData.isNotEmpty()) String.format("%.1f\u00b0C", temperatureData.maxOrNull() ?: 0f) else "--"
        sessionName.text = item.appName
        sessionTime.text = dateFormat.format(Date(item.beginTime))
        chartView.setSessionId(sessionId)
    }
}
