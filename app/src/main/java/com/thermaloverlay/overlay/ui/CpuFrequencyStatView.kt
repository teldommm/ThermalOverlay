/**
 * CPU time-at-frequency histogram for a recorded session — one bar-series
 * per cluster showing what % of the session that cluster spent at each
 * observed frequency. This is a genuinely different rendering paradigm
 * from every other chart here (vertical bars over a frequency axis, not a
 * line/step chart over time), matching the real CpuFrequencyStat.
 *
 * Toggled in place with the CPU_CLUSTER_FREQ line chart (ActivityFpsChart
 * swaps this view's and SessionMultiLineChartView's visibility via one
 * icon), matching the real app's cpu_freq_stat swap between
 * CpuFrequencyView and CpuFrequencyStat.
 *
 * Data reuses sessionClusterFreqSeries — the same per-cluster frequency
 * series the CPU_CLUSTER_FREQ chart already reads — grouped into a
 * frequency->tick-count histogram per cluster. The percentage denominator
 * is the total tick count across the whole session (matches real
 * py0.y().size()), not each cluster's own sample count, so a cluster that
 * briefly dropped out (hotplug) shows a slightly lower total% rather than
 * being renormalized to its own count — this matches the source exactly
 * (a difference from a naive per-cluster-normalized histogram).
 *
 * Both axes here are value axes (frequency / percentage), not one value +
 * one time axis like every other chart — so unlike the rest of this
 * codebase's "time axis solid, value axis dashed" convention, BOTH axes
 * are dashed here, matching the real source's own paint setup.
 */
package com.thermaloverlay.overlay.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.thermaloverlay.overlay.metrics.CpuFrequencyUtils
import com.thermaloverlay.overlay.store.FpsWatchStore

class CpuFrequencyStatView : View {
    private val cpuFrequencyUtils = CpuFrequencyUtils()
    private val clusterColors: List<Int> by lazy { cpuFrequencyUtils.getClusterColors() }
    private val dashEffect = DashPathEffect(floatArrayOf(4f, 8f), 0f)

    private lateinit var store: FpsWatchStore
    private val paint = Paint()
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
    private val density: Float get() = context.resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (sessionId < 1) return

        val series = store.sessionClusterFreqSeries(sessionId)
        if (series.isEmpty()) return
        val totalTicks = series.maxOf { it.size }
        if (totalTicks <= 0) return

        val innerPadding = dp2px(18f)
        val paddingTop = dp2px(4f)
        val textSize = dp2px(8.5f)

        // Same floor-2100 dynamic scale as CPU_CLUSTER_FREQ — this is
        // literally the same data source, so the two charts always agree
        // on their frequency axis range.
        val rawMax = series.mapNotNull { it.maxOrNull() }.maxOrNull()?.toInt() ?: 0
        val maxY = maxOf(rawMax, 2100)
        val keys = SessionChartRenderer.frequencyAxisKeys(maxY)
        val leftPadding = SessionChartRenderer.axisLabelPadding(paint, maxY, textSize, density)

        val ratioX = (width - leftPadding - innerPadding) / maxY.toFloat()
        val ratioY = (height - innerPadding - paddingTop) / 100f
        val bottom = height - innerPadding

        // x-axis: frequency gridlines, dashed, at the same tier keys the
        // line chart uses (including the "always label the true max"
        // extra key when the gap is >100).
        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = textSize
        paint.textAlign = Paint.Align.CENTER
        paint.style = Paint.Style.FILL
        paint.strokeWidth = 2f
        paint.pathEffect = dashEffect
        for (point in keys) {
            if (point > maxY) continue
            val x = point * ratioX + leftPadding
            paint.color = Color.parseColor("#888888")
            if (point > 0) canvas.drawText(point.toString(), x, height - innerPadding + textSize + 2f * density, paint)
            paint.strokeWidth = if (point == 0) 4f else 2f
            paint.color = Color.parseColor("#aa888888")
            canvas.drawLine(x, paddingTop, x, bottom, paint)
        }

        // y-axis: percentage gridlines every 10, labeled every 20 — matches
        // the real source's `i10 % 10 == 0` grid / `i10 % 20 == 0` label
        // split.
        paint.textAlign = Paint.Align.RIGHT
        paint.pathEffect = dashEffect
        for (point in 0..100 step 10) {
            val y = bottom - point * ratioY
            paint.strokeWidth = if (point == 0) 4f else 2f
            paint.color = Color.parseColor("#aa888888")
            canvas.drawLine(leftPadding, y, width - innerPadding, y, paint)
            if (point > 0 && point % 20 == 0) {
                paint.color = Color.parseColor("#888888")
                canvas.drawText("$point%", leftPadding - 2f * density, y + textSize / 2.2f, paint)
            }
        }

        // Bars: one vertical line per (cluster, observed frequency), offset
        // horizontally per cluster so overlapping clusters don't hide each
        // other. Offset and stroke width are RAW pixels in the source (not
        // dp-scaled) — confirmed already for this exact chart.
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f
        val clusterCount = series.size
        for ((clusterIndex, clusterSeries) in series.withIndex()) {
            if (clusterSeries.isEmpty()) continue
            paint.color = clusterColors[clusterIndex % clusterColors.size]
            val histogram = HashMap<Int, Int>()
            for (value in clusterSeries) {
                val freq = value.toInt()
                histogram[freq] = (histogram[freq] ?: 0) + 1
            }
            val xOffset = (clusterIndex * 2 - clusterCount) * 5f
            for ((freq, count) in histogram) {
                val percent = maxOf(1f, count.toFloat() / totalTicks * 100f)
                val x = freq * ratioX + leftPadding + xOffset
                canvas.drawLine(x, bottom, x, bottom - percent * ratioY, paint)
            }
        }
    }
}
