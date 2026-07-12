/**
 * Reads GPU frequency, load, and memory usage across Adreno, Mali, and
 * MediaTek GPUs.
 */
package com.thermaloverlay.overlay.metrics

import com.thermaloverlay.overlay.shell.KeepShellPublic
import com.thermaloverlay.overlay.shell.KernelProrp
import com.thermaloverlay.overlay.shell.RootFile

object GpuUtils {
    private var gpuLoadPath: String? = null
    private var gpuFreqCmd: String? = null
    private var gpuMemoryCmd1 = "cat /proc/mali/memory_usage | grep \"Total\" | cut -f2 -d \"(\" | cut -f1 -d \" \""
    private var kgslMemAvailable = true

    private const val MTK_MALI_PAGES = "/proc/mtk_mali/gpu_memory"
    private const val MALI_MEMORY_USAGE = "/proc/mali/memory_usage"
    private var mtkMemSource: String? = null

    private var platform: String? = null
    private var isAdrenoGPUCache: Boolean? = null
    private var isMaliGPUCache: Boolean? = null
    private const val gpuParamsDirAdreno = "/sys/class/kgsl/kgsl-3d0"
    private const val gpuParamsDirMali = "/sys/class/devfreq/gpufreq"
    private var gpuParamsDirMaliDevfreq: String? = null
    private var gpuParamsDir: String? = null

    private fun isMTK(): Boolean {
        if (platform == null) {
            platform = PlatformUtils().getCPUName()
        }
        return platform!!.startsWith("mt")
    }

    fun getMemoryUsage(): String? {
        if (isMTK()) {

            if (mtkMemSource == null) {
                mtkMemSource = when {
                    isAllDigits(KeepShellPublic.doCmdSync(gpuMemoryCmd1).trim()) -> gpuMemoryCmd1
                    isAllDigits(readMtkMaliPages()) -> MTK_MALI_PAGES
                    isAllDigits(readMaliBytes()) -> MALI_MEMORY_USAGE
                    else -> ""
                }
            }
            val src = mtkMemSource
            if (src.isNullOrEmpty()) return "?MB"
            return try {
                when (src) {
                    MTK_MALI_PAGES -> {
                        val pages = readMtkMaliPages()
                        if (isAllDigits(pages)) (pages.toLong() * 4 / 1024).toString() + "MB" else "?MB"
                    }
                    MALI_MEMORY_USAGE -> {
                        val bytes = readMaliBytes()
                        if (isAllDigits(bytes)) (bytes.toLong() / 1024 / 1024).toString() + "MB" else "?MB"
                    }
                    else -> {
                        val bytes = KeepShellPublic.doCmdSync(src).trim()
                        if (isAllDigits(bytes)) (bytes.toLong() / 1024 / 1024).toString() + "MB" else "?MB"
                    }
                }
            } catch (ex: Exception) {
                "?MB"
            }
        } else if (kgslMemAvailable) {
            val bytes = KeepShellPublic.doCmdSync("cat /sys/devices/virtual/kgsl/kgsl/page_alloc")
            try {
                return (bytes.toLong() / 1024 / 1024).toString() + "MB"
            } catch (ex: Exception) {
                kgslMemAvailable = false
            }
        }
        return null
    }

    private fun isAllDigits(s: String): Boolean = s.isNotEmpty() && s.all { it.isDigit() }

    private fun readMtkMaliPages(): String {
        val line = KernelProrp.getProp(MTK_MALI_PAGES)
            .trim().split("\n").firstOrNull()?.replace("\t", " ")?.trim() ?: ""
        return if (line.contains(" ")) line.substring(line.indexOf(" ")).trim() else ""
    }

    private fun readMaliBytes(): String {
        val line = KernelProrp.getProp(MALI_MEMORY_USAGE)
            .trim().split("\n").firstOrNull()?.replace("\t", " ")?.trim() ?: ""
        if (!line.contains(" ") || !line.contains("bytes") || !line.contains("(")) return ""
        val afterSpace = line.substring(line.indexOf(" ")).trim()
        val open = afterSpace.indexOf("(")
        val b = afterSpace.indexOf("b")
        if (open < 0 || b <= open + 1) return ""
        return afterSpace.substring(open + 1, b).trim()
    }

    fun getGpuFreq(): String {
        if (gpuFreqCmd == null) {
            val path1 = getGpuParamsDir() + "/cur_freq"
            val path2 = "/sys/kernel/gpu/gpu_clock"
            val path3 = "/sys/kernel/debug/ged/hal/current_freqency"
            val path4 = "/sys/kernel/ged/hal/current_freqency"
            gpuFreqCmd = when {
                RootFile.fileExists(path1) -> "cat $path1"
                RootFile.fileExists(path2) -> "cat $path2"
                RootFile.fileExists(path3) -> "echo \$((`cat $path3 | cut -f2 -d ' '` / 1000))"
                RootFile.fileExists(path4) -> "echo \$((`cat $path4 | cut -f2 -d ' '` / 1000))"
                else -> ""
            }
        }

        if (gpuFreqCmd!!.isEmpty()) return ""
        val freq = KeepShellPublic.doCmdSync(gpuFreqCmd!!)
        return if (freq.length > 6) freq.substring(0, freq.length - 6) else freq
    }

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
            gpuLoadPath = paths.firstOrNull { RootFile.fileExists(it) } ?: ""
        }

        if (gpuLoadPath!!.isEmpty()) return -1
        val load = KernelProrp.getProp(gpuLoadPath!!)
        return try {
            load.replace("%", "").trim().split(" ")[0].toInt()
        } catch (ex: Exception) {
            -1
        }
    }

    fun isAdrenoGPU(): Boolean {
        if (isAdrenoGPUCache == null) {
            isAdrenoGPUCache = RootFile.dirExists(gpuParamsDirAdreno)
        }
        return isAdrenoGPUCache!!
    }

    private fun isMaliGPU(): Boolean {
        if (isMaliGPUCache == null) {
            isMaliGPUCache = RootFile.dirExists(gpuParamsDirMali) || getMaliDevfreqDir().isNotEmpty()
        }
        return isMaliGPUCache!!
    }

    private fun getMaliDevfreqDir(): String {
        gpuParamsDirMaliDevfreq?.let { return it }

        if (RootFile.dirExists(gpuParamsDirMali)) {
            gpuParamsDirMaliDevfreq = gpuParamsDirMali
            return gpuParamsDirMaliDevfreq!!
        }

        val cmd = "for f in /sys/devices/platform/*mali/devfreq/*mali/available_governors " +
            "/sys/devices/platform/*mali/devfreq/*/available_governors " +
            "/sys/devices/platform/soc/*mali/devfreq/*mali/available_governors " +
            "/sys/devices/platform/soc/*mali/devfreq/*/available_governors; do " +
            "[ -f \"\$f\" ] && dirname \"\$f\" && break; " +
            "done"
        val path = KeepShellPublic.doCmdSync(cmd).trim()
        gpuParamsDirMaliDevfreq = if (path.isNotEmpty() && RootFile.dirExists(path)) path else ""
        return gpuParamsDirMaliDevfreq!!
    }

    private fun getGpuParamsDir(): String {
        if (gpuParamsDir == null) {
            gpuParamsDir = when {
                isAdrenoGPU() -> "$gpuParamsDirAdreno/devfreq"
                isMaliGPU() -> getMaliDevfreqDir()
                else -> ""
            }
        }
        return gpuParamsDir!!
    }
}
