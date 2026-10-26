/**
 * Resolves the package currently in the foreground. The lookup is a heavy
 * dumpsys call, so one shared, briefly cached answer serves every FPS source.
 */
package com.thermaloverlay.overlay.metrics

import android.os.SystemClock
import com.thermaloverlay.overlay.shell.KeepShell

object ForegroundApp {
    private const val CACHE_MS = 700L

    private var cachedPackage: String? = null
    private var cachedAt = -CACHE_MS

    @Synchronized
    fun get(shell: KeepShell): String? {
        val now = SystemClock.elapsedRealtime()
        if (now - cachedAt < CACHE_MS) return cachedPackage

        var pkg = parse(shell.doCmdSync("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'"))
        if (pkg == null) {
            pkg = parse(shell.doCmdSync("dumpsys activity activities | grep -m 1 \"mResumedActivity\""))
        }
        cachedPackage = pkg
        cachedAt = SystemClock.elapsedRealtime()
        return pkg
    }

    /** Extracts "com.example.app" from a window/activity dump line. */
    internal fun parse(text: String): String? {
        val line = text.trim()
        if (line.isEmpty() || line == KeepShell.ERROR) return null
        val match = Regex("([a-zA-Z0-9._-]+)/").find(line) ?: return null
        return match.groupValues[1]
    }
}
