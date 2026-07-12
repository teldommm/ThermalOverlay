/**
 * Reads the current FPS, trying several sources depending on what the device
 * exposes.
 */
package com.thermaloverlay.overlay.metrics

import com.thermaloverlay.overlay.shell.KeepShell
import com.thermaloverlay.overlay.shell.KeepShellPublic
import com.thermaloverlay.overlay.shell.RootFile

class FpsUtils(private val keepShell: KeepShell = KeepShellPublic.secondaryKeepShell) {
    private var fpsFilePath: String? = null
    private var subStrCommand = "| awk '{print \$2}'"
    private val gfxInfoFpsUtils = GfxInfoFpsUtils(KeepShellPublic.getInstance("gfxinfo-fps", true))

    private val fpsgoStatusPath = "/sys/kernel/fpsgo/fstb/fpsgo_status"

    private var fpsCommand2 = "service call SurfaceFlinger 1013"
    private var lastTime = -1L
    private var lastFrames = -1

    val currentFps: String?
        get() {
            gfxInfoFpsUtils.getFps()?.let {
                return String.format("%.1f", it)
            }

            if (!fpsFilePath.isNullOrEmpty()) {
                return keepShell.doCmdSync("cat $fpsFilePath $subStrCommand")
            } else if (fpsFilePath == null) {
                resolveFpsFilePath()
            }

            readFpsgoStatusFps()?.let {
                return String.format("%.1f", it)
            }

            if (fpsCommand2.isNotEmpty()) {
                val result = keepShell.doCmdSync(fpsCommand2).trim()
                if (result != "error" && !result.contains("Parcel")) {
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
                        return String.format("%.1f", fps)
                    } catch (ex: Exception) {
                        if (!(lastTime > 0 && lastFrames > 0)) {
                            fpsCommand2 = ""
                        }
                    }
                }
            }

            return null
        }

    val fps: Float
        get() {
            val fpsStr = currentFps
            if (fpsStr != null) {
                try {
                    return fpsStr.toFloat()
                } catch (ex: Exception) {
                }
            }
            return 1f
        }

    private fun resolveFpsFilePath() {
        when {
            RootFile.fileExists("/sys/class/drm/sde-crtc-0/measured_fps") -> {
                fpsFilePath = "/sys/class/drm/sde-crtc-0/measured_fps"
            }
            RootFile.fileExists("/sys/class/graphics/fb0/measured_fps") -> {
                fpsFilePath = "/sys/class/graphics/fb0/measured_fps"
                subStrCommand = ""
            }
            else -> {
                fpsFilePath = ""
                val probeShell = KeepShell()
                Thread {
                    try {
                        probeShell.doCmdSync("find /sys -name measured_fps 2>/dev/null")
                            .trim().split("\n").filter { it.contains("crtc") }.minOrNull()?.let {
                                fpsFilePath = it
                            }
                        if (fpsFilePath.isNullOrEmpty()) {
                            probeShell.doCmdSync("find /sys -name fps 2>/dev/null")
                                .trim().split("\n").filter { it.contains("crtc") }.minOrNull()?.let {
                                    fpsFilePath = it
                                }
                        }
                        if (fpsFilePath == null) fpsFilePath = ""
                        probeShell.tryExit()
                    } catch (ex: Exception) {
                        fpsFilePath = ""
                    }
                }.start()
            }
        }
    }

    private fun readFpsgoStatusFps(): Float? {
        if (!RootFile.fileExists(fpsgoStatusPath)) return null
        val pkg = getTopPackageName() ?: return null
        val output = keepShell.doCmdSync("cat $fpsgoStatusPath 2>/dev/null").trim()
        if (output.isEmpty() || output == "error") return null

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

    private fun getTopPackageName(): String? {
        val resumed = keepShell.doCmdSync("dumpsys activity activities | grep -m 1 \"mResumedActivity\"").trim()
        parsePackageFromDump(resumed)?.let { return it }
        val currentFocus = keepShell.doCmdSync("dumpsys window | grep -m 1 \"mCurrentFocus\"").trim()
        return parsePackageFromDump(currentFocus)
    }

    private fun parsePackageFromDump(line: String): String? {
        if (line.isEmpty() || line == "error") return null
        val match = Regex("([a-zA-Z0-9._]+)/(?:[a-zA-Z0-9._]+)").find(line) ?: return null
        return match.groupValues[1]
    }
}
