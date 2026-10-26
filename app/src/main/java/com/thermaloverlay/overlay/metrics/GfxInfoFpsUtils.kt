/**
 * Computes FPS for the foreground app from framestats collected over the last
 * second.
 */
package com.thermaloverlay.overlay.metrics

import android.os.SystemClock
import com.thermaloverlay.overlay.shell.KeepShell

class GfxInfoFpsUtils(private val keepShell: KeepShell) {
    private var lastActivePackage: String? = null
    private val frameTimeBuffer = ArrayList<Long>()
    private var lastProcessedFrameTime = 0L
    private var gfxColumnIndex = -1
    private var lastFrameSeenAtMs = 0L

    @Synchronized
    fun getFps(): Float? {
        val packageName = ForegroundApp.get(keepShell) ?: return null
        if (packageName != lastActivePackage) {
            lastActivePackage = packageName
            frameTimeBuffer.clear()
            lastProcessedFrameTime = 0L
            gfxColumnIndex = -1
            keepShell.doCmdSync("dumpsys gfxinfo $packageName reset")
            return null
        }

        val output = keepShell.doCmdSync("dumpsys gfxinfo $packageName framestats")
        if (output.isEmpty() || output == KeepShell.ERROR) return null

        val lines = output.split("\n")
        if (gfxColumnIndex == -1) {
            val header = lines.firstOrNull { it.contains("FrameCompleted") } ?: return null
            gfxColumnIndex = header.split(",").indexOf("FrameCompleted")
            if (gfxColumnIndex == -1) return null
        }

        var maxTimestamp = lastProcessedFrameTime
        var addedFrames = 0
        for (line in lines) {
            val parts = line.split(",")
            if (parts.size <= gfxColumnIndex) continue
            val timestamp = parts[gfxColumnIndex].trim().toLongOrNull() ?: continue
            if (timestamp <= lastProcessedFrameTime) continue
            frameTimeBuffer.add(timestamp)
            addedFrames += 1
            if (timestamp > maxTimestamp) maxTimestamp = timestamp
        }
        lastProcessedFrameTime = maxTimestamp
        if (addedFrames > 0) {
            lastFrameSeenAtMs = SystemClock.elapsedRealtime()
        } else if (lastFrameSeenAtMs > 0 && SystemClock.elapsedRealtime() - lastFrameSeenAtMs > 2000) {
            frameTimeBuffer.clear()
            lastProcessedFrameTime = 0L
            gfxColumnIndex = -1
            keepShell.doCmdSync("dumpsys gfxinfo $packageName reset")
            return null
        }

        val nowNs = System.nanoTime()
        frameTimeBuffer.removeAll { nowNs - it > 1_000_000_000L }

        if (frameTimeBuffer.isEmpty()) return null
        return frameTimeBuffer.size.toFloat()
    }
}
