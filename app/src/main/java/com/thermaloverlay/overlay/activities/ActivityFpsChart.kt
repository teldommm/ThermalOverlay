/**
 * Session history + stats screen for the framerate recorder: lists past
 * recordings; shows the Platform/Model/OS/Profile header row, max/min/avg/
 * variance FPS, "smooth" (% of frames >=45fps) and 5%-low ratios; the main
 * FPS chart (switchable right axis: temperature/battery%/CPU+GPU load,
 * each with its own real color — TEMPERATURE #80FF7E00, LOAD's CPU%
 * #80fc6bc5 / GPU% #8087d3ff, CAPACITY #8087d3ff) plus its static 4-swatch
 * legend; JANK chart with a session-total JANK/BIG JANK row; frame time
 * chart with a MAX line; per-core CPU load / per-cluster frequency
 * (togglable with a time-at-frequency histogram) / per-core cycles+temp
 * charts, each with a dynamic per-cluster legend built from
 * CpuFrequencyUtils; GPU frequency+load with a static legend; DDR; Power/
 * Battery Current (togglable, with a legend and MAX/MIN/AVG row that swap
 * together); and CPU Temperature with its own MAX/MIN/AVG row.
 *
 * Known gaps: Profile (chart_mode) has no data source in our recorder and
 * stays blank; the top summary card's single "MAX" temperature stat still
 * reads the battery `temperature` column rather than `cpu_temp` (the
 * per-chart CPU Temperature MAX/MIN/AVG row is correct); CpuLoadsView's
 * optional "Total" line itself isn't drawn (off by the source's own
 * default) even though its legend swatch is shown, matching the source.
 * Doesn't include a search/keyword-highlight path in the session list
 * (nothing exposes a search box for it). Uses plain findViewById + a
 * styled Button instead of ViewBinding + a Material FloatingActionButton,
 * matching how the rest of ThermalOverlay's activities are built.
 */
package com.thermaloverlay.overlay.activities

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.thermaloverlay.overlay.ForegroundAppService
import com.thermaloverlay.overlay.OverlayPrefs
import com.thermaloverlay.overlay.OverlayService
import com.thermaloverlay.overlay.R
import com.thermaloverlay.overlay.metrics.CpuFrequencyUtils
import com.thermaloverlay.overlay.model.FpsWatchSession
import com.thermaloverlay.overlay.store.FpsWatchStore
import com.thermaloverlay.overlay.ui.AdapterSessions
import com.thermaloverlay.overlay.ui.CpuFrequencyStatView
import com.thermaloverlay.overlay.ui.FloatFpsWatch
import com.thermaloverlay.overlay.ui.FpsDataView
import com.thermaloverlay.overlay.ui.SessionLineChartView
import com.thermaloverlay.overlay.ui.SessionJankChartView
import com.thermaloverlay.overlay.ui.SessionMultiLineChartView
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
    private lateinit var sessionLogo: ImageView
    private lateinit var fpsMax: TextView
    private lateinit var fpsMin: TextView
    private lateinit var fpsAvg: TextView
    private lateinit var fpsVariance: TextView
    private lateinit var smoothRatio: TextView
    private lateinit var lowFpsView: TextView
    private lateinit var tempMax: TextView
    private lateinit var powerAvg: TextView
    private lateinit var platform: TextView
    private lateinit var phone: TextView
    private lateinit var os: TextView
    private lateinit var rightDimensionLabel: TextView
    private lateinit var chartView: FpsDataView
    private lateinit var jankView: SessionJankChartView
    private lateinit var frameTimeView: SessionJankChartView
    private lateinit var frameTimeMax: TextView
    private lateinit var coreLoadsView: SessionMultiLineChartView
    private lateinit var coreLoadsLegend: TextView
    private lateinit var clusterFreqTitle: TextView
    private lateinit var clusterFreqView: SessionMultiLineChartView
    private lateinit var clusterFreqStatView: CpuFrequencyStatView
    private lateinit var clusterFreqLegend: TextView
    private lateinit var coreCyclesView: SessionMultiLineChartView
    private lateinit var coreCyclesLegend: TextView
    private lateinit var gpuLoadView: SessionLineChartView
    private lateinit var ddrView: SessionLineChartView
    private lateinit var powerToggleTitle: TextView
    private lateinit var powerView: SessionLineChartView
    private lateinit var powerLegendLeft: TextView
    private lateinit var powerMaxRow: TextView
    private lateinit var powerMinRow: TextView
    private lateinit var powerAvgRow: TextView
    private lateinit var cpuTempView: SessionLineChartView
    private lateinit var cpuTempMaxRow: TextView
    private lateinit var cpuTempMinRow: TextView
    private lateinit var cpuTempAvgRow: TextView
    private lateinit var jankTotal: TextView
    private lateinit var bigJankTotal: TextView

    private val cpuFrequencyUtils = CpuFrequencyUtils()

    // Real app toggles CpuFrequencyView<->CpuFrequencyStat and
    // PowerView<->BatteryIOView in place via a tap; tracked here since we
    // share one view/title pair for each instead of two separate view
    // instances (same end result).
    private var showingFreqHistogram = false
    private var showingBatteryCurrent = false

    private var adapter: AdapterSessions? = null
    private var currentSessionId: Long = 0L
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
        sessionLogo = findViewById(R.id.session_logo)
        fpsMax = findViewById(R.id.chart_fps_max)
        fpsMin = findViewById(R.id.chart_fps_min)
        fpsAvg = findViewById(R.id.chart_fps_avg)
        fpsVariance = findViewById(R.id.chart_fps_variance)
        smoothRatio = findViewById(R.id.chart_smooth_ratio)
        lowFpsView = findViewById(R.id.chart_low_fps)
        tempMax = findViewById(R.id.chart_temp_max)
        powerAvg = findViewById(R.id.chart_power_avg)
        platform = findViewById(R.id.chart_platform)
        phone = findViewById(R.id.chart_phone)
        os = findViewById(R.id.chart_os)
        rightDimensionLabel = findViewById(R.id.chart_right)
        chartView = findViewById(R.id.chart_session_view)
        jankView = findViewById<SessionJankChartView>(R.id.chart_jank_view).apply { kind = SessionJankChartView.Kind.JANK }
        frameTimeView = findViewById<SessionJankChartView>(R.id.chart_frame_time_view).apply { kind = SessionJankChartView.Kind.FRAME_TIME }
        frameTimeMax = findViewById(R.id.chart_frame_time_max)
        coreLoadsView = findViewById<SessionMultiLineChartView>(R.id.chart_core_loads_view).apply { kind = SessionMultiLineChartView.Kind.CPU_CORE_LOADS }
        coreLoadsLegend = findViewById(R.id.chart_core_loads_legend)
        clusterFreqTitle = findViewById(R.id.chart_cluster_freq_title)
        clusterFreqView = findViewById<SessionMultiLineChartView>(R.id.chart_cluster_freq_view).apply { kind = SessionMultiLineChartView.Kind.CPU_CLUSTER_FREQ }
        clusterFreqStatView = findViewById(R.id.chart_cluster_freq_stat_view)
        clusterFreqLegend = findViewById(R.id.chart_cluster_freq_legend)
        coreCyclesView = findViewById<SessionMultiLineChartView>(R.id.chart_core_cycles_view).apply { kind = SessionMultiLineChartView.Kind.CPU_CORE_CYCLES }
        coreCyclesLegend = findViewById(R.id.chart_core_cycles_legend)
        gpuLoadView = findViewById<SessionLineChartView>(R.id.chart_gpu_load_view).apply { kind = SessionLineChartView.Kind.GPU_LOAD }
        ddrView = findViewById<SessionLineChartView>(R.id.chart_ddr_view).apply { kind = SessionLineChartView.Kind.DDR_FREQUENCY }
        powerToggleTitle = findViewById(R.id.chart_power_toggle_title)
        powerView = findViewById<SessionLineChartView>(R.id.chart_power_view).apply { kind = SessionLineChartView.Kind.POWER }
        powerLegendLeft = findViewById(R.id.chart_power_legend_left)
        powerMaxRow = findViewById(R.id.chart_power_max)
        powerMinRow = findViewById(R.id.chart_power_min)
        powerAvgRow = findViewById(R.id.chart_power_avg_row)
        cpuTempView = findViewById<SessionLineChartView>(R.id.chart_cpu_temp_view).apply { kind = SessionLineChartView.Kind.CPU_TEMPERATURE }
        cpuTempMaxRow = findViewById(R.id.chart_cpu_temp_max_row)
        cpuTempMinRow = findViewById(R.id.chart_cpu_temp_min)
        cpuTempAvgRow = findViewById(R.id.chart_cpu_temp_avg)
        jankTotal = findViewById(R.id.chart_jank_total)
        bigJankTotal = findViewById(R.id.chart_big_jank_total)

        // Device topology, not session data — build once. Matches real
        // ActivityFpsSession.r(): "■ Total  " (CpuLoadsView.getMainColor(),
        // #87d3ff) then one "■ CPU first~last  " swatch per cluster for the
        // loads legend; the same per-cluster swatches (no Total) for the
        // frequency legend; and the loads' per-cluster swatches again plus
        // a trailing "■ TEMP(℃)" for the cycles legend.
        val clusterLabels = buildClusterLabels()
        coreLoadsLegend.text = buildLegend(listOf("\u25a0 Total  " to Color.parseColor("#87d3ff")) + clusterLabels)
        clusterFreqLegend.text = buildLegend(clusterLabels)
        coreCyclesLegend.text = buildLegend(clusterLabels + listOf("\u25a0 TEMP(\u2103)" to Color.parseColor("#87d3ff")))

        sessionsList.layoutManager = LinearLayoutManager(this)

        recordButton.setOnClickListener { onRecordButtonClicked() }
        rightDimensionLabel.setOnClickListener { onRightDimensionClicked() }
        clusterFreqTitle.setOnClickListener { onClusterFreqTitleClicked() }
        powerToggleTitle.setOnClickListener { onPowerToggleClicked() }

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

    // Real format per cluster: "■ CPU {first}~{last}  " for multi-core
    // clusters, "■ CPU {only}  " for single-core ones — matches
    // ActivityFpsSession.r()'s v42.n(strArr)/v42.z(strArr) (first/last).
    private fun buildClusterLabels(): List<Pair<String, Int>> {
        val clusters = cpuFrequencyUtils.getClusterInfo()
        val colors = cpuFrequencyUtils.getClusterColors()
        return clusters.mapIndexed { index, cluster ->
            val label = if (cluster.size > 1) "\u25a0 CPU ${cluster.first()}~${cluster.last()}  " else "\u25a0 CPU ${cluster.firstOrNull() ?: ""}  "
            label to colors.getOrElse(index) { colors.lastOrNull() ?: Color.parseColor("#87d3ff") }
        }
    }

    private fun buildLegend(parts: List<Pair<String, Int>>): SpannableStringBuilder {
        val builder = SpannableStringBuilder()
        for ((text, color) in parts) {
            val start = builder.length
            builder.append(text)
            builder.setSpan(ForegroundColorSpan(color), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return builder
    }

    // Matches real cpu_freq_stat: swaps the CPU_CLUSTER_FREQ line chart for
    // the CpuFrequencyStatView histogram in place. Real app keeps the same
    // static title for both states, so only visibility toggles here.
    private fun onClusterFreqTitleClicked() {
        showingFreqHistogram = !showingFreqHistogram
        clusterFreqView.visibility = if (showingFreqHistogram) View.GONE else View.VISIBLE
        clusterFreqStatView.visibility = if (showingFreqHistogram) View.VISIBLE else View.GONE
    }

    // Matches real chart_toggle_w: swaps Power(W) for Battery Current(mA)
    // in place. Unlike the frequency toggle, the real app's title text
    // also changes between the two states (chart_toggle_w_text), and so do
    // the legend's left swatch and the MAX/MIN/AVG row's unit/values.
    private fun onPowerToggleClicked() {
        showingBatteryCurrent = !showingBatteryCurrent
        powerView.kind = if (showingBatteryCurrent) SessionLineChartView.Kind.BATTERY_CURRENT else SessionLineChartView.Kind.POWER
        powerToggleTitle.text = getString(
            if (showingBatteryCurrent) R.string.fps_chart_section_battery_current else R.string.fps_chart_section_power
        )
        updatePowerLegendAndStats(currentSessionId)
    }

    private fun updatePowerLegendAndStats(sessionId: Long) {
        if (sessionId < 1) return
        // Real legend: BatteryIOView's "■ Current(mA)" is #1474e4, PowerView's
        // "■ Power(W)" is also #1474e4 — only the label text changes, not the
        // color, in both chart_battery_legend and chart_power_legend.
        powerLegendLeft.text = if (showingBatteryCurrent) "\u25a0 Current(mA)" else "\u25a0 Power(W)"
        if (showingBatteryCurrent) {
            powerMaxRow.text = String.format("%dmA", fpsWatchStore.sessionMaxCurrent(sessionId).toInt())
            powerMinRow.text = String.format("%dmA", fpsWatchStore.sessionMinCurrent(sessionId).toInt())
            val currentSamples = fpsWatchStore.sessionCurrentData(sessionId)
            val avgCurrent = if (currentSamples.isEmpty()) 0 else currentSamples.average().toInt()
            powerAvgRow.text = String.format("%dmA", avgCurrent)
        } else {
            powerMaxRow.text = String.format("%.2fW", fpsWatchStore.sessionMaxPower(sessionId))
            powerMinRow.text = String.format("%.2fW", fpsWatchStore.sessionMinPower(sessionId))
            powerAvgRow.text = String.format("%.2fW", fpsWatchStore.sessionAvgPower(sessionId))
        }
    }

    private fun onRightDimensionClicked() {
        val values = FpsDataView.Dimension.values()
        val next = values[(values.indexOf(chartView.getRightDimension()) + 1) % values.size]
        chartView.setRightDimension(next)
        rightDimensionLabel.text = when (next) {
            FpsDataView.Dimension.TEMPERATURE -> getString(R.string.fps_chart_dimension_temperature)
            FpsDataView.Dimension.CAPACITY -> getString(R.string.fps_chart_dimension_battery)
            FpsDataView.Dimension.LOAD -> {
                // Colored to match the chart's own CPU (pink) / GPU (blue)
                // line colors, same as the source app's label. Found by
                // substring rather than a fixed position, since the Russian
                // string reorders around "CPU"/"GPU" (kept as literal
                // English abbreviations in both locales).
                val text = getString(R.string.fps_chart_dimension_load)
                SpannableString(text).apply {
                    val cpuStart = text.indexOf("CPU")
                    if (cpuStart >= 0) {
                        val cpuEnd = cpuStart + 3
                        setSpan(ForegroundColorSpan(Color.parseColor("#80fc6bc5")), cpuStart, cpuEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(StyleSpan(Typeface.BOLD), cpuStart, cpuEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    val gpuStart = text.indexOf("GPU")
                    if (gpuStart >= 0) {
                        val gpuEnd = gpuStart + 3
                        setSpan(ForegroundColorSpan(Color.parseColor("#8087d3ff")), gpuStart, gpuEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(StyleSpan(Typeface.BOLD), gpuStart, gpuEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }
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
        currentSessionId = sessionId

        val smooth = fpsData.count { it >= 45 } * 100.0 / fpsData.size

        sessionDetail.visibility = View.VISIBLE
        fpsMax.text = String.format("%.1f", fpsWatchStore.sessionMaxFps(sessionId))
        fpsMin.text = String.format("%.1f", fpsWatchStore.sessionMinFps(sessionId))
        fpsAvg.text = String.format("%.1f", fpsWatchStore.sessionAvgFps(sessionId))
        fpsVariance.text = String.format("%.1f", fpsWatchStore.sessionFpsVariance(sessionId))
        smoothRatio.text = String.format("%.1f%%", smooth)
        // 5% low is only defined for sessions longer than 100 samples; Scene
        // shows "--" otherwise (as in the Burnout screenshot).
        val lowFps = fpsWatchStore.sessionLowFps(sessionId)
        lowFpsView.text = if (lowFps > 0f) String.format("%.1f", lowFps) else "--"
        // Scene consistently uses the single ℃ glyph (U+2103), not two-char
        // °C — same convention already fixed elsewhere (FloatMonitor's #CPU
        // line, every chart section title).
        tempMax.text = if (temperatureData.isNotEmpty()) String.format("%.1f\u2103", temperatureData.maxOrNull() ?: 0f) else "--"
        powerAvg.text = String.format("%.2f", fpsWatchStore.sessionAvgPower(sessionId))
        sessionName.text = item.appName
        sessionTime.text = dateFormat.format(Date(item.beginTime))
        sessionLogo.setImageDrawable(item.appIcon)

        // Header: platform (SOC), model, OS version. Profile has no source in
        // our recorder, so it stays blank rather than showing a placeholder.
        platform.text = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeIf { it.isNotBlank() } ?: Build.HARDWARE else Build.HARDWARE
        phone.text = Build.MODEL
        os.text = "Android ${Build.VERSION.RELEASE}"
        chartView.setSessionId(sessionId)
        jankView.setSessionId(sessionId)
        frameTimeView.setSessionId(sessionId)
        coreLoadsView.setSessionId(sessionId)
        clusterFreqView.setSessionId(sessionId)
        clusterFreqStatView.setSessionId(sessionId)
        coreCyclesView.setSessionId(sessionId)
        gpuLoadView.setSessionId(sessionId)
        ddrView.setSessionId(sessionId)
        powerView.setSessionId(sessionId)
        cpuTempView.setSessionId(sessionId)

        // Scene shows the worst frame time of the session under the chart.
        val frameTimeData = fpsWatchStore.sessionFrameTimeData(sessionId)
        val maxFrameTime = frameTimeData.maxOrNull() ?: 0f
        frameTimeMax.text = "MAX: ${maxFrameTime.toInt()}ms"

        // Session-total jank/big-jank counts (sum, not per-tick average).
        jankTotal.text = fpsWatchStore.sessionTotalJank(sessionId).toInt().toString()
        bigJankTotal.text = fpsWatchStore.sessionTotalBigJank(sessionId).toInt().toString()

        // CPU-temperature-specific MAX/MIN/AVG under its own chart (distinct
        // from the top summary card's MAX, which — matching an existing,
        // already-flagged gap — still reads the `temperature` (battery)
        // column rather than `cpu_temp`).
        cpuTempMaxRow.text = String.format("%.1f\u2103", fpsWatchStore.sessionMaxCpuTemp(sessionId))
        cpuTempMinRow.text = String.format("%.1f\u2103", fpsWatchStore.sessionMinCpuTemp(sessionId))
        cpuTempAvgRow.text = String.format("%.1f\u2103", fpsWatchStore.sessionAvgCpuTemp(sessionId))

        updatePowerLegendAndStats(sessionId)
    }
}
