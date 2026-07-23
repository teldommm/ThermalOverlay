/**
 * Time-at-frequency histogram, one bar series per cluster: X axis is CPU
 * frequency (same dynamic floor-2100 scale/tiers as the cluster frequency
 * line chart), Y axis is the percentage of the session's ticks that
 * cluster spent at that exact frequency value (floored at 1% so a
 * briefly-visited frequency still shows a sliver). Ported from the real
 * CpuFrequencyStat, which derives this from the same per-cluster frequency
 * data as CpuFrequencyView — just aggregated into value-counts instead of
 * a time series. Clusters that share a frequency value get slightly
 * offset bars so they don't fully overlap, matching the source.
 */
package com.thermaloverlay.overlay.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.thermaloverlay.overlay.store.FpsWatchStore

class CpuFrequencyStatView : View {
    private val clusterColors = listOf(
        Color.parseColor("#B177E3"),
        Color.parseColor("#00d5d9"),
        Color.parseColor("#00B9C2"),
        Color.parseColor("#fc8a1b")
    )
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (sessionId < 1) return

        val series = store.sessionClusterFreqSeries(sessionId)
        if (series.isEmpty()) return

        val innerPadding = dp2px(18f)
        val paddingTop = dp2px(4f)
        val textSize = dp2px(8.5f)

        val rawMax = series.mapNotNull { it.maxOrNull() }.maxOrNull()?.toInt() ?: 0
        val maxX = maxOf(rawMax, 2100)
        val keys = when {
            maxX > 4400 -> (0..4400 step 400).toList() + maxX
            maxX > 3300 -> (0..4400 step 400).toList()
            else -> (0..3300 step 300).toList()
        }

        val ratioX = (width - innerPadding * 2) / maxX.toFloat()
        val ratioY = (height - innerPadding - paddingTop) / 100f
        val baseY = height - innerPadding

        // X axis (frequency) gridlines/labels along the bottom.
        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = textSize
        paint.textAlign = Paint.Align.CENTER
        paint.pathEffect = dashEffect
        for (point in keys) {
            val x = innerPadding + point * ratioX
            paint.color = Color.parseColor("#888888")
            if (point > 0) canvas.drawText(point.toString(), x, baseY + textSize + 4f, paint)
            paint.color = Color.parseColor("#aa888888")
            canvas.drawLine(x, baseY, x, paddingTop, paint)
        }

        // Y axis (percentage) gridlines every 10, labeled every 20.
        paint.textAlign = Paint.Align.RIGHT
        for (point in 0..100 step 10) {
            paint.color = Color.parseColor("#888888")
            if (point > 0 && point % 20 == 0) {
                canvas.drawText("$point%", innerPadding - 4f, baseY - point * ratioY + textSize / 2.2f, paint)
            }
            paint.strokeWidth = if (point == 0) 4f else 2f
            paint.color = if (point == 0) Color.parseColor("#888888") else Color.parseColor("#aa888888")
            canvas.drawLine(innerPadding, baseY - point * ratioY, width - innerPadding, baseY - point * ratioY, paint)
        }

        // Bars: one histogram per cluster, values offset slightly per
        // cluster so overlapping frequencies stay visually distinct.
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.strokeWidth = dp2px(5f)
        val clusterCount = series.size
        for ((clusterIndex, clusterSeries) in series.withIndex()) {
            if (clusterSeries.isEmpty()) continue
            paint.color = clusterColors[clusterIndex % clusterColors.size]
            val histogram = clusterSeries.groupingBy { it.toInt() }.eachCount()
            val total = clusterSeries.size
            val xOffset = (clusterIndex * 2 - clusterCount) * dp2px(5f)
            for ((freq, count) in histogram) {
                val percent = (count * 100f / total).coerceAtLeast(1f)
                val x = innerPadding + freq * ratioX + xOffset
                canvas.drawLine(x, baseY, x, baseY - percent * ratioY, paint)
            }
        }
    }
}
