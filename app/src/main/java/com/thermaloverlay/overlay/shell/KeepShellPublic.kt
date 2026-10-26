/**
 * Manages named and default persistent root shell instances, balancing
 * commands across two default shells.
 */
package com.thermaloverlay.overlay.shell

object KeepShellPublic {
    private val keepShells = HashMap<String, KeepShell>()

    fun getInstance(key: String, rootMode: Boolean): KeepShell {
        synchronized(keepShells) {
            return keepShells.getOrPut(key) { KeepShell(rootMode) }
        }
    }

    val defaultKeepShell = KeepShell()
    val secondaryKeepShell = KeepShell()

    fun getDefaultInstance(): KeepShell {
        return if (defaultKeepShell.isIdle || !secondaryKeepShell.isIdle) {
            defaultKeepShell
        } else {
            secondaryKeepShell
        }
    }

    fun doCmdSync(cmd: String, timeoutMs: Long = KeepShell.DEFAULT_TIMEOUT_MS): String {
        return getDefaultInstance().doCmdSync(cmd, timeoutMs)
    }

    fun checkRoot(retryNow: Boolean = false): Boolean {
        if (retryNow) defaultKeepShell.resetBackoff()
        return defaultKeepShell.checkRoot()
    }

    /**
     * Releases the root processes that nobody is using, e.g. when the overlay
     * stops. They are respawned on demand by the next command.
     */
    fun releaseIdleShells() {
        defaultKeepShell.tryExitIfIdle()
        secondaryKeepShell.tryExitIfIdle()
        val others = synchronized(keepShells) { keepShells.values.toList() }
        others.forEach { it.tryExitIfIdle() }
    }
}
