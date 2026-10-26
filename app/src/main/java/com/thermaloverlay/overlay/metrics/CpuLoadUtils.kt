/**
 * Computes overall and per-core CPU load from /proc/stat deltas, plus CPU
 * temperature.
 */
package com.thermaloverlay.overlay.metrics

import com.thermaloverlay.overlay.shell.KeepShell
import com.thermaloverlay.overlay.shell.KeepShellPublic
import com.thermaloverlay.overlay.shell.SysReader
import java.util.Locale

/**
 * One CPU load measurement.
 *
 * @param perCore load in percent per ONLINE core. An offline core is absent
 *                from /proc/stat, hence absent here (the HUD shows it as off).
 * @param total   load of the whole CPU in percent.
 */
class CpuLoad(val perCore: Map<Int, Double>, val total: Double)

class CpuLoadUtils {
    // Index -1 is the aggregate "cpu" line. Value: [total, idle] ticks.
    private var previous: Map<Int, LongArray>? = null

    /**
     * Takes a sample and returns the load since the previous call, or null if
     * /proc/stat could not be read. The first call has no baseline yet and
     * reports 0% for every present core.
     */
    @Synchronized
    fun sample(): CpuLoad? {
        val text = SysReader.readOrNull("/proc/stat") ?: return null
        val current = parseProcStat(text)
        if (current.isEmpty()) return null

        val prev = previous
        previous = current

        val perCore = HashMap<Int, Double>()
        var total = 0.0
        for ((index, cur) in current) {
            val p = prev?.get(index)
            val load = if (p == null) 0.0 else loadBetween(p, cur)
            if (index == -1) total = load else perCore[index] = load
        }
        return CpuLoad(perCore, total)
    }

    // Resolved once: scanning thermal_zone*/type opens 100+ files on modern
    // phones. After the first pass only the matching temp nodes are read.
    // Empty results are not cached so a pre-root first tick retries later.
    private var cpuTempPaths: List<String>? = null

    fun getCpuTemperatureText(): String {
        var paths = cpuTempPaths
        if (paths == null) {
            val out = KeepShellPublic.doCmdSync(
                "grep -l 'cpu-0' /sys/class/thermal/thermal_zone*/type 2>/dev/null"
            ).trim()
            if (out.isNotEmpty() && out != KeepShell.ERROR) {
                paths = out.split("\n")
                    .map { it.trim() }
                    .filter { it.endsWith("/type") }
                    .map { it.removeSuffix("/type") + "/temp" }
                if (paths.isNotEmpty()) cpuTempPaths = paths
            }
        }
        if (paths.isNullOrEmpty()) return "--"

        val maxMilli = SysReader.readMany(paths).mapNotNull { it?.trim()?.toDoubleOrNull() }.maxOrNull()
            ?: return "--"
        // Values are millidegrees; anything <= 1000 is treated as invalid.
        if (maxMilli <= 1000) return "--"
        return String.format(Locale.US, "%.1f°C", maxMilli / 1000)
    }

    companion object {
        /**
         * Parses the "cpu"/"cpuN" lines of /proc/stat into index -> [total, idle].
         * Only the first 8 counters are used (user..steal): guest and
         * guest_nice are already included in user/nice and must not be added
         * twice.
         */
        internal fun parseProcStat(text: String): Map<Int, LongArray> {
            val result = HashMap<Int, LongArray>()
            for (line in text.lineSequence()) {
                if (!line.startsWith("cpu")) continue
                val cols = line.trim().split(Regex("\\s+"))
                val index = if (cols[0] == "cpu") -1 else cols[0].substring(3).toIntOrNull() ?: continue
                val values = cols.drop(1).take(8).map { it.toLongOrNull() ?: 0L }
                if (values.size < 4) continue
                val total = values.sum()
                val idle = values[3] + values.getOrElse(4) { 0L }
                result[index] = longArrayOf(total, idle)
            }
            return result
        }

        internal fun loadBetween(prev: LongArray, cur: LongArray): Double {
            val total = cur[0] - prev[0]
            if (total <= 0L) return 0.0
            val idle = (cur[1] - prev[1]).coerceIn(0L, total)
            return 100.0 * (total - idle) / total
        }
    }
}
