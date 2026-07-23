/**
 * Tiny inline bar chart used by the mini monitor: either a per-core
 * snapshot (setData — one bar per core, replaced whole each tick) or a
 * rolling history strip (pushRolling — one new reading pushed in, oldest
 * dropped), sharing the same rendering: narrow rounded-rect bars, color
 * and opacity scaling with load. Ported from vtools' CpuChartBarView.
 *
 * The source class's onDraw came through jadx with some duplicated-looking
 * branches (its own decompiler warnings flagged this) that didn't survive
 * translation cleanly; this reimplements the same visual algorithm — bar
 * height from load%, color thresholds at 65/85%, alpha scaling with load,
 * a small gap between bars — rather than transliterating a decompiler
 * artifact literally.
 */
package com.thermaloverlay.overlay.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.util.LinkedList

class CpuChartBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private var historySize = 5
    private var minAlpha = 35
    private var maxAlpha = 255
    private var accentColor = Color.parseColor("#4CAF50")
    private val veryHighColor = Color.parseColor("#f9592f")
    private val highColor = Color.parseColor("#fc8a1b")

    private var barWidth = 0f
    private var paint: Paint? = null
    private val values = LinkedList<Int>().apply { repeat(historySize) { add(0) } }

    fun setAccentColor(color: Int) {
        accentColor = color
    }

    fun setMinAlpha(value: Int) {
        minAlpha = value
    }

    fun setMaxAlpha(value: Int) {
        maxAlpha = value
    }

    fun setMaxHistory(count: Int) {
        if (count <= 0) return
        historySize = count
        while (values.size < count) values.add(0)
        while (values.size > count) values.poll()
        if (width > 0) barWidth = width / count.toFloat()
    }

    // Per-core snapshot: replaces the whole bar set at once (one bar per
    // core, in core-index order).
    fun setData(data: Array<Int>) {
        if (data.isEmpty()) return
        historySize = data.size
        values.clear()
        values.addAll(data)
        if (width > 0) barWidth = width / historySize.toFloat()
        invalidate()
    }

    // Rolling history: pushes one new reading, dropping the oldest once
    // over capacity — used for the GPU strip, which has only one value
    // per tick rather than one per core.
    fun pushRolling(max: Float, current: Float) {
        val percent = if (max == 0f && current == 0f) {
            0
        } else {
            100 - ((current * 100.0 / max).toInt())
        }
        values.add(percent)
        while (values.size > historySize) values.poll()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = paint ?: Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            strokeWidth = 0f
        }.also {
            paint = it
            if (width > 0) barWidth = width / historySize.toFloat()
        }

        val viewHeight = height.toFloat()
        for ((index, value) in values.withIndex()) {
            p.color = when {
                value > 85 -> veryHighColor
                value > 65 -> highColor
                else -> accentColor
            }
            p.alpha = (minAlpha + (value / 100f / 2f * 255).toInt()).coerceAtMost(maxAlpha)

            val barTop = when {
                value <= 5 -> viewHeight - viewHeight / 20f
                value >= 98 -> 0f
                else -> (100 - value) * viewHeight / 100f
            }
            val left = index * barWidth
            canvas.drawRoundRect(
                left + barWidth * 0.05f, barTop,
                left + barWidth * 0.95f, viewHeight,
                5f, 5f, p
            )
        }
    }
}
