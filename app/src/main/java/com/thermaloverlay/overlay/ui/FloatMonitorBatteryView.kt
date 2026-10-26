/**
 * Ring indicator for battery charge, colored by temperature and low charge.
 */
package com.thermaloverlay.overlay.ui

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import androidx.core.content.ContextCompat
import com.thermaloverlay.overlay.R

class FloatMonitorBatteryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RingView(context, attrs, defStyleAttr) {

    private var temperature = DEFAULT_TEMPERATURE

    /** [temperature] in °C; NaN (not read yet) is treated as a normal value. */
    fun setData(level: Int, temperature: Double) {
        this.temperature = if (temperature.isNaN()) DEFAULT_TEMPERATURE else temperature
        setPercent(level)
        refresh()
    }

    override fun colorFor(percent: Int): Int = when {
        temperature >= 48 || percent < 11 -> Color.rgb(255, 15, 0)
        temperature > 44 || percent < 16 -> color(R.color.color_load_veryhight)
        temperature > 41 || percent < 31 -> color(R.color.color_load_hight)
        temperature > 20 -> color(R.color.color_load_mid)
        else -> color(R.color.color_load_low)
    }

    private fun color(res: Int) = ContextCompat.getColor(context, res)

    private companion object {
        const val DEFAULT_TEMPERATURE = 35.0
    }
}
