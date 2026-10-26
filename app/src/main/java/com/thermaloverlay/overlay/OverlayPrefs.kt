/**
 * Persists the dual-battery toggle and the "overlay wanted" flag via
 * SharedPreferences. Whether the overlay is running right now is not stored:
 * see [OverlayState]. "Wanted" is what the user last asked for (on until they
 * stop it), which lets the service come back after the system killed it.
 */
package com.thermaloverlay.overlay

import android.content.Context

object OverlayPrefs {
    private const val PREFS = "perf_overlay_prefs"
    private const val KEY_DUAL_BATTERY = "dual_battery"
    private const val KEY_WANTED = "overlay_wanted"
    private const val KEY_BG_EXEMPT = "bg_exempt_done"

    fun isDualBattery(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DUAL_BATTERY, false)
    }

    fun setDualBattery(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DUAL_BATTERY, enabled).apply()
    }

    fun isWanted(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_WANTED, false)
    }

    fun setWanted(context: Context, wanted: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_WANTED, wanted).apply()
    }

    fun isBackgroundExemptDone(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BG_EXEMPT, false)
    }

    fun setBackgroundExemptDone(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_BG_EXEMPT, true).apply()
    }
}
