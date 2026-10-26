/**
 * Process-wide knowledge about root access. The app cannot work without root,
 * so the main screen, the tile, the service and the frequency screen all
 * consult this before doing anything.
 *
 * null = not checked yet in this process, true/false = result of the last check.
 */
package com.thermaloverlay.overlay

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
}
