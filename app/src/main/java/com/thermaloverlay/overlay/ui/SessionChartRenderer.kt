/**
 * Shared drawing logic for the single-series session charts (CPU
 * temperature, DDR frequency, battery current, power, GPU load): a time
 * axis plus one line with left-hand y-axis gridlines. Used by
 * SessionLineChartView so the several near-identical real-app widgets
 * (CpuTemperatureView, DDRView, PowerView, BatteryIOView, GpuLoadView) share
 * one implementation instead of each re-deriving the same "gridlines +
 * labels + connecting line" logic — same reasoning FpsDataView's own
 * drawSeries consolidation already used for its four dimensions.
 *
 * FpsDataView keeps its own drawSeries rather than switching to this one:
 * it needs a dual left+right axis (FPS always on the left, a switchable
 * dimension on the right) which this single-axis renderer doesn't support.
 */
package com.thermaloverlay.overlay.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path

object SessionChartRenderer {
    private val dashEffect = DashPathEffect(floatArrayOf(4f, 8f), 0f)

    /**
     * Left padding, sized to the widest y-axis label exactly like the source:
     * it builds a string of '9' as long as maxY's digit count, measures it and
     * adds 4dp (FrameTimeView is the one that adds nothing, hence `extraDp`).
     * The right and bottom padding stay at a flat 18dp.
     */
    fun axisLabelPadding(paint: Paint, maxY: Int, textSize: Float, density: Float, extraDp: Float = 4f): Float {
        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = textSize
        val nines = "9".repeat(maxY.toString().length)
        return paint.measureText(nines) + extraDp * density
    }

    /**
     * Ported from the source's vj0.c(double). Two things the port had wrong:
     * the day threshold is 1440 minutes (not 1140), and the smaller unit is
     * omitted entirely when it rounds to zero — the source prints "5m", not
     * "5m0s".
     */
    fun minutesLabel(minutes: Double): String {
        return when {
            minutes >= 1440 -> {
                val hours = ((minutes % 1440) / 60).toInt()
                "${(minutes / 1440).toInt()}d" + if (hours > 0) "${hours}h" else ""
            }
            minutes > 60 -> {
                val mins = (minutes % 60).toInt()
                "${(minutes / 60).toInt()}h" + if (mins > 0) "${mins}m" else ""
            }
            minutes == 0.0 -> "0"
            minutes < 1 -> "${(minutes * 60).toInt()}s"
            else -> {
                val secs = ((minutes % 1) * 60).toInt()
                "${minutes.toInt()}m" + if (secs > 0) "${secs}s" else ""
            }
        }
    }

    fun drawTimeAxis(
        canvas: Canvas, paint: Paint, width: Int, height: Int,
        sampleCount: Int, leftPadding: Float, innerPadding: Float, paddingTop: Float, textSize: Float,
        density: Float
    ) {
        // The source spans (n - 1) samples, not n, so the last sample lands
        // exactly on the right edge: `size = (list.size() - 1) / 60.0`.
        val minutes = (sampleCount - 1) / 60.0
        if (minutes <= 0) return
        val columns = 5
        val scaleX = minutes / columns
        val ratioX = (width - leftPadding - innerPadding) / minutes

        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = textSize
        paint.textAlign = Paint.Align.CENTER
        paint.style = Paint.Style.FILL
        paint.strokeWidth = 1f
        for (point in 0..columns) {
            // the source truncates the pixel offset before adding the padding:
            // `((int) (d2 * width)) + f3`
            val drawX = (point * scaleX * ratioX).toInt() + leftPadding
            paint.color = Color.parseColor("#888888")
            canvas.drawText(minutesLabel(point * scaleX), drawX, height - innerPadding + textSize + 2f * density, paint)
            paint.color = Color.parseColor("#40888888")
            canvas.drawLine(drawX, paddingTop, drawX, height - innerPadding, paint)
        }
    }

    // Single series, left-hand axis only (all of this screen's individual
    // widgets are one metric each — no shared right axis to coordinate
    // with, unlike FpsDataView's FPS+dimension pairing).
    fun drawSeries(
        canvas: Canvas, paint: Paint, width: Int, height: Int,
        samples: List<Float>, maxY: Int, keyValues: List<Int>,
        lineColor: Int, leftPadding: Float, innerPadding: Float, paddingTop: Float, textSize: Float,
        labelOffset: Float
    ) {
        if (samples.size < 2 || maxY <= 0) return
        val ratioY = (height - innerPadding - paddingTop) / maxY
        val startY = height - innerPadding

        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = textSize
        paint.strokeWidth = 2f
        paint.pathEffect = dashEffect
        paint.textAlign = Paint.Align.RIGHT
        for (point in 0..maxY) {
            if (point !in keyValues) continue
            paint.color = Color.parseColor("#888888")
            // the source truncates before adding paddingTop:
            // `((int) ((maxY - point) * ratioY)) + paddingTop`
            val gridY = paddingTop + ((maxY - point) * ratioY).toInt()
            if (point > 0) canvas.drawText(point.toString(), leftPadding - labelOffset, gridY + textSize / 2.2f, paint)
            paint.strokeWidth = if (point == 0) 4f else 2f
            paint.color = if (point == 0) Color.parseColor("#888888") else Color.parseColor("#aa888888")
            canvas.drawLine(leftPadding, gridY, width - innerPadding, gridY, paint)
        }

        paint.reset()
        paint.isAntiAlias = true
        // The source sets 8f + Style.FILL here, but then overrides both to
        // Style.STROKE + 4f immediately before its canvas.drawPath — so the
        // line that actually renders is 4px wide, not 8px. Verified identical
        // in all five of CpuTemperatureView / DDRView / PowerView /
        // BatteryIOView / GpuLoadView (both of GpuLoadView's series).
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.pathEffect = null
        paint.color = lineColor
        val ratioX = (width - leftPadding - innerPadding) / ((samples.size - 1) / 60f)
        var lastX = leftPadding
        var lastY = startY - samples.first().coerceAtLeast(0f) * ratioY
        for ((index, sample) in samples.withIndex()) {
            val value = sample.coerceAtLeast(0f)
            val currentX = index / 60f * ratioX + leftPadding
            val currentY = startY - value * ratioY
            canvas.drawLine(lastX, lastY, currentX, currentY, paint)
            lastX = currentX
            lastY = currentY
        }
    }

    // Same shape as drawSeries but with an explicit left/right axis choice
    // and an optional zero-line override color — needed for GPU load,
    // which (like FpsDataView's own FPS+dimension pairing) is actually a
    // dual-axis chart in the source: frequency on the left, load% on the
    // right, sharing one time axis.
    fun drawDualAxisSeries(
        canvas: Canvas, paint: Paint, width: Int, height: Int,
        samples: List<Float>, maxY: Int, keyValues: List<Int>,
        axisOnRight: Boolean, lineColor: Int, gridColor: Int, zeroLineColor: Int?,
        leftPadding: Float, innerPadding: Float, paddingTop: Float, textSize: Float,
        labelOffset: Float
    ) {
        if (samples.size < 2 || maxY <= 0) return
        val ratioY = (height - innerPadding - paddingTop) / maxY
        val startY = height - innerPadding

        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = textSize
        paint.strokeWidth = 2f
        paint.pathEffect = dashEffect
        paint.textAlign = if (axisOnRight) Paint.Align.LEFT else Paint.Align.RIGHT
        for (point in 0..maxY) {
            if (point !in keyValues) continue
            // Left/primary axis labels are #888888, right/secondary axis
            // labels are #808080 — consistent across every dual-series chart
            // in the source (FpsDataView, PowerView, BatteryIOView,
            // CpuCyclesView, GpuLoadView).
            paint.color = if (axisOnRight) Color.parseColor("#808080") else Color.parseColor("#888888")
            // right-hand labels sit at a flat +8 raw px in the source; left-hand
            // ones use a per-chart dp offset. Y truncates before paddingTop.
            val labelX = if (axisOnRight) width - innerPadding + 8f else leftPadding - labelOffset
            val gridY = paddingTop + ((maxY - point) * ratioY).toInt()
            if (point > 0) canvas.drawText(point.toString(), labelX, gridY + textSize / 2.2f, paint)
            if (axisOnRight && point == maxY) continue
            paint.strokeWidth = if (point == 0) 4f else 2f
            paint.color = if (point == 0 && zeroLineColor != null) zeroLineColor else gridColor
            canvas.drawLine(leftPadding, gridY, width - innerPadding, gridY, paint)
        }

        paint.reset()
        paint.isAntiAlias = true
        // The source sets 8f + Style.FILL here, but then overrides both to
        // Style.STROKE + 4f immediately before its canvas.drawPath — so the
        // line that actually renders is 4px wide, not 8px. Verified identical
        // in all five of CpuTemperatureView / DDRView / PowerView /
        // BatteryIOView / GpuLoadView (both of GpuLoadView's series).
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.pathEffect = null
        paint.color = lineColor
        val ratioX = (width - leftPadding - innerPadding) / ((samples.size - 1) / 60f)
        var lastX = leftPadding
        var lastY = startY - samples.first().coerceAtLeast(0f) * ratioY
        for ((index, sample) in samples.withIndex()) {
            val value = sample.coerceAtLeast(0f)
            val currentX = index / 60f * ratioX + leftPadding
            val currentY = startY - value * ratioY
            canvas.drawLine(lastX, lastY, currentX, currentY, paint)
            lastX = currentX
            lastY = currentY
        }
    }

    // Several lines sharing one scale (per-core load, per-cluster
    // frequency): each series is plotted using its own point count for the
    // x-axis, so a core that drops out mid-session (hotplug) just draws a
    // shorter line rather than corrupting the others' alignment.
    fun drawMultiSeries(
        canvas: Canvas, paint: Paint, width: Int, height: Int,
        seriesList: List<List<Float>>, sampleCountForAxis: Int,
        maxY: Int, keyValues: List<Int>,
        colorForSeries: (Int) -> Int, strokeWidthForSeries: (Int) -> Float,
        leftPadding: Float, innerPadding: Float, paddingTop: Float, textSize: Float,
        labelOffset: Float
    ) {
        if (seriesList.isEmpty() || maxY <= 0 || sampleCountForAxis < 2) return
        val ratioY = (height - innerPadding - paddingTop) / maxY
        val startY = height - innerPadding
        val ratioX = (width - leftPadding - innerPadding) / ((sampleCountForAxis - 1) / 60f)

        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = textSize
        paint.strokeWidth = 2f
        paint.pathEffect = dashEffect
        paint.textAlign = Paint.Align.RIGHT
        for (point in 0..maxY) {
            if (point !in keyValues) continue
            paint.color = Color.parseColor("#888888")
            val gridY = paddingTop + ((maxY - point) * ratioY).toInt()
            if (point > 0) canvas.drawText(point.toString(), leftPadding - labelOffset, gridY + textSize / 2.2f, paint)
            paint.strokeWidth = if (point == 0) 4f else 2f
            paint.color = if (point == 0) Color.parseColor("#888888") else Color.parseColor("#aa888888")
            canvas.drawLine(leftPadding, gridY, width - innerPadding, gridY, paint)
        }

        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.pathEffect = null
        for ((seriesIndex, series) in seriesList.withIndex()) {
            if (series.isEmpty()) continue
            paint.color = colorForSeries(seriesIndex)
            paint.strokeWidth = strokeWidthForSeries(seriesIndex)
            var lastX = leftPadding
            var lastY = startY - series.first().coerceIn(0f, maxY.toFloat()) * ratioY
            for ((index, sample) in series.withIndex()) {
                val value = sample.coerceIn(0f, maxY.toFloat())
                val currentX = index / 60f * ratioX + leftPadding
                val currentY = startY - value * ratioY
                canvas.drawLine(lastX, lastY, currentX, currentY, paint)
                lastX = currentX
                lastY = currentY
            }
        }
    }

    // Step/bar style: each sample draws as a flat-topped rectangle over its
    // time slot rather than a diagonal line to the next point — matches
    // FrameTimeView/FpsJankView's own rendering, which is visually
    // distinct from the interpolated-line style every other chart uses.
    fun drawStepSeries(
        canvas: Canvas, paint: Paint, width: Int, height: Int,
        samples: List<Float>, maxY: Int,
        color: Int, style: Paint.Style,
        leftPadding: Float, innerPadding: Float, paddingTop: Float
    ) {
        if (samples.size < 2 || maxY <= 0) return
        val ratioY = (height - innerPadding - paddingTop) / maxY
        val startY = height - innerPadding
        val ratioX = (width - leftPadding - innerPadding) / ((samples.size - 1) / 60f)

        paint.reset()
        paint.isAntiAlias = true
        paint.color = color
        paint.style = style
        paint.strokeWidth = 2f

        val path = Path()
        path.moveTo(leftPadding, startY)
        for ((index, sample) in samples.withIndex()) {
            val value = sample.coerceIn(0f, maxY.toFloat())
            val leftX = (if (index > 0) (index - 1) / 60f * ratioX else 0f) + leftPadding
            val rightX = index / 60f * ratioX + leftPadding
            val topY = startY - value * ratioY
            path.lineTo(leftX, startY)
            path.lineTo(leftX, topY)
            path.lineTo(rightX, topY)
            path.lineTo(rightX, startY)
        }
        canvas.drawPath(path, paint)
    }
}
