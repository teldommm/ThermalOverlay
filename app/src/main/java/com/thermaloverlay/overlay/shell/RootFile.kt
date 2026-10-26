/**
 * File existence checks and writes to root-owned files via the shared root
 * shell.
 *
 * The *OrNull probes return null when the shell failed (e.g. root not granted
 * yet). Callers must NOT cache a null as "does not exist" — retry later.
 */
package com.thermaloverlay.overlay.shell

import java.io.File

object RootFile {
    fun fileExistsOrNull(path: String): Boolean? = probe("-f", path) { it.isFile }

    fun dirExistsOrNull(path: String): Boolean? = probe("-d", path) { it.isDirectory }

    fun fileExists(path: String): Boolean = fileExistsOrNull(path) == true

    fun dirExists(path: String): Boolean = dirExistsOrNull(path) == true

    private inline fun probe(flag: String, path: String, direct: (File) -> Boolean): Boolean? {
        // A positive in-process answer is definitive. A negative one is not:
        // SELinux may hide nodes from the app that root can see.
        if (direct(File(path))) return true
        val r = SysReader.shellProvider().doCmdSync(
            "if [ $flag ${SysReader.shQuote(path)} ]; then echo 1; else echo 0; fi"
        )
        return when (r) {
            "1" -> true
            "0" -> false
            else -> null
        }
    }

    fun writeFile(path: String, value: String): Boolean {
        return SysReader.shellProvider().doCmdSync(
            "if echo ${SysReader.shQuote(value)} > ${SysReader.shQuote(path)} 2>/dev/null; then echo 1; fi"
        ) == "1"
    }
}
