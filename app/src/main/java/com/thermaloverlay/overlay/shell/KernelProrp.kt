/**
 * Thin wrapper over KeepShellPublic for reading sysfs/procfs nodes.
 */
package com.thermaloverlay.overlay.shell

object KernelProrp {
    fun getProp(propName: String): String {
        return KeepShellPublic.doCmdSync("if [[ -e \"$propName\" ]]; then cat \"$propName\"; fi;")
    }

    fun getProp(propName: String, grep: String): String {
        return KeepShellPublic.doCmdSync("if [[ -e \"$propName\" ]]; then cat \"$propName\" | grep \"$grep\"; fi;")
    }
}
