/**
 * Line chart for a recorded FPS session: FPS always on the left axis, plus
 * a switchable right axis (temperature / battery / CPU+GPU load).
 *
 * consolidated: the source has four
 * almost-identical ~70-line functions (one per dimension) that each redraw
 * the same "gridlines + labels + connecting line" logic with only the
 * value range, tick values, and colors differing. That's collapsed here
 * into one drawSeries() the four call sites parameterize, plus a
 * drawTimeAxis() for the x-axis (shared regardless of which right-hand
 * dimension is selected). Visual output and scaling rules are unchanged.
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

class FpsDataView : View {
    enum class Dimension { TEMPERATURE, LOAD, CAPACITY }

    private lateinit var store: FpsWatchStore
    private val paint = Paint()
    private val dashEffect = DashPathEffect(floatArrayOf(4f, 8f), 0f)
    private var rightDimension = Dimension.values().first()
    private var sessionId = 0L

    constructor(context: Context) : super(context) {
        store = FpsWatchStore(context)
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        store = FpsWatchStore(context)
    }

    constructor(context: Context, attrs: AttributeSet, defStyle: Int) : super(context, attrs, defStyle) {
        store = FpsWatchStore(context)
    }

    fun setSessionId(id: Long) {
        if (sessionId != id) {
            sessionId = id
            invalidate()
        }
    }

    fun getSessionId(): Long = sessionId

    fun setRightDimension(dimension: Dimension) {
        if (rightDimension != dimension) {
            rightDimension = dimension
            invalidate()
        }
    }

    fun getRightDimension(): Dimension = rightDimension

    private fun dp2px(value: Float): Float {
        return value * context.resources.displayMetrics.density
    }

    private fun minutesLabel(minutes: Double): String {
        return when {
            minutes >= 1140 -> "${(minutes / 1140).toInt()}d${((minutes % 1140) / 60).toInt()}h"
            minutes > 60 -> "${(minutes / 60).toInt()}h${(minutes % 60).toInt()}m"
            minutes == 0.0 -> "0"
            minutes >= 1 -> "${minutes.toInt()}m${(minutes % 1 * 60).toInt()}s"
            else -> "${(minutes * 60).toInt()}s"
        }
    }

    // x-axis (time) gridlines/labels — same regardless of which right-hand
    // dimension is selected, since it only depends on sample count.
    private fun drawTimeAxis(canvas: Canvas, sampleCount: Int, innerPadding: Float, paddingTop: Float, textSize: Float) {
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
            canvas.drawText(minutesLabel(point * scaleX), drawX, height - innerPadding + textSize + dp2px(2f), paint)
            paint.color = Color.parseColor("#40888888")
            canvas.drawLine(drawX, paddingTop, drawX, height - innerPadding, paint)
        }
    }

    // One series: y-axis gridlines/labels on the given side, plus the
    // connecting line through `samples`. `keyValues` are the only y-values
    // that get a tick; pass an empty list to draw just the line (used for
    // the GPU pass in the LOAD dimension, which shares the CPU pass's axis).
    private fun drawSeries(
        canvas: Canvas,
        samples: List<Float>,
        maxY: Int,
        keyValues: List<Int>,
        axisOnRight: Boolean,
        lineColor: Int,
        gridColor: Int,
        zeroLineColor: Int?,
        innerPadding: Float,
        paddingTop: Float,
        textSize: Float
    ) {
        if (samples.isEmpty()) return
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
            paint.color = Color.parseColor("#888888")
            val labelX = if (axisOnRight) width - innerPadding + dp2px(8f) else innerPadding - dp2px(4f)
            val labelY = paddingTop + (maxY - point) * ratioY + textSize / 2.2f
            if (point > 0) canvas.drawText(point.toString(), labelX, labelY, paint)
            // Right-hand dimensions skip the gridline right at the very top
            // (it would sit under the FPS axis); the left/FPS axis draws
            // every tick including its own top.
            if (axisOnRight && point == maxY) continue
            paint.strokeWidth = if (point == 0) 4f else 2f
            paint.color = if (point == 0 && zeroLineColor != null) zeroLineColor else gridColor
            canvas.drawLine(innerPadding, paddingTop + (maxY - point) * ratioY, width - innerPadding, paddingTop + (maxY - point) * ratioY, paint)
        }

        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.strokeWidth = 8f
        paint.pathEffect = null
        paint.color = lineColor
        val ratioX = (width - innerPadding * 2) / (samples.size / 60f)
        var lastX = innerPadding
        var lastY = startY - samples.first() * ratioY
        for ((index, sample) in samples.withIndex()) {
            val currentX = index / 60f * ratioX + innerPadding
            val currentY = startY - sample * ratioY
            canvas.drawLine(lastX, lastY, currentX, currentY, paint)
            lastX = currentX
            lastY = currentY
        }
    }

    private fun fpsScale(samples: List<Float>): Pair<Int, List<Int>> {
        val maxValue = samples.maxOrNull()!!
        val maxValueInt = maxValue.toInt() + (if (maxValue % 1 == 0f) 1 else 0)
        return when {
            maxValueInt > 160 -> maxValueInt to listOf(0, 30, 60, 90, 120, maxValueInt)
            maxValueInt > 144 -> 160 to listOf(0, 30, 60, 90, 120, 160)
            maxValueInt > 120 -> 144 to listOf(0, 30, 60, 90, 120, 144)
            maxValueInt > 90 -> 120 to listOf(0, 30, 60, 90, 120)
            maxValueInt > 60 -> 90 to listOf(0, 30, 60, 90)
            else -> 60 to listOf(0, 20, 40, 60)
        }
    }

    private fun temperatureScale(samples: List<Float>): Pair<Int, List<Int>> {
        val maxValue = samples.maxOrNull()!!
        val maxValueInt = maxValue.toInt() + (if (maxValue % 1 == 0f) 1 else 0)
        val maxY = when {
            maxValueInt > 60 -> maxValueInt
            maxValueInt > 50 -> 55
            maxValueInt > 45 -> 50
            else -> 45
        }
        return maxY to listOf(35, 40, 45, 50, 55, 60)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (sessionId < 1) return

        val innerPadding = dp2px(18f)
        val paddingTop = dp2px(4f)
        val textSize = dp2px(8.5f)

        val fpsSamples = store.sessionFpsData(sessionId)
        if (fpsSamples.isEmpty()) return

        drawTimeAxis(canvas, fpsSamples.size, innerPadding, paddingTop, textSize)
        val (fpsMaxY, fpsKeys) = fpsScale(fpsSamples)
        drawSeries(
            canvas, fpsSamples, fpsMaxY, fpsKeys, axisOnRight = false,
            lineColor = Color.parseColor("#80808080"), gridColor = Color.parseColor("#aa888888"),
            zeroLineColor = Color.parseColor("#888888"), innerPadding, paddingTop, textSize
        )

        when (rightDimension) {
            Dimension.TEMPERATURE -> {
                val samples = store.sessionTemperatureData(sessionId)
                if (samples.isNotEmpty()) {
                    val (maxY, keys) = temperatureScale(samples)
                    drawSeries(
                        canvas, samples, maxY, keys, axisOnRight = true,
                        lineColor = Color.parseColor("#8087d3ff"), gridColor = Color.parseColor("#4087d3ff"),
                        zeroLineColor = null, innerPadding, paddingTop, textSize
                    )
                }
            }
            Dimension.CAPACITY -> {
                val samples = store.sessionCapacityData(sessionId)
                if (samples.isNotEmpty()) {
                    drawSeries(
                        canvas, samples, 100, listOf(50, 75, 90, 100), axisOnRight = true,
                        lineColor = Color.parseColor("#8087d3ff"), gridColor = Color.parseColor("#4087d3ff"),
                        zeroLineColor = null, innerPadding, paddingTop, textSize
                    )
                }
            }
            Dimension.LOAD -> {
                val cpuSamples = store.sessionCpuLoadData(sessionId)
                val gpuSamples = store.sessionGpuLoadData(sessionId)
                if (cpuSamples.isNotEmpty() && gpuSamples.isNotEmpty()) {
                    drawSeries(
                        canvas, cpuSamples, 100, listOf(50, 75, 90, 100), axisOnRight = true,
                        lineColor = Color.parseColor("#80fc6bc5"), gridColor = Color.parseColor("#4087d3ff"),
                        zeroLineColor = null, innerPadding, paddingTop, textSize
                    )
                    // Shares the CPU pass's axis (same 0-100 scale) — GPU only needs its own line.
                    drawSeries(
                        canvas, gpuSamples, 100, emptyList(), axisOnRight = true,
                        lineColor = Color.parseColor("#8087d3ff"), gridColor = Color.TRANSPARENT,
                        zeroLineColor = null, innerPadding, paddingTop, textSize
                    )
                }
            }
        }
    }
}
