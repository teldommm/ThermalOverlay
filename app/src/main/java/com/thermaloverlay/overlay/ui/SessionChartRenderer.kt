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

    fun minutesLabel(minutes: Double): String {
        return when {
            minutes >= 1140 -> "${(minutes / 1140).toInt()}d${((minutes % 1140) / 60).toInt()}h"
            minutes > 60 -> "${(minutes / 60).toInt()}h${(minutes % 60).toInt()}m"
            minutes == 0.0 -> "0"
            minutes >= 1 -> "${minutes.toInt()}m${(minutes % 1 * 60).toInt()}s"
            else -> "${(minutes * 60).toInt()}s"
        }
    }

    fun drawTimeAxis(
        canvas: Canvas, paint: Paint, width: Int, height: Int,
        sampleCount: Int, innerPadding: Float, paddingTop: Float, textSize: Float
    ) {
        val minutes = sampleCount / 60.0
        if (minutes <= 0) return
        val columns = 5
        val scaleX = minutes / columns
        val ratioX = (width - innerPadding * 2) / minutes

        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = textSize
        paint.textAlign = Paint.Align.CENTER
        paint.style = Paint.Style.FILL
        for (point in 0..columns) {
            val drawX = (point * scaleX * ratioX).toFloat() + innerPadding
            paint.color = Color.parseColor("#888888")
            canvas.drawText(minutesLabel(point * scaleX), drawX, height - innerPadding + textSize + 2f, paint)
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
        lineColor: Int, innerPadding: Float, paddingTop: Float, textSize: Float
    ) {
        if (samples.isEmpty() || maxY <= 0) return
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
            val labelX = innerPadding - 4f
            val labelY = paddingTop + (maxY - point) * ratioY + textSize / 2.2f
            if (point > 0) canvas.drawText(point.toString(), labelX, labelY, paint)
            paint.strokeWidth = if (point == 0) 4f else 2f
            paint.color = if (point == 0) Color.parseColor("#888888") else Color.parseColor("#aa888888")
            canvas.drawLine(innerPadding, paddingTop + (maxY - point) * ratioY, width - innerPadding, paddingTop + (maxY - point) * ratioY, paint)
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
        val ratioX = (width - innerPadding * 2) / (samples.size / 60f)
        var lastX = innerPadding
        var lastY = startY - samples.first().coerceAtLeast(0f) * ratioY
        for ((index, sample) in samples.withIndex()) {
            val value = sample.coerceAtLeast(0f)
            val currentX = index / 60f * ratioX + innerPadding
            val currentY = startY - value * ratioY
            canvas.drawLine(lastX, lastY, currentX, currentY, paint)
            lastX = currentX
            lastY = currentY
        }
        val endX = (samples.size / 60f) * ratioX + innerPadding
        canvas.drawLine(lastX, lastY, endX, startY - samples.last().coerceAtLeast(0f) * ratioY, paint)
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
        innerPadding: Float, paddingTop: Float, textSize: Float
    ) {
        if (samples.isEmpty() || maxY <= 0) return
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
            val labelX = if (axisOnRight) width - innerPadding + 8f else innerPadding - 4f
            val labelY = paddingTop + (maxY - point) * ratioY + textSize / 2.2f
            if (point > 0) canvas.drawText(point.toString(), labelX, labelY, paint)
            if (axisOnRight && point == maxY) continue
            paint.strokeWidth = if (point == 0) 4f else 2f
            paint.color = if (point == 0 && zeroLineColor != null) zeroLineColor else gridColor
            canvas.drawLine(innerPadding, paddingTop + (maxY - point) * ratioY, width - innerPadding, paddingTop + (maxY - point) * ratioY, paint)
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
        val ratioX = (width - innerPadding * 2) / (samples.size / 60f)
        var lastX = innerPadding
        var lastY = startY - samples.first().coerceAtLeast(0f) * ratioY
        for ((index, sample) in samples.withIndex()) {
            val value = sample.coerceAtLeast(0f)
            val currentX = index / 60f * ratioX + innerPadding
            val currentY = startY - value * ratioY
            canvas.drawLine(lastX, lastY, currentX, currentY, paint)
            lastX = currentX
            lastY = currentY
        }
        val endX = (samples.size / 60f) * ratioX + innerPadding
        canvas.drawLine(lastX, lastY, endX, startY - samples.last().coerceAtLeast(0f) * ratioY, paint)
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
        innerPadding: Float, paddingTop: Float, textSize: Float
    ) {
        if (seriesList.isEmpty() || maxY <= 0 || sampleCountForAxis <= 0) return
        val ratioY = (height - innerPadding - paddingTop) / maxY
        val startY = height - innerPadding
        val ratioX = (width - innerPadding * 2) / (sampleCountForAxis / 60f)

        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = textSize
        paint.strokeWidth = 2f
        paint.pathEffect = dashEffect
        paint.textAlign = Paint.Align.RIGHT
        for (point in 0..maxY) {
            if (point !in keyValues) continue
            paint.color = Color.parseColor("#888888")
            if (point > 0) canvas.drawText(point.toString(), innerPadding - 4f, paddingTop + (maxY - point) * ratioY + textSize / 2.2f, paint)
            paint.strokeWidth = if (point == 0) 4f else 2f
            paint.color = if (point == 0) Color.parseColor("#888888") else Color.parseColor("#aa888888")
            canvas.drawLine(innerPadding, paddingTop + (maxY - point) * ratioY, width - innerPadding, paddingTop + (maxY - point) * ratioY, paint)
        }

        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.pathEffect = null
        for ((seriesIndex, series) in seriesList.withIndex()) {
            if (series.isEmpty()) continue
            paint.color = colorForSeries(seriesIndex)
            paint.strokeWidth = strokeWidthForSeries(seriesIndex)
            var lastX = innerPadding
            var lastY = startY - series.first().coerceIn(0f, maxY.toFloat()) * ratioY
            for ((index, sample) in series.withIndex()) {
                val value = sample.coerceIn(0f, maxY.toFloat())
                val currentX = index / 60f * ratioX + innerPadding
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
        innerPadding: Float, paddingTop: Float
    ) {
        if (samples.isEmpty() || maxY <= 0) return
        val ratioY = (height - innerPadding - paddingTop) / maxY
        val startY = height - innerPadding
        val ratioX = (width - innerPadding * 2) / (samples.size / 60f)

        paint.reset()
        paint.isAntiAlias = true
        paint.color = color
        paint.style = style
        paint.strokeWidth = 2f

        val path = Path()
        path.moveTo(innerPadding, startY)
        for ((index, sample) in samples.withIndex()) {
            val value = sample.coerceIn(0f, maxY.toFloat())
            val leftX = (if (index > 0) (index - 1) / 60f * ratioX else 0f) + innerPadding
            val rightX = index / 60f * ratioX + innerPadding
            val topY = startY - value * ratioY
            path.lineTo(leftX, startY)
            path.lineTo(leftX, topY)
            path.lineTo(rightX, topY)
            path.lineTo(rightX, startY)
        }
        canvas.drawPath(path, paint)
    }
}
