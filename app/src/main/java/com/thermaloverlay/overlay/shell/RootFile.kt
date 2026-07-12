/**
 * File existence checks and writes to root-owned files via the shared root
 * shell.
 */
package com.thermaloverlay.overlay.shell

object RootFile {
    fun fileExists(path: String): Boolean {
        return KeepShellPublic.doCmdSync("if [[ -f \"$path\" ]]; then echo 1; fi;") == "1"
    }

    fun dirExists(path: String): Boolean {
        return KeepShellPublic.doCmdSync("if [[ -d \"$path\" ]]; then echo 1; fi;") == "1"
    }

    fun writeFile(path: String, value: String): Boolean {
        return KeepShellPublic.doCmdSync(
            "if echo '$value' > \"$path\" 2>/dev/null; then echo 1; fi;"
        ) == "1"
    }
}
