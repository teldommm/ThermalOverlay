/**
 * Persists whether the overlay is currently running and the dual-battery
 * toggle, via SharedPreferences. UI-only state; not restored after reboot.
 */
package com.thermaloverlay.overlay

import android.content.Context

object OverlayPrefs {
    private const val PREFS = "perf_overlay_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_DUAL_BATTERY = "dual_battery"

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isDualBattery(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DUAL_BATTERY, false)
    }

    fun setDualBattery(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DUAL_BATTERY, enabled).apply()
    }
}
