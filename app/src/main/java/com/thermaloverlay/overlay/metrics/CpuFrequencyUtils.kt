/**
 * Reads current CPU cluster frequencies and DDR frequency.
 */
package com.thermaloverlay.overlay.metrics

import com.thermaloverlay.overlay.shell.KeepShellPublic
import com.thermaloverlay.overlay.shell.KernelProrp
import com.thermaloverlay.overlay.shell.NativeKernelProp
import com.thermaloverlay.overlay.shell.RootFile
import java.io.File

class CpuFrequencyUtils {
    private val cpuDir = "/sys/devices/system/cpu/cpu0/"
    private val cpufreqSysDir = "/sys/devices/system/cpu/cpu0/cpufreq/"
    private val scalingCurFreq = cpufreqSysDir + "scaling_cur_freq"

    private var coreCount = -1
    private var cpuClusterInfo: ArrayList<Array<String>>? = null
    private val nativeProp = NativeKernelProp()

    fun getCoreCount(): Int {
        if (coreCount > -1) return coreCount
        var cores = 0
        while (File(cpuDir.replace("cpu0", "cpu$cores")).exists()) {
            cores++
        }
        coreCount = cores
        return coreCount
    }

    @Synchronized
    fun getClusterInfo(): ArrayList<Array<String>> {
        cpuClusterInfo?.let { return it }

        val result = ArrayList<Array<String>>()
        val clusters = ArrayList<String>()
        var cores = 0
        while (true) {
            val relatedCpusPath = "/sys/devices/system/cpu/cpu0/cpufreq/related_cpus".replace("cpu0", "cpu$cores")
            val file = File(relatedCpusPath)
            if (file.exists()) {
                val relatedCpus = KernelProrp.getProp(relatedCpusPath).trim()
                if (relatedCpus.isNotEmpty() && !clusters.contains(relatedCpus)) {
                    clusters.add(relatedCpus)
                }
            } else {
                break
            }
            cores++
        }
        for (cluster in clusters) {
            result.add(cluster.split(Regex("[ ]+")).toTypedArray())
        }
        cpuClusterInfo = result
        return result
    }

    fun getCurrentFrequency(cluster: Int): String {
        val clusters = getClusterInfo()
        if (cluster >= clusters.size) return ""
        val cpu = "cpu" + clusters[cluster][0]
        return getCpuFreqValue(scalingCurFreq.replace("cpu0", cpu))
    }

    fun getCoreCurrentFrequency(core: String): String {
        val path = "/sys/devices/system/cpu/cpu$core/cpufreq/scaling_cur_freq"
        return getCpuFreqValue(path)
    }

    private var debugfsMount: String? = null
    private var ddrPath: String? = null
    private var ddrPathIsMccc = false

    private fun getDebugfsMount(): String {
        debugfsMount?.let { return it }
        val cmd = "mount 2>/dev/null | grep ' type debugfs ' | awk '{print \$3; exit}'"
        var mount = KeepShellPublic.doCmdSync(cmd).trim()
        if (mount.isEmpty() || !RootFile.dirExists(mount)) {
            mount = "/sys/kernel/debug"
        }
        debugfsMount = mount
        return mount
    }

    fun getDdrFrequency(): String {
        if (ddrPath == null) {
            val mccc = getDebugfsMount() + "/clk/measure_only_mccc_clk/clk_measure"
            when {
                RootFile.fileExists(mccc) -> {
                    ddrPath = mccc
                    ddrPathIsMccc = true
                }
                RootFile.fileExists("/sys/devices/system/cpu/bus_dcvs/DDR/cur_freq") -> {
                    ddrPath = "/sys/devices/system/cpu/bus_dcvs/DDR/cur_freq"
                    ddrPathIsMccc = false
                }
                else -> ddrPath = ""
            }
        }
        if (ddrPath.isNullOrEmpty()) return ""

        val raw = KernelProrp.getProp(ddrPath!!).trim().toLongOrNull() ?: return ""
        return if (ddrPathIsMccc) (raw / 500_000L).toString() else (raw / 500L).toString()
    }

    private fun getCpuFreqValue(path: String): String {
        val freqValue = nativeProp.getKernelPropLong(path)
        return if (freqValue > -1) freqValue.toString() else ""
    }
}
