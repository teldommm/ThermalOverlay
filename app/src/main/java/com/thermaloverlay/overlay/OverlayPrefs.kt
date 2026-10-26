/**
 * Persists the dual-battery toggle via SharedPreferences. Whether the overlay
 * is running is not stored anywhere: see [OverlayState].
 */
package com.thermaloverlay.overlay

import android.content.Context

object OverlayPrefs {
    private const val PREFS = "perf_overlay_prefs"
    private const val KEY_DUAL_BATTERY = "dual_battery"

    fun isDualBattery(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DUAL_BATTERY, false)
    }

    fun setDualBattery(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DUAL_BATTERY, enabled).apply()
    }
}
