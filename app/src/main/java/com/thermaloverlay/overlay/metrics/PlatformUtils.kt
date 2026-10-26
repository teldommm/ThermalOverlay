/**
 * Reads the SoC platform name (ro.board.platform). getprop needs no root, so
 * this works before (or without) a root grant.
 */
package com.thermaloverlay.overlay.metrics

object PlatformUtils {
    @Volatile
    private var cpu: String? = null

    /** "" if it couldn't be read; only a real answer is cached. */
    fun getCPUName(): String {
        cpu?.let { return it }
        val value = try {
            val p = ProcessBuilder("getprop", "ro.board.platform").redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().use { it.readText() }.trim()
            p.waitFor()
            text
        } catch (ex: Exception) {
            ""
        }
        if (value.isNotEmpty()) cpu = value
        return value
    }

    fun isMtk(): Boolean = getCPUName().startsWith("mt")
}
