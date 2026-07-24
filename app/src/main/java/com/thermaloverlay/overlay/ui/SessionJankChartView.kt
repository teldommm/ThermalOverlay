/**
 * Frame-time chart for a recorded session: the worst frame of each tick in
 * milliseconds, on the source's fixed 0-100ms scale with its 8.33ms-multiple
 * gridlines, drawn in the step/bar style FrameTimeView uses rather than an
 * interpolated line. Data comes from our own FrameStatsUtils collection —
 * see its doc comment for why the original's source is unavailable.
 */
package com.thermaloverlay.overlay.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.thermaloverlay.overlay.store.FpsWatchStore

class SessionJankChartView : View {
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

    // Shared gridline drawing (time axis + y-axis with the given key
    // values) — both kinds need this, only the data/scale differs.
    private fun drawAxes(canvas: Canvas, sampleCount: Int, maxY: Int, keys: List<Int>, leftPadding: Float, innerPadding: Float, paddingTop: Float, textSize: Float) {
        SessionChartRenderer.drawTimeAxis(canvas, paint, width, height, sampleCount, leftPadding, innerPadding, paddingTop, textSize)

        val ratioY = (height - innerPadding - paddingTop) / maxY
        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = textSize
        paint.textAlign = Paint.Align.RIGHT
        for (point in keys) {
            if (point > maxY) continue
            paint.color = Color.parseColor("#888888")
            if (point > 0) canvas.drawText(point.toString(), leftPadding - 4f, paddingTop + (maxY - point) * ratioY + textSize / 2.2f, paint)
            paint.strokeWidth = if (point == 0) 4f else 2f
            paint.color = if (point == 0) Color.parseColor("#888888") else Color.parseColor("#aa888888")
            canvas.drawLine(leftPadding, paddingTop + (maxY - point) * ratioY, width - innerPadding, paddingTop + (maxY - point) * ratioY, paint)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (sessionId < 1) return

        val innerPadding = dp2px(18f)
        val paddingTop = dp2px(4f)
        val textSize = dp2px(8.5f)
        // real FrameTimeView measures the label but adds no 4dp margin
        val leftPadding = SessionChartRenderer.axisLabelPadding(
            paint, 100, textSize, context.resources.displayMetrics.density, extraDp = 0f)

        val samples = store.sessionFrameTimeData(sessionId)
        if (samples.isEmpty()) return
        val keys = listOf(0, 8, 16, 25, 33, 41, 50, 58, 66, 75, 83, 91, 100)
        drawAxes(canvas, samples.size, 100, keys, leftPadding, innerPadding, paddingTop, textSize)
        SessionChartRenderer.drawStepSeries(
            canvas, paint, width, height, samples, 100,
            Color.parseColor("#87d3ff"), Paint.Style.STROKE, leftPadding, innerPadding, paddingTop
        )
    }
}
