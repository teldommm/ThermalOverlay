/**
 * Reads the SoC platform name (ro.board.platform) via shell.
 */
package com.thermaloverlay.overlay.metrics

import com.thermaloverlay.overlay.shell.KeepShellPublic

class PlatformUtils {
    companion object {
        private var cpu: String? = null
    }

    fun getCPUName(): String {
        if (cpu == null) {
            cpu = KeepShellPublic.doCmdSync("getprop ro.board.platform").trim()
        }
        return cpu ?: ""
    }
}
