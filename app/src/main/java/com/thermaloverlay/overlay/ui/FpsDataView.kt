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

    // x-axis (time) gridlines/labels — same regardless of which right-hand
    // dimension is selected, since it only depends on sample count.
    private fun drawTimeAxis(canvas: Canvas, sampleCount: Int, innerPadding: Float, paddingTop: Float, textSize: Float) {
        // (n - 1), like every real chart: the last sample lands on the right edge
        val minutes = (sampleCount - 1) / 60.0
        if (minutes <= 0) return
        val columns = 5
        val scaleX = minutes / columns
        val ratioX = (width - innerPadding * 2) / minutes

        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = textSize
        paint.textAlign = Paint.Align.CENTER
        paint.style = Paint.Style.FILL
        paint.strokeWidth = 1f
        for (point in 0..columns) {
            // the source truncates before adding padding: `((int)(d2 * width)) + f3`
            val drawX = (point * scaleX * ratioX).toInt() + innerPadding
            paint.color = Color.parseColor("#888888")
            canvas.drawText(SessionChartRenderer.minutesLabel(point * scaleX), drawX, height - innerPadding + textSize + dp2px(2f), paint)
            paint.color = Color.parseColor("#40888888")
            canvas.drawLine(drawX, paddingTop, drawX, height - innerPadding, paint)
        }
    }

    // One series: y-axis gridlines/labels on the given side, plus the
    // connecting line through `samples`. `keyValues` are the only y-values
    // that get a tick; pass an empty list to draw just the line (used for
    // the GPU pass in the LOAD dimension, which shares the CPU pass's axis).
    //
    // `minY` lets the axis floor sit above 0 — the real TEMPERATURE
    // dimension does this (floor 10 instead of 0) in one specific case.
    // `dataRange`, when non-null, suppresses gridlines/labels whose value
    // sits more than 5 units outside [dataMin, dataMax] — again a
    // TEMPERATURE-only behavior in the source (h(), the real
    // CpuTemperatureView-equivalent branch): far-away tier gridlines are
    // skipped entirely rather than just left unlabeled.
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
        textSize: Float,
        minY: Int = 0,
        dataRange: Pair<Float, Float>? = null
    ) {
        if (samples.size < 2) return
        val ratioY = (height - innerPadding - paddingTop) / (maxY - minY)
        val startY = height - innerPadding

        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = textSize
        paint.strokeWidth = 2f
        paint.pathEffect = dashEffect
        paint.textAlign = if (axisOnRight) Paint.Align.LEFT else Paint.Align.RIGHT
        for (point in minY..maxY) {
            if (point !in keyValues) continue
            // Right-hand dimensions skip the gridline right at the very top
            // (it would sit under the FPS axis); the left/FPS axis draws
            // every tick including its own top.
            if (axisOnRight && point == maxY) continue
            if (dataRange != null && (point < dataRange.first - 5 || point > dataRange.second + 5)) continue
            // Right-axis dimension labels use #808080 in the source, only the
            // left/FPS axis uses #888888.
            paint.color = if (axisOnRight) Color.parseColor("#808080") else Color.parseColor("#888888")
            val labelX = if (axisOnRight) width - innerPadding + dp2px(8f) else innerPadding - dp2px(4f)
            val labelY = paddingTop + (maxY - point) * ratioY + textSize / 2.2f
            if (point > minY) canvas.drawText(point.toString(), labelX, labelY, paint)
            paint.strokeWidth = if (point == 0) 4f else 2f
            paint.color = if (point == 0 && zeroLineColor != null) zeroLineColor else gridColor
            canvas.drawLine(innerPadding, paddingTop + (maxY - point) * ratioY, width - innerPadding, paddingTop + (maxY - point) * ratioY, paint)
        }

        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        // Adaptive thickness (thinner for longer sessions, so the line
        // doesn't turn into a solid block) applies only to the primary/FPS
        // line; every right-axis dimension uses a fixed 4f regardless of
        // session length.
        paint.strokeWidth = if (axisOnRight) 4f else when {
            samples.size >= 1800 -> 2f
            samples.size >= 900 -> 4f
            else -> 8f
        }
        paint.pathEffect = null
        paint.color = lineColor
        val ratioX = (width - innerPadding * 2) / ((samples.size - 1) / 60f)
        var lastX = innerPadding
        var lastY = startY - (samples.first() - minY) * ratioY
        for ((index, sample) in samples.withIndex()) {
            val currentX = index / 60f * ratioX + innerPadding
            val currentY = startY - (sample - minY) * ratioY
            canvas.drawLine(lastX, lastY, currentX, currentY, paint)
            lastX = currentX
            lastY = currentY
        }
    }

    private fun fpsScale(samples: List<Float>): Pair<Int, List<Int>> {
        val maxValue = samples.maxOrNull()!!
        val maxValueInt = maxValue.toInt() + (if (maxValue % 1 == 0f) 1 else 0)
        return when {
            maxValueInt > 167 -> maxValueInt to listOf(0, 30, 60, 90, 120, 144, maxValueInt)
            maxValueInt > 146 -> 165 to listOf(0, 30, 60, 90, 120, 165)
            maxValueInt > 122 -> 144 to listOf(0, 30, 60, 90, 120, 144)
            maxValueInt > 92 -> 120 to listOf(0, 30, 60, 90, 120)
            maxValueInt > 62 -> 90 to listOf(0, 30, 60, 90)
            else -> 60 to listOf(0, 15, 30, 45, 60)
        }
    }

    // Returns (minY, maxY, keys). `fpsKeyCount` is the FPS dimension's own
    // gridline-key count (fpsScale(...).second.size) — the real source
    // (h()) shifts this axis's floor from 0 to 10 specifically when
    // maxY==50 and that count==5, compressing the visible range instead of
    // wasting space down to 0.
    private fun temperatureScale(samples: List<Float>, fpsKeyCount: Int): Triple<Int, Int, List<Int>> {
        val maxValue = samples.maxOrNull()!!
        val maxValueInt = maxValue.toInt() + (if (maxValue % 1 == 0f) 1 else 0)
        // Only capped/bucketed below 60 (into 50 or 60); above that the
        // source uses the raw max uncapped, unlike the four-tier cascade
        // this used to have.
        val maxY = if (maxValueInt > 60) maxValueInt else if (maxValueInt > 55) 60 else 50
        val minY = if (maxY == 50 && fpsKeyCount == 5) 10 else 0
        return Triple(minY, maxY, listOf(30, 35, 40, 45, 50, 55, 60, 65))
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
                    val (minY, maxY, keys) = temperatureScale(samples, fpsKeys.size)
                    val dataMin = samples.minOrNull()!!
                    val dataMax = samples.maxOrNull()!!
                    drawSeries(
                        canvas, samples, maxY, keys, axisOnRight = true,
                        lineColor = Color.parseColor("#8087d3ff"), gridColor = Color.parseColor("#4087d3ff"),
                        zeroLineColor = null, innerPadding, paddingTop, textSize,
                        minY = minY, dataRange = dataMin to dataMax
                    )
                }
            }
            Dimension.CAPACITY -> {
                val samples = store.sessionCapacityData(sessionId)
                if (samples.isNotEmpty()) {
                    drawSeries(
                        canvas, samples, 100, listOf(25, 50, 75, 90, 100), axisOnRight = true,
                        lineColor = Color.parseColor("#8087d3ff"), gridColor = Color.parseColor("#4087d3ff"),
                        zeroLineColor = null, innerPadding, paddingTop, textSize
                    )
                }
            }
            Dimension.LOAD -> {
                val cpuSamples = store.sessionCpuLoadData(sessionId)
                val gpuSamples = store.sessionGpuLoadData(sessionId)
                if (cpuSamples.isNotEmpty() && gpuSamples.isNotEmpty()) {
                    // Source's key set varies with which FPS-axis tier is
                    // active: the FPS scale's own gridline count is 4 or 5
                    // for its two lowest tiers (<=62, <=92) — in exactly
                    // those cases LOAD uses the sparser {25,50,75,100};
                    // every higher FPS tier (key count 6 or 7) uses the
                    // denser {20,40,60,80,100}. Confirmed directly from
                    // FpsDataView.f(): `i==5 || i==4 -> sparse`.
                    val loadKeys = if (fpsKeys.size == 4 || fpsKeys.size == 5)
                        listOf(25, 50, 75, 100) else listOf(20, 40, 60, 80, 100)
                    drawSeries(
                        canvas, cpuSamples, 100, loadKeys, axisOnRight = true,
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
