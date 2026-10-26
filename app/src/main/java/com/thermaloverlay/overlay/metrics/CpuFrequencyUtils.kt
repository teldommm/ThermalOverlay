/**
 * Reads CPU topology (cores, cpufreq clusters), current cluster frequencies
 * and DDR frequency.
 */
package com.thermaloverlay.overlay.metrics

import com.thermaloverlay.overlay.shell.NativeKernelProp
import com.thermaloverlay.overlay.shell.RootFile
import com.thermaloverlay.overlay.shell.SysReader
import java.io.File

/**
 * Result of a cpufreq cluster scan.
 *
 * @param clusters cpufreq policies found, each as the list of its CPUs
 *                 (offline members are included — related_cpus lists them).
 * @param missing  CPUs that belong to no discovered policy. This is the case
 *                 for a cluster that is completely offline: the kernel removes
 *                 its cpufreq directory, so its membership is unknown.
 */
class ClusterScan(val clusters: List<List<Int>>, val missing: List<Int>) {
    /** True when every present CPU is covered by a discovered policy. */
    val isComplete: Boolean get() = missing.isEmpty()
}

class CpuFrequencyUtils(
    // Overridable so tests can point it at a fake sysfs tree.
    private val cpuRoot: String = "/sys/devices/system/cpu",
) {
    private var coreCount = -1

    @Volatile
    private var lastScan: ClusterScan? = null

    fun getCoreCount(): Int {
        if (coreCount > 0) return coreCount
        // "present" looks like "0-7" and, unlike the cpuN directory walk, is
        // unaffected by hotplug.
        val present = SysReader.read("$cpuRoot/present")
        val fromPresent = parseCpuList(present).maxOrNull()?.plus(1) ?: 0
        val count = if (fromPresent > 0) fromPresent else countCpuDirs()
        if (count > 0) coreCount = count
        return count
    }

    private fun countCpuDirs(): Int {
        var cores = 0
        while (File("$cpuRoot/cpu$cores").exists()) cores++
        return cores
    }

    /**
     * Returns the cluster layout. A complete layout is cached; an incomplete
     * one (some CPUs offline at scan time) is re-scanned on every call — it is
     * only a handful of File.exists() checks — so a cluster shows up as soon
     * as the kernel brings it back.
     */
    @Synchronized
    fun getClusters(): ClusterScan {
        lastScan?.let { if (it.isComplete) return it }

        val n = getCoreCount()
        val covered = BooleanArray(maxOf(n, 0))
        val clusters = ArrayList<List<Int>>()
        for (cpu in 0 until n) {
            if (covered[cpu]) continue
            val path = "$cpuRoot/cpu$cpu/cpufreq/related_cpus"
            // No cpufreq directory = this CPU's policy is offline right now.
            // Skip it (don't stop the whole scan like walking until the first
            // gap would).
            if (!File(path).exists()) continue
            val members = parseCpuList(SysReader.read(path)).filter { it in 0 until n }
            if (members.isEmpty()) continue
            members.forEach { covered[it] = true }
            clusters.add(members)
        }
        val missing = (0 until n).filter { !covered[it] }
        val scan = ClusterScan(clusters, missing)
        // Never leave the result of a blank pre-root read cached: an empty
        // scan has everything in `missing`, hence isComplete == false.
        lastScan = scan
        return scan
    }

    /**
     * Current frequency of a cluster in kHz, or -1 if unknown. Any online CPU
     * of the policy can answer, so one offline member doesn't blank the reading.
     */
    fun getClusterFrequencyKHz(cluster: List<Int>): Long {
        for (cpu in cluster) {
            val v = NativeKernelProp.readLong("$cpuRoot/cpu$cpu/cpufreq/scaling_cur_freq")
            if (v > -1) return v
        }
        return -1
    }

    /** Current frequency of one CPU in kHz, or -1 (offline / unreadable). */
    fun getCoreFrequencyKHz(core: Int): Long {
        return NativeKernelProp.readLong("$cpuRoot/cpu$core/cpufreq/scaling_cur_freq")
    }

    // --- DDR ---

    private var debugfsMount: String? = null
    private var ddrPath: String? = null
    private var ddrPathIsMccc = false

    private fun getDebugfsMount(): String? {
        debugfsMount?.let { return it }
        val mounts = SysReader.readOrNull("/proc/mounts") ?: return null
        val line = mounts.lineSequence().firstOrNull { it.split(' ').getOrNull(2) == "debugfs" }
        val mount = line?.split(' ')?.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: "/sys/kernel/debug"
        debugfsMount = mount
        return mount
    }

    /** DDR frequency in MHz, "" if unavailable. */
    fun getDdrFrequency(): String {
        if (ddrPath == null) {
            // Probe results are cached only when the shell actually answered;
            // a failed probe (root not granted yet) is retried on a later tick.
            val debugfs = getDebugfsMount() ?: return ""
            val mccc = "$debugfs/clk/measure_only_mccc_clk/clk_measure"
            val dcvs = "/sys/devices/system/cpu/bus_dcvs/DDR/cur_freq"
            val hasMccc = RootFile.fileExistsOrNull(mccc) ?: return ""
            if (hasMccc) {
                ddrPath = mccc
                ddrPathIsMccc = true
            } else {
                val hasDcvs = RootFile.fileExistsOrNull(dcvs) ?: return ""
                ddrPath = if (hasDcvs) dcvs else ""
                ddrPathIsMccc = false
            }
        }
        val path = ddrPath
        if (path.isNullOrEmpty()) return ""

        val raw = SysReader.read(path).trim().toLongOrNull() ?: return ""
        return if (ddrPathIsMccc) (raw / 500_000L).toString() else (raw / 500L).toString()
    }

    companion object {
        /** Parses "0-3,6" or "0 1 2 3" into a sorted list of CPU numbers. */
        fun parseCpuList(raw: String): List<Int> {
            val result = sortedSetOf<Int>()
            for (token in raw.trim().split(Regex("[\\s,]+"))) {
                if (token.isEmpty()) continue
                val range = token.split('-')
                if (range.size == 2) {
                    val a = range[0].toIntOrNull()
                    val b = range[1].toIntOrNull()
                    if (a != null && b != null && a <= b && b - a < 1024) {
                        for (i in a..b) result.add(i)
                    }
                } else {
                    token.toIntOrNull()?.let { result.add(it) }
                }
            }
            return result.toList()
        }
    }
}
