/**
 * Process-wide knowledge about root access. The app cannot work without root,
 * so the main screen, the tile, the service and the frequency screen all
 * consult this before doing anything.
 *
 * null = not checked yet in this process, true/false = result of the last check.
 */
package com.thermaloverlay.overlay

import android.content.Context
import android.provider.Settings
import com.thermaloverlay.overlay.shell.KeepShellPublic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object RootState {
    private val _granted = MutableStateFlow<Boolean?>(null)

    val granted: StateFlow<Boolean?> = _granted

    val isGranted: Boolean get() = _granted.value == true

    /**
     * Blocking: may wait for the user to answer the root manager's prompt, so
     * never call it on the main thread. [retryNow] skips the short pause that
     * follows a failed su start (use it when the user asked to check again).
     */
    fun check(retryNow: Boolean = false): Boolean {
        val ok = try {
            KeepShellPublic.checkRoot(retryNow)
        } catch (ex: Exception) {
            false
        }
        _granted.value = ok
        return ok
    }

    /**
     * "Display over other apps" is an app-op, and root may set app-ops, so
     * the user never has to open the system settings: if the permission is
     * missing it is granted here. Blocking (runs a root command), never call
     * it on the main thread. Returns whether the permission is held afterwards;
     * false means it could not be granted (no root, or a ROM that guards the
     * op itself) and the settings screen is the fallback.
     */
    fun ensureOverlayPermission(context: Context): Boolean {
        if (Settings.canDrawOverlays(context)) return true
        if (!isGranted) return false
        val pkg = context.packageName
        try {
            KeepShellPublic.doCmdSync(
                "appops set $pkg SYSTEM_ALERT_WINDOW allow 2>/dev/null || " +
                    "cmd appops set $pkg SYSTEM_ALERT_WINDOW allow 2>/dev/null"
            )
        } catch (ignored: Exception) {
        }
        return Settings.canDrawOverlays(context)
    }

    /**
     * Asks the system to leave the app alone in the background: out of Doze /
     * battery optimisation and allowed to run in the background. Root can set
     * both directly. Blocking, never on the main thread. Idempotent; the
     * caller remembers that it was done.
     */
    fun ensureBackgroundExemptions(context: Context) {
        if (!isGranted) return
        val pkg = context.packageName
        try {
            KeepShellPublic.doCmdSync(
                "dumpsys deviceidle whitelist +$pkg >/dev/null 2>&1; " +
                    "appops set $pkg RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1; " +
                    "appops set $pkg RUN_IN_BACKGROUND allow >/dev/null 2>&1"
            )
        } catch (ignored: Exception) {
        }
    }
}
