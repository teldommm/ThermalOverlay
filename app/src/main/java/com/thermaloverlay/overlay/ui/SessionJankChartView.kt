/**
 * Two step/bar charts for a recorded session, sharing one View like
 * SessionLineChartView's Kind pattern:
 *
 * - FRAME_TIME: the worst frame of each tick in milliseconds, on the
 *   source's fixed 0-100ms scale with its 8.33ms-multiple gridlines
 *   (matches real FrameTimeView). Data comes from our own FrameStatsUtils
 *   collection — see its doc comment for why the original's source is
 *   unavailable.
 * - JANK: jank + big-jank counts per tick, overlaid as two step charts
 *   (matches real FpsJankView). Data was already being captured and
 *   stored by FrameStatsUtils/FpsWatchStore (jank_count/big_jank_count,
 *   schema since v3) but never read back or rendered until now.
 *   The y-axis scale is NOT based on the session's max jank value — the
 *   source scales off the LAST sample only (`arrayListU.last()`, i.e.
 *   whatever jank count the most recent tick had), floored at 3. This
 *   means the axis can visually re-scale as a session plays out; kept
 *   exactly as the source does it rather than "fixed" to an overall max.
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
    private val density: Float get() = context.resources.displayMetrics.density

    private fun drawAxes(canvas: Canvas, sampleCount: Int, maxY: Int, keys: List<Int>, leftPadding: Float, innerPadding: Float, paddingTop: Float, textSize: Float,
                        labelOffset: Float) {
        SessionChartRenderer.drawTimeAxis(canvas, paint, width, height, sampleCount, leftPadding, innerPadding, paddingTop, textSize, density)

        val ratioY = (height - innerPadding - paddingTop) / maxY
        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = textSize
        paint.textAlign = Paint.Align.RIGHT
        for (point in keys) {
            if (point > maxY) continue
            paint.color = Color.parseColor("#888888")
            val gridY = paddingTop + ((maxY - point) * ratioY).toInt()
            if (point > 0) canvas.drawText(point.toString(), leftPadding - labelOffset, gridY + textSize / 2.2f, paint)
            paint.strokeWidth = if (point == 0) 4f else 2f
            paint.color = if (point == 0) Color.parseColor("#888888") else Color.parseColor("#aa888888")
            canvas.drawLine(leftPadding, gridY, width - innerPadding, gridY, paint)
        }
    }

    private fun drawFrameTime(canvas: Canvas, innerPadding: Float, paddingTop: Float, textSize: Float) {
        // FrameTimeView reaches the same place by a different route:
        // `fMeasureText = measureText("999")` then `f5 = fMeasureText + f4`
        // with f4 = 4dp — so it is not a special case after all.
        val leftPadding = SessionChartRenderer.axisLabelPadding(paint, 100, textSize, density)
        val labelOffset = 2f * density

        val samples = store.sessionFrameTimeData(sessionId)
        if (samples.isEmpty()) return
        val keys = listOf(0, 8, 16, 25, 33, 41, 50, 58, 66, 75, 83, 91, 100)
        drawAxes(canvas, samples.size, 100, keys, leftPadding, innerPadding, paddingTop, textSize, labelOffset)
        SessionChartRenderer.drawStepSeries(
            canvas, paint, width, height, samples, 100,
            Color.parseColor("#87d3ff"), Paint.Style.STROKE, leftPadding, innerPadding, paddingTop
        )
    }

    // maxY/keys rule ported from real FpsJankView.e(): scale is driven by
    // the LAST jank sample (not the session max), floored at 3.
    private fun jankScale(lastJank: Int): Pair<Int, List<Int>> {
        val maxY = if (lastJank > 3) lastJank else 3
        val keys = when {
            maxY > 5 -> listOf(0, 5, 10, 15, 20)
            maxY > 3 -> listOf(0, 3, 6, 9)
            else -> listOf(0, 1, 2, 3)
        }
        return maxY to keys
    }

    private fun drawJank(canvas: Canvas, innerPadding: Float, paddingTop: Float, textSize: Float) {
        val jank = store.sessionJankData(sessionId)
        val bigJank = store.sessionBigJankData(sessionId)
        if (jank.isEmpty() || bigJank.isEmpty()) return

        val (maxY, keys) = jankScale(jank.last().toInt())
        val leftPadding = SessionChartRenderer.axisLabelPadding(paint, maxY, textSize, density)
        val labelOffset = 2f * density

        drawAxes(canvas, jank.size, maxY, keys, leftPadding, innerPadding, paddingTop, textSize, labelOffset)
        // Jank first, big-jank drawn on top — matches the source's draw
        // order (py0.U() series, then py0.v() series).
        SessionChartRenderer.drawStepSeries(
            canvas, paint, width, height, jank, maxY,
            Color.parseColor("#8087d3ff"), Paint.Style.STROKE, leftPadding, innerPadding, paddingTop
        )
        SessionChartRenderer.drawStepSeries(
            canvas, paint, width, height, bigJank, maxY,
            Color.parseColor("#FDB6E2"), Paint.Style.STROKE, leftPadding, innerPadding, paddingTop
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (sessionId < 1) return

        val innerPadding = dp2px(18f)
        val paddingTop = dp2px(4f)
        val textSize = dp2px(8.5f)

        when (kind) {
            Kind.FRAME_TIME -> drawFrameTime(canvas, innerPadding, paddingTop, textSize)
            Kind.JANK -> drawJank(canvas, innerPadding, paddingTop, textSize)
        }
    }
}
