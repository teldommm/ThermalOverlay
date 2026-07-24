/**
 * One reusable view for the session-detail screen's four single-metric
 * charts: CPU temperature, DDR frequency, power, and GPU load. In the real
 * app these are separate classes
 * (CpuTemperatureView/DDRView/PowerView/GpuLoadView) that
 * differ only in data source, scale/gridline rule, and line color — the
 * same near-duplication FpsDataView's own drawSeries already consolidated
 * for its four dimensions, so it's collapsed the same way here via Kind
 * instead of five copies of the same ~150-line class.
 *
 * GPU_LOAD and POWER are the exceptions: the real GpuLoadView/PowerView
 * are dual-axis charts like FpsDataView, not single series. GPU_LOAD pairs
 * GPU frequency (left) with GPU load% (right); POWER pairs watts (left)
 * with battery capacity % (right). CpuTemperatureView and DDRView were
 * checked the same way and are confirmed genuinely single-series.
 *
 * Scale/gridline rules per kind are ported from the real per-class logic
 * (each has its own tiered maxY + key-gridline-value table); DDR is the one
 * exception among the single-series kinds — the source snaps its
 * gridlines to the actual distinct frequency steps observed in the
 * session's samples (since DDR only run at a handful of fixed hardware
 * frequencies) rather than a fixed numeric tier table, with a hardcoded
 * 4266 (MHz) floor independent of the data and 0 always included as a
 * gridline — both reproduced below.
 */
package com.thermaloverlay.overlay.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.thermaloverlay.overlay.store.FpsWatchStore

class SessionLineChartView : View {
    enum class Kind { CPU_TEMPERATURE, DDR_FREQUENCY, POWER, GPU_LOAD }

    private lateinit var store: FpsWatchStore
    private val paint = Paint()
    var kind: Kind = Kind.CPU_TEMPERATURE
        set(value) {
            field = value
            invalidate()
        }
    private var sessionId = 0L

    constructor(context: Context) : super(context) {
        store = FpsWatchStore(context)
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        store = FpsWatchStore(context)
    }

    fun setSessionId(id: Long) {
        if (sessionId != id) {
            sessionId = id
            invalidate()
        }
    }

    private fun dp2px(value: Float): Float = value * context.resources.displayMetrics.density

    // battery current's chart only shows discharge (matches the source,
    // which clamps negative samples — i.e. charging current — to 0 rather
    // than plotting it).
    private fun samplesFor(kind: Kind): List<Float> = when (kind) {
        Kind.CPU_TEMPERATURE -> store.sessionCpuTempData(sessionId)
        Kind.DDR_FREQUENCY -> store.sessionDdrFreqData(sessionId)
        Kind.POWER -> {
            val current = store.sessionCurrentData(sessionId)
            val voltage = store.sessionVoltageData(sessionId)
            current.indices.map { i ->
                val v = voltage.getOrNull(i) ?: return@map 0f
                (current[i] * v / 1000f)
            }
        }
        Kind.GPU_LOAD -> store.sessionGpuLoadData(sessionId)
    }

    private fun lineColor(kind: Kind): Int = when (kind) {
        Kind.CPU_TEMPERATURE, Kind.DDR_FREQUENCY -> Color.parseColor("#87d3ff")
        Kind.POWER, Kind.GPU_LOAD -> Color.parseColor("#1474e4")
    }

    private fun tieredScale(maxValue: Float, tiers: List<Pair<Float, Int>>, floor: Int, keysFor: (Int) -> List<Int>): Pair<Int, List<Int>> {
        for ((threshold, y) in tiers) {
            if (maxValue > threshold) return y to keysFor(y)
        }
        return floor to keysFor(floor)
    }

    private fun scaleFor(kind: Kind, samples: List<Float>): Pair<Int, List<Int>> {
        val maxValue = samples.maxOrNull() ?: 0f
        return when (kind) {
            Kind.CPU_TEMPERATURE -> {
                val maxY = when {
                    maxValue > 130 -> 150
                    maxValue > 120 -> 130
                    maxValue > 110 -> 120
                    maxValue > 100 -> 110
                    else -> 100
                }
                maxY to (0..maxY step 10).toList()
            }
            Kind.DDR_FREQUENCY -> {
                // Source hardcodes a 4266 (MHz) floor for the scale,
                // independent of what's actually observed — without it a
                // session that stayed at low DDR frequencies throughout
                // would zoom in artificially instead of using the same
                // baseline scale the real app always shows.
                val distinct = (samples.map { it.toInt() }.filter { it >= 0 } + 0).distinct().sorted()
                val maxY = maxOf(distinct.maxOrNull() ?: 0, 4266)
                maxY to distinct
            }
            Kind.POWER -> tieredScale(
                maxValue,
                listOf(25f to 30, 20f to 25, 15f to 20, 10f to 15, 8f to 10, 7f to 8, 6f to 7),
                floor = if (maxValue > 5) 6 else 5
            ) { y ->
                when {
                    y > 20 -> listOf(0, 5, 10, 15, 20, 25, 30, 35)
                    y > 15 -> listOf(0, 3, 6, 9, 12, 15, 18, 21)
                    y > 10 -> listOf(0, 2, 4, 6, 8, 10, 12, 14)
                    y > 8 -> listOf(0, 2, 4, 6, 8, 10)
                    y > 7 -> listOf(0, 1, 2, 3, 4, 5, 6, 7, 8)
                    else -> listOf(0, 1, 2, 3, 4, 5, 6, 7)
                }
            }
            Kind.GPU_LOAD -> 100 to listOf(50, 75, 90, 100)
        }
    }

    // GPU frequency's own scale: floor 600 (MHz), rounded up to the next
    // 100 above that, then one of three fixed gridline sets depending on
    // the tier — matches the real GpuLoadView.f(). The exact 1600/1200
    // tier boundaries come from a section of the decompiled source jadx
    // flagged as having reconstructed duplicated/uncertain control flow,
    // so this is our best-evidence read of it rather than a byte-exact
    // transcription.
    private fun gpuFreqScale(samples: List<Float>): Pair<Int, List<Int>> {
        val rawMax = (samples.maxOrNull() ?: 0f).toInt()
        val maxY = if (rawMax <= 600) 600 else ((rawMax / 100) * 100) + (if (rawMax % 100 > 0) 100 else 0)
        val keys = when {
            maxY > 1600 -> (0..2000 step 200).toList()
            maxY > 1200 -> (0..1500 step 150).toList()
            else -> (0..1100 step 100).toList()
        }
        return maxY to keys
    }

    // Battery capacity % as the shared secondary/right-axis series in the two
    // other dual-series kinds (Power, Battery current) — same data, color and
    // scale in both, so factored out here instead of duplicated.
    // Confirmed twice: activity_fps_session.xml labels this axis "Capacity %"
    // on both charts, and PowerView/BatteryIOView both read it through
    // py0.w() = `select capacity from fps_record`.
    private fun drawCapacitySecondary(canvas: Canvas, width: Int, height: Int, leftPadding: Float, innerPadding: Float, paddingTop: Float, textSize: Float) {
        val capacitySamples = store.sessionCapacityData(sessionId)
        if (capacitySamples.isEmpty()) return
        SessionChartRenderer.drawDualAxisSeries(
            canvas, paint, width, height, capacitySamples, 100, listOf(20, 40, 60, 80, 100), axisOnRight = true,
            lineColor = Color.parseColor("#87d3ff"), gridColor = Color.parseColor("#4087d3ff"),
            zeroLineColor = null, leftPadding, innerPadding, paddingTop, textSize
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (sessionId < 1) return

        val innerPadding = dp2px(18f)
        val density = context.resources.displayMetrics.density
        val paddingTop = dp2px(4f)
        val textSize = dp2px(8.5f)

        if (kind == Kind.GPU_LOAD) {
            val loadSamples = store.sessionGpuLoadData(sessionId)
            val freqSamples = store.sessionGpuFreqData(sessionId)
            if (loadSamples.isEmpty() || freqSamples.isEmpty()) return

            val (freqMaxY, freqKeys) = gpuFreqScale(freqSamples)
            val leftPadding = SessionChartRenderer.axisLabelPadding(paint, freqMaxY, textSize, density)
            SessionChartRenderer.drawTimeAxis(canvas, paint, width, height, freqSamples.size, leftPadding, innerPadding, paddingTop, textSize)
            SessionChartRenderer.drawDualAxisSeries(
                canvas, paint, width, height, freqSamples, freqMaxY, freqKeys, axisOnRight = false,
                lineColor = Color.parseColor("#87d3ff"), gridColor = Color.parseColor("#aa888888"),
                zeroLineColor = Color.parseColor("#888888"), leftPadding, innerPadding, paddingTop, textSize
            )
            SessionChartRenderer.drawDualAxisSeries(
                canvas, paint, width, height, loadSamples, 100, listOf(50, 75, 90, 100), axisOnRight = true,
                lineColor = Color.parseColor("#1474e4"), gridColor = Color.parseColor("#4087d3ff"),
                zeroLineColor = null, leftPadding, innerPadding, paddingTop, textSize
            )
            return
        }

        val samples = samplesFor(kind)
        if (samples.isEmpty()) return

        val (maxY, keys) = scaleFor(kind, samples)
        val leftPadding = SessionChartRenderer.axisLabelPadding(paint, maxY, textSize, density)
        SessionChartRenderer.drawTimeAxis(canvas, paint, width, height, samples.size, leftPadding, innerPadding, paddingTop, textSize)
        // Power and battery current are also dual-series in the source —
        // CPU load% drawn alongside on the right axis — unlike
        // CpuTemperatureView/DDRView, confirmed genuinely single-series.
        SessionChartRenderer.drawDualAxisSeries(
            canvas, paint, width, height, samples, maxY, keys, axisOnRight = false,
            lineColor = lineColor(kind), gridColor = Color.parseColor("#aa888888"),
            zeroLineColor = Color.parseColor("#888888"), leftPadding, innerPadding, paddingTop, textSize
        )
        if (kind == Kind.POWER) {
            drawCapacitySecondary(canvas, width, height, leftPadding, innerPadding, paddingTop, textSize)
        }
    }
}
