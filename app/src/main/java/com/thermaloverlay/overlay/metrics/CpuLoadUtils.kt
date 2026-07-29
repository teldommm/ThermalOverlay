/**
 * Computes overall and per-core CPU load from /proc/stat deltas, plus CPU
 * temperature.
 */
package com.thermaloverlay.overlay.metrics

import com.thermaloverlay.overlay.shell.KeepShellPublic
import com.thermaloverlay.overlay.shell.KernelProrp

class CpuLoadUtils {

    private var lastCpuState: String = ""
    private var lastCpuStateSum: String = ""
    private var lastCpuStateMap: HashMap<Int, Double>? = null
    private var lastCpuStateTime: Long = 0L

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
            val times = KernelProrp.getProp("/proc/stat", "^cpu")
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

            val times = KernelProrp.getProp("/proc/stat", "^cpu ")
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
    //
    // Match is a case-insensitive "cpu" substring, not the literal "cpu-0"
    // this used to be: a live device screenshot showed cpu_temp pinned at
    // exactly 0.0 for an entire session (MAX/MIN/AVG all "0,0°C") while
    // every other per-tick metric on the same session recorded fine —
    // meaning the old pattern matched zero thermal zones on that device.
    // Real zone-naming conventions vary a lot even within one vendor
    // ("cpu-0-0-usr" vs "cpu0-0-bottom-thermal" vs "cpu0-thermal" vs
    // "cpu-thermal0"), and the literal "cpu-0" (hyphen right after "cpu")
    // only matches the first of those. Broadening to "cpu" catches all of
    // them at the cost of also matching CPU-adjacent-but-not-strictly-core
    // zones (e.g. a CPU BCL/throttle zone) — an over-broad match that still
    // reports *something* is a better failure mode than the previous
    // silent, permanent "--".
    private var cpuTempPaths: List<String>? = null

    // Raw millidegrees, or null if unavailable/invalid — no locale-sensitive
    // formatting anywhere near this path. Shared by getCpuTemperatureText()
    // (display) and getCpuTemperatureCelsius() (everyone else).
    private fun cpuTemperatureMilliDegrees(): Double? {
        var paths = cpuTempPaths
        if (paths == null) {
            val out = KeepShellPublic.doCmdSync(
                "grep -il 'cpu' /sys/class/thermal/thermal_zone*/type 2>/dev/null"
            ).trim()
            if (out.isNotEmpty() && out != "error") {
                paths = out.split("\n")
                    .map { it.trim() }
                    .filter { it.endsWith("/type") }
                    .map { it.removeSuffix("/type") + "/temp" }
                if (paths.isNotEmpty()) cpuTempPaths = paths
            }
        }
        if (paths.isNullOrEmpty()) return null

        val raw = KeepShellPublic.doCmdSync("cat ${paths.joinToString(" ")} 2>/dev/null").trim()
        if (raw.isEmpty() || raw == "error") return null
        val maxMilli = raw.split("\n").mapNotNull { it.trim().toDoubleOrNull() }.maxOrNull() ?: return null
        // Same semantics as the old awk: values are millidegrees; anything
        // <= 1000 is treated as invalid.
        if (maxMilli <= 1000) return null
        return maxMilli
    }

    // Real bug found via a live device: this used to be the ONLY way to get
    // the temperature, and every caller that needed a number (FloatFpsWatch)
    // parsed it back with removeSuffix("°C").toDoubleOrNull(). But
    // String.format("%.1f°C", x) here uses the device's default locale — on
    // any comma-decimal locale (the device in question renders numbers as
    // "0,0°C") it produces a comma, and Kotlin's toDoubleOrNull() always
    // requires a period regardless of locale, so the parse silently failed
    // every single time. The floating overlay never hit this because it
    // only displays the string — it never parses it back into a number.
    // Net effect: cpu_temp was never recorded on any comma-decimal-locale
    // device, even once the "cpu" zone-matching fix (above) found the
    // zone correctly. Fixed by giving numeric callers their own function
    // that never touches a locale-formatted string.
    fun getCpuTemperatureCelsius(): Double? = cpuTemperatureMilliDegrees()?.let { it / 1000 }

    fun getCpuTemperatureText(): String {
        val milli = cpuTemperatureMilliDegrees() ?: return "--"
        return String.format("%.1f°C", milli / 1000)
    }
}
