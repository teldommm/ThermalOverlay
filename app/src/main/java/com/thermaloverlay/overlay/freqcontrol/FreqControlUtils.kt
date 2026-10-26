/**
 * sysfs paths and read/write helpers for CPU cluster and GPU (Adreno/kgsl)
 * frequency control, via this project's own root shell.
 *
 * Writes are verified: the node is read back and the write is repeated a few
 * times if the value didn't stick (some kernels reject a write transiently,
 * and vendor daemons may overwrite it a moment later). Min/max writes also
 * move the opposite bound first when needed — on many kernels a max below the
 * current min (or a min above the current max) is rejected with EINVAL, which
 * used to look like "the frequency didn't apply, select it again".
 */
package com.thermaloverlay.overlay.freqcontrol

import com.thermaloverlay.overlay.shell.RootFile
import com.thermaloverlay.overlay.shell.SysReader
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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

    // --- node detection -------------------------------------------------
    //
    // A "does this node exist" answer is cached only when it was actually
    // determined. If the root shell failed (null), nothing is cached and the
    // next call asks again — otherwise a probe made before the root grant
    // would hide the cluster/GPU card until the process restarts.

    private val existsCache = ConcurrentHashMap<String, Boolean>()

    private fun exists(path: String): Boolean? {
        existsCache[path]?.let { return it }
        val result = if (File(path).exists()) true else RootFile.fileExistsOrNull(path)
        if (result != null) existsCache[path] = result
        return result
    }

    /** Policy number of the "big" cluster (3, 4 or 6), 0 if none, -1 if unknown yet. */
    fun detectBigPolicy(): Int {
        for ((policy, path) in listOf(3 to AVAIL_FREQ_CPU3, 4 to AVAIL_FREQ_CPU4, 6 to AVAIL_FREQ_CPU6)) {
            when (exists(path)) {
                true -> return policy
                null -> return -1
                false -> Unit
            }
        }
        return 0
    }

    fun hasBigCluster(): Boolean = detectBigPolicy() > 0

    fun hasPrimeCluster(): Boolean = exists(AVAIL_FREQ_CPU7) == true

    fun hasGpu(): Boolean = exists(MIN_FREQ_GPU) == true

    fun hasCpuInputBoostMs(): Boolean = exists(CPU_INPUT_BOOST_MS) == true

    fun hasCpuSchedBoostOnInput(): Boolean = exists(CPU_SCHED_BOOST_ON_INPUT) == true

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

    // --- parsing --------------------------------------------------------

    fun parseKHz(raw: String?): Int = raw?.trim()?.toIntOrNull() ?: 0

    /** kHz node -> whole MHz (display only). */
    fun parseMHzFromKHz(raw: String?): Int = parseKHz(raw) / 1000

    fun parseGpuCurMHz(raw: String?): Int = raw?.trim()?.toLongOrNull()?.let { (it / 1_000_000).toInt() } ?: 0

    fun parseIntList(raw: String?): List<Int> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
    }

    fun parseWordList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }

    // --- reads ----------------------------------------------------------

    /** Reads many nodes with at most one shell round trip. */
    fun readNodes(paths: Collection<String>): Map<String, String> =
        SysReader.readManyMap(paths.toList())

    private fun readRaw(path: String): String = SysReader.read(path).trim()

    fun readFreqKHz(path: String): Int = parseKHz(readRaw(path))

    fun readIntNode(path: String): Int? = readRaw(path).toIntOrNull()

    private var cachedCoreCount: Int = -1

    fun cpuCoreCount(): Int {
        if (cachedCoreCount != -1) return cachedCoreCount
        // "present" looks like "0-7"; parse the upper bound. Fall back to the
        // JVM view (may under-report if cores are already offline, so it is
        // only a last resort).
        val raw = SysReader.readOrNull("/sys/devices/system/cpu/present") ?: return Runtime.getRuntime().availableProcessors()
        val count = raw.substringAfterLast('-').toIntOrNull()?.plus(1)
        if (count == null) return Runtime.getRuntime().availableProcessors()
        cachedCoreCount = count
        return count
    }

    fun coreOnlinePath(core: Int) = "/sys/devices/system/cpu/cpu$core/online"

    // cpu0 usually has no "online" node (the boot core can't be offlined).
    fun isCoreToggleable(core: Int): Boolean = exists(coreOnlinePath(core)) == true

    /** Online state of every core; cores without an "online" node count as online. */
    fun readAllCoresOnline(): List<Boolean> {
        val n = cpuCoreCount()
        val toggleable = (0 until n).map { isCoreToggleable(it) }
        val values = readNodes((0 until n).filter { toggleable[it] }.map { coreOnlinePath(it) })
        return (0 until n).map { core ->
            if (!toggleable[core]) true else values[coreOnlinePath(core)]?.trim() == "1"
        }
    }

    // --- writes ---------------------------------------------------------

    private const val WRITE_ATTEMPTS = 3
    private const val SETTLE_MS = 40L
    private const val RETRY_MS = 80L

    private fun sameValue(readBack: String, wanted: String): Boolean {
        val a = readBack.trim()
        val b = wanted.trim()
        if (a == b) return true
        val na = a.toLongOrNull()
        val nb = b.toLongOrNull()
        return na != null && na == nb
    }

    /** How nodes are read and written. Replaced by a fake kernel in tests. */
    internal interface NodeIO {
        fun read(path: String): String
        fun write(path: String, value: String): Boolean
    }

    private object ShellNodeIO : NodeIO {
        override fun read(path: String): String = SysReader.read(path)
        override fun write(path: String, value: String): Boolean = RootFile.writeFile(path, value)
    }

    @Volatile
    internal var nodes: NodeIO = ShellNodeIO

    @Volatile
    internal var sleeper: (Long) -> Unit = { ms ->
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** Writes [value] to [path] and confirms it by reading the node back. */
    fun writeVerified(path: String, value: String): WriteResult {
        for (attempt in 0 until WRITE_ATTEMPTS) {
            nodes.write(path, value)
            sleeper(SETTLE_MS)
            if (sameValue(nodes.read(path), value)) return WriteResult.OK
            if (attempt < WRITE_ATTEMPTS - 1) sleeper(RETRY_MS * (attempt + 1))
        }
        return WriteResult.FAILED
    }

    private fun readLong(path: String): Long? = nodes.read(path).trim().toLongOrNull()

    /**
     * Sets a max limit. If it is below the current min, the min is lowered to
     * the same value first so the kernel accepts it.
     */
    private fun setMax(minPath: String, maxPath: String, value: Int): WriteResult {
        val curMin = readLong(minPath)
        if (curMin != null && value < curMin) writeVerified(minPath, value.toString())
        return writeVerified(maxPath, value.toString())
    }

    /**
     * Sets a min limit. If it is above the current max, the max is raised to
     * the same value first so the kernel accepts it.
     */
    private fun setMin(minPath: String, maxPath: String, value: Int): WriteResult {
        val curMax = readLong(maxPath)
        if (curMax != null && value > curMax) writeVerified(maxPath, value.toString())
        return writeVerified(minPath, value.toString())
    }

    fun setCpuMaxKHz(paths: ClusterPaths, khz: Int): WriteResult = setMax(paths.minFreq, paths.maxFreq, khz)

    fun setCpuMinKHz(paths: ClusterPaths, khz: Int): WriteResult = setMin(paths.minFreq, paths.maxFreq, khz)

    fun setGpuMaxMHz(mhz: Int): WriteResult = setMax(MIN_FREQ_GPU, MAX_FREQ_GPU, mhz)

    fun setGpuMinMHz(mhz: Int): WriteResult = setMin(MIN_FREQ_GPU, MAX_FREQ_GPU, mhz)

    fun writeGovernor(path: String, governor: String): WriteResult = writeVerified(path, governor)

    fun writeIntNode(path: String, value: Int): WriteResult = writeVerified(path, value.toString())

    fun writeAdrenoBoost(value: Int): WriteResult = writeVerified(ADRENO_BOOST, value.toString())

    fun writeThrottling(enabled: Boolean): WriteResult = writeVerified(GPU_THROTTLING, if (enabled) "1" else "0")

    fun writeCpuInputBoostMs(ms: Int): WriteResult = writeVerified(CPU_INPUT_BOOST_MS, ms.toString())

    fun writeCpuSchedBoostOnInput(enabled: Boolean): WriteResult =
        writeVerified(CPU_SCHED_BOOST_ON_INPUT, if (enabled) "1" else "0")

    fun writeCoreOnline(core: Int, online: Boolean): WriteResult =
        writeVerified(coreOnlinePath(core), if (online) "1" else "0")
}
