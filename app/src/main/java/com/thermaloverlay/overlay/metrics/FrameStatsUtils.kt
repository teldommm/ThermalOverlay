/**
 * Real per-frame timing via `dumpsys gfxinfo <pkg> framestats` — the
 * standard on-device source for this (available since API 23), parsed by
 * header name rather than fixed column indices so it survives the minor
 * column-set differences across Android versions.
 *
 * Investigated first: the original app's jank/frame-time numbers (the
 * "jank", "big_jank", "frame_time", "frame_sequence" JSON fields read in
 * em0.c()) come from a bundled root binary invoked as "adb-monitor-fps" —
 * a proprietary compiled tool, not a technique implemented in Kotlin/Java
 * anywhere in the app. We don't have that binary and can't extract its
 * exact jank thresholds or aggregation rules, so there's no faithful port
 * available here — framestats is the closest available on-device
 * equivalent, and what's below is our own read of it, not a
 * reverse-engineered match to the original's numbers.
 *
 * Jank thresholds are our own choice (not extracted from the source,
 * which we don't have access to): a frame is "jank" past one refresh
 * interval, "big jank" past three. Refresh interval is read from the
 * display when available, falling back to 60Hz.
 */
package com.thermaloverlay.overlay.metrics

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import com.thermaloverlay.overlay.shell.KeepShellPublic

class FrameStatsUtils {
    // Only frames newer than this (per package) count toward the next
    // tick's jank stats — framestats returns a rolling ~120-frame buffer
    // each call, so without this the same frames would be recounted every
    // poll.
    private val lastFrameNs = HashMap<String, Long>()

    private fun refreshIntervalNs(context: Context): Long {
        val refreshRate = try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            dm?.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate
        } catch (ex: Exception) {
            null
        }
        val hz = if (refreshRate != null && refreshRate > 1f) refreshRate else 60f
        return (1_000_000_000.0 / hz).toLong()
    }

    data class Tick(val jankCount: Int, val bigJankCount: Int, val maxFrameTimeMs: Double)

    // Durations (ms) of frames completed since the last call for this
    // package, plus the jank/big-jank classification for this batch. Null
    // if framestats couldn't be read (app not currently drawing, no root,
    // unsupported format).
    fun poll(context: Context, packageName: String): Tick? {
        if (packageName.isEmpty()) return null
        val output = KeepShellPublic.doCmdSync("dumpsys gfxinfo $packageName framestats").trim()
        if (output.isEmpty() || output == "error") return null

        val lines = output.lines()
        val blockStarts = lines.indices.filter { lines[it].trim() == "---PROFILEDATA---" }
        // The data sits between the first and second marker; a lone
        // trailing marker with nothing after it means no frames yet.
        if (blockStarts.size < 2) return null
        val header = lines.getOrNull(blockStarts[0] + 1)?.split(",") ?: return null
        val flagsCol = header.indexOf("Flags")
        val vsyncCol = header.indexOf("IntendedVsync")
        val completedCol = header.indexOf("FrameCompleted")
        if (flagsCol < 0 || vsyncCol < 0 || completedCol < 0) return null

        val intervalNs = refreshIntervalNs(context)
        val lastSeen = lastFrameNs[packageName]
        // First poll ever for this package (no baseline yet): the
        // framestats buffer holds up to ~120 frames that may span far
        // more than the last second, well before recording started.
        // Counting all of them as "this tick" would inflate the first
        // data point — just establish the baseline instead and start
        // counting from the next poll.
        if (lastSeen == null) {
            val newestVsync = (blockStarts[0] + 2 until blockStarts[1])
                .mapNotNull { lines[it].split(",").getOrNull(vsyncCol)?.trim()?.toLongOrNull() }
                .maxOrNull()
            lastFrameNs[packageName] = newestVsync ?: 0L
            return null
        }
        var newestSeen = lastSeen
        val durationsMs = ArrayList<Double>()

        for (lineIndex in (blockStarts[0] + 2) until blockStarts[1]) {
            val cols = lines[lineIndex].split(",")
            if (cols.size <= completedCol) continue
            val flags = cols[flagsCol].trim().toLongOrNull() ?: continue
            if (flags != 0L) continue // skipped/invalid frame, excluded like the standard gfxinfo tooling does
            val vsync = cols[vsyncCol].trim().toLongOrNull() ?: continue
            val completed = cols[completedCol].trim().toLongOrNull() ?: continue
            if (vsync <= lastSeen) continue // already counted on a previous poll
            if (completed <= vsync) continue // not a real/finished frame

            durationsMs.add((completed - vsync) / 1_000_000.0)
            if (vsync > newestSeen) newestSeen = vsync
        }
        lastFrameNs[packageName] = newestSeen
        if (durationsMs.isEmpty()) return null

        val intervalMs = intervalNs / 1_000_000.0
        val jankCount = durationsMs.count { it > intervalMs }
        val bigJankCount = durationsMs.count { it > intervalMs * 3 }
        return Tick(jankCount, bigJankCount, durationsMs.max())
    }

    // Call when a recording session ends, so a later session doesn't
    // treat old frames as "already seen" for the wrong reason (a
    // different session recording the same app after a gap should still
    // see its first tick's frames as new).
    fun reset(packageName: String) {
        lastFrameNs.remove(packageName)
    }
}
