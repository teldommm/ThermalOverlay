/**
 * Ring indicator for CPU/GPU load: green when idle, red when saturated.
 */
package com.thermaloverlay.overlay.ui

import android.content.Context
import android.util.AttributeSet
import androidx.core.content.ContextCompat
import com.thermaloverlay.overlay.R

class FloatMonitorChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RingView(context, attrs, defStyleAttr) {

    override fun colorFor(percent: Int): Int = ContextCompat.getColor(
        context,
        when {
            percent > 90 -> R.color.color_load_veryhight
            percent > 75 -> R.color.color_load_hight
            percent > 20 -> R.color.color_load_mid
            else -> R.color.color_load_low
        }
    )
}
