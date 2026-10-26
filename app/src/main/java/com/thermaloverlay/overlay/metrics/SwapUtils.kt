/**
 * Reads swap/ZRAM fill percentage from /proc/swaps, zram sysfs, or
 * /proc/meminfo, in that order.
 */
package com.thermaloverlay.overlay.metrics

import com.thermaloverlay.overlay.shell.SysReader

object SwapUtils {
    fun getZramFillPercent(): Int {
        readFromProcSwaps()?.let { return it }
        readFromZramMmStat()?.let { return it }
        return readFromMeminfo()
    }

    private fun readFromProcSwaps(): Int? = parseProcSwaps(SysReader.read("/proc/swaps"))

    internal fun parseProcSwaps(output: String): Int? {
        if (output.isEmpty()) return null

        val lines = output.trim().split("\n").drop(1)
        var totalSize = 0L
        var totalUsed = 0L
        for (line in lines) {
            val cols = line.trim().split(Regex("\\s+"))
            if (cols.size < 4) continue
            val size = cols[2].toLongOrNull() ?: continue
            val used = cols[3].toLongOrNull() ?: continue
            if (size <= 0) continue
            totalSize += size
            totalUsed += used
        }
        if (totalSize <= 0) return null
        return ((totalUsed * 100) / totalSize).toInt()
    }

    private fun readFromZramMmStat(): Int? {
        val disksize = SysReader.read("/sys/block/zram0/disksize").trim().toLongOrNull()
        if (disksize == null || disksize <= 0) return null

        val stat = SysReader.read("/sys/block/zram0/mm_stat").trim()
        val memUsed = stat.split(Regex("\\s+")).getOrNull(2)?.toLongOrNull() ?: return null

        return ((memUsed * 100) / disksize).toInt()
    }

    private fun readFromMeminfo(): Int {
        val meminfo = SysReader.read("/proc/meminfo")
        val swapTotal = extractKb(meminfo, "SwapTotal") ?: return 0
        val swapFree = extractKb(meminfo, "SwapFree") ?: 0L
        if (swapTotal <= 0) return 0
        return (((swapTotal - swapFree) * 100) / swapTotal).toInt()
    }

    private fun extractKb(meminfo: String, key: String): Long? {
        val line = meminfo.lineSequence().firstOrNull { it.startsWith("$key:") } ?: return null
        val digits = line.filter { it.isDigit() }
        return digits.toLongOrNull()
    }
}
