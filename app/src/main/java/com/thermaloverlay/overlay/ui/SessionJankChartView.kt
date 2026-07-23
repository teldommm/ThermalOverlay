/**
 * Two charts built on our own frame-timing collection (FrameStatsUtils,
 * since the original's jank/frame-time source turned out to be a
 * proprietary bundled binary we don't have — see FrameStatsUtils' own
 * doc comment): frame time (worst frame per tick, ms, fixed 0-100 scale)
 * and jank/big-jank counts (dynamic scale, floored at 3, two series
 * overlaid). Both use the source's step/bar rendering style via
 * SessionChartRenderer.drawStepSeries rather than an interpolated line.
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
    enum class Kind { FRAME_TIME, JANK }

    private lateinit var store: FpsWatchStore
    private val paint = Paint()
    var kind: Kind = Kind.FRAME_TIME
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

    // Shared gridline drawing (time axis + y-axis with the given key
    // values) — both kinds need this, only the data/scale differs.
    private fun drawAxes(canvas: Canvas, sampleCount: Int, maxY: Int, keys: List<Int>, innerPadding: Float, paddingTop: Float, textSize: Float) {
        SessionChartRenderer.drawTimeAxis(canvas, paint, width, height, sampleCount, innerPadding, paddingTop, textSize)

        val ratioY = (height - innerPadding - paddingTop) / maxY
        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = textSize
        paint.textAlign = Paint.Align.RIGHT
        for (point in keys) {
            if (point > maxY) continue
            paint.color = Color.parseColor("#888888")
            if (point > 0) canvas.drawText(point.toString(), innerPadding - 4f, paddingTop + (maxY - point) * ratioY + textSize / 2.2f, paint)
            paint.strokeWidth = if (point == 0) 4f else 2f
            paint.color = if (point == 0) Color.parseColor("#888888") else Color.parseColor("#aa888888")
            canvas.drawLine(innerPadding, paddingTop + (maxY - point) * ratioY, width - innerPadding, paddingTop + (maxY - point) * ratioY, paint)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (sessionId < 1) return

        val innerPadding = dp2px(18f)
        val paddingTop = dp2px(4f)
        val textSize = dp2px(8.5f)

        when (kind) {
            Kind.FRAME_TIME -> {
                val samples = store.sessionFrameTimeData(sessionId)
                if (samples.isEmpty()) return
                val keys = listOf(0, 8, 16, 25, 33, 41, 50, 58, 66, 75, 83, 91, 100)
                drawAxes(canvas, samples.size, 100, keys, innerPadding, paddingTop, textSize)
                SessionChartRenderer.drawStepSeries(
                    canvas, paint, width, height, samples, 100,
                    Color.parseColor("#87d3ff"), Paint.Style.STROKE, innerPadding, paddingTop
                )
            }
            Kind.JANK -> {
                val jank = store.sessionJankData(sessionId)
                val bigJank = store.sessionBigJankData(sessionId)
                if (jank.isEmpty() || bigJank.isEmpty()) return
                val rawMax = jank.maxOrNull()?.toInt() ?: 0
                val maxY = maxOf(rawMax, 3)
                val keys = when {
                    maxY > 5 -> listOf(0, 5, 10, 15, 20)
                    maxY > 3 -> listOf(0, 3, 6, 9)
                    else -> listOf(0, 1, 2, 3)
                }
                drawAxes(canvas, jank.size, maxY, keys, innerPadding, paddingTop, textSize)
                SessionChartRenderer.drawStepSeries(
                    canvas, paint, width, height, jank, maxY,
                    Color.parseColor("#8087d3ff"), Paint.Style.STROKE, innerPadding, paddingTop
                )
                SessionChartRenderer.drawStepSeries(
                    canvas, paint, width, height, bigJank, maxY,
                    Color.parseColor("#FDB6E2"), Paint.Style.STROKE, innerPadding, paddingTop
                )
            }
        }
    }
}
