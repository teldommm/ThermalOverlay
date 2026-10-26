/**
 * Reads GPU frequency, load, and memory usage across Adreno, Mali, and
 * MediaTek GPUs.
 *
 * Every "which node does this device have" probe is cached only once the
 * shell has actually answered. A failed probe (root not granted yet, shell
 * hiccup) returns null/"" for this tick and is retried on the next one, so a
 * single early failure can't disable a metric until the process restarts.
 */
package com.thermaloverlay.overlay.metrics

import com.thermaloverlay.overlay.shell.RootFile
import com.thermaloverlay.overlay.shell.SysReader

object GpuUtils {
    private const val MTK_MALI_PAGES = "/proc/mtk_mali/gpu_memory"
    private const val MALI_MEMORY_USAGE = "/proc/mali/memory_usage"
    private const val KGSL_PAGE_ALLOC = "/sys/devices/virtual/kgsl/kgsl/page_alloc"
    private const val gpuParamsDirAdreno = "/sys/class/kgsl/kgsl-3d0"
    private const val gpuParamsDirMali = "/sys/class/devfreq/gpufreq"

    private class FreqSource(val path: String, val gedFormat: Boolean)

    private val noFreqSource = FreqSource("", false)

    private var freqSource: FreqSource? = null
    private var gpuLoadPath: String? = null
    private var isAdrenoCache: Boolean? = null
    private var maliDevfreqDir: String? = null
    private var gpuParamsDir: String? = null

    private enum class MtkMem { MALI_TOTAL, MTK_PAGES, MALI_BYTES, NONE }

    private var mtkMemSource: MtkMem? = null
    private var kgslMemAvailable = true

    // --- memory ---

    fun getMemoryUsage(): String? {
        if (PlatformUtils.isMtk()) {
            if (mtkMemSource == null) {
                mtkMemSource = detectMtkMemSource() ?: return "?MB"
            }
            return try {
                when (mtkMemSource) {
                    MtkMem.MALI_TOTAL -> bytesToMb(readMaliTotalBytes())
                    MtkMem.MTK_PAGES -> {
                        val pages = readMtkMaliPages()
                        if (isAllDigits(pages)) (pages.toLong() * 4 / 1024).toString() + "MB" else "?MB"
                    }
                    MtkMem.MALI_BYTES -> bytesToMb(readMaliBytes())
                    else -> "?MB"
                }
            } catch (ex: Exception) {
                "?MB"
            }
        }
        if (!kgslMemAvailable) return null
        val bytes = SysReader.readOrNull(KGSL_PAGE_ALLOC) ?: return null // shell failed: retry later
        val value = bytes.trim().toLongOrNull()
        if (value == null) {
            kgslMemAvailable = false // the node answered but isn't numeric: not supported here
            return null
        }
        return (value / 1024 / 1024).toString() + "MB"
    }

    /** null = undetermined (shell failed), caller retries next tick. */
    private fun detectMtkMemSource(): MtkMem? {
        val total = readMaliTotalBytesOrNull() ?: return null
        return when {
            isAllDigits(total) -> MtkMem.MALI_TOTAL
            isAllDigits(readMtkMaliPages()) -> MtkMem.MTK_PAGES
            isAllDigits(readMaliBytes()) -> MtkMem.MALI_BYTES
            else -> MtkMem.NONE
        }
    }

    private fun bytesToMb(bytes: String): String =
        if (isAllDigits(bytes)) (bytes.toLong() / 1024 / 1024).toString() + "MB" else "?MB"

    private fun isAllDigits(s: String): Boolean = s.isNotEmpty() && s.all { it.isDigit() }

    private fun readMaliTotalBytesOrNull(): String? {
        val text = SysReader.readOrNull(MALI_MEMORY_USAGE) ?: return null
        return parseMaliTotalBytes(text)
    }

    private fun readMaliTotalBytes(): String = readMaliTotalBytesOrNull() ?: ""

    private fun readMtkMaliPages(): String {
        val line = SysReader.read(MTK_MALI_PAGES)
            .trim().split("\n").firstOrNull()?.replace("\t", " ")?.trim() ?: ""
        return if (line.contains(" ")) line.substring(line.indexOf(" ")).trim() else ""
    }

    private fun readMaliBytes(): String {
        val line = SysReader.read(MALI_MEMORY_USAGE)
            .trim().split("\n").firstOrNull()?.replace("\t", " ")?.trim() ?: ""
        if (!line.contains(" ") || !line.contains("bytes") || !line.contains("(")) return ""
        val afterSpace = line.substring(line.indexOf(" ")).trim()
        val open = afterSpace.indexOf("(")
        val b = afterSpace.indexOf("b")
        if (open < 0 || b <= open + 1) return ""
        return afterSpace.substring(open + 1, b).trim()
    }

    /** First token after "(" on the "Total" line of /proc/mali/memory_usage. */
    internal fun parseMaliTotalBytes(text: String): String {
        val line = text.lineSequence().firstOrNull { it.contains("Total") } ?: return ""
        val afterParen = line.substringAfter('(', "")
        return afterParen.trim().substringBefore(' ')
    }

    // --- frequency ---

    /** Current GPU frequency in MHz as a number string, "" if unavailable. */
    fun getGpuFreq(): String {
        val src = resolveFreqSource() ?: return ""
        if (src.path.isEmpty()) return ""
        val raw = SysReader.read(src.path)
        val value = if (src.gedFormat) {
            // "<index> <kHz>" -> MHz
            raw.trim().split(' ').getOrNull(1)?.toLongOrNull()?.div(1000)?.toString() ?: ""
        } else {
            raw.trim()
        }
        if (!isAllDigits(value)) return ""
        // devfreq reports Hz; other nodes already report MHz.
        return if (value.length > 6) value.substring(0, value.length - 6) else value
    }

    private fun resolveFreqSource(): FreqSource? {
        freqSource?.let { return it }
        val dir = getGpuParamsDir() ?: return null
        val candidates = listOf(
            FreqSource("$dir/cur_freq", false),
            FreqSource("/sys/kernel/gpu/gpu_clock", false),
            FreqSource("/sys/kernel/debug/ged/hal/current_freqency", true),
            FreqSource("/sys/kernel/ged/hal/current_freqency", true),
        )
        for (c in candidates) {
            val exists = RootFile.fileExistsOrNull(c.path) ?: return null
            if (exists) {
                freqSource = c
                return c
            }
        }
        freqSource = noFreqSource
        return noFreqSource
    }

    // --- load ---

    /** GPU load in percent, or -1 if unavailable. */
    fun getGpuLoad(): Int {
        if (gpuLoadPath == null) {
            val paths = arrayOf(
                "/sys/kernel/gpu/gpu_busy",
                "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
                "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
                "/sys/class/kgsl/kgsl-3d0/gpuload",
                "/sys/class/devfreq/gpufreq/mali_ondemand/utilisation",
                "/sys/kernel/debug/ged/hal/gpu_utilization",
                "/sys/kernel/ged/hal/gpu_utilization",
                "/sys/module/ged/parameters/gpu_loading"
            )
            var found = ""
            for (p in paths) {
                val exists = RootFile.fileExistsOrNull(p) ?: return -1 // undetermined: retry
                if (exists) {
                    found = p
                    break
                }
            }
            gpuLoadPath = found
        }

        val path = gpuLoadPath ?: return -1
        if (path.isEmpty()) return -1
        return parseGpuLoad(SysReader.read(path))
    }

    internal fun parseGpuLoad(raw: String): Int {
        return raw.replace("%", "").trim().split(" ")[0].toIntOrNull() ?: -1
    }

    // --- platform detection ---

    private fun isAdrenoGPU(): Boolean? {
        isAdrenoCache?.let { return it }
        val r = RootFile.dirExistsOrNull(gpuParamsDirAdreno) ?: return null
        isAdrenoCache = r
        return r
    }

    private fun getMaliDevfreqDir(): String? {
        maliDevfreqDir?.let { return it }

        val standard = RootFile.dirExistsOrNull(gpuParamsDirMali) ?: return null
        if (standard) {
            maliDevfreqDir = gpuParamsDirMali
            return gpuParamsDirMali
        }

        val cmd = "for f in /sys/devices/platform/*mali/devfreq/*mali/available_governors " +
            "/sys/devices/platform/*mali/devfreq/*/available_governors " +
            "/sys/devices/platform/soc/*mali/devfreq/*mali/available_governors " +
            "/sys/devices/platform/soc/*mali/devfreq/*/available_governors; do " +
            "[ -f \"\$f\" ] && dirname \"\$f\" && break; " +
            "done; echo done"
        val out = com.thermaloverlay.overlay.shell.KeepShellPublic.doCmdSync(cmd).trim()
        if (out == com.thermaloverlay.overlay.shell.KeepShell.ERROR) return null
        val path = out.lines().firstOrNull { it.startsWith("/") }.orEmpty()
        maliDevfreqDir = path
        return path
    }

    private fun getGpuParamsDir(): String? {
        gpuParamsDir?.let { return it }
        val dir = when (isAdrenoGPU() ?: return null) {
            true -> "$gpuParamsDirAdreno/devfreq"
            false -> getMaliDevfreqDir() ?: return null
        }
        gpuParamsDir = dir
        return dir
    }
}
