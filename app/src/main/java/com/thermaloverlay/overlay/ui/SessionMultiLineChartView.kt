/**
 * Multi-line session chart for metrics with one series per core/cluster:
 * per-core CPU load (0-100%, fixed scale) and per-cluster CPU frequency
 * (dynamic scale, same "floor at 2100 unless the data goes higher, then
 * pick a step size" rule as the real CpuFrequencyView). Lines are colored
 * by cluster using the real app's palette (thicker line for
 * higher/faster clusters), via CpuFrequencyUtils.getClusterInfo() to map
 * core index -> cluster index for the per-core variant.
 *
 * Not ported here: the optional "total/average" overlay line CpuLoadsView
 * can draw, off by the source's own default and not worth the added
 * complexity. CpuFrequencyStat (a time-at-frequency histogram, a
 * different visualization from this line chart) is ported separately as
 * CpuFrequencyStatView.
 */
package com.thermaloverlay.overlay.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.thermaloverlay.overlay.metrics.CpuFrequencyUtils
import com.thermaloverlay.overlay.store.FpsWatchStore

class SessionMultiLineChartView : View {
    enum class Kind { CPU_CORE_LOADS, CPU_CLUSTER_FREQ, CPU_CORE_CYCLES }

    // Real app's cluster color palette (the >3-cluster case, the common
    // modern layout — little/mid/big/prime); cycles if there are more.
    private val clusterColors = listOf(
        Color.parseColor("#B177E3"),
        Color.parseColor("#00d5d9"),
        Color.parseColor("#00B9C2"),
        Color.parseColor("#fc8a1b")
    )

    private lateinit var store: FpsWatchStore
    private val cpuFrequencyUtils = CpuFrequencyUtils()
    private val paint = Paint()
    var kind: Kind = Kind.CPU_CORE_LOADS
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

    // Maps each core-load series to its cluster's color/width; cluster
    // frequency series are already one-per-cluster.
    private fun coreToClusterIndex(coreCount: Int): IntArray {
        val clusters = cpuFrequencyUtils.getClusterInfo()
        val map = IntArray(coreCount) { -1 }
        for ((clusterIndex, cluster) in clusters.withIndex()) {
            for (coreStr in cluster) {
                coreStr.toIntOrNull()?.let { core -> if (core in 0 until coreCount) map[core] = clusterIndex }
            }
        }
        return map
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (sessionId < 1) return

        val innerPadding = dp2px(18f)
        val paddingTop = dp2px(4f)
        val textSize = dp2px(8.5f)

        when (kind) {
            Kind.CPU_CORE_LOADS -> {
                val series = store.sessionCoreLoadSeries(sessionId)
                if (series.isEmpty()) return
                val sampleCount = series.maxOf { it.size }
                val clusterOf = coreToClusterIndex(series.size)
                SessionChartRenderer.drawTimeAxis(canvas, paint, width, height, sampleCount, innerPadding, paddingTop, textSize)
                SessionChartRenderer.drawMultiSeries(
                    canvas, paint, width, height, series, sampleCount,
                    maxY = 100, keyValues = (0..100 step 10).toList(),
                    colorForSeries = { i -> val c = clusterOf.getOrElse(i) { 0 }.coerceAtLeast(0); clusterColors[c % clusterColors.size] },
                    strokeWidthForSeries = { i -> dp2px((clusterOf.getOrElse(i) { 0 }.coerceAtLeast(0) + 1).toFloat()) },
                    innerPadding, paddingTop, textSize
                )
            }
            Kind.CPU_CLUSTER_FREQ -> {
                val series = store.sessionClusterFreqSeries(sessionId)
                if (series.isEmpty()) return
                val sampleCount = series.maxOf { it.size }
                val rawMax = series.mapNotNull { it.maxOrNull() }.maxOrNull()?.toInt() ?: 0
                val maxY = maxOf(rawMax, 2100)
                val keys = when {
                    maxY > 4400 -> (0..4400 step 400).toList() + maxY
                    maxY > 3300 -> (0..4400 step 400).toList()
                    else -> (0..3300 step 300).toList()
                }
                SessionChartRenderer.drawTimeAxis(canvas, paint, width, height, sampleCount, innerPadding, paddingTop, textSize)
                SessionChartRenderer.drawMultiSeries(
                    canvas, paint, width, height, series, sampleCount,
                    maxY = maxY, keyValues = keys,
                    colorForSeries = { i -> clusterColors[i % clusterColors.size] },
                    strokeWidthForSeries = { i -> dp2px((i + 1).toFloat()) },
                    innerPadding, paddingTop, textSize
                )
            }
            // Per-core like loads, but cycles are a MHz-ish reading so they
            // share cluster-frequency's dynamic scale rather than a fixed
            // 0-100. Not replicated: the source additionally clamps this
            // scale to 1.5x the fastest core's rated max frequency (guards
            // against simpleperf measurement noise spiking the axis) — a
            // minor refinement skipped here.
            Kind.CPU_CORE_CYCLES -> {
                val series = store.sessionCoreCyclesSeries(sessionId)
                if (series.isEmpty()) return
                val sampleCount = series.maxOf { it.size }
                val clusterOf = coreToClusterIndex(series.size)
                val rawMax = series.mapNotNull { it.maxOrNull() }.maxOrNull()?.toInt() ?: 0
                val maxY = maxOf(rawMax, 2100)
                val keys = when {
                    maxY > 4400 -> (0..4400 step 400).toList() + maxY
                    maxY > 3300 -> (0..4400 step 400).toList()
                    else -> (0..3300 step 300).toList()
                }
                SessionChartRenderer.drawTimeAxis(canvas, paint, width, height, sampleCount, innerPadding, paddingTop, textSize)
                SessionChartRenderer.drawMultiSeries(
                    canvas, paint, width, height, series, sampleCount,
                    maxY = maxY, keyValues = keys,
                    colorForSeries = { i -> val c = clusterOf.getOrElse(i) { 0 }.coerceAtLeast(0); clusterColors[c % clusterColors.size] },
                    strokeWidthForSeries = { i -> dp2px((clusterOf.getOrElse(i) { 0 }.coerceAtLeast(0) + 1).toFloat()) },
                    innerPadding, paddingTop, textSize
                )
            }
        }
    }
}
