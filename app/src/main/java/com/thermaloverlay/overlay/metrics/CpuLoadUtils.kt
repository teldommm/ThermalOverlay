/**
 * Computes overall and per-core CPU load from /proc/stat deltas, plus CPU
 * temperature.
 */
package com.thermaloverlay.overlay.metrics

import com.thermaloverlay.overlay.shell.KeepShellPublic
import java.io.File

class CpuLoadUtils {

    private var lastCpuState: String = ""
    private var lastCpuStateSum: String = ""
    private var lastCpuStateMap: HashMap<Int, Double>? = null
    private var lastCpuStateTime: Long = 0L

    // /proc/stat is world-readable — no need to spawn a root-shell subprocess
    // for it on every tick.
    private fun readProcStatCpuLines(aggregateOnly: Boolean): String {
        return try {
            File("/proc/stat").readLines()
                .filter { if (aggregateOnly) it.startsWith("cpu ") else it.startsWith("cpu") }
                .joinToString("\n")
        } catch (ex: Exception) {
            ""
        }
    }

    private fun getCpuIndex(cols: List<String>): Int {
        return if (cols[0] == "cpu") -1 else cols[0].substring(3).toInt()
    }

    private fun cpuTotalTime(cols: List<String>): Long {
        var total = 0L
        for (i in 1 until cols.size) {
            total += cols[i].toLong()
        }
        return total
    }

    private fun cpuIdleTime(cols: List<String>): Long =
        cols[4].toLong() + cols.getOrElse(5) { "0" }.toLong()

    val cpuLoad: HashMap<Int, Double>
        get() {
            lastCpuStateMap?.let {
                if (System.currentTimeMillis() - lastCpuStateTime < 500) return it
            }

            val loads = HashMap<Int, Double>()
            val times = readProcStatCpuLines(aggregateOnly = false)
            if (times != "error" && times.startsWith("cpu")) {
                try {
                    if (lastCpuState.isEmpty()) {
                        lastCpuState = times
                        Thread.sleep(100)
                        return cpuLoad
                    }
                    val curTick = times.split("\n")
                    val prevTick = lastCpuState.split("\n")

                    for (cpuCurrentTime in curTick) {
                        val cols1 = cpuCurrentTime.replace(Regex(" {2}"), " ").split(" ")
                        var cols0: List<String>? = null
                        for (cpu in prevTick) {
                            if (cpu.startsWith(cols1[0] + " ")) {
                                cols0 = cpu.replace(Regex(" {2}"), " ").split(" ")
                                break
                            }
                        }
                        if (cols0 != null && cols0.isNotEmpty()) {
                            val total1 = cpuTotalTime(cols1)
                            val idle1 = cpuIdleTime(cols1)
                            val total0 = cpuTotalTime(cols0)
                            val idle0 = cpuIdleTime(cols0)
                            val timePoor = total1 - total0
                            if (timePoor == 0L) {
                                loads[getCpuIndex(cols1)] = 0.0
                            } else {
                                val idlePoor = idle1 - idle0
                                loads[getCpuIndex(cols1)] = if (idlePoor < 1) {
                                    100.0
                                } else {
                                    100 - (idlePoor * 100.0 / timePoor)
                                }
                            }
                        } else {
                            loads[getCpuIndex(cols1)] = 0.0
                        }
                    }
                    lastCpuState = times
                    lastCpuStateTime = System.currentTimeMillis()
                    lastCpuStateMap = loads
                    return loads
                } catch (ex: Exception) {
                    return loads
                }
            }
            return loads
        }

    val cpuLoadSum: Double
        get() {
            lastCpuStateMap?.let {
                if (System.currentTimeMillis() - lastCpuStateTime < 500 && it.containsKey(-1)) {
                    return it[-1]!!
                }
            }

            val times = readProcStatCpuLines(aggregateOnly = true)
            if (times != "error" && times.startsWith("cpu")) {
                try {
                    if (lastCpuStateSum.isEmpty()) {
                        lastCpuStateSum = times
                        Thread.sleep(100)
                        return cpuLoadSum
                    }
                    val curTick = times.split("\n")
                    val prevTick = lastCpuStateSum.split("\n")

                    for (cpuCurrentTime in curTick) {
                        val cols1 = cpuCurrentTime.replace(Regex(" {2}"), " ").split(" ")
                        if (cols1[0].trim() == "cpu") {
                            for (cpu in prevTick) {
                                if (cpu.startsWith("cpu ")) {
                                    lastCpuStateSum = times
                                    val cols0 = cpu.replace(Regex(" {2}"), " ").split(" ")
                                    val total1 = cpuTotalTime(cols1)
                                    val idle1 = cpuIdleTime(cols1)
                                    val total0 = cpuTotalTime(cols0)
                                    val idle0 = cpuIdleTime(cols0)
                                    val timePoor = total1 - total0
                                    return if (timePoor == 0L) {
                                        0.0
                                    } else {
                                        val idlePoor = idle1 - idle0
                                        if (idlePoor < 1) 100.0 else (100 - (idlePoor * 100.0 / timePoor))
                                    }
                                }
                            }
                            return 0.0
                        }
                    }
                } catch (ignored: Exception) {
                }
            }
            return -1.0
        }

    // Resolved once: the awk scan opens every thermal_zone*/type (often 100+
    // files on modern phones). After the first pass we only cat the matching
    // temp nodes. Empty results are not cached so a pre-root first tick
    // retries later.
    private var cpuTempPaths: List<String>? = null

    fun getCpuTemperatureText(): String {
        var paths = cpuTempPaths
        if (paths == null) {
            val out = KeepShellPublic.doCmdSync(
                "grep -l 'cpu-0' /sys/class/thermal/thermal_zone*/type 2>/dev/null"
            ).trim()
            if (out.isNotEmpty() && out != "error") {
                paths = out.split("\n")
                    .map { it.trim() }
                    .filter { it.endsWith("/type") }
                    .map { it.removeSuffix("/type") + "/temp" }
                if (paths.isNotEmpty()) cpuTempPaths = paths
            }
        }
        if (paths.isNullOrEmpty()) return "--"

        val raw = KeepShellPublic.doCmdSync("cat ${paths.joinToString(" ")} 2>/dev/null").trim()
        if (raw.isEmpty() || raw == "error") return "--"
        val maxMilli = raw.split("\n").mapNotNull { it.trim().toDoubleOrNull() }.maxOrNull() ?: return "--"
        // Same semantics as the old awk: values are millidegrees; anything
        // <= 1000 is treated as invalid.
        if (maxMilli <= 1000) return "--"
        return String.format("%.1f°C", maxMilli / 1000)
    }
}
