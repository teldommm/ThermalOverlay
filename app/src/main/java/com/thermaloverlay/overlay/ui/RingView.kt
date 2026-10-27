/**
 * Base for the HUD ring indicators: a faint full circle plus a rounded arc
 * showing a 0..100 percentage. Subclasses only decide the arc color.
 */
package com.thermaloverlay.overlay.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.thermaloverlay.overlay.R

abstract class RingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    protected var percent = 0
        private set

    private val oval = RectF()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.hud_ring_track)
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** Arc color for the current [percent]. */
    protected abstract fun colorFor(percent: Int): Int

    fun setPercent(value: Int) {
        val v = value.coerceIn(0, 100)
        if (v == percent) return
        percent = v
        invalidate()
    }

    /** Re-evaluates the color without changing the percentage. */
    protected fun refresh() = invalidate()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val strokeWidth = (4f * resources.displayMetrics.density + 0.5f).toInt().toFloat()
        trackPaint.strokeWidth = strokeWidth
        arcPaint.strokeWidth = strokeWidth
        val radius = ((minOf(w, h) * 0.9) - strokeWidth).toInt().toFloat()
        val left = (w - radius) / 2f
        val top = (h - radius) / 2f
        oval.set(left, top, left + radius, top + radius)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawArc(oval, 0f, 360f, false, trackPaint)

        if (percent < 1) return
        arcPaint.color = colorFor(percent)
        val sweep = if (percent >= 98) 360f else percent * 3.6f
        canvas.drawArc(oval, -90f, sweep, false, arcPaint)
    }
}
