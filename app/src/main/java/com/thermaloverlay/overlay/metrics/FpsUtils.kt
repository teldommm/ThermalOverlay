/**
 * Reads the current FPS, trying several sources depending on what the device
 * exposes.
 */
package com.thermaloverlay.overlay.metrics

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

    private var fpsCommand2 = "service call SurfaceFlinger 1013"
    private var lastTime = -1L
    private var lastFrames = -1

    @Volatile
    private var probing = false

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

            if (fpsCommand2.isNotEmpty()) {
                val result = keepShell.doCmdSync(fpsCommand2).trim()
                if (result != KeepShell.ERROR && !result.contains("Parcel")) {
                    fpsCommand2 = ""
                } else {
                    try {
                        val index = result.indexOf("(") + 1
                        val frames = Integer.parseInt(result.substring(index, index + 8), 16)
                        val time = System.currentTimeMillis()
                        var fps = 0f
                        if (lastTime > 0 && lastFrames > 0) {
                            fps = (frames - lastFrames) * 1000.0f / (time - lastTime)
                        }
                        lastFrames = frames
                        lastTime = time
                        return fmt(fps)
                    } catch (ex: Exception) {
                        if (!(lastTime > 0 && lastFrames > 0)) {
                            fpsCommand2 = ""
                        }
                    }
                }
            }

            return null
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
        if (probing) return
        probing = true
        Thread({
            val probeShell = KeepShell()
            try {
                var found = firstCrtcMatch(probeShell.doCmdSync("find /sys -name measured_fps 2>/dev/null", KeepShell.LONG_TIMEOUT_MS))
                if (found == null) {
                    found = firstCrtcMatch(probeShell.doCmdSync("find /sys -name fps 2>/dev/null", KeepShell.LONG_TIMEOUT_MS))
                }
                fpsFilePath = found ?: ""
            } catch (ex: Exception) {
                fpsFilePath = ""
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
