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
    private const val KEY_MINI_ENABLED = "mini_monitor_enabled"
    private const val KEY_PROCESS_ENABLED = "process_monitor_enabled"
    private const val KEY_THREAD_ENABLED = "thread_monitor_enabled"
    private const val KEY_FPS_RECORDER_ENABLED = "fps_recorder_enabled"

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isMiniMonitorEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_MINI_ENABLED, false)
    }

    fun setMiniMonitorEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_MINI_ENABLED, enabled).apply()
    }

    fun isProcessMonitorEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PROCESS_ENABLED, false)
    }

    fun setProcessMonitorEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PROCESS_ENABLED, enabled).apply()
    }

    fun isThreadMonitorEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_THREAD_ENABLED, false)
    }

    fun setThreadMonitorEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_THREAD_ENABLED, enabled).apply()
    }

    fun isFpsRecorderEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FPS_RECORDER_ENABLED, false)
    }

    fun setFpsRecorderEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_FPS_RECORDER_ENABLED, enabled).apply()
    }

    fun isDualBattery(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DUAL_BATTERY, false)
    }

    fun setDualBattery(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DUAL_BATTERY, enabled).apply()
    }
}
