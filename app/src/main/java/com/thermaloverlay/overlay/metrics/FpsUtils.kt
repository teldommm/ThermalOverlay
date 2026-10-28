/**
 * Reads the current FPS, trying several sources depending on what the device
 * exposes.
 */
package com.thermaloverlay.overlay.metrics

import android.os.SystemClock
import com.thermaloverlay.overlay.shell.KeepShell
import com.thermaloverlay.overlay.shell.KeepShellPublic
import com.thermaloverlay.overlay.shell.RootFile
import java.util.Locale

class FpsUtils(private val keepShell: KeepShell = KeepShellPublic.secondaryKeepShell) {
    // null = not resolved yet, "" = resolved to "no such node".
    @Volatile
    private var fpsFilePath: String? = null
    private var subStrCommand = "| awk '{print \$2}'"
    private val gfxInfoFpsUtils = GfxInfoFpsUtils(KeepShellPublic.getInstance("gfxinfo-fps", true))

    private val fpsgoStatusPath = "/sys/kernel/fpsgo/fstb/fpsgo_status"
    private var hasFpsgo: Boolean? = null

    @Volatile
    private var probing = false

    // Earliest time a probe that failed (shell error, not "no node") may rerun.
    @Volatile
    private var probeRetryAt = 0L

    val currentFps: String?
        get() {
            gfxInfoFpsUtils.getFps()?.let {
                return fmt(it)
            }

            val path = fpsFilePath
            if (!path.isNullOrEmpty()) {
                val v = keepShell.doCmdSync("cat $path $subStrCommand").trim()
                // "error"/blank must not reach the HUD as a value.
                return if (v.toFloatOrNull() != null) v else null
            } else if (path == null) {
                resolveFpsFilePath()
            }

            readFpsgoStatusFps()?.let {
                return fmt(it)
            }

            return null
        }

    private companion object {
        const val PROBE_RETRY_MS = 30_000L
    }

    private fun fmt(v: Float): String = String.format(Locale.US, "%.1f", v)

    private fun resolveFpsFilePath() {
        val sde = RootFile.fileExistsOrNull("/sys/class/drm/sde-crtc-0/measured_fps") ?: return
        if (sde) {
            fpsFilePath = "/sys/class/drm/sde-crtc-0/measured_fps"
            return
        }
        val fb0 = RootFile.fileExistsOrNull("/sys/class/graphics/fb0/measured_fps") ?: return
        if (fb0) {
            fpsFilePath = "/sys/class/graphics/fb0/measured_fps"
            subStrCommand = ""
            return
        }
        // Slow whole-/sys search: run it once in the background on its own
        // shell (with a generous timeout) so it never stalls the HUD.
        if (probing || SystemClock.elapsedRealtime() < probeRetryAt) return
        probing = true
        Thread({
            val probeShell = KeepShell()
            try {
                val first = probeShell.doCmdSync("find /sys -name measured_fps 2>/dev/null", KeepShell.LONG_TIMEOUT_MS)
                var failed = first == KeepShell.ERROR
                var found = if (failed) null else firstCrtcMatch(first)
                if (found == null) {
                    val second = probeShell.doCmdSync("find /sys -name fps 2>/dev/null", KeepShell.LONG_TIMEOUT_MS)
                    if (second == KeepShell.ERROR) failed = true else found = firstCrtcMatch(second)
                }
                when {
                    found != null -> fpsFilePath = found
                    // The shell failed: that is not "no such node". Leave the
                    // path unresolved and try again after a pause.
                    failed -> probeRetryAt = SystemClock.elapsedRealtime() + PROBE_RETRY_MS
                    else -> fpsFilePath = ""
                }
            } catch (ex: Exception) {
                probeRetryAt = SystemClock.elapsedRealtime() + PROBE_RETRY_MS
            } finally {
                probeShell.tryExit()
                probing = false
            }
        }, "FpsProbe").start()
    }

    private fun firstCrtcMatch(output: String): String? {
        if (output == KeepShell.ERROR) return null
        return output.trim().split("\n").filter { it.contains("crtc") }.minOrNull()
    }

    private fun readFpsgoStatusFps(): Float? {
        if (hasFpsgo == null) hasFpsgo = RootFile.fileExistsOrNull(fpsgoStatusPath)
        if (hasFpsgo != true) return null
        val pkg = ForegroundApp.get(keepShell) ?: return null
        val output = keepShell.doCmdSync("cat $fpsgoStatusPath 2>/dev/null").trim()
        if (output.isEmpty() || output == KeepShell.ERROR) return null

        val rows = output.split("\n")
        var nameIndex = -1
        var fpsIndex = -1
        var bestFps = -1f
        for (row in rows) {
            val line = row.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("tid")) {
                val headerCols = line.split(Regex("\\s+"))
                nameIndex = headerCols.indexOf("name")
                fpsIndex = headerCols.indexOf("currentFPS")
                continue
            }
            if (nameIndex < 0 || fpsIndex < 0) continue
            val cols = line.split(Regex("\\s+"))
            if (cols.size <= fpsIndex || cols.size <= nameIndex) continue
            if (!nameMatchesPackage(cols[nameIndex], pkg)) continue
            val fps = cols[fpsIndex].toFloatOrNull() ?: continue
            if (fps > bestFps) bestFps = fps
        }
        if (bestFps == 0f) return 1f
        return if (bestFps >= 0f) bestFps else null
    }

    private fun nameMatchesPackage(name: String, packageName: String): Boolean {
        if (packageName == name || packageName.endsWith(name)) return true
        val tail = packageName.substringAfterLast(".")
        return name.endsWith(tail)
    }
}
