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

    fun getCpuTemperatureText(): String {
        val cmd = "awk '" +
            "FNR==1 { " +
            "z=FILENAME; sub(/\\/type$/,\"\",z); " +
            "if (\$0 ~ /cpu-0/) { " +
            "tf=z\"/temp\"; if ((getline t < tf) > 0) { if (t+0>m) m=t+0 } close(tf); " +
            "} " +
            "} " +
            "END { if (m>1000) printf \"%.1f\", m/1000; else print 0 }" +
            "' /sys/class/thermal/thermal_zone*/type 2>/dev/null"

        val result = KeepShellPublic.doCmdSync(cmd).trim()
        val value = result.toDoubleOrNull()
        return if (value != null && value > 0) String.format("%.1f°C", value) else "--"
    }
}
