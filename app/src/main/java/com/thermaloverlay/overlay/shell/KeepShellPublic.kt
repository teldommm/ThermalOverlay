/**
 * Manages named and default persistent root shell instances, balancing
 * commands across two default shells.
 */
package com.thermaloverlay.overlay.shell

object KeepShellPublic {
    private val keepShells = HashMap<String, KeepShell>()

    fun getInstance(key: String, rootMode: Boolean): KeepShell {
        synchronized(keepShells) {
            if (!keepShells.containsKey(key)) {
                keepShells[key] = KeepShell(rootMode)
            }
            return keepShells[key]!!
        }
    }

    fun destroyInstance(key: String) {
        synchronized(keepShells) {
            val keepShell = keepShells.remove(key) ?: return
            keepShell.tryExit()
        }
    }

    fun destroyAll() {
        synchronized(keepShells) {
            while (keepShells.isNotEmpty()) {
                val key = keepShells.keys.first()
                val keepShell = keepShells.remove(key)!!
                keepShell.tryExit()
            }
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

    fun doCmdSync(commands: List<String>): Boolean {
        val stringBuilder = StringBuilder()
        for (cmd in commands) {
            stringBuilder.append(cmd)
            stringBuilder.append("\n\n")
        }
        return doCmdSync(stringBuilder.toString()) != "error"
    }

    fun doCmdSync(cmd: String): String {
        return getDefaultInstance().doCmdSync(cmd)
    }

    fun checkRoot(): Boolean {
        return defaultKeepShell.checkRoot()
    }

    fun tryExit() {
        defaultKeepShell.tryExit()
        secondaryKeepShell.tryExit()
    }
}
