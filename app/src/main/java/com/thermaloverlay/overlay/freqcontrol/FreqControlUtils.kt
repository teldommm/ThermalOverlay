/**
 * sysfs paths and read/write helpers for CPU cluster and GPU (Adreno/kgsl)
 * frequency control, via this project's own root shell.
 */
package com.thermaloverlay.overlay.freqcontrol

import com.thermaloverlay.overlay.shell.KernelProrp
import com.thermaloverlay.overlay.shell.RootFile
import java.io.File

object FreqControlUtils {

    const val MIN_FREQ_CPU0 = "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq"
    const val MAX_FREQ_CPU0 = "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"
    const val CUR_FREQ_CPU0 = "/sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq"
    const val AVAIL_FREQ_CPU0 = "/sys/devices/system/cpu/cpufreq/policy0/scaling_available_frequencies"
    const val GOV_CPU0 = "/sys/devices/system/cpu/cpufreq/policy0/scaling_governor"
    const val AVAIL_GOV_CPU0 = "/sys/devices/system/cpu/cpufreq/policy0/scaling_available_governors"

    const val MIN_FREQ_CPU3 = "/sys/devices/system/cpu/cpufreq/policy3/scaling_min_freq"
    const val MAX_FREQ_CPU3 = "/sys/devices/system/cpu/cpufreq/policy3/scaling_max_freq"
    const val CUR_FREQ_CPU3 = "/sys/devices/system/cpu/cpufreq/policy3/scaling_cur_freq"
    const val AVAIL_FREQ_CPU3 = "/sys/devices/system/cpu/cpufreq/policy3/scaling_available_frequencies"
    const val AVAIL_BOOST_CPU3 = "/sys/devices/system/cpu/cpufreq/policy3/scaling_boost_frequencies"
    const val GOV_CPU3 = "/sys/devices/system/cpu/cpufreq/policy3/scaling_governor"
    const val AVAIL_GOV_CPU3 = "/sys/devices/system/cpu/cpufreq/policy3/scaling_available_governors"

    const val MIN_FREQ_CPU4 = "/sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq"
    const val MAX_FREQ_CPU4 = "/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq"
    const val CUR_FREQ_CPU4 = "/sys/devices/system/cpu/cpufreq/policy4/scaling_cur_freq"
    const val AVAIL_FREQ_CPU4 = "/sys/devices/system/cpu/cpufreq/policy4/scaling_available_frequencies"
    const val AVAIL_BOOST_CPU4 = "/sys/devices/system/cpu/cpufreq/policy4/scaling_boost_frequencies"
    const val GOV_CPU4 = "/sys/devices/system/cpu/cpufreq/policy4/scaling_governor"
    const val AVAIL_GOV_CPU4 = "/sys/devices/system/cpu/cpufreq/policy4/scaling_available_governors"

    const val MIN_FREQ_CPU6 = "/sys/devices/system/cpu/cpufreq/policy6/scaling_min_freq"
    const val MAX_FREQ_CPU6 = "/sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq"
    const val CUR_FREQ_CPU6 = "/sys/devices/system/cpu/cpufreq/policy6/scaling_cur_freq"
    const val AVAIL_FREQ_CPU6 = "/sys/devices/system/cpu/cpufreq/policy6/scaling_available_frequencies"
    const val AVAIL_BOOST_CPU6 = "/sys/devices/system/cpu/cpufreq/policy6/scaling_boost_frequencies"
    const val GOV_CPU6 = "/sys/devices/system/cpu/cpufreq/policy6/scaling_governor"
    const val AVAIL_GOV_CPU6 = "/sys/devices/system/cpu/cpufreq/policy6/scaling_available_governors"

    const val MIN_FREQ_CPU7 = "/sys/devices/system/cpu/cpufreq/policy7/scaling_min_freq"
    const val MAX_FREQ_CPU7 = "/sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq"
    const val CUR_FREQ_CPU7 = "/sys/devices/system/cpu/cpufreq/policy7/scaling_cur_freq"
    const val AVAIL_FREQ_CPU7 = "/sys/devices/system/cpu/cpufreq/policy7/scaling_available_frequencies"
    const val GOV_CPU7 = "/sys/devices/system/cpu/cpufreq/policy7/scaling_governor"
    const val AVAIL_GOV_CPU7 = "/sys/devices/system/cpu/cpufreq/policy7/scaling_available_governors"

    const val CPU_INPUT_BOOST_MS = "/sys/devices/system/cpu/cpu_boost/input_boost_ms"
    const val CPU_SCHED_BOOST_ON_INPUT = "/sys/devices/system/cpu/cpu_boost/sched_boost_on_input"

    const val MIN_FREQ_GPU = "/sys/class/kgsl/kgsl-3d0/min_clock_mhz"
    const val MAX_FREQ_GPU = "/sys/class/kgsl/kgsl-3d0/max_clock_mhz"
    const val CUR_FREQ_GPU = "/sys/class/kgsl/kgsl-3d0/gpuclk"
    const val AVAIL_FREQ_GPU = "/sys/class/kgsl/kgsl-3d0/freq_table_mhz"
    const val GOV_GPU = "/sys/class/kgsl/kgsl-3d0/devfreq/governor"
    const val AVAIL_GOV_GPU = "/sys/class/kgsl/kgsl-3d0/devfreq/available_governors"
    const val MAX_PWRLEVEL = "/sys/class/kgsl/kgsl-3d0/max_pwrlevel"
    const val MIN_PWRLEVEL = "/sys/class/kgsl/kgsl-3d0/min_pwrlevel"
    const val DEFAULT_PWRLEVEL = "/sys/class/kgsl/kgsl-3d0/default_pwrlevel"
    const val ADRENO_BOOST = "/sys/class/kgsl/kgsl-3d0/devfreq/adrenoboost"
    const val GPU_THROTTLING = "/sys/class/kgsl/kgsl-3d0/throttling"

    enum class Cluster { LITTLE, BIG, PRIME }

    data class ClusterPaths(
        val minFreq: String,
        val maxFreq: String,
        val curFreq: String,
        val availFreq: String,
        val availBoost: String?,
        val gov: String,
        val availGov: String,
    )

    enum class WriteResult { OK, FAILED }

    private fun fileExists(path: String): Boolean {
        return File(path).exists() || RootFile.fileExists(path)
    }

    private var cachedBigPolicy: Int = -1
    fun detectBigPolicy(): Int {
        if (cachedBigPolicy != -1) return cachedBigPolicy
        val found = when {
            fileExists(AVAIL_FREQ_CPU3) -> 3
            fileExists(AVAIL_FREQ_CPU4) -> 4
            fileExists(AVAIL_FREQ_CPU6) -> 6
            else -> 0
        }
        cachedBigPolicy = found
        return found
    }

    fun hasBigCluster(): Boolean = detectBigPolicy() != 0

    private var cachedHasPrime: Boolean? = null
    fun hasPrimeCluster(): Boolean {
        cachedHasPrime?.let { return it }
        val found = fileExists(AVAIL_FREQ_CPU7)
        cachedHasPrime = found
        return found
    }

    private var cachedHasGpu: Boolean? = null
    fun hasGpu(): Boolean {
        cachedHasGpu?.let { return it }
        val found = fileExists(MIN_FREQ_GPU)
        cachedHasGpu = found
        return found
    }

    private var cachedHasCpuInputBoostMs: Boolean? = null
    fun hasCpuInputBoostMs(): Boolean {
        cachedHasCpuInputBoostMs?.let { return it }
        val found = fileExists(CPU_INPUT_BOOST_MS)
        cachedHasCpuInputBoostMs = found
        return found
    }

    private var cachedHasCpuSchedBoostOnInput: Boolean? = null
    fun hasCpuSchedBoostOnInput(): Boolean {
        cachedHasCpuSchedBoostOnInput?.let { return it }
        val found = fileExists(CPU_SCHED_BOOST_ON_INPUT)
        cachedHasCpuSchedBoostOnInput = found
        return found
    }

    fun pathsFor(cluster: Cluster): ClusterPaths? = when (cluster) {
        Cluster.LITTLE -> ClusterPaths(
            MIN_FREQ_CPU0, MAX_FREQ_CPU0, CUR_FREQ_CPU0, AVAIL_FREQ_CPU0, null, GOV_CPU0, AVAIL_GOV_CPU0,
        )

        Cluster.BIG -> when (detectBigPolicy()) {
            3 -> ClusterPaths(MIN_FREQ_CPU3, MAX_FREQ_CPU3, CUR_FREQ_CPU3, AVAIL_FREQ_CPU3, AVAIL_BOOST_CPU3, GOV_CPU3, AVAIL_GOV_CPU3)
            4 -> ClusterPaths(MIN_FREQ_CPU4, MAX_FREQ_CPU4, CUR_FREQ_CPU4, AVAIL_FREQ_CPU4, AVAIL_BOOST_CPU4, GOV_CPU4, AVAIL_GOV_CPU4)
            6 -> ClusterPaths(MIN_FREQ_CPU6, MAX_FREQ_CPU6, CUR_FREQ_CPU6, AVAIL_FREQ_CPU6, AVAIL_BOOST_CPU6, GOV_CPU6, AVAIL_GOV_CPU6)
            else -> null
        }

        Cluster.PRIME -> if (hasPrimeCluster()) {
            ClusterPaths(MIN_FREQ_CPU7, MAX_FREQ_CPU7, CUR_FREQ_CPU7, AVAIL_FREQ_CPU7, null, GOV_CPU7, AVAIL_GOV_CPU7)
        } else {
            null
        }
    }

    private fun readRaw(path: String): String = KernelProrp.getProp(path).trim()

    fun readFreqMHz(path: String): Int {
        val raw = readRaw(path)
        return raw.toIntOrNull()?.let { it / 1000 } ?: 0
    }

    // CPU frequencies are handled in kHz end-to-end (sysfs native unit).
    // Converting to whole MHz loses the remainder (e.g. 1804800 kHz -> 1804 MHz),
    // and writing 1804000 back makes cpufreq clamp max_freq DOWN a full step.
    fun readFreqKHz(path: String): Int {
        val raw = readRaw(path)
        return raw.toIntOrNull() ?: 0
    }

    fun readAvailableFreqKHz(path: String): List<Int> {
        val raw = readRaw(path)
        if (raw.isEmpty()) return emptyList()
        return raw.split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
    }

    fun readAvailableFreqWithBoost(freqPath: String, boostPath: String?): List<Int> {
        val base = readAvailableFreqKHz(freqPath)
        if (boostPath == null) return base.distinct().sorted()
        val boost = readAvailableFreqKHz(boostPath)
        return (base + boost).distinct().sorted()
    }

    fun readGovernor(path: String): String = readRaw(path)

    fun readAvailableGovernors(path: String): List<String> {
        val raw = readRaw(path)
        if (raw.isEmpty()) return emptyList()
        return raw.split(Regex("\\s+")).filter { it.isNotEmpty() }
    }

    fun readGpuFreqMHz(): Int {
        val raw = readRaw(CUR_FREQ_GPU)
        return raw.toLongOrNull()?.let { (it / 1_000_000).toInt() } ?: 0
    }

    fun readGpuMinMax(path: String): Int = readRaw(path).toIntOrNull() ?: 0

    fun readAvailableFreqGPU(): List<Int> {
        val raw = readRaw(AVAIL_FREQ_GPU)
        if (raw.isEmpty()) return emptyList()
        return raw.split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }.distinct().sorted()
    }

    fun readIntNode(path: String): Int? = readRaw(path).toIntOrNull()
    fun readAdrenoBoost(): String = readRaw(ADRENO_BOOST)
    fun readThrottlingEnabled(): Boolean = readRaw(GPU_THROTTLING) == "1"
    fun readCpuSchedBoostOnInputEnabled(): Boolean = readRaw(CPU_SCHED_BOOST_ON_INPUT) == "1"

    private fun writeNode(path: String, value: String): WriteResult =
        if (RootFile.writeFile(path, value)) WriteResult.OK else WriteResult.FAILED

    fun writeFreqCPU(path: String, khz: Int): WriteResult =
        writeNode(path, khz.toString())

    fun writeGovernor(path: String, governor: String): WriteResult =
        writeNode(path, governor)

    fun writeFreqGPU(path: String, mhz: Int): WriteResult =
        writeNode(path, mhz.toString())

    fun writeIntNode(path: String, value: Int): WriteResult =
        writeNode(path, value.toString())

    fun writeAdrenoBoost(value: Int): WriteResult =
        writeNode(ADRENO_BOOST, value.toString())

    fun writeThrottling(enabled: Boolean): WriteResult =
        writeNode(GPU_THROTTLING, if (enabled) "1" else "0")

    fun writeCpuInputBoostMs(ms: Int): WriteResult =
        writeNode(CPU_INPUT_BOOST_MS, ms.toString())

    fun writeCpuSchedBoostOnInput(enabled: Boolean): WriteResult =
        writeNode(CPU_SCHED_BOOST_ON_INPUT, if (enabled) "1" else "0")
}
