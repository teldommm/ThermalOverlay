/**
 * Custom view: a ring indicator for CPU/GPU load.
 */
package com.thermaloverlay.overlay.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.thermaloverlay.overlay.R

class FloatMonitorChartView : View {
    private var ratio = 0
    private var ratioState = 0

    private var mRadius = 300f
    private var mStrokeWidth = 40f
    private var textSize = 20

    private var cyclePaint: Paint? = null
    private var textPaint: Paint? = null
    private var labelPaint: Paint? = null

    private val textColor = -0x777778

    private var mHeight: Int = 0
    private var mWidth: Int = 0

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        @SuppressLint("CustomViewStyleable") val array = context.obtainStyledAttributes(attrs, R.styleable.RamInfo)
        val total = array.getInteger(R.styleable.RamInfo_total, 1)
        val free = array.getInteger(R.styleable.RamInfo_free, 1)
        val freeRatio = (free * 100.0 / total).toInt()
        ratio = 100 - freeRatio
        array.recycle()
    }

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        @SuppressLint("CustomViewStyleable") val array = context.obtainStyledAttributes(attrs, R.styleable.RamInfo)
        val total = array.getInteger(R.styleable.RamInfo_total, 1)
        val free = array.getInteger(R.styleable.RamInfo_free, 1)
        val freeRatio = (free * 100.0 / total).toInt()
        ratio = freeRatio
        array.recycle()
    }

    private fun dp2px(context: Context, dpValue: Float): Int {
        val scale = context.resources.displayMetrics.density
        return (dpValue * scale + 0.5f).toInt()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        mWidth = w
        mHeight = h
        val strokeWidth = dp2px(context, 4f)
        mStrokeWidth = strokeWidth.toFloat()
        textSize = dp2px(context, 18f)
        mRadius = if (w > h) (h * 0.9 - strokeWidth).toInt().toFloat() else (w * 0.9 - strokeWidth).toInt().toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.translate(mWidth / 2 - mRadius / 2, mHeight / 2 - mRadius / 2)
        initPaint()
        drawCycle(canvas)
    }

    fun setData(total: Float, free: Float) {
        ratio = if (free == total && total == 0F) {
            0
        } else {
            val freeRatio = (free * 100.0 / total).toInt()
            100 - freeRatio
        }
        ratioState = ratio
        invalidate()
    }

    private fun initPaint() {
        cyclePaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = mStrokeWidth
        }
        textPaint = Paint().apply {
            isAntiAlias = true
            color = textColor
            style = Paint.Style.STROKE
            strokeWidth = 1f
            textSize = this@FloatMonitorChartView.textSize.toFloat()
        }
        labelPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            strokeWidth = 2f
        }
    }

    private fun drawCycle(canvas: Canvas) {
        cyclePaint!!.color = 0x22FFFFFF
        canvas.drawArc(RectF(0f, 0f, mRadius, mRadius), 0f, 360f, false, cyclePaint!!)

        cyclePaint!!.color = when {
            ratioState > 90 -> ContextCompat.getColor(context, R.color.color_load_veryhight)
            ratioState > 75 -> ContextCompat.getColor(context, R.color.color_load_hight)
            ratioState > 20 -> ContextCompat.getColor(context, R.color.color_load_mid)
            else -> ContextCompat.getColor(context, R.color.color_load_low)
        }

        cyclePaint!!.strokeCap = Paint.Cap.ROUND
        if (ratio < 1 && ratioState <= 2) {
            return
        } else if (ratioState >= 98) {
            canvas.drawArc(RectF(0f, 0f, mRadius, mRadius), -90f, 360f, false, cyclePaint!!)
        } else {
            canvas.drawArc(RectF(0f, 0f, mRadius, mRadius), -90f, (ratioState * 3.6f), false, cyclePaint!!)
        }
    }
}
